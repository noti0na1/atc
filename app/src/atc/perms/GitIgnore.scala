package atc.perms

import atc.platform.{Platform, PlatformPath}

import java.nio.file.{Files, Path}
import scala.util.matching.Regex
import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*

/** Whether a path is hidden from directory listings because git ignores it
  * (config `respectGitignore`). This is *visibility*, not permission: an
  * ignored path is left out of `ls`/`walk`/`find`/`grepRecursive`, but reading
  * or writing it by name still works if the policy allows it.
  */
trait GitIgnore:
  /** True if `p` (absolute) is ignored. */
  def ignores(p: Path): Boolean

object GitIgnore:
  /** Ignores nothing (`"respectGitignore": false`). */
  val Disabled: GitIgnore = _ => false

  /** Reads the `.gitignore` files of the repository containing `cwd` (nested
    * ones included, deeper files taking precedence), and always ignores `.git`.
    * Files are read on first use and then cached for the session. */
  def apply(cwd: Path): GitIgnore = Rules(repoRoot(cwd))

  /** The nearest ancestor of `dir` holding a `.git` entry, `dir` itself if
    * there is none: a `.gitignore` above the working directory still applies. */
  def repoRoot(dir: Path): Path =
    def up(p: Path | Null): Option[Path] = p match
      case null => None
      case d: Path => if Files.exists(d.resolve(".git")) then Some(d) else up(d.getParent)
    up(dir.toAbsolutePath.nn.normalize).getOrElse(dir)

  private final class Rules(root: Path) extends GitIgnore:
    private val cache = TrieMap[Path, List[Rule]]()

    private def rulesOf(dir: Path): List[Rule] = cache.getOrElseUpdate(dir, load(dir))

    private def load(dir: Path): List[Rule] =
      val file = dir.resolve(".gitignore").nn
      if !Files.isRegularFile(file) then Nil
      else
        try
          Files.readAllLines(file).nn.asScala.toList.flatMap { line =>
            // A malformed pattern (e.g. an unbalanced character class makes an
            // invalid regex) disables just its line, as git does — not the file.
            try Rule.parse(line)
            catch case _: Exception => None
          }
        catch case _: Exception => Nil // unreadable or not text: no rules from it

    /** Component by component, as git decides it: a path is ignored as soon as
      * one of its ancestors is, so a negation inside an ignored directory does
      * not bring it back. For one component, the deepest `.gitignore` that
      * matches wins, and within a file the last matching rule wins. */
    def ignores(p: Path): Boolean =
      if p == root || !p.startsWith(root) then false
      else
        val rel = root.relativize(p).nn
        val n = rel.getNameCount
        var ignored = false
        var i = 0
        while i < n && !ignored do
          if Platform.samePathName(rel.getName(i).nn.toString, ".git") then ignored = true
          else
            val isDir = i < n - 1 || Files.isDirectory(root.resolve(rel.subpath(0, i + 1)))
            var j = 0
            while j <= i do
              val dir = if j == 0 then root else root.resolve(rel.subpath(0, j)).nn
              val name = slashes(rel.subpath(j, i + 1).nn)
              for r <- rulesOf(dir) if r.matches(name, isDir) do ignored = !r.negated
              j += 1
          i += 1
        ignored

    private def slashes(p: Path): String = PlatformPath.portable(p)

  /** One `.gitignore` line, compiled against paths relative to its own directory. */
  private final class Rule(val negated: Boolean, dirOnly: Boolean, regex: Regex):
    def matches(rel: String, isDir: Boolean): Boolean =
      (isDir || !dirOnly) && regex.matches(rel)

  private object Rule:
    /** `None` for blank lines and comments. */
    def parse(line: String): Option[Rule] =
      val text = trimTrailing(line)
      if text.isEmpty || text.startsWith("#") then None
      else
        val negated = text.startsWith("!")
        val body = unescapeLead(if negated then text.drop(1) else text)
        val dirOnly = body.endsWith("/")
        val pattern = body.stripSuffix("/")
        if pattern.isEmpty then None
        else
          // A `/` anywhere but at the end anchors the pattern to this directory;
          // otherwise it matches a path component at any depth below it.
          val core = pattern.stripPrefix("/")
          val anchored = pattern.startsWith("/") || core.contains('/')
          val prefix = if anchored then "" else "(?:.*/)?"
          Some(Rule(negated, dirOnly, Regex((if Platform.isWindows then "(?i)" else "") + prefix + toRegex(core))))

    /** Trailing spaces are not part of the pattern unless backslash-escaped. */
    private def trimTrailing(line: String): String =
      var end = line.length
      while end > 0 && line.charAt(end - 1) == ' ' && !(end >= 2 && line.charAt(end - 2) == '\\') do end -= 1
      line.substring(0, end).nn

    /** `\#` / `\!` start a literal `#` / `!`. */
    private def unescapeLead(p: String): String =
      if p.startsWith("\\#") || p.startsWith("\\!") then p.drop(1) else p

    /** Git glob → regex over a `/`-separated relative path: `*` and `?` stop at
      * a separator, `**` crosses them, `[…]` is a character class. */
    private def toRegex(glob: String): String =
      val out = StringBuilder()
      var i = 0
      while i < glob.length do
        glob.charAt(i) match
          case '*' if i + 1 < glob.length && glob.charAt(i + 1) == '*' =>
            val atSegmentStart = i == 0 || glob.charAt(i - 1) == '/'
            if atSegmentStart && i + 2 < glob.length && glob.charAt(i + 2) == '/' then
              out ++= "(?:.*/)?" // `**/` — zero or more directories
              i += 3
            else
              out ++= ".*" // `/**` at the end, or `**` inside a name
              i += 2
          case '*' =>
            out ++= "[^/]*"
            i += 1
          case '?' =>
            out ++= "[^/]"
            i += 1
          case '[' =>
            val end = glob.indexOf(']', i + 1)
            if end < 0 then
              out ++= "\\["
              i += 1
            else
              val cls = glob.substring(i, end + 1).nn
              out ++= (if cls.startsWith("[!") then "[^" + cls.substring(2) else cls)
              i = end + 1
          case '\\' if i + 1 < glob.length =>
            out ++= Regex.quote(glob.charAt(i + 1).toString)
            i += 2
          case c =>
            out ++= Regex.quote(c.toString)
            i += 1
      out.toString
