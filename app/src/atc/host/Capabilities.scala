package atc.host

import atc.lib.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.{Failure, Success, Try}

/** Concrete capabilities. Each carries the id of its permission scope; the
  * policy resolves the effective permissions of that scope (its own grants
  * plus those of its ancestors, on top of the configured base). Constructors
  * of the abstract capability classes are `private[atc]`, so agent code (in
  * the empty package) cannot forge these. */
sealed trait Scoped:
  def scope: Long

final class FileSystemImpl(val scope: Long, val host: Host) extends FileSystem, Scoped:
  def access(path: String): FileEntry = FileEntryImpl(this, host.canonical(path))

final class ExecImpl(val scope: Long) extends Exec, Scoped
final class NetworkImpl(val scope: Long) extends Network, Scoped

final class FileEntryImpl(fs: FileSystemImpl, p: Path) extends FileEntry(fs):
  private def host: Host = fs.host
  private def scope: Long = fs.scope
  def path: String = p.toString
  def name: String = Option(p.getFileName).map(_.toString).getOrElse(p.toString)
  def exists: Boolean = { host.requireRead(scope, p, "exists"); Files.exists(p) }
  def isDirectory: Boolean = { host.requireRead(scope, p, "isDirectory"); Files.isDirectory(p) }
  def isClassified: Boolean = host.requireRead(scope, p, "isClassified").classified
  def size: Long =
    val pm = host.requireRead(scope, p, "size")
    host.requireNotClassified(pm, p, "size", "readClassified()")
    Files.size(p)
  def read(): String = String(readBytes(), StandardCharsets.UTF_8)
  def readBytes(): Array[Byte] =
    val pm = host.requireRead(scope, p, "read")
    host.requireNotClassified(pm, p, "read", "readClassified()")
    Files.readAllBytes(p).nn
  def readLines(): List[String] = read().linesIterator.toList
  def forEachLine(op: (String, Int) => Unit): Unit =
    var i = 0
    read().linesIterator.foreach { line =>
      i += 1; op(line, i)
    }
  def write(content: String): Unit = host.writeFile(scope, p, content, append = false)
  def append(content: String): Unit = host.writeFile(scope, p, content, append = true)
  def delete(): Unit = { host.requireWrite(scope, p, "delete"); Files.delete(p) }
  def mkdir(): Unit = { host.requireWrite(scope, p, "mkdir"); Files.createDirectories(p); () }
  def children: List[FileEntry] =
    val pm = host.requireRead(scope, p, "children")
    host.requireNotClassified(pm, p, "children", "childrenClassified")
    host.visibleChildren(scope, p).map(FileEntryImpl(fs, _))
  def walk(): List[FileEntry] =
    val pm = host.requireRead(scope, p, "walk")
    host.requireNotClassified(pm, p, "walk", "walkClassified")
    host.walkPaths(scope, p, intoClassified = false).map(FileEntryImpl(fs, _))
  def readClassified(): Classified[String] =
    ClassifiedImpl.fromTry(Try {
      host.requireRead(scope, p, "readClassified"); Files.readString(p, StandardCharsets.UTF_8).nn
    })
  def writeClassified(content: Classified[String]): Unit =
    ClassifiedImpl.unwrap(content) match
      case Success(v) => host.writeClassifiedFile(scope, p, v)
      case Failure(_) => throw ClassifiedImpl.failed() // never rethrow: the message may hold the secret
  def childrenClassified: Classified[List[String]] =
    ClassifiedImpl.fromTry(Try {
      host.requireRead(scope, p, "childrenClassified"); host.visibleChildren(scope, p).map(_.toString)
    })
  def walkClassified(): Classified[List[String]] =
    ClassifiedImpl.fromTry(Try {
      host.requireRead(scope, p, "walkClassified"); host.walkPaths(scope, p, intoClassified = true).map(_.toString)
    })
