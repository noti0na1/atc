package atc.host

import atc.lib.ProcessResult

import java.util.concurrent.TimeUnit

/** Running external processes with bounded, deadlock-free output capture */
object Processes:
  private val MaxStreamBytes = 8 * 1024 * 1024
  private val TruncationMarker = "\n...[truncated: output exceeded 8 MiB cap]..."

  private final class StreamCapture:
    private val out = java.io.ByteArrayOutputStream()
    @volatile private var truncated = false
    def drain(stream: java.io.InputStream): Unit =
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
          n = stream.read(buf)
      finally stream.close()
    def text: String =
      val s = out.toString("UTF-8")
      if truncated then s + TruncationMarker else s

  /** Start `pb`, capture both streams (capped at 8 MiB each), enforce the timeout. */
  def run(pb: ProcessBuilder, name: String, timeoutMs: Long): ProcessResult =
    val process = pb.start().nn
    try
      val stdout = StreamCapture()
      val stderr = StreamCapture()
      val t1 = Thread(() => stdout.drain(process.getInputStream.nn))
      val t2 = Thread(() => stderr.drain(process.getErrorStream.nn))
      t1.setDaemon(true); t2.setDaemon(true)
      t1.start(); t2.start()
      val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
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
