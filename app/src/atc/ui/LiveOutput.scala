package atc.ui

import Ansi.Dim

/** Program output inside a tool block. It goes straight to the screen while
  * the section fits in [[LiveOutput.FoldAfterRows]] terminal *rows*; everything
  * after that into a live tail window ("⋯ N more lines" + the last
  * [[LiveOutput.FoldTail]] lines), unless `direct` says to write everything out
  * (expanded view, no terminal to redraw, or no tool block open).
  *
  * The budget counts rows rather than lines because a long line wraps: a few
  * 400-character lines, or output printed without newlines at all, would
  * otherwise fill the screen without ever reaching a line count. Called with
  * the screen's lock held. */
private[ui] final class LiveOutput(screen: Screen, direct: () => Boolean):
  import screen.{g, styled}

  /** Rows the direct section has used, and how far into its last row it got. */
  private var usedRows = 0
  private var column = 0
  private var folding = false
  private val held = TailBuffer(TailBuffer.MaxChars)
  private var region: Option[screen.LiveRegion] = None

  /** A new tool block begins: nothing written, nothing folded yet. */
  def start(): Unit = { usedRows = 0; column = 0; folding = false; held.clear() }

  def emit(body: String): Unit =
    if direct() then screen.writeGuttered(body, screen.gutter(Dim))
    else if folding then fold(body)
    else
      // One logical line at a time, while the rows it needs still fit; the
      // first one that does not fit starts the folded tail window.
      var rest = body
      while rest.nonEmpty && !folding do
        val nl = rest.indexOf('\n')
        val (segment, remainder) = if nl < 0 then (rest, "") else rest.splitAt(nl + 1)
        val placed = Screen.place(column, segment, screen.width, screen.GutterWidth)
        if usedRows + placed.rows > LiveOutput.FoldAfterRows then folding = true
        else
          screen.writeGuttered(segment, screen.gutter(Dim))
          usedRows += placed.rows
          column = placed.column
          rest = remainder
      if rest.nonEmpty then fold(rest)

  /** The output section is over: whatever the tail window shows stays on screen. */
  def end(): Unit =
    region.foreach(_.redraw(window(interactive = false), force = true))
    region.foreach(_.freeze())
    region = None
    folding = false
    held.clear()

  /** Ctrl-O: take the window down and hand back the text it was hiding. */
  def detach(): String =
    val hidden = held.text
    region.foreach(_.clear())
    region = None
    held.clear()
    hidden

  def foldFromHere(): Unit = folding = true
  def showEverything(): Unit = folding = false
  def resize(): Unit = region.foreach(_.redraw(window(), force = true))

  private def fold(text: String): Unit =
    held.append(text)
    val live = region.getOrElse { val r = screen.LiveRegion(); region = Some(r); r }
    live.redraw(window())

  private def window(interactive: Boolean = true): List[String] =
    val lines = held.tail(LiveOutput.FoldTail)
    val hidden = held.lineCount - lines.length
    val header =
      if hidden > 0 then
        val hint = if interactive then " (Ctrl-O to expand)" else ""
        List(screen.gutter(Dim) + styled(s"${g.ellipsis} ${Format.plural(hidden, "more line")}$hint", Dim))
      else Nil
    header ++ lines.map(l => screen.gutter(Dim) + screen.fit(l, screen.GutterWidth))

private[atc] object LiveOutput:
  /** Terminal rows of live output shown before the rest is folded, and the
    * number of lines in the live tail that replaces it. */
  val FoldAfterRows = 10
  val FoldTail = 10
