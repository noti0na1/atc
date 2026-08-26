package atc.host

import java.io.OutputStream
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{Charset, CharsetDecoder, CodingErrorAction, StandardCharsets}

/** An output stream that hands its bytes on as text, as they arrive: what the
  * live views of program and command output are fed with. UTF-8 is the default;
  * a leading UTF-8/UTF-16 byte-order mark is detected and removed, so native
  * Windows tools that mark their output can be read correctly too. Decoding is
  * incremental, so a character whose bytes are split across writes comes out
  * whole (with the later write); malformed input is replaced, never thrown.
  * `finish()` flushes an incomplete trailing sequence as replacement text.
  * Thread-safe, since process streams are drained concurrently. */
final class TextSink(sink: String => Unit) extends OutputStream:
  private val boms = List(
    Array(0xef.toByte, 0xbb.toByte, 0xbf.toByte) -> StandardCharsets.UTF_8,
    Array(0xff.toByte, 0xfe.toByte) -> StandardCharsets.UTF_16LE,
    Array(0xfe.toByte, 0xff.toByte) -> StandardCharsets.UTF_16BE,
  )
  private var decoder: CharsetDecoder | Null = null
  private var undecided: Array[Byte] = Array.empty
  /** Bytes of an incomplete character, waiting for the next write. */
  private var leftover: Array[Byte] = Array.empty

  override def write(b: Int): Unit = write(Array(b.toByte), 0, 1)

  override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized:
    if len > 0 then
      feed(java.util.Arrays.copyOfRange(b, off, off + len), endOfInput = false)

  /** Emit what an incomplete trailing sequence decodes to; the stream stays usable. */
  def finish(): Unit = synchronized:
    feed(Array.emptyByteArray, endOfInput = true)
    leftover = Array.empty
    Option(decoder).foreach(_.reset())

  private def feed(bytes: Array[Byte], endOfInput: Boolean): Unit =
    var input = bytes
    if decoder == null then
      undecided ++= bytes
      boms.find((bom, _) => startsWith(undecided, bom)) match
        case Some((bom, charset)) =>
          decoder = newDecoder(charset)
          input = undecided.drop(bom.length)
          undecided = Array.empty
        case None if !endOfInput && boms.exists((bom, _) => startsWith(bom, undecided)) => return
        case None =>
          decoder = newDecoder(StandardCharsets.UTF_8)
          input = undecided
          undecided = Array.empty
    val in = ByteBuffer.wrap(leftover ++ input)
    decode(in, endOfInput)
    leftover = new Array[Byte](in.remaining)
    in.get(leftover)

  private def startsWith(value: Array[Byte], prefix: Array[Byte]): Boolean =
    value.length >= prefix.length && prefix.indices.forall(index => value(index) == prefix(index))

  private def newDecoder(charset: Charset): CharsetDecoder =
    charset.newDecoder().nn
      .onMalformedInput(CodingErrorAction.REPLACE).nn
      .onUnmappableCharacter(CodingErrorAction.REPLACE).nn

  private def decode(in: ByteBuffer, endOfInput: Boolean): Unit =
    val current = decoder.nn
    val out = CharBuffer.allocate(in.remaining * 2 + 4)
    current.decode(in, out, endOfInput)
    if endOfInput then current.flush(out)
    out.flip()
    if out.hasRemaining then sink(out.toString)
