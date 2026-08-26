package atc.host

import atc.lib.ProcessResult

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** Running external processes with bounded, deadlock-free output capture */
object Processes:
  private val MaxStreamChars = 8 * 1024 * 1024
  private val TruncationMarker = "\n...[truncated: output exceeded 8 MiB cap]..."
  /** A command still running after this long has its output shown live from then on. */
  val LiveAfterMs = 1000L
  /** How much of a command's early output is kept to show when it goes live (the tail). */
  private val LiveBacklogChars = 64 * 1024
  /** How much of each stream a timeout error quotes. */
  private val TimeoutTailChars = 2000

  /** Where a long-running command's output is shown as it comes: `begin` once,
    * [[LiveAfterMs]] after the start when the command is still running, then
    * `output` for what it produced so far and for every chunk after that, from
    * the draining threads. A quick command is never shown this way. */
  trait LiveOutput:
    def begin(): Unit
    def output(text: String): Unit

  /** A bounded text buffer fed by a drain thread and read by the agent. With
    * `keepHead` the first `cap` characters are kept and the rest dropped (a
    * foreground command: its first megabytes carry the diagnostics); otherwise
    * the oldest text is dropped (a long-running process: the recent output
    * matters). Reads may consume, so an interactive session sees each chunk once. */
  private final class OutputBuffer(cap: Int, keepHead: Boolean):
    private val sb = StringBuilder()
    private var truncated = false
    def append(text: String): Unit = synchronized:
      if keepHead then
        val room = cap - sb.length
        if room > 0 then sb.append(text.take(room))
        if text.length > room then truncated = true
      else
        sb.append(text)
        if sb.length > cap then
          sb.delete(0, sb.length - cap)
          truncated = true
      notifyAll()
    /** The unread text, left in place. */
    def peek: String = synchronized(sb.toString)
    /** The unread text, consumed. */
    def take(): String = synchronized:
      val s = sb.toString
      sb.clear()
      s
    /** The first `n` unread characters, consumed. */
    def consume(n: Int): String = synchronized:
      val s = sb.substring(0, n)
      sb.delete(0, n)
      s
    /** Whether the cap ever dropped text (reported once, in `marker`). */
    def marker: String = synchronized:
      if !truncated then ""
      else if keepHead then TruncationMarker
      else "\n...[older output dropped: exceeded 8 MiB cap]...\n"
    /** Wait (at most `ms`) for more text to arrive. */
    def awaitChange(ms: Long): Unit = synchronized(wait(math.max(1L, ms)))

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

  /** A started pipeline: its stages, the stdout of the last and the stderr of
    * every stage draining into bounded buffers, optionally a live view, and a
    * watcher that reports the exit. Both a foreground `exec` (start, wait,
    * result) and a background `spawn` (talk to it, read as it goes) are built on it. */
  final class ManagedProcess private (
    procs: List[java.lang.Process],
    val stageLines: List[String],
    val line: String,
    private val stdoutBuf: OutputBuffer,
    private val stderrBufs: List[OutputBuffer],
    gate: Option[LiveGate],
  ):
    private val stdin = procs.head.getOutputStream.nn
    private var drains: List[Thread] = Nil
    @volatile private var stdinClosed = false

    def isAlive: Boolean = procs.exists(_.isAlive)
    /** Pipefail-style: the rightmost non-zero code, else 0; `None` while running. */
    def exitCode: Option[Int] =
      if isAlive then None else Some(procs.map(_.exitValue()).reverse.find(_ != 0).getOrElse(0))

    def send(text: String): Unit = synchronized:
      if stdinClosed then throw RuntimeException(s"the stdin of '$line' is closed")
      try
        stdin.write(text.getBytes(StandardCharsets.UTF_8))
        stdin.flush()
      catch
        case e: java.io.IOException =>
          throw RuntimeException(
            s"could not write to '$line' (exited? ${exitCode.fold("no")(c => s"yes, code $c")}): ${e.getMessage}"
          )
    def closeStdin(): Unit = synchronized:
      if !stdinClosed then
        stdinClosed = true
        try stdin.close()
        catch case _: java.io.IOException => ()

    /** Unread stdout, consumed; "" when there is none. */
    def read(): String = stdoutBuf.take()
    /** Unread stderr of every stage, consumed (labelled per stage when there are several). */
    def readErr(): String = labelled(stderrBufs.map(_.take()))
    private def labelled(texts: List[String]): String =
      if texts.lengthIs == 1 then texts.head
      else
        stageLines.zip(texts).zipWithIndex.collect {
          case ((l, t), i) if t.nonEmpty => s"[stage ${i + 1}: $l]\n" + (if t.endsWith("\n") then t else t + "\n")
        }.mkString

    /** Wait until `regex` matches the unread stdout (returns the text up to and
      * including the match, consumed), the process exits without it matching, or
      * `timeoutMs` passes; the latter two throw `RuntimeException` carrying what
      * did arrive (left unread, so `read()` can still fetch it). */
    def readUntil(regex: String, timeoutMs: Long): String =
      val pattern = java.util.regex.Pattern.compile(regex)
      val started = System.nanoTime()
      def tryMatch(): Option[String] =
        val text = stdoutBuf.peek
        val m = pattern.matcher(text)
        if m.find() then Some(stdoutBuf.consume(m.end())) else None
      var found = tryMatch()
      while found.isEmpty do
        if !isAlive then
          drains.foreach(_.join(500)) // let the last chunks land
          found = tryMatch()
          if found.isEmpty then
            throw RuntimeException(
              s"'$line' exited with code ${exitCode.getOrElse(-1)} before '$regex' matched; unread output:\n${stdoutBuf.peek.takeRight(TimeoutTailChars)}"
            )
        else
          val remaining = timeoutMs - (System.nanoTime() - started) / 1_000_000L
          if remaining <= 0 then
            throw RuntimeException(
              s"timed out after ${timeoutMs}ms waiting for '$regex' from '$line'; output so far (still unread):\n${stdoutBuf.peek.takeRight(TimeoutTailChars)}"
            )
          stdoutBuf.awaitChange(math.min(remaining, 200L)) // InterruptedException propagates (Ctrl-C)
          found = tryMatch()
      found.get

    /** Wait (at most `timeoutMs`) for every stage to exit; whether they did. */
    def awaitExit(timeoutMs: Long): Boolean =
      val started = System.nanoTime()
      procs.foreach { p =>
        val remaining = math.max(0L, timeoutMs - (System.nanoTime() - started) / 1_000_000L)
        if p.isAlive then p.waitFor(remaining, TimeUnit.MILLISECONDS)
      }
      !isAlive

    /** Let the drains deliver the last chunks; then everything unread, consumed. */
    def result(): ProcessResult =
      drains.foreach(_.join(5000))
      ProcessResult(exitCode.getOrElse(-1), stdoutBuf.take() + stdoutBuf.marker, labelled(stderrBufs.map(_.take())))

    /** The last part of each stream, unread text included, for an error message. */
    def tails: (String, String) =
      (stdoutBuf.peek.takeRight(TimeoutTailChars), stderrBufs.map(_.peek).mkString.takeRight(TimeoutTailChars))

    def goLive(): Unit = gate.foreach(_.goLive())

    private def descendants(): List[java.lang.ProcessHandle] =
      procs.flatMap { process =>
        val stream = process.descendants().nn
        try stream.iterator().nn.asScala.toList
        finally stream.close()
      }.distinct

    /** Terminate every descendant and pipeline stage, give them a moment, then
      * force what is left. Wrapper scripts on Windows commonly start the real
      * server as a child; killing only cmd/npm/gradlew would leak that server. */
    def kill(): Unit =
      val children = descendants()
      children.reverse.foreach(_.destroy())
      procs.foreach(_.destroy())
      val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000)
      var interrupted = false
      while !interrupted && (procs.exists(_.isAlive) || children.exists(_.isAlive)) && System.nanoTime() < deadline do
        try Thread.sleep(20)
        catch case _: InterruptedException => interrupted = true
      children.reverse.filter(_.isAlive).foreach(_.destroyForcibly())
      procs.filter(_.isAlive).foreach(_.destroyForcibly())
      if interrupted then Thread.currentThread().interrupt()
      ManagedProcess.live.remove(this)
      ()

  object ManagedProcess:
    /** Every started, not yet exited process tree of this JVM: killed at
      * shutdown so an agent's dev server cannot outlive atc. */
    private val live = java.util.concurrent.ConcurrentHashMap.newKeySet[ManagedProcess]().nn
    java.lang.Runtime.getRuntime.nn.addShutdownHook(Thread(() => live.forEach(_.kill())))

    /** Start the stages (one `ProcessBuilder` each; the caller may already have
      * redirected the first's input / the last's output to files), feed `stdin`
      * to the first (closing it either way when it is empty... no: closing it
      * only when `closeStdinAfter`: a foreground command gets EOF at once, a
      * spawned one keeps its stdin open for `send`), drain the streams into
      * buffers, and watch for the exit. */
    def start(
      pbs: List[ProcessBuilder],
      stageLines: List[String],
      line: String,
      stdin: String,
      closeStdinAfter: Boolean,
      live: Option[LiveOutput],
      keepHead: Boolean,
      onExit: Int => Unit,
    ): ManagedProcess =
      // Enforce strict Windows quoting at the actual launch boundary too:
      // callers of this low-level API may supply ProcessBuilders directly.
      WindowsExecutable.configureProcessRuntime()
      val procs: List[java.lang.Process] =
        if pbs.lengthIs == 1 then List(pbs.head.start().nn)
        else ProcessBuilder.startPipeline(pbs.asJava).nn.asScala.toList
      val gate = live.map(LiveGate(_))
      val m = ManagedProcess(
        procs,
        stageLines,
        line,
        OutputBuffer(MaxStreamChars, keepHead),
        procs.map(_ => OutputBuffer(MaxStreamChars, keepHead)),
        gate
      )
      ManagedProcess.live.add(m)
      // stdin: written on its own thread (a large input would deadlock against the drains).
      if stdin.isEmpty then
        if closeStdinAfter then m.closeStdin()
      else
        val feeder = Thread(() =>
          try m.send(stdin)
          catch case _: RuntimeException => ()
          finally if closeStdinAfter then m.closeStdin()
        )
        feeder.setDaemon(true)
        feeder.start()
      def drainer(stream: java.io.InputStream, into: OutputBuffer): Thread =
        Thread(() =>
          val text = TextSink(into.append)
          val shown = gate.map(g => TextSink(g.feed))
          val buf = new Array[Byte](8192)
          try
            var n = stream.read(buf)
            while n >= 0 do
              text.write(buf, 0, n)
              shown.foreach(_.write(buf, 0, n))
              n = stream.read(buf)
          catch case _: java.io.IOException => ()
          finally
            text.finish()
            shown.foreach(_.finish())
            try stream.close()
            catch case _: java.io.IOException => ()
        )
      m.drains = drainer(procs.last.getInputStream.nn, m.stdoutBuf) ::
        procs.zip(m.stderrBufs).map((p, b) => drainer(p.getErrorStream.nn, b))
      m.drains.foreach { t =>
        t.setDaemon(true); t.start()
      }
      val watcher = Thread(() =>
        procs.foreach(p =>
          try p.waitFor()
          catch case _: InterruptedException => ()
        )
        m.drains.foreach(_.join(5000))
        ManagedProcess.live.remove(m)
        onExit(m.exitCode.getOrElse(-1))
      )
      watcher.setDaemon(true)
      watcher.start()
      m

  /** Start `pb`, capture both streams (capped), enforce the timeout; with `live`,
    * show the output as it comes once the command has run for [[LiveAfterMs]].
    * Single-stage convenience over the pipeline form. */
  def run(pb: ProcessBuilder, name: String, timeoutMs: Long, live: Option[LiveOutput] = None): ProcessResult =
    run(List(pb), List(name), name, timeoutMs, live, "")

  /** Run a pipeline to completion: start the stages (see [[ManagedProcess.start]]),
    * feed `stdin` to the first and close it, wait at most `timeoutMs` for every
    * stage (switching the live view on after [[LiveAfterMs]]), kill them all on a
    * timeout (the error quotes the output so far), and return the last stage's
    * stdout, every stage's stderr (labelled when there are several) and the
    * pipefail-style exit code. */
  def run(
    pbs: List[ProcessBuilder],
    stageLines: List[String],
    name: String,
    timeoutMs: Long,
    live: Option[LiveOutput],
    stdin: String,
  ): ProcessResult =
    val m = ManagedProcess.start(pbs, stageLines, name, stdin, closeStdinAfter = true, live, keepHead = true, _ => ())
    try
      val firstWait = math.min(LiveAfterMs, timeoutMs)
      if !m.awaitExit(firstWait) then
        m.goLive()
        if !m.awaitExit(timeoutMs - firstWait) then
          m.kill()
          val (out, err) = m.tails
          throw RuntimeException(
            s"Process '$name' timed out after ${timeoutMs}ms (raise it with ExecOptions(timeoutMs = ...)); output so far:\n$out${
                if err.isEmpty then "" else s"\n[stderr]\n$err"
              }"
          )
      m.result()
    catch
      case e: Exception =>
        m.kill()
        throw e
