package atc.confine

import atc.confine.SandboxPlan.{Level, Restriction, Target}
import atc.platform.{PathGlob, PlatformPath}

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, LinkOption, Path, Paths, SimpleFileVisitor}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Linux: commands run under bubblewrap with new user, PID, IPC and (without network)
  * network namespaces. Only the system directories are mounted, read-only, so other
  * users' homes, `/srv`, `/mnt`, `/var` and the service sockets under `/run` stay out of
  * reach; `/tmp` and the home directory are empty file systems, and the plan's roots are
  * mounted over them, read-only or writable. Restrictions are mounted last: an empty read-only
  * file system over a hidden directory, `/dev/null` over a hidden file, and a read-only
  * mount over a read-only path. Mounts need existing paths, so a glob restriction is
  * applied to the matches that exist when the command starts, and a restricted path
  * created later is not covered. */
private[atc] object Bubblewrap:
  /** Whether `bwrap` runs here: missing, or refused where unprivileged user namespaces are off. */
  def available: Boolean =
    try
      val process = ProcessBuilder(
        "bwrap",
        "--ro-bind",
        "/",
        "/",
        "--unshare-user",
        "--unshare-pid",
        "--unshare-net",
        "--die-with-parent",
        "--",
        "true"
      ).redirectErrorStream(true).start().nn
      process.getInputStream.nn.readAllBytes()
      process.waitFor(10, TimeUnit.SECONDS) && process.exitValue == 0
    catch case NonFatal(_) => false

  /** Directories not searched for glob matches: dependencies and build output. */
  val SkippedDirs: Set[String] = Set(
    ".git",
    "node_modules",
    ".venv",
    "venv",
    "site-packages",
    "target",
    "out",
    "build",
    "dist",
    "__pycache__",
    ".gradle",
    ".bloop",
    ".metals",
    ".bsp"
  )

  /** Entries visited per root when searching for glob matches. */
  val MaxEntries: Int = 50_000

  /** The loopback port inside the sandbox that `socat` forwards to the host's proxy. */
  val ProxyPort: Int = 3128

  /** Where the host's proxy socket appears inside the sandbox. */
  private val ProxyDir = "/tmp/.atc-proxy"

  /** `socat` on the `PATH`, which the network bridge needs. */
  def socat: Option[Path] =
    Option(System.getenv("PATH")).toList.flatMap(_.split(java.io.File.pathSeparator).toList)
      .filter(_.nonEmpty).map(dir => Paths.get(dir, "socat").nn).find(Files.isExecutable(_))

  /** The command line prefix that runs a command under `plan`. The command's temporary
    * directory is the sandbox's own `/tmp`. With `bridge` (`socat` and the directory
    * holding the host's proxy socket), a command that may use the network keeps its own
    * network namespace and reaches only the proxy, through `socat` listening on
    * [[ProxyPort]]; without it, such a command shares the host's network. */
  def prefix(plan: SandboxPlan, bridge: Option[(Path, Path)]): List[String] =
    val args = List.newBuilder[String]
    args += "bwrap"
    args ++= systemMounts
    args ++= List("--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp")
    for (_, dir) <- bridge do args ++= List("--bind", dir.toString, ProxyDir)
    if Files.isDirectory(plan.home) then args ++= List("--tmpfs", plan.home.toString)
    val binds = (plan.readable ++ plan.toolchain).filter(Files.exists(_)).map(p => ("--ro-bind", p)) ++
      (plan.writable :+ plan.cache).filter(Files.exists(_)).map(p => ("--bind", p))
    for (flag, path) <- binds.sortBy(_._2.getNameCount) do args ++= List(flag, path.toString, path.toString)
    for (level, path) <- masks(plan).sortBy(_._2.getNameCount) do args ++= mask(level, path)
    for socket <- agentSockets do args ++= mask(Level.Hidden, socket)
    args ++= List("--unshare-user", "--unshare-pid", "--unshare-ipc")
    if !plan.network || bridge.isDefined then args += "--unshare-net"
    args ++= List("--die-with-parent", "--new-session", "--cap-drop", "ALL", "--")
    for (socat, _) <- bridge do
      // Start the forwarder, wait until it accepts connections, then become the command.
      val script =
        s"$socat TCP-LISTEN:$ProxyPort,bind=127.0.0.1,reuseaddr,fork UNIX-CONNECT:$ProxyDir/proxy.sock >/dev/null 2>&1 & " +
          s"i=0; while [ $$i -lt 100 ] && ! $socat -u OPEN:/dev/null TCP:127.0.0.1:$ProxyPort >/dev/null 2>&1; " +
          "do i=$((i+1)); sleep 0.02; done; exec \"$@\""
      args ++= List("/bin/sh", "-c", script, "atc-proxy")
    args.result()

  /** The prefix that runs the evaluator process: the system directories read-only, the home
    * directory and `/tmp` empty except for what `readable` names (the JDK, ATC's classes),
    * and every namespace unshared, so it has no network and sees no other process. */
  def evaluatorPrefix(home: Path, readable: List[Path]): List[String] =
    val args = List.newBuilder[String]
    args += "bwrap"
    args ++= systemMounts
    args ++= List("--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp")
    if Files.isDirectory(home) then args ++= List("--tmpfs", home.toString)
    for path <- readable.filter(Files.exists(_)).sortBy(_.getNameCount) do
      args ++= List("--ro-bind", path.toString, path.toString)
    for socket <- agentSockets do args ++= mask(Level.Hidden, socket)
    args ++= List("--chdir", "/tmp", "--unshare-all", "--die-with-parent", "--new-session", "--cap-drop", "ALL", "--")
    args.result()

  /** System directories every sandboxed process may read. */
  private val SystemRoots = List("/usr", "/etc", "/opt", "/sys", "/nix", "/gnu", "/snap")

  /** Top-level directories that a merged `/usr` replaces with links into it. */
  private val UsrLinks = List("/bin", "/sbin", "/lib", "/lib32", "/lib64", "/libx32")

  /** Read-only mounts of the system directories present here, with the links of a merged `/usr`. */
  private def systemMounts: List[String] =
    SystemRoots.map(Paths.get(_).nn).filter(Files.isDirectory(_)).flatMap(dir =>
      List("--ro-bind", dir.toString, dir.toString)
    ) ++
      UsrLinks.map(Paths.get(_).nn).flatMap: path =>
        if Files.isSymbolicLink(path) then List("--symlink", Files.readSymbolicLink(path).toString, path.toString)
        else if Files.isDirectory(path) then List("--ro-bind", path.toString, path.toString)
        else Nil

  /** Where agents and daemons that act for the user listen, as the existing real paths:
    * bubblewrap cannot mount over a path reached through a link such as `/var/run`. */
  private def agentSockets: List[Path] =
    (Option(System.getenv("XDG_RUNTIME_DIR")).map(Paths.get(_).nn).toList ++
      List(Paths.get("/run/docker.sock").nn, Paths.get("/var/run/docker.sock").nn)).flatMap: path =>
      try Some(path.toRealPath().nn)
      catch case NonFatal(_) => None
    .distinct

  private def mask(level: Level, path: Path): List[String] =
    val shown = path.toString
    level match
      case Level.ReadOnly => List("--ro-bind", shown, shown)
      case _ if Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) => List("--tmpfs", shown, "--remount-ro", shown)
      case _ => List("--ro-bind", "/dev/null", shown)

  /** The existing paths each restriction covers, outermost first. */
  def masks(plan: SandboxPlan): List[(Level, Path)] =
    val exact = plan.restrictions.collect:
      case Restriction(level, Target.Exact(path)) if Files.exists(path, LinkOption.NOFOLLOW_LINKS) => (level, path)
    val globs = plan.restrictions.collect:
      case Restriction(level, Target.Component(glob)) =>
        val pattern = PathGlob.pattern(glob)
        (level, (p: Path) => Option(p.getFileName).exists(name => pattern.matcher(name.toString).matches()))
      case Restriction(level, Target.Anchored(root, glob)) =>
        val pattern = PathGlob.pattern(glob)
        (
          level,
          (p: Path) =>
            p.startsWith(root) && p != root && pattern.matcher(PlatformPath.portable(root.relativize(p).nn)).matches()
        )
    val found =
      if globs.isEmpty then Nil
      else
        (plan.writable ++
          plan.readable.filter(r => plan.writable.exists(r.startsWith(_)) || r.startsWith(plan.project)))
          .distinct.flatMap(root => search(root, globs))
    (exact ++ found).distinct

  /** Walk `root` and return the paths a glob restriction matches, without descending below a match. */
  private def search(root: Path, globs: List[(Level, Path => Boolean)]): List[(Level, Path)] =
    val found = List.newBuilder[(Level, Path)]
    var visited = 0
    def check(path: Path): Boolean =
      val hits = globs.filter(_._2(path))
      // The strictest level wins when several globs match one path.
      hits.map(_._1).minByOption(_.ordinal).foreach(level => found += ((level, path)))
      hits.nonEmpty
    try
      Files.walkFileTree(
        root,
        new SimpleFileVisitor[Path]:
          override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            visited += 1
            if visited > MaxEntries then FileVisitResult.TERMINATE
            else if dir != root && (check(dir) || SkippedDirs.contains(dir.getFileName.toString)) then
              FileVisitResult.SKIP_SUBTREE
            else FileVisitResult.CONTINUE
          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
            visited += 1
            check(file)
            if visited > MaxEntries then FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
          override def visitFileFailed(file: Path, e: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE
      )
    catch case NonFatal(_) => ()
    found.result()
