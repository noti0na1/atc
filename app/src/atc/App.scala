package atc

import atc.SlashCommand as Cmd
import atc.agent.{Agent, InputPredictor, Prompts}
import atc.config.{Config, Configuration, ModelCatalog, ModelSpec, Origin}
import atc.host.{Host, HostLlm, HostOutput, HostUi}
import atc.lib.Todo
import atc.llm.{ChatModel, TokenUsage}
import atc.perms.*
import atc.sandbox.{ReplSession, SandboxConfig}
import atc.ui.{Ansi, Tui}

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.util.Properties

/** The running application: wires configuration, models, permission policy,
  * host, sandbox session, agent loop and terminal UI together, then runs
  * either one non-interactive turn (`-p`) or the interactive loop with its
  * slash commands. */
final class App(args: Main.Args):
  val cwd: Path = args.cwd
  val tui = Tui(Paths.get(Properties.userHome, ".atc", "history"))

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

  /** Show a pop-up: the time the human takes to answer does not count against the execution timeout. */
  private def whileUserDecides[T](popup: => T): T = withClockPaused(popup)

  /** Nor does the time a command runs (it has its own timeout, see `ExecOptions`). */
  private def withClockPaused[T](body: => T): T =
    session.foreach(_.clock.pause())
    try body
    finally session.foreach(_.clock.resume())

  // ── permission policy ─────────────────────────────────────────────

  val prompter: PermissionPrompter =
    App.permissionPrompter(args, request => whileUserDecides(tui.askPermission(request)))
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

  val output = new HostOutput:
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
  val llm = new HostLlm:
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
  val hostUi = new HostUi:
    def askUser(question: String, options: List[String], multiple: Boolean): Option[String] =
      whileUserDecides(tui.askUser(question, options, multiple))
    def showTodos(items: List[Todo]): Unit = tui.showTodos(items)
  /** Listings hide what git ignores unless the config turns that off. */
  val gitIgnore: GitIgnore = if config.respectGitignore then GitIgnore(cwd) else GitIgnore.Disabled
  val host = Host(policy, cwd, output, llm, hostUi, gitIgnore)

  // ── agent ─────────────────────────────────────────────────────────

  val agent = Agent(config, cwd, policy, tui, initialModel, initialClassified, config.instructions)

  // ── running ───────────────────────────────────────────────────────

  private def newReplSession(): ReplSession =
    tui.status(s"starting sandbox (${policy.mode.label} mode)")
    try ReplSession(SandboxConfig(config.safeMode, policy.mode, config.executionTimeoutMs), host).init()
    finally tui.endTurn()

  /** Close the sandbox session, if any, and start a fresh one. False (after
    * reporting `failure`) when the new one could not start; the app then runs
    * without a session until the next attempt. */
  private def replaceSession(failure: String): Boolean =
    host.killProcesses() // spawned processes belong to the session
    session.foreach(_.close())
    session = None
    try
      session = Some(newReplSession())
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

  /** `/new`: start over as if atc had just been launched, keeping only what
    * the user configured (mode, models). The sandbox is closed, the
    * conversation, TODO list and every session-scoped permission grant are
    * forgotten, and a fresh REPL is started. Nothing refers to the old session
    * afterwards, so its compiler and class loader can be collected; the GC is
    * asked for explicitly since that is most of the process's memory. */
  private def newSession(): Boolean =
    host.killProcesses()
    session.foreach(_.close())
    session = None
    agent.clear()
    host.clearTodos()
    policy.resetSession()
    System.gc()
    replaceSession("could not start the sandbox")

  def run(): Int =
    try
      // A directory no config covers is unreachable; say so rather than let the
      // agent discover it one denial at a time.
      if !policy.effective(ScopeId.Base, PathPattern.canonical(cwd)).canRead then
        tui.info(
          s"No configuration grants access to $cwd, so the agent has to ask for every file. " +
            "Run `atc --init` to give this project a config, or add a rule to ~/.atc/config.json."
        )
      args.prompt match
        case Some(p) =>
          tui.askToContinue = false // nobody to ask: the tool budget is a hard stop here
          session = Some(newReplSession())
          // Report a failed turn through the process exit code so scripts can detect it.
          if runTurn(p) then 0 else 1
        case None =>
          banner()
          session = Some(newReplSession())
          interactive()
          0
    finally
      host.killProcesses()
      session.foreach(_.close())
      tui.close()

  /** `provider/alias — model-id`, how a model in use is named everywhere. */
  private def describe(m: ChatModel): String =
    s"${m.ref} — ${m.modelId}" + (if m.webSearch then " (web search)" else "")

  private def banner(): Unit =
    val noClassifiedModel = "(none — set \"classifiedModel\" in the config to use classifiedChat)"
    val noConfig = "(none; built-in defaults — try `atc --init`)"
    tui.banner(
      s"atc ${Main.Version}",
      List(
        "model" -> describe(agent.model),
        "classified model" -> agent.classifiedModel.map(describe).getOrElse(noClassifiedModel),
        "cwd" -> App.pretty(cwd),
        "config" -> (if configFiles.isEmpty then noConfig else configFiles.map(App.pretty).mkString(", ")),
        "mode" -> policy.mode.describe,
        "sandbox" -> List(
          s"safe mode ${if config.safeMode then "on" else "off"}",
          config.executionTimeoutMs.map(ms => s"timeout ${ms / 1000} s").getOrElse("no timeout"),
          s"max ${config.maxToolCalls} tool calls/turn"
        ).mkString(" · "),
      ),
      (List("Type a request", "/help commands", "Shift-Tab or /mode cycle mode")
        ++ Option.when(predicting)("Tab or → accept the suggested next request")
        ++ List("Ctrl-C interrupt", "Ctrl-O expand/collapse", "Ctrl-D quit")).mkString(" · "),
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

  /** Run one turn. Returns `false` if no sandbox is available or the turn throws,
    * allowing a `-p` invocation to exit with a non-zero status. */
  private def runTurn(input: String): Boolean =
    predictor.invalidate()
    tui.beginTurn()
    val started = System.nanoTime()
    val (usageBefore, callsBefore) = (agent.usage, agent.toolCalls)
    try
      session match
        case Some(s) =>
          agent.turn(s, input, () => tui.isInterrupted)
          true
        case None =>
          tui.error(App.SandboxUnavailable)
          false
    catch
      case e: Exception =>
        tui.error(s"${e.getClass.getSimpleName}: ${e.getMessage}")
        Debug.trace(e)
        false
    finally
      val tokens = (agent.usage.input + agent.usage.output) - (usageBefore.input + usageBefore.output)
      val context = agent.contextUsage
      tui.endTurn(Some(Tui.TurnStats(
        (System.nanoTime() - started) / 1e9,
        agent.toolCalls - callsBefore,
        tokens,
        context.tokens,
        context.window,
      )))
      if predicting then predictor.start()

  private def interactive(): Unit =
    var running = true
    while running do
      tui.readLine(prompt) match
        case None =>
          Debug.log("input closed, exiting")
          running = false
        case Some(line) if line.trim.isEmpty => ()
        // What people type out of habit; not listed in /help.
        case Some(line) if App.QuitWords.contains(line.trim.toLowerCase) => running = false
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
        dispatch(cmd, arg)
        true

  private def dispatch(cmd: SlashCommand, arg: String): Unit = cmd match
    case Cmd.Help => tui.println(SlashCommand.helpText)
    case Cmd.Model => switchModel(arg)
    case Cmd.ClassifiedModel => switchClassifiedModel(arg)
    case Cmd.Models => showModels()
    case Cmd.Mode => switchMode(arg)
    case Cmd.Perms => tui.println(policy.summary)
    case Cmd.Config => showConfig()
    case Cmd.Interface => tui.println(Prompts.interfaceSource)
    case Cmd.Run => runCode(arg)
    case Cmd.New =>
      predictor.invalidate()
      if newSession() then
        tui.success("new session: conversation, TODO list and session grants forgotten; sandbox restarted")
    case Cmd.Reset => if restartSession("you asked for /reset") then tui.success("sandbox restarted")
    case Cmd.Clear =>
      agent.clear()
      predictor.invalidate()
      tui.success("conversation cleared")
    case Cmd.Todos => tui.showTodosNow(host.currentTodos)
    // Both commands display model-generated process names, so strip terminal controls.
    case Cmd.Ps => tui.println(Ansi.sanitize(host.processSummary))
    case Cmd.Kill => tui.println(Ansi.sanitize(host.killProcess(arg)))
    case Cmd.Cost => showCost()
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
    session match
      case None => tui.error(App.SandboxUnavailable)
      case Some(s) =>
        tui.beginTurn()
        try
          tui.toolStart(code, "/run")
          val start = System.nanoTime()
          val decisionsBefore = policy.decisionCount
          val result = s.run(code)
          val millis = (System.nanoTime() - start - s.clock.paused) / 1_000_000L
          tui.toolEnd(result, millis)
          agent.noteUserRan(code, result, policy.decisionsSince(decisionsBefore))
        catch
          case e: Exception =>
            tui.error(s"${e.getClass.getSimpleName}: ${e.getMessage}")
            Debug.trace(e)
        finally tui.endTurn()

  /** The block of code typed after a bare `/run`; empty when cancelled (Ctrl-C, Ctrl-D). */
  private def readCode(): String =
    tui.info("Scala code; Enter on an empty line runs it, Ctrl-C cancels")
    tui.suggest(None) // no ghost text while typing code
    tui.readBlock(prompt).getOrElse("")

  /** `/config`: the layers, which key names are bound (never the values), and the scalar settings. */
  private def showConfig(): Unit =
    tui.println("config layers, in order:")
    configuration.layers.foreach(l => tui.println(l.describe))
    val keys = configuration.keys
    if keys.sources.nonEmpty then
      tui.println(s"key bindings: ${keys.names.mkString(", ")} (from ${keys.sources.mkString(", ")})")
    tui.println(
      s"safeMode=${config.safeMode} executionTimeoutMs=${config.executionTimeoutMs.getOrElse("none")} maxToolCalls=${config.maxToolCalls} respectGitignore=${config.respectGitignore} predictInput=${config.predictInput}"
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

  /** One line per configured model: its name, `provider/model-id`, and the
    * role it currently plays. */
  private def modelRow(spec: ModelSpec): String =
    val marks = List(
      Option.when(agent.model.ref == spec.ref)("agent"),
      Option.when(agent.classifiedModel.exists(_.ref == spec.ref))("classified"),
    ).flatten
    val role = if marks.isEmpty then "" else s"  [${marks.mkString(", ")}]"
    val width = catalog.labels.map(_.length).maxOption.getOrElse(0)
    s"${catalog.label(spec).padTo(width, ' ')}  ${spec.provider}/${spec.modelId}$role"

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
      refreshPrediction()
      tui.success(s"model -> ${describe(agent.model)}" + remember("model", Some(spec)))
    }

  /** `/classifiedmodel`: the trusted isolated model used by `classifiedChat`. `off` unsets it. */
  private def switchClassifiedModel(arg: String): Unit =
    if arg.trim.toLowerCase == "off" || arg.trim.toLowerCase == "none" then
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
          tui.success(s"mode -> ${m.describe} (fresh REPL)")
        else policy.mode = previous
    }

object App:
  private val SandboxUnavailable = "the sandbox is not running (a restart failed); try /reset"

  /** Thrown to end the program from setup, before there is anything to run. */
  final case class Exit(code: Int) extends RuntimeException(s"exit $code")

  /** A scripted turn has nobody to answer permission pop-ups. Fail closed
    * without reading stdin unless the caller explicitly chose `--approve-all`. */
  private[atc] def permissionPrompter(
    args: Main.Args,
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
  def setup(args: Main.Args, tui: Tui): Configuration =
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
        .effective(ScopeId.Base, PathPattern.canonical(args.cwd)).canRead

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
      tui.close()
      throw Exit(0)
    configuration

  /** Bare lines that quit like `/quit`: shell and editor habits. */
  val QuitWords: Set[String] = Set(":q", "exit", "quit")

  /** A path for display: under `~` when inside the home directory. */
  def pretty(p: Path): String =
    val home = Paths.get(Properties.userHome)
    if p == home then "~" else if p.startsWith(home) then "~/" + home.relativize(p) else p.toString

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
