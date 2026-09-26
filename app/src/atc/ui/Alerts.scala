package atc.ui

import atc.agent.TurnOutcome

import org.jline.terminal.Terminal

import java.util.concurrent.{Executors, ScheduledFuture, TimeUnit}

/** Notifications for moments when atc waits for the user, and the terminal
  * focus they depend on. An alert is sent only after a wait in which the user
  * neither typed nor had the terminal focused: [[Alerts.QuestionDelayMillis]] for
  * a question or permission request, [[Alerts.TurnDelayMillis]] for a finished
  * turn. `emit` writes control text to the terminal (for terminal notifications
  * and the bell).
  *
  * Focus reports are on only while a raw-mode reader that consumes them runs
  * (the prompt, the turn's key reader): in line mode the terminal driver would
  * echo them as `^[[I` text. Focus counts only once the terminal has reported
  * it: a terminal may claim focus reporting (any `xterm*` type) and send none. */
private[ui] final class Alerts(
  terminal: Terminal,
  plain: Boolean,
  emit: String => Unit,
  questionDelayMillis: Long = Alerts.QuestionDelayMillis,
  turnDelayMillis: Long = Alerts.TurnDelayMillis,
):
  @volatile var notifier: Notifier = Notifier.Off
  /** The notification title, naming the working directory once it is known. */
  @volatile var title = "atc"

  @volatile private var focused = true
  /** Whether the terminal has sent a focus report, so `focused` means something. */
  @volatile private var focusKnown = false
  /** The alert waiting for input, with its text; guarded by `this`. */
  private var pending: Option[(ScheduledFuture[?], String)] = None
  private lazy val timer = Executors.newSingleThreadScheduledExecutor { r =>
    val t = Thread(r, "atc-alert")
    t.setDaemon(true)
    t
  }.nn

  /** Tell the user that a question or permission request waits for them. */
  def alert(body: String): Unit = schedule(body, questionDelayMillis)

  /** Tell the user after `delayMillis` unless they type or focus the terminal
    * first. Nothing is sent while the terminal is known to be focused: they are
    * looking at it. Until the terminal reports focus, only typing counts. */
  private def schedule(body: String, delayMillis: Long): Unit = if !plain && notifier != Notifier.Off then
    synchronized:
      take()
      if !(focusKnown && focused) then
        val task: Runnable = () => take().foreach(send)
        pending = Some((timer.schedule(task, delayMillis, TimeUnit.MILLISECONDS).nn, body))

  /** The user typed or answered, so they have seen what waits. */
  def touch(): Unit = take()

  /** Coming back to the terminal counts as seeing what waits; leaving it sends nothing early. */
  def focusChanged(in: Boolean): Unit =
    focused = in
    focusKnown = true
    if in then take()

  /** Cancel the pending alert and return its text. */
  private def take(): Option[String] = synchronized:
    val body = pending.map((future, body) => { future.cancel(false); body })
    pending = None
    body

  private def send(body: String): Unit = notifier.send(title, body, emit)

  // ── focus reporting ───────────────────────────────────────────────

  /** Whether the terminal can report focus changes (`ESC[I` / `ESC[O`). */
  val focusSupported: Boolean = !plain && terminal.hasFocusSupport
  @volatile private var reporting = false

  def reportFocus(on: Boolean): Unit = if focusSupported && on != reporting then
    reporting = on
    terminal.trackFocus(on)

  /** Run `body` with focus reports on (a menu, which reads them), as they were after it. */
  def withFocusReports[T](body: => T): T =
    val was = reporting
    reportFocus(true)
    try body
    finally reportFocus(was)

  // ── the turn's text ───────────────────────────────────────────────

  /** The current turn's last prose block and last error; guarded by `this`. */
  private val prose = StringBuilder()
  private var error = ""

  def beginTurn(): Unit = synchronized:
    prose.clear()
    error = ""

  /** Streamed prose; `newBlock` starts the block the alert quotes. */
  def proseDelta(text: String, newBlock: Boolean): Unit = synchronized:
    if newBlock then prose.clear()
    if prose.length < Alerts.ProseChars then prose.append(text)

  def turnError(message: String): Unit = synchronized { error = message }

  def turnEnded(stats: Tui.TurnStats): Unit =
    schedule(synchronized(Alerts.turnText(stats, prose.toString, error)), turnDelayMillis)

  def close(): Unit =
    take()
    reportFocus(false)

private[atc] object Alerts:
  /** How long a question or permission request waits for input or focus before it notifies the user. */
  val QuestionDelayMillis: Long = 10_000L
  /** The same for a finished turn, whose answer may take a while to read. */
  val TurnDelayMillis: Long = 30_000L
  /** How much of the turn's last prose block is kept for its alert. */
  val ProseChars = 1000

  /** The alert text for an ended turn: the start of the agent's last reply,
    * after the outcome unless the turn finished normally, or the error that
    * ended it. The outcome and duration alone when there is neither. */
  private[atc] def turnText(stats: Tui.TurnStats, prose: String, error: String): String =
    val outcome = stats.outcome.label.capitalize
    val reply = Notifier.plainText(prose)
    stats.outcome match
      case TurnOutcome.Finished if reply.nonEmpty => reply
      case TurnOutcome.Failed | TurnOutcome.Blocked if error.nonEmpty => s"$outcome: $error"
      case TurnOutcome.Interrupted => s"$outcome after ${Format.duration(stats.seconds)}"
      case _ if reply.nonEmpty => s"$outcome: $reply"
      case _ => s"$outcome in ${Format.duration(stats.seconds)}"
