package atc.ui

import atc.{Debug, ProcessEnvironment}
import atc.agent.{AgentUI, TurnOutcome}
import atc.lib.{Todo, TodoStatus}
import atc.perms.*
import atc.host.FileChange
import atc.sandbox.{ExecutionResult, ReplSession}

import org.jline.prompt.{CheckboxResult, ListResult, PromptBuilder, PromptResult, PrompterConfig, PrompterFactory}
import org.jline.keymap.KeyMap
import org.jline.reader.{
  Binding,
  Candidate,
  Completer,
  EOFError,
  EndOfFileException,
  LineReader,
  LineReaderBuilder,
  ParsedLine,
  Parser,
  Reference,
  UserInterruptException,
  Widget,
}
import org.jline.reader.impl.{DefaultHighlighter, DefaultParser, LineReaderImpl}
import org.jline.reader.impl.history.DefaultHistory
import org.jline.terminal.{Attributes, Terminal, TerminalBuilder}
import org.jline.terminal.impl.DumbTerminal
import org.jline.utils.{AttributedString, AttributedStringBuilder, AttributedStyle, InfoCmp, NonBlockingReader, Status}

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.{PosixFileAttributeView, PosixFilePermissions}
import java.nio.file.{FileAlreadyExistsException, Files, LinkOption, Path}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

import Ansi.{Blue, Bold, ClearLine, Cyan, Dim, Green, Magenta, Red, Reset, Yellow}

/** JLine terminal interface for input, streaming responses, tool output and menus.
  * Ctrl-O toggles expanded output; non-interactive terminals print all output.
  * Writes track line boundaries so streamed chunks retain their indentation.
  * See `doc/development.md` for the layout and keyboard controls. */
final class Tui(historyFile: Path, nonInteractive: Boolean = false) extends AgentUI:
  private val historyPath = Tui.secureHistoryFile(historyFile)
  // No grapheme-cluster probing: it sends a DECRQM query to the terminal and
  // waits for a reply, which swallows early input on ptys that don't answer.
  val terminal: Terminal = Tui.openTerminal(nonInteractive)
  private val out = terminal.writer()
  Debug.log(
    s"terminal: ${terminal.getClass.getSimpleName} type=${terminal.getType} size=${terminal.getSize} encoding=${terminal.encoding()}"
  )
  /** The predicted next message (`suggest`), drawn as ghost text after what
    * is typed as long as that is a prefix of it. */
  @volatile private var suggestion: Option[String] = None
  /** What the ghost text would add to `typed`: the rest of the suggestion. */
  private def ghost(typed: String): Option[String] =
    suggestion.filter(s => s.length > typed.length && s.startsWith(typed)).map(_.drop(typed.length))
  /** The line reader's highlighter, with the ghost text appended in faint
    * style. The cursor is positioned from the buffer, not from this string,
    * so the extra text is display only. */
  private object ghostHighlighter extends DefaultHighlighter:
    override def highlight(r: LineReader, buffer: String): AttributedString =
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
  @volatile private var blockMode = false
  /** Multi-line input ([[Continuation]]): on Enter, an `EOFError` tells JLine
    * to insert a newline (indented by the open-bracket depth) instead of
    * accepting; everything else is the default word splitting the completer uses. */
  private val continuationParser: Parser = new DefaultParser:
    override def parse(line: String, cursor: Int, context: Parser.ParseContext): ParsedLine =
      if context == Parser.ParseContext.ACCEPT_LINE then
        Continuation.pending(line, blockMode).foreach(depth => throw EOFError(-1, -1, "incomplete", "", depth, null))
      super.parse(line, cursor, context)

  private val reader: LineReader =
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

  /** Not a real terminal (piped / `-p` in a script): no spinner, no cursor tricks, no menus, nothing folded. */
  private val plain: Boolean = terminal.getType == Terminal.TYPE_DUMB || terminal.getType == Terminal.TYPE_DUMB_COLOR

  /** The line `readLine` returns when the user presses Shift-Tab on an empty
    * prompt: the app treats it as the `/mode` command (cycle the sandbox mode). */
  val CycleModeLine: String = "/mode"
  if !plain then
    val cycle: Widget = () =>
      if reader.getBuffer.length == 0 then
        reader.getBuffer.write(CycleModeLine)
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
  private val g: Glyphs =
    Tui.glyphs(terminal.encoding(), ProcessEnvironment.contains("ATC_ASCII"))

  // ── styles (by role, see `Ansi`) ──────────────────────────────────

  /** Colours the terminal supports; 0 (no styling at all) when there is no real terminal. */
  private val colors: Int =
    if plain then 0
    else Option(terminal.getNumericCapability(InfoCmp.Capability.max_colors)).map(_.intValue).getOrElse(0)
  private def styled(s: String, codes: Int*): String = if colors <= 0 then s else Ansi.styled(s, codes*)
  // Continuation lines: a bar under the prompt, padded (`%P`) to the prompt's
  // width. ASCII on purpose: JLine turns box glyphs in a prompt into DEC
  // line-drawing escapes.
  reader.setVariable(LineReader.SECONDARY_PROMPT_PATTERN, "%P " + styled("| ", Cyan, Bold))
  private val Indent = "  "
  private def width: Int = { val w = terminal.getSize.getColumns; if w <= 0 then 80 else w }

  // ── state ─────────────────────────────────────────────────────────

  /** Set while an agent turn is running; Ctrl-C sets it. */
  val interrupted = AtomicBoolean(false)
  @volatile private var busy = false
  @volatile private var closed = false
  /** The last two characters written: tells whether we are at a line start / after a blank line. */
  @volatile private var tail = "\n\n"
  private var spinner: Option[Spinner] = None
  /** The open assistant prose block (bullet already printed), rendering Markdown as it streams. */
  private var prose: Option[MarkdownStream] = None
  /** A tool block is open; `outputStarted` once the first program output line appeared. */
  private var toolOpen = false
  private var outputStarted = false
  /** Agent-visible text printed during the current tool call, as it appears in
    * the REPL's captured output; `toolEnd` subtracts it from the result panel. */
  private val printed = StringBuilder()
  private val liveCaptured = StringBuilder()
  private var liveTruncated = false
  private val fileChanges = collection.mutable.ListBuffer[FileChange]()
  private val toolHistory = ToolHistory()
  private var currentCode = ""
  private var popupDepth = 0
  private val pendingProcessEvents = collection.mutable.Queue[String]()

  def fileChanged(change: FileChange): Unit = synchronized:
    if toolOpen && fileChanges.size < 50 then fileChanges += change

  def clearOutputHistory(): Unit = synchronized(toolHistory.clear())

  def showOutput(argument: String): Unit = frame:
    val parts = argument.trim.split("\\s+").toList.filter(_.nonEmpty)
    if parts.isEmpty then
      if toolHistory.list.isEmpty then info("No retained tool output.")
      else toolHistory.list.foreach(println)
    else if parts.size > 2 || parts.lift(1).exists(value => !value.toIntOption.exists(_ >= 1)) then
      error("Use /output <id|last> [line], with a positive line number.")
    else
      val id = if parts.head == "last" then toolHistory.latest.map(_.id) else parts.head.toIntOption
      val from = parts.drop(1).headOption.flatMap(_.toIntOption).getOrElse(1)
      id.flatMap(toolHistory.get) match
        case Some(entry) =>
          val lines = entry.render.linesIterator.toVector
          if from > lines.size then error(s"Tool ${entry.id} has ${lines.size} lines of retained output.")
          else
            val end = (from.toLong - 1 + 200).min(lines.size.toLong).toInt
            lines.slice(from - 1, end).foreach(println)
            if lines.size > end then info(s"More output: /output ${entry.id} ${end + 1}")
        case _ => error("Output is unavailable. Use /output to list retained results.")
  /** TODO list changed during the current tool call; drawn once when it ends. */
  @volatile private var pendingTodos: Option[List[Todo]] = None
  /** Ctrl-O: show thinking in full and never fold output. Sticks for the session. */
  @volatile private var expanded = false

  /** Extra action on Ctrl-C during a turn (e.g. interrupt the REPL evaluation). */
  @volatile var onInterrupt: () => Unit = () => ()
  @volatile var onSubmit: String => Unit = _ => ()
  @volatile private var contextLabel: String = ""
  @volatile var queuedInputs: () => Int = () => 0
  @volatile private var operation = "ready"
  @volatile private var turnStarted = 0L
  @volatile private var draftInput = ""
  @volatile private var promptHint = ""
  private val statusLine = if plain then None else Option(Status.getStatus(terminal))
  private var statusSize = (0, 0)
  private var lastStatus = ""
  statusLine.foreach { status =>
    status.setBorder(false)
    // Reserve the footer before writing content; growing it later scrolls the first lines away.
    Tui.drawStatus(terminal, status, "")
  }

  def setContext(model: String, mode: String, directory: String): Unit = synchronized:
    contextLabel = s"$model ${g.dot} $mode ${g.dot} $directory"
    refreshStatus()

  private def refreshStatus(): Unit =
    if closed || statusLine.isEmpty then return
    val elapsed = if busy then s" ${Tui.duration((System.nanoTime() - turnStarted) / 1e9)}" else ""
    val queued = queuedInputs()
    val waiting = if queued > 0 then s" ${g.dot} $queued message${if queued == 1 then "" else "s"} queued" else ""
    val label =
      if promptHint.nonEmpty then promptHint
      else if draftInput.nonEmpty then
        s"Update: ${draftInput.takeRight((width - 30).max(10))} ${g.dot} Enter to send$waiting"
      else if busy then
        val frame = g.spinner(((System.nanoTime() - turnStarted) / 100_000_000L % g.spinner.length).toInt)
        s"$frame ${operation.take((width / 2).max(20))}$elapsed$waiting ${g.dot} $contextLabel"
      else contextLabel
    val singleLine = Ansi.sanitize(label).replace('\n', ' ').replace('\t', ' ')
    statusLine.foreach { status =>
      val size = terminal.getSize
      val dimensions = (size.getColumns, size.getRows)
      val resized = dimensions != statusSize
      if resized then
        status.resize(size)
        statusSize = dimensions
      val text = fit(singleLine, 0)
      if resized || text != lastStatus then
        flushOutput()
        Tui.drawStatus(terminal, status, text)
        lastStatus = text
    }

  override def inputAccepted(text: String): Unit = frame:
    beginBlock()
    write(styled(s"${g.arrow} applying update: ${Ansi.sanitize(text)}", Cyan) + "\n")
    refreshStatus()

  private def withOperation[A](label: String)(body: => A): A =
    val previous = operation
    synchronized { operation = label; refreshStatus() }
    try body
    finally synchronized { operation = previous; refreshStatus() }

  private def setOperation(label: String): Unit =
    if operation != label then
      operation = label
      refreshStatus()

  private def withPromptHint[A](hint: String)(body: => A): A =
    val previous = promptHint
    synchronized { promptHint = hint; refreshStatus() }
    try body
    finally synchronized { promptHint = previous; refreshStatus() }
  /** Whether an exhausted tool budget asks the human to continue (off for `-p` runs). */
  @volatile var askToContinue: Boolean = true

  override def confirmMoreToolCalls(used: Int, budget: Int): Boolean =
    askToContinue && confirm(
      s"Tool limit reached after ${Tui.plural(used, "call")}. Allow ${Tui.plural(budget, "more call")}?"
    )

  terminal.handle(Terminal.Signal.INT, _ => if busy then { interrupted.set(true); onInterrupt() })
  terminal.handle(
    Terminal.Signal.WINCH,
    _ =>
      frame {
        refreshStatus()
        thinking.resize()
        liveOutput.resize()
      }
  )

  def beginTurn(): Unit =
    frame:
      interrupted.set(false)
      turnStarted = System.nanoTime()
      busy = true
      operation = "starting turn"
      refreshStatus()
    keys.start()
  /** End the turn: close open blocks, say what the turn cost (`stats`) and
    * leave one blank line before the next prompt — the agent is idle again. */
  def endTurn(stats: Option[Tui.TurnStats] = None): Unit =
    frame:
      stopSpinner()
      busy = false
      operation = stats.fold("ready")(_.outcome.label)
      thinking.end()
      closeProse()
      liveOutput.end()
      toolOpen = false
      flushTodos()
      stats.foreach { s =>
        ensureNewline()
        val calls = Tui.plural(s.toolCalls, "tool call")
        val context = Tui.contextUsage(s.context, s.window)
        val summary =
          s"${g.bullet} ${s.outcome.label} in ${Tui.duration(s.seconds)} ${g.dot} $calls ${g.dot} ${Tui.count(s.tokens)} tokens ${g.dot} $context"
        val lines = if plain then List(summary) else TextLayout.wrap(summary, width - Indent.length - 1)
        lines.zipWithIndex.foreach((line, index) =>
          write((if index == 0 then "" else Indent) + styled(line, Dim) + "\n")
        )
      }
      blankLine()
      refreshStatus()
    keys.stop() // outside the lock: the key thread may be waiting for it
  def isInterrupted: Boolean = interrupted.get()

  // ── low-level writing ─────────────────────────────────────────────

  private var outputDirty = false
  private var frameDepth = 0

  /** Flush complete updates rather than every gutter, style and text fragment. */
  private def frame[A](body: => A): A = synchronized:
    frameDepth += 1
    try body
    finally
      frameDepth -= 1
      if frameDepth == 0 then
        flushOutput()

  private def flushOutput(): Unit = if outputDirty then
    out.flush()
    outputDirty = false

  private def write(s: String): Unit =
    if s.nonEmpty then
      out.print(s)
      outputDirty = true
      tail = if s.length >= 2 then s.takeRight(2) else (tail + s).takeRight(2)

  /** A style sequence: printed like `write`, but it takes no columns and leaves `tail` alone. */
  private def writeStyle(s: String): Unit =
    out.print(s)
    outputDirty = true

  private def atLineStart: Boolean = tail.endsWith("\n")
  private def afterBlankLine: Boolean = tail == "\n\n"
  private def ensureNewline(): Unit = if !atLineStart then write("\n")
  /** Make sure the previous content is followed by an empty line. */
  private def blankLine(): Unit = { ensureNewline(); if !afterBlankLine then write("\n") }
  /** Start a new block: blank line before it, and no prose/thinking block is open any more. */
  private def beginBlock(): Unit = { stopSpinner(); thinking.end(); closeProse(); blankLine() }

  /** Write text that may arrive in chunks and span lines, putting `gutter` at
    * every line start. Empty lines get the gutter too unless it is blank
    * (indentation), so boxes stay closed and prose has no trailing spaces. */
  private def writeGuttered(text: String, gutter: String): Unit =
    if !text.contains('\n') then
      if atLineStart && text.nonEmpty then write(gutter + text) else write(text)
      return
    val parts = text.split("\n", -1)
    val rendered = StringBuilder()
    var lineStart = atLineStart
    var i = 0
    while i < parts.length do
      val seg = parts(i)
      val last = i == parts.length - 1
      if lineStart && (seg.nonEmpty || (!last && !gutter.isBlank)) then rendered.append(gutter)
      rendered.append(seg)
      if !last then rendered.append('\n')
      lineStart = !last
      i += 1
    write(rendered.toString)

  private def gutter(code: Int): String = Indent + styled(g.bar, code) + " "
  /** Visible width of `gutter`: the indent plus the bar and its space. */
  private val GutterWidth = Indent.length + 2

  /** Cut a plain line so it fits on one terminal row (region lines must not wrap). */
  private def fit(line: String, used: Int): String =
    val room = width - used - 1
    // Reserve the ellipsis's actual width: "…" is one cell but ASCII "..." is three,
    // so budgeting a single column would let a truncated ASCII line overflow and wrap.
    val ell = math.max(1, Tui.displayWidth(g.ellipsis))
    if room <= 0 then ""
    else if Tui.displayWidth(line) <= room then line
    else if room <= ell then g.ellipsis.take(room)
    else
      // Whole code points until the width budget (minus the ellipsis) is spent.
      val budget = room - ell
      val sb = StringBuilder()
      var w = 0
      var i = 0
      var styledText = false
      while i < line.length && w < budget do
        val styleEnd = Tui.sgrEnd(line, i)
        if styleEnd > 0 then { sb.append(line, i, styleEnd); styledText = true; i = styleEnd } // takes no cells
        else
          val cp = line.codePointAt(i)
          val cw = Tui.cellWidth(cp, w)
          if w + cw > budget then i = line.length
          else { sb.append(String(Character.toChars(cp))); w += cw; i += Character.charCount(cp) }
      // A cut may have dropped the line's own reset: never let its style leak into the next row.
      sb.toString + (if styledText then Reset else "") + g.ellipsis

  /** Update only changed rows in a live preview. Clearing the rest of the screen would
    * also erase the footer, forcing unrelated output to be repainted on every token. */
  private final class LiveRegion:
    private var drawn = 0
    private var tailBefore = tail
    private var previousLines = List.empty[String]
    def redraw(lines: List[String], force: Boolean = false): Unit =
      if !force && lines == previousLines then return
      if drawn == 0 then { ensureNewline(); tailBefore = tail }
      write(Tui.replaceRows(previousLines, lines, force))
      tail = lines.lastOption match
        case Some("") => "\n\n"
        case Some(last) => last.takeRight(1) + "\n"
        case None => tailBefore
      drawn = lines.length
      previousLines = lines
    def clear(): Unit = redraw(Nil)
    /** Keep what is drawn as ordinary output. */
    def freeze(): Unit =
      drawn = 0
      previousLines = Nil

  // ── plain lines (banner, slash commands, notices) ─────────────────

  private def renderedLine(s: String): Unit = frame:
    stopSpinner()
    ensureNewline()
    val lines = if plain then s.split("\n", -1).toList else TextLayout.wrap(s, width - 1)
    lines.foreach(line => write(line + "\n"))
  /** A plain line supplied by the application. Config values, policy summaries
    * and paths may be repository-controlled, so terminal controls never pass. */
  def println(s: String = ""): Unit = renderedLine(Ansi.sanitize(s))
  def info(s: String): Unit = renderedLine(styled(Ansi.sanitize(s), Dim))
  def preview(s: String): Unit = info(fit(Ansi.sanitize(s).replace('\n', ' '), 0))
  def success(s: String): Unit = renderedLine(styled(Ansi.sanitize(s), Green))
  def warn(s: String): Unit = renderedLine(styled(s"${g.warn} ${Ansi.sanitize(s)}", Yellow))
  def error(s: String): Unit = renderedLine(styled(s"${g.cross} ${Ansi.sanitize(s)}", Red))

  def showHelp(rows: List[(String, String)]): Unit = frame:
    println("Commands:")
    TextLayout.fields(
      rows.map((command, description) =>
        styled(Ansi.sanitize(command), Cyan) -> Ansi.sanitize(description)
      ),
      width
    ).foreach(renderedLine)
  /** The start-up banner: a title, aligned `label → value` rows and a dim hint line. */
  def banner(title: String, rows: List[(String, String)], hint: String): Unit = frame:
    stopSpinner()
    ensureNewline()
    write(styled(s"${g.bullet} ${Ansi.sanitize(title)}", Cyan, Bold) + "\n")
    // Values can be paths (possibly named by an attacker in a cloned repo): sanitize.
    TextLayout.fields(rows.map((label, value) => styled(Ansi.sanitize(label), Dim) -> Ansi.sanitize(value)), width)
      .foreach(line => write(line + "\n"))
    val separated = Ansi.sanitize(hint).replace(" · ", s" ${g.dot} ")
    val controls = if g == Glyphs.ascii then separated.replace("→", "Right") else separated
    TextLayout.wrap(controls, width - 3).foreach(line => write(Indent + styled(line, Dim) + "\n"))

  // ── thinking (streamed reasoning) ─────────────────────────────────

  def thinkingDelta(text: String): Unit = frame:
    setOperation("reasoning")
    thinking.delta(Ansi.sanitize(text))

  /** The model's reasoning as it streams. Compact view: a live window over the
    * last lines that collapses to a one-line summary when the reasoning ends.
    * Plain or expanded view: written out in full, the block staying open so
    * later deltas simply append. */
  private object thinking:
    private val buf = Tui.TailBuffer(Tui.MaxHeldChars)
    private var region: Option[LiveRegion] = None
    /** Written out in full (plain/expanded), so the block is still open. */
    private var streaming = false
    private var started = 0L

    def active: Boolean = streaming || region.isDefined

    def resize(): Unit = region.foreach(_.redraw(window(), force = true))

    def delta(text: String): Unit = if text.nonEmpty then
      stopSpinner()
      if started == 0L then started = System.nanoTime()
      buf.append(text)
      if streaming then writeGuttered(text, Indent) else render()

    /** (Re)draw everything gathered so far in the current view. */
    def render(): Unit =
      if plain || expanded then
        clearRegion()
        beginBlock()
        write(styled(g.bullet, Dim) + " " + styled("thinking", Dim) + "\n")
        if colors > 0 then writeStyle(Ansi.sgr(Dim))
        streaming = true
        writeGuttered(buf.text.dropWhile(_ == '\n'), Indent)
      else
        val live = region.getOrElse { beginBlock(); val r = LiveRegion(); region = Some(r); r }
        live.redraw(window())

    /** Take the rendering off the screen but keep the reasoning (Ctrl-O). */
    def detach(): Unit =
      clearRegion()
      if streaming then
        if colors > 0 then writeStyle(Reset)
        ensureNewline()
        streaming = false

    /** Thinking ended (answer text or a tool call follows): a window collapses
      * to a summary line, reasoning shown in full just ends. */
    def end(): Unit = if active then
      val collapses = region.isDefined
      detach()
      if collapses then write(summary())
      buf.clear()
      started = 0L

    private def clearRegion(): Unit = { region.foreach(_.clear()); region = None }

    private def summary(): String =
      val secs = (System.nanoTime() - started) / 1e9
      styled(
        s"${g.bullet} reasoning ${g.dot} ${Tui.duration(secs)}",
        Dim
      ) + "\n"

    /** Header + the last few lines of the reasoning so far. */
    private def window(): List[String] =
      styled(s"${g.bullet} thinking${g.ellipsis} (Ctrl-O to expand)", Dim) ::
        buf.tail(Tui.ThinkingWindow).map(l => Indent + styled(fit(l, Indent.length), Dim))

  // ── assistant prose (streamed) ────────────────────────────────────

  def assistantDelta(text: String): Unit = frame:
    setOperation("responding")
    stopSpinner()
    val clean = Ansi.sanitize(text) // model text: no terminal control may reach the screen
    prose match
      case Some(md) => writeGuttered(md.push(clean), Indent)
      case None =>
        val t = clean.dropWhile(_ == '\n')
        if t.nonEmpty then
          beginBlock()
          write(styled(g.bullet, Bold) + " ")
          val md = newProse()
          prose = Some(md)
          writeGuttered(md.push(t), Indent)

  /** Markdown rendering for one prose block; plain pass-through when there are no colours. */
  private def newProse(): MarkdownStream =
    if colors > 0 then
      MarkdownStream(
        MarkdownStream.Glyphs(g.bullet2, g.quote, g.rule, styled(g.bar, Blue) + " ", g.bar, g.junction),
        Highlight.scala,
        () => width - Indent.length - 1,
      )
    else MarkdownStream.plain

  /** Flush what the Markdown renderer still holds and end the prose block. */
  private def closeProse(): Unit =
    prose.foreach(md => writeGuttered(md.finish(), Indent))
    prose = None

  /** Out-of-band note during streaming ("web search"): a spinner until the
    * next text; empty = paragraph break within the same prose block. */
  def assistantNote(text: String): Unit = frame:
    stopSpinner()
    thinking.end()
    ensureNewline()
    if text.nonEmpty then status(text)
  def assistantEnd(): Unit = frame { stopSpinner(); thinking.end(); closeProse(); ensureNewline() }

  // ── tool blocks ───────────────────────────────────────────────────

  def toolStart(code: String): Unit = toolStart(code, "run_scala")
  /** Open a code block titled `title`: the agent's `run_scala`, or the user's own `/run`. */
  def toolStart(code: String, title: String): Unit = frame:
    setOperation("running Scala")
    beginBlock()
    write(styled(g.bullet, Magenta) + " " + styled(title, Magenta, Bold) + "\n")
    // The code is model-written: sanitize before highlighting/printing.
    val lines = if colors > 0 then Highlight.scala(Ansi.sanitize(code)) else Ansi.sanitize(code).linesIterator.toList
    lines.flatMap(line => TextLayout.wrap(line, width - GutterWidth - 1))
      .foreach(line => write(gutter(Magenta) + line + "\n"))
    toolOpen = true
    outputStarted = false
    printed.clear()
    liveCaptured.clear()
    liveTruncated = false
    fileChanges.clear()
    currentCode = code
    liveOutput.start()
    if !plain && statusLine.isEmpty then spin(Indent, "running")

  /** Live output of the agent's `println` (see `HostOutput.print`). Classified
    * content — where the two texts differ — is marked so the user knows the
    * model cannot see it. `printed` keeps the RAW text (it is matched verbatim
    * against the REPL capture in `toolEnd`); only the display is sanitized. */
  def agentPrint(agentText: String, userText: String): Unit = frame:
    // Text beyond the REPL capture limit cannot be subtracted from its result.
    val room = ReplSession.MaxOutputBytes - printed.length
    if room > 0 then printed.append(agentText.take(room))
    openOutputSection()
    if agentText == userText then liveOutput.emit(Ansi.sanitize(userText))
    else liveOutput.emit(styled("[classified] ", Yellow, Bold) + styled(Ansi.sanitize(userText), Yellow))

  /** A command the agent runs (`exec`) is taking a while: name it, then show
    * what it writes as it comes (`commandOutput`), in the same output section
    * as the prints. Not part of the tool result, so not remembered in `printed`. */
  def commandRunning(commandLine: String): Unit = frame:
    setOperation(s"running $commandLine")
    openOutputSection()
    liveOutput.emit(styled(s"$$ ${Ansi.sanitize(commandLine)}", Cyan) + "\n")
  def commandOutput(text: String): Unit = frame:
    val room = Tui.MaxHeldChars - liveCaptured.length
    if room > 0 then liveCaptured.append(text.take(room))
    if text.length > room then liveTruncated = true
    openOutputSection()
    liveOutput.emit(Ansi.sanitize(text))
  /** Process notifications preserve the current prompt and wait until any menu closes. */
  def processEvent(text: String): Unit = frame:
    if closed then ()
    else if popupDepth > 0 then
      if pendingProcessEvents.size >= 100 then pendingProcessEvents.dequeue()
      pendingProcessEvents.enqueue(text.take(2000))
    else displayProcessEvent(text)

  private def displayProcessEvent(text: String): Unit =
    if toolOpen then
      openOutputSection()
      liveOutput.emit(styled(Ansi.sanitize(text), Cyan) + "\n")
    else reader.printAbove(styled(Ansi.sanitize(text), Cyan))

  /** The first program output of a tool block opens its `├ output` section. */
  private def openOutputSection(): Unit =
    stopSpinner()
    if toolOpen && !outputStarted then
      ensureNewline()
      write(section("output", Dim))
      outputStarted = true

  /** Program output inside a tool block. It goes straight to the screen while
    * the section fits in `Tui.FoldAfterRows` terminal *rows*; everything after
    * that into a live tail window ("⋯ N more lines" + the last `Tui.FoldTail`
    * lines), unless the view is expanded or there is no terminal to redraw.
    *
    * The budget counts rows rather than lines because a long line wraps: a few
    * 400-character lines, or output printed without newlines at all, would
    * otherwise fill the screen without ever reaching a line count. */
  private object liveOutput:
    /** Rows the direct section has used, and how far into its last row it got. */
    private var usedRows = 0
    private var column = 0
    private var folding = false
    private val held = Tui.TailBuffer(Tui.MaxHeldChars)
    private var region: Option[LiveRegion] = None

    /** A new tool block begins: nothing written, nothing folded yet. */
    def start(): Unit = { usedRows = 0; column = 0; folding = false; held.clear() }

    def emit(body: String): Unit =
      if plain || expanded || !toolOpen then writeGuttered(body, gutter(Dim))
      else if folding then fold(body)
      else
        // One logical line at a time, while the rows it needs still fit; the
        // first one that does not fit starts the folded tail window.
        var rest = body
        while rest.nonEmpty && !folding do
          val nl = rest.indexOf('\n')
          val (segment, remainder) = if nl < 0 then (rest, "") else rest.splitAt(nl + 1)
          val placed = Tui.place(column, segment, width, GutterWidth)
          if usedRows + placed.rows > Tui.FoldAfterRows then folding = true
          else
            writeGuttered(segment, gutter(Dim))
            usedRows += placed.rows
            column = placed.column
            rest = remainder
        if rest.nonEmpty then fold(rest)

    /** The output section is over: whatever the tail window shows stays on screen. */
    def end(): Unit =
      region.foreach(_.redraw(window(interactive = false), force = true))
      region.foreach(_.freeze())
      region = None
      folding = false
      held.clear()

    /** Ctrl-O: take the window down and hand back the text it was hiding. */
    def detach(): String =
      val hidden = held.text
      region.foreach(_.clear())
      region = None
      held.clear()
      hidden

    def foldFromHere(): Unit = folding = true
    def showEverything(): Unit = folding = false
    def resize(): Unit = region.foreach(_.redraw(window(), force = true))

    private def fold(text: String): Unit =
      held.append(text)
      val live = region.getOrElse { val r = LiveRegion(); region = Some(r); r }
      live.redraw(window())

    private def window(interactive: Boolean = true): List[String] =
      val lines = held.tail(Tui.FoldTail)
      val hidden = held.lineCount - lines.length
      val header =
        if hidden > 0 then
          val hint = if interactive then " (Ctrl-O to expand)" else ""
          List(gutter(Dim) + styled(s"${g.ellipsis} ${Tui.plural(hidden, "more line")}$hint", Dim))
        else Nil
      header ++ lines.map(l => gutter(Dim) + fit(l, GutterWidth))

  private def section(label: String, code: Int): String =
    Indent + styled(s"${g.tee} $label", code) + "\n"

  /** Close the tool block: what the REPL produced *besides* the agent's own
    * prints (those were shown live) — diagnostics, echoed values, exceptions —
    * then the verdict. Long bodies are cut in the middle (unless expanded) so
    * both the first diagnostics and the tail stay visible. */
  def toolEnd(r: ExecutionResult, millis: Long): Unit = frame:
    setOperation(if r.success then "tool completed" else "tool failed")
    val live = liveCaptured.toString + (if liveTruncated then "\n[retained live output limit reached]" else "")
    val record = toolHistory.add(currentCode, r, millis, live, fileChanges.toList)
    stopSpinner()
    liveOutput.end()
    ensureNewline()
    val body =
      List(Option(ExecutionResult.trimStackFrames(r.output)).filter(_.nonEmpty), r.error).flatten.mkString("\n")
    val lines = Tui.withoutPrinted(body, printed.toString).linesIterator.toList
    if lines.nonEmpty then
      // REPL output holds the agent's raw prints and compiler diagnostics: sanitize
      // before display (the subtraction above happens in raw space, on purpose).
      val cleaned = lines.map(Ansi.sanitize(_)).flatMap(line =>
        if plain then List(line) else TextLayout.wrap(line, width - GutterWidth - 1)
      )
      val kept =
        if cleaned.length <= Tui.MaxPanelLines || plain || expanded then cleaned
        else
          cleaned.take(Tui.MaxPanelLines * 2 / 3) ++
            List(s"${g.ellipsis} ${cleaned.length - Tui.MaxPanelLines} lines omitted ${g.dot} /output ${record.id}") ++
            cleaned.takeRight(Tui.MaxPanelLines / 3)
      if r.success then
        write(section("result", Dim))
        kept.foreach(l => write(gutter(Dim) + styled(l, Dim) + "\n"))
      else
        write(section("error", Red))
        kept.foreach(l => write(gutter(Red) + l + "\n"))
    fileChanges.foreach(change =>
      write(Indent + styled(s"${Ansi.sanitize(change.path)}: ${change.summary}", Cyan) + "\n")
    )
    val verdict =
      if r.success then styled(s"${g.end} ok ${millis} ms", Green) else styled(s"${g.end} failed ${millis} ms", Red)
    write(Indent + verdict + styled(s" ${g.dot} /output ${record.id}", Dim) + "\n")
    toolOpen = false
    flushTodos()

  // ── expanded / compact toggle (Ctrl-O) ────────────────────────────

  private def toggleExpanded(): Unit = frame:
    expanded = !expanded
    // Take down what is live, say what happened, then re-render it in the new view.
    val wasThinking = thinking.active
    thinking.detach()
    val heldBack = liveOutput.detach()
    info(if expanded then "expanded view (Ctrl-O to collapse)" else "compact view (Ctrl-O to expand)")
    if wasThinking then thinking.render() // the whole reasoning (expanded) or a window over it (compact)
    if expanded then
      if heldBack.nonEmpty then writeGuttered(heldBack, gutter(Dim)) // what was held back
      liveOutput.showEverything()
    else if toolOpen && outputStarted then liveOutput.foldFromHere()

  // ── spinner ───────────────────────────────────────────────────────

  /** An animated "the agent is working" line — `prefix ⠋ text… 12 s` — that
    * lives on the current (empty) line until something else is written. */
  private final class Spinner(prefix: String, text: String) extends Thread("atc-spinner"):
    setDaemon(true)
    @volatile var running = true
    private val started = System.nanoTime()
    override def run(): Unit =
      var i = 0
      while running do
        val secs = (System.nanoTime() - started) / 1_000_000_000L
        val elapsed = if secs >= 2 then s" $secs s" else ""
        Tui.this.synchronized:
          if running then
            out.print(ClearLine + prefix +
              styled(s"${g.spinner(i % g.spinner.length)} $text${g.ellipsis}$elapsed", Dim))
            out.flush()
        i += 1
        try Thread.sleep(80)
        catch case _: InterruptedException => running = false
    def stopAndClear(): Unit =
      running = false
      interrupt()
      out.print(ClearLine) // back to the line start we began on; `tail` still ends with "\n"
      out.flush()

  /** Show progress ("model is thinking"); ends the current prose block. */
  def status(text: String): Unit = frame:
    operation = text
    refreshStatus()
    stopSpinner()
    thinking.end()
    closeProse()
    ensureNewline()
    if plain then write(styled(s"~ ${Ansi.sanitize(text)}...", Dim) + "\n")
    else if statusLine.isEmpty then spin("", Ansi.sanitize(text))

  private def spin(prefix: String, text: String): Unit =
    val s = Spinner(prefix, text)
    spinner = Some(s)
    s.start()

  private def stopSpinner(): Unit = synchronized:
    spinner.foreach(_.stopAndClear())
    spinner = None

  // ── keys during a turn (Ctrl-O toggle, type-ahead) ────────────────

  /** During turns, read corrections and Ctrl-O in raw mode. Enter submits a correction;
    * unsent text becomes the next prompt's draft. Menus take exclusive control of input. */
  private object keys:
    private var thread: Option[Thread] = None
    private var saved: Option[Attributes] = None
    @volatile private var running = false
    /** The pop-up handshake: both guarded by pauseLock. A pop-up may not read
      * while the key thread is inside `read`, and the key thread may not start
      * a read once a pop-up asked for the pause. */
    private val pauseLock = Object()
    private var pauseDepth = 0
    private var reading = false
    val typeAhead = StringBuilder()

    def start(): Unit = if !plain && thread.isEmpty then
      saved = Some(terminal.enterRawMode())
      out.print(Ansi.Esc + "[?2004h")
      out.flush()
      running = true
      val t = Thread(() => loop(), "atc-keys")
      t.setDaemon(true)
      thread = Some(t)
      t.start()

    def stop(): Unit =
      running = false
      pauseLock.synchronized(pauseLock.notifyAll())
      thread.foreach(t => t.join(2500))
      thread = None
      saved.foreach(terminal.setAttributes)
      saved = None
      if !plain then
        out.print(Ansi.Esc + "[?2004l")
        out.flush()

    /** Run `body` with the key thread idle (a pop-up is about to read the terminal). */
    def withPaused[T](body: => T): T =
      pauseLock.synchronized { pauseDepth += 1 }
      try
        pauseLock.synchronized:
          while reading do pauseLock.wait(50)
        body
      finally
        pauseLock.synchronized:
          pauseDepth -= 1
          if pauseDepth == 0 && running then
            out.print(Ansi.Esc + "[?2004h")
            out.flush()
          pauseLock.notifyAll()

    /** Swallow the rest of an escape sequence (arrow keys, function keys): its
      * bytes are all ≥ 32 and would otherwise land in the type-ahead as
      * `[A`-style garbage. */
    private def loop(): Unit =
      val in: NonBlockingReader = terminal.reader()
      var skipLf = false // a CR already added the newline of a CRLF
      var pasting = false
      def submit(): Unit =
        val text = typeAhead.toString.trim
        if text.nonEmpty then
          onSubmit(text)
          typeAhead.clear()
      while running do
        val mayRead = pauseLock.synchronized:
          while pauseDepth > 0 && running do pauseLock.wait(50)
          reading = running
          reading
        if mayRead then
          try
            val c =
              try in.read(100L)
              catch case _: Exception => -1
            c match
              case NonBlockingReader.READ_EXPIRED | -1 => () // no key read: leave skipLf pending
              case '\r' =>
                if pasting then typeAhead.append('\n') else submit()
                skipLf = true
              // Collapse only a CRLF pair: an LF right after a CR. Any other real key
              // clears the latch, so a later lone LF is not wrongly swallowed as the
              // tail of an old CR (`skipLf` is reset in every branch below but '\r').
              case '\n' =>
                if !skipLf then
                  if pasting then typeAhead.append('\n') else submit()
                skipLf = false
              case other =>
                skipLf = false
                other match
                  case 15 => toggleExpanded() // Ctrl-O
                  case 27 => Tui.readEscapeSequence(() => in.read(30L)) match
                      case "[200~" => pasting = true
                      case "[201~" => pasting = false
                      case _ => ()
                  case 127 | 8 =>
                    if typeAhead.nonEmpty then
                      val end = typeAhead.length
                      val removed =
                        if end >= 2 && Character.isSurrogatePair(typeAhead.charAt(end - 2), typeAhead.charAt(end - 1))
                        then 2
                        else 1
                      typeAhead.setLength(end - removed)
                  // ch.toChar alone would truncate a non-BMP code point; UTF-16 units
                  // (a reader that delivers surrogates) pass through reassembled.
                  case ch if ch > 0xffff => typeAhead.append(String(Character.toChars(ch)))
                  case ch if ch >= 32 => typeAhead.append(ch.toChar)
                  case _ => ()
            frame:
              draftInput = typeAhead.toString
              refreshStatus()
          finally
            pauseLock.synchronized:
              reading = false
              pauseLock.notifyAll()

    /** Hand the type-ahead to the next prompt. */
    def takeTypeAhead(): String =
      val text = typeAhead.toString
      typeAhead.clear()
      draftInput = ""
      text

  // ── pop-ups: permission requests and questions from the agent ─────

  /** Run one jline-prompt pop-up named "a" and read its result; `None` on
    * Ctrl-C/Ctrl-D. jline-prompt echoes the chosen *id* after the message once
    * the user confirms, so menu ids are the visible labels (made unique). */
  private def popup[R](hint: String)(define: PromptBuilder => Unit)(read: PromptResult[?] => Option[R]): Option[R] =
    keys.withPaused(withPromptHint(hint)(runPopup(define)(read)))

  private def runPopup[R](define: PromptBuilder => Unit)(read: PromptResult[?] => Option[R]): Option[R] =
    flushOutput()
    val config = if g == Glyphs.ascii then PrompterConfig.windows() else PrompterConfig.defaults()
    val prompter = PrompterFactory.create(terminal, config.withCancellableFirstPrompt(true))
    val builder = prompter.newBuilder()
    define(builder)
    try Option(prompter.prompt(List.empty[AttributedString].asJava, builder.build()).get("a")).flatMap(read)
    catch case _: UserInterruptException | _: EndOfFileException => None
      // The prompter redraws the answer, clears the menu below it and prints one
      // more newline (`DefaultPrompter.close`), so the cursor is already past a
      // blank line: tell `blankLine` so the block does not get a second one.
    finally tail = "\n\n"

  /** A single-choice menu, returning its index so duplicate display labels do
    * not collapse into the first option. */
  private def menuIndex(message: String, labels: List[String]): Option[Int] =
    val byId = Tui.uniqueIds(labels).zip(labels)
    popup(s"Arrows move ${g.dot} Enter confirm ${g.dot} Ctrl-C cancel") { b =>
      val lp = b.createListPrompt().name("a").message(message)
      byId.foreach((id, l) => lp.add(id, l))
      lp.addPrompt()
    } {
      case r: ListResult => byId.map(_._1).zipWithIndex.toMap.get(r.getSelectedId)
      case _ => None
    }

  private def menu(message: String, labels: List[String]): Option[String] =
    menuIndex(message, labels).flatMap(labels.lift)

  /** A multi-choice menu; `Some(Nil)` if nothing was ticked. Indices preserve
    * the identity of duplicate labels. */
  private def checkboxIndices(message: String, labels: List[String]): Option[List[Int]] =
    val byId = Tui.uniqueIds(labels).zip(labels)
    popup(s"Space toggle ${g.dot} Enter confirm ${g.dot} Ctrl-C cancel") { b =>
      val cb = b.createCheckboxPrompt().name("a").message(message)
      byId.foreach((id, l) => cb.add(id, l))
      cb.addPrompt()
    } {
      case r: CheckboxResult =>
        val indices = byId.map(_._1).zipWithIndex.toMap
        Some(r.getSelectedIds.asScala.toList.flatMap(indices.get))
      case _ => None
    }

  /** Whether pop-up menus can be drawn (a real terminal). */
  def menusAvailable: Boolean = !plain

  /** A pop-up is a block of its own: a pending TODO panel is drawn first, then
    * `body` (which reads the terminal), then the blank line that ends the block. */
  private def popupBlock[T](body: => T): T = keys.withPaused:
    frame:
      popupDepth += 1
      liveOutput.end()
      flushTodos()
      beginBlock()
    try body
    finally frame:
        blankLine()
        popupDepth -= 1
        if popupDepth == 0 then
          while pendingProcessEvents.nonEmpty do displayProcessEvent(pendingProcessEvents.dequeue())

  /** A single-choice pop-up for a slash command (`/model`, `/classifiedmodel`).
    * `None` when there is no terminal for menus, no options, or the user
    * cancelled with Ctrl-C/Ctrl-D. */
  def choose(title: String, options: List[String]): Option[String] =
    if plain || options.isEmpty then None
    else
      val clean = options.map(Ansi.sanitize)
      popupBlock(menuIndex(Ansi.sanitize(title), clean)).flatMap(options.lift)

  def askPermission(req: PermissionRequest): Decision = withOperation("waiting for permission"):
    popupBlock:
      // The request embeds model-chosen paths and command lines: sanitize.
      write(Indent + styled(s"${g.warn} Permission request: ${Ansi.sanitize(req.title)}", Yellow, Bold) + "\n")
      req.details.foreach { detail =>
        TextLayout.wrap(Ansi.sanitize(detail), width - 5)
          .foreach(line => write(Indent + Indent + styled(line, Yellow) + "\n"))
      }
      val decision =
        if plain then
          Tui.permissionReply(freeText(styled("Allow? [y]es once / [s]ession / [n]o / type instructions: ", Yellow)))
        else
          var selected: Option[Decision] = None
          while selected.isEmpty do
            selected = menu("Allow?", List(Tui.AllowOnce, Tui.AllowSession, Tui.DenyLabel, Tui.ReviseLabel)) match
              case Some(Tui.AllowOnce) => Some(Decision.AllowOnce)
              case Some(Tui.AllowSession) => Some(Decision.AllowSession)
              case Some(Tui.ReviseLabel) =>
                info("Describe what to change. The current request will not be approved.")
                freeText(styled("instructions> ", Cyan)).map(Decision.Revise(_))
              case _ => Some(Decision.Deny)
          selected.get
      // The menu already echoes the choice; confirm only what the user did not see.
      decision match
        case Decision.Revise(instructions) =>
          write(Indent + styled(s"${g.arrow} instructions sent: ${Ansi.sanitize(instructions)}", Cyan) + "\n")
        case _ if plain || decision == Decision.Deny =>
          val label = if decision == Decision.AllowOnce then styled(s"${g.arrow} allowed once", Green)
          else if decision == Decision.AllowSession then styled(s"${g.arrow} allowed for this session", Green)
          else styled(s"${g.arrow} denied", Red)
          write(Indent + label + "\n")
        case _ => ()
      decision

  /** A yes/no question from the app itself (setup, not the agent): a menu
    * when there is a terminal, a `[y/N]` line otherwise. Cancelling means no. */
  def confirm(question: String): Boolean = popupBlock:
    write(Indent + styled("? " + Ansi.sanitize(question), Cyan, Bold) + "\n")
    val yes =
      if plain then
        freeText(styled("[y/N]: ", Cyan)).exists(_.toLowerCase(java.util.Locale.ROOT).startsWith("y"))
      else menu("Choose", List(Tui.YesLabel, Tui.NoLabel)).contains(Tui.YesLabel)
    if plain then
      write(Indent + styled(s"${g.arrow} ${if yes then "yes" else "no"}", if yes then Green else Red) + "\n")
    yes

  /** Ask the user a question on behalf of the agent. Options render as a
    * menu (or checkboxes when `multiple`), always with a custom-answer
    * entry; no options → a free-text line. `None` on Ctrl-C/Ctrl-D. */
  def askUser(question: String, options: List[String], multiple: Boolean): Option[String] =
    withOperation("waiting for your answer"):
      popupBlock:
        // The question and options are model-written: sanitize.
        write(Indent + styled("? " + Ansi.sanitize(question), Cyan, Bold) + "\n")
        val cleanOptions = options.map(Ansi.sanitize(_))
        val answerPrompt = styled("answer> ", Cyan)
        val answer: Option[String] =
          if cleanOptions.isEmpty || plain then
            cleanOptions.foreach(o => write(Indent + Indent + styled(s"- $o", Cyan) + "\n"))
            if cleanOptions.nonEmpty then info("Choose a listed answer or type your own answer or instructions.")
            freeText(answerPrompt)
          else if multiple then
            checkboxIndices("Choose answers", cleanOptions :+ Tui.AddAnswerLabel) match
              case None => None
              case Some(ids) =>
                val chosen = ids.sorted.filter(_ < cleanOptions.size).flatMap(cleanOptions.lift)
                if ids.contains(cleanOptions.size) then freeText(answerPrompt).map(t => (chosen :+ t).mkString("; "))
                else if chosen.isEmpty then None
                else Some(chosen.mkString("; "))
          else
            menuIndex("Choose an answer", cleanOptions :+ Tui.OtherLabel) match
              case Some(i) if i == cleanOptions.size => freeText(answerPrompt)
              case Some(i) => cleanOptions.lift(i)
              case None => None
        // A single-choice menu echoes the selection itself; confirm the other outcomes.
        answer match
          case Some(a) if cleanOptions.isEmpty || plain || multiple || !cleanOptions.contains(a) =>
            write(Indent + styled(s"${g.arrow} ${Ansi.sanitize(a)}", Green) + "\n")
          case Some(_) => ()
          case None => write(Indent + styled(s"${g.arrow} No answer", Dim) + "\n")
        answer

  private def freeText(prompt: String): Option[String] = keys.withPaused:
    withPromptHint(s"Enter send ${g.dot} Ctrl-C cancel"):
      flushOutput()
      try Tui.readAnswer(reader.readLine(prompt))
      finally tail = "\n"

  // ── TODO panel ────────────────────────────────────────────────────

  /** Record a change; the panel is drawn once when the current tool call
    * ends (or before the next pop-up), not on every `markTodo`. */
  def showTodos(todos: List[Todo]): Unit = pendingTodos = Some(todos)

  private def flushTodos(): Unit =
    pendingTodos.foreach(showTodosNow)
    pendingTodos = None

  def showTodosNow(todos: List[Todo]): Unit = frame:
    stopSpinner()
    ensureNewline()
    val empty = if todos.isEmpty then styled(" (empty)", Dim) else ""
    write(Indent + styled(s"${g.todo} TODO", Blue, Bold) + empty + "\n")
    todos.foreach { t =>
      val text = Ansi.sanitize(t.text) // model-written
      val line = t.status match
        case TodoStatus.Done => styled(s"${g.done} $text", Dim)
        case TodoStatus.InProgress => styled(s"${g.inProgress} $text", Yellow)
        case TodoStatus.Pending => s"${g.pending} $text"
      TextLayout.wrap(line, width - 5).foreach(row => write(Indent + Indent + row + "\n"))
    }

  // ── input ─────────────────────────────────────────────────────────

  /** Read one input; `None` on EOF (Ctrl-D). Ctrl-C clears it. Keys typed
    * during the previous turn are pre-filled. The input may span lines (a
    * pasted block, Shift+Enter or `\`+Enter, a `/run` with brackets still
    * open: [[Continuation]]); the trailing empty line that submits is removed. */
  def readLine(prompt: String): Option[String] = readBuffer(prompt).map(_.stripTrailing)

  /** Read a block of code: Enter adds a line until one is left empty, which
    * submits the block (without that line); `None` on EOF. */
  def readBlock(prompt: String): Option[String] =
    blockMode = true
    try readBuffer(prompt).map(_.stripTrailing)
    finally blockMode = false

  private def readBuffer(prompt: String): Option[String] =
    var result: Option[String] = None
    var again = true
    while again do
      try
        result = Some(reader.readLine(
          styled(prompt, Cyan, Bold),
          null: String | Null,
          null: org.jline.reader.MaskingCallback | Null,
          keys.takeTypeAhead()
        ))
        again = false
      catch
        case _: UserInterruptException =>
          Thread.interrupted()
          again = !blockMode
        case e: EndOfFileException =>
          Debug.log(s"EOF on input: ${e.getMessage}"); Debug.trace(e)
          result = None; again = false
        case e: Throwable =>
          Debug.log(s"readLine failed: $e"); Debug.trace(e)
          throw e
      finally tail = "\n" // the reader echoed the line and moved to the next one
    result

  /** Offer `text` as the predicted next message: ghost text at the prompt,
    * redrawn at once if the user is already at it. `None` withdraws it. */
  def suggest(text: Option[String]): Unit =
    // Model-generated text: strip terminal control before it is drawn as ghost text
    // (and, on Tab/→, inserted into the input buffer), like every other model output.
    suggestion = if plain then None else text.map(t => Ansi.sanitize(t.trim)).filter(_.nonEmpty)
    if !plain then
      // Redraws under the reader's lock, and only while it is actually reading.
      try reader.callWidget(LineReader.REDISPLAY)
      catch case _: IllegalStateException => ()

  /** Whether the terminal can show ghost text (a real terminal). */
  def suggestionsAvailable: Boolean = !plain

  def close(): Unit =
    if closed then return
    closed = true
    stopSpinner()
    keys.stop()
    synchronized(flushOutput())
    statusLine.foreach(_.close())
    try reader.getHistory.save()
    catch case _: Exception => ()
    terminal.close()

object Tui:
  /** Draw the one-line footer and flush it. JLine's `Status.update` flushes the text itself
    * but leaves the closing synchronized-update sequence (`ESC[?2026l`) in the buffered
    * writer: a terminal honouring mode 2026 (xterm.js/VS Code, iTerm2, kitty, Ghostty, WezTerm)
    * then keeps rendering frozen until something else flushes, so a footer repainted from the
    * input-poll clock stalled the whole window for up to a poll interval per repaint. */
  private[atc] def drawStatus(terminal: Terminal, status: Status, text: String): Unit =
    status.update(List(AttributedString(text)).asJava)
    terminal.writer().flush()

  /** Replace owned rows with the cursor initially just below them. Leave everything below
    * that region intact and finish immediately below the replacement. */
  private[atc] def replaceRows(before: List[String], after: List[String], force: Boolean = false): String =
    val prefix = if force then 0 else before.zip(after).takeWhile((a, b) => a == b).size
    val update = StringBuilder()
    if before.size > prefix then update.append(s"\r${Ansi.Esc}[${before.size - prefix}A")
    after.drop(prefix).foreach(line => update.append(Ansi.ClearLine).append(line).append('\n'))
    val removed = (before.size - after.size).max(0)
    for row <- 0 until removed do
      if row > 0 then update.append(s"${Ansi.Esc}[1B")
      update.append(Ansi.ClearLine)
    if removed > 1 then update.append(s"${Ansi.Esc}[${removed - 1}A")
    update.toString

  /** Cancel the input field and consume JLine's interrupt before another prompt reads. */
  private[atc] def readAnswer(read: => String): Option[String] =
    try Some(read).map(_.trim).filter(_.nonEmpty)
    catch
      case _: UserInterruptException =>
        Thread.interrupted()
        None
      case _: EndOfFileException => None

  /** Plain permission prompts accept exact approvals; every other answer is feedback. */
  private[atc] def permissionReply(answer: Option[String]): Decision =
    answer.map(_.trim).filter(_.nonEmpty) match
      case None => Decision.Deny
      case Some(text) => text.toLowerCase(java.util.Locale.ROOT) match
          case "y" | "yes" => Decision.AllowOnce
          case "s" | "session" => Decision.AllowSession
          case "n" | "no" => Decision.Deny
          case _ => Decision.Revise(text)

  /** Consume CSI/SS3 bytes after ESC, stopping on a final byte, EOF or timeout. */
  private[atc] def readEscapeSequence(read: () => Int): String =
    val result = StringBuilder()
    def next(): Int =
      try
        val char = read()
        if char >= 0 then result.append(char.toChar)
        char
      catch case _: java.io.IOException => -1
    next() match
      case '[' =>
        var char = next()
        while result.length < 64 && char >= 0 && !(char >= 0x40 && char <= 0x7e) do char = next()
      case 'O' => next(); ()
      case _ => ()
    result.toString

  /** A scripted `-p` run never needs console discovery or raw mode. Giving it
    * a known dumb UTF-8 terminal also avoids platform-specific null encodings
    * when Windows redirects stdin/stdout (as CI and normal pipelines do). */
  private[atc] def openTerminal(
    nonInteractive: Boolean,
    input: InputStream = System.in.nn,
    output: OutputStream = System.out.nn,
  ): Terminal =
    if nonInteractive then
      new DumbTerminal("atc", Terminal.TYPE_DUMB, input, output, StandardCharsets.UTF_8)
    else TerminalBuilder.builder().system(true).graphemeCluster(false).build().nn

  /** Choose safe layout characters. Dumb/non-interactive terminals may report
    * no encoding at all, in which case ASCII is the only sound default. */
  private[atc] def glyphs(encoding: java.nio.charset.Charset | Null, forceAscii: Boolean): Glyphs =
    if !forceAscii && Option(encoding).exists(_.name.nn.toUpperCase(java.util.Locale.ROOT).contains("UTF")) then
      Glyphs.unicode
    else Glyphs.ascii

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

  /** Lines of a tool-result section before it is cut in the middle. */
  val MaxPanelLines = 30
  /** Terminal rows of live output shown before the rest is folded, and the
    * number of lines in the live tail that replaces it. */
  val FoldAfterRows = 10
  val FoldTail = 10
  /** Lines of reasoning shown live in the thinking window. */
  val ThinkingWindow = 5
  /** Cap on the text a live tail window retains (the front is dropped whole lines). */
  val MaxHeldChars = 1024 * 1024
  /** What a turn cost, for the summary line `endTurn` prints. */
  final case class TurnStats(
    seconds: Double,
    toolCalls: Int,
    tokens: Long,
    context: Long,
    window: Option[Int],
    outcome: TurnOutcome = TurnOutcome.Finished,
  )

  /** `context 45.2k/200k (23%)`, or `context ~45.2k` when the model's window is unknown. */
  def contextUsage(tokens: Long, window: Option[Int]): String = window match
    case Some(w) if w > 0 => s"context ${count(tokens)}/${count(w)} (${(tokens * 100 + w / 2) / w}%)"
    case _ => s"context ~${count(tokens)}"

  /** Where a chunk of text lands on screen: the number of terminal rows it
    * adds to a section whose last row already holds `column` characters, and
    * the column it ends at (0 when it ended the line). A fresh row starts with
    * a gutter, hence `indent`; a line always takes at least one row. */
  def place(column: Int, text: String, width: Int, indent: Int): (rows: Int, column: Int) =
    val body = text.stripSuffix("\n")
    val start = if column == 0 then indent else column
    val end = start + displayWidth(body)
    val rows = math.max(1, (end + width - 1) / width)
    (
      rows = if column == 0 then rows else rows - 1, // the row we started on was already counted
      column = if text.endsWith("\n") then 0 else { val c = end % width; if c == 0 then width else c },
    )

  /** Width in terminal cells of one code point placed at column `col`: a tab
    * advances to the next multiple of 8, wide (CJK) code points count 2, other
    * controls 0. `String.length` counts UTF-16 units and undercounts all of these,
    * which would let "one row" lines wrap and corrupt the live regions. */
  private def cellWidth(cp: Int, col: Int): Int =
    if cp == '\t' then 8 - (col % 8) else math.max(0, org.jline.utils.WCWidth.wcwidth(cp))

  /** Display width in terminal cells of `s` starting at column 0; SGR sequences take none. */
  def displayWidth(s: String): Int =
    var w = 0
    var i = 0
    while i < s.length do
      val styleEnd = sgrEnd(s, i)
      if styleEnd > 0 then i = styleEnd
      else
        val cp = s.codePointAt(i)
        w += cellWidth(cp, w)
        i += Character.charCount(cp)
    w

  /** The end of the SGR sequence (`ESC [ … m`) starting at `i`, or -1 when there is none. */
  private[atc] def sgrEnd(s: String, i: Int): Int =
    if i + 1 < s.length && s.charAt(i) == '\u001b' && s.charAt(i + 1) == '[' then
      var k = i + 2
      while k < s.length && (s.charAt(k).isDigit || s.charAt(k) == ';') do k += 1
      if k < s.length && s.charAt(k) == 'm' then k + 1 else -1
    else -1

  /** Bounded output buffer with incremental line counts. `tail(n)` scans backward
    * for the requested lines. Overflow discards a prefix at a line boundary when
    * possible, or within a line when necessary to enforce the character limit. */
  private[atc] final class TailBuffer(cap: Int):
    private val sb = StringBuilder()
    private var newlines = 0L
    def append(text: String): Unit =
      newlines += text.count(_ == '\n')
      sb.append(text)
      if sb.length > cap then
        val nl = sb.indexOf("\n", sb.length - cap)
        sb.delete(0, if nl >= 0 then nl + 1 else sb.length - cap)
    /** Everything retained (the whole text while under the cap). */
    def text: String = sb.toString
    /** Lines ever appended (each `\n` ends one), plus an unfinished last line. */
    def lineCount: Long = newlines + (if sb.nonEmpty && sb.charAt(sb.length - 1) != '\n' then 1 else 0)
    /** The last `n` lines (an unfinished last line counts; a trailing newline is not a line). */
    def tail(n: Int): List[String] =
      var i = if sb.nonEmpty && sb.charAt(sb.length - 1) == '\n' then sb.length - 2 else sb.length - 1
      var nl = 0
      while i >= 0 && nl < n do { if sb.charAt(i) == '\n' then nl += 1; i -= 1 }
      // `nl < n` means we ran off the front before finding n newlines (return all);
      // otherwise `i` sits just before the n-th newline from the end — even at -1
      // when that newline is the very first char, so `i + 2` is the correct start.
      val text = if nl < n then sb.toString else sb.substring(i + 2)
      text.split("\n", -1).toList match
        case init :+ "" => init
        case ls => ls
    def clear(): Unit = { sb.clear(); newlines = 0 }

  /** `1 line`, `2 lines`. */
  def plural(n: Long, noun: String): String = s"$n $noun${if n == 1 then "" else "s"}"

  def duration(secs: Double): String =
    if secs < 10 then f"$secs%.1f s"
    else if secs < 60 then s"${secs.round} s"
    else
      val total = secs.round // round first: 119.6 s is "2 min 0 s", not "1 min 60 s"
      s"${total / 60} min ${total % 60} s"

  /** `1234` → `1.2k`, `200000` → `200k`, `1234567` → `1.2M`. */
  def count(n: Long): String =
    def short(x: Double, unit: String) = (if x == x.floor then f"$x%.0f" else f"$x%.1f") + unit
    if n < 1000 then n.toString
    else if n < 1_000_000 then short(n / 1e3, "k")
    else short(n / 1e6, "M")

  val AllowOnce = "Allow once"
  val AllowSession = "Allow for this session"
  val DenyLabel = "Deny this request"
  val ReviseLabel = "Tell the agent what to change"
  val OtherLabel = "Write a different answer"
  val AddAnswerLabel = "Add an answer or instructions"
  val YesLabel = "Yes"
  val NoLabel = "No"

  /** Menu ids are the labels where possible; collisions get numeric suffixes
    * that are themselves checked (so `a`, `a (1)`, `a` still stays unique). */
  def uniqueIds(labels: List[String]): List[String] =
    val used = collection.mutable.Set[String]()
    labels.map { l =>
      var n = 0
      var candidate = l
      while used.contains(candidate) do
        n += 1
        candidate = s"$l ($n)"
      used += candidate
      candidate
    }

  /** The REPL output without the agent's own prints. The host wrote those to
    * the very same stream, so they occur verbatim and contiguously: remove the
    * first occurrence of `printed`. If it cannot be found (truncated capture),
    * the body is shown as is. */
  def withoutPrinted(body: String, printed: String): String =
    val p = printed.stripTrailing()
    if p.isEmpty then body
    else
      val i = body.indexOf(p)
      if i < 0 then body
      else
        val before = body.take(i).stripTrailing()
        val after = body.drop(i + p.length).stripLeading()
        if before.isEmpty then after
        else if after.isEmpty then before
        else before + "\n" + after
