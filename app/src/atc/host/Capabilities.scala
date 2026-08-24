package atc.host

import atc.lib.*
import atc.perms.ScopeId

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.util.{Try, Using}

/** Concrete capabilities. Each carries the id of its permission scope; the
  * policy resolves the effective permissions of that scope (its own grants
  * plus those of its ancestors, on top of the configured base). Constructors
  * of the abstract capability classes are `private[atc]`, so agent code (in
  * the empty package) cannot forge these.
  *
  * The read-only/full distinction is purely a matter of types on the agent
  * side (`FileEntry`'s `update` methods, `Cap`'s capture sets): the same
  * objects serve both views, and the host enforces the policy, including the
  * sandbox mode, on every call. */
sealed trait Scoped:
  def scope: ScopeId

final class FileSystemImpl(val scope: ScopeId, val host: Host) extends FileSystem, Scoped:
  def access(path: String): FileEntry = FileEntryImpl(this, host.canonical(path))

final class ExecImpl(val scope: ScopeId) extends Exec, Scoped

final class NetworkImpl(val scope: ScopeId) extends Network, Scoped

final class FileEntryImpl(fs: FileSystemImpl, p: Path) extends FileEntry:
  private def host: Host = fs.host
  private def scope: ScopeId = fs.scope

  /** Require read access for `what` *and* that the content is not classified;
    * `alt` names the `Classified`-returning member to use instead. */
  private def requireReadable(what: String, alt: String): Unit =
    host.requireNotClassified(host.requireRead(scope, p, what), p, what, alt)

  /** Run `op` (a read of classified content) as a `Classified` result: the
    * permission check and any failure stay inside the classified value. */
  private def asClassified[T](what: String)(op: => T): Classified[T] =
    ClassifiedImpl.fromTry(Try {
      host.requireRead(scope, p, what)
      op
    })

  def path: String = Host.portablePath(p)

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
    * line with its 1-based number. Decoding is lenient like `read()` (invalid
    * UTF-8 becomes U+FFFD, so binary or Latin-1 files do not abort a search);
    * the file is closed when the iteration ends. */
  def forEachLine(op: (String, Int) => Unit): Unit =
    requireReadable("forEachLine", "readClassified()")
    // Not `Files.lines`/`newBufferedReader`: their decoder throws on malformed input.
    val reader = java.io.BufferedReader(java.io.InputStreamReader(Files.newInputStream(p).nn, UTF_8))
    Using.resource(reader) { r =>
      var i = 0
      var line = r.readLine()
      while line != null do
        i += 1
        op(line, i)
        line = r.readLine()
    }

  def write(content: String): Unit =
    host.writeFile(scope, p, content, append = false)

  def writeBytes(content: Array[Byte]): Unit =
    host.writeFileBytes(scope, p, content)

  def append(content: String): Unit =
    host.writeFile(scope, p, content, append = true)

  def delete(): Unit =
    host.requireWrite(scope, p, "delete")
    Files.delete(p)

  def mkdir(): Unit =
    host.requireWrite(scope, p, "mkdir")
    Files.createDirectories(p)
    ()

  def children: List[FileEntry] =
    requireReadable("children", "childrenClassified")
    host.visibleChildren(scope, p).map(FileEntryImpl(fs, _))

  def walk(): List[FileEntry] =
    requireReadable("walk", "walkClassified")
    host.walkPaths(scope, p, intoClassified = false).map(FileEntryImpl(fs, _))

  def readClassified(): Classified[String] =
    asClassified("readClassified")(Files.readString(p, UTF_8).nn)

  def childrenClassified: Classified[List[String]] =
    asClassified("childrenClassified")(host.visibleChildren(scope, p).map(Host.portablePath))

  def walkClassified(): Classified[List[String]] =
    asClassified("walkClassified")(host.walkPaths(scope, p, intoClassified = true).map(Host.portablePath))

  def writeClassified(content: Classified[String]): Unit =
    // Hand the raw `Try` to the host: it runs the permission/target checks before
    // it branches on success/failure, so neither the thrown exception nor the
    // target's existence can become a per-bit oracle over the classified value.
    host.writeClassifiedFile(scope, p, ClassifiedImpl.unwrap(content))
