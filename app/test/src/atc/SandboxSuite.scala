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

  test("classified map fatal-exception oracle is rejected before it runs"):
    // A pure callback that throws only when a predicate over the secret holds would be a
    // per-bit oracle *if* the fatal throwable it raises could be caught. `Try` traps only
    // NonFatal, so the attack needs a fatal throwable (here InterruptedException) and a
    // catch to observe it — and the validator rejects both, so the code never runs.
    val attack =
      """val c = readClassified("secrets/s.txt")
        |def bit(i: Int, mid: Char): Boolean =
        |  try { c.map(s => if i < s.length && s(i) < mid then throw new InterruptedException() else 0); false }
        |  catch case _: InterruptedException => true
        |println(bit(0, 'm'))""".stripMargin
    val r = run(attack)
    assert(!r.success, s"expected validation to reject the oracle, got: ${r.output}")
    assert(r.error.exists(_.contains("throwable-interrupted")), r.error.toString)

  test("classified map StackOverflow oracle is rejected: cannot catch the fatal error"):
    // No explicit throw — a runaway recursion raises StackOverflowError — but observing it
    // still requires catching a fatal Error, which the validator forbids.
    val attack =
      """val c = readClassified("secrets/s.txt")
        |def bit(i: Int, mid: Char): Boolean =
        |  try { c.map(s => if s(i) < mid then { def rec(n: Int): Int = rec(n + 1) + 1; rec(0) } else 0); false }
        |  catch case _: StackOverflowError => true
        |println(bit(0, 'm'))""".stripMargin
    val r = run(attack)
    assert(!r.success, s"expected validation to reject the oracle, got: ${r.output}")
    assert(r.error.exists(_.contains("catch-fatal")), r.error.toString)

  test("agent code cannot swallow the ThreadDeath stop signal with a catch-all"):
    // A loop that catches everything could otherwise defeat the interrupt/timeout, which
    // stops REPL loops by raising ThreadDeath at back-edges. The catch-all is rejected.
    val r = run("""while true do try { val x = 1 } catch case _ => ()""")
    assert(!r.success, s"expected validation to reject the catch-all, got: ${r.output}")
    assert(r.error.exists(_.contains("catch-all")), r.error.toString)

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
