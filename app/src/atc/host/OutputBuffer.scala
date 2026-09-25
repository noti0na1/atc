package atc.host

import java.util.ArrayDeque
import java.util.regex.Pattern

/** A bounded text buffer fed by a drain thread and read by the agent. A
  * [[OutputBuffer.Head]] keeps the first `cap` characters and drops the rest,
  * which suits a foreground command whose first megabytes carry the diagnostics;
  * a [[OutputBuffer.Tail]] drops the oldest text, which suits a long-running
  * process whose recent output matters. Reads may consume, so an interactive
  * session sees each chunk once. */
private[atc] sealed trait OutputBuffer:
  def append(text: String): Unit

  /** The unread text, left in place. */
  def peek: String

  /** The unread text, consumed. */
  def take(): String

  /** The first `n` unread characters, consumed. */
  def consume(n: Int): String

  /** A note to append once the cap has dropped text, else "". */
  def marker: String

  /** The unread text up to and including the first match of `pattern`, consumed; `None`
    * (nothing consumed) when it does not match. One critical section: a tail buffer
    * dropping its front between a peek and a consume would shift the offsets. */
  def consumeThrough(pattern: Pattern): Option[String] = synchronized:
    val matcher = pattern.matcher(peek)
    if matcher.find() then Some(consume(matcher.end())) else None

  /** Set once the process has exited and its output has all landed (`end()`). */
  private var ended = false

  /** Check for a match and begin waiting under the same lock as append/end,
    * so output arriving just before the wait cannot lose its notification. */
  def awaitMatch(pattern: Pattern, ms: Long): Option[String] = synchronized:
    consumeThrough(pattern).orElse:
      if !ended then wait(math.max(1L, ms))
      consumeThrough(pattern)

  def end(): Unit = synchronized:
    ended = true
    notifyAll()

private[atc] object OutputBuffer:
  def apply(cap: Int, keepHead: Boolean): OutputBuffer = if keepHead then Head(cap) else Tail(cap)

  /** Keeps the first `cap` characters. */
  final class Head(cap: Int) extends OutputBuffer:
    private val retained = StringBuilder()
    private var truncated = false

    def append(text: String): Unit = synchronized:
      val room = cap - retained.length
      if room > 0 then retained.append(text.take(room))
      if text.length > room then truncated = true
      notifyAll()

    def peek: String = synchronized(retained.toString)

    def take(): String = synchronized:
      val all = retained.toString
      retained.clear()
      all

    def consume(n: Int): String = synchronized:
      val end = math.min(n, retained.length)
      val taken = retained.substring(0, end)
      retained.delete(0, end)
      taken

    def marker: String = synchronized(if truncated then "\n...[truncated: output exceeded 8 MiB cap]..." else "")

  /** Keeps the last `cap` characters in bounded-size chunks, so dropping the
    * front is a constant-time list removal rather than a full-buffer shift.
    * Small appends share a chunk, so a process producing one character at a
    * time cannot turn the character cap into millions of retained `String`s. */
  final class Tail(cap: Int) extends OutputBuffer:
    private val MaxChunkChars = 8 * 1024
    private val chunkChars = math.max(1, math.min(cap, MaxChunkChars))
    private val chunks = ArrayDeque[java.lang.StringBuilder]()
    /** Characters already dropped from the front of the first chunk. */
    private var headSkip = 0
    /** Total retained characters, excluding [[headSkip]]. */
    private var length = 0
    private var truncated = false

    def append(text: String): Unit = synchronized:
      var offset = 0
      while offset < text.length do
        val last = chunks.peekLast()
        val chunk =
          if last != null && last.length < chunkChars then last
          else
            val fresh = java.lang.StringBuilder(chunkChars)
            chunks.addLast(fresh)
            fresh
        val copied = math.min(chunkChars - chunk.length, text.length - offset)
        chunk.append(text, offset, offset + copied)
        offset += copied
        length += copied
        while length > cap do
          truncated = true
          val first = chunks.peekFirst().nn
          val firstLength = first.length - headSkip
          val excess = length - cap
          if firstLength <= excess then
            chunks.removeFirst()
            length -= firstLength
            headSkip = 0
          else
            headSkip += excess
            length -= excess
      notifyAll()

    def peek: String = synchronized:
      val out = java.lang.StringBuilder(length + headSkip)
      chunks.forEach(chunk => out.append(chunk))
      out.substring(headSkip)

    def take(): String = synchronized:
      val all = peek
      chunks.clear()
      headSkip = 0
      length = 0
      all

    def consume(n: Int): String = synchronized:
      val out = java.lang.StringBuilder(math.min(n, length))
      var remaining = n
      while remaining > 0 && !chunks.isEmpty do
        val first = chunks.peekFirst().nn
        val available = first.length - headSkip
        if available <= remaining then
          out.append(first, headSkip, first.length)
          chunks.removeFirst()
          headSkip = 0
          length -= available
          remaining -= available
        else
          out.append(first, headSkip, headSkip + remaining)
          headSkip += remaining
          length -= remaining
          remaining = 0
      out.toString

    def marker: String = synchronized(if truncated then "\n...[older output dropped: exceeded 8 MiB cap]...\n" else "")

    /** Exposed only for a focused invariant test: retained storage must be
      * bounded by chunks, not by the number of append calls. */
    private[atc] def retainedChunkCount: Int = synchronized(chunks.size)
