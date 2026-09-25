package atc.ui

import Ansi.{Dim, Reset}

/** The model's reasoning as it streams. Compact view: a live window over the
  * last lines that collapses to a one-line summary when the reasoning ends.
  * Plain or expanded view: written out in full, the block staying open so
  * later deltas append. Called with the screen's lock held. */
private[ui] final class ThinkingView(screen: Screen, expanded: () => Boolean, beginBlock: () => Unit):
  import screen.{g, styled, Indent}

  private val buf = TailBuffer(TailBuffer.MaxChars)
  private var region: Option[screen.LiveRegion] = None
  /** Written out in full (plain/expanded), so the block is still open. */
  private var streaming = false
  private var started = 0L

  def active: Boolean = streaming || region.isDefined

  def resize(): Unit = region.foreach(_.redraw(window(), force = true))

  def delta(text: String): Unit = if text.nonEmpty then
    screen.stopSpinner()
    if started == 0L then started = System.nanoTime()
    buf.append(text)
    if streaming then screen.writeGuttered(text, Indent) else render()

  /** (Re)draw everything gathered so far in the current view. */
  def render(): Unit =
    if screen.plain || expanded() then
      clearRegion()
      beginBlock()
      screen.write(styled(g.bullet, Dim) + " " + styled("thinking", Dim) + "\n")
      if screen.colors > 0 then screen.writeStyle(Ansi.sgr(Dim))
      streaming = true
      screen.writeGuttered(buf.text.dropWhile(_ == '\n'), Indent)
    else
      val live = region.getOrElse { beginBlock(); val r = screen.LiveRegion(); region = Some(r); r }
      live.redraw(window())

  /** Take the rendering off the screen but keep the reasoning (Ctrl-O). */
  def detach(): Unit =
    clearRegion()
    if streaming then
      if screen.colors > 0 then screen.writeStyle(Reset)
      screen.ensureNewline()
      streaming = false

  /** Thinking ended (answer text or a tool call follows): a window collapses
    * to a summary line, reasoning shown in full ends. */
  def end(): Unit = if active then
    val collapses = region.isDefined
    detach()
    if collapses then screen.write(summary())
    buf.clear()
    started = 0L

  private def clearRegion(): Unit = { region.foreach(_.clear()); region = None }

  private def summary(): String =
    val secs = (System.nanoTime() - started) / 1e9
    styled(s"${g.bullet} reasoning ${g.dot} ${Format.duration(secs)}", Dim) + "\n"

  /** Header + the last few lines of the reasoning so far. */
  private def window(): List[String] =
    styled(s"${g.bullet} thinking${g.ellipsis} (Ctrl-O to expand)", Dim) ::
      buf.tail(ThinkingView.Lines).map(l => Indent + styled(screen.fit(l, Indent.length), Dim))

private[atc] object ThinkingView:
  /** Lines of reasoning shown live in the thinking window. */
  val Lines = 5
