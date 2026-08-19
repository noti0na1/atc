package atc.ui

import atc.Debug
import atc.agent.AgentUI
import atc.lib.{Todo, TodoStatus}
import atc.perms.*
import atc.sandbox.ExecutionResult

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
import org.jline.utils.{AttributedString, AttributedStringBuilder, AttributedStyle, InfoCmp, NonBlockingReader}

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

import Ansi.{Blue, Bold, ClearLine, Cyan, Dim, Green, Magenta, Red, Reset, Yellow}

/** Terminal front-end. Every kind of content has one shape so a glance tells
  * them apart:
  *
  * {{{
  * > user request                        cyan prompt
  *
  * ● thinking… (last lines, live)        dim; collapses to "● thought for 8 s · 40 lines"
  *
  * ● assistant prose                     bullet, 2-space indent, Markdown rendered
  *
  * ● run_scala                           tool block, magenta
  *   │ code                              magenta gutter, syntax-coloured
  *   ├ output                            live program output, dim gutter
  *   │ hello                             (folded after 15 rows: "⋯ N more lines" + the last 5, live)
  *   ├ result  /  ├ error                what the REPL added: echoes, diagnostics
  *   │ val x: Int = 1
  *   └ ok 34 ms  /  └ failed 34 ms
  *
  *   ▸ TODO  ✓ done  ▶ in progress  ○ pending
  *
  *   ⚠ Permission request …             pop-ups (yellow); ? questions (cyan)
  * }}}
  *
  * Ctrl-O during a turn toggles the *expanded* view: thinking streams in
  * full and output is never folded (the toggle sticks for the session).
  * Without a real terminal (`-p` in a pipe) everything is shown in full.
  *
  * Blocks are separated by one blank line. Everything goes through one
  * `write`, which remembers the last characters written so gutters can be
  * inserted at line starts even when text arrives in arbitrary chunks. */
final class Tui(historyFile: Path) extends AgentUI:
  // No grapheme-cluster probing: it sends a DECRQM query to the terminal and
  // waits for a reply, which swallows early input on ptys that don't answer.
  val terminal: Terminal = TerminalBuilder.builder().system(true).graphemeCluster(false).build()
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
    Files.createDirectories(historyFile.getParent)
    LineReaderBuilder.builder()
      .terminal(terminal)
      .history(DefaultHistory())
      .highlighter(ghostHighlighter)
      .completer(commandCompleter)
      .parser(continuationParser)
      .variable(LineReader.HISTORY_FILE, historyFile)
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
        (Option(KeyMap.key(terminal, InfoCmp.Capability.back_tab)).toList :+ "\u001b[Z").distinct.filter(_.nonEmpty)
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
    if terminal.encoding().name.toUpperCase.contains("UTF") && !sys.env.contains("ATC_ASCII") then Glyphs.unicode
    else Glyphs.ascii

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
  /** TODO list changed during the current tool call; drawn once when it ends. */
  @volatile private var pendingTodos: Option[List[Todo]] = None
  /** Ctrl-O: show thinking in full and never fold output. Sticks for the session. */
  @volatile private var expanded = false

  /** Extra action on Ctrl-C during a turn (e.g. interrupt the REPL evaluation). */
  @volatile var onInterrupt: () => Unit = () => ()

  terminal.handle(Terminal.Signal.INT, _ => if busy then { interrupted.set(true); onInterrupt() })

  def beginTurn(): Unit =
    interrupted.set(false)
    busy = true
    keys.start()
  /** End the turn: close open blocks, say what the turn cost (`stats`) and
    * leave one blank line before the next prompt — the agent is idle again. */
  def endTurn(stats: Option[Tui.TurnStats] = None): Unit =
    synchronized:
      stopSpinner()
      busy = false
      thinking.end()
      closeProse()
      liveOutput.end()
      toolOpen = false
      flushTodos()
      stats.foreach { s =>
        ensureNewline()
        val calls = Tui.plural(s.toolCalls, "tool call")
        write(styled(
          s"${g.bullet} worked for ${Tui.duration(s.seconds)} ${g.dot} $calls ${g.dot} ${Tui.count(s.tokens)} tokens",
          Dim
        ) + "\n")
      }
      blankLine()
    keys.stop() // outside the lock: the key thread may be waiting for it
  def isInterrupted: Boolean = interrupted.get()

  // ── low-level writing ─────────────────────────────────────────────

  private def write(s: String): Unit =
    if s.nonEmpty then
      out.print(s)
      out.flush()
      tail = (tail + s).takeRight(2)

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
    val parts = text.split("\n", -1)
    var i = 0
    while i < parts.length do
      val seg = parts(i)
      val last = i == parts.length - 1
      if atLineStart && (seg.nonEmpty || (!last && !gutter.isBlank)) then write(gutter)
      write(seg)
      if !last then write("\n")
      i += 1

  private def gutter(code: Int): String = Indent + styled(g.bar, code) + " "
  /** Visible width of `gutter`: the indent plus the bar and its space. */
  private val GutterWidth = Indent.length + 2

  /** Cut a plain line so it fits on one terminal row (region lines must not wrap). */
  private def fit(line: String, used: Int): String =
    val room = width - used - 1
    if room <= 1 || line.length <= room then line else line.take(room - 1) + g.ellipsis

  /** A few lines at the bottom of the screen that are redrawn in place
    * (cursor up + clear to end of screen). Lines must not wrap. */
  private final class LiveRegion:
    private var drawn = 0
    private var tailBefore = tail
    def active: Boolean = drawn > 0
    def redraw(lines: List[String]): Unit =
      if drawn > 0 then { out.print(s"\r${Ansi.Esc}[${drawn}A${Ansi.Esc}[J"); out.flush(); tail = tailBefore }
      else { ensureNewline(); tailBefore = tail }
      lines.foreach(l => write(l + "\n"))
      drawn = lines.length
    def clear(): Unit = redraw(Nil)
    /** Keep what is drawn as ordinary output. */
    def freeze(): Unit = drawn = 0

  // ── plain lines (banner, slash commands, notices) ─────────────────

  def println(s: String = ""): Unit = { stopSpinner(); ensureNewline(); write(s + "\n") }
  def info(s: String): Unit = println(styled(s, Dim))
  def success(s: String): Unit = println(styled(s, Green))
  def warn(s: String): Unit = println(styled(s"${g.warn} $s", Yellow))
  def error(s: String): Unit = println(styled(s"${g.cross} $s", Red))
  /** The start-up banner: a title, aligned `label → value` rows and a dim hint line. */
  def banner(title: String, rows: List[(String, String)], hint: String): Unit =
    stopSpinner()
    ensureNewline()
    write(styled(s"${g.bullet} $title", Cyan, Bold) + "\n")
    val labelWidth = rows.map(_._1.length).maxOption.getOrElse(0)
    rows.foreach((label, value) => write(Indent + styled(label.padTo(labelWidth, ' '), Dim) + "  " + value + "\n"))
    write(Indent + styled(hint, Dim) + "\n")

  // ── thinking (streamed reasoning) ─────────────────────────────────

  def thinkingDelta(text: String): Unit = synchronized(thinking.delta(text))

  /** The model's reasoning as it streams. Compact view: a live window over the
    * last lines that collapses to a one-line summary when the reasoning ends.
    * Plain or expanded view: written out in full, the block staying open so
    * later deltas simply append. */
  private object thinking:
    private val buf = StringBuilder()
    private var region: Option[LiveRegion] = None
    /** Written out in full (plain/expanded), so the block is still open. */
    private var streaming = false
    private var started = 0L

    def active: Boolean = streaming || region.isDefined

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
        if colors > 0 then out.print(Ansi.sgr(Dim))
        streaming = true
        writeGuttered(buf.toString.dropWhile(_ == '\n'), Indent)
      else
        val live = region.getOrElse { beginBlock(); val r = LiveRegion(); region = Some(r); r }
        live.redraw(window())

    /** Take the rendering off the screen but keep the reasoning (Ctrl-O). */
    def detach(): Unit =
      clearRegion()
      if streaming then
        if colors > 0 then out.print(Reset)
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
      val lines = buf.toString.linesIterator.count(_.trim.nonEmpty)
      styled(s"${g.bullet} thought for ${Tui.duration(secs)} ${g.dot} ${Tui.plural(lines, "line")}", Dim) + "\n"

    /** Header + the last few lines of the reasoning so far. */
    private def window(): List[String] =
      styled(s"${g.bullet} thinking${g.ellipsis} (Ctrl-O to expand)", Dim) ::
        buf.toString.linesIterator.toList.takeRight(Tui.ThinkingWindow)
          .map(l => Indent + styled(fit(l, Indent.length), Dim))

  // ── assistant prose (streamed) ────────────────────────────────────

  def assistantDelta(text: String): Unit = synchronized:
    stopSpinner()
    prose match
      case Some(md) => writeGuttered(md.push(text), Indent)
      case None =>
        val t = text.dropWhile(_ == '\n')
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
        Highlight.scala
      )
    else MarkdownStream.plain

  /** Flush what the Markdown renderer still holds and end the prose block. */
  private def closeProse(): Unit =
    prose.foreach(md => writeGuttered(md.finish(), Indent))
    prose = None

  /** Out-of-band note during streaming ("web search"): a spinner until the
    * next text; empty = paragraph break within the same prose block. */
  def assistantNote(text: String): Unit = synchronized:
    stopSpinner()
    thinking.end()
    ensureNewline()
    if text.nonEmpty then status(text)
  def assistantEnd(): Unit = synchronized { stopSpinner(); thinking.end(); closeProse(); ensureNewline() }

  // ── tool blocks ───────────────────────────────────────────────────

  def toolStart(code: String): Unit = toolStart(code, "run_scala")
  /** Open a code block titled `title`: the agent's `run_scala`, or the user's own `/run`. */
  def toolStart(code: String, title: String): Unit = synchronized:
    beginBlock()
    write(styled(g.bullet, Magenta) + " " + styled(title, Magenta, Bold) + "\n")
    val lines = if colors > 0 then Highlight.scala(code) else code.linesIterator.toList
    lines.foreach(l => write(gutter(Magenta) + l + "\n"))
    toolOpen = true
    outputStarted = false
    printed.clear()
    liveOutput.start()
    if !plain then spin(Indent, "running") // until the first output line / the result

  /** Live output of the agent's `println` (see `HostOutput.print`). Classified
    * content — where the two texts differ — is marked so the user knows the
    * model cannot see it. */
  def agentPrint(agentText: String, userText: String): Unit = synchronized:
    stopSpinner()
    printed.append(agentText)
    if toolOpen && !outputStarted then
      ensureNewline()
      write(section("output", Dim))
      outputStarted = true
    if agentText == userText then liveOutput.emit(userText)
    else liveOutput.emit(styled("[classified] ", Yellow, Bold) + styled(userText, Yellow))

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
    private val held = StringBuilder()
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
      region.foreach(_.freeze())
      region = None
      folding = false
      held.clear()

    /** Ctrl-O: take the window down and hand back the text it was hiding. */
    def detach(): String =
      val hidden = held.toString
      region.foreach(_.clear())
      region = None
      held.clear()
      hidden

    def foldFromHere(): Unit = folding = true
    def showEverything(): Unit = folding = false

    private def fold(text: String): Unit =
      held.append(text)
      val live = region.getOrElse { val r = LiveRegion(); region = Some(r); r }
      live.redraw(window())

    private def window(): List[String] =
      val lines = held.toString.split("\n", -1).toList match
        case init :+ "" => init // an unfinished last line is shown; a trailing newline is not a line
        case ls => ls
      val hidden = lines.length - Tui.FoldTail
      val header =
        if hidden > 0 then
          List(gutter(Dim) + styled(s"${g.ellipsis} ${Tui.plural(hidden, "more line")} (Ctrl-O to expand)", Dim))
        else Nil
      header ++ lines.takeRight(Tui.FoldTail).map(l => gutter(Dim) + fit(l, GutterWidth))

  private def section(label: String, code: Int): String =
    Indent + styled(s"${g.tee} $label", code) + "\n"

  /** Close the tool block: what the REPL produced *besides* the agent's own
    * prints (those were shown live) — diagnostics, echoed values, exceptions —
    * then the verdict. Long bodies are cut in the middle (unless expanded) so
    * both the first diagnostics and the tail stay visible. */
  def toolEnd(r: ExecutionResult, millis: Long): Unit = synchronized:
    stopSpinner()
    liveOutput.end()
    ensureNewline()
    val body =
      List(Option(ExecutionResult.trimStackFrames(r.output)).filter(_.nonEmpty), r.error).flatten.mkString("\n")
    val lines = Tui.withoutPrinted(body, printed.toString).linesIterator.toList
    if lines.nonEmpty then
      val kept =
        if lines.length <= Tui.MaxPanelLines || plain || expanded then lines
        else
          lines.take(Tui.MaxPanelLines * 2 / 3) ++
            List(s"${g.ellipsis} ${lines.length - Tui.MaxPanelLines} lines omitted (Ctrl-O to expand next time)") ++
            lines.takeRight(Tui.MaxPanelLines / 3)
      // One row per line, so the panel's line budget really is a row budget:
      // diagnostics and echoed values are often far wider than the terminal.
      val shown = if plain || expanded then kept else kept.map(fit(_, GutterWidth))
      if r.success then
        write(section("result", Dim))
        shown.foreach(l => write(gutter(Dim) + styled(l, Dim) + "\n"))
      else
        write(section("error", Red))
        shown.foreach(l => write(gutter(Red) + l + "\n"))
    val verdict =
      if r.success then styled(s"${g.end} ok ${millis} ms", Green) else styled(s"${g.end} failed ${millis} ms", Red)
    write(Indent + verdict + "\n")
    toolOpen = false
    flushTodos()

  // ── expanded / compact toggle (Ctrl-O) ────────────────────────────

  private def toggleExpanded(): Unit = synchronized:
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
        out.print(ClearLine + prefix + styled(s"${g.spinner(i % g.spinner.length)} $text${g.ellipsis}$elapsed", Dim))
        out.flush()
        i += 1
        try Thread.sleep(80)
        catch case _: InterruptedException => running = false
    def stopAndClear(): Unit =
      running = false
      interrupt()
      try join(200)
      catch case _: InterruptedException => ()
      out.print(ClearLine) // back to the line start we began on; `tail` still ends with "\n"
      out.flush()

  /** Show progress ("model is thinking"); ends the current prose block. */
  def status(text: String): Unit = synchronized:
    stopSpinner()
    thinking.end()
    closeProse()
    ensureNewline()
    if plain then write(styled(s"~ $text...", Dim) + "\n") else spin("", text)

  private def spin(prefix: String, text: String): Unit =
    val s = Spinner(prefix, text)
    spinner = Some(s)
    s.start()

  private def stopSpinner(): Unit = synchronized:
    spinner.foreach(_.stopAndClear())
    spinner = None

  // ── keys during a turn (Ctrl-O toggle, type-ahead) ────────────────

  /** While a turn runs the terminal is in raw mode and this thread reads
    * single keys: Ctrl-O toggles the expanded view, everything printable is
    * kept as type-ahead for the next prompt. Paused around pop-ups, which
    * read the terminal themselves. Never used without a real terminal. */
  private object keys:
    private var thread: Option[Thread] = None
    private var saved: Option[Attributes] = None
    @volatile private var running = false
    @volatile private var paused = false
    @volatile private var reading = false
    val typeAhead = StringBuilder()

    def start(): Unit = if !plain && thread.isEmpty then
      saved = Some(terminal.enterRawMode())
      running = true
      val t = Thread(() => loop(), "atc-keys")
      t.setDaemon(true)
      thread = Some(t)
      t.start()

    def stop(): Unit =
      running = false
      thread.foreach(t => t.join(500))
      thread = None
      saved.foreach(terminal.setAttributes)
      saved = None

    /** Run `body` with the key thread idle (a pop-up is about to read the terminal). */
    def withPaused[T](body: => T): T =
      paused = true
      var waited = 0
      while reading && waited < 300 do { Thread.sleep(5); waited += 5 }
      try body
      finally paused = false

    private def loop(): Unit =
      val in: NonBlockingReader = terminal.reader()
      while running do
        if paused then Thread.sleep(20)
        else
          reading = true
          val c =
            try in.read(100L)
            catch case _: Exception => -1
          reading = false
          c match
            case NonBlockingReader.READ_EXPIRED | -1 => ()
            case 15 => toggleExpanded() // Ctrl-O
            case 127 | 8 => if typeAhead.nonEmpty then typeAhead.setLength(typeAhead.length - 1)
            case ch if ch >= 32 && ch != 127 => typeAhead.append(ch.toChar)
            case _ => ()

    /** Hand the type-ahead to the next prompt. */
    def takeTypeAhead(): String = { val s = typeAhead.toString; typeAhead.clear(); s }

  // ── pop-ups: permission requests and questions from the agent ─────

  /** Run one jline-prompt pop-up named "a" and read its result; `None` on
    * Ctrl-C/Ctrl-D. jline-prompt echoes the chosen *id* after the message once
    * the user confirms, so menu ids are the visible labels (made unique). */
  private def popup[R](define: PromptBuilder => Unit)(read: PromptResult[?] => Option[R]): Option[R] = keys.withPaused:
    val prompter = PrompterFactory.create(terminal, PrompterConfig.defaults().withCancellableFirstPrompt(true))
    val builder = prompter.newBuilder()
    define(builder)
    try Option(prompter.prompt(List.empty[AttributedString].asJava, builder.build()).get("a")).flatMap(read)
    catch case _: UserInterruptException | _: EndOfFileException => None
    finally tail = "\n" // the prompter leaves the cursor at a fresh line

  /** A single-choice menu. */
  private def menu(message: String, labels: List[String]): Option[String] =
    val byId = Tui.uniqueIds(labels).zip(labels)
    popup { b =>
      val lp = b.createListPrompt().name("a").message(message)
      byId.foreach((id, l) => lp.add(id, l))
      lp.addPrompt()
    } {
      case r: ListResult => byId.toMap.get(r.getSelectedId)
      case _ => None
    }

  /** A multi-choice menu; `Some(Nil)` if nothing was ticked. */
  private def checkboxes(message: String, labels: List[String]): Option[List[String]] =
    val byId = Tui.uniqueIds(labels).zip(labels)
    popup { b =>
      val cb = b.createCheckboxPrompt().name("a").message(message)
      byId.foreach((id, l) => cb.add(id, l))
      cb.addPrompt()
    } {
      case r: CheckboxResult => Some(r.getSelectedIds.asScala.toList.flatMap(byId.toMap.get))
      case _ => None
    }

  /** Whether pop-up menus can be drawn (a real terminal). */
  def menusAvailable: Boolean = !plain

  /** A pop-up is a block of its own: a pending TODO panel is drawn first, then
    * `body` (which reads the terminal), then the blank line that ends the block. */
  private def popupBlock[T](body: => T): T =
    synchronized { flushTodos(); beginBlock() }
    try body
    finally blankLine()

  /** A single-choice pop-up for a slash command (`/model`, `/classifiedmodel`).
    * `None` when there is no terminal for menus, no options, or the user
    * cancelled with Ctrl-C/Ctrl-D. */
  def choose(title: String, options: List[String]): Option[String] =
    if plain || options.isEmpty then None else popupBlock(menu(title, options))

  def askPermission(req: PermissionRequest): Decision = popupBlock:
    write(Indent + styled(s"${g.warn} Permission request: ${req.title}", Yellow, Bold) + "\n")
    req.details.foreach(d => write(Indent + Indent + styled(d, Yellow) + "\n"))
    val decision =
      if plain then
        // No menus without a terminal: a one-letter answer on a line.
        freeText(styled("Allow? [y]es once / [s]ession / [n]o: ", Yellow)).map(_.toLowerCase) match
          case Some(a) if a.startsWith("y") => Decision.AllowOnce
          case Some(a) if a.startsWith("s") => Decision.AllowSession
          case _ => Decision.Deny
      else
        menu("Allow?", List(Tui.AllowOnce, Tui.AllowSession, Tui.DenyLabel)) match
          case Some(Tui.AllowOnce) => Decision.AllowOnce
          case Some(Tui.AllowSession) => Decision.AllowSession
          case _ => Decision.Deny
    // The menu already echoes the choice; confirm only what the user did not see.
    if plain || decision == Decision.Deny then
      val label = decision match
        case Decision.AllowOnce => styled(s"${g.arrow} allowed once", Green)
        case Decision.AllowSession => styled(s"${g.arrow} allowed for this session", Green)
        case Decision.Deny => styled(s"${g.arrow} denied", Red)
      write(Indent + label + "\n")
    decision

  /** A yes/no question from the app itself (setup, not the agent): a menu
    * when there is a terminal, a `[y/N]` line otherwise. Cancelling means no. */
  def confirm(question: String): Boolean = popupBlock:
    write(Indent + styled("? " + question, Cyan, Bold) + "\n")
    val yes =
      if plain then freeText(styled("[y/N]: ", Cyan)).exists(_.toLowerCase.startsWith("y"))
      else menu("Choose", List(Tui.YesLabel, Tui.NoLabel)).contains(Tui.YesLabel)
    if plain then
      write(Indent + styled(s"${g.arrow} ${if yes then "yes" else "no"}", if yes then Green else Red) + "\n")
    yes

  /** Ask the user a question on behalf of the agent. Options render as a
    * menu (or checkboxes when `multiple`), always with an "Other" free-text
    * entry; no options → a free-text line. `None` on Ctrl-C/Ctrl-D. */
  def askUser(question: String, options: List[String], multiple: Boolean): Option[String] = popupBlock:
    write(Indent + styled("? " + question, Cyan, Bold) + "\n")
    val answerPrompt = styled("answer> ", Cyan)
    val answer: Option[String] =
      if options.isEmpty || plain then
        options.foreach(o => write(Indent + Indent + styled(s"- $o", Cyan) + "\n"))
        freeText(answerPrompt)
      else if multiple then
        checkboxes("Select (space to toggle, enter to confirm)", options :+ Tui.OtherLabel) match
          case None => None
          case Some(ids) =>
            val chosen = ids.filter(_ != Tui.OtherLabel)
            if ids.contains(Tui.OtherLabel) then freeText(answerPrompt).map(t => (chosen :+ t).mkString("; "))
            else if chosen.isEmpty then None
            else Some(chosen.mkString("; "))
      else
        menu("Choose", options :+ Tui.OtherLabel) match
          case Some(Tui.OtherLabel) => freeText(answerPrompt)
          case other => other
    // A single-choice menu echoes the selection itself; confirm the other outcomes.
    answer match
      case Some(a) if options.isEmpty || plain || multiple || !options.contains(a) =>
        write(Indent + styled(s"${g.arrow} $a", Green) + "\n")
      case Some(_) => ()
      case None => write(Indent + styled(s"${g.arrow} (no answer)", Red) + "\n")
    answer

  private def freeText(prompt: String): Option[String] = keys.withPaused:
    try Some(reader.readLine(prompt)).map(_.trim).filter(_.nonEmpty)
    catch
      case _: UserInterruptException | _: EndOfFileException => None
    finally tail = "\n"

  // ── TODO panel ────────────────────────────────────────────────────

  /** Record a change; the panel is drawn once when the current tool call
    * ends (or before the next pop-up), not on every `markTodo`. */
  def showTodos(todos: List[Todo]): Unit = pendingTodos = Some(todos)

  private def flushTodos(): Unit =
    pendingTodos.foreach(showTodosNow)
    pendingTodos = None

  def showTodosNow(todos: List[Todo]): Unit =
    stopSpinner()
    ensureNewline()
    val empty = if todos.isEmpty then styled(" (empty)", Dim) else ""
    write(Indent + styled(s"${g.todo} TODO", Blue, Bold) + empty + "\n")
    todos.foreach { t =>
      val line = t.status match
        case TodoStatus.Done => styled(s"${g.done} ${t.text}", Dim)
        case TodoStatus.InProgress => styled(s"${g.inProgress} ${t.text}", Yellow)
        case TodoStatus.Pending => s"${g.pending} ${t.text}"
      write(Indent + Indent + line + "\n")
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
        case _: UserInterruptException => again = true
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
    suggestion = if plain then None else text.map(_.trim).filter(_.nonEmpty)
    if !plain then
      // Redraws under the reader's lock, and only while it is actually reading.
      try reader.callWidget(LineReader.REDISPLAY)
      catch case _: IllegalStateException => ()

  /** Whether the terminal can show ghost text (a real terminal). */
  def suggestionsAvailable: Boolean = !plain

  def close(): Unit =
    stopSpinner()
    keys.stop()
    try reader.getHistory.save()
    catch case _: Exception => ()
    terminal.close()

object Tui:
  /** Lines of a tool-result section before it is cut in the middle. */
  val MaxPanelLines = 40
  /** Terminal rows of live output shown before the rest is folded, and the
    * number of lines in the live tail that replaces it. */
  val FoldAfterRows = 15
  val FoldTail = 5
  /** Lines of reasoning shown live in the thinking window. */
  val ThinkingWindow = 5
  /** What a turn cost, for the summary line `endTurn` prints. */
  final case class TurnStats(seconds: Double, toolCalls: Int, tokens: Long)

  /** Where a chunk of text lands on screen: the number of terminal rows it
    * adds to a section whose last row already holds `column` characters, and
    * the column it ends at (0 when it ended the line). A fresh row starts with
    * a gutter, hence `indent`; a line always takes at least one row. */
  def place(column: Int, text: String, width: Int, indent: Int): (rows: Int, column: Int) =
    val body = text.stripSuffix("\n")
    val start = if column == 0 then indent else column
    val end = start + body.length
    val rows = math.max(1, (end + width - 1) / width)
    (
      rows = if column == 0 then rows else rows - 1, // the row we started on was already counted
      column = if text.endsWith("\n") then 0 else { val c = end % width; if c == 0 then width else c },
    )

  /** `1 line`, `2 lines`. */
  def plural(n: Long, noun: String): String = s"$n $noun${if n == 1 then "" else "s"}"

  def duration(secs: Double): String =
    if secs < 10 then f"$secs%.1f s"
    else if secs < 60 then s"${secs.round} s"
    else s"${(secs / 60).toInt} min ${(secs % 60).round} s"

  /** `1234` → `1.2k`, `1234567` → `1.2M`. */
  def count(n: Long): String =
    if n < 1000 then n.toString
    else if n < 1_000_000 then f"${n / 1e3}%.1fk"
    else f"${n / 1e6}%.1fM"

  val AllowOnce = "Yes, this time"
  val AllowSession = "Yes, for the rest of this session"
  val DenyLabel = "No"
  val OtherLabel = "Other (type an answer)"
  val YesLabel = "Yes"
  val NoLabel = "No"

  /** Menu ids are the labels; duplicates get a numeric suffix so they stay unique. */
  def uniqueIds(labels: List[String]): List[String] =
    val seen = collection.mutable.Map[String, Int]()
    labels.map { l =>
      val n = seen.getOrElse(l, 0)
      seen(l) = n + 1
      if n == 0 then l else s"$l ($n)"
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
