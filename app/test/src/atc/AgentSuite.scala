package atc

import atc.agent.*
import atc.config.Config
import atc.host.*
import atc.llm.*
import atc.perms.*
import atc.sandbox.*
import java.nio.file.{Files, Path}

/** End to end: echo model → agent loop → sandbox REPL → real host → policy. */
class AgentSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private val echoCommand = ProcessFixture.command("echo")
  private val echoPattern = ProcessFixture.pattern("echo")

  val root: Path = Files.createTempDirectory("atc-agent").toRealPath()
  Files.writeString(root.resolve("hello.txt"), "hello world")
  Files.createDirectories(root.resolve("secrets"))
  Files.writeString(root.resolve("secrets/token.txt"), "TOKEN-123")

  var decisions: List[Decision] = Nil
  val asked = collection.mutable.ListBuffer[PermissionRequest]()
  val prompter: PermissionPrompter = r =>
    asked += r
    decisions match
      case d :: rest => decisions = rest; d
      case Nil => Decision.Deny

  val policy = Policy(
    List(
      FileRule(PathPattern(".", root), Some(Access.Write), None),
      FileRule(PathPattern("secrets", root), None, Some(true)),
    ),
    List(echoPattern),
    Nil,
    prompter
  )

  val userSeen = StringBuilder()
  val agentSeen = StringBuilder()
  var session: ReplSession = scala.compiletime.uninitialized
  val output = new HostOutput:
    def print(agentText: String, userText: String): Unit =
      agentSeen.append(agentText); session.printStream.print(agentText)
      userSeen.append(if agentText == userText then userText else s"[C]$userText")
  val llm = new HostLlm:
    def chat(m: String) = s"normal:$m"
    def classifiedChat(m: String) = s"safe:$m"
  var answers: List[Option[String]] = Nil
  val questions = collection.mutable.ListBuffer[(String, List[String], Boolean)]()
  var shownTodos: List[atc.lib.Todo] = Nil
  val hostUi = new HostUi:
    def askUser(question: String, options: List[String], multiple: Boolean): Option[String] =
      questions += ((question, options, multiple))
      answers match
        case a :: rest => answers = rest; a
        case Nil => None
    def showTodos(items: List[atc.lib.Todo]): Unit = shownTodos = items
  val host = Host(policy, root, output, llm, hostUi)

  val toolLog = collection.mutable.ListBuffer[(String, ExecutionResult)]()
  class TestUI extends AgentUI:
    var lastCode = ""
    def assistantDelta(text: String): Unit = ()
    def assistantNote(text: String): Unit = ()
    def assistantEnd(): Unit = ()
    def toolStart(code: String): Unit = lastCode = code
    def toolEnd(result: ExecutionResult, millis: Long): Unit = toolLog += ((lastCode, result))
    def status(text: String): Unit = ()
    def warn(text: String): Unit = ()
    def thinkingDelta(text: String): Unit = ()
  val ui = TestUI()

  lazy val agent = Agent(
    Config(),
    AgentEnvironment.current(root),
    policy,
    ui,
    EchoModel("echo"),
    Some(EchoModel("safe")),
    None,
  )

  override def beforeAll(): Unit =
    session = ReplSession(SandboxConfig(safeMode = true, executionTimeoutMs = Some(60000)), host).init()

  private def lastResult: ExecutionResult = toolLog.last._2
  private def lastToolText: String = agent.history.collect { case Msg.ToolResults(rs) =>
    rs.map(_.output).mkString
  }.last

  test("plain chat"):
    agent.turn(session, "hi", () => false)
    assertEquals(agent.history.last, Msg.Assistant("echo: hi", Nil, None))

  test("tool call: read a file and print"):
    agent.turn(session, """run: println(read("hello.txt").toUpperCase)""", () => false)
    assert(lastResult.success, lastResult.toString)
    assert(agentSeen.toString.contains("HELLO WORLD"))
    assert(lastToolText.contains("HELLO WORLD"), lastToolText)

  test("compile error is reported to the model, not executed"):
    agent.turn(session, """run: val leaked = requestFiles("/tmp") { access("/tmp") }""", () => false)
    assert(!lastResult.success)
    assert(lastToolText.toLowerCase.contains("leak"), lastToolText)

  test("classified: model sees mask, user sees content, classified model used"):
    agent.turn(
      session,
      """run: val c = readClassified("secrets/token.txt"); println(c); println(classifiedChat(c.map(_ + "?")))""",
      () => false
    )
    assert(lastResult.success, lastResult.toString)
    assert(agentSeen.toString.contains("Classified(***)"))
    assert(!lastToolText.contains("TOKEN-123"), lastToolText)
    assert(userSeen.toString.contains("[C]TOKEN-123"))
    assert(userSeen.toString.contains("safe:TOKEN-123?"))

  test("denied plain read of classified path explains the alternative"):
    agent.turn(session, """run: read("secrets/token.txt")""", () => false)
    assert(!lastResult.success)
    assert(lastToolText.contains("readClassified"), lastToolText)

  test("permission request flow: denied then granted"):
    val outside = Files.createTempDirectory("atc-agent-out").toRealPath()
    Files.writeString(outside.resolve("x.txt"), "outside!")
    decisions = List(Decision.Deny)
    val outsideCode = ScalaSource.stringLiteral(outside.toString)
    val fileCode = ScalaSource.stringLiteral(outside.resolve("x.txt").toString)
    agent.turn(
      session,
      s"""run: requestFiles($outsideCode, Access.Read, "need it") { read($fileCode) }""",
      () => false
    )
    assert(!lastResult.success)
    assert(lastToolText.contains("denied"), lastToolText)
    decisions = List(Decision.AllowOnce)
    agent.turn(
      session,
      s"""run: requestFiles($outsideCode, Access.Read, "need it") { read($fileCode) }""",
      () => false
    )
    assert(lastResult.success, lastResult.toString)
    assert(lastToolText.contains("outside!"), lastToolText)
    assertEquals(asked.size, 2)
    assertEquals(asked.last.asInstanceOf[FileRequest].reason, "need it")
    // scope closed after the block: no lingering access
    agent.turn(session, s"run: read($fileCode)", () => false)
    assert(!lastResult.success)

  test("exec through policy"):
    agent.turn(
      session,
      s"run: println(exec(${ujson.write(echoCommand)}, List(\"from-exec\")).stdout.trim)",
      () => false,
    )
    assert(lastResult.success, lastResult.toString)
    assert(lastToolText.contains("from-exec"))
    agent.turn(session, """run: exec("ls")""", () => false)
    assert(!lastResult.success)
    assert(lastToolText.contains("requestExec"), lastToolText)

  test("state persists across tool calls"):
    agent.turn(session, "run: def twice(s: String) = s + s", () => false)
    agent.turn(session, """run: println(twice("ab"))""", () => false)
    assert(lastToolText.contains("abab"), lastToolText)

  test("ask() reaches the user and returns the answer"):
    answers = List(Some("Blue"))
    agent.turn(session, """run: println(ask("Which colour?", List("Red", "Blue")))""", () => false)
    assertEquals(questions.last, ("Which colour?", List("Red", "Blue"), false))
    assert(lastToolText.contains("Some(Blue)"), lastToolText)
    agent.turn(session, """run: println(ask("Anything else?"))""", () => false)
    assert(lastToolText.contains("None"), lastToolText)

  test("ask() is an effect: unusable inside Classified.map"):
    agent.turn(session, """run: classify("x").map(s => ask(s))""", () => false)
    assert(!lastResult.success)

  test("setTodos/markTodo update the list shown to the user"):
    agent.turn(
      session,
      """run: setTodos(List(Todo("write build file", TodoStatus.Done), Todo("add tests", TodoStatus.InProgress), Todo("run tests")))""",
      () => false
    )
    assert(lastResult.success, lastResult.toString)
    assertEquals(
      shownTodos.map(t => (t.text, t.status.toString)),
      List(("write build file", "Done"), ("add tests", "InProgress"), ("run tests", "Pending"))
    )
    agent.turn(
      session,
      """run: markTodo("add tests", TodoStatus.Done); println(todos.count(_.status == TodoStatus.Done))""",
      () => false
    )
    assert(lastToolText.contains("2"), lastToolText)
    assertEquals(host.currentTodos.count(_.status == atc.lib.TodoStatus.Done), 2)

  test("cancellation between tool calls keeps history consistent"):
    var cancelled = false
    agent.turn(session, "run: 1 + 1", () => { val c = cancelled; cancelled = true; c })
    // history must end with an assistant message (no dangling tool results)
    assert(agent.history.last.isInstanceOf[Msg.Assistant])
