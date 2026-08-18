package atc.sandbox

import atc.lib.Interface
import atc.perms.Mode

import dotty.tools.repl.*
import dotty.tools.dotc.reporting.Diagnostic

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CountDownLatch, TimeUnit}

object ReplSession:
  val MaxOutputBytes: Int = 4 * 1024 * 1024
  val TruncationMarker: String = "\n... [output truncated: exceeded 4 MiB capture limit]"

  /** Bounded capture buffer: retains at most `limit` bytes. */
  final class BoundedOutputStream(limit: Int) extends java.io.OutputStream:
    private val buf = new java.io.ByteArrayOutputStream(math.min(limit, 8192))
    @volatile var truncated: Boolean = false
    override def write(b: Int): Unit =
      if buf.size() < limit then buf.write(b) else truncated = true
    override def write(b: Array[Byte], off: Int, len: Int): Unit =
      val room = limit - buf.size()
      if room <= 0 then truncated = true
      else
        if len > room then truncated = true
        buf.write(b, off, math.min(len, room))
    def resetCapture(): Unit = { buf.reset(); truncated = false }
    def capturedString: String = buf.toString(StandardCharsets.UTF_8)

  private class OpenReplDriver(settings: Array[String], out: PrintStream, cl: Option[ClassLoader], maxEchoChars: Int)
      extends ReplDriver(settings, out, cl):
    // Replace the stock renderer before the first evaluation (it creates the REPL class loader lazily).
    rendering = CappedRendering(cl, maxEchoChars)
    def runParseResult(res: ParseResult)(using State): State = runBody(interpret(res))
    /** The loader of the REPL-defined classes (null before the first evaluation). */
    def replClassLoader: ClassLoader | Null = rendering.myClassLoader
    /** Raise/clear the stop flag checked by the instrumented REPL classes
      * (loop back-edges and method entries throw `ThreadDeath` while it is set). */
    def setStopFlag(stop: Boolean): Unit =
      val loader = replClassLoader
      if loader != null then ReplBytecodeInstrumentation.setStopFlag(loader, stop)

  private def compilerArgs(classpath: String): Array[String] = Array(
    "-classpath",
    classpath,
    "-color:never",
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Yexplicit-nulls",
    "-Wsafe-init",
    "-language:experimental.captureChecking",
    "-language:experimental.modularity",
    // Interrupt instrumentation makes loops in REPL-defined classes stoppable
    // (`local` would instrument nothing at all). Library classes are protected
    // from being re-defined by `Sandbox.SandboxLoader`, which hides their
    // bytecode resources from the REPL loader.
    "-Xrepl-interrupt-instrumentation:true",
    // Every top-level `val` bound to a Java-returning call (`s.replace(...)`)
    // would warn that it "exposes a flexible type": noise for agent code.
    "-Wconf:msg=exposes a flexible type:s",
  )

  /** Definitions in scope before any agent code runs, for `mode`.
    *
    * `object api` holds the host and the root capability privately and
    * publishes, as givens, only the capabilities of the mode:
    *
    *  - full: `io: IOCap^` (the root itself) and `fs`/`ex`/`net` derived from it;
    *  - local: `io: IOCap` (a read-only view of the root), a full `fs: FileSystem^`
    *    and `ex: Exec^` derived from the hidden root, with no network and no
    *    full `io` (so no way to derive one);
    *  - read-only: `io: IOCap` and the read-only `fs` derived from it.
    *
    * `atc.lib.Runtime.current`/`.rootIO` are `@rejectSafe`: the preamble is
    * compiled before the safe-mode import, agent code after it, so agent code
    * cannot name them (nor `api.host`/`api.root`, which are private).
    *
    * The chunks are loaded as separate REPL rounds (`init`): the base
    * (`object api` + imports) first, then **each given on its own round**. That
    * isolation matters for capture checking: each given becomes a field of its
    * own line-wrapper object, so a pure `Classified.map` that reads a file
    * captures only the `fs` wrapper, not the always-full `user`/`io` givens,
    * which live in other wrappers. If all givens shared one wrapper, capturing
    * `fs` would pull in that full `user`/`io` and the read would be rejected. */
  def preambleChunks(mode: Mode): List[String] =
    val base =
      """|import language.experimental.captureChecking
         |import atc.lib.*
         |import caps.*
         |@assumeSafe object api:
         |  private val host: Interface = atc.lib.Runtime.current
         |  export host.*
         |import api.*""".stripMargin
    val givens = mode match
      case Mode.Full =>
        List(
          "@assumeSafe given io: (IOCap^) = atc.lib.Runtime.rootIO",
          "@assumeSafe given user: (UserIO^) = atc.lib.Runtime.rootUser",
          "@assumeSafe given fs: (FileSystem^{io}) = fileSystem",
          "@assumeSafe given ex: (Exec^{io}) = processes",
          "@assumeSafe given net: (Network^{io}) = network",
        )
      case Mode.Local =>
        List(
          "@assumeSafe given io: IOCap = atc.lib.Runtime.rootIO",
          "@assumeSafe given user: (UserIO^) = atc.lib.Runtime.rootUser",
          "@assumeSafe given fs: (FileSystem^) = fileSystem(using atc.lib.Runtime.rootIO)",
          "@assumeSafe given ex: (Exec^) = processes(using atc.lib.Runtime.rootIO)",
        )
      case Mode.ReadOnly =>
        List(
          "@assumeSafe given io: IOCap = atc.lib.Runtime.rootIO",
          "@assumeSafe given user: (UserIO^) = atc.lib.Runtime.rootUser",
          "@assumeSafe given fs: (FileSystem^{io.rd}) = readOnlyFileSystem",
        )
    base :: givens

  /** The full preamble as one string (for display, e.g. `:imports`). */
  def preamble(mode: Mode): String = preambleChunks(mode).mkString("\n")

  val safeModeImport: String = "import language.experimental.safe"

  private val exceptionHead = """^[\w.$]+(Exception|Error)(:.*)?$""".r
  private val traceLine = """^\s+(at |\.\.\. \d+ (more|elided)).*$""".r

  /** The REPL prints uncaught exceptions (with an elided stack trace) instead
    * of failing the evaluation; detect that so the agent gets `isError`. */
  def looksLikeUncaughtException(output: String): Boolean =
    val lines = output.linesIterator.toVector
    lines.indices.exists { i =>
      exceptionHead.matches(lines(i)) && i + 1 < lines.length && traceLine.matches(lines(i + 1))
    }

  /** System.out/err are swapped around each evaluation to capture compiler
    * diagnostics that bypass the driver's stream; that is process-global, so
    * evaluations are serialized. A `ReentrantLock` (rather than `synchronized`)
    * lets a run give up when a previous evaluation was interrupted but its
    * thread never died: that thread holds the stream forever, and waiting on
    * it with `synchronized` would wedge the whole process. */
  private val outputLock = java.util.concurrent.locks.ReentrantLock()
  private val OutputLockWaitMs = 10000L

  /** The lock is JVM-wide (so is `System.out`): a new session cannot recover
    * from a stuck evaluation either, only a restart of the process can. */
  val StuckEvaluationMessage: String =
    "A previous evaluation is still running (it could not be stopped) and holds the output stream, " +
      "so nothing can be evaluated until atc is restarted; tell the user."

  /** What one evaluation produced, before it is adopted into the session. */
  private case class Evaluated(state: State, output: String, thrown: Option[Throwable])

/** One persistent REPL with its own sandbox class loader and host. */
final class ReplSession(config: SandboxConfig, host: Interface, preambleOverride: Option[String] = None):
  import ReplSession.*

  private val outputCapture = BoundedOutputStream(MaxOutputBytes)
  /** Where agent-visible output goes: REPL results, diagnostics, and (via the
    * host's `print`) the agent's own `println` calls. */
  val printStream: PrintStream = PrintStream(outputCapture, true, StandardCharsets.UTF_8)
  val clock: ExecutionClock = ExecutionClock()

  private val classpath = Sandbox.libraryClasspath.map(_.toString).mkString(java.io.File.pathSeparator)
  private val driver =
    OpenReplDriver(compilerArgs(classpath), printStream, Some(Sandbox.newLoader()), config.maxEchoChars)
  private var state: State = driver.initialState
  @volatile private var evalThread: Thread | Null = null
  /** Set while a stop (interrupt or timeout) is in flight for the current run. */
  @volatile private var stopRequested: Boolean = false

  /** Load the preamble; errors here are programmer bugs and are thrown. Each
    * chunk is a separate REPL round so that every given lands in its own
    * line-wrapper (see `preambleChunks`); a `preambleOverride` runs as one round. */
  def init(): this.type =
    Sandbox.installHost(host)
    val chunks = preambleOverride.map(List(_)).getOrElse(preambleChunks(config.mode))
    chunks.foreach(setUp("Sandbox preamble", _))
    if config.safeMode then setUp("Safe mode import", safeModeImport)
    this

  /** Evaluate one set-up round, failing loudly: nothing the agent writes has run yet. */
  private def setUp(what: String, code: String): Unit =
    val (out, thrown) = withOutputCapture() { state = driver.run(code)(using state) }
    thrown.foreach(throw _)
    if out.toLowerCase.contains("error") then throw IllegalStateException(s"$what failed to compile:\n$out")

  def close(): Unit = ()

  /** Interrupt a running evaluation (best effort): raise the REPL stop flag
    * for loops in agent code and interrupt the thread for blocking calls. */
  def interrupt(): Unit =
    val t = evalThread
    if t != null then
      stopRequested = true
      driver.setStopFlag(true)
      t.interrupt()

  def run(code: String): ExecutionResult =
    clock.reset() // per run, whichever way it ends (callers read `clock.paused` afterwards)
    val violations = CodeValidator.validate(code)
    if violations.nonEmpty then ExecutionResult(false, "", Some(CodeValidator.formatErrors(violations)))
    else
      stopRequested = false
      ParseResult(code.stripTrailing() + "\n")(using state) match
        case p: Parsed => dispatch(p)
        case cmd @ (_: TypeOf | _: DocOf | Imports) => dispatch(cmd)
        case _: Command => ExecutionResult(false, "", Some("Only :type, :doc, and :imports REPL commands are allowed."))
        case Newline => ExecutionResult(true, "")
        case SyntaxErrors(_, errors, _) =>
          ExecutionResult(false, "", Some("Syntax error:\n" + formatDiagnostics(errors)))
        case other => ExecutionResult(false, "", Some(s"Unexpected parse result: $other"))

  private def dispatch(res: ParseResult): ExecutionResult =
    config.executionTimeoutMs match
      case None => adopt(res, evaluate(res))
      case Some(limit) => dispatchWithTimeout(res, limit)

  /** `started` is released once the output stream is ours (or we gave up waiting for it). */
  private def evaluate(res: ParseResult, started: CountDownLatch = CountDownLatch(0)): Evaluated =
    var newState = state
    val (output, thrown) = withOutputCapture(onEnter = started.countDown()) {
      driver.setStopFlag(false) // a previous evaluation may have been stopped
      newState = driver.runParseResult(res)(using state)
    }
    Evaluated(newState, output, thrown)

  /** Take over the new state and turn the evaluation into the agent-visible result. */
  private def adopt(res: ParseResult, evaluated: Evaluated): ExecutionResult =
    val Evaluated(newState, output, thrown) = evaluated
    state = newState
    if stopRequested then
      // The evaluation was interrupted: its wrapper class may be half-initialized
      // (`ThreadDeath` from the stop check surfaces as an `ExceptionInInitializerError`
      // the REPL renders as normal output). Skip that wrapper index so the next
      // line does not collide with the poisoned class, and report the abort.
      skipPoisonedWrapper()
      ExecutionResult(false, "", Some("Execution interrupted by the user (session state unchanged)"))
    else
      thrown match
        case Some(e) => ExecutionResult(false, output, Option(e.getMessage).orElse(Some(e.toString)))
        case None =>
          val compileFailed = res match
            case p: Parsed => p.reporter.hasErrors
            case _ => false
          ExecutionResult(!compileFailed && !looksLikeUncaughtException(output), output)

  /** After a stopped evaluation, advance past the (possibly poisoned) wrapper
    * index and mark it invalid, so a later line does not reuse the class name. */
  private def skipPoisonedWrapper(): Unit =
    state = state.copy(
      objectIndex = state.objectIndex + 1,
      invalidObjectIndexes = state.invalidObjectIndexes + state.objectIndex,
    )

  private def dispatchWithTimeout(res: ParseResult, limitMs: Long): ExecutionResult =
    val resultRef = java.util.concurrent.atomic.AtomicReference[Evaluated]()
    val started = CountDownLatch(1)
    val worker = Thread(() => resultRef.set(evaluate(res, started)))
    worker.setName("atc-repl-eval")
    worker.setDaemon(true)
    evalThread = worker
    worker.start()
    try
      // The timeout is execution time: it starts once the worker owns the output
      // stream (a stuck previous evaluation may hold it for up to OutputLockWaitMs,
      // after which the worker gives up and reports that instead), and does not
      // count time the host spends waiting for the user.
      while worker.isAlive && !started.await(50, TimeUnit.MILLISECONDS) do ()
      val start = System.nanoTime()
      var remaining = limitMs
      while worker.isAlive && remaining > 0 do
        worker.join(math.min(remaining, 200L))
        remaining = limitMs - (System.nanoTime() - start - clock.paused) / 1_000_000L
      if worker.isAlive then
        interrupt()
        worker.join(2000)
        skipPoisonedWrapper()
        val note = if worker.isAlive then "; the evaluation could not be stopped and is still running" else ""
        ExecutionResult(false, "", Some(s"Execution timed out after ${limitMs}ms (session state unchanged)$note"))
      else
        resultRef.get() match
          case null => ExecutionResult(false, "", Some("Execution failed (no result; possible fatal error)"))
          case evaluated => adopt(res, evaluated)
    finally evalThread = null

  /** Run `run` with System.out/err captured; `onEnter` fires as soon as the
    * wait for the (process-wide) output stream is over, either way. */
  private def withOutputCapture(onEnter: => Unit = ())(run: => Unit): (String, Option[Throwable]) =
    val acquired =
      try outputLock.tryLock(OutputLockWaitMs, TimeUnit.MILLISECONDS)
      catch case _: InterruptedException => false
    onEnter
    if !acquired then ("", Some(RuntimeException(StuckEvaluationMessage)))
    else
      try
        outputCapture.resetCapture()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(printStream)
        System.setErr(printStream)
        val thrown =
          try { run; None }
          catch case scala.util.control.NonFatal(e) => Option(e)
          finally
            System.setOut(oldOut)
            System.setErr(oldErr)
            printStream.flush()
        // Only trailing whitespace is dropped: the UI removes the agent's own
        // prints (already shown live) from this text and needs an exact match.
        val captured = outputCapture.capturedString.stripTrailing()
        val output = if outputCapture.truncated then captured + TruncationMarker else captured
        (output, thrown)
      finally outputLock.unlock()

  private def formatDiagnostics(diags: List[Diagnostic]): String =
    diags.map: d =>
      val pos = d.pos
      if pos != null && pos.exists then s"Line ${pos.line + 1}: ${d.message}" else d.message
    .mkString("\n")
