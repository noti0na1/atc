package atc.ui

import org.jline.keymap.KeyMap
import org.jline.reader.{
  Binding,
  Candidate,
  Completer,
  EOFError,
  EndOfFileException,
  LineReader,
  MaskingCallback,
  ParsedLine,
  Parser,
  Reference,
  UserInterruptException,
  Widget,
}
import org.jline.reader.impl.{DefaultHighlighter, DefaultParser, LineReaderImpl}
import org.jline.reader.impl.history.DefaultHistory
import org.jline.terminal.Terminal
import org.jline.utils.{AttributedString, AttributedStringBuilder, AttributedStyle, InfoCmp}

import java.nio.file.attribute.{PosixFileAttributeView, PosixFilePermissions}
import java.nio.file.{FileAlreadyExistsException, Files, LinkOption, Path}
import java.util.Locale
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
  /** Set while a read ends: its final redraw is what stays on screen, without a list or ghost text. */
  @volatile private var closing = false
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
        case Some(rest) if !plain && !closing =>
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

  /** The slash commands as `/help` lists them (usage, description). While the
    * main prompt holds a partial command name (`/`, `/mo`), the matching ones
    * are listed under it with one selected: ↑/↓ move the selection, Tab fills
    * it in and Enter runs it. */
  @volatile var commandList: List[(String, String)] = Nil
  /** The list is offered in this read (the main prompt, not answers or blocks). */
  private var listing = false
  /** The buffer the selection was made for; typing resets the selection. */
  private var listedFor = ""
  private var selected = 0
  /** ↑/↓ recalled the buffer from history: it is not listed until edited, so they keep browsing. */
  private var recalled = false

  /** The listed commands for the current buffer, and the selected index. */
  private def listState(): (List[(String, String)], Int) =
    val buffer = reader.getBuffer.toString
    val rows = if listing then PromptReader.matchingCommands(buffer, commandList) else Nil
    if buffer != listedFor then
      listedFor = buffer
      recalled = false
      selected = rows.indexWhere(row => PromptReader.commandName(row._1) == buffer.toLowerCase(Locale.ROOT)).max(0)
    (if recalled then Nil else rows, selected)

  /** The selected command's usage while the list is shown. */
  private def selectedCommand(): Option[String] =
    val (rows, index) = listState()
    rows.lift(index).map(_._1)

  private def moveSelection(delta: Int): Boolean =
    val (rows, index) = listState()
    rows.nonEmpty && { selected = Math.floorMod(index + delta, rows.size); true }

  /** Replace the buffer with the selected command's name, plus a space when it takes arguments. */
  private def fillSelected(withSpace: Boolean): Boolean =
    selectedCommand() match
      case Some(usage) =>
        val name = PromptReader.commandName(usage)
        val buf = reader.getBuffer
        buf.clear()
        buf.write(if withSpace && usage != name then name + " " else name)
        true
      case None => false

  /** JLine draws `post` under the buffer; the list is ours when `post` holds
    * `commandRows`, and other users of it (completion lists, history search)
    * keep it while they have it. The list is not drawn into the final display
    * `doCleanup` leaves behind when the read ends. */
  private final class Reader extends LineReaderImpl(terminal, terminal.getName, java.util.HashMap[String, Object]()):
    private val commandRows: java.util.function.Supplier[AttributedString] = () =>
      val (rows, index) = listState()
      val size = getTerminal.getSize
      PromptReader.renderCommands(rows, index, screen.g.todo, size.getColumns, (size.getRows / 2).max(3).min(12))
    override protected def redisplay(flush: Boolean): Unit =
      if post == null || (post eq commandRows) then
        post = if !closing && listState()._1.nonEmpty then commandRows else null
      super.redisplay(flush)
    override protected def doCleanup(nl: Boolean): Unit =
      closing = true
      try super.doCleanup(nl)
      finally closing = false

  val reader: LineReaderImpl = Reader()
  reader.setHistory(DefaultHistory())
  reader.setHighlighter(ghostHighlighter)
  reader.setCompleter(commandCompleter)
  reader.setParser(continuationParser)
  reader.setVariable(LineReader.HISTORY_FILE, historyPath)
  reader.setVariable(LineReader.INDENTATION, 2)
  reader.option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)

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
      def fallback(previous: Binding | Null): Boolean = previous match
        case r: Reference => reader.callWidget(r.name); true
        case w: Widget => w.apply()
        case _ => true
      /** Bind `keys` to `action`; when it declines, they do what they did before. */
      def intercept(name: String, keys: List[String], previous: Binding | Null)(action: () => Boolean): Unit =
        reader.getWidgets.put(name, (() => action() || fallback(previous)): Widget)
        keyMap.bind(Reference(name), keys.distinct.filter(_.nonEmpty)*)
      def key(capability: InfoCmp.Capability, fallbacks: String*): List[String] =
        Option(KeyMap.key(terminal, capability)).toList ++ fallbacks
      // Tab fills in the selected command while the command list is shown;
      // Tab and → accept the ghost text (when the cursor is at the end and
      // there is some); otherwise they do what they did before.
      def acceptGhost(): Boolean =
        val buf = reader.getBuffer
        ghost(buf.toString) match
          case Some(rest) if buf.cursor == buf.length => buf.write(rest); true
          case _ => false
      intercept("atc-accept-suggestion-tab", List("\t"), keyMap.getBound("\t")): () =>
        fillSelected(withSpace = true) || acceptGhost()
      intercept(
        "atc-accept-suggestion-right",
        key(InfoCmp.Capability.key_right, "\u001b[C", "\u001bOC"),
        Reference(LineReader.FORWARD_CHAR),
      )(() => acceptGhost())
      // ↑ and ↓ move through the command list while it is shown, and otherwise
      // browse the history as before.
      def arrow(name: String, keys: List[String], delta: Int): Unit =
        val previous = keyMap.getBound(keys.head)
        intercept(name, keys, previous): () =>
          moveSelection(delta) || {
            fallback(previous)
            listState()
            recalled = true
            true
          }
      arrow("atc-command-up", key(InfoCmp.Capability.key_up, "\u001b[A", "\u001bOA"), -1)
      arrow("atc-command-down", key(InfoCmp.Capability.key_down, "\u001b[B", "\u001bOB"), 1)

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
          if reader.getLastBinding == "\r" && reader.peekCharacter(50) == '\n' then reader.readCharacter()
          true
        else
          fillSelected(withSpace = false)
          reader.callWidget(LineReader.ACCEPT_LINE)
          true
      reader.getWidgets.put("atc-newline", newline)
      reader.getWidgets.put("atc-enter", enter)
      keyMap.bind(Reference("atc-enter"), "\r", "\n")
      // Shift+Enter as CSI u (kitty, Ghostty, WezTerm, foot, iTerm2 with it
      // on), as xterm's modifyOtherKeys, and Alt/Option+Enter as ESC CR.
      keyMap.bind(Reference("atc-newline"), "\u001b[13;2u", "\u001b[27;2;13~", "\u001b\r")

  // Called in raw mode: after `readLine` enters it (and installs its SIGINT
  // handler, which it restores on leaving), and on Enter before it leaves it.
  // In raw mode JLine's Unix terminals turn a Ctrl-C byte into both SIGINT and
  // a key, and each ends a read: one would end the read after it, leaving an
  // empty prompt line. Only the key remains, as on Windows, where it is the only one.
  reader.getWidgets.put(
    LineReader.CALLBACK_INIT,
    (() =>
      terminal.handle(Terminal.Signal.INT, _ => ())
      if alerts.focusSupported then alerts.reportFocus(true)
      true
    ): Widget,
  )
  if alerts.focusSupported then
    reader.getWidgets.put(LineReader.FOCUS_IN, (() => { alerts.focusChanged(true); true }): Widget)
    reader.getWidgets.put(LineReader.FOCUS_OUT, (() => { alerts.focusChanged(false); true }): Widget)
    reader.getWidgets.put(LineReader.CALLBACK_FINISH, (() => { alerts.reportFocus(false); true }): Widget)

  /** Read a line with `typed` already in the buffer (the turn's type-ahead). */
  def read(prompt: String, typed: String): String = read(prompt, typed, commands = !blockMode)

  private def read(prompt: String, typed: String, commands: Boolean): String =
    ghostHighlighter.seen = typed
    listing = commands && !plain
    try reader.readLine(prompt, null: String | Null, null: MaskingCallback | Null, typed)
    finally
      listing = false
      alerts.reportFocus(false) // Ctrl-C and Ctrl-D leave without `callback-finish`

  /** Read a typed answer; `None` when it is empty or cancelled. */
  def readAnswer(prompt: String): Option[String] = PromptReader.readAnswer(read(prompt, "", commands = false))

  /** Read a secret: echoed as `*` and kept out of the history. */
  def readSecret(prompt: String): Option[String] =
    reader.setVariable(LineReader.DISABLE_HISTORY, true)
    try PromptReader.readAnswer(reader.readLine(prompt, Character.valueOf('*')))
    finally
      reader.setVariable(LineReader.DISABLE_HISTORY, false)
      alerts.reportFocus(false)

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

  /** The command a usage line names: `/model` for `/model [ref]`. */
  private[atc] def commandName(usage: String): String = usage.takeWhile(_ != ' ')

  /** The rows of `commands` (usage, description) to list under a prompt holding
    * `buffer`: those whose name starts with it, while it is a single word
    * starting with `/`. */
  private[atc] def matchingCommands(buffer: String, commands: List[(String, String)]): List[(String, String)] =
    if !buffer.startsWith("/") || buffer.exists(_.isWhitespace) then Nil
    else
      val typed = buffer.toLowerCase(Locale.ROOT)
      commands.filter(row => commandName(row._1).startsWith(typed))

  /** At most `height` of `rows`, scrolled to show `selected`, in aligned columns
    * cut to `width`; the selected row is highlighted and marked with `marker`. */
  private[atc] def renderCommands(
    rows: List[(String, String)],
    selected: Int,
    marker: String,
    width: Int,
    height: Int,
  ): AttributedString =
    val first = (selected - height + 1).max(0).min((rows.size - height).max(0))
    val usageWidth = rows.map(_._1.length).maxOption.getOrElse(0)
    val text = AttributedStringBuilder()
    rows.zipWithIndex.slice(first, first + height).foreach: (row, index) =>
      val (usage, help) = row
      val chosen = index == selected
      val line = AttributedStringBuilder()
      line.append(
        if chosen then marker + " " else " " * (marker.length + 1),
        AttributedStyle.BOLD.foreground(AttributedStyle.CYAN)
      )
      line.append(
        usage.padTo(usageWidth, ' '),
        if chosen then AttributedStyle.BOLD.foreground(AttributedStyle.CYAN) else AttributedStyle.DEFAULT
      )
      line.append("  ")
      line.append(help, if chosen then AttributedStyle.DEFAULT else AttributedStyle.DEFAULT.faint())
      if index > first then text.append("\n")
      text.append(line.toAttributedString.nn.columnSubSequence(0, (width - 1).max(1)))
    text.toAttributedString.nn

  /** Cancel the input field and consume JLine's interrupt before another prompt reads. */
  private[atc] def readAnswer(read: => String): Option[String] =
    try Some(read).map(_.trim).filter(_.nonEmpty)
    catch
      case _: UserInterruptException =>
        Thread.interrupted()
        None
      case _: EndOfFileException => None

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
