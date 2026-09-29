package atc.confine

import atc.perms.{Policy, ScopeId}
import atc.platform.{Platform, PlatformPath}

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}
import java.util.Locale
import java.util.concurrent.{Callable, ExecutionException, Executors, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/** How the commands the agent runs are confined: under Seatbelt on macOS, under
  * bubblewrap on Linux, or not at all. The `osSandbox` setting chooses
  * between confining when the platform can (`auto`), refusing commands when it
  * cannot (`required`) and not confining (`off`). */
sealed trait CommandSandbox:
  /** Whether commands run confined. */
  def confined: Boolean

  /** One line for the banner: how commands run. */
  def describe: String

  /** What the banner should warn about, if anything. */
  def notice: Option[String] = Option.unless(confined)(describe)

  /** How to run the evaluator process confined, reading only `readable` (the JDK and ATC's
    * classes) besides the system's libraries; `None` where there is no OS sandbox. */
  def evaluator(java: Path, readable: List[Path]): Option[CommandSandbox.EvaluatorLaunch] = None

  /** Rewrite each stage of `line` to run confined, and return how to start the stages
    * and what to clean up after the process tree has exited. `scope` is the file
    * system capability's scope, `network` the scope of the `Network` a `withNetwork`
    * block lent the command (none: no network), and `writable` whether it may write
    * where the file system may, or only its temporary directory. */
  def prepare(
    stages: List[ProcessBuilder],
    policy: Policy,
    scope: ScopeId,
    network: Option[ScopeId],
    writable: Boolean,
    cwd: Path,
    line: String
  ): CommandSandbox.Launch

object CommandSandbox:
  final case class Launch(start: List[ProcessBuilder] => List[java.lang.Process], cleanup: () => Unit)

  /** The command line prefix of the evaluator process and how to start it. */
  final case class EvaluatorLaunch(prefix: List[String], start: ProcessBuilder => java.lang.Process)

  /** Starts the stages on the calling thread, as ProcessBuilder does. */
  val startHere: List[ProcessBuilder] => List[java.lang.Process] = stages =>
    if stages.lengthIs == 1 then List(stages.head.start().nn)
    else ProcessBuilder.startPipeline(stages.asJava).nn.asScala.toList

  /** Commands run as they are, with the user's authority. */
  final class Unconfined(reason: String) extends CommandSandbox:
    def confined: Boolean = false
    def describe: String = s"off ($reason): commands run with your privileges and the REPL runs inside ATC"
    def prepare(
      stages: List[ProcessBuilder],
      policy: Policy,
      scope: ScopeId,
      network: Option[ScopeId],
      writable: Boolean,
      cwd: Path,
      line: String,
    ): Launch =
      Launch(startHere, () => ())

  /** `required` without a usable sandbox: every command is refused. */
  final class Refusing(reason: String) extends CommandSandbox:
    def confined: Boolean = false
    def describe: String = s"required but $reason: commands and the REPL are refused"
    def prepare(
      stages: List[ProcessBuilder],
      policy: Policy,
      scope: ScopeId,
      network: Option[ScopeId],
      writable: Boolean,
      cwd: Path,
      line: String,
    ): Launch =
      throw SecurityException(
        s"Access denied: commands must run in the OS sandbox (\"osSandbox\": \"required\"), but $reason. Tell the user."
      )

  /** The sandbox the setting asks for, as far as this platform provides it. */
  def detect(setting: String): CommandSandbox = detect(setting, None)

  /** `mirror` (isolate mode's copy and the project it copies): commands see the copy at the
    * project's path as well, where the backend can show a directory at another path (Linux). */
  def detect(setting: String, mirror: Option[(Path, Path)]): CommandSandbox =
    val backend: Either[String, CommandSandbox] =
      if Platform.isMac then Either.cond(Seatbelt.available, SeatbeltSandbox(), "sandbox-exec cannot run here")
      else if Platform.isWindows then Left("Windows has no command sandbox yet")
      else
        Either.cond(
          Bubblewrap.available,
          BubblewrapSandbox(Bubblewrap.socat, mirror),
          "bubblewrap is missing or user namespaces are off"
        )
    Setting.parse(setting) match
      case Setting.Off => Unconfined("the osSandbox setting is off")
      case Setting.Auto => backend.fold(Unconfined(_), identity)
      case Setting.Required => backend.fold(Refusing(_), identity)

  /** The values of `osSandbox`, from least to most strict. */
  enum Setting(val label: String):
    case Off extends Setting("off")
    case Auto extends Setting("auto")
    case Required extends Setting("required")

  object Setting:
    def parse(value: String): Setting =
      values.find(_.label == value.trim.toLowerCase(Locale.ROOT)).getOrElse(
        throw IllegalArgumentException(s"Unknown osSandbox '$value' (expected auto|required|off)")
      )

  /** Variables removed from every confined command: they point at agents that act for the user. */
  private val AgentVariables = List("SSH_AUTH_SOCK", "GPG_AGENT_INFO", "DOCKER_HOST")

  private def home: Path = PlatformPath.canonical(PlatformPath.userHome)

  /** A directory the sandbox owns, where tools keep caches between commands. */
  private def cacheDir: Path =
    val base =
      if Platform.isMac then home.resolve("Library/Caches").nn
      else Option(System.getenv("XDG_CACHE_HOME")).map(Paths.get(_).nn).getOrElse(home.resolve(".cache").nn)
    val dir = base.resolve("atc-sandbox").nn
    if !Files.isDirectory(dir) then
      Files.createDirectories(dir)
      try Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
      catch case NonFatal(_) => ()
    PlatformPath.canonical(dir)

  private def plan(
    policy: Policy,
    scope: ScopeId,
    network: Option[ScopeId],
    writable: Boolean,
    cwd: Path
  ): SandboxPlan =
    SandboxPlan(policy, scope, PlatformPath.canonical(cwd), home, network.isDefined, writable, cacheDir)

  /** Settings that keep common tools inside their temporary directory and the sandbox's
    * cache, and send their traffic to the proxy at `proxy` (`127.0.0.1:port`) when the
    * command may use the network. */
  private def configure(
    stage: ProcessBuilder,
    command: List[String],
    tmp: String,
    cache: Path,
    proxy: Option[Int],
    readOnly: Boolean,
  ): Unit =
    stage.command((command ++ stage.command().nn.asScala).asJava)
    val environment = stage.environment().nn
    AgentVariables.foreach(environment.remove)
    // A read-only command cannot read the user's git configuration; git would stop at it.
    if readOnly then environment.put("GIT_CONFIG_GLOBAL", "/dev/null")
    environment.put("TMPDIR", tmp)
    environment.put("TMPPREFIX", s"$tmp/zsh")
    environment.put("MPLCONFIGDIR", cache.resolve("matplotlib").toString)
    environment.put("UV_CACHE_DIR", cache.resolve("uv").toString)
    val sbt = s"-Dsbt.global.staging=${cache.resolve("sbt-staging")} -Dsbt.server.forcestart=true"
    appendOption(environment, "SBT_OPTS", sbt)
    for port <- proxy do
      val url = s"http://127.0.0.1:$port"
      for name <- List("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY") do
        environment.put(name, url)
        environment.put(name.toLowerCase(Locale.ROOT), url)
      environment.put("NO_PROXY", "localhost,127.0.0.1,::1")
      environment.put("no_proxy", "localhost,127.0.0.1,::1")
      // The JVM ignores the variables above; its proxy comes from system properties.
      appendOption(
        environment,
        "JAVA_TOOL_OPTIONS",
        s"-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=$port -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=$port " +
          "-Dhttp.nonProxyHosts=localhost|127.0.0.1",
      )

  private def appendOption(environment: java.util.Map[String, String], name: String, value: String): Unit =
    environment.put(name, Option(environment.get(name)).fold(value)(existing => s"$existing $value"))

  private def closing(proxy: Option[CommandProxy], more: => Unit): () => Unit = () =>
    proxy.foreach(_.close())
    more

  private final class SeatbeltSandbox extends CommandSandbox:
    def confined: Boolean = true
    def describe: String = "sandboxed with Seatbelt"
    override def evaluator(java: Path, readable: List[Path]): Option[EvaluatorLaunch] =
      Some(EvaluatorLaunch(Seatbelt.evaluatorPrefix(java, readable), _.start().nn))
    def prepare(
      stages: List[ProcessBuilder],
      policy: Policy,
      scope: ScopeId,
      network: Option[ScopeId],
      writable: Boolean,
      cwd: Path,
      line: String,
    ): Launch =
      val sandboxPlan = plan(policy, scope, network, writable, cwd)
      val proxy = network.map(CommandProxy.tcp(policy, _, line))
      // A short path: sbt's socket path is the temporary directory plus about 49 bytes, within a 104-byte limit.
      val tmp = PlatformPath.canonical(Files.createTempDirectory(Paths.get("/private/tmp").nn, "atc-").nn)
      val prefix = Seatbelt.prefix(sandboxPlan, tmp, proxy.map(_.port))
      stages.foreach: stage =>
        configure(stage, prefix, tmp.toString, sandboxPlan.cache, proxy.map(_.port), readOnly = !writable)
        // The JVM on macOS ignores TMPDIR and would write into the user's shared temporary directory.
        appendOption(stage.environment().nn, "JAVA_TOOL_OPTIONS", s"-Djava.io.tmpdir=$tmp")
      Launch(startHere, closing(proxy, deleteTree(tmp)))

  /** `socat`, when present, forwards the sandbox's loopback proxy port to the host's proxy. */
  private final class BubblewrapSandbox(socat: Option[Path], mirror: Option[(Path, Path)]) extends CommandSandbox:
    def confined: Boolean = true
    def describe: String = "sandboxed with bubblewrap"
    override def evaluator(java: Path, readable: List[Path]): Option[EvaluatorLaunch] =
      Some(EvaluatorLaunch(Bubblewrap.evaluatorPrefix(home, readable), stage => Launcher.start(List(stage)).head))
    override def notice: Option[String] =
      Option.when(socat.isEmpty)(
        "sandboxed with bubblewrap; commands inside withNetwork reach the network unfiltered (socat is missing)"
      )
    def prepare(
      stages: List[ProcessBuilder],
      policy: Policy,
      scope: ScopeId,
      network: Option[ScopeId],
      writable: Boolean,
      cwd: Path,
      line: String,
    ): Launch =
      val sandboxPlan = plan(policy, scope, network, writable, cwd)
      val bridge =
        for socatPath <- socat; netScope <- network yield
          val dir = Files.createTempDirectory("atc-proxy").nn
          (socatPath, dir, CommandProxy.unix(dir.resolve("proxy.sock").nn, policy, netScope, line))
      // In isolate mode the command starts at the project's path, where it sees the copy.
      val start =
        mirror.collect { case (copy, project) if cwd.startsWith(copy) => project.resolve(copy.relativize(cwd)).nn }
      val prefix = Bubblewrap.prefix(sandboxPlan, bridge.map((socatPath, dir, _) => (socatPath, dir)), mirror, start)
      stages.foreach: stage =>
        configure(stage, prefix, "/tmp", sandboxPlan.cache, bridge.map(_ => Bubblewrap.ProxyPort), !writable)
        start.foreach(dir => stage.environment().nn.put("PWD", dir.toString))
      Launch(Launcher.start, closing(bridge.map(_._3), bridge.foreach((_, dir, _) => deleteTree(dir))))

  /** Starts processes on one long-lived thread. `--die-with-parent` kills the sandbox
    * when the thread that started it ends, not the JVM, and agent code runs on
    * short-lived threads. */
  private object Launcher:
    private val executor = Executors.newSingleThreadExecutor: runnable =>
      val thread = Thread(runnable, "atc-command-launcher")
      thread.setDaemon(true)
      thread

    def start(stages: List[ProcessBuilder]): List[java.lang.Process] =
      val future: Future[List[java.lang.Process]] =
        executor.submit((() => startHere(stages)): Callable[List[java.lang.Process]])
      // Starting takes milliseconds; waiting through an interrupt keeps a started process from being lost.
      var interrupted = false
      var result: Option[List[java.lang.Process]] = None
      while result.isEmpty do
        try result = Some(future.get())
        catch
          case _: InterruptedException => interrupted = true
          case e: ExecutionException => throw Option(e.getCause).getOrElse(e)
      if interrupted then Thread.currentThread().interrupt()
      result.get

  private def deleteTree(root: Path): Unit =
    try
      if Files.exists(root) then
        Using.resource(Files.walk(root).nn)(_.iterator.nn.asScala.toList).reverse.foreach(p => Files.deleteIfExists(p))
    catch case NonFatal(_) => ()
