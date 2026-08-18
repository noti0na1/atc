package atc

import atc.perms.*
import atc.sandbox.*

/** The sandbox itself: that a session starts, that agent code reaches the real
  * host through it, that definitions persist, that the class loader hides the
  * application, and that the last-resort safety nets (validator, fatal
  * throwables, uncaught exceptions) behave.
  *
  * What the *capabilities* allow lives in [[CapabilitySuite]], what each mode
  * hands out in [[ModeSuite]], and the REPL's own mechanics (timeouts,
  * interrupts, REPL commands, safe mode) in [[ReplSessionSuite]]. */
class SandboxSuite extends munit.FunSuite, ReplAssertions:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private val env = TestEnv(TestEnv.withSecrets, commands = List("echo"), prefix = "atc-sandbox")
  private lazy val session: ReplSession = env.newSession()
  private def run(code: String): ExecutionResult = { env.activate(); session.run(code) }

  override def beforeAll(): Unit =
    env.file("secrets/s.txt", "secret")
    session

  // ── The session works end to end ────────────────────────────────

  test("the preamble loads and the agent's println reaches the host"):
    env.clearOutput()
    assertOk(run("""println("hello")"""))
    assertEquals(env.agentOut.toString, "hello\n")

  test("file effects go through the real host"):
    assertOk(run("""write("a.txt", "content")"""))
    assertEquals(env.contents("a.txt"), "content")
    assert(assertOk(run("""read("a.txt")""")).output.contains("content"))

  test("definitions persist across snippets"):
    assertOk(run("val persisted = 41"))
    assert(assertOk(run("persisted + 1")).output.contains("42"))

  test("a classified file round-trips to the safe model without reaching the agent"):
    env.clearOutput()
    val r = assertOk(run("""val c = chat(readClassified("secrets/s.txt")); println(c); c"""))
    assert(env.safeChats.contains("secret"), env.safeChats.toString)
    assert(env.agentOut.toString.contains("Classified(***)"), env.agentOut.toString)
    assert(env.userOut.toString.contains("safe:secret"), env.userOut.toString)
    assert(!r.output.contains("secret"), r.output)

  test("the preamble gives each capability its own REPL round"):
    // Separate rounds put each given in its own line wrapper, which is what lets
    // a pure `Classified.map` capture the read-only `fs` without dragging in the
    // always-full `user`/`io` (see ModeSuite's read-in-map test).
    for m <- Mode.values do
      val chunks = ReplSession.preambleChunks(m)
      assert(chunks.size >= 3, s"${m.label}: expected a base chunk plus one per given, got ${chunks.size}")
      assert(chunks.head.contains("object api"), s"${m.label}: the first chunk should define `api`")
      for g <- chunks.tail do
        assertEquals(g.linesIterator.count(_.contains("given")), 1, s"${m.label}: one given per round, got `$g`")

  // ── Class-loader isolation ──────────────────────────────────────

  test("the application is invisible to the sandbox loader, the API is shared"):
    val loader = Sandbox.newLoader()
    intercept[ClassNotFoundException](loader.loadClass("atc.host.Host"))
    intercept[ClassNotFoundException](loader.loadClass("dotty.tools.repl.ReplDriver"))
    // Shared, so the host can implement the interface the agent programs against.
    assert(loader.loadClass("atc.lib.Interface") eq classOf[atc.lib.Interface])
    assert(loader.loadClass("scala.collection.immutable.List") eq classOf[List[?]])

  // ── Safety nets ─────────────────────────────────────────────────

  test("the validator rejects a snippet before it is compiled or run"):
    assertFails(run("""java.io.File("/etc/passwd").exists"""), "file-io-java")

  test("an uncaught exception is reported as a failure, not a result"):
    val r = assertFails(run("""throw new RuntimeException("boom")"""))
    assert(r.output.contains("boom"), r.output)

  test("a fatal-throwable oracle over classified data is rejected before it runs"):
    // A pure callback that throws only when a predicate over the secret holds is a
    // per-bit oracle *if* the fatal throwable it raises can be caught. `Try` traps
    // only NonFatal, so the attack needs a fatal throwable and a catch. The
    // validator rejects both, so the code never runs.
    val attack =
      """val c = readClassified("secrets/s.txt")
        |def bit(i: Int, mid: Char): Boolean =
        |  try { c.map(s => if i < s.length && s(i) < mid then throw new InterruptedException() else 0); false }
        |  catch case _: InterruptedException => true
        |println(bit(0, 'm'))""".stripMargin
    assertFails(run(attack), "throwable-interrupted")

  test("a StackOverflow oracle is rejected too: the fatal error cannot be caught"):
    val attack =
      """val c = readClassified("secrets/s.txt")
        |def bit(i: Int, mid: Char): Boolean =
        |  try { c.map(s => if s(i) < mid then { def rec(n: Int): Int = rec(n + 1) + 1; rec(0) } else 0); false }
        |  catch case _: StackOverflowError => true
        |println(bit(0, 'm'))""".stripMargin
    assertFails(run(attack), "catch-fatal")

  test("the ThreadDeath stop signal cannot be swallowed by a catch-all"):
    // Interrupts and timeouts stop REPL loops by raising ThreadDeath at back-edges;
    // a loop that caught everything would defeat them.
    assertFails(run("""while true do try { val x = 1 } catch case _ => ()"""), "catch-all")
