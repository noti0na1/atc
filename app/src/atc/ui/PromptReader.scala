package atc.ui

import org.jline.keymap.KeyMap
import org.jline.reader.{
  Binding,
  Candidate,
  Completer,
  EOFError,
  LineReader,
  LineReaderBuilder,
  ParsedLine,
  Parser,
  Reference,
  Widget,
}
import org.jline.reader.impl.{DefaultHighlighter, DefaultParser, LineReaderImpl}
import org.jline.reader.impl.history.DefaultHistory
import org.jline.terminal.Terminal
import org.jline.utils.{AttributedString, AttributedStringBuilder, AttributedStyle, InfoCmp}

import java.nio.file.attribute.{PosixFileAttributeView, PosixFilePermissions}
import java.nio.file.{FileAlreadyExistsException, Files, LinkOption, Path}
import scala.jdk.CollectionConverters.*

import Ansi.{Bold, Cyan}

/** The JLine line reader for the prompt and for typed answers: history, ghost
  * text for the predicted next message, slash-command completion, multi-line
  * input ([[Continuation]]) and the key bindings that go with them. A changed
  * buffer and focus reports go to `alerts`. */
private[ui] final class PromptReader(screen: Screen, historyPath: Path, alerts: Alerts):
  private val terminal: Terminal = screen.terminal
  private val plain = screen.plain

  /** The predicted next message (`suggest`), drawn as ghost text after what
    * is typed as long as that is a prefix of it. */
  @volatile var suggestion: Option[String] = None
  /** What the ghost text would add to `typed`: the rest of the suggestion. */
  private def ghost(typed: String): Option[String] =
    suggestion.filter(s => s.length > typed.length && s.startsWith(typed)).map(_.drop(typed.length))

  /** The line reader's highlighter, with the ghost text appended in faint
    * style. The cursor is positioned from the buffer, not from this string,
    * so the extra text is display only. */
  private object ghostHighlighter extends DefaultHighlighter:
    /** The buffer at the last redraw: a change means the user is typing. */
    var seen = ""
    override def highlight(r: LineReader, buffer: String): AttributedString =
      if buffer != seen then
        seen = buffer
        alerts.touch()
      val base = super.highlight(r, buffer).nn
      ghost(buffer) match
        case Some(rest) if !plain =>
          AttributedStringBuilder().append(base).styled(AttributedStyle.DEFAULT.faint(), rest).toAttributedString.nn
        case _ => base

  /** Tab completion for slash commands, by plain string matching: given the
    * words typed so far (the last one possibly empty or partial), the values
    * the last word may take. The app fills this in with its commands and
    * their arguments; nothing else on the line is completed. */
  @volatile var completions: List[String] => List[String] = _ => Nil
  private val commandCompleter: Completer = (_, line, candidates) =>
    val words = line.words.asScala.toList
    if words.headOption.exists(_.startsWith("/")) then
      completions(words.take(line.wordIndex + 1)).foreach(c => candidates.add(Candidate(c)))

  /** `readBlock` is reading: Enter continues until an empty line. */
  @volatile var blockMode = false
  /** Multi-line input ([[Continuation]]): on Enter, an `EOFError` tells JLine
    * to insert a newline (indented by the open-bracket depth) instead of
    * accepting; everything else is the default word splitting the completer uses. */
  private val continuationParser: Parser = new DefaultParser:
    override def parse(line: String, cursor: Int, context: Parser.ParseContext): ParsedLine =
      if context == Parser.ParseContext.ACCEPT_LINE then
        Continuation.pending(line, blockMode).foreach(depth => throw EOFError(-1, -1, "incomplete", "", depth, null))
      super.parse(line, cursor, context)

  val reader: LineReader =
    LineReaderBuilder.builder()
      .terminal(terminal)
      .history(DefaultHistory())
      .highlighter(ghostHighlighter)
      .completer(commandCompleter)
      .parser(continuationParser)
      .variable(LineReader.HISTORY_FILE, historyPath)
      .variable(LineReader.INDENTATION, 2)
      .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
      .build()

  // Continuation lines: a bar under the prompt, padded (`%P`) to the prompt's
  // width. ASCII, because JLine turns box glyphs in a prompt into DEC
  // line-drawing escapes.
  reader.setVariable(LineReader.SECONDARY_PROMPT_PATTERN, "%P " + screen.styled("| ", Cyan, Bold))

  if !plain then
    val cycle: Widget = () =>
      if reader.getBuffer.length == 0 then
        reader.getBuffer.write(PromptReader.CycleModeLine)
        reader.callWidget(LineReader.ACCEPT_LINE)
      true
    reader.getWidgets.put("atc-cycle-mode", cycle)
    // Shift-Tab: `kcbt` from the terminfo when present, plus the usual CSI Z sequence.
    val keyMap = reader.getKeyMaps.get(LineReader.MAIN)
    if keyMap != null then
      val seqs =
        (Option(KeyMap.key(terminal, InfoCmp.Capability.back_tab)).toList
          :+ "\u001b[Z").distinct.filter(_.nonEmpty)
      keyMap.bind(Reference("atc-cycle-mode"), seqs*)
      // Tab and → accept the ghost text (when the cursor is at the end and
      // there is some); otherwise they do what they did before.
      def accepting(name: String, previous: Binding | Null): Unit =
        val widget: Widget = () =>
          val buf = reader.getBuffer
          ghost(buf.toString) match
            case Some(rest) if buf.cursor == buf.length => buf.write(rest); true
            case _ =>
              previous match
                case r: Reference => reader.callWidget(r.name); true
                case w: Widget => w.apply()
                case _ => true
        reader.getWidgets.put(name, widget)
      accepting("atc-accept-suggestion-tab", keyMap.getBound("\t"))
      accepting("atc-accept-suggestion-right", Reference(LineReader.FORWARD_CHAR))
      keyMap.bind(Reference("atc-accept-suggestion-tab"), "\t")
      val rights = (Option(KeyMap.key(terminal, InfoCmp.Capability.key_right)).toList ++ List("\u001b[C", "\u001bOC"))
        .distinct.filter(_.nonEmpty)
      keyMap.bind(Reference("atc-accept-suggestion-right"), rights*)

  // Multi-line input by key (the parser handles open brackets, block mode and
  // pastes): Enter on a line ending in `\` turns the backslash into a newline
  // (typed by hand, or how terminals set up to send `\`+Enter for Shift+Enter
  // arrive), and a Shift+Enter / Alt+Enter the terminal reports as such
  // inserts one directly. Bound in every mode: piped input uses it too.
  locally:
    val keyMap = reader.getKeyMaps.get(LineReader.MAIN)
    if keyMap != null then
      val newline: Widget = () => { reader.getBuffer.write("\n"); true }
      val enter: Widget = () =>
        val buf = reader.getBuffer
        if buf.length > 0 && buf.atChar(buf.length - 1) == '\\' then
          buf.cursor(buf.length)
          buf.backspace()
          buf.write("\n")
          // VS Code's Shift+Enter (as Claude Code's terminal setup binds it)
          // sends `\`, CR, LF: drop the LF, or it would submit the new empty line.
          if reader.getLastBinding == "\r" then
            reader match
              case impl: LineReaderImpl => if impl.peekCharacter(50) == '\n' then impl.readCharacter()
              case _ => ()
          true
        else
          reader.callWidget(LineReader.ACCEPT_LINE)
          true
      reader.getWidgets.put("atc-newline", newline)
      reader.getWidgets.put("atc-enter", enter)
      keyMap.bind(Reference("atc-enter"), "\r", "\n")
      // Shift+Enter as CSI u (kitty, Ghostty, WezTerm, foot, iTerm2 with it
      // on), as xterm's modifyOtherKeys, and Alt/Option+Enter as ESC CR.
      keyMap.bind(Reference("atc-newline"), "\u001b[13;2u", "\u001b[27;2;13~", "\u001b\r")

  if alerts.focusSupported then
    reader.getWidgets.put(LineReader.FOCUS_IN, (() => { alerts.focusChanged(true); true }): Widget)
    reader.getWidgets.put(LineReader.FOCUS_OUT, (() => { alerts.focusChanged(false); true }): Widget)
    // Called in raw mode: after `readLine` enters it, and on Enter before it leaves it.
    reader.getWidgets.put(LineReader.CALLBACK_INIT, (() => { alerts.reportFocus(true); true }): Widget)
    reader.getWidgets.put(LineReader.CALLBACK_FINISH, (() => { alerts.reportFocus(false); true }): Widget)

  /** Read a line with `typed` already in the buffer (the turn's type-ahead). */
  def read(prompt: String, typed: String): String =
    ghostHighlighter.seen = typed
    try reader.readLine(prompt, null: String | Null, null: org.jline.reader.MaskingCallback | Null, typed)
    finally alerts.reportFocus(false) // Ctrl-C and Ctrl-D leave without `callback-finish`

  /** Read a typed answer; `None` when it is empty or cancelled. */
  def readAnswer(prompt: String): Option[String] = PromptReader.readAnswer(read(prompt, ""))

  /** Redraw the prompt (new ghost text), under the reader's lock and only while it is reading. */
  def redisplay(): Unit =
    try reader.callWidget(LineReader.REDISPLAY)
    catch case _: IllegalStateException => ()

  def saveHistory(): Unit =
    try reader.getHistory.save()
    catch case _: Exception => ()

private[atc] object PromptReader:
  /** The line `readLine` returns when the user presses Shift-Tab on an empty
    * prompt: the app treats it as the `/mode` command (cycle the sandbox mode). */
  val CycleModeLine: String = "/mode"

  /** Cancel the input field and consume JLine's interrupt before another prompt reads. */
  private[atc] def readAnswer(read: => String): Option[String] =
    try Some(read).map(_.trim).filter(_.nonEmpty)
    catch
      case _: org.jline.reader.UserInterruptException =>
        Thread.interrupted()
        None
      case _: org.jline.reader.EndOfFileException => None

  /** Prepare the prompt-history file without following a final symlink and
    * make it owner-only on POSIX systems: user prompts can contain secrets.
    * Returning a path under the resolved parent also prevents a parent symlink
    * from being swapped after this check. */
  private[atc] def secureHistoryFile(path: Path): Path =
    val absolute = path.toAbsolutePath.nn.normalize.nn
    val parent = Option(absolute.getParent).getOrElse(
      throw IllegalArgumentException(s"history path has no parent: $path")
    )
    Files.createDirectories(parent)
    val resolved = parent.toRealPath().nn.resolve(absolute.getFileName.nn).nn
    if Files.isSymbolicLink(resolved) then
      throw IllegalArgumentException(s"refusing a symbolic link as the history file: $path")
    if !Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) then
      val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
      try Files.createFile(resolved, ownerOnly)
      catch
        case _: UnsupportedOperationException => Files.createFile(resolved)
        case _: FileAlreadyExistsException => () // a concurrent ATC created it; validate below
    if !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS) then
      throw IllegalArgumentException(s"history path is not a regular file: $path")
    val posix = Files.getFileAttributeView(
      resolved,
      classOf[PosixFileAttributeView],
      LinkOption.NOFOLLOW_LINKS,
    )
    if posix != null then posix.setPermissions(PosixFilePermissions.fromString("rw-------"))
    resolved
