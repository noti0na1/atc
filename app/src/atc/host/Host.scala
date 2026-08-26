package atc.host

import atc.lib.*
import atc.perms.{GitIgnore, GlobMatcher, Policy, ScopeId}

import java.nio.file.Path

/** Stable façade for the agent-facing API. Cohesive implementation modules
  * provide filesystem, process, network, and interaction operations, while this
  * class owns their shared capabilities and permission-scope lifecycle. */
final class Host(
  val policy: Policy,
  val cwd: Path,
  private[host] val output: HostOutput,
  private[host] val llm: HostLlm,
  private[host] val ui: HostUi,
  /** Paths git ignores are left out of listings (config `respectGitignore`). */
  private[host] val gitIgnore: GitIgnore = GitIgnore.Disabled,
) extends Interface, Derivations, HostFiles, HostProcesses, HostNetwork, HostInteraction:

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

object Host:
  /** `cat(path)` shows at most this many lines, then says how to see the rest. */
  val CatMaxLines: Int = 400
  /** `cat` cuts a line beyond this many characters (minified files) with a marker. */
  val CatMaxLineChars: Int = 2000
  /** How much stderr `execOutput` quotes when a command fails. */
  val ExecErrorTailChars: Int = 2000
  /** Live `spawn`ed processes per session; beyond it `spawn` asks to `kill()` one. */
  val MaxProcesses: Int = 8
  /** How much of an error body `httpGet`/`httpPost` quote. */
  val HttpErrorBodyChars: Int = 500
  /** Largest HTTP response body retained in memory. */
  val HttpMaxResponseBytes: Int = 8 * 1024 * 1024

  /** Normalize a host for policy matching: lowercase, remove a trailing dot, and
    * convert numeric IP literals to canonical form. IPv4 and IPv4-mapped IPv6
    * addresses use dotted-quad notation. This ensures that alternate forms such
    * as `evil.com.`, `2852039166`, and `[::ffff:169.254.169.254]` cannot bypass an
    * equivalent rule. Literal parsing does not use DNS; ordinary hostnames are
    * returned unchanged after case and trailing-dot normalization. */
  def normalizeHost(host: String): String =
    GlobMatcher.normalizeHost(host)

  /** Convert a numeric IPv4 literal with one to four decimal parts into
    * canonical dotted-quad form without a DNS lookup. */
  private[host] def literalIpAddress(value: String): Option[String] =
    GlobMatcher.literalIpAddress(value)
