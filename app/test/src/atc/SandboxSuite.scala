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

  test("a classified file round-trips to the classified model without reaching the agent"):
    env.clearOutput()
    val r = assertOk(run("""val c = chat(readClassified("secrets/s.txt")); println(c); c"""))
    assert(env.classifiedChats.contains("secret"), env.classifiedChats.toString)
    assert(env.agentOut.toString.contains("Classified(***)"), env.agentOut.toString)
    assert(env.userOut.toString.contains("safe:secret"), env.userOut.toString)
    assert(!r.output.contains("secret"), r.output)

  test("exfiltration drill: the agent never sees the classified content, on any channel"):
    // One classified file pushed at every outward channel the sandbox offers:
    // masked sinks, the REPL echo, a failed computation, declassifying writes and
    // command redirections. The agent-visible streams must never carry the value.
    env.file("secrets/drill.txt", "DRILL-SECRET-42")
    env.clearOutput()
    // masked channels: println and the REPL echo show Classified(***)
    val printed = assertOk(run("""println(readClassified("secrets/drill.txt"))"""))
    assert(printed.output.contains("Classified(***)"), printed.output)
    val echoed = assertOk(run("""val echoed = readClassified("secrets/drill.txt"); ()"""))
    assert(!echoed.output.contains("DRILL-SECRET-42"), echoed.output)
    // a failed computation over the secret prints a sanitized note, not the error
    assertOk(run("""println(readClassified("secrets/drill.txt").map(s => throw RuntimeException(s)))"""))
    // refused channels: declassifying write, classified redirection in and out
    assertFails(run("""writeClassified("leak.txt", readClassified("secrets/drill.txt"))"""), "declassify")
    assertFails(run("""exec("echo x < secrets/drill.txt")"""), "classified")
    assertFails(run("""exec("echo x > secrets/out.txt")"""), "classified")
    assert(!env.existsOnDisk("leak.txt"))
    assert(!env.existsOnDisk("secrets/out.txt"))
    // the agent saw none of it anywhere; the user saw it (the println above)
    assert(!env.agentOut.toString.contains("DRILL-SECRET-42"), env.agentOut.toString)
    assert(env.userOut.toString.contains("DRILL-SECRET-42"), env.userOut.toString)

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

  test("the sandbox loader hides *.class resources (only REPL classes get interrupt-instrumented)"):
    // With interrupt instrumentation on, the REPL loader would otherwise read the
    // bytecode of shared classes through its parent and re-define an instrumented
    // copy — including atc.lib.Interface, losing the installed host.
    val loader = Sandbox.newLoader()
    assertEquals(loader.getResource("atc/lib/Interface.class"), null)
    assertEquals(loader.getResource("scala/collection/immutable/List.class"), null)

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

  test("a catch-all in a LATER arm is rejected too (it catches fatal errors)"):
    // The catch-all used to slip through as a non-first arm. A recursion needs no
    // forbidden type name to raise a StackOverflowError, so this WAS a live
    // per-bit oracle over classified data.
    val attack =
      """val c = readClassified("secrets/s.txt")
        |def bit(ch: Char): Boolean =
        |  try { c.map(s => if s.contains(ch) then { def r(): Int = r() + 1; r() } else 0); false }
        |  catch
        |    case _: RuntimeException => false
        |    case _ => true
        |println(bit('e'))""".stripMargin
    assertFails(run(attack), "catch-all")

  test("a catch-all behind two nested brace levels is rejected too"):
    assertFails(
      run("""try println(1) catch { case _: RuntimeException => { val x = { 1 }; x }; case _ => 2 }"""),
      "catch-all"
    )

  test("a type alias cannot smuggle a fatal catch past the validator"):
    assertFails(run("type T = Throwable\ntry println(1) catch case _: T => 2"), "catch-fatal-alias")
    assertFails(run("type E = StackOverflowError\ntry println(1) catch case _: E => 2"), "catch-fatal-alias")
