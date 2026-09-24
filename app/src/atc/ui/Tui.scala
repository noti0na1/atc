package atc.ui

import atc.{Debug, ProcessEnvironment}
import atc.agent.{AgentUI, TurnOutcome}
import atc.lib.{Todo, TodoStatus}
import atc.perms.*
import atc.host.FileChange
import atc.sandbox.{ExecutionResult, ReplSession}

import org.jline.reader.{EndOfFileException, UserInterruptException}
import org.jline.terminal.{Terminal, TerminalBuilder}
import org.jline.terminal.impl.DumbTerminal
import org.jline.utils.{AttributedString, Status}

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

import Ansi.{Blue, Bold, Cyan, Dim, Green, Magenta, Red, Yellow}

/** JLine terminal interface for input, streaming responses, tool output and menus.
  * Ctrl-O toggles expanded output; non-interactive terminals print all output.
  * The views it composes write through one [[Screen]], whose monitor is the TUI's
  * lock. See `doc/development.md` for the layout and keyboard controls. */
final class Tui(historyFile: Path, nonInteractive: Boolean = false) extends AgentUI:
  private val historyPath = PromptReader.secureHistoryFile(historyFile)
  // No grapheme-cluster probing: it sends a DECRQM query to the terminal and
  // waits for a reply, which swallows early input on ptys that don't answer.
  private val terminal: Terminal = Tui.openTerminal(nonInteractive)
  Debug.log(
    s"terminal: ${terminal.getClass.getSimpleName} type=${terminal.getType} size=${terminal.getSize} encoding=${terminal.encoding()}"
  )
  /** Not a real terminal (piped / `-p` in a script): no spinner, no cursor tricks, no menus, nothing folded. */
  private val plain: Boolean = terminal.getType == Terminal.TYPE_DUMB || terminal.getType == Terminal.TYPE_DUMB_COLOR
  private val screen =
    Screen(terminal, plain, Tui.glyphs(terminal.encoding(), ProcessEnvironment.contains("ATC_ASCII")))
  import screen.{Indent, GutterWidth, blankLine, ensureNewline, frame, g, gutter, styled, width, write, writeGuttered}

  private val alerts = Alerts(terminal, plain, text => screen.frame(screen.writeStyle(text)))
  private val prompt = PromptReader(screen, historyPath, alerts)
  private val menus = Menus(screen, alerts)

  /** Notifies the user when a turn ends or a question waits (config `notifications`). */
  def notifier: Notifier = alerts.notifier
  def notifier_=(value: Notifier): Unit = alerts.notifier = value

  /** Tab completion for slash commands (see [[PromptReader.completions]]). */
  def completions: List[String] => List[String] = prompt.completions
  def completions_=(value: List[String] => List[String]): Unit = prompt.completions = value

  // ── state ─────────────────────────────────────────────────────────

  /** Set while an agent turn is running; Ctrl-C sets it. */
  private val interrupted = AtomicBoolean(false)
  @volatile private var busy = false
  @volatile private var closed = false
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
  /** TODO list changed during the current tool call; drawn once when it ends. */
  @volatile private var pendingTodos: Option[List[Todo]] = None
  /** Ctrl-O: show thinking in full and never fold output. Sticks for the session. */
  @volatile private var expanded = false

  private val thinking = ThinkingView(screen, () => expanded, () => beginBlock())
  private val liveOutput = LiveOutput(screen, () => plain || expanded || !toolOpen)

  /** Extra action on Ctrl-C during a turn (e.g. interrupt the REPL evaluation). */
  @volatile var onInterrupt: () => Unit = () => ()
  @volatile var onSubmit: String => Unit = _ => ()
  @volatile private var contextLabel: String = ""
  @volatile var queuedInputs: () => Int = () => 0
  @volatile private var operation = "ready"
  @volatile private var turnStarted = 0L
  @volatile private var draftInput = ""
  @volatile private var promptHint = ""

  private val keys = KeyReader(
    terminal,
    plain,
    alerts,
    () => toggleExpanded(),
    text => onSubmit(text),
    text => frame { draftInput = text; refreshStatus() },
  )

  def fileChanged(change: FileChange): Unit = screen.synchronized:
    if toolOpen && fileChanges.size < 50 then fileChanges += change

  def clearOutputHistory(): Unit = screen.synchronized(toolHistory.clear())

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

  // ── status line ───────────────────────────────────────────────────

  private val statusLine = if plain then None else Option(Status.getStatus(terminal))
  private var statusSize = (0, 0)
  private var lastStatus = ""
  statusLine.foreach { status =>
    status.setBorder(false)
    // Reserve the footer before writing content; growing it later scrolls the first lines away.
    Tui.drawStatus(terminal, status, "")
  }

  def setContext(model: String, mode: String, directory: String): Unit = screen.synchronized:
    contextLabel = s"$model ${g.dot} $mode ${g.dot} $directory"
    titleBase = s"atc ${g.dot} $directory"
    alerts.title = titleBase
    refreshStatus()

  // ── window title ──────────────────────────────────────────────────

  /** `atc · <directory>`, marked while a turn runs (`●`) or a pop-up waits during one (`?`),
    * so a tab that needs the user stands out. The terminal's own title is saved first
    * (xterm's title stack) and restored by `close`. */
  private var titleBase = "atc"
  private var lastTitle = ""
  if !plain then screen.frame(screen.writeStyle(s"${Ansi.Esc}[22;0t"))

  private def refreshTitle(): Unit = if !plain && !closed then
    val marker = if busy && popupDepth > 0 then "? " else if busy then s"${g.bullet} " else ""
    val title = Ansi.sanitize(marker + titleBase)
    if title != lastTitle then
      lastTitle = title
      screen.writeStyle(s"${Ansi.Esc}]0;$title\u0007")
      screen.flush()

  private def refreshStatus(): Unit =
    refreshTitle()
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
      val text = screen.fit(singleLine, 0)
      if resized || text != lastStatus then
        screen.flush()
        Tui.drawStatus(terminal, status, text)
        lastStatus = text
    }

  override def inputAccepted(text: String): Unit = frame:
    beginBlock()
    write(styled(s"${g.arrow} applying update: ${Ansi.sanitize(text)}", Cyan) + "\n")
    refreshStatus()

  private def withOperation[A](label: String)(body: => A): A =
    val previous = operation
    screen.synchronized { operation = label; refreshStatus() }
    try body
    finally screen.synchronized { operation = previous; refreshStatus() }

  private def setOperation(label: String): Unit =
    if operation != label then
      operation = label
      refreshStatus()

  private def withPromptHint[A](hint: String)(body: => A): A =
    val previous = promptHint
    screen.synchronized { promptHint = hint; refreshStatus() }
    try body
    finally screen.synchronized { promptHint = previous; refreshStatus() }

  // ── turns ─────────────────────────────────────────────────────────

  /** Whether an exhausted tool budget asks the human to continue (off for `-p` runs). */
  @volatile var askToContinue: Boolean = true

  override def confirmMoreToolCalls(used: Int, budget: Int): Boolean =
    if askToContinue then alerts.alert(s"Tool limit reached after ${Tui.plural(used, "call")}. Continue?")
    askToContinue && confirm(
      s"Tool limit reached after ${Tui.plural(used, "call")}. Allow ${Tui.plural(budget, "more call")}?"
    )

  terminal.handle(
    Terminal.Signal.INT,
    _ =>
      alerts.touch()
      if busy then { interrupted.set(true); onInterrupt() }
  )
  terminal.handle(
    Terminal.Signal.WINCH,
    _ =>
      frame {
        screen.resized()
        refreshStatus()
        thinking.resize()
        liveOutput.resize()
      }
  )

  def beginTurn(): Unit =
    frame:
      interrupted.set(false)
      alerts.beginTurn()
      turnStarted = System.nanoTime()
      busy = true
      operation = "starting turn"
      refreshStatus()
    keys.start()
  /** End the turn: close open blocks, say what the turn cost (`stats`) and
    * leave one blank line before the next prompt. */
  def endTurn(stats: Option[Tui.TurnStats] = None): Unit =
    frame:
      screen.stopSpinner()
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
    stats.foreach(alerts.turnEnded)
  def isInterrupted: Boolean = interrupted.get()

  /** Start a new block: blank line before it, and no prose/thinking block is open any more. */
  private def beginBlock(): Unit = { screen.stopSpinner(); thinking.end(); closeProse(); blankLine() }

  // ── plain lines (banner, slash commands, notices) ─────────────────

  private def renderedLine(s: String): Unit = frame:
    screen.stopSpinner()
    ensureNewline()
    val lines = if plain then s.split("\n", -1).toList else TextLayout.wrap(s, width - 1)
    lines.foreach(line => write(line + "\n"))
  /** A plain line supplied by the application. Config values, policy summaries
    * and paths may be repository-controlled, so terminal controls never pass. */
  def println(s: String = ""): Unit = renderedLine(Ansi.sanitize(s))
  def info(s: String): Unit = renderedLine(styled(Ansi.sanitize(s), Dim))
  def preview(s: String): Unit = info(screen.fit(Ansi.sanitize(s).replace('\n', ' '), 0))
  def success(s: String): Unit = renderedLine(styled(Ansi.sanitize(s), Green))
  def warn(s: String): Unit = renderedLine(styled(s"${g.warn} ${Ansi.sanitize(s)}", Yellow))
  def error(s: String): Unit =
    if busy then alerts.turnError(s)
    renderedLine(styled(s"${g.cross} ${Ansi.sanitize(s)}", Red))

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
    screen.stopSpinner()
    ensureNewline()
    write(styled(s"${g.bullet} ${Ansi.sanitize(title)}", Cyan, Bold) + "\n")
    // Values can be paths (possibly named by an attacker in a cloned repo): sanitize.
    TextLayout.fields(rows.map((label, value) => styled(Ansi.sanitize(label), Dim) -> Ansi.sanitize(value)), width)
      .foreach(line => write(line + "\n"))
    val separated = Ansi.sanitize(hint).replace(" · ", s" ${g.dot} ")
    val controls = if g == Glyphs.ascii then separated.replace("→", "Right") else separated
    TextLayout.wrap(controls, width - 3).foreach(line => write(Indent + styled(line, Dim) + "\n"))

  // ── model output: reasoning and prose ─────────────────────────────

  def thinkingDelta(text: String): Unit = frame:
    setOperation("reasoning")
    thinking.delta(Ansi.sanitize(text))

  def assistantDelta(text: String): Unit = frame:
    setOperation("responding")
    screen.stopSpinner()
    val clean = Ansi.sanitize(text) // model text: no terminal control may reach the screen
    prose match
      case Some(md) =>
        alerts.proseDelta(clean, newBlock = false)
        writeGuttered(md.push(clean), Indent)
      case None =>
        val t = clean.dropWhile(_ == '\n')
        if t.nonEmpty then
          alerts.proseDelta(t, newBlock = true)
          beginBlock()
          write(styled(g.bullet, Bold) + " ")
          val md = newProse()
          prose = Some(md)
          writeGuttered(md.push(t), Indent)

  /** Markdown rendering for one prose block; plain pass-through when there are no colours. */
  private def newProse(): MarkdownStream =
    if screen.colors > 0 then
      MarkdownStream(
        MarkdownStream.Glyphs(g.bullet2, g.quote, g.rule, styled(g.bar, Blue) + " ", g.bar, g.junction),
        Highlight.scalaTail,
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
    screen.stopSpinner()
    thinking.end()
    ensureNewline()
    if text.nonEmpty then status(text)
  def assistantEnd(): Unit = frame { screen.stopSpinner(); thinking.end(); closeProse(); ensureNewline() }

  /** Show progress ("model is thinking"); ends the current prose block. */
  def status(text: String): Unit = frame:
    operation = text
    refreshStatus()
    screen.stopSpinner()
    thinking.end()
    closeProse()
    ensureNewline()
    if plain then write(styled(s"~ ${Ansi.sanitize(text)}...", Dim) + "\n")
    else if statusLine.isEmpty then screen.spin("", Ansi.sanitize(text))

  // ── tool blocks ───────────────────────────────────────────────────

  def toolStart(code: String): Unit = toolStart(code, "run_scala")
  /** Open a code block titled `title`: the agent's `run_scala`, or the user's own `/run`. */
  def toolStart(code: String, title: String): Unit = frame:
    setOperation("running Scala")
    beginBlock()
    write(styled(g.bullet, Magenta) + " " + styled(title, Magenta, Bold) + "\n")
    // The code is model-written: sanitize before highlighting/printing.
    val lines =
      if screen.colors > 0 then Highlight.scala(Ansi.sanitize(code)) else Ansi.sanitize(code).linesIterator.toList
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
    if !plain && statusLine.isEmpty then screen.spin(Indent, "running")

  /** Live output of the agent's `println` (see `HostOutput.print`). Classified
    * content, where the two texts differ, is marked so the user knows the model
    * cannot see it. `printed` keeps the raw text, which `toolEnd` matches
    * verbatim against the REPL capture; only the display is sanitized. */
  def agentPrint(agentText: String, userText: String): Unit = frame:
    // Text beyond the REPL capture limit cannot be subtracted from its result. The
    // limit is in bytes and this length in chars, so the budget is approximate.
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
    val room = TailBuffer.MaxChars - liveCaptured.length
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
    else prompt.reader.printAbove(styled(Ansi.sanitize(text), Cyan))

  /** The first program output of a tool block opens its `├ output` section. */
  private def openOutputSection(): Unit =
    screen.stopSpinner()
    if toolOpen && !outputStarted then
      ensureNewline()
      write(section("output", Dim))
      outputStarted = true

  private def section(label: String, code: Int): String =
    Indent + styled(s"${g.tee} $label", code) + "\n"

  /** Close the tool block: what the REPL produced besides the agent's own
    * prints (those were shown live), meaning diagnostics, echoed values and
    * exceptions, then the verdict. Long bodies are cut in the middle (unless
    * expanded) so both the first diagnostics and the tail stay visible. */
  def toolEnd(r: ExecutionResult, millis: Long): Unit = frame:
    setOperation(if r.success then "tool completed" else "tool failed")
    val live = liveCaptured.toString + (if liveTruncated then "\n[retained live output limit reached]" else "")
    val record = toolHistory.add(currentCode, r, millis, live, fileChanges.toList)
    screen.stopSpinner()
    liveOutput.end()
    ensureNewline()
    val body =
      List(Option(ExecutionResult.trimStackFrames(r.output)).filter(_.nonEmpty), r.error).flatten.mkString("\n")
    val lines = Tui.withoutPrinted(body, printed.toString).linesIterator.toList
    if lines.nonEmpty then
      // REPL output holds the agent's raw prints and compiler diagnostics: sanitize
      // before display. The subtraction above runs on the raw text, before this.
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
      write(Indent + styled(Ansi.sanitize(s"${change.path}: ${change.summary}"), Cyan) + "\n")
    )
    val verdict =
      if r.success then styled(s"${g.end} ok ${millis} ms", Green) else styled(s"${g.end} failed ${millis} ms", Red)
    write(Indent + verdict + styled(s" ${g.dot} /output ${record.id}", Dim) + "\n")
    toolOpen = false
    flushTodos()

  /** Ctrl-O: switch between the compact and the expanded view. */
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

  // ── pop-ups: permission requests and questions from the agent ─────

  private def menuIndex(message: String, labels: List[String]): Option[Int] =
    keys.withPaused(withPromptHint(menus.ListHint)(menus.list(message, labels)))

  private def menu(message: String, labels: List[String]): Option[String] =
    menuIndex(message, labels).flatMap(labels.lift)

  private def checkboxIndices(message: String, labels: List[String], checked: Set[Int] = Set.empty)
    : Option[List[Int]] =
    keys.withPaused(withPromptHint(menus.CheckboxHint)(menus.checkbox(message, labels, checked)))

  /** A multi-choice pop-up with the `checked` options ticked at first: the
    * indices ticked when confirmed, `None` when cancelled or without menus. */
  def chooseMany(title: String, options: List[String], checked: Set[Int]): Option[Set[Int]] =
    if plain || options.isEmpty then None
    else popupBlock(checkboxIndices(Ansi.sanitize(title), options.map(Ansi.sanitize), checked)).map(_.toSet)

  /** Whether pop-up menus can be drawn (a real terminal). */
  def menusAvailable: Boolean = !plain

  /** Pop-ups from different threads (`parallel` tasks asking or requesting permission)
    * take turns; a waiter that is interrupted meanwhile never shows its pop-up. */
  private val popupLock = java.util.concurrent.locks.ReentrantLock()

  /** A pop-up is a block of its own: a pending TODO panel is drawn first, then
    * `body` (which reads the terminal), then the blank line that ends the block. */
  private def popupBlock[T](body: => T): T =
    popupLock.lockInterruptibly()
    try
      keys.withPaused:
        frame:
          popupDepth += 1
          refreshTitle()
          liveOutput.end()
          flushTodos()
          beginBlock()
        try body
        finally frame:
            alerts.touch()
            blankLine()
            popupDepth -= 1
            refreshTitle()
            if popupDepth == 0 then
              while pendingProcessEvents.nonEmpty do displayProcessEvent(pendingProcessEvents.dequeue())
    finally popupLock.unlock()

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
      alerts.alert(s"Permission needed: ${req.title} (${req.details.mkString(", ")})")
      // The request embeds model-chosen paths and command lines: sanitize.
      write(Indent + styled(s"${g.warn} Permission request: ${Ansi.sanitize(req.title)}", Yellow, Bold) + "\n")
      req.details.foreach { detail =>
        TextLayout.wrap(Ansi.sanitize(detail), width - 5)
          .foreach(line => write(Indent + Indent + styled(line, Yellow) + "\n"))
      }
      val decision =
        if plain then
          Menus.permissionReply(freeText(styled("Allow? [y]es once / [s]ession / [n]o / type instructions: ", Yellow)))
        else
          var selected: Option[Decision] = None
          while selected.isEmpty do
            selected =
              menu("Allow?", List(Menus.AllowOnce, Menus.AllowSession, Menus.DenyLabel, Menus.ReviseLabel)) match
                case Some(Menus.AllowOnce) => Some(Decision.AllowOnce)
                case Some(Menus.AllowSession) => Some(Decision.AllowSession)
                case Some(Menus.ReviseLabel) =>
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
      else menu("Choose", List(Menus.YesLabel, Menus.NoLabel)).contains(Menus.YesLabel)
    if plain then
      write(Indent + styled(s"${g.arrow} ${if yes then "yes" else "no"}", if yes then Green else Red) + "\n")
    yes

  /** Ask for a secret such as an API key: the typed text is shown as `*` and
    * never enters the prompt history. `None` when empty or cancelled. */
  def askSecret(question: String): Option[String] = popupBlock:
    write(Indent + styled("? " + Ansi.sanitize(question), Cyan, Bold) + "\n")
    keys.withPaused:
      withPromptHint(s"Enter send ${g.dot} Ctrl-C cancel"):
        screen.synchronized(screen.flush())
        try prompt.readSecret(styled("key> ", Cyan))
        finally screen.tail = "\n"

  /** Ask the user a question on behalf of the agent. Options render as a
    * menu (or checkboxes when `multiple`), always with a custom-answer
    * entry; no options → a free-text line. `None` on Ctrl-C/Ctrl-D. */
  def askUser(question: String, options: List[String], multiple: Boolean): Option[String] =
    withOperation("waiting for your answer"):
      popupBlock:
        alerts.alert(s"Question: $question")
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
            checkboxIndices("Choose answers", cleanOptions :+ Menus.AddAnswerLabel) match
              case None => None
              case Some(ids) =>
                val chosen = ids.sorted.filter(_ < options.size).flatMap(options.lift)
                if ids.contains(cleanOptions.size) then freeText(answerPrompt).map(t => (chosen :+ t).mkString("; "))
                else if chosen.isEmpty then None
                else Some(chosen.mkString("; "))
          else
            menuIndex("Choose an answer", cleanOptions :+ Menus.OtherLabel) match
              case Some(i) if i == cleanOptions.size => freeText(answerPrompt)
              case Some(i) => options.lift(i)
              case None => None
        // A single-choice menu echoes the selection itself; confirm the other outcomes.
        answer match
          case Some(a) if options.isEmpty || plain || multiple || !options.contains(a) =>
            write(Indent + styled(s"${g.arrow} ${Ansi.sanitize(a)}", Green) + "\n")
          case Some(_) => ()
          case None => write(Indent + styled(s"${g.arrow} No answer", Dim) + "\n")
        answer

  private def freeText(promptText: String): Option[String] = keys.withPaused:
    withPromptHint(s"Enter send ${g.dot} Ctrl-C cancel"):
      screen.synchronized(screen.flush())
      try prompt.readAnswer(promptText)
      finally screen.tail = "\n"

  // ── TODO panel ────────────────────────────────────────────────────

  /** Record a change; the panel is drawn once when the current tool call
    * ends (or before the next pop-up), not on every `markTodo`. */
  def showTodos(todos: List[Todo]): Unit = pendingTodos = Some(todos)

  private def flushTodos(): Unit =
    pendingTodos.foreach(showTodosNow)
    pendingTodos = None

  def showTodosNow(todos: List[Todo]): Unit = frame:
    screen.stopSpinner()
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
  def readLine(promptText: String): Option[String] = readBuffer(promptText).map(_.stripTrailing)

  /** Read a block of code: Enter adds a line until one is left empty, which
    * submits the block (without that line); `None` on EOF. */
  def readBlock(promptText: String): Option[String] =
    prompt.blockMode = true
    try readBuffer(promptText).map(_.stripTrailing)
    finally prompt.blockMode = false

  private def readBuffer(promptText: String): Option[String] =
    var result: Option[String] = None
    var again = true
    while again do
      try
        val typed = keys.takeTypeAhead()
        draftInput = ""
        result = Some(prompt.read(styled(promptText, Cyan, Bold), typed))
        again = false
      catch
        case _: UserInterruptException =>
          Thread.interrupted()
          again = !prompt.blockMode
        case e: EndOfFileException =>
          Debug.log(s"EOF on input: ${e.getMessage}"); Debug.trace(e)
          result = None; again = false
        case e: Throwable =>
          Debug.log(s"readLine failed: $e"); Debug.trace(e)
          throw e
      finally
        screen.tail = "\n" // the reader echoed the line and moved to the next one
        alerts.touch()
    result

  /** Offer `text` as the predicted next message: ghost text at the prompt,
    * redrawn at once if the user is already at it. `None` withdraws it. */
  def suggest(text: Option[String]): Unit =
    // Model-generated text: strip terminal control before it is drawn as ghost text
    // (and, on Tab/→, inserted into the input buffer), like every other model output.
    prompt.suggestion = if plain then None else text.map(t => Ansi.sanitize(t.trim)).filter(_.nonEmpty)
    if !plain then prompt.redisplay()

  /** Whether the terminal can show ghost text (a real terminal). */
  def suggestionsAvailable: Boolean = !plain

  def close(): Unit =
    if closed then return
    closed = true
    screen.stopSpinner()
    keys.stop()
    alerts.close()
    if !plain then screen.synchronized(screen.writeStyle(s"${Ansi.Esc}[23;0t"))
    screen.synchronized(screen.flush())
    statusLine.foreach(_.close())
    prompt.saveHistory()
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

  /** Lines of a tool-result section before it is cut in the middle. */
  val MaxPanelLines = 30
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

  /** The REPL output without the agent's own prints. The host wrote those to
    * the same stream, so they occur verbatim and contiguously: remove the
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
