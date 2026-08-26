package atc.perms

import atc.platform.{PathGlob, PlatformPath}

import java.nio.file.{FileSystems, Path, PathMatcher, Paths}
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
        val relative = if p == root then "" else PlatformPath.portable(root.relativize(p))
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
    val untrimmed = PlatformPath.expandHome(pattern.trim)
    val isRoot = PlatformPath.isRoot(untrimmed)
    val expanded =
      if isRoot then untrimmed
      else PlatformPath.stripTrailingSeparators(untrimmed)
    val stripped = if expanded.isEmpty then "." else expanded
    PlatformPath.driveRelativeValidationError(stripped).foreach(reason =>
      throw IllegalArgumentException(s"invalid Windows path: $reason")
    )
    if stripped == "." then new PathPattern(pattern, Kind.Exact(PlatformPath.canonical(base)))
    else if !PlatformPath.hasSeparator(stripped) then
      if !globChars.exists(stripped.contains(_)) then
        PlatformPath.validationError(stripped).foreach(reason =>
          throw IllegalArgumentException(s"invalid Windows path: $reason")
        )
      new PathPattern(pattern, Kind.Component(FileSystems.getDefault.getPathMatcher(s"glob:$stripped")))
    else
      // Windows refuses `*`, `?` and several other glob characters in a Path,
      // so never hand the glob-bearing suffix to Paths.get. Split it as text,
      // parse only the literal prefix, and match the remainder in a stable
      // slash-separated form on every platform.
      val normalized = PlatformPath.slashSeparated(stripped)
      val (prefix, rest) = splitGlob(normalized)
      PlatformPath.validationError(prefix).foreach(reason =>
        throw IllegalArgumentException(s"invalid Windows path: $reason")
      )
      val literal = Paths.get(PlatformPath.native(prefix)).nn
      val root = if literal.isAbsolute then literal else base.resolve(literal).nn
      if rest.isEmpty then new PathPattern(pattern, Kind.Exact(PlatformPath.canonical(root)))
      else new PathPattern(pattern, Kind.Anchored(PlatformPath.canonical(root), globOrDescendantMatchers(rest)))

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
  private def globPattern(glob: String): Pattern = PathGlob.pattern(glob)
