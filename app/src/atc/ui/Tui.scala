package atc.ui

import atc.Debug
import atc.agent.AgentUI
import atc.lib.{Todo, TodoStatus}
import atc.perms.*
import atc.sandbox.ExecutionResult

import org.jline.prompt.{CheckboxResult, ListResult, PromptBuilder, PromptResult, PrompterConfig, PrompterFactory}
import org.jline.reader.{EndOfFileException, LineReader, LineReaderBuilder, UserInterruptException}
import org.jline.reader.impl.history.DefaultHistory
import org.jline.terminal.{Attributes, Terminal, TerminalBuilder}
import org.jline.utils.{AttributedString, InfoCmp, NonBlockingReader}

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

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
  *   │ hello                             (folded after 15 lines: "⋯ N more lines" + the last 5, live)
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
  private val reader: LineReader =
    Files.createDirectories(historyFile.getParent)
    LineReaderBuilder.builder()
      .terminal(terminal)
      .history(DefaultHistory())
      .variable(LineReader.HISTORY_FILE, historyFile)
      .variable(LineReader.SECONDARY_PROMPT_PATTERN, "%M> ")
      .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
      .build()

  /** Not a real terminal (piped / `-p` in a script): no spinner, no cursor tricks, no menus, nothing folded. */
  private val plain: Boolean = terminal.getType == Terminal.TYPE_DUMB || terminal.getType == Terminal.TYPE_DUMB_COLOR
  private val g: Tui.Glyphs =
    if terminal.encoding().name.toUpperCase.contains("UTF") && System.getenv("ATC_ASCII") == null then
      Tui.Glyphs.unicode
    else Tui.Glyphs.ascii

  // ── styles (by role) ──────────────────────────────────────────────

  /** SGR codes. Rendered by hand rather than through JLine's `toAnsi`, which
    * rewrites box-drawing glyphs (into the DEC charset or plain ASCII). */
  private val Bold = 1
  private val Dim = 2
  private val Red = 31
  private val Green = 32
  private val Yellow = 33 // warnings, permissions, classified
  private val Blue = 34 // TODO panel, fenced code in prose
  private val Magenta = 35 // tool calls
  private val Cyan = 36 // the user, questions

  /** Colours the terminal supports; 0 (no styling at all) when there is no real terminal. */
  private val colors: Int =
    if plain then 0
    else Option(terminal.getNumericCapability(InfoCmp.Capability.max_colors)).map(_.intValue).getOrElse(0)
  private def styled(s: String, codes: Int*): String =
    if colors <= 0 || s.isEmpty then s else s"\u001b[${codes.mkString(";")}m$s\u001b[0m"
  private val Reset = "\u001b[0m"
  private val ClearLine = "\r\u001b[2K"
  private val Indent = "  "
  private def width: Int = { val w = terminal.getWidth; if w <= 0 then 80 else w }

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
      endThinking()
      closeProse()
      endOutput()
      toolOpen = false
      flushTodos()
      stats.foreach { s =>
        ensureNewline()
        val calls = s"${s.toolCalls} tool call${if s.toolCalls == 1 then "" else "s"}"
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
  private def beginBlock(): Unit = { stopSpinner(); endThinking(); closeProse(); blankLine() }

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
      if drawn > 0 then { out.print(s"\r\u001b[${drawn}A\u001b[J"); out.flush(); tail = tailBefore }
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

  private val thinkBuf = StringBuilder()
  private var thinkRegion: Option[LiveRegion] = None
  /** Thinking is being streamed in full (plain or expanded mode). */
  private var thinkOpen = false
  private var thinkStart = 0L

  def thinkingDelta(text: String): Unit = synchronized:
    if text.isEmpty then return
    stopSpinner()
    if thinkStart == 0L then thinkStart = System.nanoTime()
    thinkBuf.append(text)
    if plain || expanded then
      if thinkOpen then writeGuttered(text, Indent) else renderThinking()
    else renderThinking()

  /** (Re)draw the reasoning gathered so far in the current mode: in full
    * (plain/expanded — the block stays open and later deltas append) or as
    * a live window over its last lines. */
  private def renderThinking(): Unit =
    if plain || expanded then
      thinkRegion.foreach(_.clear()); thinkRegion = None
      beginBlock()
      write(styled(g.bullet, Dim) + " " + styled("thinking", Dim) + "\n")
      if colors > 0 then out.print(s"\u001b[${Dim}m")
      thinkOpen = true
      writeGuttered(thinkBuf.toString.dropWhile(_ == '\n'), Indent)
    else
      val region = thinkRegion.getOrElse {
        beginBlock()
        val r = LiveRegion(); thinkRegion = Some(r); r
      }
      region.redraw(thinkWindow())

  /** The live window: header + the last few lines of the reasoning so far. */
  private def thinkWindow(): List[String] =
    val header = styled(s"${g.bullet} thinking${g.ellipsis} (Ctrl-O to expand)", Dim)
    val lines = thinkBuf.toString.linesIterator.toList
    header :: lines.takeRight(Tui.ThinkingWindow).map(l => Indent + styled(fit(l, Indent.length), Dim))

  /** Thinking ended (answer text or tool call follows): a window collapses to
    * a summary line; reasoning shown in full just ends. */
  private def endThinking(): Unit = if thinkOpen || thinkRegion.isDefined then
    if thinkOpen then
      if colors > 0 then out.print(Reset)
      ensureNewline()
      thinkOpen = false
    thinkRegion.foreach { r =>
      r.clear()
      val secs = (System.nanoTime() - thinkStart) / 1e9
      val n = thinkBuf.toString.linesIterator.count(_.trim.nonEmpty)
      write(styled(
        s"${g.bullet} thought for ${Tui.duration(secs)} ${g.dot} $n line${if n == 1 then "" else "s"}",
        Dim
      ) + "\n")
    }
    thinkRegion = None
    thinkBuf.clear()
    thinkStart = 0L

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
    endThinking()
    ensureNewline()
    if text.nonEmpty then status(text)
  def assistantEnd(): Unit = synchronized { stopSpinner(); endThinking(); closeProse(); ensureNewline() }

  // ── tool blocks ───────────────────────────────────────────────────

  def toolStart(code: String): Unit = synchronized:
    beginBlock()
    write(styled(g.bullet, Magenta) + " " + styled("run_scala", Magenta, Bold) + "\n")
    val lines = if colors > 0 then Highlight.scala(code) else code.linesIterator.toList
    lines.foreach(l => write(gutter(Magenta) + l + "\n"))
    toolOpen = true
    outputStarted = false
    printed.clear()
    outDirectLines = 0
    folding = false
    foldBuf.clear()
    if !plain then spin(Indent, "running") // until the first output line / the result

  /** Agent-visible text printed during the current tool call, as it appears
    * in the REPL's captured output; `toolEnd` subtracts it from the result panel. */
  private val printed = StringBuilder()

  // Folding of long live output: the first `FoldAfter` lines are written as
  // they come; from then on the text goes to a live tail window ("⋯ N more
  // lines" + the last `FoldTail` lines) unless the view is expanded.
  private var outDirectLines = 0
  private var folding = false
  private val foldBuf = StringBuilder()
  private var foldRegion: Option[LiveRegion] = None

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
    val body =
      if agentText == userText then userText
      else styled("[classified] ", Yellow, Bold) + styled(userText, Yellow)
    if plain || expanded || !toolOpen then writeGuttered(body, gutter(Dim))
    else if folding then foldAppend(body)
    else
      // write whole lines until the fold threshold, then start folding
      var rest = body
      while rest.nonEmpty && !folding do
        val nl = rest.indexOf('\n')
        if nl < 0 then { writeGuttered(rest, gutter(Dim)); rest = "" }
        else
          writeGuttered(rest.take(nl + 1), gutter(Dim))
          rest = rest.drop(nl + 1)
          outDirectLines += 1
          if outDirectLines >= Tui.FoldAfter then folding = true
      if rest.nonEmpty then foldAppend(rest)

  private def foldAppend(text: String): Unit =
    foldBuf.append(text)
    val region = foldRegion.getOrElse { val r = LiveRegion(); foldRegion = Some(r); r }
    region.redraw(foldWindow())

  private def foldWindow(): List[String] =
    val lines = foldBuf.toString.split("\n", -1).toList match
      case init :+ "" => init // an unfinished last line is shown; a trailing newline is not a line
      case ls => ls
    val hidden = lines.length - Tui.FoldTail
    val head = if hidden > 0 then
      List(gutter(Dim) + styled(s"${g.ellipsis} $hidden more lines (Ctrl-O to expand)", Dim))
    else Nil
    head ++ lines.takeRight(Tui.FoldTail).map(l => gutter(Dim) + fit(l, Indent.length + 2))

  /** The output section is over: whatever the tail window shows stays on screen. */
  private def endOutput(): Unit =
    foldRegion.foreach(_.freeze())
    foldRegion = None
    folding = false
    foldBuf.clear()

  private def section(label: String, code: Int): String =
    Indent + styled(s"${g.tee} $label", code) + "\n"

  /** Close the tool block: what the REPL produced *besides* the agent's own
    * prints (those were shown live) — diagnostics, echoed values, exceptions —
    * then the verdict. Long bodies are cut in the middle (unless expanded) so
    * both the first diagnostics and the tail stay visible. */
  def toolEnd(r: ExecutionResult, millis: Long): Unit = synchronized:
    stopSpinner()
    endOutput()
    ensureNewline()
    val body =
      List(Option(ExecutionResult.trimStackFrames(r.output)).filter(_.nonEmpty), r.error).flatten.mkString("\n")
    val lines = Tui.withoutPrinted(body, printed.toString).linesIterator.toList
    if lines.nonEmpty then
      val shown =
        if lines.length <= Tui.MaxPanelLines || plain || expanded then lines
        else
          lines.take(Tui.MaxPanelLines * 2 / 3) ++
            List(s"${g.ellipsis} ${lines.length - Tui.MaxPanelLines} lines omitted (Ctrl-O to expand next time)") ++
            lines.takeRight(Tui.MaxPanelLines / 3)
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
    // Take down what is live, say what happened, then re-render it in the new mode.
    val thinking = thinkRegion.isDefined || thinkOpen
    thinkRegion.foreach(_.clear()); thinkRegion = None
    if thinkOpen then { if colors > 0 then out.print(Reset); ensureNewline(); thinkOpen = false }
    val foldText = foldBuf.toString
    foldRegion.foreach(_.clear()); foldRegion = None
    foldBuf.clear()
    info(if expanded then "expanded view (Ctrl-O to collapse)" else "compact view (Ctrl-O to expand)")
    if thinking then renderThinking() // the whole reasoning so far (expanded) or a window over it (compact)
    if expanded then
      if foldText.nonEmpty then writeGuttered(foldText, gutter(Dim)) // what was held back
      folding = false
    else if toolOpen && outputStarted then folding = true // fold from here on

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
    endThinking()
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
    try Option(prompter.prompt(java.util.List.of[AttributedString](), builder.build()).get("a")).flatMap(read)
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

  def askPermission(req: PermissionRequest): Decision =
    synchronized { flushTodos(); beginBlock() }
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
    blankLine()
    decision

  /** Ask the user a question on behalf of the agent. Options render as a
    * menu (or checkboxes when `multiple`), always with an "Other" free-text
    * entry; no options → a free-text line. `None` on Ctrl-C/Ctrl-D. */
  def askUser(question: String, options: List[String], multiple: Boolean): Option[String] =
    synchronized { flushTodos(); beginBlock() }
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
    blankLine()
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

  /** Read a line; `None` on EOF (Ctrl-D). Ctrl-C clears the line. Keys typed
    * during the previous turn are pre-filled. */
  def readLine(prompt: String): Option[String] =
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

  def close(): Unit =
    stopSpinner()
    keys.stop()
    try reader.getHistory.save()
    catch case _: Exception => ()
    terminal.close()

object Tui:
  /** Lines of a tool-result section before it is cut in the middle. */
  val MaxPanelLines = 40
  /** Live output lines shown before the rest is folded, and the size of the live tail. */
  val FoldAfter = 15
  val FoldTail = 5
  /** Lines of reasoning shown live in the thinking window. */
  val ThinkingWindow = 5
  /** What a turn cost, for the summary line `endTurn` prints. */
  final case class TurnStats(seconds: Double, toolCalls: Int, tokens: Long)

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

  /** The characters that draw the layout; ASCII when the terminal is not UTF-8 (or `ATC_ASCII` is set). */
  final case class Glyphs(
    bullet: String,
    bar: String,
    tee: String,
    end: String,
    arrow: String,
    warn: String,
    cross: String,
    todo: String,
    done: String,
    inProgress: String,
    pending: String,
    bullet2: String,
    quote: String,
    rule: String,
    ellipsis: String,
    dot: String,
    junction: String,
    spinner: IndexedSeq[String]
  )
  object Glyphs:
    val unicode: Glyphs = Glyphs(
      "●",
      "│",
      "├",
      "└",
      "→",
      "⚠",
      "✗",
      "▸",
      "✓",
      "▶",
      "○",
      "•",
      "▎",
      "─",
      "…",
      "·",
      "┼",
      IndexedSeq("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
    )
    val ascii: Glyphs = Glyphs(
      "*",
      "|",
      "+",
      "`",
      "->",
      "!",
      "x",
      ">",
      "[x]",
      "[>]",
      "[ ]",
      "-",
      ">",
      "-",
      "...",
      "-",
      "+",
      IndexedSeq("|", "/", "-", "\\")
    )

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
