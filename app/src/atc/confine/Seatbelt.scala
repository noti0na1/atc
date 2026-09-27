package atc.confine

import atc.confine.SandboxPlan.{Level, Restriction, Target}
import atc.platform.Platform

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import scala.util.control.NonFatal

/** macOS: commands run under `/usr/bin/sandbox-exec` with a profile generated from a
  * [[SandboxPlan]]. The profile denies by default. Rules match the real path on disk,
  * so symbolic links and differently cased spellings resolve before a rule applies;
  * glob rules become case-insensitive regular expressions, because the policy
  * matches names case-insensitively where the file system does. In a profile the
  * last matching rule wins: allows come first, then glob restrictions, then the
  * exemption of dependency directories from them, then exact restrictions. */
private[atc] object Seatbelt:
  val Executable: Path = Paths.get("/usr/bin/sandbox-exec").nn

  /** Whether `sandbox-exec` runs here. It fails inside another Seatbelt sandbox. */
  def available: Boolean =
    Files.isExecutable(Executable) &&
      (try
        val process = ProcessBuilder(Executable.toString, "-p", "(version 1)(allow default)", "/usr/bin/true")
          .redirectErrorStream(true).start().nn
        process.getInputStream.nn.readAllBytes()
        process.waitFor(10, TimeUnit.SECONDS) && process.exitValue == 0
      catch case NonFatal(_) => false)

  /** The command line prefix that runs a command under `plan`, with `tmp` as its private temporary directory. */
  def prefix(plan: SandboxPlan, tmp: Path): List[String] = List(Executable.toString, "-p", profile(plan, tmp))

  /** System directories every command may read. */
  private val SystemRoots = List(
    "/usr",
    "/bin",
    "/sbin",
    "/System",
    "/Library/Java",
    "/Library/Preferences",
    "/Library/Developer/CommandLineTools",
    "/Applications/Xcode.app",
    "/opt/homebrew",
    "/private/etc",
    "/private/var/select",
    "/private/var/db/timezone",
  )

  /** Directories whose code may be mapped executable, besides the system's. */
  private val ExecutableRoots = List("/opt/homebrew", "/Library/Developer/CommandLineTools", "/Applications/Xcode.app")

  /** Lock files the sbt launcher writes into its otherwise read-only home directories. */
  private val SbtLocks = List(
    ".sbt/boot/sbt.boot.lock",
    ".ivy2/.sbt.ivy.lock",
    ".ivy2/exclude_classifiers",
    ".ivy2/exclude_classifiers.lock"
  )

  /** Directory names whose files are exempt from glob restrictions: installed
    * dependencies ship certificate bundles and test keys that `*.pem`-style rules
    * would otherwise hide. */
  val DependencyDirs: List[String] = List(".venv", "venv", "node_modules", "site-packages")

  def profile(plan: SandboxPlan, tmp: Path): String =
    val readRoots = plan.readable ++ plan.writable ++ List(tmp, plan.cache)
    val writeRoots = plan.writable ++ List(tmp, plan.cache)
    val (globs, exact) = plan.restrictions.partition(_.target match
      case Target.Exact(_) => false
      case _ => true)
    val lines = List.newBuilder[String]
    lines += "(version 1)"
    lines += "(deny default)"
    lines += "(import \"system.sb\")"
    lines += "(allow process-fork process-exec)"
    lines += "(allow signal (target same-sandbox))"
    lines += "(allow process-info* (target same-sandbox))"
    lines += "(allow process-info-pidinfo process-info-setcontrol process-info-dirtycontrol process-info-codesignature)"
    lines += "(allow sysctl-read system-info ipc-posix-sem ipc-posix-shm user-preference-read pseudo-tty)"
    // stat() everywhere: getcwd, realpath and canonicalization need it; content stays restricted.
    lines += "(allow file-read-metadata file-test-existence)"
    lines += "(allow file-read* file-write-data file-ioctl (literal \"/dev/tty\") (regex #\"^/dev/ttys[0-9]+$\") " +
      "(literal \"/dev/ptmx\") (literal \"/dev/null\") (literal \"/dev/zero\") (literal \"/dev/random\") (literal \"/dev/urandom\"))"
    lines += s"(allow file-read* ${SystemRoots.map(r => subpath(Paths.get(r).nn)).mkString(" ")})"
    lines += s"(allow file-read* ${readRoots.map(subpath).mkString(" ")})"
    lines +=
      s"(allow file-map-executable ${(plan.readable ++ writeRoots ++ ExecutableRoots.map(Paths.get(_).nn)).map(subpath).mkString(" ")})"
    // configd: JVM network interface queries; FSEvents: file watchers. Launch Services, the
    // pasteboard and the keychain stay denied: `open` would start programs outside the sandbox.
    val mach = List("com.apple.SystemConfiguration.configd", "com.apple.FSEvents") ++
      Option.when(plan.network)("com.apple.trustd.agent")
    lines += s"(allow mach-lookup ${mach.map(name => s"(global-name ${string(name)})").mkString(" ")})"
    val locks = SbtLocks.map(name => literal(plan.home.resolve(name).nn))
    lines += s"(allow file-write* ${(writeRoots.map(subpath) ++ locks).mkString(" ")} " +
      "(literal \"/dev/null\") (literal \"/dev/zero\") (literal \"/dev/tty\") (regex #\"^/dev/ttys[0-9]+$\") " +
      "(literal \"/dev/ptmx\") (literal \"/dev/dtracehelper\") (subpath \"/dev/fd\"))"
    globs.foreach(r => lines += deny(r.level, filter(r.target, plan)))
    if globs.nonEmpty then
      val dependencies = s"(${DependencyDirs.map(escapeRegex).mkString("|")})"
      lines += s"(allow file-read-data ${readRoots.map(root =>
          regex(s"^${escapeRegex(root.toString)}/(.*/)?$dependencies(/.*)?$$")
        ).mkString(" ")})"
      lines += s"(allow file-write* ${writeRoots.map(root =>
          regex(s"^${escapeRegex(root.toString)}/(.*/)?$dependencies(/.*)?$$")
        ).mkString(" ")})"
    exact.foreach(r => lines += deny(r.level, filter(r.target, plan)))
    // A protected path inside a writable parent could be swapped out by renaming the parent.
    for case Restriction(Level.ReadOnly, Target.Exact(path)) <- exact; parent <- protectedParents(path, plan) do
      lines += s"(deny file-write-unlink ${literal(parent)})"
    if plan.network then
      lines += "(allow network*)"
      // No Unix sockets and no loopback: a daemon outside the sandbox (a build server, an
      // IDE, a credential agent) acts with the user's full authority.
      lines += "(deny network* (local unix) (remote unix))"
      lines += "(deny network-outbound (remote ip \"localhost:*\"))"
      lines += "(allow network-outbound (remote unix-socket (path-literal \"/private/var/run/mDNSResponder\")))"
    else lines += "(deny network*)"
    lines.result().mkString("\n")

  private def deny(level: Level, filter: String): String = level match
    case Level.Hidden => s"(deny file-read* file-write* $filter)"
    case Level.Secret => s"(deny file-read-data file-write* $filter)"
    case Level.ReadOnly => s"(deny file-write* $filter)"

  private def filter(target: Target, plan: SandboxPlan): String = target match
    case Target.Exact(path) => subpath(path)
    case Target.Anchored(root, glob) => regex(s"^${escapeRegex(root.toString)}/${globRegex(glob)}(/.*)?$$")
    case Target.Component(glob) => regex(s"/${globRegex(glob)}(/.*)?$$")

  /** Directories between a writable root and `path` that a rename could swap out. */
  private def protectedParents(path: Path, plan: SandboxPlan): List[Path] =
    plan.writable.filter(root => path.startsWith(root) && path != root).flatMap: root =>
      Iterator.iterate(path.getParent)(p => if p == null then null else p.getParent)
        .takeWhile(p => p != null && p != root && p.startsWith(root)).map(_.nn).toList

  /** A glob as a POSIX extended regular expression for one or more names. */
  def globRegex(glob: String): String =
    val out = StringBuilder()
    var i = 0
    var inClass = false
    var braces = 0
    while i < glob.length do
      val c = glob.charAt(i)
      if inClass then
        if c == ']' then inClass = false
        out.append(c)
        i += 1
      else if glob.startsWith("**/", i) then
        out.append("(.*/)?")
        i += 3
      else if glob.startsWith("**", i) then
        out.append(".*")
        i += 2
      else
        c match
          case '*' => out.append("[^/]*")
          case '?' => out.append("[^/]")
          case '[' =>
            inClass = true
            out.append('[')
            if glob.startsWith("[!", i) then
              out.append('^')
              i += 1
          case '{' =>
            braces += 1
            out.append('(')
          case '}' if braces > 0 =>
            braces -= 1
            out.append(')')
          case ',' if braces > 0 => out.append('|')
          case other => out.append(caseless(other))
        i += 1
    out.toString

  /** A character matched regardless of case where file names are, escaped as needed. */
  private def caseless(c: Char): String =
    if Platform.caseInsensitivePaths && c.isLetter && c.toLower != c.toUpper then s"[${c.toLower}${c.toUpper}]"
    else escapeRegex(c.toString)

  private def escapeRegex(text: String): String =
    text.flatMap(c => if "\\^$.|?*+()[]{}".contains(c) then s"\\$c" else c.toString)

  private def subpath(path: Path): String = s"(subpath ${string(path.toString)})"
  private def literal(path: Path): String = s"(literal ${string(path.toString)})"
  private def regex(pattern: String): String = s"(regex ${string(pattern)})"
  private def string(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
