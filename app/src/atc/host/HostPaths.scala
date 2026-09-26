package atc.host

import atc.{Debug, ScalaSource}
import atc.perms.{Perm, ScopeId}
import atc.platform.PlatformPath

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, LinkOption, Path, Paths, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try, Using}
import scala.util.control.NonFatal

/** Path resolution, permission checks and the checked primitives behind [[FileEntryImpl]] and
  * command redirection. Paths are canonicalized by [[canonical]] before any policy check. */
private[host] trait HostPaths:
  self: Host =>

  /** Resolve a path against the host working directory and canonicalize it for
    * policy evaluation, including symlinks and dangling write targets. */
  private[atc] def canonical(path: String): Path =
    val expanded = PlatformPath.expandHome(path)
    PlatformPath.validationError(expanded).foreach: reason =>
      throw IllegalArgumentException(s"Invalid Windows path ${ScalaSource.stringLiteral(path)}: $reason")
    val raw = Paths.get(PlatformPath.native(expanded)).nn
    // Canonicalizing a UNC path contacts its server (DNS, SMB) before any permission check,
    // so even a read-only file system inside `Classified.map` could reach the network.
    if PlatformPath.isUnc(raw) && raw.getRoot != cwd.getRoot then
      throw IllegalArgumentException(
        s"UNC path ${ScalaSource.stringLiteral(path)} is not supported outside the working directory's share"
      )
    PlatformPath.canonical(if raw.isAbsolute then raw else cwd.resolve(raw).nn)

  /** `operation` carries its own preposition, so that it reads as a phrase in
    * front of the path (`read '/x'`, `running a command in '/x'`). */
  private def denied(path: Path, operation: String, permission: Perm, hint: String): SecurityException =
    val shown = PlatformPath.portable(path)
    val guidance =
      if permission.locked then
        "The file rule is locked. Permission requests cannot widen access; the user must change the configuration."
      else hint
    SecurityException(
      s"Access denied: $operation '$shown' is not permitted (current permission: ${permission.describe}). $guidance"
    )

  private[host] def requireRead(scope: ScopeId, path: Path, operation: String): Perm =
    requireAccess(scope, path, operation, write = false)

  private[host] def requireWrite(scope: ScopeId, path: Path, operation: String): Perm =
    requireAccess(scope, path, operation, write = true)

  private def requireAccess(scope: ScopeId, path: Path, operation: String, write: Boolean): Perm =
    val permission = policy.effective(scope, path)
    if !(if write then permission.canWrite else permission.canRead) then
      val access = if write then "Access.Write" else "Access.Read"
      val shown = ScalaSource.stringLiteral(PlatformPath.portable(path))
      throw denied(path, operation, permission, s"Use requestFiles($shown, $access, reason) { ... } to ask the user.")
    permission

  private def requireNotClassified(permission: Perm, path: Path, operation: String, alternative: String): Unit =
    if permission.classified then
      throw SecurityException(
        s"Access denied: '${PlatformPath.portable(path)}' is classified; '$operation' would reveal its content. Use $alternative instead."
      )

  /** Require read access and that the content is not classified. `alternative`
    * names what to use instead on a classified path. */
  private[host] def requireReadable(scope: ScopeId, path: Path, operation: String, alternative: String): Perm =
    val permission = requireRead(scope, path, operation)
    requireNotClassified(permission, path, operation, alternative)
    permission

  /** Require write access and that the target is not classified. */
  private[host] def requireWritable(
    scope: ScopeId,
    path: Path,
    operation: String,
    alternative: String = "writeClassified(path, classify(content))"
  ): Perm =
    val permission = requireWrite(scope, path, operation)
    requireNotClassified(permission, path, operation, alternative)
    permission

  private[host] def ensureParent(path: Path): Unit = Option(path.getParent).foreach(Files.createDirectories(_))

  /** Run `body` and report what it changed at `path` through [[HostOutput.fileChanged]]. */
  private[host] def withFileChange[A](path: Path, operation: String)(body: => A): A =
    val before = FileChange.snapshot(path)
    val result = body
    try
      FileChange.between(display(PlatformPath.portable(path)), operation, before, FileChange.snapshot(path))
        .foreach(output.fileChanged)
    catch case NonFatal(error) => Debug.trace(error)
    result

  private[atc] def writeFile(scope: ScopeId, path: Path, content: String, append: Boolean): Unit =
    requireWritable(scope, path, if append then "append" else "write")
    withFileChange(path, "updated"):
      ensureParent(path)
      if append then
        Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      else Files.writeString(path, content, StandardCharsets.UTF_8)

  private[host] def writeFileBytes(scope: ScopeId, path: Path, content: Array[Byte]): Unit =
    requireWritable(scope, path, "writeBytes")
    withFileChange(path, "updated"):
      ensureParent(path)
      Files.write(path, content)
    ()

  private[host] def writeClassifiedFile(scope: ScopeId, path: Path, content: Try[String]): Unit =
    // Check the target before inspecting the classified computation. Otherwise
    // its success or failure could become an observable bit.
    val permission = requireWrite(scope, path, "writeClassified")
    if !permission.classified then
      throw SecurityException(
        s"Access denied: '${PlatformPath.portable(path)}' is not a classified path; writing classified content there would declassify it."
      )
    ensureParent(path)
    content match
      case Success(value) =>
        // Once the classified computation has been inspected, neither an I/O
        // failure nor its message may reach the agent: either would reveal a
        // success/failure bit, and an exception can quote the content.
        try Files.writeString(path, value, StandardCharsets.UTF_8)
        catch case NonFatal(_) => classifiedSinkFailed(s"writing '$path'")
      case Failure(_) =>
        // Create the file as the successful branch would, since `exists` works
        // on classified paths and would otherwise reveal the failure.
        try
          if !Files.exists(path) then
            Files.createFile(path)
            ()
        catch case NonFatal(_) => ()
        classifiedSinkFailed(s"writing '$path'")

  /** Visible children paired with whether the original directory entry was a
    * symlink. Policy checks use canonical paths; gitignore checks use the entry. */
  private def visibleEntries(scope: ScopeId, dir: Path): List[(Path, Boolean)] =
    val entries = Using.resource(Files.list(dir).nn): stream =>
      stream.iterator.nn.asScala.toList.sortBy(_.getFileName.toString).flatMap: entry =>
        try
          // Canonicalize every entry. Windows junctions and other reparse points are
          // not reported as symbolic links, but their NOFOLLOW attributes are
          // `isOther`. Keep every final reparse point out of recursive traversal
          // and evaluate policy on what it actually reaches.
          val path = PlatformPath.canonical(entry.toAbsolutePath.nn.normalize.nn)
          val attributes = Files.readAttributes(entry, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS).nn
          val isLinkLike = attributes.isSymbolicLink || attributes.isOther
          Option.when(!gitIgnore.ignores(entry) && policy.effective(scope, path).canRead)((path, isLinkLike))
        catch
          case NonFatal(_) => None
    // A link beside its own target resolves to the same path: list that path once, as the plain entry.
    val plain = entries.collect { case (path, false) => path }.toSet
    entries.filter((path, link) => !link || !plain.contains(path)).distinctBy(_._1)

  private[host] def visibleChildren(scope: ScopeId, dir: Path): List[Path] = visibleEntries(scope, dir).map(_._1)

  /** Visible descendants in pre-order. Classified trees require an explicit
    * classified traversal, and symlinked directories are never followed. */
  private[host] def walkPaths(scope: ScopeId, dir: Path, intoClassified: Boolean): List[Path] =
    iteratePaths(scope, dir, intoClassified).toList

  private[host] def iteratePaths(scope: ScopeId, dir: Path, intoClassified: Boolean): Iterator[Path] =
    def descendInto(child: Path, isLink: Boolean): Boolean =
      !isLink && Files.isDirectory(child) && (intoClassified || !policy.effective(scope, child).classified)
    def visit(current: Path): Iterator[Path] =
      visibleEntries(scope, current).iterator.flatMap: (child, isLink) =>
        Iterator.single(child) ++ (if descendInto(child, isLink) then visit(child) else Iterator.empty)
    visit(dir)

  private lazy val canonicalCwd: Path = canonical(".")

  /** `absolute` relative to the working directory when inside it, with `/` separators. */
  private[host] def display(absolute: String): String =
    val path = Paths.get(absolute).nn
    if path == canonicalCwd then "."
    else if path.startsWith(canonicalCwd) then PlatformPath.portable(canonicalCwd.relativize(path).nn)
    else PlatformPath.portable(path)
