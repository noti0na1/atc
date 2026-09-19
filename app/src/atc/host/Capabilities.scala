package atc.host

import atc.lib.*
import atc.perms.ScopeId
import atc.platform.PlatformPath

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.util.{Try, Using}

/** Runtime capabilities carry a permission scope ID. The host checks scope,
  * configured permissions and sandbox mode on each operation. Agent-facing
  * capture types enforce the read-only/full distinction over these same objects.
  * Capability constructors are private to ATC. */
sealed trait Scoped:
  def scope: ScopeId

final class FileSystemImpl(val scope: ScopeId, val host: Host) extends FileSystem, Scoped:
  def access(path: String): FileEntry = FileEntryImpl(this, host.canonical(path))

final class ExecImpl(val scope: ScopeId) extends Exec, Scoped

final class NetworkImpl(val scope: ScopeId) extends Network, Scoped

final class FileEntryImpl(fs: FileSystemImpl, p: Path) extends FileEntry:
  private def host: Host = fs.host
  private def scope: ScopeId = fs.scope
  private[host] def canonicalPath: Path = p

  /** Require read access for `operation` and that the content is not
    * classified; `alternative` names the `Classified`-returning member to use
    * instead. */
  private def requireReadable(operation: String, alternative: String): Unit =
    host.requireReadable(scope, p, operation, alternative)
    ()

  /** Run `op` (a read of classified content) as a `Classified` result: the
    * permission check and any failure stay inside the classified value. */
  private def asClassified[T](what: String)(op: => T): Classified[T] =
    ClassifiedImpl.fromTry(Try {
      host.requireRead(scope, p, what)
      op
    })

  def path: String = PlatformPath.portable(p)

  def name: String = Option(p.getFileName).map(_.toString).getOrElse(p.toString)

  def exists: Boolean =
    host.requireRead(scope, p, "exists")
    Files.exists(p)

  def isDirectory: Boolean =
    host.requireRead(scope, p, "isDirectory")
    Files.isDirectory(p)

  def isClassified: Boolean = host.requireRead(scope, p, "isClassified").classified

  def size: Long =
    requireReadable("size", "readClassified()")
    Files.size(p)

  def read(): String = String(readBytes(), UTF_8)

  def readBytes(): Array[Byte] =
    requireReadable("read", "readClassified()")
    Files.readAllBytes(p).nn

  def readLines(): List[String] = read().linesIterator.toList

  /** Stream the file line by line (never loaded whole); `op` receives each
    * line with its 1-based number. Decoding is lenient like `read()`: invalid
    * UTF-8 becomes U+FFFD, so binary or Latin-1 files do not abort a search.
    * The file is closed when the iteration ends. */
  def forEachLine(op: (String, Int) => Unit): Unit = forEachLine("forEachLine", op)

  /** [[forEachLine]] naming the operation the agent called, for denials. */
  private[host] def forEachLine(operation: String, op: (String, Int) => Unit): Unit =
    scanLines(operation, Int.MaxValue) { (line, _, number) => op(line, number); true }
    ()

  /** Stream line prefixes without ever materializing a whole line. `op` gets
    * the retained prefix (at most `maxChars` characters), the line's full
    * UTF-16 character count and its one-based number, and returns whether to
    * carry on; the stream is closed as soon as it returns false. CR, LF and
    * CRLF all end a line, and a final terminator adds no phantom line.
    *
    * Reads stop after `maxReadChars` characters. The result says whether that
    * budget cut the file short, so a file ending exactly on it is not reported
    * as truncated.
    */
  private[host] def scanLines(
    operation: String,
    maxChars: Int,
    maxReadChars: Long = Long.MaxValue
  )(op: (String, Long, Int) => Boolean): Boolean =
    if maxChars < 0 then throw IllegalArgumentException(s"maxChars must be non-negative (got $maxChars)")
    requireReadable(operation, "readClassified()")
    val reader = java.io.InputStreamReader(Files.newInputStream(p).nn, UTF_8)
    Using.resource(reader) { r =>
      val input = new Array[Char](8192)
      val prefix = StringBuilder(math.min(maxChars, input.length))
      var lineChars = 0L
      var lineNumber = 0
      var afterCr = false
      var continue = true
      var consumed = 0L
      var truncated = false

      def emit(): Unit =
        lineNumber += 1
        continue = op(prefix.toString, lineChars, lineNumber)
        prefix.clear()
        lineChars = 0L

      var read = r.read(input)
      var index = 0
      while continue && !truncated && read >= 0 do
        if index >= read then
          // Refilling past the budget is how a file ending on it is told from a cut one.
          read = r.read(input)
          index = 0
        else if consumed >= maxReadChars then truncated = true
        else
          val char = input(index)
          if afterCr && char == '\n' then afterCr = false
          else
            afterCr = false
            if char == '\r' then
              emit()
              afterCr = true
            else if char == '\n' then emit()
            else
              if lineChars < maxChars.toLong then prefix.append(char)
              lineChars += 1
          index += 1
          consumed += 1

      if continue && lineChars > 0 then emit()
      truncated
    }

  /** Open this file for streaming reads; checked like `read`. The caller must
    * close the returned stream. */
  private[host] def openRead(): java.io.InputStream =
    requireReadable("read", "readClassified()")
    Files.newInputStream(p).nn

  /** Stream `in` into this file; checked like `writeBytes`. When source and
    * target are two names for the same inode, all permission checks still run
    * but opening the truncating output stream is skipped. */
  private[host] def writeFrom(source: FileEntryImpl, in: java.io.InputStream): Unit =
    host.requireWritable(scope, p, "writeBytes")
    val sameFile =
      if p == source.canonicalPath then true
      else if !Files.exists(p) then false
      else
        try Files.isSameFile(source.canonicalPath, p)
        catch case _: java.nio.file.NoSuchFileException => false
    if !sameFile then
      host.withFileChange(p, "copied") {
        host.ensureParent(p)
        Using.resource(Files.newOutputStream(p).nn)(out => in.transferTo(out))
      }

  def write(content: String): Unit =
    host.writeFile(scope, p, content, append = false)

  def writeBytes(content: Array[Byte]): Unit =
    host.writeFileBytes(scope, p, content)

  def append(content: String): Unit =
    host.writeFile(scope, p, content, append = true)

  def delete(): Unit =
    val permission = host.requireWrite(scope, p, "delete")
    if permission.classified then Files.delete(p)
    else host.withFileChange(p, "deleted")(Files.delete(p))

  def mkdir(): Unit =
    val permission = host.requireWrite(scope, p, "mkdir")
    if permission.classified then Files.createDirectories(p)
    else host.withFileChange(p, "directory created")(Files.createDirectories(p))
    ()

  def children: List[FileEntry] =
    requireReadable("children", "childrenClassified")
    host.visibleChildren(scope, p).map(FileEntryImpl(fs, _))

  def walk(): List[FileEntry] =
    requireReadable("walk", "walkClassified")
    host.walkPaths(scope, p, intoClassified = false).map(FileEntryImpl(fs, _))

  private[host] def walkIterator: Iterator[FileEntryImpl] =
    requireReadable("walk", "walkClassified")
    host.iteratePaths(scope, p, intoClassified = false).map(FileEntryImpl(fs, _))

  def readClassified(): Classified[String] =
    asClassified("readClassified")(Files.readString(p, UTF_8).nn)

  def childrenClassified: Classified[List[String]] =
    asClassified("childrenClassified")(host.visibleChildren(scope, p).map(PlatformPath.portable))

  def walkClassified(): Classified[List[String]] =
    asClassified("walkClassified")(host.walkPaths(scope, p, intoClassified = true).map(PlatformPath.portable))

  def writeClassified(content: Classified[String]): Unit =
    // Hand the raw `Try` to the host: it runs the permission/target checks before
    // it branches on success/failure, so neither the thrown exception nor the
    // target's existence can become a per-bit oracle over the classified value.
    host.writeClassifiedFile(scope, p, ClassifiedImpl.unwrap(content))
