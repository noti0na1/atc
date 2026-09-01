package atc.host

import atc.{ScalaSource, TextFiles}
import atc.lib.*
import atc.perms.{Perm, ScopeId}
import atc.platform.{PathGlob, PlatformPath}

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileSystems, Files, LinkOption, Path, Paths, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try, Using}
import scala.util.control.NonFatal

/** Filesystem effects and file-oriented convenience operations supplied by
  * [[Host]]. All paths pass through the same canonicalization and policy checks. */
private[host] trait HostFiles:
  self: Host =>

  /** Resolve a path against the host working directory and canonicalize it for
    * policy evaluation, including symlinks and dangling write targets. */
  private[atc] def canonical(path: String): Path =
    val expanded = PlatformPath.expandHome(path)
    PlatformPath.validationError(expanded).foreach(reason =>
      throw IllegalArgumentException(s"Invalid Windows path ${ScalaSource.stringLiteral(path)}: $reason")
    )
    val raw = Paths.get(PlatformPath.native(expanded)).nn
    PlatformPath.canonical(if raw.isAbsolute then raw else cwd.resolve(raw).nn)

  private def denied(path: Path, operation: String, permission: Perm, hint: String): SecurityException =
    val shown = PlatformPath.portable(path)
    SecurityException(
      s"Access denied: $operation on '$shown' is not permitted (current permission: ${permission.describe}). $hint"
    )

  private[atc] def requireRead(scope: ScopeId, path: Path, operation: String): Perm =
    val permission = policy.effective(scope, path)
    if !permission.canRead then
      val shown = PlatformPath.portable(path)
      throw denied(
        path,
        operation,
        permission,
        s"Use requestFiles(${ScalaSource.stringLiteral(shown)}, Access.Read, reason) { ... } to ask the user."
      )
    permission

  private[atc] def requireWrite(scope: ScopeId, path: Path, operation: String): Perm =
    val permission = policy.effective(scope, path)
    if !permission.canWrite then
      val shown = PlatformPath.portable(path)
      throw denied(
        path,
        operation,
        permission,
        s"Use requestFiles(${ScalaSource.stringLiteral(shown)}, Access.Write, reason) { ... } to ask the user."
      )
    permission

  private[atc] def requireNotClassified(
    permission: Perm,
    path: Path,
    operation: String,
    alternative: String
  ): Unit =
    if permission.classified then
      throw SecurityException(
        s"Access denied: '${PlatformPath.portable(path)}' is classified; '$operation' would reveal its content. Use $alternative instead."
      )

  private[host] def ensureParent(path: Path): Unit = Option(path.getParent).foreach(Files.createDirectories(_))

  private[atc] def writeFile(scope: ScopeId, path: Path, content: String, append: Boolean): Unit =
    val permission = requireWrite(scope, path, if append then "append" else "write")
    requireNotClassified(permission, path, "write", "writeClassified(path, classify(content))")
    ensureParent(path)
    if append then
      Files.writeString(
        path,
        content,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
    else Files.writeString(path, content, StandardCharsets.UTF_8)

  private[atc] def writeFileBytes(scope: ScopeId, path: Path, content: Array[Byte]): Unit =
    val permission = requireWrite(scope, path, "writeBytes")
    requireNotClassified(permission, path, "writeBytes", "writeClassified(path, classify(content))")
    ensureParent(path)
    Files.write(path, content)
    ()

  private[atc] def writeClassifiedFile(scope: ScopeId, path: Path, content: Try[String]): Unit =
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
        // failure nor its message may escape to the agent: that would reveal a
        // success/failure bit (and an exception can itself quote the content).
        try Files.writeString(path, value, StandardCharsets.UTF_8)
        catch case NonFatal(_) => classifiedSinkFailed(s"writing '$path'")
      case Failure(_) =>
        // Equalise the observable existence side effect with the successful
        // branch. `exists` is intentionally available even for classified paths.
        try
          if !Files.exists(path) then
            Files.createFile(path)
            ()
        catch case NonFatal(_) => ()
        classifiedSinkFailed(s"writing '$path'")

  /** Visible children paired with whether the original directory entry was a
    * symlink. Policy checks use canonical paths; gitignore checks use the entry. */
  private def visibleEntries(scope: ScopeId, dir: Path): List[(Path, Boolean)] =
    Using.resource(Files.list(dir).nn) { stream =>
      stream.iterator.nn.asScala.toList.sortBy(_.getFileName.toString).flatMap { entry =>
        try
          // Canonicalize every entry. Windows junctions/reparse points are not
          // reported as symbolic links, but their NOFOLLOW attributes are
          // `isOther`. Keep every final reparse point out of recursive traversal
          // and evaluate policy on what it actually reaches.
          val lexical = entry.toAbsolutePath.nn.normalize.nn
          val path = PlatformPath.canonical(lexical)
          val attributes = Files.readAttributes(entry, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS).nn
          val isLinkLike = attributes.isSymbolicLink || attributes.isOther
          Option.when(!gitIgnore.ignores(entry) && policy.effective(scope, path).canRead)((path, isLinkLike))
        catch case _: Exception => None
      }
    }

  private[atc] def visibleChildren(scope: ScopeId, dir: Path): List[Path] = visibleEntries(scope, dir).map(_._1)

  /** Visible descendants in pre-order. Classified trees require an explicit
    * classified traversal, and symlinked directories are never followed. */
  private[atc] def walkPaths(scope: ScopeId, dir: Path, intoClassified: Boolean): List[Path] =
    def descendInto(child: Path, isLink: Boolean): Boolean =
      !isLink && Files.isDirectory(child) && (intoClassified || !policy.effective(scope, child).classified)
    def visit(current: Path): List[Path] =
      visibleEntries(scope, current).flatMap { (child, isLink) =>
        child :: (if descendInto(child, isLink) then visit(child) else Nil)
      }
    visit(dir)

  def requestFiles[T, C <: caps.CapSet](path: String)(using UserIO, FileSystem)(op: FileSystem ?=> T): T =
    requestFiles(path, Access.Read, "")(op)

  def requestFiles[T, C <: caps.CapSet](path: String, access: Access)(using
    UserIO,
    FileSystem
  )(
    op: FileSystem ?=> T
  ): T =
    requestFiles(path, access, "")(op)

  def requestFiles[T, C <: caps.CapSet](path: String, access: Access, reason: String)(using
    user: UserIO,
    parent: FileSystem
  )(op: FileSystem ?=> T): T =
    val requestedAccess = access match
      case Access.Read => atc.perms.Access.Read
      case Access.Write => atc.perms.Access.Write
    inScope(policy.requestFile(scopeOf(parent), canonical(path), requestedAccess, reason)) { id =>
      op(using FileSystemImpl(id, this))
    }

  def access(path: String)(using fs: FileSystem): FileEntry = fs.access(path)

  def read(path: String)(using fs: FileSystem): String = fs.access(path).read()

  def readLines(path: String)(using fs: FileSystem): List[String] = fs.access(path).readLines()

  /** Print a numbered view capped at [[Host.CatMaxLines]]. Lines are streamed,
    * so a large file or line is never loaded whole: only the shown prefixes
    * are kept. */
  def cat(path: String)(using fs: FileSystem, user: UserIO): Unit =
    val entry = impl(fs.access(path))
    val kept = collection.mutable.ListBuffer[CappedLine]()
    var lineCount = 0
    entry.forEachCappedLine(Host.CatMaxLineChars) { (prefix, chars, number) =>
      lineCount = number
      if lineCount <= Host.CatMaxLines then kept += CappedLine(prefix, chars)
    }
    val text =
      if lineCount == 0 then "[empty file]\n"
      else
        val body = numbered(kept.toList, 1)
        if lineCount <= Host.CatMaxLines then body
        else
          val next = math.min(lineCount, 2 * Host.CatMaxLines)
          body + s"... [${lineCount - Host.CatMaxLines} more lines ($lineCount in all): cat(${ScalaSource.stringLiteral(path)}, ${Host.CatMaxLines + 1}, $next) shows the next]\n"
    output.print(text, text)

  /** Print an inclusive, one-based range with line numbers. Streamed: only the
    * requested window is kept in memory, however far `to` runs past the end. */
  def cat(path: String, from: Int, to: Int)(using fs: FileSystem, user: UserIO): Unit =
    if from < 1 || to < from then
      throw IllegalArgumentException(s"cat: the range must satisfy 1 <= from <= to (got $from, $to)")
    val entry = impl(fs.access(path))
    val kept = collection.mutable.ListBuffer[CappedLine]()
    var lineCount = 0
    entry.forEachCappedLine(Host.CatMaxLineChars) { (prefix, chars, number) =>
      lineCount = number
      if lineCount >= from && lineCount <= to then kept += CappedLine(prefix, chars)
    }
    val text =
      if from > lineCount then s"[nothing to show: $path has $lineCount lines]\n"
      else
        val body = numbered(kept.toList, from)
        if to > lineCount then body + s"[end of file: $lineCount lines]\n" else body
    output.print(text, text)

  private case class CappedLine(prefix: String, chars: Long)

  private def numbered(lines: List[CappedLine], first: Int): String =
    val result = StringBuilder()
    var number = first
    lines.foreach { line =>
      val shown =
        val omitted = line.chars - line.prefix.length
        if omitted == 0 then line.prefix else line.prefix + s" ... [+$omitted chars]"
      result.append(f"$number%6d\t").append(shown).append('\n')
      number += 1
    }
    result.toString

  def readBytes(path: String)(using fs: FileSystem): Array[Byte] = fs.access(path).readBytes()

  def write(path: String, content: String)(using fs: FileSystem): Unit = fs.access(path).write(content)

  def writeBytes(path: String, content: Array[Byte])(using fs: FileSystem): Unit =
    fs.access(path).writeBytes(content)

  /** Move through checked primitives so the operation grants no extra access.
    * Streamed: a large file is copied in chunks rather than read whole. */
  def move(from: String, to: String)(using fs: FileSystem): Unit =
    val source = impl(fs.access(from))
    if source.isDirectory then
      throw IllegalArgumentException(s"move: '$from' is a directory; move its files and mkdir/delete the directories")
    val target = impl(fs.access(to))
    if source.path != target.path then
      Using.resource(source.openRead())(in => target.writeFrom(source, in))
      source.delete()

  def copy(from: String, to: String)(using fs: FileSystem): Unit =
    val source = impl(fs.access(from))
    if source.isDirectory then
      throw IllegalArgumentException(s"copy: '$from' is a directory; copy its files one by one")
    Using.resource(source.openRead())(in => impl(fs.access(to)).writeFrom(source, in))

  /** The host's own [[FileEntry]] implementation; agent code cannot supply
    * another, so the cast is safe and keeps streaming helpers off the public API. */
  private def impl(entry: FileEntry): FileEntryImpl = entry match
    case e: FileEntryImpl => e
    case other => throw SecurityException(s"Unknown FileEntry implementation: ${other.getClass.getName}")

  /** Rewrite every regex match in place and reject an accidental no-op. One
    * pass both counts and rewrites, so a large file is scanned once. */
  def sed(path: String, pattern: String, replacement: String)(using fs: FileSystem): Int =
    if pattern.isEmpty then throw IllegalArgumentException("sed: the pattern must not be empty")
    val regex = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.MULTILINE)
    val entry = fs.access(path)
    val before = entry.read()
    val javaReplacement = sedReplacement(replacement)
    val rewritten = new java.lang.StringBuffer()
    val matcher = regex.matcher(before)
    var count = 0
    while matcher.find() do
      count += 1
      matcher.appendReplacement(rewritten, javaReplacement)
    matcher.appendTail(rewritten)
    if count == 0 then
      throw IllegalArgumentException(
        s"sed: the regex '$pattern' matches nothing in '${entry.path}', so nothing was changed; check it with grep(path, pattern), and quote literal text with quote(text) (the pattern) and quoteReplacement(text) (the replacement)."
      )
    entry.write(rewritten.toString)
    count

  /** Convert sed-style group and escape syntax to Java replacement syntax. */
  private def sedReplacement(replacement: String): String =
    val result = StringBuilder()
    var index = 0
    while index < replacement.length do
      val char = replacement.charAt(index)
      if char == '\\' && index + 1 < replacement.length then
        replacement.charAt(index + 1) match
          case digit if digit.isDigit => result.append('$').append(digit)
          case 'n' => result.append('\n')
          case 't' => result.append('\t')
          case other => result.append('\\').append(other)
        index += 2
      else
        if char == '\\' then result.append("\\\\") else result.append(char)
        index += 1
    result.toString

  // TODO(safe-mode): remove with the Interface declarations once safe mode admits these methods.
  def quote(text: String): String = java.util.regex.Pattern.quote(text).nn

  def quoteReplacement(text: String): String = java.util.regex.Matcher.quoteReplacement(text).nn

  def replaceLines(path: String, from: Int, to: Int, text: String)(using fs: FileSystem): String =
    val entry = fs.access(path)
    val document = TextFiles.splitLines(entry.read())
    val lines = document.lines
    val lineCount = lines.length
    if from < 1 || to < from || to > lineCount then
      throw IllegalArgumentException(
        s"replaceLines: the range must satisfy 1 <= from <= to <= $lineCount (the file has $lineCount lines), got $from..$to; cat the file again, line numbers shift after an edit"
      )
    val old = lines.slice(from - 1, to)
    val updated = lines.take(from - 1) ++ TextFiles.splitLines(text).lines ++ lines.drop(to)
    entry.write(document.copy(lines = updated).join)
    old.mkString(document.lineEnding)

  def insertLines(path: String, before: Int, text: String)(using fs: FileSystem): Unit =
    val entry = fs.access(path)
    val document = TextFiles.splitLines(entry.read())
    val lines = document.lines
    val lineCount = lines.length
    if before < 1 || before > lineCount + 1 then
      throw IllegalArgumentException(
        s"insertLines: `before` must be between 1 and ${lineCount + 1} (the file has $lineCount lines), got $before"
      )
    val updated = lines.take(before - 1) ++ TextFiles.splitLines(text).lines ++ lines.drop(before - 1)
    entry.write(document.copy(lines = updated).join)

  def append(path: String, content: String)(using fs: FileSystem): Unit = fs.access(path).append(content)

  def exists(path: String)(using fs: FileSystem): Boolean = fs.access(path).exists

  def isDirectory(path: String)(using fs: FileSystem): Boolean = fs.access(path).isDirectory

  def mkdir(path: String)(using fs: FileSystem): Unit = fs.access(path).mkdir()

  def delete(path: String)(using fs: FileSystem): Unit = fs.access(path).delete()

  def ls(dir: String)(using fs: FileSystem): List[String] = fs.access(dir).children.map(entry => display(entry.path))

  def walk(dir: String)(using fs: FileSystem): List[String] = fs.access(dir).walk().map(entry => display(entry.path))

  private lazy val canonicalCwd: Path = canonical(".")

  private def display(absolute: String): String =
    val path = Paths.get(absolute).nn
    if path == canonicalCwd then "."
    else if path.startsWith(canonicalCwd) then PlatformPath.portable(canonicalCwd.relativize(path).nn)
    else PlatformPath.portable(path)

  private def grepEntry(entry: FileEntry, regex: scala.util.matching.Regex): List[GrepMatch] =
    val matches = collection.mutable.ListBuffer[GrepMatch]()
    val shown = display(entry.path)
    entry.forEachLine { (line, number) =>
      if regex.findFirstIn(line).isDefined then matches += GrepMatch(shown, number, line)
    }
    matches.toList

  def grep(path: String, pattern: String)(using fs: FileSystem): List[GrepMatch] =
    grepEntry(fs.access(path), pattern.r)

  def grepRecursive(dir: String, pattern: String)(using fs: FileSystem): List[GrepMatch] =
    grepRecursive(dir, pattern, "*")

  def grepRecursive(dir: String, pattern: String, glob: String)(using fs: FileSystem): List[GrepMatch] =
    val regex = pattern.r
    filesNamed(dir, glob).filterNot(_.isClassified).flatMap(grepEntry(_, regex))

  def find(dir: String, glob: String)(using fs: FileSystem): List[String] =
    filesNamed(dir, glob).map(entry => display(entry.path))

  /** Select non-directory descendants by filename or relative-path glob. */
  private def filesNamed(dir: String, glob: String)(using fs: FileSystem): List[FileEntry] =
    val files = fs.access(dir).walk().filterNot(_.isDirectory)
    if glob.contains('/') || glob.contains("**") then
      val base = canonical(dir)
      val regex = PathGlob.regex(glob)
      files.filter(entry => regex.matches(PlatformPath.portable(base.relativize(Paths.get(entry.path)).nn)))
    else
      val matcher = FileSystems.getDefault.nn.getPathMatcher(s"glob:$glob").nn
      files.filter(entry => matcher.matches(Paths.get(entry.path).nn.getFileName))

  def readClassified(path: String)(using fs: FileSystem): Classified[String] = fs.access(path).readClassified()

  def writeClassified(path: String, content: Classified[String])(using fs: FileSystem): Unit =
    fs.access(path).writeClassified(content)
