package atc

import atc.confine.CommandSandbox
import atc.evaluator.EvaluatorSession
import atc.perms.*
import atc.platform.{Platform, PlatformPath}
import atc.sandbox.SandboxConfig

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

/** The agent's REPL in a separate evaluator process: every API call crosses to the host,
  * and the process itself runs under the OS sandbox where there is one. */
class EvaluatorSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private lazy val sandbox: CommandSandbox = CommandSandbox.detect("auto")

  private def withSession[T](env: TestEnv, mode: Mode = Mode.Full, timeout: Option[Long] = Some(60_000L))(
    body: EvaluatorSession => T
  ): T =
    // ATC starts an evaluator only under an OS sandbox, which it does not have on Windows yet.
    assume(!Platform.isWindows, "no evaluator process on Windows")
    env.policy.mode = mode
    val session = EvaluatorSession.start(SandboxConfig(true, mode, timeout), env.host, sandbox)
    env.session = Some(session) // host-side prints go to the evaluator, as in the application
    try body(session)
    finally session.close()

  test("the evaluator runs code and forwards file operations to the host"):
    val env = TestEnv(prefix = "atc-evaluator")
    env.file("a.txt", "hello")
    withSession(env): session =>
      val read = session.run("""read("a.txt").length""")
      assert(read.success && read.output.contains("5"), read.render)
      val written = session.run("""write("b.txt", "written"); println("done")""")
      assert(written.success && written.output.contains("done"), written.render)
      assertEquals(env.contents("b.txt"), "written")
      val denied = session.run("""read("/etc/hosts")""")
      assert(!denied.success && denied.render.contains("Access denied"), denied.render)

  test("definitions persist between runs, and compile errors come back as results"):
    val env = TestEnv(prefix = "atc-evaluator-defs")
    withSession(env): session =>
      assert(session.run("val x = 41").success)
      assert(session.run("x + 1").output.contains("42"))
      val broken = session.run("val y: Int = \"no\"")
      assert(!broken.success && broken.render.contains("Found"), broken.render)

  test("a request block runs its callback in the evaluator and closes its scope"):
    val env = TestEnv(prefix = "atc-evaluator-request")
    val outside = TestEnv.outsideDir("from-outside")
    env.decisions = List(Decision.AllowOnce)
    withSession(env): session =>
      val code = s"""requestFiles(${env.scalaString(outside)}) { read(${env.scalaString(outside.resolve("o.txt"))}) }"""
      val result = session.run(code)
      assert(result.success && result.output.contains("from-outside"), result.render)
      assertEquals(env.policy.openScopeCount, 0)

  test("prints from the evaluator and from the host reach the result in order"):
    val env = TestEnv(prefix = "atc-evaluator-print")
    env.file("f.txt", "file line")
    withSession(env): session =>
      val result = session.run("""println("before"); cat("f.txt"); println("after")""")
      val output = result.output
      assert(output.indexOf("before") < output.indexOf("file line"), output)
      assert(output.indexOf("file line") < output.indexOf("after"), output)
      assert(env.userOut.toString.contains("before"), env.userOut.toString)

  test("parallel tasks call the host from their own threads"):
    val env = TestEnv(prefix = "atc-evaluator-parallel")
    env.file("a.txt", "abc")
    withSession(env): session =>
      val result = session.run("""parallel(List.fill(6)(() => read("a.txt").length)).sum""")
      assert(result.success && result.output.contains("18"), result.render)

  test("classified values stay masked for the model and readable for the user"):
    val env = TestEnv(prefix = "atc-evaluator-classified")
    withSession(env): session =>
      val result = session.run("""println(classify("s3cr3t").map(_.toUpperCase))""")
      assert(
        result.success && !result.output.contains("S3CR3T") && result.output.contains("Classified(***)"),
        result.render
      )
      assert(env.userOut.toString.contains("S3CR3T"), env.userOut.toString)

  test("a classified block runs in the evaluator and reads classified files for the user only"):
    val env = TestEnv(mkRules = TestEnv.withSecrets, prefix = "atc-evaluator-block")
    env.file("secrets/k.txt", "s3cr3t-in-a-file")
    withSession(env): session =>
      val result = session.run("""println(classified { read("secrets/k.txt").toUpperCase })""")
      assert(
        result.success && !result.output.contains("S3CR3T") && result.output.contains("Classified(***)"),
        result.render
      )
      assert(env.userOut.toString.contains("S3CR3T-IN-A-FILE"), env.userOut.toString)
      val written = session.run("""classified { write("public.txt", read("secrets/k.txt")) }""")
      assert(!written.success, "a classified block writes nothing: write does not compile there")
      assert(!env.existsOnDisk("public.txt"))
      assertEquals(env.policy.openScopeCount, 0)

  test("control flow that depends on a classified value stays inside it, and so does its failure"):
    val env = TestEnv(mkRules = TestEnv.withSecrets, prefix = "atc-evaluator-control")
    env.file("secrets/k.txt", "S")
    withSession(env): session =>
      assert(session.run("""val c = classified { read("secrets/k.txt") }""").success)
      val viaMap = session.run(
        """def bit(i: Int): Boolean = { c.map(s => if ((s.charAt(0) >> i) & 1) == 1 then return true else 0); false }
          |println((0 until 8).map(i => if bit(i) then 1 else 0).mkString)""".stripMargin
      )
      assert(viaMap.success && viaMap.output.contains("00000000"), viaMap.render)
      val viaBlock = session.run(
        """def bit2(i: Int): Boolean = { classified { if ((c.reveal.charAt(0) >> i) & 1) == 1 then return true else 0 }; false }
          |println((0 until 8).map(i => if bit2(i) then 1 else 0).mkString)""".stripMargin
      )
      assert(viaBlock.success && viaBlock.output.contains("00000000"), viaBlock.render)
      val written = session.run(
        """class Boom extends RuntimeException { override def getMessage: String = throw IllegalStateException("seen") }
          |writeClassified("secrets/n.txt", c.map(_ => throw Boom()))
          |println("written")""".stripMargin
      )
      assert(written.success && written.output.contains("written"), written.render)

  test("an interrupted loop stops and the session stays usable"):
    val env = TestEnv(prefix = "atc-evaluator-interrupt")
    withSession(env, timeout = None): session =>
      val stopper = Thread(() => { Thread.sleep(1500); session.interrupt() })
      stopper.start()
      val stopped = session.run("while true do ()")
      assert(!stopped.success, stopped.render)
      assert(session.alive)
      assert(session.run("1 + 1").output.contains("2"))

  test("an interrupt during a run with a time limit stops the command the host runs for it"):
    val env = TestEnv(commands = List(ProcessFixture.pattern("sleep")), prefix = "atc-evaluator-stop-command")
    def sleeping = ProcessHandle.current().nn.descendants().nn.iterator().nn.asScala
      .exists(p =>
        p.info().nn.arguments().nn.orElse(Array.empty[String]).nn.toList.takeRight(3) ==
          List("atc.TestProcess", "sleep", "30000")
      )
    withSession(env): session =>
      val stopper = Thread: () =>
        val deadline = System.nanoTime() + 20_000_000_000L
        while !sleeping && System.nanoTime() < deadline do Thread.sleep(100)
        session.interrupt()
      stopper.start()
      val stopped = session.run(s"""exec(${ujson.write(ProcessFixture.command("sleep", "30000"))})""")
      assert(!stopped.success, stopped.render)
      val deadline = System.nanoTime() + 5_000_000_000L
      while sleeping && System.nanoTime() < deadline do Thread.sleep(100)
      assert(!sleeping, "the command outlived the interrupt")
      assert(session.alive)

  test("the time limit is the host's: a loop stops at it, inside a classified block too, and the session goes on"):
    val env = TestEnv(prefix = "atc-evaluator-timeout")
    withSession(env, timeout = Some(2000L)): session =>
      assert(session.run("val kept = 41").success)
      for code <- List("while true do ()", "classified { while true do (); 1 }") do
        val stopped = session.run(code)
        assert(!stopped.success && stopped.render.contains("timed out after 2000ms"), stopped.render)
        assert(session.alive, s"the evaluator was ended for: $code")
      assert(session.run("kept + 1").output.contains("42"), "the definitions are kept")

  test("the stop of one run does not end the next"):
    val env = TestEnv(commands = List(ProcessFixture.pattern("sleep")), prefix = "atc-evaluator-next-run")
    withSession(env, timeout = None): session =>
      val stopper = Thread(() => { Thread.sleep(1000); session.interrupt() })
      stopper.start()
      assert(!session.run("while true do ()").success)
      // Runs past the grace period that began with the first run's stop.
      val next = session.run(s"""exec(${ujson.write(ProcessFixture.command("sleep", "6000"))}).exitCode""")
      assert(next.success && next.output.contains("0"), next.render)

  test("a result too large for the channel is an error, and the session goes on"):
    val env = TestEnv(prefix = "atc-evaluator-large")
    Files.write(env.root.resolve("big.txt"), Array.fill[Byte](70 * 1024 * 1024)('a'.toByte))
    withSession(env): session =>
      val large = session.run("""read("big.txt").length""")
      assert(!large.success && large.render.contains("more than the channel carries"), large.render)
      assert(session.run("1 + 1").output.contains("2"))

  test("a run that ignores the stop is ended with its process"):
    val env = TestEnv(prefix = "atc-evaluator-kill")
    withSession(env, timeout = None): session =>
      val stopper = Thread(() => { Thread.sleep(1500); session.interrupt() })
      stopper.start()
      // JDK code, which the REPL does not instrument, never looks at the stop flag, and this
      // power takes far longer than the grace period.
      val result = session.run("BigInt(3).pow(200000000).bitLength")
      assert(!result.success && result.render.contains("evaluator process stopped"), result.render)
      assert(!session.alive)

  test("the evaluator's OS sandbox lets it read the JDK and nothing of the user's"):
    assume(sandbox.confined, s"no OS sandbox here: ${sandbox.describe}")
    val work = PlatformPath.canonical(Files.createTempDirectory("atc-evaluator-probe").nn)
    val probe = work.resolve("Probe.java").nn
    val secret = PlatformPath.canonical(Files.createTempFile(PlatformPath.userHome, ".atc-probe", ".txt").nn)
    Files.writeString(secret, "user data")
    Files.writeString(
      probe,
      s"""public class Probe {
         |  static void attempt(String what, java.util.concurrent.Callable<Object> body) {
         |    try { body.call(); System.out.println(what + " allowed"); }
         |    catch (Throwable e) { System.out.println(what + " denied"); }
         |  }
         |  public static void main(String[] args) {
         |    attempt("home", () -> java.nio.file.Files.readString(java.nio.file.Path.of("$secret")));
         |    attempt("network", () -> new java.net.Socket("1.1.1.1", 443));
         |    attempt("write", () -> java.nio.file.Files.writeString(java.nio.file.Path.of("$work", "out.txt"), "x"));
         |    attempt("exec", () -> new ProcessBuilder("/bin/echo").start().waitFor());
         |  }
         |}""".stripMargin,
    )
    try
      val java = PlatformPath.canonical(Paths.get(System.getProperty("java.home"), "bin", "java").nn)
      val javaHome = PlatformPath.canonical(Paths.get(System.getProperty("java.home")).nn)
      val launch = sandbox.evaluator(java, List(javaHome, work)).get
      val builder = ProcessBuilder((launch.prefix ++ List(java.toString, "-Xlog:disable", probe.toString)).asJava)
        .directory(work.toFile).redirectErrorStream(true)
      builder.environment().nn.clear()
      val process = launch.start(builder)
      val output = String(process.getInputStream.nn.readAllBytes().nn)
      process.waitFor()
      // On Linux a program the evaluator starts stays inside the same sandbox; the macOS
      // profile allows executing nothing but `java`.
      for what <- List("home", "network", "write") ++ Option.when(atc.platform.Platform.isMac)("exec") do
        assert(output.contains(s"$what denied"), output)
    finally Files.deleteIfExists(secret)
