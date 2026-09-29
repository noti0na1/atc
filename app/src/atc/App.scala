package atc

import atc.agent.{Agent, AgentEnvironment, InputPredictor, SessionSnapshot, ToolCallHooks, TurnOutcome}
import atc.checkpoint.{Checkpoints, Isolation}
import atc.commands.{Commands, SlashCommand}
import atc.confine.CommandSandbox
import atc.config.{Config, Configuration, ProjectRules, ProjectTrust}
import atc.host.{FileChange, Host, HostLlm, HostOutput, HostUi}
import atc.lib.Todo
import atc.llm.ChatModel
import atc.perms.*
import atc.platform.PlatformPath
import atc.ui.{Notifier, Tui}

import java.nio.file.{Files, Path}
import java.util.Locale
import scala.util.control.NonFatal

/** The running application: wires configuration, models, permission policy,
  * host, sandbox, agent loop and terminal UI together, then runs either one
  * non-interactive turn (`-p`) or the interactive loop. [[Commands]] runs the
  * slash commands. */
final class App(args: Cli.Args, val tui: Tui, resume: Option[SessionSnapshot] = None):
  val cwd: Path = args.cwd
  /** In isolate mode, the project this session works on a copy of. */
  val isolatedFrom: Option[Path] = args.isolatedFrom
  /** The mode the command line named, which a resumed session's mode does not override. */
  val cliMode: Option[Mode] = args.mode
  /** Where the session is saved and resumed: the project, in isolate mode too. */
  val sessionRoot: Path = isolatedFrom.getOrElse(cwd)

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

  val sandbox: SandboxRepl = SandboxRepl(config, policy, host, tui, osSandbox)

  // ── permission policy ─────────────────────────────────────────────

  val policy: Policy =
    Policy(
      // The project an isolated session copied is out of reach, whatever the rules grant there.
      isolatedRoots.fold(configuration.fileRules(cwd))((project, copy) =>
        FileRule.forCopy(configuration.fileRules(cwd), project, copy)
      ) ++
        isolatedFrom.map(from =>
          FileRule(PathPattern(".", App.projectOf(from)), Some(Access.None), None, locked = true)
        ),
      config.commands,
      config.hosts,
      App.permissionPrompter(args, request => askPermission(request)),
      config.denyCommands,
      config.denyHosts
    )
  policy.mode = args.sessionMode.orElse(args.mode).orElse(config.mode.map(Mode.parse)).getOrElse(Mode.Full)
  policy.auto = args.auto || config.auto
  policy.copyRoot = isolatedRoots.map(_._2)
  models.useMode(policy.mode)

  /** The permission pop-up, offering to save the grant to the project config where it can
    * be written there, and saving it when the user chooses that. */
  private def askPermission(request: PermissionRequest): Decision =
    // In isolate mode a grant is saved to the project's own config, which the copy takes on
    // entry, for the project's path that the copy's path stands for.
    val here = PlatformPath.canonical(sessionRoot)
    val saved = (request, isolatedRoots) match
      case (file: FileRequest, Some((project, copy))) if file.path.startsWith(copy) =>
        file.copy(path = project.resolve(copy.relativize(file.path)).nn)
      case _ => request
    val target = ProjectRules.plan(sessionRoot, saved).map: plan =>
      if plan.config.startsWith(here) then PlatformPath.portable(here.relativize(plan.config).nn)
      else PlatformPath.display(plan.config)
    val decision = withClockPaused(tui.askPermission(request, target))
    if decision == Decision.AllowAlways then
      try tui.info(ProjectRules.save(sessionRoot, saved))
      catch
        case NonFatal(e) =>
          tui.error(s"The grant holds for this session but could not be saved: ${Debug.message(e)}")
    decision

  // ── host (the sandbox API implementation) and its ports ───────────

  private def withClockPaused[T](body: => T): T = sandbox.withClockPaused(body)

  private val output: HostOutput = new HostOutput:
    override def fileChanged(change: FileChange): Unit = tui.fileChanged(change)
    def print(agentText: String, userText: String): Unit =
      sandbox.session.foreach(_.printAgent(agentText)) // into the tool result, in order with REPL output
      tui.agentPrint(agentText, userText)
    override def show(agentText: String, userText: String): Unit = tui.agentPrint(agentText, userText)
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
  /** How commands run: confined by the platform's sandbox, refused, or unconfined (config `osSandbox`). */
  val osSandbox: CommandSandbox =
    CommandSandbox.detect(config.osSandbox, isolatedRoots.map((project, copy) => (copy, project)))
  val host: Host =
    Host(policy, cwd, output, llm, hostUi, gitIgnore, () => models.configuration.keyVariables, osSandbox, isolatedRoots)

  /** The copy and its stores, in an isolated session. */
  lazy val isolation: Option[Isolation] = isolatedFrom.map(from => isolationOf(App.projectOf(from)))

  /** In isolate mode, the project and this session's copy of it: the copy's root lies as many
    * levels above the working directory as the session started below the project. */
  lazy val isolatedRoots: Option[(Path, Path)] = isolatedFrom.map: from =>
    val project = App.projectOf(from)
    val depth = project.relativize(PlatformPath.canonical(from)).nn.toString match
      case "" => 0
      case relative => java.nio.file.Paths.get(relative).nn.getNameCount
    (
      project,
      Iterator.iterate(PlatformPath.canonical(cwd))(dir => Option(dir.getParent).getOrElse(dir)).drop(depth).next()
    )

  private def isolationOf(project: Path): Isolation =
    // This session's view of the project root: the project, or the copy's in isolate mode.
    val root = isolatedRoots.fold(project)(_._2)
    // Classified and no-access paths are not recorded, as for checkpoints.
    def excluded(path: String): Boolean =
      val permission = policy.effective(ScopeId.Base, root.resolve(PlatformPath.native(path)).nn)
      permission.classified || (permission.locked && !permission.canRead)
    Isolation(project, Config.globalDir, Isolation.dataDir(PlatformPath.canonical(PlatformPath.userHome)), excluded)

  /** Make this project's copy current and return the arguments that run the session there. */
  def isolatedArgs(): Cli.Args =
    if !osSandbox.confined then
      throw IllegalStateException(
        s"isolate mode needs the OS sandbox to keep commands off the project, and commands here are ${osSandbox.describe}"
      )
    val here = PlatformPath.canonical(cwd)
    val project = App.projectOf(here)
    val home = PlatformPath.canonical(PlatformPath.userHome)
    if home.startsWith(project) then
      throw IllegalStateException(
        s"isolate mode copies a project, and ${PlatformPath.display(project)} holds your home directory; " +
          "start ATC in a project directory, or give this one a config with `atc --init`"
      )
    val isolation = isolationOf(project)
    tui.info(s"Preparing the copy of ${PlatformPath.display(project)}...")
    val kept = isolation.enter()
    if kept > 0 then
      tui.info(
        s"The copy keeps ${
            if kept == 1 then "1 change" else s"$kept changes"
          } from before; the project's own changes since then reach it after /apply or /discard."
      )
    // The copy's config is the project's: trusted when the project's is.
    val ownConfig = Files.isRegularFile(Config.projectPath(project))
    if ownConfig && ProjectTrust.pending(project, Config.globalDir).isEmpty &&
      ProjectTrust.pending(isolation.copy, Config.globalDir).isDefined
    then ProjectTrust.trust(isolation.copy, Config.globalDir)
    // A directory the copy lacks (new or ignored since the copy was made) is made there.
    val start = Files.createDirectories(isolation.copy.resolve(project.relativize(here))).nn
    moved(args.copy(cwd = start, isolatedFrom = Some(here), sessionMode = Some(Mode.Isolate)))

  /** The arguments that run the session in `project` again, in `mode`. */
  def argsLeaving(project: Path, mode: Mode): Cli.Args =
    moved(args.copy(cwd = project, isolatedFrom = None, sessionMode = Some(mode)))

  /** A moved session keeps its model, its effort and the `auto` switch. */
  private def moved(next: Cli.Args): Cli.Args =
    next.copy(auto = policy.auto, model = Some(agent.model.ref), sessionEffort = Some(agent.model.effort))

  /** Records the files the agent changes in each turn, for `/undo` (config
    * `checkpoints`). A `-p` run has nobody to undo anything. */
  val checkpoints: Option[Checkpoints] =
    if config.checkpoints && args.prompt.isEmpty then
      Some(Checkpoints(PlatformPath.canonical(cwd), Config.globalDir, host, policy))
    else None

  // ── agent ─────────────────────────────────────────────────────────

  val agent: Agent = Agent(
    config,
    AgentEnvironment.current(cwd, userPresent = args.prompt.isEmpty, commandsConfined = osSandbox.confined),
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

  /** Show the mode, the model with its effort and the directory in the status line. */
  def updateStatus(): Unit =
    val directory = Option(cwd.getFileName).fold(PlatformPath.display(cwd))(_.toString)
    val effort = agent.model.effort.fold("")(e => s" ($e)")
    val mode = if policy.auto then s"${policy.mode.label} auto" else policy.mode.label
    tui.setContext(mode, models.catalog.label(models.catalog.find(agent.model.ref)) + effort, directory)
  updateStatus()

  // ── commands ──────────────────────────────────────────────────────

  private val commands = Commands(this)
  tui.completions = commands.complete
  tui.commandList = SlashCommand.table

  // ── running ───────────────────────────────────────────────────────

  def run(): Int =
    try
      if policy.mode == Mode.Isolate && isolatedFrom.isEmpty then throw App.Restart(isolatedArgs(), None)
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
          resume match
            // A moved session carries its conversation, even an empty one, and offers no other.
            case Some(saved) => if saved.nonEmpty then commands.sessionCommands.resumeFrom(saved)
            case None => if tui.menusAvailable then commands.sessionCommands.offerResume()
          sandbox.warm() // after the resume offer: restoring would only discard it
          checkpoints.foreach(_.warm())
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
      ) ++ isolatedFrom.map(project => "copy of" -> s"${PlatformPath.display(project)} (/apply, /discard)")
        ++ osSandbox.notice.map("OS sandbox" -> _)
        ++ agent.classifiedModel.map(model => "classified model" -> models.describe(model))
        ++ Option.when(args.approveAll)("permissions" -> "every request approved without asking (--approve-all)")
        ++ Option.when(policy.auto)("permissions" -> "every request rejected without asking (/auto switches it)"),
      (List("/help commands", "Shift-Tab mode", "Ctrl-C interrupt", "Ctrl-O details", "Ctrl-D quit")
        ++ Option.when(predictor.enabled)("Tab or → accept a suggestion")).mkString(" · "),
    )

  /** Run one turn and retain its outcome for the terminal summary and scripted exit code. */
  private def runTurn(input: String): TurnOutcome =
    predictor.invalidate()
    tui.beginTurn()
    val started = System.nanoTime()
    val (usageBefore, callsBefore) = (agent.usage, agent.toolCalls)
    val rejectionsBefore = policy.rejectionCount
    var outcome = TurnOutcome.Failed
    checkpoints.foreach(_.beginTurn())
    try
      outcome = agent.turn(sandbox.ensure(), input, () => tui.isInterrupted, checkpoints.getOrElse(ToolCallHooks.None))
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
      showChanges()
      showUnapplied()
      showRejected(rejectionsBefore)
      predictor.start()

  /** After a turn: what the agent changed, which `/undo` can revert. */
  private def showChanges(): Unit =
    for c <- checkpoints do
      val changes = c.endTurn()
      c.takeFailure().foreach(reason => tui.warn(s"Checkpoints are off for this session: $reason"))
      if changes.nonEmpty then
        val shown = changes.take(App.ChangesShown).map(Checkpoints.describe).mkString(", ")
        val more = if changes.size > App.ChangesShown then s" and ${changes.size - App.ChangesShown} more" else ""
        val files = if changes.size == 1 then "1 file" else s"${changes.size} files"
        tui.info(s"Changed $files: $shown$more. /undo reverts them.")

  /** After a turn in isolate mode: how far the copy is from the project. */
  private def showUnapplied(): Unit =
    for i <- isolation do
      try
        val count = i.unapplied.size
        if count > 0 then
          val files = if count == 1 then "1 file" else s"$count files"
          tui.info(
            s"The copy differs from the project in $files: /apply writes the changes there, /discard drops them."
          )
      catch case NonFatal(e) => tui.warn(s"Could not compare the copy with the project: ${Debug.message(e)}")

  /** After a turn: the requests `auto` rejected in it, which `/perms grant` allows. */
  private def showRejected(since: Int): Unit =
    val rejected = policy.rejectedSince(since)
    if rejected.nonEmpty then
      tui.info(s"Auto rejected ${rejected.map(_._2).mkString("; ")}. /perms grant allows them for the session.")

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

  /** The input prompt names the mode unless it is the full one, and `auto` when it is on. */
  def prompt: String =
    (Option.when(policy.mode != Mode.Full)(policy.mode.label) ++ Option.when(policy.auto)("auto")).map(_ + " ")
      .mkString + "> "

object App:
  /** Thrown to end the program from setup, before there is anything to run. */
  final case class Exit(code: Int) extends RuntimeException(s"exit $code")

  /** The directory isolate mode copies for a session in `dir`: the project root its config
    * belongs to, or `dir` itself when it has none (the global config is not a project's). */
  def projectOf(dir: Path): Path =
    val here = PlatformPath.canonical(dir)
    Config.projectRoot(here).map(PlatformPath.canonical)
      .filterNot(root => root.resolve(".atc").nn == PlatformPath.canonical(Config.globalDir)).getOrElse(here)

  /** Thrown to run the session again with `args` (another directory, entering or leaving
    * isolate mode), carrying the conversation. A control throwable, so no handler of
    * ordinary failures swallows it on the way out. */
  final case class Restart(args: Cli.Args, resume: Option[SessionSnapshot])
      extends scala.util.control.ControlThrowable

  /** Changed files named after a turn; the rest are counted. */
  private val ChangesShown = 6

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
