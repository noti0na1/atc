package atc.evaluator

import java.io.*
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import java.util.concurrent.{
  ConcurrentHashMap,
  LinkedBlockingQueue,
  RejectedExecutionException,
  SynchronousQueue,
  ThreadPoolExecutor,
  TimeUnit
}
import scala.util.control.NonFatal

/** One message: a call, its return or error, or a cancellation of a conversation. */
private[atc] final class Frame(
  val kind: Byte,
  val id: Long,
  val conversation: Long,
  val method: String,
  val payload: Array[Byte]
)

private[atc] object Frame:
  final val Call: Byte = 1
  final val Return: Byte = 2
  final val Error: Byte = 3
  final val Cancel: Byte = 4
  /** Never sent: wakes the waiters when the channel closes. */
  final val Closed: Byte = 99

/** A failure the peer reported, recreated here as a plain exception of a known class. */
private[atc] final class RemoteFailure(val className: String, message: String) extends RuntimeException(message)

/** A symmetric call channel over two byte streams: the evaluator process's stdin and stdout.
  *
  * A frame is `int length | byte kind | long id | long conversation | short method length |
  * method | payload`. A conversation is one logical call stack spanning both processes: a
  * thread that calls out waits for its reply and meanwhile serves the calls the peer makes
  * on the same conversation, so a callback runs on the thread that is blocked in the outer
  * call (the thread that interruption and the REPL's stop flag reach), and a call made from
  * inside the callback goes back to the peer thread blocked in its own call. A call on a new
  * conversation, from a new thread of the peer, runs on a pool thread that adopts it. A
  * cancellation interrupts this side's thread serving a call of the conversation, and a
  * thread that stops waiting for a reply cancels its conversation, so that the peer stops
  * working for it.
  *
  * The host treats everything the evaluator sends as untrusted: a frame that is too large or
  * malformed closes the channel, and whoever owns the process ends it. A call this side
  * cannot take (on a conversation of its own that nobody waits in, or beyond
  * [[Channel.MaxServing]] at once) gets an error. A message too large to send fails here. */
private[atc] final class Channel(name: String, in: InputStream, out: OutputStream, conversationTag: Long):
  /** Runs an incoming call and returns its encoded result. */
  @volatile var handler: (String, Array[Byte]) => Array[Byte] =
    (method, _) => throw UnsupportedOperationException(s"$name: no handler for $method")
  @volatile var onCancel: Long => Unit = _ => ()
  @volatile var onClose: () => Unit = () => ()

  private val input = DataInputStream(BufferedInputStream(in, 1 << 16))
  private val output = DataOutputStream(BufferedOutputStream(out, 1 << 16))
  private val ids = AtomicLong()
  private val conversations = AtomicLong()
  private val mailboxes = ConcurrentHashMap[java.lang.Long, LinkedBlockingQueue[Frame]]()
  private val bound = ThreadLocal[java.lang.Long]()
  /** The thread serving a nested call of each conversation, which a cancellation interrupts. */
  private val nested = ConcurrentHashMap[java.lang.Long, Thread]()
  /** The pool thread serving a conversation the peer started, which a cancellation interrupts. */
  private val serving = ConcurrentHashMap[java.lang.Long, Thread]()
  @volatile private var closed = false

  private val threadCount = AtomicInteger()
  private val pool = ThreadPoolExecutor(
    0,
    Channel.MaxServing,
    60L,
    TimeUnit.SECONDS,
    SynchronousQueue[Runnable](),
    runnable =>
      // The REPL compiles and runs agent code on these threads: give them the launcher's stack size.
      val thread = Thread(null, runnable, s"$name-serve-${threadCount.incrementAndGet()}", 4L << 20)
      thread.setDaemon(true)
      thread
  )

  def isClosed: Boolean = closed

  def start(): this.type =
    val reader = Thread(() => readLoop(), s"$name-reader")
    reader.setDaemon(true)
    reader.start()
    this

  def close(): Unit =
    closed = true
    try in.close()
    catch case NonFatal(_) => ()
    try out.close()
    catch case NonFatal(_) => ()

  /** The conversation of the calling thread: allocated on first use and kept. */
  def conversation: Long =
    val existing = bound.get()
    if existing != null then existing.longValue
    else
      val created = (conversations.incrementAndGet() << 1) | conversationTag
      bound.set(created)
      created

  /** Ask the peer to stop `conversation`, and interrupt this side's thread serving a call of it. */
  def cancel(conversation: Long): Unit =
    send(Frame(Frame.Cancel, 0, conversation, "", Array.emptyByteArray))
    Option(nested.get(conversation)).foreach(_.interrupt())

  /** Call `method` on the peer and return its encoded result, serving the peer's calls of the
    * same conversation meanwhile. */
  def call(method: String, payload: Array[Byte]): Array[Byte] =
    if closed then throw IOException(s"$name: the channel is closed")
    val conversation = this.conversation
    var mailbox = mailboxes.get(conversation)
    val owned = mailbox == null
    if owned then
      mailbox = LinkedBlockingQueue()
      mailboxes.put(conversation, mailbox)
    val id = ids.incrementAndGet()
    try
      send(Frame(Frame.Call, id, conversation, method, payload))
      var result: Array[Byte] | Null = null
      while result == null do
        val frame =
          try mailbox.take().nn
          catch
            case e: InterruptedException =>
              // This thread gives up on the call: the peer need not finish it.
              if !closed then
                try send(Frame(Frame.Cancel, 0, conversation, "", Array.emptyByteArray))
                catch case NonFatal(_) => ()
              throw e
        frame.kind match
          case Frame.Return if frame.id == id => result = frame.payload
          case Frame.Error if frame.id == id => throw Channel.decodeError(frame.payload)
          case Frame.Call =>
            nested.put(conversation, Thread.currentThread())
            try serve(frame, topLevel = false)
            finally
              nested.remove(conversation)
              Thread.interrupted() // a cancellation aimed at the nested call must not end this wait
          case Frame.Closed => throw IOException(s"$name: the channel closed during $method")
          case _ => () // a late reply to a call this thread gave up on
      result.nn
    finally if owned then mailboxes.remove(conversation)

  def call(method: String)(args: Encoder => Unit): Decoder =
    val encoder = Encoder()
    args(encoder)
    Decoder(call(method, encoder.result))

  private def send(frame: Frame): Unit = output.synchronized:
    val method = frame.method.getBytes(UTF_8)
    val length = Channel.HeaderBytes + method.length.toLong + frame.payload.length
    if length > Channel.MaxFrameBytes then
      val what = if frame.method.isEmpty then "a message" else s"the call ${frame.method}"
      throw IllegalArgumentException(
        s"$what is $length bytes, more than the channel carries (${Channel.MaxFrameBytes >> 20} MiB)"
      )
    output.writeInt(length.toInt)
    output.writeByte(frame.kind)
    output.writeLong(frame.id)
    output.writeLong(frame.conversation)
    output.writeShort(method.length)
    output.write(method)
    output.write(frame.payload)
    output.flush()

  private def readFrame(): Frame | Null =
    val length =
      try input.readInt()
      catch case _: EOFException => return null
    if length < Channel.HeaderBytes || length > Channel.MaxFrameBytes then
      throw IOException(s"$name: bad frame length $length")
    val kind = input.readByte()
    val id = input.readLong()
    val conversation = input.readLong()
    val methodLength = input.readUnsignedShort()
    if methodLength > length - Channel.HeaderBytes then throw IOException(s"$name: bad method length")
    val method = new Array[Byte](methodLength)
    input.readFully(method)
    val payload = new Array[Byte](length - Channel.HeaderBytes - methodLength)
    input.readFully(payload)
    Frame(kind, id, conversation, String(method, UTF_8), payload)

  private def readLoop(): Unit =
    try
      var frame = readFrame()
      while frame != null do
        dispatch(frame.nn)
        frame = readFrame()
    catch case NonFatal(_) => ()
    finally
      closed = true
      mailboxes.values.forEach(_.offer(Frame(Frame.Closed, 0, 0, "", Array.emptyByteArray)))
      onClose()

  private def dispatch(frame: Frame): Unit = frame.kind match
    case Frame.Return | Frame.Error =>
      Option(mailboxes.get(frame.conversation)).foreach(_.offer(frame)) // else the caller gave up
    case Frame.Call =>
      Option(mailboxes.get(frame.conversation)) match
        case Some(mailbox) => mailbox.offer(frame) // a thread of the conversation waits and serves it
        case None if (frame.conversation & 1) == conversationTag =>
          // One of this side's conversations that nobody waits in any more: a late callback.
          refuse(frame, "the call it belongs to has ended")
        case None =>
          mailboxes.put(frame.conversation, LinkedBlockingQueue()) // before the pool thread runs
          try
            pool.execute: () =>
              bound.set(frame.conversation)
              serving.put(frame.conversation, Thread.currentThread())
              try serve(frame, topLevel = true)
              finally
                serving.remove(frame.conversation)
                bound.remove()
                Thread.interrupted() // a cancellation that came late must not reach the next call
          catch
            case _: RejectedExecutionException =>
              mailboxes.remove(frame.conversation)
              refuse(frame, s"more than ${Channel.MaxServing} calls are running at once")
    case Frame.Cancel =>
      Option(nested.get(frame.conversation)).foreach(_.interrupt())
      Option(serving.get(frame.conversation)).foreach(_.interrupt())
      onCancel(frame.conversation)
    case other => throw IOException(s"$name: unknown frame kind $other")

  /** Answer `frame` with an error without running it. */
  private def refuse(frame: Frame, why: String): Unit =
    try send(Frame(Frame.Error, frame.id, frame.conversation, "", Channel.encodeError(IllegalStateException(why))))
    catch case NonFatal(_) => ()

  /** Run a call and reply. A top-level server releases its mailbox first: once the reply is
    * sent, the peer may start a new top-level call on the same conversation. */
  private def serve(frame: Frame, topLevel: Boolean): Unit =
    var fatal: Throwable | Null = null
    val reply =
      try
        val result = handler(frame.method, frame.payload)
        if result.length > Channel.MaxFrameBytes - Channel.HeaderBytes then
          throw IllegalStateException(
            s"the result of ${frame.method} is ${result.length} bytes, more than the channel carries (${Channel.MaxFrameBytes >>
                20} MiB)"
          )
        Frame(Frame.Return, frame.id, frame.conversation, "", result)
      catch
        case e: Throwable =>
          if !NonFatal(e) && !e.isInstanceOf[InterruptedException] then fatal = e
          Frame(Frame.Error, frame.id, frame.conversation, "", Channel.encodeError(e))
    if topLevel then mailboxes.remove(frame.conversation)
    if !closed then
      try send(reply)
      catch case NonFatal(_) => ()
    if fatal != null then throw fatal.nn

private[atc] object Channel:
  /** kind, id, conversation and method length. */
  val HeaderBytes: Int = 1 + 8 + 8 + 2
  /** The largest frame either side accepts: file contents and command output travel in frames. */
  val MaxFrameBytes: Int = 64 * 1024 * 1024
  /** The most calls the peer may have running here at once: the agent's thread, the REPL's and
    * `parallel`'s tasks need far fewer. */
  val MaxServing: Int = 64
  /** What the evaluator writes first on its stdout, so the host knows the channel begins there. */
  val Greeting: Array[Byte] = "ATC-EVALUATOR-1\n".getBytes(UTF_8)

  def encodeError(e: Throwable): Array[Byte] =
    Encoder().string(e.getClass.getName).string(Option(e.getMessage).getOrElse(e.toString)).result

  /** The peer's failure as an exception of the same class where that class is a plain,
    * well-known one; anything else becomes a [[RemoteFailure]] carrying the message. */
  def decodeError(bytes: Array[Byte]): Throwable =
    val decoder = Decoder(bytes)
    val className = decoder.string()
    val message = decoder.string()
    className match
      case "java.lang.SecurityException" => SecurityException(message)
      case "java.lang.IllegalArgumentException" => IllegalArgumentException(message)
      case "java.lang.IllegalStateException" => IllegalStateException(message)
      case "java.lang.UnsupportedOperationException" => UnsupportedOperationException(message)
      case "java.lang.RuntimeException" => RuntimeException(message)
      case "java.lang.InterruptedException" => InterruptedException(message)
      case "java.io.IOException" | "java.nio.file.NoSuchFileException" | "java.nio.file.FileAlreadyExistsException" =>
        IOException(message)
      case _ => RemoteFailure(className, message)

/** A positional binary encoding of call arguments and results. */
private[atc] final class Encoder:
  private val bytes = ByteArrayOutputStream(64)
  private val data = DataOutputStream(bytes)
  def string(s: String): Encoder = { val b = s.getBytes(UTF_8); data.writeInt(b.length); data.write(b); this }
  def int(i: Int): Encoder = { data.writeInt(i); this }
  def long(l: Long): Encoder = { data.writeLong(l); this }
  def bool(b: Boolean): Encoder = { data.writeBoolean(b); this }
  def bytes(b: Array[Byte]): Encoder = { data.writeInt(b.length); data.write(b); this }
  def strings(xs: Iterable[String]): Encoder = { int(xs.size); xs.foreach(string); this }
  def stringMap(m: Map[String, String]): Encoder = { int(m.size); m.foreach((k, v) => { string(k); string(v) }); this }
  def optionalString(o: Option[String]): Encoder = { bool(o.isDefined); o.foreach(string); this }
  def optionalLong(o: Option[Long]): Encoder = { bool(o.isDefined); o.foreach(long); this }
  def result: Array[Byte] = { data.flush(); bytes.toByteArray.nn }

private[atc] final class Decoder(payload: Array[Byte]):
  private val data = DataInputStream(ByteArrayInputStream(payload))
  def string(): String = String(bytes(), UTF_8)
  def int(): Int = data.readInt()
  def long(): Long = data.readLong()
  def bool(): Boolean = data.readBoolean()
  def bytes(): Array[Byte] =
    val length = data.readInt()
    if length < 0 || length > data.available() then throw IOException("bad length in a message")
    val b = new Array[Byte](length)
    data.readFully(b)
    b
  def strings(): List[String] = List.fill(count())(string())
  def stringMap(): Map[String, String] = List.fill(count())((string(), string())).toMap
  def optionalString(): Option[String] = if bool() then Some(string()) else None
  def optionalLong(): Option[Long] = if bool() then Some(long()) else None
  private def count(): Int =
    val n = data.readInt()
    if n < 0 || n > data.available() then throw IOException("bad count in a message")
    n
