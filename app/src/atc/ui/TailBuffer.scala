package atc.ui

/** Bounded output buffer with incremental line counts. `tail(n)` scans backward
  * for the requested lines. Overflow discards a prefix at a line boundary when
  * possible, or within a line when necessary to enforce the character limit. */
private[atc] final class TailBuffer(cap: Int):
  private val sb = StringBuilder()
  private var newlines = 0L
  def append(text: String): Unit =
    newlines += text.count(_ == '\n')
    sb.append(text)
    if sb.length > cap then
      val nl = sb.indexOf("\n", sb.length - cap)
      sb.delete(0, if nl >= 0 then nl + 1 else sb.length - cap)
  /** Everything retained (the whole text while under the cap). */
  def text: String = sb.toString
  /** Lines ever appended (each `\n` ends one), plus an unfinished last line. */
  def lineCount: Long = newlines + (if sb.nonEmpty && sb.charAt(sb.length - 1) != '\n' then 1 else 0)
  /** The last `n` lines (an unfinished last line counts; a trailing newline is not a line). */
  def tail(n: Int): List[String] =
    var i = if sb.nonEmpty && sb.charAt(sb.length - 1) == '\n' then sb.length - 2 else sb.length - 1
    var nl = 0
    while i >= 0 && nl < n do { if sb.charAt(i) == '\n' then nl += 1; i -= 1 }
    // `nl < n` means we ran off the front before finding n newlines (return all);
    // otherwise `i` sits just before the n-th newline from the end, which is -1
    // when that newline is the first character, so `i + 2` is the correct start.
    val text = if nl < n then sb.toString else sb.substring(i + 2)
    text.split("\n", -1).toList match
      case init :+ "" => init
      case ls => ls
  def clear(): Unit = { sb.clear(); newlines = 0 }

private[atc] object TailBuffer:
  /** Cap on the text a live view retains (the front is dropped whole lines). */
  val MaxChars: Int = 1024 * 1024
