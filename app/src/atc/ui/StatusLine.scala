package atc.ui

import org.jline.terminal.Terminal
import org.jline.utils.{AttributedString, Status}

import scala.jdk.CollectionConverters.*

/** The footer (JLine `Status`) and the window title. The footer shows what the turn is
  * doing, an unsent correction, or the keys a pop-up takes; between turns it shows the
  * model, mode and directory. The title is `atc · <directory>`, marked while a turn runs
  * (`●`) or a pop-up waits during one (`?`), so a tab that needs the user stands out. The
  * terminal's own title is saved first (xterm's title stack) and restored by `close`.
  *
  * Updates run with the screen's lock held and redraw only what changed. */
private[ui] final class StatusLine(
  screen: Screen,
  busy: () => Boolean,
  popupOpen: () => Boolean,
  queuedInputs: () => Int,
):
  import screen.{g, plain, terminal, width}

  private val footer = if plain then None else Option(Status.getStatus(terminal))
  private var footerSize = (0, 0)
  private var lastFooter = ""
  private var context = ""
  private var titleBase = "atc"
  private var lastTitle = ""
  @volatile private var closed = false
  @volatile private var turnStarted = 0L
  /** What the turn is doing ("reasoning", "running Scala"). */
  @volatile var operation = "ready"
  /** A correction typed during the turn and not sent yet. */
  @volatile var draft = ""
  /** The keys the open menu or answer field takes; shown instead of everything else. */
  @volatile private var hint = ""

  footer.foreach: status =>
    status.setBorder(false)
    // Reserve the footer before writing content; growing it later scrolls the first lines away.
    StatusLine.draw(terminal, status, "")
  if !plain then screen.frame(screen.writeStyle(s"${Ansi.Esc}[22;0t"))

  /** Whether there is a footer to show progress in (otherwise a spinner line shows it). */
  def shown: Boolean = footer.isDefined

  def setContext(label: String, title: String): Unit =
    context = label
    titleBase = title
    refresh()

  /** A turn starts: the elapsed time counts from now. */
  def beginTurn(): Unit =
    turnStarted = System.nanoTime()
    operation = "starting turn"
    refresh()

  def setOperation(label: String): Unit =
    if operation != label then
      operation = label
      refresh()

  def withOperation[A](label: String)(body: => A): A =
    val previous = operation
    def show(value: String): Unit = screen.synchronized:
      operation = value
      refresh()
    show(label)
    try body
    finally show(previous)

  def withHint[A](keys: String)(body: => A): A =
    val previous = hint
    def show(value: String): Unit = screen.synchronized:
      hint = value
      refresh()
    show(keys)
    try body
    finally show(previous)

  def refreshTitle(): Unit = if !plain && !closed then
    val marker = if busy() && popupOpen() then "? " else if busy() then s"${g.bullet} " else ""
    val title = Ansi.sanitize(marker + titleBase)
    if title != lastTitle then
      lastTitle = title
      screen.writeStyle(s"${Ansi.Esc}]0;$title\u0007")
      screen.flush()

  /** Redraw the title and the footer where they changed; the footer also after a resize. */
  def refresh(): Unit =
    refreshTitle()
    if !closed then
      footer.foreach: status =>
        val size = terminal.getSize
        val dimensions = (size.getColumns, size.getRows)
        val resized = dimensions != footerSize
        if resized then
          status.resize(size)
          footerSize = dimensions
        val text = screen.fit(Ansi.sanitize(label).replace('\n', ' ').replace('\t', ' '), 0)
        if resized || text != lastFooter then
          screen.flush()
          StatusLine.draw(terminal, status, text)
          lastFooter = text

  private def label: String =
    val elapsed = if busy() then s" ${Format.duration((System.nanoTime() - turnStarted) / 1e9)}" else ""
    val queued = queuedInputs()
    val waiting = if queued > 0 then s" ${g.dot} ${Format.plural(queued, "message")} queued" else ""
    if hint.nonEmpty then hint
    else if draft.nonEmpty then s"Update: ${draft.takeRight((width - 30).max(10))} ${g.dot} Enter to send$waiting"
    else if busy() then
      val frame = g.spinner(((System.nanoTime() - turnStarted) / 100_000_000L % g.spinner.length).toInt)
      s"$frame ${operation.take((width / 2).max(20))}$elapsed$waiting ${g.dot} $context"
    else context

  /** Restore the terminal's title and take the footer down; later updates draw nothing. */
  def close(): Unit =
    closed = true
    if !plain then screen.synchronized(screen.writeStyle(s"${Ansi.Esc}[23;0t"))
    screen.synchronized(screen.flush())
    footer.foreach(_.close())

private[atc] object StatusLine:
  /** Draw the one-line footer and flush it. JLine's `Status.update` flushes the text itself
    * but leaves the closing synchronized-update sequence (`ESC[?2026l`) in the buffered
    * writer: a terminal honouring mode 2026 (xterm.js/VS Code, iTerm2, kitty, Ghostty, WezTerm)
    * then keeps rendering frozen until something else flushes, so a footer repainted from the
    * input-poll clock stalled the whole window for up to a poll interval per repaint. */
  private[atc] def draw(terminal: Terminal, status: Status, text: String): Unit =
    status.update(List(AttributedString(text)).asJava)
    terminal.writer().flush()
