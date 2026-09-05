package atc.ui

import atc.host.FileChange
import atc.sandbox.ExecutionResult

/** Recent model-visible output and file previews. Classified terminal text is never stored. */
private[atc] final class ToolHistory(maxChars: Int = 8 * 1024 * 1024, maxEntries: Int = 20):
  require(maxChars > 0 && maxEntries > 0, "Tool history limits must be positive")
  final case class Entry(
    id: Int,
    code: String,
    output: String,
    success: Boolean,
    millis: Long,
    changes: List[FileChange]
  ):
    def render: String =
      s"Tool $id (${if success then "ok" else "failed"}, $millis ms)\n\n$code\n\n$output" +
        changes.map(c => s"\n\n${c.path}: ${c.summary}\n${c.preview}").mkString
    def size: Int = code.length + output.length +
      changes.map(c => c.path.length + c.summary.length + c.preview.length).sum

  private var entries = Vector.empty[Entry]
  private var nextId = 0
  private def cap(text: String, limit: Int): String =
    if text.length <= limit then text else text.take(limit) + "\n[retained output limit reached]"

  def add(code: String, result: ExecutionResult, millis: Long, live: String, changes: List[FileChange]): Entry =
    nextId += 1
    val entry = Entry(
      nextId,
      cap(code, 32000),
      cap(
        result.render + Option.when(live.nonEmpty)(s"\n\nLive output:\n$live").getOrElse(""),
        math.min(maxChars / 2, 2 * 1024 * 1024)
      ),
      result.success,
      millis,
      changes.take(50).map(c => c.copy(preview = cap(c.preview, 4000)))
    )
    entries :+= entry
    while entries.size > maxEntries || entries.size > 1 && entries.map(_.size).sum > maxChars do entries = entries.tail
    entry

  def list: List[String] = entries.map(e =>
    s"${e.id}. ${if e.success then "ok" else "failed"} · ${e.millis} ms · ${e.code.linesIterator.nextOption().getOrElse("").take(80)}"
  ).toList
  def get(id: Int): Option[Entry] = entries.find(_.id == id)
  def latest: Option[Entry] = entries.lastOption
  def clear(): Unit = entries = Vector.empty
