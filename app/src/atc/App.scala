package atc

import atc.SlashCommand as Cmd
import atc.agent.{
  Agent, AgentEnvironment, InputPredictor, Prompts, ScalaToolRunner, SessionSnapshot, SessionStore, TurnOutcome
}
import atc.config.{Config, Configuration, ModelCatalog, ModelSpec, Origin}
import atc.host.{Host, HostLlm, HostOutput, HostUi}
import atc.lib.Todo
import atc.llm.{ChatModel, TokenUsage}
import atc.perms.*
import atc.platform.PlatformPath
import atc.sandbox.{ReplSession, SandboxConfig}
import atc.ui.{Ansi, Tui}

import java.nio.file.{Files, Path}
import scala.collection.mutable

/** The running application: wires configuration, models, permission policy,
  * host, sandbox session, agent loop and terminal UI together, then runs
  * either one non-interactive turn (`-p`) or the interactive loop with its
  * slash commands. */
final class App(args: Cli.Args, val tui: Tui):
  val cwd: Path = args.cwd

  /** Every configuration layer in force (global ← project ← `-c`), after the
    * first-run offers of [[App.setup]] (which may end the program instead). */
  val configuration: Configuration = App.setup(args, tui)
  /** The effective settings. The *policy* lists live on `configuration`. */
  val config: Config = configuration.settings
  val configFiles: List[Path] = configuration.sources

  // ── models ────────────────────────────────────────────────────────

  /** Every model of every configured provider, resolved with its key. */
  val catalog: ModelCatalog = configuration.catalog
  private val modelCache = mutable.Map[String, ChatModel]()

  /** The client for one configured model, created once per session. */
  def modelFor(spec: ModelSpec): ChatModel = modelCache.getOrElseUpdate(spec.ref, ChatModel.create(spec))

  /** The client for a model reference (`alias` or `provider/alias`). */
  def modelFor(reference: String): ChatModel = modelFor(catalog.find(reference))

  val initialModel: ChatModel =
    modelFor(args.model.orElse(config.model).map(catalog.find).getOrElse(catalog.default))
  val initialClassified: Option[ChatModel] = config.classifiedModel.map(modelFor)

  // ── sandbox session ───────────────────────────────────────────────

  @volatile var session: Option[ReplSession] = None
  tui.onInterrupt = () => session.foreach(_.interrupt())

  /** Exclude user input and operations with their own timeout from the snippet clock. */
  private def withClockPaused[T](body: => T): T =
    session.foreach(_.clock.pause())
    try body
    finally session.foreach(_.clock.resume())

  // ── permission policy ─────────────────────────────────────────────

  val prompter: PermissionPrompter =
    App.permissionPrompter(args, request => withClockPaused(tui.askPermission(request)))
  val policy =
    Policy(
      App.fileRules(configuration, cwd),
      config.commands,
      config.hosts,
      prompter,
      config.denyCommands,
      config.denyHosts
    )
  policy.mode = args.mode.orElse(config.mode.map(Mode.parse)).getOrElse(Mode.Full)

  // ── host (the sandbox API implementation) and its ports ───────────

  val output: HostOutput = new HostOutput:
    override def fileChanged(change: atc.host.FileChange): Unit = tui.fileChanged(change)
    def print(agentText: String, userText: String): Unit =
      session.foreach(_.printStream.print(agentText)) // into the tool result, in order with REPL output
      tui.agentPrint(agentText, userText)
    override def commandRunning(commandLine: String): Unit = tui.commandRunning(commandLine)
    override def commandOutput(text: String): Unit = tui.commandOutput(text)
    override def whileCommandRuns[T](body: => T): T = withClockPaused(body)
    override def processStarted(id: Int, commandLine: String): Unit =
      tui.processEvent(s"$$ $commandLine  [p$id started]")
    override def processInput(id: Int, text: String): Unit = tui.processEvent(s"p$id > ${text.stripSuffix("\n")}")
    override def processExited(id: Int, exitCode: Int): Unit = tui.processEvent(s"[p$id exited $exitCode]")
  val llm: HostLlm = new HostLlm:
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
  val hostUi: HostUi = new HostUi:
    def askUser(question: String, options: List[String], multiple: Boolean): Option[String] =
      withClockPaused(tui.askUser(question, options, multiple))
    def showTodos(items: List[Todo]): Unit = tui.showTodos(items)
  /** Listings hide what git ignores unless the config turns that off. */
  val gitIgnore: GitIgnore = if config.respectGitignore then GitIgnore(cwd) else GitIgnore.Disabled
  val host: Host = Host(policy, cwd, output, llm, hostUi, gitIgnore)

  // ── agent ─────────────────────────────────────────────────────────

  val agent: Agent = Agent(
    config,
    AgentEnvironment.current(cwd),
    policy,
    tui,
    initialModel,
    initialClassified,
    config.instructions,
    () => (host.currentTaskNotes, host.currentTodos),
  )
  tui.onSubmit = agent.submit
  tui.queuedInputs = () => agent.queuedInputCount

  private def updateStatusContext(): Unit =
    val directory = Option(cwd.getFileName).fold(App.pretty(cwd))(_.toString)
    tui.setContext(catalog.label(catalog.find(agent.model.ref)), policy.mode.label, directory)
  updateStatusContext()

  // ── running ───────────────────────────────────────────────────────

  private def ensureSession(): ReplSession = session.getOrElse {
    tui.status(s"starting sandbox (${policy.mode.label} mode)")
    val created = ReplSession(SandboxConfig(config.safeMode, policy.mode, config.executionTimeoutMs), host).init()
    if tui.isInterrupted then
      created.close()
      throw atc.llm.CancelledException()
    session = Some(created)
    created
  }

  /** Discard the REPL and its processes. The next tool call initializes a new session. */
  private def replaceSession(failure: String): Boolean =
    try
      host.killProcesses()
      session.foreach(_.close())
      session = None
      true
    catch
      case e: Exception =>
        tui.error(s"$failure: ${e.getMessage}")
        Debug.trace(e)
        false

  /** Replace the sandbox session (after `/reset` or a mode switch); the conversation is kept.
    * `reason` is passed to the agent so it knows its REPL definitions are gone. */
  private def restartSession(reason: String): Boolean =
    val ok = replaceSession("could not restart the sandbox")
    if ok then
      agent.noteSandboxRestarted(reason)
      // The restart notice is pending, not in history yet; any prediction now
      // would still assume that old REPL definitions exist.
      predictor.invalidate()
    ok

  /** Clear conversation, task state, output history and grants while retaining configured models and mode. */
  private def newSession(): Boolean =
    val replaced = replaceSession("could not clear the sandbox")
    if replaced then
      agent.clear()
      host.clearTodos()
      tui.clearOutputHistory()
      policy.resetSession()
    replaced

  def run(): Int =
    try
      // A directory no config covers is unreachable; say so rather than let the
      // agent discover it one denial at a time.
      if !policy.effective(ScopeId.Base, PlatformPath.canonical(cwd)).canRead then
        tui.info(
          s"No configuration grants access to $cwd, so the agent has to ask for every file. " +
            "Run `atc --init` to give this project a config, or add a rule to ~/.atc/config.json."
        )
      args.prompt match
        case Some(p) =>
          tui.askToContinue = false // nobody to ask: the tool budget is a hard stop here
          // Report a failed turn through the process exit code so scripts can detect it.
          runTurn(p).exitCode
        case None =>
          banner()
          if tui.menusAvailable then offerResume()
          interactive()
          if tui.menusAvailable then saveOnExit()
          0
    finally
      predictor.invalidate()
      host.killProcesses()
      session.foreach(_.close())
      modelCache.values.foreach { model =>
        try model.close()
        catch case scala.util.control.NonFatal(error) => Debug.trace(error)
      }

  private lazy val autoSaveFile = SessionStore.autoSavePath(PlatformPath.userHome, cwd)

  private def offerResume(): Unit =
    try
      if Files.exists(autoSaveFile) then
        val saved = SessionStore.read(autoSaveFile)
        if saved.nonEmpty then
          saved.userRequests.lastOption.foreach(text =>
            tui.preview(s"Last request: $text")
          )
          tui.choose(
            "Continue your last session in this directory?",
            List("Resume last session", "Start a new session")
          ) match
            case Some("Resume last session") => restoreSession(saved)
            case _ => ()
    catch
      case scala.util.control.NonFatal(error) =>
        tui.warn(s"Could not load the previous session: ${Debug.describe(error)}")
        Debug.trace(error)

  private def saveOnExit(): Unit =
    val saved = agent.snapshot
    if saved.nonEmpty then
      try
        SessionStore.checkpoint(autoSaveFile, saved)
        tui.info("Session saved. Start ATC in this directory to resume.")
      catch
        case scala.util.control.NonFatal(error) =>
          tui.error(s"Could not save the session: ${Debug.describe(error)}")
          Debug.trace(error)

  private def restoreSession(saved: SessionSnapshot): Unit =
    predictor.invalidate()
    if newSession() then
      host.restoreTaskState(saved.task, saved.todos)
      agent.restore(saved)
      if saved.model != agent.model.ref then
        tui.info(s"Using ${agent.model.ref}; the saved session used ${saved.model}.")
      tui.success(
        s"Resumed ${saved.history.size} messages with fresh permissions and REPL state. No tool calls were replayed."
      )

  /** `provider/alias — display-name-or-model-id`, how a model in use is named everywhere. */
  private def describe(m: ChatModel): String =
    App.describe(m, catalog.find(m.ref))

  private def banner(): Unit =
    tui.banner(
      s"atc ${Main.Version}",
      List(
        "model" -> describe(agent.model),
        "mode" -> policy.mode.describe,
        "directory" -> App.pretty(cwd),
      ) ++ agent.classifiedModel.map(model => "classified model" -> describe(model)),
      (List("/help commands", "Shift-Tab mode", "Ctrl-C interrupt", "Ctrl-O details", "Ctrl-D quit")
        ++ Option.when(predicting)("Tab or → accept the suggested next request")).mkString(" · "),
    )

  // ── next-input prediction ─────────────────────────────────────────

  /** Guesses the next request after each turn (config `predictInput`), shown
    * as ghost text at the prompt. Interactive runs on a real terminal only. */
  private val predictor =
    InputPredictor(() => agent.model, () => agent.history, tui.suggest, agent.recordUsage(Agent.Prediction, _))
  private val predicting: Boolean = config.predictInput && tui.suggestionsAvailable && args.prompt.isEmpty

  /** Retire a guess made from stale model/session state and predict again from
    * the state now in force. */
  private def refreshPrediction(): Unit =
    predictor.invalidate()
    if predicting then predictor.start()

  /** Run one turn and retain its outcome for the terminal summary and scripted exit code. */
  private def runTurn(input: String): TurnOutcome =
    predictor.invalidate()
    tui.beginTurn()
    val started = System.nanoTime()
    val (usageBefore, callsBefore) = (agent.usage, agent.toolCalls)
    var outcome = TurnOutcome.Failed
    try
      outcome = agent.turn(ensureSession(), input, () => tui.isInterrupted)
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
      if predicting then predictor.start()

  private def interactive(): Unit =
    var running = true
    while running do
      val next = if agent.queuedInputCount > 0 then Some("") else tui.readLine(prompt)
      next match
        case Some("") if agent.queuedInputCount > 0 => runTurn("")
        case None =>
          Debug.log("input closed, exiting")
          running = false
        case Some(line) if line.trim.isEmpty => ()
        // What people type out of habit; not listed in /help.
        case Some(line) if App.QuitWords.contains(line.trim.toLowerCase(java.util.Locale.ROOT)) => running = false
        case Some(line) if line.trim.startsWith("/") => running = command(line.trim)
        case Some(line) => runTurn(line)

  /** The input prompt names the mode unless it is the full one. */
  private def prompt: String = policy.mode match
    case Mode.Full => "> "
    case m => s"${m.label} > "

  // ── slash commands (the table is `SlashCommand`; this is what each one does) ──

  tui.completions = {
    case _ :: Nil => SlashCommand.names
    case "/model" :: _ :: Nil => catalog.labels
    case "/classifiedmodel" :: _ :: Nil => catalog.labels :+ "off"
    case "/mode" :: _ :: Nil => Mode.values.toList.map(_.label)
    case "/perms" :: _ :: Nil => List("revoke")
    case _ => Nil
  }

  /** Handle a slash command line; returns false to quit. */
  private def command(line: String): Boolean =
    SlashCommand.parse(line) match
      case Left(typed) =>
        tui.error(s"unknown command $typed (try /help)")
        true
      case Right((Cmd.Quit, _)) => false
      case Right((cmd, arg)) =>
        try dispatch(cmd, arg)
        catch
          case scala.util.control.NonFatal(error) =>
            tui.error(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
            Debug.trace(error)
        true

  private def dispatch(cmd: SlashCommand, arg: String): Unit = cmd match
    case Cmd.Help => tui.showHelp(SlashCommand.values.toList.map(command => command.usage -> command.help))
    case Cmd.Model => switchModel(arg)
    case Cmd.ClassifiedModel => switchClassifiedModel(arg)
    case Cmd.Models => showModels()
    case Cmd.Mode => switchMode(arg)
    case Cmd.Perms => permissions(arg)
    case Cmd.Config => showConfig()
    case Cmd.Interface => tui.println(Prompts.interfaceSource)
    case Cmd.Run => runCode(arg)
    case Cmd.New =>
      predictor.invalidate()
      if newSession() then
        tui.success("new session: conversation, task notes and session grants cleared")
    case Cmd.Reset =>
      if restartSession("you asked for /reset") then tui.success("REPL cleared; starts with the next tool call")
    case Cmd.Clear =>
      agent.clear()
      predictor.invalidate()
      tui.success("conversation cleared")
    case Cmd.Compact =>
      predictor.invalidate()
      tui.beginTurn()
      try
        agent.compact(arg, () => tui.isInterrupted) match
          case Agent.CompactOutcome.Compacted => tui.success("conversation context compacted")
          case Agent.CompactOutcome.NothingToCompact =>
            tui.info("Nothing to compact: the conversation fits the retention budget; history unchanged.")
          case Agent.CompactOutcome.SummaryNotSmaller =>
            tui.warn("The summary was no smaller than the history it would replace; history unchanged.")
      catch case _: atc.llm.CancelledException => tui.info("Compaction cancelled; history unchanged.")
      finally
        tui.endTurn()
        if predicting then predictor.start()
    case Cmd.Todos => tui.showTodosNow(host.currentTodos)
    // Both commands display model-generated process names, so strip terminal controls.
    case Cmd.Ps => tui.println(Ansi.sanitize(host.processSummary))
    case Cmd.Kill => tui.println(Ansi.sanitize(host.killProcess(arg)))
    case Cmd.Cost => showCost()
    case Cmd.Output => tui.showOutput(arg)
    case Cmd.Task =>
      val notes = host.currentTaskNotes
      if notes == atc.lib.TaskNotes() then tui.info("No task notes yet.")
      else
        if notes.goal.nonEmpty then tui.println(s"Goal: ${notes.goal}")
        List("Constraints" -> notes.constraints, "Completed" -> notes.completed, "Remaining" -> notes.remaining)
          .filter(_._2.nonEmpty).foreach((label, values) =>
            tui.println(s"$label:")
            values.foreach(value => tui.println(s"  - $value"))
          )
    case Cmd.Save =>
      val path = if arg.isEmpty then cwd.resolve(s".atc/sessions/session-${System.currentTimeMillis()}.json").nn
      else sessionPath(arg)
      try
        SessionStore.write(path, agent.snapshot)
        tui.success(s"Saved conversation to ${App.pretty(path)}")
      catch
        case _: java.nio.file.FileAlreadyExistsException =>
          tui.error(s"Save file already exists: ${App.pretty(path)}. Choose another filename.")
    case Cmd.Resume =>
      val path = if arg.isEmpty then autoSaveFile else sessionPath(arg)
      if arg.isEmpty && !Files.exists(path) then tui.info("No saved session for this directory.")
      else
        val saved = SessionStore.read(path)
        restoreSession(saved)
    case Cmd.Quit => () // `command` ends the loop instead

  /** `/run`: the user runs Scala in the sandbox themselves, against the same
    * API, givens and permissions as the agent, shown as a code block like an
    * agent tool call and with the same keys (Ctrl-C interrupts, Ctrl-O
    * expands). The code is on the line (Enter continues it while brackets
    * are open, see `Continuation`; a pasted block keeps its newlines) or,
    * with none, typed as a block that an empty line submits. The REPL is
    * shared, so the agent is told what was run and what came of it on its
    * next turn. */
  private def runCode(arg: String): Unit =
    // `/run` mutates the persistent REPL and queues a note that is not part of
    // history until the next real user turn. A prediction made before it is stale.
    predictor.invalidate()
    val code = if arg.nonEmpty then arg else readCode()
    if code.trim.isEmpty then return
    tui.beginTurn()
    try
      tui.toolStart(code, "/run")
      val s = ensureSession()
      val (result, decisions) = ScalaToolRunner.evaluate(s, policy, tui, code)
      agent.noteUserRan(code, result, decisions)
    catch
      case e: Exception =>
        tui.error(Debug.describe(e))
        Debug.trace(e)
    finally tui.endTurn()

  /** The block of code typed after a bare `/run`; empty when cancelled (Ctrl-C, Ctrl-D). */
  private def readCode(): String =
    tui.info("Scala code; Enter on an empty line runs it, Ctrl-C cancels")
    tui.suggest(None) // no ghost text while typing code
    tui.readBlock(prompt).getOrElse("")

  private def sessionPath(value: String): Path =
    val path = java.nio.file.Paths.get(PlatformPath.native(PlatformPath.expandHome(value))).nn
    (if path.isAbsolute then path else cwd.resolve(path).nn).normalize.nn

  /** Session grant selection and revocation; configured policy is unchanged. */
  private def permissions(arg: String): Unit =
    val grants = policy.sessionGrants
    def list(): Unit =
      if grants.isEmpty then tui.info("No session grants.")
      else grants.zipWithIndex.foreach((grant, index) => tui.println(s"  ${index + 1}. ${grant.describe}"))
    def revoke(grant: SessionGrant): Unit =
      predictor.invalidate()
      policy.revoke(grant)
      agent.notePermissionRevoked(grant.describe)
      tui.success(s"Revoked ${grant.describe} for future operations.")
    arg.trim.split("\\s+", 2).toList match
      case "" :: Nil =>
        tui.println(policy.summary)
        list()
        if grants.nonEmpty then tui.info("Use /perms revoke to remove a session grant; /kill stops existing processes.")
      case "revoke" :: "all" :: Nil => grants.foreach(revoke)
      case "revoke" :: number :: Nil =>
        number.toIntOption.flatMap(n => grants.lift(n - 1)) match
          case Some(grant) => revoke(grant)
          case None => tui.error("Unknown grant number. Run /perms to list current grants.")
      case "revoke" :: Nil =>
        if !tui.menusAvailable || grants.isEmpty then list()
        else
          val rows = grants.zipWithIndex.map((grant, index) => s"${index + 1}. ${grant.describe}")
          tui.choose("Revoke a session grant", rows).flatMap(row => grants.lift(rows.indexOf(row))).foreach(revoke)
      case _ => tui.error("Usage: /perms [revoke [number|all]]")

  /** `/config`: the layers, key names and scalar settings. */
  private def showConfig(): Unit =
    tui.println("config layers, in order:")
    configuration.layers.foreach(l => tui.println(l.describe))
    val keys = configuration.keys
    if keys.sources.nonEmpty then
      tui.println(s"key bindings: ${keys.names.mkString(", ")} (from ${keys.sources.mkString(", ")})")
    tui.println(
      s"safeMode=${config.safeMode} executionTimeoutMs=${config.executionTimeoutMs.getOrElse("none")} maxToolCalls=${config.maxToolCalls} respectGitignore=${config.respectGitignore} predictInput=${config.predictInput} autoCompactThreshold=${config.autoCompactThreshold} compactKeepRatio=${config.compactKeepRatio}"
    )
    tui.println(s"open permission scopes: ${policy.openScopeCount}")

  /** `/cost`: token usage in total and, when there is more than one purpose, by purpose. */
  private def showCost(): Unit =
    def show(u: TokenUsage) = s"input=${u.input} (cached ${u.cacheRead}) output=${u.output}"
    tui.println(s"tokens: ${show(agent.usage)}; tool calls: ${agent.toolCalls}")
    val by = agent.usageByPurpose
    if by.size > 1 then by.foreach((purpose, u) => tui.println(f"  $purpose%-22s ${show(u)}"))
    val context = agent.contextUsage
    val window = context.window.fold(" (no contextWindow configured for this model)")(_ => "")
    tui.println(s"${Tui.contextUsage(context.tokens, context.window)} estimated for the next request$window")

  /** One line per configured model: its selectable name, friendly name (or
    * `provider/model-id` fallback), and the role it currently plays. */
  private def modelRow(spec: ModelSpec): String =
    val marks = List(
      Option.when(agent.model.ref == spec.ref)("agent"),
      Option.when(agent.classifiedModel.exists(_.ref == spec.ref))("classified"),
    ).flatten
    val role = if marks.isEmpty then "" else s"  [${marks.mkString(", ")}]"
    s"${catalog.label(spec).padTo(labelWidth, ' ')}  ${App.modelDetail(spec)}$role"

  private lazy val labelWidth: Int = catalog.labels.map(_.length).maxOption.getOrElse(0)

  private def showModels(): Unit = catalog.models.foreach(m => tui.println("  " + modelRow(m)))

  /** Pick a model from the list. Without a menu (plain mode) the list is
    * printed instead, so the user can name one with `/model <ref>`. */
  private def pickModel(title: String): Option[ModelSpec] =
    val rows = catalog.models.map(modelRow)
    tui.choose(title, rows) match
      case Some(row) => catalog.models.zip(rows).collectFirst { case (m, r) if r == row => m }
      case None =>
        if !tui.menusAvailable then showModels()
        None

  /** `/model`: pick from the list, or switch to the named one. */
  private def switchModel(arg: String): Unit =
    setModel(arg, "model", describe(agent.model)) { spec =>
      agent.model = modelFor(spec)
      updateStatusContext()
      refreshPrediction()
      tui.success(s"model -> ${describe(agent.model)}" + remember("model", Some(spec)))
    }

  /** `/classifiedmodel`: the trusted isolated model used by `classifiedChat`. `off` unsets it. */
  private def switchClassifiedModel(arg: String): Unit =
    if Set("off", "none").contains(arg.trim.toLowerCase(java.util.Locale.ROOT)) then
      agent.classifiedModel = None
      refreshPrediction()
      tui.success(
        "classified model -> (none): classified data is no longer sent to any model" + remember("classifiedModel", None)
      )
    else
      val current = agent.classifiedModel.map(describe).getOrElse("(none)")
      setModel(arg, "classified model", current) { spec =>
        val m = modelFor(spec)
        agent.classifiedModel = Some(m)
        refreshPrediction()
        tui.success(s"classified model -> ${describe(m)}" + remember("classifiedModel", Some(spec)))
      }

  /** Shared by the two switches: an argument names a model, no argument opens
    * the picker; the current one is reported when nothing is chosen. */
  private def setModel(arg: String, what: String, current: String)(use: ModelSpec => Unit): Unit =
    if arg.nonEmpty then
      try use(catalog.find(arg))
      catch case e: IllegalArgumentException => tui.error(e.getMessage)
    else
      pickModel(s"Choose the $what") match
        case Some(spec) => use(spec)
        case None => tui.info(s"$what: $current")

  /** The working directory's own `.atc/config.json`, if it has one. Only that
    * file is ever written: a project config found in a parent directory
    * governs this run but is not touched from a sub-directory. */
  private def projectConfig: Option[Path] =
    Some(Config.projectPath(cwd)).filter(Files.isRegularFile(_))

  /** Keep a model choice in the working directory's config, so the next run
    * here starts with it (`None` unsets the role: `"classifiedModel": null`).
    * Without a config in `cwd` there is nothing to write. Returns the note to
    * append to the confirmation. */
  private def remember(key: String, choice: Option[ModelSpec]): String =
    def show(p: Path): String =
      val abs = p.toAbsolutePath.nn.normalize.nn
      if abs.startsWith(cwd) then cwd.relativize(abs).toString else App.pretty(abs)
    projectConfig match
      case None => ""
      case Some(path) =>
        val value = choice.map(m => ujson.Str(catalog.label(m))).getOrElse(ujson.Null)
        try
          Config.setTopLevel(path, key, value, after = List("model"))
          // A `-c` file that sets the same key wins over the project config on the next start.
          val overridden = configuration.layers
            .filter(l => l.origin == Origin.Explicit && l.defines(key))
            .flatMap(_.path)
            .filterNot(_.toAbsolutePath.nn.normalize == path.toAbsolutePath.nn.normalize)
            .headOption
            .map(p => s"; ${show(p)} also sets $key and wins over it")
            .getOrElse("")
          s" (saved to ${show(path)}$overridden)"
        catch
          case e: Exception =>
            tui.error(s"could not save the choice to ${show(path)}: ${e.getMessage}")
            ""

  /** `/mode`: cycle (no argument) or set the sandbox mode; a new REPL is
    * started with only that mode's capabilities (definitions are gone, the
    * conversation stays). */
  private def switchMode(arg: String): Unit =
    val target =
      if arg.isEmpty then Some(policy.mode.next)
      else
        try Some(Mode.parse(arg))
        catch
          case e: IllegalArgumentException =>
            tui.error(e.getMessage)
            None
    target.foreach { m =>
      if m == policy.mode then tui.info(s"mode: ${m.describe}")
      else
        val previous = policy.mode
        policy.mode = m
        if restartSession(s"the sandbox mode changed to ${m.label}") then
          updateStatusContext()
          tui.success(s"mode -> ${m.describe} (fresh REPL)")
        else policy.mode = previous
    }

object App:

  /** Presentation only: references and provider requests continue to use the
    * configured alias and backend model id. */
  private[atc] def describe(model: ChatModel, spec: ModelSpec): String =
    s"${model.ref} — ${spec.displayName.getOrElse(model.modelId)}" +
      (if model.webSearch then " (web search)" else "")

  /** The detail column of `/models`, with the historical provider/model-id
    * form retained when no friendly name is configured. */
  private[atc] def modelDetail(spec: ModelSpec): String =
    spec.displayName.getOrElse(s"${spec.provider}/${spec.modelId}")

  /** Thrown to end the program from setup, before there is anything to run. */
  final case class Exit(code: Int) extends RuntimeException(s"exit $code")

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

  /** Load the configuration, offering to write what is missing first. No
    * configuration is written without asking, and nothing is asked in a
    * scripted (`-p`) run:
    *
    *  - no `~/.atc/config.json`: offer to write the starting config and the
    *    key bindings beside it. Declined (or `-p`), the bundled starting config
    *    stands in for this run.
    *  - no config grants the working directory and it has no `.atc/config.json`
    *    of its own: offer to write the starting project config there (as
    *    `--init` does), and use it at once.
    *
    * When the global config was written the program then stops (via [[Exit]]),
    * so the user can fill in the keys or export them and start again. */
  def setup(args: Cli.Args, tui: Tui): Configuration =
    val interactive = args.prompt.isEmpty
    val global = Config.globalPath
    val globalMissing = !Files.isRegularFile(global)
    val writeGlobal =
      globalMissing && interactive && {
        tui.println(s"No configuration at ${pretty(global)}.")
        tui.confirm("Write the starting config and key bindings there? (No: use the built-in ones for now)")
      }
    if writeGlobal then tui.println(s"Wrote ${Config.ensureGlobal().map(pretty).mkString(" and ")}.")
    else if globalMissing then
      tui.info(s"Using the built-in starting config for this run (`atc --init-global` writes it).")
    val bundledGlobal = globalMissing && !writeGlobal

    def cwdReadable(c: Configuration): Boolean =
      Policy(fileRules(c, args.cwd), Nil, Nil, _ => Decision.Deny)
        .effective(ScopeId.Base, PlatformPath.canonical(args.cwd)).canRead

    def offerProjectConfig(current: Configuration): Configuration =
      val project = Config.projectPath(args.cwd)
      val shouldOffer = interactive && !cwdReadable(current) && !Files.exists(project)
      if !shouldOffer then current
      else
        tui.println(
          s"No configuration grants access to ${pretty(args.cwd)}, so the agent would have to ask for every file."
        )
        val accepted =
          tui.confirm(s"Write a starting project config to ${pretty(project)}? (It opens this directory to the agent)")
        if !accepted then current
        else
          val created = Config.initProject(args.cwd).map(pretty).mkString(" and ")
          tui.println(s"Wrote $created; edit it to change what the agent may touch here.")
          Config.load(args.cwd, args.config, bundledGlobal)

    // Offered whenever cwd has no `.atc/config.json` of its own and nothing
    // grants it, whatever an ancestor's project config (or the home `.atc`,
    // which the walk-up also finds) says: the new file becomes the nearest
    // project config and takes over from there.
    val configuration = offerProjectConfig(Config.load(args.cwd, args.config, bundledGlobal))

    if writeGlobal then
      tui.println(
        s"Fill in the API keys in ${pretty(global.getParent.nn.resolve(Config.KeysFile).nn)} " +
          "(or export them in the environment), then start atc again."
      )
      throw Exit(0)
    configuration

  /** Bare lines that quit like `/quit`: shell and editor habits. */
  val QuitWords: Set[String] = Set(":q", "exit", "quit")

  /** A path for display: under `~` when inside the home directory. */
  def pretty(p: Path): String =
    val home = PlatformPath.userHome
    if p == home then "~"
    else if p.startsWith(home) then "~/" + PlatformPath.portable(home.relativize(p))
    else PlatformPath.portable(p)

  /** The configured file rules, in layer order. Nothing is granted here or
    * anywhere else in the program: a path is reachable only because a config
    * says so: `~/.atc/config.json` for anything, a project's own
    * `.atc/config.json` for paths inside that project. */
  def fileRules(configuration: Configuration, cwd: Path): List[FileRule] =
    configuration.rules.map { r =>
      FileRule(
        // A project layer reads its relative patterns against its own folder,
        // and grants only inside it.
        PathPattern(r.rule.path, r.base.getOrElse(cwd)),
        r.rule.access.map(Access.parse),
        r.rule.classified,
        r.rule.locked,
        grantsWithin = r.base,
      )
    }
