package atc.confine

import atc.confine.SandboxPlan.{Level, Restriction, Target}
import atc.platform.{PathGlob, PlatformPath}

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, LinkOption, Path, Paths, SimpleFileVisitor}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Linux: commands run under bubblewrap with new user, PID and IPC namespaces, and a network
  * namespace unless the command may use the network and `socat` is missing. Only the system directories are mounted, read-only, so other
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
  def prefix(plan: SandboxPlan, bridge: Option[(Path, Path)]): List[String] = prefix(plan, bridge, None, None)

  /** With `mirror` (a copy and the path it stands for), the copy is mounted at that path too
    * and the command starts in `start`. bubblewrap takes a mount's source from the file system
    * outside the sandbox, so that mount shows the copy unrestricted: the copy's restrictions
    * are mounted again at the path it stands for. */
  def prefix(
    plan: SandboxPlan,
    bridge: Option[(Path, Path)],
    mirror: Option[(Path, Path)],
    start: Option[Path]
  ): List[String] =
    val args = List.newBuilder[String]
    args += "bwrap"
    args ++= systemMounts
    args ++= List("--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp")
    for (_, dir) <- bridge do args ++= List("--bind", dir.toString, ProxyDir)
    if Files.isDirectory(plan.home) then args ++= List("--tmpfs", plan.home.toString)
    val writable = plan.writable ++ plan.cache ++ plan.locks
    val binds = (plan.readable ++ plan.toolchain).filter(Files.exists(_)).map(p => ("--ro-bind", p)) ++
      writable.filter(Files.exists(_)).map(p => ("--bind", p))
    for (flag, path) <- binds.sortBy(_._2.getNameCount) do args ++= List(flag, path.toString, path.toString)
    // A path under nothing mounted is absent already; masking it would only create it.
    val mounted = systemPaths ++ plan.readable ++ plan.toolchain ++ writable
    def visible(path: Path) = mounted.exists(path.startsWith(_))
    val masked = masks(plan).sortBy(_._2.getNameCount).filter((_, path) => visible(path))
    for (level, path) <- masked do args ++= mask(level, path, path)
    for socket <- agentSockets if visible(socket) do args ++= mask(Level.Hidden, socket, socket)
    for (copy, original) <- mirror do
      val flag = if writable.exists(copy.startsWith(_)) then "--bind" else "--ro-bind"
      args ++= List(flag, copy.toString, original.toString)
      for (level, path) <- masked if path.startsWith(copy) do
        args ++= mask(level, path, original.resolve(copy.relativize(path)).nn)
    for dir <- start do args ++= List("--chdir", dir.toString)
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
    for socket <- agentSockets if (systemPaths ++ readable).exists(socket.startsWith(_)) do
      args ++= mask(Level.Hidden, socket, socket)
    args ++= List("--chdir", "/tmp", "--unshare-all", "--die-with-parent", "--new-session", "--cap-drop", "ALL", "--")
    args.result()

  /** System directories every sandboxed process may read. */
  private val SystemRoots = List("/usr", "/etc", "/opt", "/sys", "/nix", "/gnu", "/snap")

  /** Top-level directories that a merged `/usr` replaces with links into it. */
  private val UsrLinks = List("/bin", "/sbin", "/lib", "/lib32", "/lib64", "/libx32")

  /** The system directories and top-level links present here. */
  private def systemPaths: List[Path] = (SystemRoots ++ UsrLinks).map(Paths.get(_).nn).filter(p =>
    Files.isDirectory(p) || Files.isSymbolicLink(p)
  )

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

  /** Restrict `target`, which shows `source`: a read-only mount takes its content from there. */
  private def mask(level: Level, source: Path, target: Path): List[String] =
    val shown = target.toString
    level match
      case Level.ReadOnly => List("--ro-bind", source.toString, shown)
      case _ if Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) => List("--tmpfs", shown, "--remount-ro", shown)
      case _ => List("--ro-bind", "/dev/null", shown)

  /** The existing paths each restriction covers. A name glob is searched for in the roots; a
    * glob below a path is searched for from its leading literal names, so that one inside a
    * skipped directory such as `.git` is found too. */
  def masks(plan: SandboxPlan): List[(Level, Path)] =
    val exact = plan.restrictions.collect:
      case Restriction(level, Target.Exact(path)) if Files.exists(path, LinkOption.NOFOLLOW_LINKS) => (level, path)
    val roots =
      (plan.writable ++
        plan.readable.filter(r => plan.writable.exists(r.startsWith(_)) || r.startsWith(plan.project))).distinct
    val names = plan.restrictions.collect:
      case Restriction(level, Target.Component(glob)) =>
        val pattern = PathGlob.pattern(glob)
        (level, (p: Path) => Option(p.getFileName).exists(name => pattern.matcher(name.toString).matches()))
    val anchored = plan.restrictions.flatMap:
      case Restriction(level, Target.Anchored(root, glob)) =>
        val start = glob.split('/').init.takeWhile(!_.exists("*?[{\\".contains(_))).foldLeft(root)(_.resolve(_).nn)
        val pattern = PathGlob.pattern(glob)
        val matches = (p: Path) => pattern.matcher(PlatformPath.portable(root.relativize(p).nn)).matches()
        Option.when(roots.exists(start.startsWith(_)) && Files.isDirectory(start))(search(
          start,
          List((level, matches))
        ))
          .toList.flatten
      case _ => Nil
    val found = if names.isEmpty then Nil else roots.flatMap(search(_, names))
    (exact ++ found ++ anchored).distinct

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
