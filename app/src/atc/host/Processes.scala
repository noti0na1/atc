package atc.host

import atc.lib.ProcessResult

import java.util.concurrent.TimeUnit

/** Running external processes with bounded, deadlock-free output capture */
object Processes:
  private val MaxStreamBytes = 8 * 1024 * 1024
  private val TruncationMarker = "\n...[truncated: output exceeded 8 MiB cap]..."
  /** A command still running after this long has its output shown live from then on. */
  val LiveAfterMs = 1000L
  /** How much of a command's early output is kept to show when it goes live (the tail). */
  private val LiveBacklogChars = 64 * 1024

  /** Where a long-running command's output is shown as it comes: `begin` once,
    * [[LiveAfterMs]] after the start when the command is still running, then
    * `output` for what it produced so far and for every chunk after that, from
    * the draining threads. A quick command is never shown this way. */
  trait LiveOutput:
    def begin(): Unit
    def output(text: String): Unit

  private final class StreamCapture:
    private val out = java.io.ByteArrayOutputStream()
    @volatile private var truncated = false
    def drain(stream: java.io.InputStream, live: Option[TextSink]): Unit =
      val buf = new Array[Byte](8192)
      try
        var n = stream.read(buf)
        while n >= 0 do
          val room = MaxStreamBytes - out.size()
          if room > 0 then
            val keep = math.min(room, n)
            out.write(buf, 0, keep)
            if keep < n then truncated = true
          else truncated = true
          live.foreach(_.write(buf, 0, n))
          n = stream.read(buf)
      finally
        stream.close()
        live.foreach(_.finish())
    def text: String =
      val s = out.toString("UTF-8")
      if truncated then s + TruncationMarker else s

  /** The gate between the draining threads and the live view: text is held
    * back until `goLive()` (keeping at most the last [[LiveBacklogChars]]),
    * then passed straight on. */
  private final class LiveGate(view: LiveOutput):
    private var live = false
    private val backlog = StringBuilder()
    def feed(text: String): Unit = synchronized:
      if live then view.output(text)
      else
        backlog.append(text)
        if backlog.length > LiveBacklogChars then backlog.delete(0, backlog.length - LiveBacklogChars)
    def goLive(): Unit = synchronized:
      if !live then
        live = true
        view.begin()
        if backlog.nonEmpty then view.output(backlog.toString)
        backlog.clear()

  /** Start `pb`, capture both streams (capped at 8 MiB each), enforce the
    * timeout; with `live`, show the output as it comes once the command has
    * run for [[LiveAfterMs]]. */
  def run(pb: ProcessBuilder, name: String, timeoutMs: Long, live: Option[LiveOutput] = None): ProcessResult =
    val process = pb.start().nn
    try
      val stdout = StreamCapture()
      val stderr = StreamCapture()
      val gate = live.map(LiveGate(_))
      def sink() = gate.map(g => TextSink(g.feed))
      val t1 = Thread(() => stdout.drain(process.getInputStream.nn, sink()))
      val t2 = Thread(() => stderr.drain(process.getErrorStream.nn, sink()))
      t1.setDaemon(true); t2.setDaemon(true)
      t1.start(); t2.start()
      val firstWait = math.min(LiveAfterMs, timeoutMs)
      var finished = process.waitFor(firstWait, TimeUnit.MILLISECONDS)
      if !finished && timeoutMs > firstWait then
        gate.foreach(_.goLive())
        finished = process.waitFor(timeoutMs - firstWait, TimeUnit.MILLISECONDS)
      if !finished then
        process.destroyForcibly()
        t1.join(1000); t2.join(1000)
        throw RuntimeException(s"Process '$name' timed out after ${timeoutMs}ms")
      t1.join(5000); t2.join(5000)
      ProcessResult(process.exitValue(), stdout.text, stderr.text)
    catch
      case e: Exception =>
        process.destroyForcibly()
        throw e
