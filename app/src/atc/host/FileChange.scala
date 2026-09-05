package atc.host

import atc.TextFiles
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal
import scala.util.Using

/** A bounded preview of an unclassified file operation, for the terminal only. */
final case class FileChange(path: String, summary: String, preview: String)

private[atc] object FileChange:
  private val MaxBytes = 64000
  final case class Snapshot(exists: Boolean, text: Option[String])

  def snapshot(path: Path): Snapshot =
    try
      if !Files.exists(path) then Snapshot(false, Some(""))
      else if !Files.isRegularFile(path) || Files.size(path) > MaxBytes then Snapshot(true, None)
      else
        val bytes = Using.resource(Files.newInputStream(path).nn)(_.readNBytes(MaxBytes + 1).nn)
        val text = String(bytes, UTF_8)
        Snapshot(
          true,
          Option.when(bytes.length <= MaxBytes && !text.contains('\u0000') && !text.contains('\ufffd'))(text)
        )
    catch case NonFatal(_) => Snapshot(true, None)

  def between(path: String, operation: String, before: Snapshot, after: Snapshot): Option[FileChange] =
    if before == after && before.text.isDefined then None
    else
      (before.text, after.text) match
        case (Some(old), Some(updated)) =>
          val a = TextFiles.splitLines(old).lines.toVector
          val b = TextFiles.splitLines(updated).lines.toVector
          val prefix = a.zip(b).takeWhile((x, y) => x == y).size
          val suffix = a.drop(prefix).reverse.zip(b.drop(prefix).reverse).takeWhile((x, y) => x == y).size
          val removed = a.slice(prefix, a.size - suffix)
          val added = b.slice(prefix, b.size - suffix)
          val lines = removed.map("- " + _) ++ added.map("+ " + _)
          val body = if lines.isEmpty then "[line endings or final newline changed]"
          else lines.take(40).map(_.take(240)).mkString("\n")
          val note = if lines.size > 40 then "\n[diff preview truncated]" else ""
          val action = if !before.exists then "created" else if !after.exists then "deleted" else "updated"
          Some(FileChange(path, s"$action (+${added.size} -${removed.size})", s"@@ line ${prefix + 1} @@\n$body$note"))
        case _ => Some(FileChange(path, operation, "[text preview unavailable: binary, directory or large file]"))
