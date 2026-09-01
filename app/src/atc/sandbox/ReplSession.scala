package atc.sandbox

import atc.lib.{Derivations, Interface}
import atc.perms.Mode
import atc.platform.Platform

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
    private val capped = CappedRendering(cl, maxEchoChars)
    rendering = capped
    def runParseResult(res: ParseResult)(using State): State = runBody(interpret(res))
    /** Whether an evaluation threw an uncaught exception since the last reset. */
    def evaluationThrew: Boolean = capped.threw
    def resetEvaluationThrew(): Unit = capped.resetThrew()
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
    * `object api` holds the host privately; the preamble publishes, as givens,
    * only the root view and derived capabilities of the mode:
    *
    *  - full: `io: IOCap^` (the root itself) and `fs`/`ex`/`net` derived from it;
    *  - local: `io: IOCap^` (the root itself) and `fs`/`ex` derived from it,
    *    with no network capability;
    *  - read-only: `io: IOCap` and the read-only `fs` derived from it.
    *
    * The root records the capture hierarchy; it is not itself a factory. The
    * `Runtime` derivations mint the leaves, and agent code cannot call them, so
    * local mode cannot produce the omitted `net` from its full `io`.
    *
    * `atc.lib.Runtime.current`/`.rootIO` are `@rejectSafe`: the preamble is
    * compiled before the safe-mode import, agent code after it, so agent code
    * cannot name them (nor `api.host`/`api.root`, which are private).
    *
    * The chunks are loaded as separate REPL rounds (`init`): the base
    * (`object api` + imports) first, then **each given on its own round**. That
    * isolation matters for capture checking: each given becomes a field of its
    * own line-wrapper object, so a pure `Classified.map` that reads a file
    * captures only the `fs` wrapper, not the separate `user`/`io` givens,
    * which live in other wrappers. If all givens shared one wrapper, capturing
    * `fs` would also pull in the full `user` capability and the read would be
    * rejected. */
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
          "@assumeSafe given fs: (FileSystem^{io}) = atc.lib.Runtime.fileSystem",
          "@assumeSafe given ex: (Exec^{io}) = atc.lib.Runtime.processes",
          "@assumeSafe given net: (Network^{io}) = atc.lib.Runtime.network",
        )
      case Mode.Local =>
        List(
          "@assumeSafe given io: (IOCap^) = atc.lib.Runtime.rootIO",
          "@assumeSafe given user: (UserIO^) = atc.lib.Runtime.rootUser",
          "@assumeSafe given fs: (FileSystem^{io}) = atc.lib.Runtime.fileSystem",
          "@assumeSafe given ex: (Exec^{io}) = atc.lib.Runtime.processes",
        )
      case Mode.ReadOnly =>
        List(
          "@assumeSafe given io: IOCap = atc.lib.Runtime.rootIO",
          "@assumeSafe given user: (UserIO^) = atc.lib.Runtime.rootUser",
          "@assumeSafe given fs: (FileSystem^{io.rd}) = atc.lib.Runtime.readOnlyFileSystem",
        )
    base :: givens

  val safeModeImport: String = "import language.experimental.safe"

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

  /** What one evaluation produced, before it is adopted into the session:
    * the new state, the captured output, what escaped the driver (`thrown`),
    * and whether agent code threw an exception the REPL rendered (`failed`). */
  private case class Evaluated(state: State, output: String, thrown: Option[Throwable], failed: Boolean)

/** One persistent REPL with its own sandbox class loader and host. */
final class ReplSession(config: SandboxConfig, host: Interface & Derivations, preambleOverride: Option[String] = None):
  import ReplSession.*

  private val outputCapture = BoundedOutputStream(MaxOutputBytes)
  /** Where agent-visible output goes: REPL results, diagnostics, and (via the
    * host's `print`) the agent's own `println` calls. */
  val printStream: PrintStream = PrintStream(outputCapture, true, StandardCharsets.UTF_8)
  val clock: ExecutionClock = ExecutionClock()

  private val classpath = Sandbox.libraryClasspath.map(_.toString).mkString(Platform.pathListSeparator)
  private val driver =
    OpenReplDriver(compilerArgs(classpath), printStream, Some(Sandbox.newLoader()), config.maxEchoChars)
  private var state: State = driver.initialState
  @volatile private var evalThread: Thread | Null = null
  /** Set while a stop (interrupt or timeout) is in flight for the current run. */
  @volatile private var stopRequested: Boolean = false
  @volatile private var closed: Boolean = false

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
    val (out, thrown) = withOutputCapture() {
      // Runtime's bootstrap slot is process-global. The same JVM may host more
      // than one session, so select this session's host while holding the same
      // process-wide lock that serializes evaluation and lazy wrapper loading.
      Sandbox.installHost(host)
      state = driver.run(code)(using state)
    }
    thrown.foreach(throw _)
    if out.toLowerCase(java.util.Locale.ROOT).contains("error") then
      throw IllegalStateException(s"$what failed to compile:\n$out")

  /** End the session: stop a running evaluation (best effort) and refuse
    * further runs. The compiler and its class loader are not released here:
    * they are collected once nothing refers to the session any more, which is
    * why `/new` asks for a GC after dropping it. */
  def close(): Unit =
    closed = true
    interrupt()

  /** Interrupt a running evaluation (best effort): raise the REPL stop flag
    * for loops in agent code and interrupt the thread for blocking calls. The
    * request is recorded even when no evaluation thread is visible yet (an
    * interrupt landing just before `evalThread` is set must not be lost). */
  def interrupt(): Unit =
    stopRequested = true
    driver.setStopFlag(true)
    val t = evalThread
    if t != null then t.interrupt()

  def run(code: String): ExecutionResult =
    clock.reset() // per run, whichever way it ends (callers read `clock.paused` afterwards)
    // Safe mode resolves aliases before admitting an API, so ordinary Scala
    // import aliases are useful and safe there. Without safe mode the lexical
    // validator is the remaining barrier and aliases must not hide a forbidden API.
    val violations = CodeValidator.validate(code, strictImportAliases = !config.safeMode)
    if closed then ExecutionResult(false, "", Some("The sandbox session is closed; start a new one."))
    else if violations.nonEmpty then ExecutionResult(false, "", Some(CodeValidator.formatErrors(violations)))
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
      case None =>
        // No worker thread here: a fatal error in agent code must be reported like
        // the worker path's "no result" case, not crash the whole process. The stop
        // signal itself (ThreadDeath) still propagates.
        try adopt(res, evaluate(res))
        catch
          case t: ThreadDeath => throw t
          case _: Throwable => ExecutionResult(false, "", Some("Execution failed (no result; possible fatal error)"))
      case Some(limit) => dispatchWithTimeout(res, limit)

  /** `started` is released once the output stream is ours (or we gave up waiting for it). */
  private def evaluate(res: ParseResult, started: CountDownLatch = CountDownLatch(0)): Evaluated =
    var newState = state
    val (output, thrown) = withOutputCapture(onEnter = started.countDown()) {
      // An interrupt may have landed while we waited for the output lock (`run`
      // cleared `stopRequested` at the top, so it is true only if `interrupt()`
      // fired during THIS run). If so, do not execute the cancelled code — and do
      // not clear the stop flag it raised. `adopt` then honestly reports the abort.
      if !stopRequested then
        // A preamble object/given can be initialized lazily on the first agent
        // line, long after `init`. Re-select the owning host inside the global
        // evaluation lock so another session cannot supply its capabilities.
        Sandbox.installHost(host)
        driver.setStopFlag(false) // a previous evaluation may have been stopped
        driver.resetEvaluationThrew()
        newState = driver.runParseResult(res)(using state)
    }
    Evaluated(newState, output, thrown, driver.evaluationThrew)

  /** Take over the new state and turn the evaluation into the agent-visible result. */
  private def adopt(res: ParseResult, evaluated: Evaluated): ExecutionResult =
    val Evaluated(newState, output, thrown, failed) = evaluated
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
          ExecutionResult(!compileFailed && !failed, output)

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
      catch
        // Interrupted while waiting (e.g. a user interrupt landed here): the lock
        // may well be free — retry once, uninterruptibly, before crying "stuck".
        case _: InterruptedException => outputLock.tryLock()
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
