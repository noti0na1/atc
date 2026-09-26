package atc.ui

import org.jline.terminal.{Size, Terminal}
import org.jline.utils.{AttributedString, Status}

import scala.jdk.CollectionConverters.*

/** The footer (JLine `Status`) and the window title. The footer's left side holds the
  * mode, then the model and its settings; its right side holds the folder's name, with what
  * a turn is doing beside it while one runs, so the fields keep their place. An unsent correction or
  * the keys a pop-up takes replace the whole line. The title is `atc · <directory>`, marked while a turn runs
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
  /** The sandbox mode, the footer's first field, drawn in its own colour. */
  private var mode = ""
  /** The model and its settings, after the mode. */
  private var context = ""
  /** What the footer's right end shows: the working directory's name. */
  private var folder = ""
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

  def setContext(modeLabel: String, label: String, place: String, title: String): Unit =
    mode = modeLabel
    context = label
    folder = place
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
    val title = Ansi.sanitize(marker + titleBase).replace('\n', ' ').replace('\t', ' ')
    if title != lastTitle then
      lastTitle = title
      screen.writeStyle(s"${Ansi.Esc}]0;$title\u0007")
      screen.flush()

  /** Redraw the title and the footer where they changed; the footer also after a resize,
    * at the size the screen measured when the terminal said it changed. */
  def refresh(): Unit =
    refreshTitle()
    if !closed then
      footer.foreach: status =>
        val dimensions = (width, screen.height)
        val resized = dimensions != footerSize
        if resized then
          status.resize(Size.of(width, screen.height))
          footerSize = dimensions
        val text = line
        if resized || text != lastFooter then
          screen.flush()
          StatusLine.draw(terminal, status, text)
          lastFooter = text

  /** Draw the footer again from nothing, after the screen was cleared under it. */
  def redraw(): Unit =
    footer.foreach(_.reset())
    footerSize = (0, 0)
    lastFooter = ""
    refresh()

  /** The footer's text, cut to one row. */
  private[ui] def line: String =
    def clean(text: String) = Ansi.sanitize(text).replace('\n', ' ').replace('\t', ' ')
    val queued = queuedInputs()
    val waiting = if queued > 0 then s" ${g.dot} ${Format.plural(queued, "message")} queued" else ""
    if hint.nonEmpty then screen.fit(clean(hint), 0)
    else if draft.nonEmpty then
      screen.fit(clean(s"Update: ${draft.takeRight((width - 30).max(10))} ${g.dot} Enter to send$waiting"), 0)
    else
      val activity =
        if !busy() then ""
        else
          val frame = g.spinner(((System.nanoTime() - turnStarted) / 100_000_000L % g.spinner.length).toInt)
          val elapsed = Format.duration((System.nanoTime() - turnStarted) / 1e9)
          s"$frame ${operation.take((width / 2).max(20))} $elapsed$waiting   "
      val fields = screen.styled(clean(mode), Ansi.Cyan, Ansi.Bold) + clean(s" ${g.dot} $context")
      spread(fields, clean(activity) + clean(folder))

  /** `left` at the start of the row and `right` at its end; `left` is cut first when both do not fit. */
  private def spread(left: String, right: String): String =
    val room = width - 1
    val rightCells = Screen.displayWidth(right)
    val shown = if Screen.displayWidth(left) + 2 + rightCells <= room then left else screen.fit(left, rightCells + 2)
    if shown.isEmpty then screen.fit(right, 0)
    else shown + " " * (room - Screen.displayWidth(shown) - rightCells).max(2) + right

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
    status.update(List(AttributedString.fromAnsi(text)).asJava)
    terminal.writer().flush()
