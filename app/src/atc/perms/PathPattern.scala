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
    case Kind.Component(m) => (0 until p.getNameCount).exists(i => m.matches(p.getName(i)))
    // matched against the path relative to the anchor root
    case Kind.Anchored(root, matchers) =>
      p.startsWith(root) && matchers.exists(_.matches(if p == root then EmptyPath else root.relativize(p)))
    case Kind.Exact(path) => p == path || p.startsWith(path)

  override def toString: String = raw

object PathPattern:
  private enum Kind:
    case Component(m: PathMatcher)
    /** `root` is an absolute, real path; matchers are applied to the path relative to it. */
    case Anchored(root: Path, matchers: List[PathMatcher])
    case Exact(path: Path)

  private val globChars = "*?[{"
  private val EmptyPath = Paths.get("")

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

  /** `~` / `~/x` resolved against the home directory (also used by `Host` for agent-supplied paths). */
  def expandHome(p: String): String =
    if p == "~" || p.startsWith("~/") then scala.util.Properties.userHome + p.drop(1) else p

  /** Split an absolute path into its longest glob-free prefix and the rest. */
  private def splitGlob(abs: Path): (Path, String) =
    val n = abs.getNameCount
    val firstGlob = (0 until n).find(i => globChars.exists(abs.getName(i).toString.contains(_))).getOrElse(n)
    val root = if firstGlob == 0 then abs.getRoot else abs.getRoot.resolve(abs.subpath(0, firstGlob))
    val rest = if firstGlob == n then "" else abs.subpath(firstGlob, n).toString
    (root, rest)

  private def globOrDescendantMatchers(glob: String): List[PathMatcher] =
    val fs = FileSystems.getDefault
    def variants(g: String): List[String] = if g.startsWith("**/") then g :: variants(g.stripPrefix("**/")) else List(g)
    variants(glob).flatMap(g => List(fs.getPathMatcher(s"glob:$g"), fs.getPathMatcher(s"glob:$g/**")))

  /** Absolute, normalized, symlink-resolved as far as the path exists. A symlink
    * is resolved even when its target does NOT exist (a dangling link): a write
    * through the link creates/writes the target, so the policy must judge the
    * target, not the link. */
  def canonical(p: Path): Path =
    val abs = p.toAbsolutePath.normalize
    realPathOfNearestAncestor(abs, MaxLinkDepth)

  /** Symlink-chain cap, like the kernel's ELOOP threshold. */
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
