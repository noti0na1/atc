package atc.sandbox

import atc.perms.Mode

/** What one evaluation of agent code produced. */
case class ExecutionResult(success: Boolean, output: String, error: Option[String] = None):
  /** What the agent gets to see. */
  def render: String =
    val parts = List(
      Some(ExecutionResult.trimStackFrames(output)).filter(_.nonEmpty),
      error.map(e => s"ERROR: $e"),
    ).flatten
    if parts.nonEmpty then parts.mkString("\n")
    else if success then "(no output)"
    else "(failed, no output)"

object ExecutionResult:
  private val hostFrame = """^\s+at (atc\.|java\.|jdk\.|scala\.|dotty\.|sun\.).*$""".r
  private val elided = """^\s+\.\.\. \d+ (more|elided)$""".r
  /** Drop stack frames that point into the host or the runtime; frames in
    * agent code (`rs$line$N`) are kept because they locate the failing line. */
  def trimStackFrames(text: String): String =
    text.linesIterator.filterNot(l => hostFrame.matches(l) || elided.matches(l)).mkString("\n")

/** Wall-clock accounting for the execution timeout: time spent waiting for the
  * user (permission prompts, questions) or for a command the agent runs does not
  * count. Pauses nest: the clock runs again when the outermost one ends. */
final class ExecutionClock:
  private var pausedNanos: Long = 0L
  /** When the current pause began, or -1 while the clock is running. */
  private var pauseStart: Long = -1L
  private var depth: Int = 0

  def pause(): Unit = synchronized:
    depth += 1
    if pauseStart < 0 then pauseStart = System.nanoTime()
  def resume(): Unit = synchronized:
    if depth > 0 then depth -= 1
    if depth == 0 && pauseStart >= 0 then
      pausedNanos += System.nanoTime() - pauseStart
      pauseStart = -1L
  /** Nanoseconds spent paused since the last `reset()`, including an open pause. */
  def paused: Long = synchronized { pausedNanos + (if pauseStart >= 0 then System.nanoTime() - pauseStart else 0L) }
  def reset(): Unit = synchronized { pausedNanos = 0L; pauseStart = -1L; depth = 0 }

case class SandboxConfig(
  safeMode: Boolean = true,
  /** Which capabilities the preamble hands to the agent (see `ReplSession.preambleChunks`). */
  mode: Mode = Mode.Full,
  executionTimeoutMs: Option[Long] = Some(300000L),
  /** Longest rendering of a top-level definition's value the REPL echoes
    * (`val x: T = ...`); longer values are cut. Printed output is unaffected. */
  maxEchoChars: Int = 2000,
)
