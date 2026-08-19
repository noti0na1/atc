package atc.host

import java.io.OutputStream
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{CodingErrorAction, StandardCharsets}

/** An output stream that hands its bytes on as text, as they arrive: what the
  * live views of program and command output are fed with. UTF-8 is decoded
  * incrementally, so a character whose bytes are split across two writes
  * comes out whole (with the second write); malformed input is replaced, never
  * thrown. `finish()` flushes an incomplete trailing sequence as replacement
  * text. Thread-safe, since process streams are drained concurrently. */
final class TextSink(sink: String => Unit) extends OutputStream:
  private val decoder = StandardCharsets.UTF_8.newDecoder().nn
    .onMalformedInput(CodingErrorAction.REPLACE).nn
    .onUnmappableCharacter(CodingErrorAction.REPLACE).nn
  /** Bytes of an incomplete character, waiting for the next write. */
  private var leftover: Array[Byte] = Array.empty

  override def write(b: Int): Unit = write(Array(b.toByte), 0, 1)

  override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized:
    if len > 0 then
      val in = ByteBuffer.wrap(leftover ++ java.util.Arrays.copyOfRange(b, off, off + len))
      decode(in, endOfInput = false)
      leftover = new Array[Byte](in.remaining)
      in.get(leftover)

  /** Emit what an incomplete trailing sequence decodes to; the stream stays usable. */
  def finish(): Unit = synchronized:
    if leftover.nonEmpty then
      decode(ByteBuffer.wrap(leftover), endOfInput = true)
      leftover = Array.empty
      decoder.reset()

  private def decode(in: ByteBuffer, endOfInput: Boolean): Unit =
    val out = CharBuffer.allocate(in.remaining + 2) // UTF-8 never yields more chars than bytes
    decoder.decode(in, out, endOfInput)
    if endOfInput then decoder.flush(out)
    out.flip()
    if out.hasRemaining then sink(out.toString)
