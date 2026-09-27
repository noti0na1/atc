package atc.evaluator

import atc.confine.CommandSandbox
import atc.host.{Host, HostDispatch}
import atc.platform.{Platform, PlatformPath}
import atc.sandbox.{ExecutionClock, ExecutionResult, SandboxConfig, SandboxSession}

import java.io.{IOException, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** The agent's REPL in a separate evaluator process, started confined by the OS sandbox.
  * The compiler, the REPL and the agent's code run there and hold no keys; every effect
  * comes back here as a call that [[HostDispatch]] checks against the policy. Stopping
  * first raises the REPL's stop flag; an evaluation that does not stop within a grace
  * period, or runs past its time limit, is ended with the process, and the next tool call
  * starts a new one. */
final class EvaluatorSession private (
  process: java.lang.Process,
  channel: Channel,
  stderr: EvaluatorSession.Tail,
  config: SandboxConfig,
  work: Path,
) extends SandboxSession:
  /** Host-side pauses (prompts, commands, model calls), subtracted from the reported time. */
  val clock: ExecutionClock = ExecutionClock()
  @volatile private var running: Option[Long] = None
  @volatile private var stopReason: Option[String] = None

  override def alive: Boolean = process.isAlive && !channel.isClosed

  def run(code: String): ExecutionResult =
    clock.reset()
    if !alive then ExecutionResult.failed(stopped("the evaluator process is not running"))
    else
      running = Some(channel.conversation)
      val watchdog =
        config.executionTimeoutMs.map(limit => EvaluatorSession.daemon("atc-evaluator-watchdog")(() => watch(limit)))
      try
        val d = channel.call("eval")(_.string(code))
        ExecutionResult(d.bool(), d.string(), d.optionalString())
      catch
        case e: IOException => ExecutionResult.failed(stopped(e.getMessage.nn))
      finally
        running = None
        watchdog.foreach(_.interrupt())

  /** End the process when the run exceeds its time limit, less host-side pauses, by more than
    * the grace period: the REPL's own timeout did not stop it. */
  private def watch(limitMs: Long): Unit =
    val start = System.nanoTime()
    try
      while running.isDefined do
        Thread.sleep(500)
        val elapsedMs = (System.nanoTime() - start - clock.paused) / 1_000_000L
        if running.isDefined && elapsedMs > limitMs + EvaluatorSession.GraceMs then
          kill(s"the snippet ran past its ${limitMs} ms limit and did not stop")
    catch case _: InterruptedException => ()

  def printAgent(text: String): Unit =
    if alive then
      try channel.call("print")(_.string(text))
      catch case NonFatal(_) => ()

  def interrupt(): Unit =
    for conversation <- running do
      try channel.cancel(conversation)
      catch case NonFatal(_) => ()
      EvaluatorSession.daemon("atc-evaluator-stop"): () =>
        try
          Thread.sleep(EvaluatorSession.GraceMs)
          if running.contains(conversation) then kill("the snippet did not stop when interrupted")
        catch case _: InterruptedException => ()

  def close(): Unit =
    stopReason = stopReason.orElse(Some("the session was closed"))
    channel.close()
    process.destroy()
    EvaluatorSession.daemon("atc-evaluator-close"): () =>
      if !process.waitFor(2, TimeUnit.SECONDS) then EvaluatorSession.killTree(process)
      EvaluatorSession.deleteTree(work)

  private def kill(reason: String): Unit =
    stopReason = stopReason.orElse(Some(reason))
    EvaluatorSession.killTree(process)

  private def stopped(detail: String): String =
    val why = stopReason.getOrElse(detail)
    val tail = stderr.text.trim
    s"The evaluator process stopped ($why). Its REPL definitions are gone; the next tool call starts a new REPL." +
      (if tail.isEmpty then "" else s"\n$tail")

object EvaluatorSession:
  /** How long a stopped or overdue evaluation gets before its process is ended. */
  val GraceMs: Long = 5_000L

  /** Start an evaluator process for `host`, confined by `sandbox` where it provides a way
    * (unconfined otherwise), and load the preamble. */
  def start(config: SandboxConfig, host: Host, sandbox: CommandSandbox): EvaluatorSession =
    val java = PlatformPath.canonical(Paths.get(System.getProperty("java.home"), "bin", "java").nn)
    val classpath = entries(System.getProperty("java.class.path"))
    val library = entries(System.getProperty("atc.lib.classpath"))
    val work = PlatformPath.canonical(Files.createTempDirectory(tempRoot, "atc-evaluator-").nn)
    val javaHome = PlatformPath.canonical(Paths.get(System.getProperty("java.home")).nn)
    val readable = (javaHome :: work :: classpath ++ library).distinct
    val launch = sandbox.evaluator(java, readable).getOrElse(CommandSandbox.EvaluatorLaunch(Nil, _.start().nn))
    val command = launch.prefix ++ List(
      java.toString,
      "-Xss4m",
      "-Xms32m",
      "-Xmx1g",
      "-XX:+UseSerialGC",
      "-XX:-UsePerfData",
      "-Dfile.encoding=UTF-8",
      // The JVM's own warnings go to stdout by default, where they would break the channel.
      "-Xlog:disable",
      "-Xlog:all=warning:stderr",
      s"-Datc.lib.classpath=${library.mkString(Platform.pathListSeparator)}",
      "-cp",
      classpath.mkString(Platform.pathListSeparator),
      "atc.evaluator.EvaluatorMain",
    )
    val builder = ProcessBuilder(command.asJava).directory(work.toFile).nn
    builder.environment().nn.clear() // no keys, and nothing else, reach the evaluator
    val process = launch.start(builder)
    val stderr = Tail(process.getErrorStream.nn)
    try
      greet(process.getInputStream.nn, process)
      lazy val channel: Channel =
        Channel("host", process.getInputStream.nn, process.getOutputStream.nn, conversationTag = 0)
      val dispatch = HostDispatch(host, channel)
      channel.handler = dispatch.handle
      channel.start()
      val session = EvaluatorSession(process, channel, stderr, config, work)
      channel.call("init"): e =>
        e.bool(
          config.safeMode
        ).string(config.mode.toString).optionalLong(config.executionTimeoutMs).int(config.maxEchoChars)
      session
    catch
      case NonFatal(e) =>
        killTree(process)
        deleteTree(work)
        val tail = stderr.text.trim
        throw IllegalStateException(
          s"the evaluator process did not start: ${e.getMessage}${if tail.isEmpty then "" else s"\n$tail"}"
        )

  /** Wait for the evaluator's greeting, which marks where the channel begins. */
  private def greet(in: InputStream, process: java.lang.Process): Unit =
    val timer = daemon("atc-evaluator-start"): () =>
      try
        Thread.sleep(60_000)
        killTree(process)
      catch case _: InterruptedException => ()
    try
      val greeting = in.readNBytes(Channel.Greeting.length).nn
      if !java.util.Arrays.equals(greeting, Channel.Greeting) then
        throw IOException(s"unexpected output instead of the greeting: ${String(greeting, UTF_8).take(80)}")
    finally timer.interrupt()

  private def entries(classpath: String | Null): List[Path] =
    Option(classpath).toList.flatMap(_.split(Platform.pathListSeparator).toList).filter(_.nonEmpty)
      .map(p => Paths.get(p).nn).filter(Files.exists(_)).map(PlatformPath.canonical)

  /** A short temporary directory the evaluator starts in. */
  private def tempRoot: Path =
    if Platform.isMac then Paths.get("/private/tmp").nn else Paths.get(System.getProperty("java.io.tmpdir")).nn

  private def killTree(process: java.lang.Process): Unit =
    try process.descendants().nn.forEach(_.destroyForcibly())
    catch case NonFatal(_) => ()
    process.destroyForcibly()
    ()

  private def deleteTree(root: Path): Unit =
    try
      if Files.exists(root) then
        scala.util.Using.resource(Files.walk(root).nn)(
          _.iterator.nn.asScala.toList
        ).reverse.foreach(Files.deleteIfExists)
    catch case NonFatal(_) => ()

  private def daemon(name: String)(body: Runnable): Thread =
    val thread = Thread(body, name)
    thread.setDaemon(true)
    thread.start()
    thread

  /** The last few kilobytes the evaluator wrote to stderr, for the message when it stops. */
  private final class Tail(stream: InputStream):
    private val buffer = StringBuilder()
    private val reader = daemon("atc-evaluator-stderr"): () =>
      val chunk = new Array[Byte](4096)
      try
        var count = stream.read(chunk)
        while count >= 0 do
          buffer.synchronized:
            buffer.append(String(chunk, 0, count, UTF_8))
            if buffer.length > 8192 then buffer.delete(0, buffer.length - 8192)
          count = stream.read(chunk)
      catch case NonFatal(_) => ()
    def text: String = buffer.synchronized(buffer.toString)
