package atc

import atc.agent.{Agent, Prompts}
import atc.config.{Config, FileRuleConfig, ModelCatalog, ModelSpec}
import atc.host.{Host, HostLlm, HostOutput, HostUi}
import atc.lib.Todo
import atc.llm.ChatModel
import atc.perms.*
import atc.sandbox.{ReplSession, SandboxConfig}
import atc.ui.Tui

import java.nio.file.{Path, Paths}
import scala.collection.mutable

/** The running application: wires configuration, models, permission policy,
  * host, sandbox session, agent loop and terminal UI together, then runs
  * either one non-interactive turn (`-p`) or the interactive loop with its
  * slash commands. */
final class App(args: Main.Args):
  val cwd: Path = args.cwd
  val (config, configFiles) = Config.load(cwd, args.config)
  val tui = Tui(Paths.get(System.getProperty("user.home"), ".atc", "history"))

  // ── models ────────────────────────────────────────────────────────

  /** Every model of every configured provider, resolved. */
  val catalog: ModelCatalog = ModelCatalog.from(config)
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
    Policy(App.fileRules(config, cwd), config.commands, config.hosts, prompter, config.denyCommands, config.denyHosts)
  policy.mode = args.mode.orElse(config.mode.map(Mode.parse)).getOrElse(Mode.Full)

  // ── host (the sandbox API implementation) and its ports ───────────

  val output = new HostOutput:
    def print(agentText: String, userText: String): Unit =
      session.foreach(_.printStream.print(agentText)) // into the tool result, in order with REPL output
      tui.agentPrint(agentText, userText)
  val llm = new HostLlm:
    def chat(message: String): String = agent.model.simple(None, message)
    def chatClassified(message: String): String =
      agent.classifiedModel.getOrElse(throw RuntimeException(
        "No classified model configured: set \"classifiedModel\" in the config to a model that may see classified data."
      ))
        .simple(Some("You are a trusted assistant handling confidential data. Answer directly and concisely."), message)
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

  private def newSession(): ReplSession =
    tui.status(s"starting sandbox (${policy.mode.label} mode)")
    try ReplSession(SandboxConfig(config.safeMode, policy.mode, config.executionTimeoutMs), host).init()
    finally tui.endTurn()

  /** Replace the sandbox session (after `/reset` or a mode switch); the conversation is kept.
    * `reason` is passed to the agent so it knows its REPL definitions are gone. */
  private def restartSession(reason: String): Boolean =
    session.foreach(_.close())
    session = None
    try
      session = Some(newSession())
      agent.noteSandboxRestarted(reason)
      true
    catch
      case e: Exception =>
        tui.error(s"could not restart the sandbox: ${e.getMessage}")
        Debug.trace(e)
        false

  def run(): Int =
    try
      args.prompt match
        case Some(p) =>
          session = Some(newSession())
          runTurn(p)
        case None =>
          banner()
          session = Some(newSession())
          interactive()
      0
    finally
      session.foreach(_.close())
      tui.close()

  private def banner(): Unit =
    def describe(m: ChatModel): String = s"${m.ref} — ${m.modelId}" + (if m.webSearch then " (web search)" else "")
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
      "Type a request · /help commands · Shift-Tab or /mode cycle mode · Ctrl-C interrupt · Ctrl-O expand/collapse · Ctrl-D quit",
    )

  private def runTurn(input: String): Unit =
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

  private def interactive(): Unit =
    var running = true
    while running do
      tui.readLine(prompt) match
        case None =>
          Debug.log("input closed, exiting")
          running = false
        case Some(line) if line.trim.isEmpty => ()
        case Some(line) if line.trim.startsWith("/") => running = command(line.trim)
        case Some(line) => runTurn(line)

  /** The input prompt names the mode unless it is the full one. */
  private def prompt: String = policy.mode match
    case Mode.Full => "> "
    case m => s"${m.label} > "

  // ── slash commands ────────────────────────────────────────────────

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
      case "/help" | "/?" => tui.println(helpText)
      case "/models" => showModels()
      case "/model" => switchModel(arg)
      case "/classifiedmodel" | "/classified" => switchClassifiedModel(arg)
      case "/perms" | "/permissions" => tui.println(policy.summary)
      case "/mode" => switchMode(arg)
      case "/config" =>
        tui.println(s"config files: ${if configFiles.isEmpty then "(none)" else configFiles.mkString(", ")}")
        tui.println(
          s"safeMode=${config.safeMode} executionTimeoutMs=${config.executionTimeoutMs.getOrElse("none")} maxToolCalls=${config.maxToolCalls} respectGitignore=${config.respectGitignore}"
        )
        tui.println(s"open permission scopes: ${policy.openScopeCount}")
      case "/interface" | "/api" => tui.println(Prompts.interfaceSource)
      case "/reset" =>
        if restartSession("you asked for /reset") then tui.success("sandbox restarted")
      case "/clear" =>
        agent.clear()
        tui.success("conversation cleared")
      case "/todos" | "/todo" => tui.showTodosNow(host.currentTodos)
      case "/cost" | "/usage" =>
        val u = agent.usage
        tui.println(
          s"tokens: input=${u.input} (cached ${u.cacheRead}) output=${u.output}; tool calls: ${agent.toolCalls}"
        )
      case other => tui.error(s"unknown command $other (try /help)")

  /** One line per configured model, marked with the role it currently plays. */
  private def modelRow(spec: ModelSpec): String =
    val marks = List(
      Option.when(agent.model.ref == spec.ref)("agent"),
      Option.when(agent.classifiedModel.exists(_.ref == spec.ref))("classified"),
    ).flatten
    val search = if spec.settings.webSearch then " web-search" else ""
    val role = if marks.isEmpty then "" else s"  [${marks.mkString(", ")}]"
    f"${catalog.label(spec)}%-14s ${spec.provider}%-12s ${spec.api}%-17s ${spec.modelId}%-26s$search$role"

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
    setModel(arg, "model", agent.model) { m =>
      agent.model = m
      tui.success(s"model -> ${m.ref} (${m.modelId})")
    }

  /** `/classifiedmodel`: the model that may see classified data. `off` unsets it. */
  private def switchClassifiedModel(arg: String): Unit =
    if arg.trim.toLowerCase == "off" || arg.trim.toLowerCase == "none" then
      agent.classifiedModel = None
      tui.success("classified model -> (none): classified data is no longer sent to any model")
    else
      setModel(arg, "classified model", agent.classifiedModel.getOrElse(agent.model)) { m =>
        agent.classifiedModel = Some(m)
        tui.success(s"classified model -> ${m.ref} (${m.modelId})")
      }

  /** Shared by the two switches: an argument names a model, no argument opens
    * the picker; the current one is reported when nothing is chosen. */
  private def setModel(arg: String, what: String, current: ChatModel)(use: ChatModel => Unit): Unit =
    if arg.nonEmpty then
      try use(modelFor(arg))
      catch case e: IllegalArgumentException => tui.error(e.getMessage)
    else
      pickModel(s"Choose the $what") match
        case Some(spec) => use(modelFor(spec))
        case None => tui.info(s"$what: ${current.ref} (${current.providerKey} ${current.modelId})")

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
  /** A path for display: under `~` when inside the home directory. */
  def pretty(p: Path): String =
    val home = Paths.get(System.getProperty("user.home"))
    if p == home then "~" else if p.startsWith(home) then "~/" + home.relativize(p) else p.toString

  /** Build the file rules from the config: explicit rules (default: the
    * working directory is writable) plus the built-in classified patterns. */
  def fileRules(cfg: Config, cwd: Path): List[FileRule] =
    val explicit = if cfg.files.isEmpty then List(FileRuleConfig(".", access = Some("write"))) else cfg.files
    val rules = explicit.map { r =>
      FileRule(PathPattern(r.path, cwd), r.access.map(Access.parse), r.classified, r.locked)
    }
    val classified =
      if cfg.defaultClassified then
        Config.DefaultClassifiedPatterns.map(p => FileRule(PathPattern(p, cwd), None, Some(true), builtin = true))
      else Nil
    rules ++ classified
