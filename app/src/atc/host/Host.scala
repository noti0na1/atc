package atc.host

import atc.lib.*
import atc.perms.{GitIgnore, Policy, ScopeId}

import java.nio.file.Path
import scala.util.Try

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

  /** Normalize a host for policy matching: lowercase, remove a trailing dot, and
    * convert numeric IP literals to canonical form. IPv4 and IPv4-mapped IPv6
    * addresses use dotted-quad notation. This ensures that alternate forms such
    * as `evil.com.`, `2852039166`, and `[::ffff:169.254.169.254]` cannot bypass an
    * equivalent rule. Literal parsing does not use DNS; ordinary hostnames are
    * returned unchanged after case and trailing-dot normalization. */
  def normalizeHost(host: String): String =
    val normalized = host.stripSuffix(".").toLowerCase
    // URI.getHost returns bracketed IPv6 literals. Canonicalize them so an
    // IPv4-mapped spelling cannot bypass a rule for the IPv4 address.
    val (bare, bracketed) =
      if normalized.startsWith("[") && normalized.endsWith("]") then
        (normalized.substring(1, normalized.length - 1), true)
      else (normalized, false)
    literalIpAddress(bare)
      .orElse(if bracketed || looksLikeIpv6(bare) then ipv6Literal(bare) else None)
      .getOrElse(bare)

  /** Whether a string contains only IPv6-literal characters. */
  private def looksLikeIpv6(value: String): Boolean =
    value.contains(':') && value.forall { char =>
      char == ':' || char == '.' || (char >= '0' && char <= '9') || (char >= 'a' && char <= 'f')
    }

  /** Canonicalize an IPv6 literal without resolving ordinary hostnames. */
  private def ipv6Literal(value: String): Option[String] =
    try
      java.net.InetAddress.getByName(value) match
        case address: java.net.Inet4Address => Some(address.getHostAddress.nn)
        case address: java.net.Inet6Address => Some(address.getHostAddress.nn)
        case _ => None
    catch case _: java.net.UnknownHostException => None

  /** Convert a numeric IPv4 literal with one to four decimal parts into
    * canonical dotted-quad form without a DNS lookup. */
  private[host] def literalIpAddress(value: String): Option[String] =
    val parts = value.split("\\.", -1).toList

    def partValue(part: String): Option[Long] =
      if part.nonEmpty && part.forall(_.isDigit) then
        try Some(java.lang.Long.parseLong(part, 10))
        catch case _: NumberFormatException => None
      else None

    def parseParts: Option[List[Long]] =
      parts.foldRight(Option(List.empty[Long])) { (part, parsed) =>
        for
          number <- partValue(part)
          tail <- parsed
        yield number :: tail
      }

    if parts.isEmpty || parts.lengthIs > 4 then None
    else
      for
        values <- parseParts
        lastMax = 1L << (8 * (5 - values.length))
        if values.init.forall(_ <= 255) && values.last < lastMax
        address = values.init.zipWithIndex.foldLeft(values.last) { case (current, (part, index)) =>
          current | (part << (8 * (3 - index)))
        }
        bytes = Array.tabulate(4)(index => ((address >> (8 * (3 - index))) & 0xff).toByte)
        canonical <- Try(java.net.InetAddress.getByAddress(bytes).nn.getHostAddress.nn).toOption
      yield canonical

  /** Gitignore-style glob over a `/`-separated relative path. */
  def globRegex(glob: String): scala.util.matching.Regex =
    val result = StringBuilder("^")
    var index = 0
    var inClass = false
    var inBraces = false
    while index < glob.length do
      val char = glob.charAt(index)
      if inClass then
        if char == ']' then inClass = false
        result.append(char)
        index += 1
      else if glob.startsWith("**/", index) then
        result.append("(?:.*/)?")
        index += 3
      else if glob.startsWith("**", index) then
        result.append(".*")
        index += 2
      else
        char match
          case '*' => result.append("[^/]*")
          case '?' => result.append("[^/]")
          case '[' =>
            inClass = true
            result.append('[')
            if glob.startsWith("[!", index) then
              result.append('^')
              index += 1
          case '{' =>
            inBraces = true
            result.append("(?:")
          case '}' if inBraces =>
            inBraces = false
            result.append(')')
          case ',' if inBraces => result.append('|')
          case other => result.append(java.util.regex.Pattern.quote(other.toString))
        index += 1
    result.append('$')
    result.toString.r

  /** Split file content while retaining its separator and trailing-newline state. */
  def splitLines(content: String): (List[String], String, Boolean) =
    val separator = if content.contains("\r\n") then "\r\n" else "\n"
    if content.isEmpty then (Nil, separator, true)
    else
      val trailing = content.endsWith(separator) || content.endsWith("\n")
      val body =
        if content.endsWith(separator) then content.dropRight(separator.length)
        else content.stripSuffix("\n")
      (body.split(java.util.regex.Pattern.quote(separator), -1).toList, separator, trailing)

  /** Lines contributed by an edit; a final newline is not an extra empty line. */
  def textLines(text: String): List[String] =
    if text.isEmpty then Nil else text.stripSuffix("\n").split("\n", -1).toList

  def joinLines(lines: List[String], separator: String, trailing: Boolean): String =
    if lines.isEmpty then "" else lines.mkString(separator) + (if trailing then separator else "")
