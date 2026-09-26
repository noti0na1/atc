package atc.ui

import atc.{Debug, ProcessEnvironment}
import atc.agent.{AgentUI, TurnOutcome}
import atc.lib.{Todo, TodoStatus}
import atc.perms.*
import atc.host.FileChange
import atc.sandbox.ExecutionResult

import org.jline.reader.{EndOfFileException, UserInterruptException}
import org.jline.terminal.{Terminal, TerminalBuilder}
import org.jline.terminal.impl.DumbTerminal

import java.io.{InputStream, OutputStream}
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable
import scala.util.control.NonFatal

import Ansi.{Blue, Bold, Cyan, Dim, Green, Red, Yellow}

/** JLine terminal interface for input, streaming responses, tool output and menus.
  * Ctrl-O toggles expanded output; non-interactive terminals print all output.
  * The views it composes ([[StatusLine]], [[ThinkingView]], [[ToolBlock]], [[Dialogs]])
  * write through one [[Screen]], whose monitor is the TUI's lock. See
  * `doc/development.md` for the layout and keyboard controls. */
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
  import screen.{Indent, blankLine, ensureNewline, frame, g, styled, width, write, writeGuttered}

  private val alerts = Alerts(terminal, plain, text => screen.frame(screen.writeStyle(text)))
  private val prompt = PromptReader(screen, historyPath, alerts)

  /** Notifies the user when a turn ends or a question waits (config `notifications`). */
  def notifier: Notifier = alerts.notifier
  def notifier_=(value: Notifier): Unit = alerts.notifier = value

  /** Tab completion for slash commands (see [[PromptReader.completions]]). */
  def completions: List[String] => List[String] = prompt.completions
  def completions_=(value: List[String] => List[String]): Unit = prompt.completions = value

  /** The commands listed under a prompt holding a partial command (see [[PromptReader.commandList]]). */
  def commandList: List[(String, String)] = prompt.commandList
  def commandList_=(value: List[(String, String)]): Unit = prompt.commandList = value

  // ── state ─────────────────────────────────────────────────────────

  /** Set while an agent turn is running; Ctrl-C sets it. */
  private val interrupted = AtomicBoolean(false)
  @volatile private var busy = false
  @volatile private var closed = false
  /** The open assistant prose block (bullet already printed), rendering Markdown as it streams. */
  private var prose: Option[MarkdownStream] = None
  private var popupDepth = 0
  private val pendingProcessEvents = mutable.Queue[String]()
  /** TODO list changed during the current tool call; drawn once when it ends. */
  @volatile private var pendingTodos: Option[List[Todo]] = None
  /** Ctrl-O: show thinking in full and never fold output. Sticks for the session. */
  @volatile private var expanded = false

  private val thinking = ThinkingView(screen, () => expanded, () => beginBlock())
  private val tool = ToolBlock(screen, () => expanded)

  /** Extra action on Ctrl-C during a turn (e.g. interrupt the REPL evaluation). */
  @volatile var onInterrupt: () => Unit = () => ()
  /** A correction the user sent during a turn. */
  @volatile var onSubmit: String => Unit = _ => ()
  /** How many corrections wait for the next tool boundary; the footer shows the count. */
  @volatile var queuedInputs: () => Int = () => 0

  private val keys = KeyReader(
    terminal,
    plain,
    alerts,
    () => toggleExpanded(),
    text => onSubmit(text),
    text => frame { statusLine.draft = text; statusLine.refresh() },
  )
  private val statusLine = StatusLine(screen, () => busy, () => popupDepth > 0, () => queuedInputs())
  private val dialogs = Dialogs(screen, alerts, keys, statusLine, prompt)

  def fileChanged(change: FileChange): Unit = screen.synchronized(tool.fileChanged(change))

  /** Clear the window and its scrollback (`/new`): what follows starts at the top,
    * above a footer drawn again. A plain terminal has nothing to clear. */
  def clearScreen(): Unit = if !plain then
    frame:
      screen.writeStyle(s"${screen.beforeClear}${Ansi.Esc}[H${Ansi.Esc}[2J${Ansi.Esc}[3J")
      screen.tail = "\n\n"
      statusLine.redraw()

  def clearOutputHistory(): Unit = screen.synchronized(tool.history.clear())

  /** `/output`: list the retained results, or show one from a line on, 200 lines at a time. */
  def showOutput(argument: String): Unit = frame:
    val history = tool.history
    val parts = argument.trim.split("\\s+").toList.filter(_.nonEmpty)
    if parts.isEmpty then
      if history.list.isEmpty then info("No retained tool output.")
      else history.list.foreach(println)
    else if parts.size > 2 || parts.lift(1).exists(value => !value.toIntOption.exists(_ >= 1)) then
      error("Use /output <id|last> [line], with a positive line number.")
    else
      val id = if parts.head == "last" then history.latest.map(_.id) else parts.head.toIntOption
      val from = parts.lift(1).flatMap(_.toIntOption).getOrElse(1)
      id.flatMap(history.get) match
        case Some(entry) =>
          val lines = entry.render.linesIterator.toVector
          if from > lines.size then error(s"Tool ${entry.id} has ${lines.size} lines of retained output.")
          else
            val end = (from.toLong - 1 + 200).min(lines.size.toLong).toInt
            (from - 1 until end).foreach: i =>
              val line = Ansi.sanitize(lines(i))
              renderedLine(if i < entry.changesFrom then line else diffLine(line))
            if lines.size > end then info(s"More output: /output ${entry.id} ${end + 1}")
        case _ => error("Output is unavailable. Use /output to list retained results.")

  /** A line of a file-change preview: the file's header bold, additions green, removals red. */
  private def diffLine(line: String): String =
    if line.startsWith("@@") then styled(line, Dim)
    else if line.startsWith("+") then styled(line, Green)
    else if line.startsWith("-") then styled(line, Red)
    else if line.nonEmpty && !line.startsWith(" ") then styled(line, Bold)
    else line

  def setContext(model: String, mode: String, directory: String): Unit = screen.synchronized:
    val title = s"atc ${g.dot} $directory"
    alerts.title = title
    statusLine.setContext(s"$model ${g.dot} $mode ${g.dot} $directory", title)

  override def inputAccepted(text: String): Unit = frame:
    beginBlock()
    write(styled(s"${g.arrow} applying update: ${Ansi.sanitize(text)}", Cyan) + "\n")
    statusLine.refresh()

  // ── turns ─────────────────────────────────────────────────────────

  /** Whether an exhausted tool budget asks the human to continue (off for `-p` runs). */
  @volatile var askToContinue: Boolean = true

  override def confirmMoreToolCalls(used: Int, budget: Int): Boolean =
    if askToContinue then alerts.alert(s"Tool limit reached after ${Format.plural(used, "call")}. Continue?")
    askToContinue && confirm(
      s"Tool limit reached after ${Format.plural(used, "call")}. Allow ${Format.plural(budget, "more call")}?"
    )

  terminal.handle(Terminal.Signal.INT, _ => interruptTurn())
  terminal.handle(Terminal.Signal.WINCH, _ => resized())

  private def interruptTurn(): Unit =
    alerts.touch()
    if busy then
      interrupted.set(true)
      onInterrupt()

  private def resized(): Unit = frame:
    screen.resized()
    redrawSized()

  /** JLine's line reader takes the terminal's resize signal while it reads, so a size
    * changed meanwhile is caught up with when a prompt or pop-up returns. */
  private def catchUpOnResize(): Unit = if screen.resized() then frame(redrawSized())

  /** Redraw what depends on the terminal's size. */
  private def redrawSized(): Unit =
    statusLine.refresh()
    thinking.resize()
    tool.resize()

  def beginTurn(): Unit =
    frame:
      interrupted.set(false)
      alerts.beginTurn()
      busy = true
      statusLine.beginTurn()
    keys.start()

  /** End the turn: close open blocks, say what the turn cost (`stats`) and
    * leave one blank line before the next prompt. */
  def endTurn(stats: Option[Tui.TurnStats] = None): Unit =
    frame:
      screen.stopSpinner()
      busy = false
      statusLine.operation = stats.fold("ready")(_.outcome.label)
      thinking.end()
      closeProse()
      tool.endTurn()
      flushTodos()
      flushProcessEvents()
      stats.foreach: s =>
        blankLine()
        val calls = Format.plural(s.toolCalls, "tool call")
        val context = Format.contextUsage(s.context, s.window)
        val summary =
          s"${g.bullet} ${s.outcome.label} in ${Format.duration(s.seconds)} ${g.dot} $calls ${g.dot} ${Format.count(s.tokens)} tokens ${g.dot} $context"
        val lines = if plain then List(summary) else TextLayout.wrap(summary, width - Indent.length - 1)
        lines.zipWithIndex.foreach: (line, index) =>
          write((if index == 0 then "" else Indent) + styled(line, Dim) + "\n")
      blankLine()
      statusLine.refresh()
    keys.stop() // outside the lock: the key thread may be waiting for it
    stats.foreach(alerts.turnEnded)

  def isInterrupted: Boolean = interrupted.get()

  /** Start a new block: blank line before it, and no prose/thinking block is open any more. */
  private def beginBlock(): Unit =
    screen.stopSpinner()
    thinking.end()
    closeProse()
    blankLine()

  // ── plain lines (banner, slash commands, notices) ─────────────────

  private def renderedLine(s: String): Unit = frame:
    screen.stopSpinner()
    tool.endOutput() // a running block's live region stays above the line
    ensureNewline()
    val lines = if plain then s.split("\n", -1).toList else TextLayout.wrap(s, width - 1)
    lines.foreach(line => write(line + "\n"))

  /** A plain line supplied by the application. Config values, policy summaries
    * and paths may be repository-controlled, so terminal controls never pass. */
  def println(s: String = ""): Unit = renderedLine(Ansi.sanitize(s))
  def info(s: String): Unit = renderedLine(styled(Ansi.sanitize(s), Dim))
  /** One dim line of context, set apart from what came before. */
  def preview(s: String): Unit =
    frame(blankLine())
    info(screen.fit(Ansi.sanitize(s).replace('\n', ' '), 0))
  def success(s: String): Unit = renderedLine(styled(Ansi.sanitize(s), Green))
  def warn(s: String): Unit = renderedLine(styled(s"${g.warn} ${Ansi.sanitize(s)}", Yellow))
  def error(s: String): Unit =
    if busy then alerts.turnError(s)
    renderedLine(styled(s"${g.cross} ${Ansi.sanitize(s)}", Red))

  def showHelp(rows: List[(String, String)]): Unit = frame:
    println("Commands:")
    val fields = rows.map((command, description) => styled(Ansi.sanitize(command), Cyan) -> Ansi.sanitize(description))
    TextLayout.fields(fields, width).foreach(renderedLine)

  /** The start-up banner: a title, aligned `label → value` rows and a dim hint line. */
  def banner(title: String, rows: List[(String, String)], hint: String): Unit = frame:
    screen.stopSpinner()
    blankLine()
    write(styled(s"${g.bullet} ${Ansi.sanitize(title)}", Cyan, Bold) + "\n")
    // Values can be paths (possibly named by an attacker in a cloned repo): sanitize.
    TextLayout.fields(rows.map((label, value) => styled(Ansi.sanitize(label), Dim) -> Ansi.sanitize(value)), width)
      .foreach(line => write(line + "\n"))
    val controls =
      Ansi.sanitize(hint).split(" · ").toList.map(c => if g == Glyphs.ascii then c.replace("→", "Right") else c)
    // Rows of whole controls: a row break inside one would split a key from what it does.
    val hintRows = controls.foldLeft(List.empty[String]):
      case (row :: done, control) if TextLayout.width(row) + 3 + TextLayout.width(control) <= width - 3 =>
        s"$row ${g.dot} $control" :: done
      case (done, control) => control :: done
    hintRows.reverse.foreach(row =>
      TextLayout.wrap(row, width - 3).foreach(line => write(Indent + styled(line, Dim) + "\n"))
    )

  // ── model output: reasoning and prose ─────────────────────────────

  def thinkingDelta(text: String): Unit = frame:
    statusLine.setOperation("reasoning")
    thinking.delta(Ansi.sanitize(text))

  def assistantDelta(text: String): Unit = frame:
    statusLine.setOperation("responding")
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

  def assistantEnd(): Unit = frame:
    screen.stopSpinner()
    thinking.end()
    closeProse()
    ensureNewline()

  /** Show progress ("model is thinking"); ends the current prose block. */
  def status(text: String): Unit = frame:
    statusLine.operation = text
    statusLine.refresh()
    screen.stopSpinner()
    thinking.end()
    closeProse()
    ensureNewline()
    if plain then write(styled(s"~ ${Ansi.sanitize(text)}...", Dim) + "\n")
    else if !statusLine.shown then screen.spin("", Ansi.sanitize(text))

  // ── tool blocks ───────────────────────────────────────────────────

  def toolStart(code: String): Unit = toolStart(code, "run_scala")

  /** Open a code block titled `title`: the agent's `run_scala`, or the user's own `/run`. */
  def toolStart(code: String, title: String): Unit = frame:
    statusLine.setOperation("running Scala")
    beginBlock()
    tool.start(code, title)
    flushProcessEvents()
    if !plain && !statusLine.shown then screen.spin(Indent, "running")

  /** Live output of the agent's `println` (see `HostOutput.print`); `userText` differs
    * from `agentText` for classified content. */
  def agentPrint(agentText: String, userText: String): Unit = frame(tool.print(agentText, userText))

  /** A command the agent runs (`exec`) is taking a while: name it, then show
    * what it writes as it comes (`commandOutput`), in the same output section
    * as the prints. */
  def commandRunning(commandLine: String): Unit = frame:
    statusLine.setOperation(s"running $commandLine")
    tool.emit(styled(s"$$ ${Ansi.sanitize(commandLine)}", Cyan) + "\n")

  def commandOutput(text: String): Unit = frame(tool.commandOutput(text))

  /** Process notifications wait while a menu is open, and during a turn until a tool
    * block opens or the turn ends: written into streaming prose they would break its
    * lines. A tool block shows them in its output, and the prompt keeps its input. */
  def processEvent(text: String): Unit = frame:
    if !closed then
      if pendingProcessEvents.size >= 100 then pendingProcessEvents.dequeue()
      pendingProcessEvents.enqueue(text.take(2000))
      flushProcessEvents()

  private def flushProcessEvents(): Unit =
    if popupDepth == 0 && (!busy || tool.isOpen) then
      while pendingProcessEvents.nonEmpty do
        val line = styled(Ansi.sanitize(pendingProcessEvents.dequeue()), Cyan)
        if tool.isOpen then tool.emit(line + "\n")
        else if prompt.reader.isReading then prompt.reader.printAbove(line)
        else renderedLine(line)

  def toolEnd(r: ExecutionResult, millis: Long): Unit = frame:
    statusLine.setOperation(if r.success then "tool completed" else "tool failed")
    tool.end(r, millis)
    flushTodos()

  /** Ctrl-O: switch between the compact and the expanded view. */
  private def toggleExpanded(): Unit = frame:
    expanded = !expanded
    // Take down what is live, say what happened, then re-render it in the new view.
    val wasThinking = thinking.active
    thinking.detach()
    tool.detach()
    info(if expanded then "expanded view (Ctrl-O to collapse)" else "compact view (Ctrl-O to expand)")
    if wasThinking then thinking.render() // the whole reasoning (expanded) or a window over it (compact)
    tool.reattach()

  // ── pop-ups: permission requests and questions from the agent ─────

  /** Whether pop-up menus can be drawn (a real terminal). */
  def menusAvailable: Boolean = !plain

  /** Pop-ups from different threads (`parallel` tasks asking or requesting permission)
    * take turns; a waiter that is interrupted meanwhile never shows its pop-up. */
  private val popupLock = ReentrantLock()

  /** A pop-up is a block of its own: a pending TODO panel is drawn first, then
    * `body` (which reads the terminal), then the blank line that ends the block. */
  private def popupBlock[T](body: => T): T =
    popupLock.lockInterruptibly()
    try
      keys.withPaused:
        frame:
          popupDepth += 1
          statusLine.refreshTitle()
          tool.endOutput()
          tool.hold()
          flushTodos()
          beginBlock()
        try body
        finally frame:
            catchUpOnResize()
            alerts.touch()
            blankLine()
            popupDepth -= 1
            statusLine.refreshTitle()
            if popupDepth == 0 then
              tool.release()
              flushProcessEvents()
    finally popupLock.unlock()

  // Slash-command menus follow one pattern. A one-shot picker (`/model`, `/effort`)
  // acts on the choice and closes; a menu the user comes back to (`/providers`,
  // `/config`) is a `menuLoop` ending in Done; a menu opened from another is
  // `chooseOrBack`, ending in Back. Esc goes back one level in each of them.

  /** A single-choice pop-up for a slash command (`/model`, `/classifiedmodel`).
    * `None` when there is no terminal for menus, no options, or the user
    * left it with Esc, Ctrl-C or Ctrl-D. */
  def choose(title: String, options: List[String]): Option[String] = choose(title, options, 0)

  /** As [[choose]], the menu opening on the option at `initial` (the value in use). */
  def choose(title: String, options: List[String], initial: Int): Option[String] =
    chooseIndex(title, options, initial).flatMap(options.lift)

  /** A sub-menu: `options` and a last Back row. The chosen index; `None` for Back,
    * Esc or no menus, which return to the menu that opened it. */
  def chooseOrBack(title: String, options: List[String]): Option[Int] =
    chooseIndex(title, options :+ Menus.BackLabel).filter(_ < options.size)

  /** A menu the user comes back to after each choice: `entries`, labels with what
    * choosing them does, are built again every time, and the last row, Done, or
    * Esc leaves it. It comes back with the cursor on the row last chosen. Without
    * menus it does nothing: see [[menusAvailable]]. */
  def menuLoop(title: String)(entries: () => List[(String, () => Unit)]): Unit =
    var open = true
    var last = 0
    while open do
      val current = entries()
      chooseIndex(title, current.map(_._1) :+ Menus.DoneLabel, last).flatMap(i => current.lift(i).map(i -> _)) match
        case Some((i, (_, act))) =>
          last = i
          act()
        case None => open = false

  private def chooseIndex(title: String, options: List[String], initial: Int = 0): Option[Int] =
    if plain || options.isEmpty then None
    else popupBlock(dialogs.menuIndex(Ansi.sanitize(title), options.map(Ansi.sanitize), escape = "back", initial))

  /** A multi-choice pop-up with the `checked` options ticked at first: the
    * indices ticked when confirmed, `None` when left with Esc or without menus. */
  def chooseMany(title: String, options: List[String], checked: Set[Int]): Option[Set[Int]] =
    if plain || options.isEmpty then None
    else
      popupBlock(dialogs.checkboxIndices(Ansi.sanitize(title), options.map(Ansi.sanitize), checked, escape = "back"))
        .map(_.toSet)

  def askPermission(req: PermissionRequest): Decision =
    statusLine.withOperation("waiting for permission")(popupBlock(dialogs.permission(req)))

  /** A yes/no question from the app itself (see [[Dialogs.confirm]]). */
  def confirm(question: String): Boolean = popupBlock(dialogs.confirm(question))

  /** Ask for a secret such as an API key (see [[Dialogs.secret]]). */
  def askSecret(question: String): Option[String] = popupBlock(dialogs.secret(question))

  /** Ask the user a question on behalf of the agent (see [[Dialogs.answer]]). */
  def askUser(question: String, options: List[String], multiple: Boolean): Option[String] =
    statusLine.withOperation("waiting for your answer")(popupBlock(dialogs.answer(question, options, multiple)))

  // ── TODO panel ────────────────────────────────────────────────────

  /** Record a change; the panel is drawn once when the current tool call
    * ends (or before the next pop-up), not on every `markTodo`. */
  def showTodos(todos: List[Todo]): Unit = pendingTodos = Some(todos)

  /** The list the panel last showed, to tell a status change from a new list. */
  private var shownTodos: List[Todo] = Nil

  /** A new list is shown whole; when only statuses changed, the changed items are shown
    * with the progress, since the agent marks one item per call and the whole list again
    * each time buries the work between. */
  private def flushTodos(): Unit =
    pendingTodos.foreach: todos =>
      if todos.isEmpty || todos.map(_.text) != shownTodos.map(_.text) then showTodosNow(todos)
      else
        val changed = todos.zip(shownTodos).collect { case (now, before) if now.status != before.status => now }
        if changed.nonEmpty then drawTodos(todos, changed)
    pendingTodos = None

  def showTodosNow(todos: List[Todo]): Unit = drawTodos(todos, todos)

  /** The panel: its header, then `rows`, the whole list or the items that changed. */
  private def drawTodos(todos: List[Todo], rows: List[Todo]): Unit = frame:
    shownTodos = todos
    screen.stopSpinner()
    ensureNewline()
    val note =
      if todos.isEmpty then " (empty)"
      else if rows.size < todos.size then s" ${g.dot} ${todos.count(_.status == TodoStatus.Done)} of ${todos.size} done"
      else ""
    write(Indent + styled(s"${g.todo} TODO", Blue, Bold) + styled(note, Dim) + "\n")
    rows.foreach: t =>
      val text = Ansi.sanitize(t.text) // model-written
      val line = t.status match
        case TodoStatus.Done => styled(s"${g.done} $text", Dim)
        case TodoStatus.InProgress => styled(s"${g.inProgress} $text", Yellow)
        case TodoStatus.Pending => s"${g.pending} $text"
      TextLayout.wrap(line, width - 5).foreach(row => write(Indent + Indent + row + "\n"))

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

  /** Text the next prompt starts with, before any keys typed ahead. */
  @volatile private var nextDraft = ""

  /** Start the next prompt with `text`, for the user to edit or send. */
  def draft(text: String): Unit = nextDraft = text

  private def readBuffer(promptText: String): Option[String] =
    var result: Option[String] = None
    var again = true
    while again do
      try
        val typed = nextDraft + keys.takeTypeAhead()
        nextDraft = ""
        statusLine.draft = ""
        result = Some(prompt.read(styled(promptText, Cyan, Bold), typed))
        again = false
      catch
        case _: UserInterruptException =>
          Thread.interrupted()
          again = !prompt.blockMode
        case e: EndOfFileException =>
          Debug.log(s"EOF on input: ${e.getMessage}")
          Debug.trace(e)
          result = None
          again = false
        case NonFatal(e) =>
          Debug.log(s"readLine failed: $e")
          Debug.trace(e)
          throw e
      finally
        screen.tail = "\n" // the reader echoed the line and moved to the next one
        alerts.touch()
        catchUpOnResize()
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

  def close(): Unit = if !closed then
    closed = true
    screen.stopSpinner()
    keys.stop()
    alerts.close()
    statusLine.close()
    prompt.saveHistory()
    terminal.close()

object Tui:
  /** A scripted `-p` run never needs console discovery or raw mode. Giving it
    * a known dumb UTF-8 terminal also avoids platform-specific null encodings
    * when Windows redirects stdin/stdout (as CI and normal pipelines do). */
  private[atc] def openTerminal(
    nonInteractive: Boolean,
    input: InputStream = System.in.nn,
    output: OutputStream = System.out.nn,
  ): Terminal =
    if nonInteractive then new DumbTerminal("atc", Terminal.TYPE_DUMB, input, output, StandardCharsets.UTF_8)
    else TerminalBuilder.builder().system(true).graphemeCluster(false).build().nn

  /** Choose safe layout characters. Dumb/non-interactive terminals may report
    * no encoding at all, in which case ASCII is the only sound default. */
  private[atc] def glyphs(encoding: Charset | Null, forceAscii: Boolean): Glyphs =
    if !forceAscii && Option(encoding).exists(_.name.nn.toUpperCase(Locale.ROOT).contains("UTF")) then Glyphs.unicode
    else Glyphs.ascii

  /** What a turn cost, for the summary line `endTurn` prints. */
  final case class TurnStats(
    seconds: Double,
    toolCalls: Int,
    tokens: Long,
    context: Long,
    window: Option[Int],
    outcome: TurnOutcome = TurnOutcome.Finished,
  )
