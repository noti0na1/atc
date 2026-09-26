package atc

import atc.agent.{Agent, AgentEnvironment, InputPredictor, TurnOutcome}
import atc.commands.{Commands, SlashCommand}
import atc.config.{Config, Configuration}
import atc.host.{FileChange, Host, HostLlm, HostOutput, HostUi}
import atc.lib.Todo
import atc.llm.ChatModel
import atc.perms.*
import atc.platform.PlatformPath
import atc.ui.{Notifier, Tui}

import java.nio.file.Path
import java.util.Locale

/** The running application: wires configuration, models, permission policy,
  * host, sandbox, agent loop and terminal UI together, then runs either one
  * non-interactive turn (`-p`) or the interactive loop. [[Commands]] runs the
  * slash commands. */
final class App(args: Cli.Args, val tui: Tui):
  val cwd: Path = args.cwd

  /** Every configuration layer in force (global ← project ← `-c`), after the
    * first-run offers of [[Setup.load]] (which may end the program instead). */
  val configuration: Configuration = Setup.load(args, tui)
  /** The effective settings. The *policy* lists live on `configuration`. */
  val config: Config = configuration.settings

  val models: Models = Models(args, configuration)
  private val initialModel: ChatModel = models.initial(tui.warn)
  private val initialClassified: Option[ChatModel] = models.configured(
    "classifiedModel",
    config.classifiedModel,
    message =>
      tui.warn(s"$message. Classified data is not sent to any model until /classifiedmodel chooses one.")
  )

  val sandbox: SandboxRepl = SandboxRepl(config, policy, host, tui)

  // ── permission policy ─────────────────────────────────────────────

  val policy: Policy =
    Policy(
      configuration.fileRules(cwd),
      config.commands,
      config.hosts,
      App.permissionPrompter(args, request => withClockPaused(tui.askPermission(request))),
      config.denyCommands,
      config.denyHosts
    )
  policy.mode = args.mode.orElse(config.mode.map(Mode.parse)).getOrElse(Mode.Full)
  models.useMode(policy.mode)

  // ── host (the sandbox API implementation) and its ports ───────────

  private def withClockPaused[T](body: => T): T = sandbox.withClockPaused(body)

  private val output: HostOutput = new HostOutput:
    override def fileChanged(change: FileChange): Unit = tui.fileChanged(change)
    def print(agentText: String, userText: String): Unit =
      sandbox.session.foreach(_.printStream.print(agentText)) // into the tool result, in order with REPL output
      tui.agentPrint(agentText, userText)
    override def commandRunning(commandLine: String): Unit = tui.commandRunning(commandLine)
    override def commandOutput(text: String): Unit = tui.commandOutput(text)
    override def whileCommandRuns[T](body: => T): T = withClockPaused(body)
    override def processStarted(id: Int, commandLine: String): Unit =
      tui.processEvent(s"$$ $commandLine  [p$id started]")
    override def processInput(id: Int, text: String): Unit = tui.processEvent(s"p$id > ${text.stripSuffix("\n")}")
    override def processExited(id: Int, exitCode: Int): Unit = tui.processEvent(s"[p$id exited $exitCode]")
  private val llm: HostLlm = new HostLlm:
    def chat(message: String): String =
      val reply = withClockPaused(agent.model.simple(None, message))
      agent.recordUsage(Agent.Chat, reply.usage)
      reply.text
    def classifiedChat(message: String): String =
      val model = agent.classifiedModel.getOrElse(throw RuntimeException(
        "No classified model configured: set \"classifiedModel\" to an isolated model trusted with classified data."
      ))
      val reply =
        withClockPaused(model.simple(
          Some("You are a trusted assistant handling confidential data. Answer directly and concisely."),
          message
        ))
      agent.recordUsage(Agent.ClassifiedChat, reply.usage)
      reply.text
  private val hostUi: HostUi = new HostUi:
    // A `-p` run has nobody to ask, and its stdin may be a pipe whose data is not an answer.
    def askUser(question: String, options: List[String], multiple: Boolean): Option[String] =
      if args.prompt.isDefined then None else withClockPaused(tui.askUser(question, options, multiple))
    def showTodos(items: List[Todo]): Unit = tui.showTodos(items)
  /** Listings hide what git ignores unless the config turns that off. */
  private val gitIgnore: GitIgnore = if config.respectGitignore then GitIgnore(cwd) else GitIgnore.Disabled
  val host: Host = Host(policy, cwd, output, llm, hostUi, gitIgnore, () => models.configuration.keyVariables)

  // ── agent ─────────────────────────────────────────────────────────

  val agent: Agent = Agent(
    config,
    AgentEnvironment.current(cwd, userPresent = args.prompt.isEmpty),
    policy,
    tui,
    initialModel,
    initialClassified,
    config.instructions,
    () => (host.currentTaskNotes, host.currentTodos),
  )
  tui.onSubmit = agent.submit
  if args.prompt.isEmpty then tui.notifier = Notifier.fromSetting(config.notifications)
  tui.queuedInputs = () => agent.queuedInputCount
  tui.onInterrupt = () =>
    agent.interrupt()
    sandbox.interrupt()

  /** Guesses the next request after each turn (config `predictInput`), shown as
    * ghost text at the prompt. Interactive runs on a real terminal only. */
  val predictor: InputPredictor = InputPredictor(
    () => agent.model,
    () => agent.history,
    tui.suggest,
    agent.recordUsage(Agent.Prediction, _),
    enabled = config.predictInput && canPredict,
  )
  private def canPredict: Boolean = tui.suggestionsAvailable && args.prompt.isEmpty

  /** Apply the settings `/config` may change to the running session. The
    * models take up `webSearch` when [[Models.reload]] loads it. */
  def useSettings(settings: Config): Unit =
    agent.config = agent.config
      .copy(autoCompactThreshold = settings.autoCompactThreshold, compactKeepRatio = settings.compactKeepRatio)
    if args.prompt.isEmpty then tui.notifier = Notifier.fromSetting(settings.notifications)
    predictor.enabled = settings.predictInput && canPredict
    if !predictor.enabled then predictor.invalidate()

  /** Show the model, its effort, the mode and the directory in the status line. */
  def updateStatus(): Unit =
    val directory = Option(cwd.getFileName).fold(PlatformPath.display(cwd))(_.toString)
    val effort = agent.model.effort.fold("")(e => s" ($e)")
    tui.setContext(models.catalog.label(models.catalog.find(agent.model.ref)) + effort, policy.mode.label, directory)
  updateStatus()

  // ── commands ──────────────────────────────────────────────────────

  private val commands = Commands(this)
  tui.completions = commands.complete
  tui.commandList = SlashCommand.table

  // ── running ───────────────────────────────────────────────────────

  def run(): Int =
    try
      // A directory no config covers is unreachable; say so rather than leave the
      // agent to find out through repeated denials.
      if !policy.effective(ScopeId.Base, PlatformPath.canonical(cwd)).canRead then
        tui.info(
          s"No configuration grants access to $cwd, so the agent has to ask for every file. " +
            "Run `atc --init` to give this project a config, or add a rule to ~/.atc/config.json."
        )
      args.prompt match
        case Some(p) =>
          tui.askToContinue = false // nobody to ask: the tool budget is a hard stop here
          sandbox.warm()
          // Report a failed turn through the process exit code so scripts can detect it.
          runTurn(p).exitCode
        case None =>
          banner()
          models.catalog.refresh()
          if tui.menusAvailable then commands.sessionCommands.offerResume()
          sandbox.warm() // after the resume offer: restoring would only discard it
          interactive()
          if tui.menusAvailable then commands.sessionCommands.saveOnExit()
          0
    finally
      predictor.invalidate()
      host.killProcesses()
      sandbox.close()
      models.close()

  private def banner(): Unit =
    tui.banner(
      s"atc ${Main.Version}",
      List(
        "model" -> models.describe(agent.model),
        "mode" -> policy.mode.describe,
        "directory" -> PlatformPath.display(cwd),
      ) ++ agent.classifiedModel.map(model => "classified model" -> models.describe(model))
        ++ Option.when(args.approveAll)("permissions" -> "every request approved without asking (--approve-all)"),
      (List("/help commands", "Shift-Tab mode", "Ctrl-C interrupt", "Ctrl-O details", "Ctrl-D quit")
        ++ Option.when(predictor.enabled)("Tab or → accept the suggested next request")).mkString(" · "),
    )

  /** Run one turn and retain its outcome for the terminal summary and scripted exit code. */
  private def runTurn(input: String): TurnOutcome =
    predictor.invalidate()
    tui.beginTurn()
    val started = System.nanoTime()
    val (usageBefore, callsBefore) = (agent.usage, agent.toolCalls)
    var outcome = TurnOutcome.Failed
    try
      outcome = agent.turn(sandbox.ensure(), input, () => tui.isInterrupted)
      outcome
    catch
      case e: Exception =>
        tui.error(Debug.describe(e))
        Debug.trace(e)
        TurnOutcome.Failed
    finally
      val tokens = (agent.usage.input + agent.usage.output) - (usageBefore.input + usageBefore.output)
      val context = agent.contextUsage
      tui.endTurn(Some(Tui.TurnStats(
        (System.nanoTime() - started) / 1e9,
        agent.toolCalls - callsBefore,
        tokens,
        context.tokens,
        context.window,
        outcome,
      )))
      predictor.start()

  private def interactive(): Unit =
    var running = true
    while running do
      val next = if agent.queuedInputCount > 0 then Some("") else tui.readLine(prompt)
      next match
        case Some("") if agent.queuedInputCount > 0 => afterTurn(runTurn(""))
        case None =>
          Debug.log("input closed, exiting")
          running = false
        case Some(line) if line.trim.isEmpty => ()
        // Typed out of habit; not listed in /help.
        case Some(line) if App.QuitWords.contains(line.trim.toLowerCase(Locale.ROOT)) => running = false
        case Some(line) if line.trim.startsWith("/") => running = commands.run(line.trim)
        case Some(line) => afterTurn(runTurn(line))

  /** After Ctrl-C, a correction typed during the turn goes back to the prompt: starting
    * another turn with it would contact the model right after the user stopped it. */
  private def afterTurn(outcome: TurnOutcome): Unit =
    if outcome == TurnOutcome.Interrupted then
      val queued = agent.takeQueuedInput()
      if queued.nonEmpty then tui.draft(queued.mkString("\n"))

  /** The input prompt names the mode unless it is the full one. */
  def prompt: String = policy.mode match
    case Mode.Full => "> "
    case m => s"${m.label} > "

object App:
  /** Thrown to end the program from setup, before there is anything to run. */
  final case class Exit(code: Int) extends RuntimeException(s"exit $code")

  /** Bare lines that quit like `/quit`: what shells and editors use. */
  private val QuitWords: Set[String] = Set(":q", "exit", "quit")

  /** A scripted turn has nobody to answer permission pop-ups. Fail closed
    * without reading stdin unless the caller explicitly chose `--approve-all`. */
  private[atc] def permissionPrompter(
    args: Cli.Args,
    interactive: PermissionRequest => Decision
  ): PermissionPrompter =
    if args.approveAll then _ => Decision.AllowSession
    else if args.prompt.isDefined then
      _ =>
        throw SecurityException(
          "non-interactive run cannot ask for permission; configure a standing grant or use --approve-all in a trusted setup"
        )
    else request => interactive(request)
