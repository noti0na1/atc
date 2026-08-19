package atc

import atc.agent.{Agent, InputPredictor, Prompts}
import atc.config.{Config, Configuration, ModelCatalog, ModelSpec, Origin}
import atc.host.{Host, HostLlm, HostOutput, HostUi}
import atc.lib.Todo
import atc.llm.{ChatModel, TokenUsage}
import atc.perms.*
import atc.sandbox.{ReplSession, SandboxConfig}
import atc.ui.Tui

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
  private def whileUserDecides[T](popup: => T): T =
    session.foreach(_.clock.pause())
    try popup
    finally session.foreach(_.clock.resume())

  // ── permission policy ─────────────────────────────────────────────

  val prompter: PermissionPrompter =
    if args.approveAll then (_ => Decision.AllowSession)
    else request => whileUserDecides(tui.askPermission(request))
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
  val llm = new HostLlm:
    def chat(message: String): String =
      val reply = agent.model.simple(None, message)
      agent.recordUsage(Agent.Chat, reply.usage)
      reply.text
    def chatClassified(message: String): String =
      val model = agent.classifiedModel.getOrElse(throw RuntimeException(
        "No classified model configured: set \"classifiedModel\" in the config to a model that may see classified data."
      ))
      val reply =
        model.simple(
          Some("You are a trusted assistant handling confidential data. Answer directly and concisely."),
          message
        )
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

  /** Replace the sandbox session (after `/reset` or a mode switch); the conversation is kept.
    * `reason` is passed to the agent so it knows its REPL definitions are gone. */
  private def restartSession(reason: String): Boolean =
    session.foreach(_.close())
    session = None
    try
      session = Some(newReplSession())
      agent.noteSandboxRestarted(reason)
      true
    catch
      case e: Exception =>
        tui.error(s"could not restart the sandbox: ${e.getMessage}")
        Debug.trace(e)
        false

  /** `/new`: start over as if atc had just been launched, keeping only what
    * the user configured (mode, models). The sandbox is closed, the
    * conversation, TODO list and every session-scoped permission grant are
    * forgotten, and a fresh REPL is started. Nothing refers to the old session
    * afterwards, so its compiler and class loader can be collected; the GC is
    * asked for explicitly since that is most of the process's memory. */
  private def newSession(): Boolean =
    session.foreach(_.close())
    session = None
    agent.clear()
    host.clearTodos()
    policy.resetSession()
    System.gc()
    try
      session = Some(newReplSession())
      true
    catch
      case e: Exception =>
        tui.error(s"could not start the sandbox: ${e.getMessage}")
        Debug.trace(e)
        false

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
          session = Some(newReplSession())
          runTurn(p)
        case None =>
          banner()
          session = Some(newReplSession())
          interactive()
      0
    finally
      session.foreach(_.close())
      tui.close()

  /** `provider/alias — model-id`, how a model in use is named everywhere. */
  private def describe(m: ChatModel): String =
    s"${m.ref} — ${m.modelId}" + (if m.webSearch then " (web search)" else "")

  private def banner(): Unit =
    val noClassifiedModel = "(none — set \"classifiedModel\" in the config to chat about classified data)"
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

  private def runTurn(input: String): Unit =
    predictor.invalidate()
    tui.beginTurn()
    val started = System.nanoTime()
    val (usageBefore, callsBefore) = (agent.usage, agent.toolCalls)
    try
      session match
        case Some(s) => agent.turn(s, input, () => tui.isInterrupted)
        case None => tui.error("the sandbox is not running (a restart failed); try /reset")
    catch
      case e: Exception =>
        tui.error(s"${e.getClass.getSimpleName}: ${e.getMessage}")
        Debug.trace(e)
    finally
      val tokens = (agent.usage.input + agent.usage.output) - (usageBefore.input + usageBefore.output)
      tui.endTurn(Some(Tui.TurnStats((System.nanoTime() - started) / 1e9, agent.toolCalls - callsBefore, tokens)))
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

  // ── slash commands ────────────────────────────────────────────────

  /** The commands `/help` lists, for Tab completion (aliases are accepted but not offered). */
  private val commandNames = List(
    "/help",
    "/model",
    "/classifiedmodel",
    "/models",
    "/mode",
    "/perms",
    "/config",
    "/interface",
    "/new",
    "/reset",
    "/clear",
    "/todos",
    "/cost",
    "/quit",
  )
  tui.completions = {
    case _ :: Nil => commandNames
    case "/model" :: _ :: Nil => catalog.labels
    case "/classifiedmodel" :: _ :: Nil => catalog.labels :+ "off"
    case "/mode" :: _ :: Nil => Mode.values.toList.map(_.label)
    case _ => Nil
  }

  private val helpText =
    """Commands:
      |  /help                   this help
      |  /model [ref]            switch the agent model (no argument: pick from a list)
      |  /classifiedmodel [ref]  switch the model that may see classified data ("off" to unset)
      |  /models                 list configured models
      |  /mode [name]            cycle the sandbox mode (readonly → local → full), or set it; restarts the REPL
      |  /perms                  show the effective permission policy
      |  /config                 show config files and settings
      |  /interface              show the sandbox API reference
      |  /new                    start over: fresh REPL, conversation, TODOs and session grants forgotten
      |  /reset                  restart the sandbox REPL (keeps the conversation)
      |  /clear                  forget the conversation (keeps the REPL)
      |  /todos                  show the agent's TODO list
      |  /cost                   show token usage
      |  /quit                   exit""".stripMargin

  /** Handle a slash command; returns false to quit. */
  private def command(line: String): Boolean =
    val parts = line.split("\\s+", 2)
    val cmd = parts(0).toLowerCase
    val arg = if parts.length > 1 then parts(1).trim else ""
    if cmd == "/quit" || cmd == "/exit" || cmd == "/q" then false
    else
      dispatch(cmd, arg)
      true

  /** Run one (non-quitting) slash command. */
  private def dispatch(cmd: String, arg: String): Unit =
    cmd match
      case "/help" | "/h" | "/?" => tui.println(helpText)
      case "/models" => showModels()
      case "/model" => switchModel(arg)
      case "/classifiedmodel" | "/classified" => switchClassifiedModel(arg)
      case "/perms" | "/permissions" => tui.println(policy.summary)
      case "/mode" => switchMode(arg)
      case "/config" =>
        tui.println("config layers, in order:")
        configuration.layers.foreach(l => tui.println(l.describe))
        // Which providers have a key, never the keys themselves.
        val keys = configuration.keys
        if keys.sources.nonEmpty then
          tui.println(s"key bindings: ${keys.names.mkString(", ")} (from ${keys.sources.mkString(", ")})")
        tui.println(
          s"safeMode=${config.safeMode} executionTimeoutMs=${config.executionTimeoutMs.getOrElse("none")} maxToolCalls=${config.maxToolCalls} respectGitignore=${config.respectGitignore} predictInput=${config.predictInput}"
        )
        tui.println(s"open permission scopes: ${policy.openScopeCount}")
      case "/interface" | "/api" => tui.println(Prompts.interfaceSource)
      case "/new" =>
        predictor.invalidate()
        if newSession() then
          tui.success("new session: conversation, TODO list and session grants forgotten; sandbox restarted")
      case "/reset" =>
        if restartSession("you asked for /reset") then tui.success("sandbox restarted")
      case "/clear" =>
        agent.clear()
        predictor.invalidate()
        tui.success("conversation cleared")
      case "/todos" | "/todo" => tui.showTodosNow(host.currentTodos)
      case "/cost" | "/usage" =>
        def show(u: TokenUsage) = s"input=${u.input} (cached ${u.cacheRead}) output=${u.output}"
        tui.println(s"tokens: ${show(agent.usage)}; tool calls: ${agent.toolCalls}")
        val by = agent.usageByPurpose
        if by.size > 1 then by.foreach((purpose, u) => tui.println(f"  $purpose%-22s ${show(u)}"))
      case other => tui.error(s"unknown command $other (try /help)")

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
    setModel(arg, "model", agent.model) { spec =>
      agent.model = modelFor(spec)
      tui.success(s"model -> ${describe(agent.model)}" + remember("model", Some(spec)))
    }

  /** `/classifiedmodel`: the model that may see classified data. `off` unsets it. */
  private def switchClassifiedModel(arg: String): Unit =
    if arg.trim.toLowerCase == "off" || arg.trim.toLowerCase == "none" then
      agent.classifiedModel = None
      tui.success(
        "classified model -> (none): classified data is no longer sent to any model" + remember("classifiedModel", None)
      )
    else
      setModel(arg, "classified model", agent.classifiedModel.getOrElse(agent.model)) { spec =>
        val m = modelFor(spec)
        agent.classifiedModel = Some(m)
        tui.success(s"classified model -> ${describe(m)}" + remember("classifiedModel", Some(spec)))
      }

  /** Shared by the two switches: an argument names a model, no argument opens
    * the picker; the current one is reported when nothing is chosen. */
  private def setModel(arg: String, what: String, current: ChatModel)(use: ModelSpec => Unit): Unit =
    if arg.nonEmpty then
      try use(catalog.find(arg))
      catch case e: IllegalArgumentException => tui.error(e.getMessage)
    else
      pickModel(s"Choose the $what") match
        case Some(spec) => use(spec)
        case None => tui.info(s"$what: ${describe(current)}")

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
        catch case e: IllegalArgumentException => { tui.error(e.getMessage); None }
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
  /** Thrown to end the program from setup, before there is anything to run. */
  final case class Exit(code: Int) extends RuntimeException(s"exit $code")

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
    val writeGlobal = globalMissing && interactive && {
      tui.println(s"No configuration at ${pretty(global)}.")
      tui.confirm("Write the starting config and key bindings there? (No: use the built-in ones for now)")
    }
    if writeGlobal then tui.println(s"Wrote ${Config.ensureGlobal().map(pretty).mkString(" and ")}.")
    else if globalMissing then
      tui.info(s"Using the built-in starting config for this run (`atc --init-global` writes it).")
    val bundledGlobal = globalMissing && !writeGlobal
    var configuration = Config.load(args.cwd, args.config, bundledGlobal)

    def cwdReadable(c: Configuration): Boolean =
      Policy(fileRules(c, args.cwd), Nil, Nil, _ => Decision.Deny)
        .effective(ScopeId.Base, PathPattern.canonical(args.cwd)).canRead
    // Offered whenever cwd has no `.atc/config.json` of its own and nothing
    // grants it, whatever an ancestor's project config (or the home `.atc`,
    // which the walk-up also finds) says: the new file becomes the nearest
    // project config and takes over from there.
    if interactive && !cwdReadable(configuration) && !Files.exists(Config.projectPath(args.cwd)) then
      tui.println(
        s"No configuration grants access to ${pretty(args.cwd)}, so the agent would have to ask for every file."
      )
      val project = Config.projectPath(args.cwd)
      if tui.confirm(s"Write a starting project config to ${pretty(project)}? (It opens this directory to the agent)")
      then
        tui.println(
          s"Wrote ${Config.initProject(args.cwd).map(pretty).mkString(" and ")}; edit it to change what the agent may touch here."
        )
        configuration = Config.load(args.cwd, args.config, bundledGlobal)

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
