package atc.host

import atc.lib.*
import atc.perms.{GitIgnore, GlobMatcher, Policy, ScopeId}

import java.io.File
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
  private[atc] val Windows: Boolean = File.separatorChar == '\\'

  /** Stable path text given to agent code. Windows accepts forward slashes,
    * and they can be copied into ordinary Scala string literals safely. */
  private[atc] def portablePath(path: Path): String = atc.perms.PathPattern.portable(path)

  /** Quote generated Scala source (permission hints and test snippets). */
  private[atc] def scalaString(value: String): String =
    val result = StringBuilder("\"")
    value.foreach:
      case '"' => result.append("\\\"")
      case '\\' => result.append("\\\\")
      case '\n' => result.append("\\n")
      case '\r' => result.append("\\r")
      case '\t' => result.append("\\t")
      case '\b' => result.append("\\b")
      case '\f' => result.append("\\f")
      case char if Character.isISOControl(char) => result.append(f"\\u${char.toInt}%04x")
      case char => result.append(char)
    result.append('"').toString

  /** Why a Win32 path is unsafe to pass to the file APIs. DOS device names
    * resolve in every directory, and trailing dots/spaces alias another name;
    * either could make a lexical in-project path reach something else. */
  private[atc] def invalidWindowsPath(value: String): Option[String] =
    atc.perms.PathPattern.invalidWindowsPath(value)

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
    ((if Windows then "(?i)" else "") + result.toString).r

  /** Split file content on LF, CRLF, or bare CR while retaining a stable output
    * separator (the first one present) and trailing-newline state. Mixed-newline
    * input is normalized to that first separator when an edit is written back. */
  def splitLines(content: String): (List[String], String, Boolean) =
    if content.isEmpty then (Nil, "\n", true)
    else
      val lines = collection.mutable.ListBuffer[String]()
      var separator: String | Null = null
      var start = 0
      var index = 0
      while index < content.length do
        val width =
          content.charAt(index) match
            case '\r' if index + 1 < content.length && content.charAt(index + 1) == '\n' => 2
            case '\r' | '\n' => 1
            case _ => 0
        if width == 0 then index += 1
        else
          if separator == null then separator = content.substring(index, index + width)
          lines += content.substring(start, index)
          index += width
          start = index
      val trailing = start == content.length
      if !trailing then lines += content.substring(start)
      (lines.toList, Option(separator).getOrElse("\n"), trailing)

  /** Lines contributed by an edit; a final newline is not an extra empty line. */
  def textLines(text: String): List[String] =
    splitLines(text)._1

  def joinLines(lines: List[String], separator: String, trailing: Boolean): String =
    if lines.isEmpty then "" else lines.mkString(separator) + (if trailing then separator else "")
