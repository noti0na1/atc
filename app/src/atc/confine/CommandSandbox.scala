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
  * bubblewrap on Linux, or not at all. The `commandSandbox` setting chooses
  * between confining when the platform can (`auto`), refusing commands when it
  * cannot (`required`) and not confining (`off`). */
sealed trait CommandSandbox:
  /** Whether commands run confined. */
  def confined: Boolean

  /** One line for the banner: how commands run. */
  def describe: String

  /** Rewrite each stage of a command started with capabilities of `scope` to run
    * confined, and return how to start the stages and what to clean up after the
    * process tree has exited. */
  def prepare(stages: List[ProcessBuilder], policy: Policy, scope: ScopeId, cwd: Path): CommandSandbox.Launch

object CommandSandbox:
  final case class Launch(start: List[ProcessBuilder] => List[java.lang.Process], cleanup: () => Unit)

  /** Starts the stages on the calling thread, as ProcessBuilder does. */
  val startHere: List[ProcessBuilder] => List[java.lang.Process] = stages =>
    if stages.lengthIs == 1 then List(stages.head.start().nn)
    else ProcessBuilder.startPipeline(stages.asJava).nn.asScala.toList

  /** Commands run as they are, with the user's authority. */
  final class Unconfined(reason: String) extends CommandSandbox:
    def confined: Boolean = false
    def describe: String = s"not sandboxed ($reason)"
    def prepare(stages: List[ProcessBuilder], policy: Policy, scope: ScopeId, cwd: Path): Launch =
      Launch(startHere, () => ())

  /** `required` without a usable sandbox: every command is refused. */
  final class Refusing(reason: String) extends CommandSandbox:
    def confined: Boolean = false
    def describe: String = s"refused: the sandbox is required but $reason"
    def prepare(stages: List[ProcessBuilder], policy: Policy, scope: ScopeId, cwd: Path): Launch =
      throw SecurityException(
        s"Access denied: commands must run in the OS sandbox (\"commandSandbox\": \"required\"), but $reason. Tell the user."
      )

  /** The sandbox the setting asks for, as far as this platform provides it. */
  def detect(setting: String): CommandSandbox =
    val backend: Either[String, CommandSandbox] =
      if Platform.isMac then Either.cond(Seatbelt.available, SeatbeltSandbox(), "sandbox-exec cannot run here")
      else if Platform.isWindows then Left("Windows has no command sandbox yet")
      else Either.cond(Bubblewrap.available, BubblewrapSandbox(), "bubblewrap is missing or user namespaces are off")
    Setting.parse(setting) match
      case Setting.Off => Unconfined("the commandSandbox setting is off")
      case Setting.Auto => backend.fold(Unconfined(_), identity)
      case Setting.Required => backend.fold(Refusing(_), identity)

  /** The values of `commandSandbox`, from least to most strict. */
  enum Setting(val label: String):
    case Off extends Setting("off")
    case Auto extends Setting("auto")
    case Required extends Setting("required")

  object Setting:
    def parse(value: String): Setting =
      values.find(_.label == value.trim.toLowerCase(Locale.ROOT)).getOrElse(
        throw IllegalArgumentException(s"Unknown commandSandbox '$value' (expected auto|required|off)")
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

  private def plan(policy: Policy, scope: ScopeId, cwd: Path): SandboxPlan =
    SandboxPlan(policy, scope, PlatformPath.canonical(cwd), home, policy.mode.allowsNetwork, cacheDir)

  /** Settings that keep common tools inside their temporary directory and the sandbox's cache. */
  private def configure(stage: ProcessBuilder, command: List[String], tmp: String, cache: Path): Unit =
    stage.command((command ++ stage.command().nn.asScala).asJava)
    val environment = stage.environment().nn
    AgentVariables.foreach(environment.remove)
    environment.put("TMPDIR", tmp)
    environment.put("TMPPREFIX", s"$tmp/zsh")
    environment.put("MPLCONFIGDIR", cache.resolve("matplotlib").toString)
    environment.put("UV_CACHE_DIR", cache.resolve("uv").toString)
    val sbt = s"-Dsbt.global.staging=${cache.resolve("sbt-staging")} -Dsbt.server.forcestart=true"
    environment.put("SBT_OPTS", Option(environment.get("SBT_OPTS")).fold(sbt)(existing => s"$existing $sbt"))

  private final class SeatbeltSandbox extends CommandSandbox:
    def confined: Boolean = true
    def describe: String = "sandboxed with Seatbelt"
    def prepare(stages: List[ProcessBuilder], policy: Policy, scope: ScopeId, cwd: Path): Launch =
      val sandboxPlan = plan(policy, scope, cwd)
      // A short path: sbt's socket path is the temporary directory plus about 49 bytes, within a 104-byte limit.
      val tmp = PlatformPath.canonical(Files.createTempDirectory(Paths.get("/private/tmp").nn, "atc-").nn)
      val prefix = Seatbelt.prefix(sandboxPlan, tmp)
      stages.foreach: stage =>
        configure(stage, prefix, tmp.toString, sandboxPlan.cache)
        // The JVM on macOS ignores TMPDIR and would write into the user's shared temporary directory.
        val environment = stage.environment().nn
        val option = s"-Djava.io.tmpdir=$tmp"
        environment.put(
          "JAVA_TOOL_OPTIONS",
          Option(environment.get("JAVA_TOOL_OPTIONS")).fold(option)(o => s"$o $option")
        )
      Launch(startHere, () => deleteTree(tmp))

  private final class BubblewrapSandbox extends CommandSandbox:
    def confined: Boolean = true
    def describe: String = "sandboxed with bubblewrap"
    def prepare(stages: List[ProcessBuilder], policy: Policy, scope: ScopeId, cwd: Path): Launch =
      val sandboxPlan = plan(policy, scope, cwd)
      val prefix = Bubblewrap.prefix(sandboxPlan)
      stages.foreach(configure(_, prefix, "/tmp", sandboxPlan.cache))
      Launch(Launcher.start, () => ())

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
