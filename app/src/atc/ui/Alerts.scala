package atc.ui

import atc.agent.TurnOutcome

import org.jline.terminal.Terminal

import java.util.concurrent.{Executors, ScheduledFuture, TimeUnit}

/** Notifications for moments when atc waits for the user, and the terminal
  * focus they depend on. An alert waits [[Alerts.NotifyAfterMillis]] for input
  * and is sent at once when the terminal is not focused. `emit` writes control
  * text to the terminal (for terminal notifications and the bell).
  *
  * Focus reports are on only while a raw-mode reader that consumes them runs
  * (the prompt, the turn's key reader): in line mode the terminal driver would
  * echo them as `^[[I` text. Terminals without focus reporting count as focused. */
private[ui] final class Alerts(terminal: Terminal, plain: Boolean, emit: String => Unit):
  @volatile var notifier: Notifier = Notifier.Off
  /** The notification title, naming the working directory once it is known. */
  @volatile var title = "atc"

  @volatile private var focused = true
  /** The alert waiting for input, with its text; guarded by `this`. */
  private var pending: Option[(ScheduledFuture[?], String)] = None
  private lazy val timer = Executors.newSingleThreadScheduledExecutor { r =>
    val t = Thread(r, "atc-alert")
    t.setDaemon(true)
    t
  }.nn

  /** Tell the user that atc waits for them: at once when the terminal is not
    * focused, otherwise after [[Alerts.NotifyAfterMillis]] unless they type
    * first or the focus leaves, which sends it then. */
  def alert(body: String): Unit = if !plain && notifier != Notifier.Off then
    if !focused then send(body)
    else
      val task: Runnable = () => take().foreach(send)
      synchronized:
        take()
        pending = Some((timer.schedule(task, Alerts.NotifyAfterMillis, TimeUnit.MILLISECONDS).nn, body))

  /** The user typed or answered, so they have seen what waits. */
  def touch(): Unit = take()

  def focusChanged(in: Boolean): Unit =
    focused = in
    if !in then take().foreach(send)

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

  /** Run `body` with focus reports off (a jline-prompt menu, which does not parse them). */
  def withoutFocusReports[T](body: => T): T =
    val was = reporting
    reportFocus(false)
    try body
    finally
      if was then
        focused = true // the user just answered the menu
        reportFocus(true)

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

  def turnEnded(stats: Tui.TurnStats): Unit = alert(synchronized(Alerts.turnText(stats, prose.toString, error)))

  def close(): Unit =
    take()
    reportFocus(false)

private[atc] object Alerts:
  /** How long a finished turn or a waiting question waits for input before it notifies the user. */
  val NotifyAfterMillis: Long = 10_000L
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
