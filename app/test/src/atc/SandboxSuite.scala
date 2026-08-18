package atc

import atc.host.*
import atc.perms.*
import atc.sandbox.*
import java.nio.file.Files

/** Sandbox behaviour with the real host and a permissive, scripted policy. */
class SandboxSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  val root = Files.createTempDirectory("atc-sandbox").nn.toRealPath().nn
  Files.createDirectories(root.resolve("secrets"))
  Files.writeString(root.resolve("secrets/s.txt"), "secret")

  val requests = collection.mutable.ListBuffer[PermissionRequest]()
  val prompter: PermissionPrompter = r => { requests += r; Decision.AllowOnce }
  val policy = Policy(
    List(
      FileRule(PathPattern(".", root), Some(Access.Write), None),
      FileRule(PathPattern("secrets", root), None, Some(true)),
    ),
    List("echo"),
    List("example.com"),
    prompter
  )

  val agentOut = StringBuilder()
  val userOut = StringBuilder()
  var session: ReplSession = scala.compiletime.uninitialized
  val output = new HostOutput:
    def print(agentText: String, userText: String): Unit =
      agentOut.append(agentText); session.printStream.print(agentText)
      userOut.append(userText)
  val safeChats = collection.mutable.ListBuffer[String]()
  val llm = new HostLlm:
    def chat(m: String) = s"echo:$m"
    def chatClassified(m: String) = { safeChats += m; s"safe:$m" }
  val hostUi = new HostUi:
    def askUser(question: String, options: List[String], multiple: Boolean): Option[String] = Some("yes")
    def showTodos(items: List[atc.lib.Todo]): Unit = ()
  val host = Host(policy, root, output, llm, hostUi)

  override def beforeAll(): Unit =
    session = ReplSession(SandboxConfig(safeMode = true, executionTimeoutMs = Some(60000)), host).init()

  private def run(code: String): ExecutionResult = session.run(code)

  private def assertCompileError(code: String, pattern: String)(using loc: munit.Location): Unit =
    val r = run(code)
    assert(!r.success, s"expected failure, got success: ${r.output}")
    val text = (r.output + r.error.getOrElse("")).toLowerCase
    assert(text.contains(pattern.toLowerCase), s"expected '$pattern' in:\n${r.output}\n${r.error}")

  test("preamble loads and println works"):
    val r = run("""println("hello")""")
    assert(r.success, r.error.toString + r.output)
    assert(agentOut.toString.contains("hello"))

  test("file write/read through default fs"):
    val r = run("""write("a.txt", "content"); read("a.txt")""")
    assert(r.success, r.error.toString + r.output)
    assert(r.output.contains("content"), r.output)

  test("requestFiles opens a scope, prompts, and closes it"):
    val other = Files.createTempDirectory("atc-sandbox-out").nn.toRealPath().nn
    Files.writeString(other.resolve("b.txt"), "outside")
    val r = run(s"""requestFiles("$other", Access.Read, "test") { read("$other/b.txt") }""")
    assert(r.success, r.error.toString + r.output)
    assert(r.output.contains("outside"), r.output)
    assert(requests.exists { case f: FileRequest => f.reason == "test"; case _ => false }, requests.toString)
    assertEquals(policy.openScopeCount, 0)
    val denied = run(s"""read("$other/b.txt")""")
    assert(!denied.success)

  test("capability cannot leak out of requestFiles"):
    assertCompileError("""val leaked = requestFiles("/tmp") { access("/tmp") }""", "leak")

  test("closure capturing FileSystem cannot leak"):
    assertCompileError("""val fn = requestFiles("/tmp") { () => read("/tmp/x") }""", "leak")

  test("classified map cannot println"):
    assertCompileError("""classify("s").map(s => { println(s); s })""", "capture")

  test("classified map cannot use file system"):
    assertCompileError("""classify("s").map(s => { write("out.txt", s); s })""", "capture")

  test("classified map cannot take root capability"):
    assertCompileError("""classify("s").map(s => { println(s)(using atc.lib.Interface.takeRootIO()); s })""", "")

  test("classified map cannot chat with the normal model"):
    assertCompileError("""classify("s").map(s => chat(s))""", "")

  test("classified chat goes to safe model and stays classified"):
    val r = run("""val c = chat(readClassified("secrets/s.txt")); println(c); c""")
    assert(r.success, r.error.toString + r.output)
    assert(safeChats.contains("secret"))
    assert(agentOut.toString.contains("Classified(***)"))
    assert(userOut.toString.contains("safe:secret"))
    assert(!r.output.contains("secret"), r.output)

  test("cannot construct capabilities or reach internals"):
    assertCompileError("""new atc.host.FileSystemImpl(0, null)""", "")
    assertCompileError("""atc.lib.Interface.current""", "")
    assertCompileError("""atc.lib.Interface.takeRootIO()""", "")

  test("host classes are invisible to the sandbox loader"):
    val loader = Sandbox.newLoader()
    intercept[ClassNotFoundException](loader.loadClass("atc.host.Host"))
    intercept[ClassNotFoundException](loader.loadClass("dotty.tools.repl.ReplDriver"))
    assert(loader.loadClass("atc.lib.Interface") eq classOf[atc.lib.Interface])
    assert(loader.loadClass("scala.collection.immutable.List") eq classOf[List[?]])

  test("validator blocks java.io"):
    val r = run("""java.io.File("/etc/passwd").exists""")
    assert(!r.success)
    assert(r.error.exists(_.contains("file-io-java")))

  test("state persists"):
    assert(run("val persisted = 41").success)
    val r = run("persisted + 1")
    assert(r.output.contains("42"), r.output)

  test("uncaught exception is reported as failure"):
    val r = run("""throw new RuntimeException("boom")""")
    assert(!r.success)
    assert(r.output.contains("boom"))
