package atc

import atc.perms.Decision
import atc.sandbox.*

import java.nio.file.Files

/** The sandbox REPL executor: language coverage, error reporting, REPL
  * command allow-list, validator integration, timeouts, output capture,
  * safe-mode on/off and session isolation (migrated from TACIT's
  * ScalaExecutorSuite / SessionManagerSuite / CodeValidatorEvasionSuite e2e). */
class ReplSessionSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  // Each session gets its own host so that per-session routing can be checked.
  val envSafe = TestEnv(prefix = "atc-repl-safe")
  val envNoSafe = TestEnv(prefix = "atc-repl-nosafe")
  val envQuick = TestEnv(prefix = "atc-repl-quick")
  lazy val safe: ReplSession = envSafe.newSession()
  lazy val nosafe: ReplSession = envNoSafe.newSession(safeMode = false)
  lazy val quick: ReplSession = envQuick.newSession(timeoutMs = Some(1000L))

  private def run(code: String): ExecutionResult = safe.run(code)
  private def assertOk(r: ExecutionResult)(using munit.Location): ExecutionResult =
    assert(r.success, s"expected success, got: ${r.error.getOrElse("")}\n${r.output}")
    r
  private def assertFails(r: ExecutionResult, pattern: String = "")(using munit.Location): ExecutionResult =
    assert(!r.success, s"expected failure, got success with: ${r.output}")
    val text = (r.output + "\n" + r.error.getOrElse("")).toLowerCase
    assert(text.contains(pattern.toLowerCase), s"expected '$pattern' in:\n${r.output}\n${r.error}")
    r

  // ── Basic execution ─────────────────────────────────────────────

  test("simple expression") { assert(assertOk(run("1 + 1")).output.contains("2")) }
  test("println goes through the host and into the tool output"):
    envSafe.clearOutput()
    val r = assertOk(run("""println("Hello, World!")"""))
    assert(r.output.contains("Hello, World!"), r.output)
    assertEquals(envSafe.agentOut.toString, "Hello, World!\n")
    assertEquals(envSafe.userOut.toString, "Hello, World!\n")
  test("val definition is echoed"):
    val r = assertOk(run("val fortyTwo = 42"))
    assert(r.output.contains("val fortyTwo: Int = 42"), r.output)
  test("function definition and call"):
    assert(assertOk(run("def add(a: Int, b: Int): Int = a + b\nadd(2, 3)")).output.contains("5"))
  test("List map") { assert(assertOk(run("List(1, 2, 3).map(_ * 2)")).output.contains("List(2, 4, 6)")) }
  test("foreach println on a List of String"):
    val r = assertOk(run("""List("hello", "world").foreach(println)"""))
    assert(r.output.contains("hello") && r.output.contains("world"), r.output)
  test("Map") {
    assert(assertOk(run("""Map("a" -> 1, "b" -> 2).values.toList.sorted""")).output.contains("List(1, 2)"))
  }
  test("java.time is available"):
    assert(assertOk(run("import java.time.LocalDate\nLocalDate.of(2025, 1, 1).getYear")).output.contains("2025"))
  test("scala.util.Try is available"):
    assert(assertOk(run("import scala.util.Try\nTry(\"123\".toInt).isSuccess")).output.contains("true"))
  test("case class with method"):
    val r = assertOk(run("""
      case class Point(x: Int, y: Int):
        def distTo(other: Point): Double =
          math.sqrt(math.pow(x - other.x, 2) + math.pow(y - other.y, 2))
      Point(0, 0).distTo(Point(3, 4))
    """))
    assert(r.output.contains("5.0"), r.output)
  test("pattern matching"):
    val r = assertOk(run("val any: Any = 42\nany match\n  case i: Int => s\"int: $i\"\n  case _ => \"other\""))
    assert(r.output.contains("int: 42"), r.output)
  test("higher-order functions"):
    assert(assertOk(run("List(1, 2, 3, 4, 5).filter(_ % 2 == 0).map(_ * 10)")).output.contains("List(20, 40)"))
  test("for comprehension"):
    assert(assertOk(
      run("for\n  x <- List(1, 2, 3)\n  y <- List(10, 20)\nyield x * y")
    ).output.contains("List(10, 20, 20, 40, 30, 60)"))
  test("string interpolation and chaining"):
    val r = assertOk(run("val n = 3; val s = s\"n=${n * 2}\"; List(1,2,3)\n  .map(_ * 2)\n  .filter(_ > 2)\n  .sum"))
    assert(r.output.contains("n=6") && r.output.contains("10"), r.output)
  test("object, enum and lazy val definitions"):
    assertOk(run("object Counter { def x = 1 }\nenum Color { case Red, Green }\nlazy val lz = Color.Red"))
    assert(assertOk(run("Counter.x + 41")).output.contains("42"))
    assert(assertOk(run("lz.toString")).output.contains("Red"))
  test("large output completes"):
    val r = assertOk(run("(1 to 500).toList"))
    assert(r.output.replace(" ", "").replace("\n", "").contains("List(1,2,3"))
  test("a warning does not fail the evaluation"):
    val r = assertOk(run("Some(1) match { case Some(v) => v; case None => 0 }"))
    assert(r.output.toLowerCase.contains("warning"), r.output)

  // ── State ───────────────────────────────────────────────────────

  test("state persists across runs"):
    assertOk(run("val persisted = 41"))
    assert(assertOk(run("persisted + 1")).output.contains("42"))
  test("imports persist across runs"):
    assertOk(run("import java.time.Duration"))
    assert(assertOk(run("Duration.ofSeconds(90).toMinutes")).output.contains("1"))
  test("a failed run leaves the previous state intact"):
    assertOk(run("val keep = \"kept\""))
    assertFails(run("val keep2: Int = \"nope\""))
    assert(assertOk(run("keep")).output.contains("kept"))
  test("sessions are isolated from each other and keep their own host"):
    assertOk(safe.run("val shared = 1"))
    assertOk(nosafe.run("val shared = 2"))
    assert(assertOk(safe.run("shared")).output.contains("1"))
    assert(assertOk(nosafe.run("shared")).output.contains("2"))
    envSafe.clearOutput(); envNoSafe.clearOutput()
    assertOk(safe.run("""println("from-safe")"""))
    assertOk(nosafe.run("""println("from-nosafe")"""))
    assertEquals(envSafe.agentOut.toString, "from-safe\n")
    assertEquals(envNoSafe.agentOut.toString, "from-nosafe\n")

  // ── Errors ──────────────────────────────────────────────────────

  test("syntax error"):
    val r = assertFails(run("val x = def"), "syntax error")
    assert(r.error.exists(_.contains("Line 1")), r.error.toString)
  test("type error") { assertFails(run("""val x: Int = "hello""""), "type mismatch") }
  test("unknown identifier") { assertFails(run("nonexistentThing + 1"), "not found") }
  test("uncaught exception is reported as failure with the message"):
    val r = assertFails(run("""throw new RuntimeException("boom")"""), "boom")
    assert(r.output.contains("RuntimeException"))
  test("host frames are trimmed from traces but agent frames stay"):
    val r = assertFails(
      run("""def fail(): Int = throw new IllegalStateException("deep")
      |fail()""".stripMargin),
      "deep"
    )
    val rendered = r.render
    assert(!rendered.contains("at atc."), rendered)
    assert(!rendered.contains("at java."), rendered)
    assert(!rendered.contains("elided"), rendered)
    assert(rendered.contains("rs$line$"), rendered)
  test("empty and whitespace-only code do not crash"):
    assertOk(run(""))
    assertOk(run("   \n\n  "))
  test("comment-only code is fine") { assertOk(run("// nothing to see\n/* here */")) }

  // ── Validator integration ───────────────────────────────────────

  test("validator rejects java.io before compilation"):
    val r = assertFails(run("import java.io.File\nval f = new File(\"/tmp\")\nf.isDirectory"), "file-io-java")
    assert(r.error.exists(_.contains("Code validation failed")))
    assertEquals(r.output, "")
  test("validator rejects scala.io.Source") { assertFails(run("import scala.io.Source"), "file-io-scala") }
  test("validator allows a forbidden token in a string literal"):
    assert(assertOk(run("""println("java.io is just a string")""")).output.contains("java.io is just a string"))
  test("validator: interpolation is not executed"):
    val r = assertFails(run("""val home = s"${System.getProperty("user.home")}"; home"""), "sys-getprop")
    assert(!r.output.contains(System.getProperty("user.home")))

  // ── REPL command allow-list ─────────────────────────────────────

  private def assertCommandRejected(cmd: String)(using munit.Location): Unit =
    val r = run(cmd)
    assert(!r.success, s"'$cmd' should be rejected")
    assert(r.error.exists(_.contains("Only :type, :doc, and :imports")), s"'$cmd' → ${r.error}")
  test("rejects :quit") { assertCommandRejected(":quit") }
  test("rejects :q") { assertCommandRejected(":q") }
  test("rejects :settings") { assertCommandRejected(":settings") }
  test("rejects :dep") { assertCommandRejected(":dep com.example::lib:1.0") }
  test("rejects :sh") { assertCommandRejected(":sh echo hello") }
  test("rejects :load") { assertCommandRejected(":load /tmp/evil.scala") }
  test("rejects :reset") { assertCommandRejected(":reset") }
  test("rejects :help") { assertCommandRejected(":help") }
  test("rejects :jar") { assertCommandRejected(":jar /tmp/x.jar") }
  test(":type works"):
    assertOk(run("val typed = 42"))
    assert(assertOk(run(":type typed")).output.contains("Int"))
  test(":imports shows the preamble and safe mode"):
    val out = assertOk(run(":imports")).output
    assert(out.contains("import atc.lib.*"), out)
    assert(out.contains("import api.*"), out)
    assert(out.contains("import language.experimental.safe"), out)
  test(":doc is allowed") { assertOk(run(":doc List")) }

  // ── Safe mode ───────────────────────────────────────────────────

  test("safe mode: top-level var is rejected") { assertFails(run("var counter = 0"), "mutable variable") }
  test("safe mode: mutable collections are rejected"):
    assertFails(run("scala.collection.mutable.ArrayBuffer[String]()"), "safe code")
    assertFails(run("import scala.collection.mutable.ListBuffer\nListBuffer[Int]()"), "safe code")
  test("safe mode: StringBuilder is rejected (documented quirk)"):
    assertFails(run("val sb = StringBuilder()"), "safe code")
  test("safe mode: Thread.sleep and sys.error are rejected"):
    assertFails(run("Thread.sleep(1)"), "safe code")
    assertFails(run("""sys.error("boom")"""), "safe code")
  test("safe mode: local var and immutable collections are fine"):
    val r = assertOk(run("def count(): Int = { var c = 0; for i <- 1 to 3 do c += i; c }\ncount()"))
    assert(r.output.contains("6"), r.output)
  test("safe mode: effects inside Option.foreach are rejected, match works"):
    assertFails(run("Some(1).foreach(println)"), "")
    assertOk(run("Some(1) match { case Some(v) => println(v); case _ => () }"))
  test("safe mode: top-level val of a capturing type needs an explicit type"):
    assertFails(run("""val handle = access("hello.txt")"""), "needs an explicit type")
    assertFails(run("""val printer = () => println("x")"""), "needs an explicit type")
    assertOk(run("""val handle: FileEntry^{fs} = access("hello.txt")"""))
    assertOk(run("""def printer(): Unit = println("x")"""))
  test("no safe mode: top-level var and mutable collections work, validator still applies"):
    assert(assertOk(nosafe.run("var counter = 0; counter += 1; counter")).output.contains("1"))
    assertOk(nosafe.run("val buf = scala.collection.mutable.ArrayBuffer[Int](); buf += 1; buf.size"))
    assertFails(nosafe.run("import java.io.File"), "file-io-java")
    assertFails(nosafe.run("""sys.props("user.home")"""), "sys-scala")
    assertFails(nosafe.run("import java.\n  io.File"), "file-io-java")
    assertFails(nosafe.run("""val h = s"${System.getProperty("user.home")}"; h"""), "sys-getprop")
  test("no safe mode: :imports lacks the safe import"):
    assert(!assertOk(nosafe.run(":imports")).output.contains("experimental.safe"))
  test("no safe mode: capture checking still prevents leaks"):
    assertFails(nosafe.run("""val leaked = requestFiles("/tmp") { access("/tmp") }"""), "leak")

  // ── Timeouts and interruption ───────────────────────────────────

  test("execution timeout is reported and the session stays usable"):
    val r = quick.run("while true do ()")
    assert(!r.success)
    assert(r.error.exists(_.contains("timed out")), r.error.toString)
    assert(assertOk(quick.run("1 + 1")).output.contains("2"))
  test("time spent waiting for the user is not counted against the timeout"):
    val outside = TestEnv.outsideDir("slow-user")
    envQuick.onRequest = _ =>
      envQuick.session.foreach(_.clock.pause())
      Thread.sleep(1500)
      envQuick.session.foreach(_.clock.resume())
    envQuick.decisions = List(Decision.AllowOnce)
    try
      val r = assertOk(quick.run(s"""requestFiles("$outside", Access.Read, "slow") { read("$outside/o.txt") }"""))
      assert(r.output.contains("slow-user"), r.output)
    finally envQuick.onRequest = _ => ()
  test("interrupt() aborts a running evaluation"):
    val env = TestEnv(prefix = "atc-repl-interrupt")
    val s = env.newSession(timeoutMs = Some(60000L))
    @volatile var result: Option[ExecutionResult] = None
    val t = Thread(() => result = Some(s.run("while true do ()")))
    t.setDaemon(true)
    t.start()
    Thread.sleep(700)
    s.interrupt()
    t.join(10000)
    assert(!t.isAlive, "evaluation did not stop after interrupt")
    assert(result.exists(!_.success), result.toString)
    assert(assertOk(s.run("2 + 2")).output.contains("4"))

  // ── Output capture ──────────────────────────────────────────────

  test("printed output is bounded by the capture limit"):
    val r = assertOk(run("""println("x" * (ReplSessionMax + 100000)); ()""".replace(
      "ReplSessionMax",
      ReplSession.MaxOutputBytes.toString
    )))
    assert(r.output.endsWith(ReplSession.TruncationMarker.trim), r.output.takeRight(200))
    assert(r.output.length <= ReplSession.MaxOutputBytes + ReplSession.TruncationMarker.length + 16)
  test("leading whitespace of printed output is preserved (the UI matches it verbatim)"):
    val r = assertOk(run("""println("   indented"); ()"""))
    assert(r.output.startsWith("   indented"), r.output)
  test("echoed values are capped, printed output is not"):
    val r = assertOk(run("""val big = "x" * 5000"""))
    assert(r.output.contains("more characters not shown"), r.output.takeRight(200))
    assert(r.output.length < 5000, r.output.length.toString)
    val p = assertOk(run("""println("y" * 5000); ()"""))
    assert(p.output.count(_ == 'y') == 5000, p.output.length.toString)
  test("the echo cap never splits a surrogate pair and counts the hidden characters exactly"):
    // Default cap is 2000 chars; the echo `val emoji: String = "🙂🙂🙂…"` puts a pair's high half at
    // index 1999 (21-char prefix), so a naive cut would leave a lone surrogate.
    val r = assertOk(run("""val emoji = "\uD83D\uDE42" * 1200"""))
    val shown = r.output.takeWhile(_ != '…')
    assert(!Character.isHighSurrogate(shown.last), "cut inside a surrogate pair")
    val hidden = """\[(\d+) more characters""".r.findFirstMatchIn(r.output).map(_.group(1).nn.toInt).getOrElse(-1)
    val full = "val emoji: String = \"" + "\uD83D\uDE42" * 1200 + "\""
    assertEquals(shown.length + hidden, full.length)
  test("Java-returning calls bound to top-level vals do not warn about flexible types"):
    val r = assertOk(run("""val s = "abc".replace("a", "b")"""))
    assert(!r.output.contains("flexible type"), r.output)
    assert(!r.output.contains("warning"), r.output)
  test("output is reset between runs"):
    assertOk(run("""println("first")"""))
    val r = assertOk(run("""println("second")"""))
    assert(!r.output.contains("first"), r.output)

  // ── Preamble ────────────────────────────────────────────────────

  test("a broken preamble fails init loudly"):
    val env = TestEnv(prefix = "atc-repl-badpre")
    val e = intercept[IllegalStateException](env.newSession(preambleOverride = Some("val x: Int = \"no\"")))
    assert(e.getMessage.nn.contains("preamble"), e.getMessage)
  test("agent code cannot re-take the root capability"):
    // The validator blocks the name outright...
    assertFails(run("atc.lib.Runtime.rootIO"), "atc-runtime")
    // ...and the given `io` the preamble bound is the only IOCap in scope; there is no
    // other way to obtain one, so an effect always needs the single existing capability.
    assert(assertOk(run("""println("io works")""")).output.contains("io works"))

  // ── ExecutionResult / helpers (unit) ────────────────────────────

  test("ExecutionResult.render"):
    assertEquals(ExecutionResult(true, "").render, "(no output)")
    assertEquals(ExecutionResult(false, "").render, "(failed, no output)")
    assertEquals(ExecutionResult(true, "out").render, "out")
    assertEquals(ExecutionResult(false, "", Some("bad")).render, "ERROR: bad")
    assertEquals(ExecutionResult(false, "out", Some("bad")).render, "out\nERROR: bad")
  test("ExecutionResult.trimStackFrames"):
    val trace = """java.lang.RuntimeException: boom
      |  at rs$line$3$.f(rs$line$3:1)
      |  at atc.host.Host.exec(Host.scala:1)
      |  at java.base/java.lang.Thread.run(Thread.java:1)
      |  at scala.Function0.apply(Function0.scala:1)
      |  at dotty.tools.repl.Rendering.x(Rendering.scala:1)
      |  at sun.misc.X.y(X.java:1)
      |  at jdk.internal.X.y(X.java:1)
      |  ... 33 elided
      |  ... 2 more""".stripMargin
    assertEquals(
      ExecutionResult.trimStackFrames(trace),
      "java.lang.RuntimeException: boom\n  at rs$line$3$.f(rs$line$3:1)"
    )
  test("an exception thrown while binding a val, or by a later statement, fails the run"):
    assertFails(run("""val v: Int = throw new IllegalStateException("in val")"""), "in val")
    assertFails(run("def f(): Int = throw new IllegalStateException(\"in def\")\nval ok = 1\nf()"), "in def")
  test("output that merely looks like a stack trace does not fail the run"):
    assertOk(run("""println("java.lang.RuntimeException: boom\n  ... 33 elided")"""))
    assertOk(run("""println("java.lang.RuntimeException: boom\n  at rs$line$1$.x(rs$line$1:1)")"""))
  test("a closed session refuses to run"):
    val env = TestEnv(prefix = "atc-repl-closed")
    val s = env.newSession()
    s.close()
    assertFails(s.run("1 + 1"), "closed")
  test("BoundedOutputStream truncates and resets"):
    val b = ReplSession.BoundedOutputStream(10)
    b.write("hello".getBytes)
    assert(!b.truncated)
    b.write(" world!!".getBytes)
    assert(b.truncated)
    assertEquals(b.capturedString, "hello worl")
    b.write('x'.toInt)
    assertEquals(b.capturedString, "hello worl")
    b.resetCapture()
    assert(!b.truncated)
    assertEquals(b.capturedString, "")
    b.write('x'.toInt)
    assertEquals(b.capturedString, "x")
  test("the clock is reset for every run, including ones the validator rejects"):
    quick.clock.pause(); Thread.sleep(20); quick.clock.resume()
    assert(quick.clock.paused > 0)
    assertFails(quick.run("java.io.File"), "forbidden")
    assertEquals(quick.clock.paused, 0L)
  test("ExecutionClock accounts paused time"):
    val c = ExecutionClock()
    assertEquals(c.paused, 0L)
    c.pause()
    Thread.sleep(50)
    assert(c.paused >= 40_000_000L, c.paused.toString)
    c.resume()
    val after = c.paused
    Thread.sleep(20)
    assertEquals(c.paused, after) // not counting while resumed
    c.resume() // idempotent
    c.pause(); c.pause() // idempotent
    c.resume()
    c.reset()
    assertEquals(c.paused, 0L)

  // ── Class-loader isolation (unit) ───────────────────────────────

  test("Sandbox.isShared"):
    assert(Sandbox.isShared("scala.collection.immutable.List"))
    assert(Sandbox.isShared("atc.lib.Interface"))
    assert(Sandbox.isShared("scala.runtime.BoxesRunTime"))
    assert(!Sandbox.isShared("atc.host.Host"))
    assert(!Sandbox.isShared("atc.sandbox.Sandbox"))
    assert(!Sandbox.isShared("dotty.tools.repl.ReplDriver"))
    assert(!Sandbox.isShared("scala.tools.nsc.Global"))
    assert(!Sandbox.isShared("scala.quoted.runtime.impl.QuotesImpl"))
    assert(!Sandbox.isShared("ujson.Value"))
    assert(!Sandbox.isShared("java.util.List"))
  test("sandbox loader: JDK from the platform, shared classes from the app, nothing else"):
    val loader = Sandbox.newLoader()
    assert(loader.loadClass("java.util.ArrayList") eq classOf[java.util.ArrayList[?]])
    assert(loader.loadClass("scala.collection.immutable.List") eq classOf[List[?]])
    assert(loader.loadClass("atc.lib.Classified") eq classOf[atc.lib.Classified[?]])
    intercept[ClassNotFoundException](loader.loadClass("atc.host.Host"))
    intercept[ClassNotFoundException](loader.loadClass("atc.host.ClassifiedImpl"))
    intercept[ClassNotFoundException](loader.loadClass("atc.sandbox.ReplSession"))
    intercept[ClassNotFoundException](loader.loadClass("ujson.Value"))
    intercept[ClassNotFoundException](loader.loadClass("com.openai.client.OpenAIClient"))
    intercept[ClassNotFoundException](loader.loadClass("dotty.tools.repl.ReplDriver"))
  test("library classpath property points at existing entries"):
    assert(Sandbox.libraryClasspath.nonEmpty)
    assert(Sandbox.libraryClasspath.forall(Files.exists(_)))
