package atc

import atc.agent.{Agent, Prompts}
import atc.config.{Config, FileRuleConfig, ModelConfig}
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

  val modelConfigs: Map[String, ModelConfig] = if config.models.isEmpty then Config.DefaultModels else config.models
  private val modelCache = mutable.Map[String, ChatModel]()
  def modelFor(alias: String): ChatModel =
    modelCache.getOrElseUpdate(
      alias, {
        val mc = modelConfigs.getOrElse(
          alias,
          throw IllegalArgumentException(
            s"Unknown model alias '$alias'. Configured: ${modelConfigs.keys.toList.sorted.mkString(", ")}"
          )
        )
        ChatModel.create(alias, mc)
      }
    )
  val initialModel: ChatModel =
    modelFor(args.model.orElse(config.model).getOrElse(modelConfigs.keys.toList.sorted.head))
  val initialSafe: Option[ChatModel] = config.safeModel.map(modelFor)

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
  val policy = Policy(App.fileRules(config, cwd), config.commands, config.hosts, prompter)

  // ── host (the sandbox API implementation) and its ports ───────────

  val output = new HostOutput:
    def print(agentText: String, userText: String): Unit =
      session.foreach(_.printStream.print(agentText)) // into the tool result, in order with REPL output
      tui.agentPrint(agentText, userText)
  val llm = new HostLlm:
    def chat(message: String): String = agent.model.simple(None, message)
    def chatClassified(message: String): String =
      agent.safeModel.getOrElse(throw RuntimeException(
        "No safe model configured: set \"safeModel\" in the config to a model that may see classified data."
      ))
        .simple(Some("You are a trusted assistant handling confidential data. Answer directly and concisely."), message)
  val hostUi = new HostUi:
    def askUser(question: String, options: List[String], multiple: Boolean): Option[String] =
      whileUserDecides(tui.askUser(question, options, multiple))
    def showTodos(items: List[Todo]): Unit = tui.showTodos(items)
  val host = Host(policy, cwd, output, llm, hostUi)

  // ── agent ─────────────────────────────────────────────────────────

  val agent = Agent(config, cwd, policy, tui, initialModel, initialSafe, config.instructions)

  // ── running ───────────────────────────────────────────────────────

  private def newSession(): ReplSession =
    tui.status("starting sandbox")
    try ReplSession(SandboxConfig(config.safeMode, config.executionTimeoutMs), host).init()
    finally tui.endTurn()

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
    def describe(m: ChatModel): String = s"${m.alias} — ${m.modelId}" + (if m.webSearch then " (web search)" else "")
    tui.banner(
      s"atc ${Main.Version}",
      List(
        "model" -> describe(agent.model),
        "safe model" -> agent.safeModel.map(
          describe
        ).getOrElse("(none — set \"safeModel\" in the config to chat about classified data)"),
        "cwd" -> App.pretty(cwd),
        "config" -> (if configFiles.nonEmpty then configFiles.map(App.pretty).mkString(", ")
                     else "(none; built-in defaults — try `atc --init`)"),
        "sandbox" -> List(
          s"safe mode ${if config.safeMode then "on" else "off"}",
          config.executionTimeoutMs.map(ms => s"timeout ${ms / 1000} s").getOrElse("no timeout"),
          s"max ${config.maxToolCalls} tool calls/turn"
        ).mkString(" · "),
      ),
      "Type a request · /help commands · Ctrl-C interrupt · Ctrl-O expand/collapse · Ctrl-D quit",
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
      tui.readLine("> ") match
        case None =>
          Debug.log("input closed, exiting")
          running = false
        case Some(line) if line.trim.isEmpty => ()
        case Some(line) if line.trim.startsWith("/") => running = command(line.trim)
        case Some(line) => runTurn(line)

  // ── slash commands ────────────────────────────────────────────────

  private val helpText =
    """Commands:
      |  /help              this help
      |  /model [alias]     show or switch the agent model
      |  /models            list configured models
      |  /perms             show the effective permission policy
      |  /config            show config files and settings
      |  /interface         show the sandbox API reference
      |  /reset             restart the sandbox REPL (keeps the conversation)
      |  /clear             forget the conversation (keeps the REPL)
      |  /todos             show the agent's TODO list
      |  /cost              show token usage
      |  /quit              exit""".stripMargin

  /** Handle a slash command; returns false to quit. */
  private def command(line: String): Boolean =
    val parts = line.split("\\s+", 2)
    val cmd = parts(0).toLowerCase
    val arg = if parts.length > 1 then parts(1).trim else ""
    cmd match
      case "/quit" | "/exit" | "/q" => return false
      case "/help" | "/?" => tui.println(helpText)
      case "/models" => showModels()
      case "/model" => switchModel(arg)
      case "/perms" | "/permissions" => tui.println(policy.summary)
      case "/config" =>
        tui.println(s"config files: ${if configFiles.isEmpty then "(none)" else configFiles.mkString(", ")}")
        tui.println(
          s"safeMode=${config.safeMode} executionTimeoutMs=${config.executionTimeoutMs.getOrElse("none")} maxToolCalls=${config.maxToolCalls}"
        )
        tui.println(s"open permission scopes: ${policy.openScopeCount}")
      case "/interface" | "/api" => tui.println(Prompts.interfaceSource)
      case "/reset" =>
        session.foreach(_.close())
        session = None
        try
          session = Some(newSession())
          tui.success("sandbox restarted")
        catch
          case e: Exception =>
            tui.error(s"could not restart the sandbox: ${e.getMessage}")
            Debug.trace(e)
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
    true

  private def showModels(): Unit =
    modelConfigs.toList.sortBy(_._1).foreach { (alias, mc) =>
      val marks = List(
        if agent.model.alias == alias then Some("agent") else None,
        if agent.safeModel.exists(_.alias == alias) then Some("safe") else None
      ).flatten
      tui.println(f"  $alias%-12s ${mc.provider}%-18s ${mc.model}%-28s${if mc.webSearch then " web-search" else ""}${
          if marks.nonEmpty then s"  [${marks.mkString(", ")}]" else ""
        }")
    }

  private def switchModel(arg: String): Unit =
    if arg.isEmpty then tui.info(s"model: ${agent.model.alias} (${agent.model.providerKey} ${agent.model.modelId})")
    else
      try
        agent.model = modelFor(arg)
        tui.success(s"model -> ${agent.model.alias} (${agent.model.modelId})")
      catch case e: IllegalArgumentException => tui.error(e.getMessage)

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
    val defaults =
      if cfg.defaultClassified then
        Config.DefaultClassifiedPatterns.map(p => FileRule(PathPattern(p, cwd), None, Some(true), builtin = true))
      else Nil
    rules ++ defaults
