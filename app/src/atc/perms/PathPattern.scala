package atc.perms

import java.nio.file.{FileSystems, Files, Path, PathMatcher, Paths}

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
    case Kind.Component(m) =>
      var i = 0
      var found = false
      val n = p.getNameCount
      while i < n && !found do
        if m.matches(p.getName(i)) then found = true
        i += 1
      found
    case Kind.Anchored(root, matchers) =>
      // match against the path relative to the anchor root
      if !p.startsWith(root) then false
      else if p == root then matchers.exists(_.matches(Paths.get("")))
      else
        val rel = root.relativize(p)
        matchers.exists(_.matches(rel))
    case Kind.Exact(path) => p == path || p.startsWith(path)

  override def toString: String = raw

object PathPattern:
  private enum Kind:
    case Component(m: PathMatcher)
    /** `root` is an absolute, real path; matchers are applied to the path relative to it. */
    case Anchored(root: Path, matchers: List[PathMatcher])
    case Exact(path: Path)

  private val globChars = "*?[{"

  def apply(pattern: String, base: Path): PathPattern =
    val expanded = expandHome(pattern.trim).stripSuffix("/")
    val stripped = if expanded.isEmpty then "." else expanded
    if stripped == "." then new PathPattern(pattern, Kind.Exact(canonical(base)))
    else if !stripped.contains('/') then
      new PathPattern(pattern, Kind.Component(FileSystems.getDefault.getPathMatcher(s"glob:$stripped")))
    else
      val abs = Paths.get(stripped)
      val (root, rest) =
        if abs.isAbsolute then splitGlob(abs)
        else splitGlob(base.resolve(stripped))
      if rest.isEmpty then new PathPattern(pattern, Kind.Exact(canonical(root)))
      else new PathPattern(pattern, Kind.Anchored(canonical(root), globOrDescendantMatchers(rest)))

  private def expandHome(p: String): String =
    if p == "~" || p.startsWith("~/") then System.getProperty("user.home") + p.drop(1) else p

  /** Split an absolute path into its longest glob-free prefix and the rest. */
  private def splitGlob(abs: Path): (Path, String) =
    val n = abs.getNameCount
    var firstGlob = n
    var i = 0
    while i < n && firstGlob == n do
      if globChars.exists(abs.getName(i).toString.contains(_)) then firstGlob = i
      i += 1
    val root = if firstGlob == 0 then abs.getRoot else abs.getRoot.resolve(abs.subpath(0, firstGlob))
    val rest = if firstGlob == n then "" else abs.subpath(firstGlob, n).toString
    (root, rest)

  private def globOrDescendantMatchers(glob: String): List[PathMatcher] =
    val fs = FileSystems.getDefault
    def variants(g: String): List[String] = if g.startsWith("**/") then g :: variants(g.stripPrefix("**/")) else List(g)
    variants(glob).flatMap(g => List(fs.getPathMatcher(s"glob:$g"), fs.getPathMatcher(s"glob:$g/**")))

  /** Absolute, normalized, symlink-resolved as far as the path exists. */
  def canonical(p: Path): Path =
    val abs = p.toAbsolutePath.normalize
    realPathOfNearestAncestor(abs)

  private def realPathOfNearestAncestor(abs: Path): Path =
    if Files.exists(abs) then
      try abs.toRealPath()
      catch case _: java.io.IOException => abs
    else
      val parent = abs.getParent
      val name = abs.getFileName
      if parent != null && name != null then realPathOfNearestAncestor(parent).resolve(name) else abs
