package atc.perms

import java.io.File
import java.nio.file.{FileSystems, Files, Path, PathMatcher, Paths}
import java.util.regex.Pattern

/** A path pattern from the configuration, gitignore-flavoured (as in TACIT):
  *
  *  - no `/` in the pattern: matches any path *component* by glob
  *    (`.env`, `.env.*`, `node_modules`, `*.pem`)
  *  - relative pattern with `/`: relative to `base` (the working directory),
  *    supports `*`, `**`, `?`, `[…]` (`src/main`, `build/[a-z]*`)
  *  - absolute pattern (or starting with `~`): matched against the absolute
  *    path (`/etc`, `~/notes`, `~/proj/x/build`)
  *  - `.` means `base` itself
  *  - a trailing `/` is stripped
  *
  * A pattern matches a path if it matches the path itself or any ancestor,
  * so a rule on a directory covers its whole subtree.
  */
final class PathPattern private (val raw: String, private val kind: PathPattern.Kind):
  import PathPattern.*

  def matches(p: Path): Boolean = kind match
    case Kind.Component(m) => (0 until p.getNameCount).exists(i => m.matches(p.getName(i)))
    // matched against the path relative to the anchor root
    case Kind.Anchored(root, matchers) =>
      p.startsWith(root) && {
        val relative = if p == root then "" else portable(root.relativize(p))
        matchers.exists(_.matcher(relative).matches())
      }
    case Kind.Exact(path) => p == path || p.startsWith(path)

  override def toString: String = raw

object PathPattern:
  private enum Kind:
    case Component(m: PathMatcher)
    /** `root` is an absolute, real path; matchers are applied to the path relative to it. */
    case Anchored(root: Path, matchers: List[Pattern])
    case Exact(path: Path)

  private val globChars = "*?[{"
  def apply(pattern: String, base: Path): PathPattern =
    val untrimmed = expandHome(pattern.trim)
    val windows = File.separatorChar == '\\'
    val isRoot = untrimmed == "/" || (windows && (untrimmed == "\\" || untrimmed.matches("(?i)^[a-z]:[\\\\/]$")))
    val expanded =
      if isRoot then untrimmed
      else untrimmed.reverse.dropWhile(c => c == '/' || (windows && c == '\\')).reverse
    val stripped = if expanded.isEmpty then "." else expanded
    if windows && stripped.replace('\\', '/').matches("(?i)^[a-z]:(?:$|[^/].*)") then
      throw IllegalArgumentException(
        "invalid Windows path: drive-relative paths such as 'C:work' are ambiguous; use 'C:/work'"
      )
    if stripped == "." then new PathPattern(pattern, Kind.Exact(canonical(base)))
    else if !stripped.exists(c => c == '/' || (windows && c == '\\')) then
      if windows && !globChars.exists(stripped.contains(_)) then
        invalidWindowsPath(stripped).foreach(reason => throw IllegalArgumentException(s"invalid Windows path: $reason"))
      new PathPattern(pattern, Kind.Component(FileSystems.getDefault.getPathMatcher(s"glob:$stripped")))
    else
      // Windows refuses `*`, `?` and several other glob characters in a Path,
      // so never hand the glob-bearing suffix to Paths.get. Split it as text,
      // parse only the literal prefix, and match the remainder in a stable
      // slash-separated form on every platform.
      val normalized = if windows then stripped.replace('\\', '/') else stripped
      val (prefix, rest) = splitGlob(normalized)
      if windows then
        invalidWindowsPath(prefix).foreach(reason => throw IllegalArgumentException(s"invalid Windows path: $reason"))
      val literal = Paths.get(prefix.replace('/', File.separatorChar)).nn
      val root = if literal.isAbsolute then literal else base.resolve(literal).nn
      if rest.isEmpty then new PathPattern(pattern, Kind.Exact(canonical(root)))
      else new PathPattern(pattern, Kind.Anchored(canonical(root), globOrDescendantMatchers(rest)))

  /** `~` / `~/x` resolved against the home directory (also used by `Host` for agent-supplied paths). */
  def expandHome(p: String): String =
    if p == "~" then scala.util.Properties.userHome
    else if p.startsWith("~/") || p.startsWith("~\\") then
      val relative = p.drop(2).map(c => if c == '/' || c == '\\' then File.separatorChar else c)
      Paths.get(scala.util.Properties.userHome).resolve(relative).toString
    else p

  /** Split a slash-normalized pattern before the first glob-bearing component.
    * The returned prefix contains no characters Windows rejects in a Path. */
  private def splitGlob(value: String): (String, String) =
    value.indexWhere(globChars.contains) match
      case -1 => (value, "")
      case firstGlob =>
        val boundary = value.lastIndexOf('/', firstGlob)
        if boundary < 0 then ("", value)
        else (value.substring(0, boundary + 1), value.substring(boundary + 1))

  private def globOrDescendantMatchers(glob: String): List[Pattern] =
    def variants(g: String): List[String] = if g.startsWith("**/") then g :: variants(g.stripPrefix("**/")) else List(g)
    variants(glob).flatMap(g => List(globPattern(g), globPattern(s"$g/**")))

  /** Gitignore-style path glob. PathPattern syntax is slash-based even on
    * Windows, where matching is case-insensitive like the file system. */
  private def globPattern(glob: String): Pattern =
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
          case other => result.append(Pattern.quote(other.toString))
        index += 1
    result.append('$')
    Pattern.compile(result.toString, if File.separatorChar == '\\' then Pattern.CASE_INSENSITIVE else 0)

  /** Stable path text exposed to agent code. Forward slashes are accepted by
    * the Windows file APIs and, unlike backslashes, are safe to copy into a
    * Scala string literal. */
  def portable(path: Path): String =
    val native = path.toString
    if File.separatorChar == '\\' then native.replace('\\', '/') else native

  /** Why a path is unsafe under Win32 name resolution. Kept string-based so
    * callers can reject device namespaces before java.nio touches them. */
  private[atc] def invalidWindowsPath(value: String): Option[String] =
    val normalized = value.replace('/', '\\')
    val lower = normalized.toLowerCase(java.util.Locale.ROOT)
    if lower.startsWith("\\\\.\\") || lower.startsWith("\\\\?\\") || lower.startsWith("\\??\\") then
      Some("Win32 device namespaces are not allowed")
    else if normalized.matches("(?i)^[a-z]:(?:$|[^\\\\].*)") then
      Some("drive-relative paths such as 'C:work' are ambiguous; use 'C:/work'")
    else
      normalized.split("\\\\+", -1).iterator
        .filterNot(component =>
          component.isEmpty || component == "." || component == ".." || component.matches("(?i)[a-z]:")
        )
        .flatMap { component =>
          if component.contains(':') then Some("alternate data streams are not allowed")
          else if component.endsWith(".") || component.endsWith(" ") then
            Some("path components ending in a dot or space are not allowed")
          else
            val stem = component.takeWhile(_ != '.').reverse.dropWhile(c => c == '.' || c == ' ').reverse
              .toUpperCase(java.util.Locale.ROOT)
            val reserved =
              Set("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$", "CLOCK$").contains(stem) ||
                stem.matches("COM[1-9¹²³]") || stem.matches("LPT[1-9¹²³]")
            Option.when(reserved)(s"'$component' is a reserved Windows device name")
        }
        .nextOption()

  /** Convert a path to absolute normalized form and resolve symlinks as far as
    * possible. Resolve dangling links as well because writing through one creates
    * its target, which is the path the policy must evaluate. */
  def canonical(p: Path): Path =
    val abs = p.toAbsolutePath.normalize
    realPathOfNearestAncestor(abs, MaxLinkDepth)

  /** Maximum symlink-chain depth, analogous to the kernel's ELOOP threshold. */
  private val MaxLinkDepth = 40

  private def realPathOfNearestAncestor(abs: Path, depth: Int): Path =
    if depth <= 0 then abs // a symlink loop: judge the path literally
    else if Files.isSymbolicLink(abs) then // NOFOLLOW: true for dangling links too
      try
        val target = Files.readSymbolicLink(abs).nn
        val resolved = if target.isAbsolute then target else abs.getParent.resolve(target).nn
        realPathOfNearestAncestor(resolved.toAbsolutePath.normalize, depth - 1)
      catch case _: java.io.IOException => abs
    else if Files.exists(abs) then
      try abs.toRealPath()
      catch case _: java.io.IOException => abs
    else
      val parent = abs.getParent
      val name = abs.getFileName
      if parent != null && name != null then realPathOfNearestAncestor(parent, depth).resolve(name) else abs
