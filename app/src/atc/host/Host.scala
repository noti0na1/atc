package atc.host

import atc.lib.*
import atc.perms.{GitIgnore, Policy, ScopeId}

import java.nio.file.Path
import java.util.concurrent.{ExecutionException, Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/** Implements the agent API and manages shared capabilities and permission scopes. */
final class Host(
  val policy: Policy,
  val cwd: Path,
  private[host] val output: HostOutput,
  private[host] val llm: HostLlm,
  private[host] val ui: HostUi,
  /** Paths git ignores are left out of listings (config `respectGitignore`). */
  private[host] val gitIgnore: GitIgnore = GitIgnore.Disabled,
  /** The environment variables holding provider keys, removed from every command's environment. */
  private[host] val keyVariables: () => Set[String] = () => Set.empty,
) extends Interface, Derivations, HostPaths, HostFiles, HostProcesses, HostNetwork, HostInteraction:

  /** The permission scope for a capability issued by this host. */
  private[host] def scopeOf(capability: AnyRef): ScopeId = capability match
    case scoped: Scoped => scoped.scope
    case other => throw SecurityException(s"Unknown capability implementation: ${other.getClass.getName}")

  /** Run with a temporary permission scope, then close it and its processes. */
  private[host] def inScope[T](id: ScopeId)(operation: ScopeId => T): T =
    try operation(id)
    finally
      killProcessesInScope(id)
      policy.closeScope(id)

  // Read-only and full access use the same runtime objects; their Scala types
  // expose different operations to agent code.
  def fileSystem(using IOCap): FileSystem = FileSystemImpl(ScopeId.Base, this)

  def readOnlyFileSystem(using IOCap): FileSystem = FileSystemImpl(ScopeId.Base, this)

  def processes(using IOCap): Exec = ExecImpl(ScopeId.Base)

  def network(using IOCap): Network = NetworkImpl(ScopeId.Base)

  /** Run the tasks on up to [[Host.MaxParallel]] daemon threads of a pool that lives for
    * this call only (a nested `parallel` gets its own, so it cannot starve the outer one).
    * Every task runs to its end before the call returns, even when one of them failed: a
    * task's effects (a command, a request) must not outlive the snippet. A fatal throwable
    * from any task wins over ordinary failures, since the REPL's stop signal (`ThreadDeath`,
    * raised in instrumented agent code on every thread once the session is interrupted)
    * must reach the evaluation thread. Interrupting the caller while it waits interrupts
    * the workers (for blocking host calls) and is reported as an interruption. */
  def parallel[A, C <: caps.CapSet](tasks: Seq[() => A]): List[A] =
    if tasks.isEmpty then Nil
    else
      val pool = Executors.newFixedThreadPool(math.min(tasks.size, Host.MaxParallel), Host.parallelThreads)
      try
        val futures = tasks.toList.map(task => pool.submit[A](() => task()))
        val outcomes = futures.map(future => Try(future.get()))
        val failures = outcomes.collect:
          case Failure(wrapped: ExecutionException) => wrapped.getCause.nn
          case Failure(other) => other
        failures.find(!NonFatal(_)).orElse(failures.headOption).foreach(throw _)
        outcomes.collect { case Success(value) => value }
      finally pool.shutdownNow()

object Host:
  /** How many tasks `parallel` runs at once. */
  val MaxParallel: Int = 8
  /** `cat(path)` shows at most this many lines, then says how to see the rest. */
  val CatMaxLines: Int = 400
  /** `cat` cuts a line beyond this many characters (minified files) with a marker. */
  val CatMaxLineChars: Int = 2000
  /** How much of a file `readRange` reads before giving up on finding the requested lines. */
  val ReadRangeMaxChars: Long = 2000000L
  /** How much of a file `cat` reads to count the lines after the shown window: a giant
    * file must not cost the whole snippet timeout for 400 lines of output. */
  val CatMaxReadChars: Long = 64000000L
  /** How much stderr `execOutput` quotes when a command fails. */
  val ExecErrorTailChars: Int = 2000
  /** Live `spawn`ed processes per session; beyond it `spawn` asks to `kill()` one. */
  val MaxProcesses: Int = 8
  /** How much of an error body `httpGet`/`httpPost` quote. */
  val HttpErrorBodyChars: Int = 500
  /** Largest HTTP response body retained in memory. */
  val HttpMaxResponseBytes: Int = 8 * 1024 * 1024

  private val parallelThreadCount = AtomicInteger()
  private val parallelThreads: ThreadFactory = runnable =>
    val thread = Thread(runnable, s"atc-parallel-${parallelThreadCount.incrementAndGet()}")
    thread.setDaemon(true)
    thread
