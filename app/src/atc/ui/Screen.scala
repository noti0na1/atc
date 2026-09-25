package atc.ui

import org.jline.terminal.Terminal
import org.jline.utils.{InfoCmp, WCWidth}

import Ansi.{ClearLine, Dim, Reset}

/** The terminal output shared by the TUI's views: styles and glyphs, the
  * terminal width, and writes that track the last two characters so blocks
  * know whether they start a line or follow a blank one.
  *
  * The screen is the TUI's lock. Every view writes inside `frame` (or holds
  * the screen's monitor), and a frame flushes once when the outermost one ends. */
private[ui] final class Screen(val terminal: Terminal, val plain: Boolean, val g: Glyphs):
  private val out = terminal.writer()

  /** Colours the terminal supports; 0 (no styling at all) when there is no real terminal. */
  val colors: Int =
    if plain then 0
    else Option(terminal.getNumericCapability(InfoCmp.Capability.max_colors)).map(_.intValue).getOrElse(0)
  def styled(s: String, codes: Int*): String = if colors <= 0 then s else Ansi.styled(s, codes*)

  val Indent = "  "
  /** Terminal columns, measured once per resize (`resized`): `getSize` is a system
    * call, and the live views ask for the width for every line of every token. */
  @volatile private var columns = measureColumns()
  private def measureColumns(): Int = { val w = terminal.getSize.getColumns; if w <= 0 then 80 else w }
  def width: Int = columns
  def resized(): Unit = columns = measureColumns()

  // ── writing ───────────────────────────────────────────────────────

  /** The last two characters written: tells whether we are at a line start / after a blank line. */
  @volatile var tail = "\n\n"
  private var outputDirty = false
  private var frameDepth = 0

  /** Flush complete updates rather than every gutter, style and text fragment. */
  def frame[A](body: => A): A = synchronized:
    frameDepth += 1
    try body
    finally
      frameDepth -= 1
      if frameDepth == 0 then flush()

  def flush(): Unit = if outputDirty then
    out.flush()
    outputDirty = false

  def write(s: String): Unit =
    if s.nonEmpty then
      out.print(s)
      outputDirty = true
      tail = if s.length >= 2 then s.takeRight(2) else (tail + s).takeRight(2)

  /** A style or control sequence: printed like `write`, but it takes no columns and leaves `tail` alone. */
  def writeStyle(s: String): Unit =
    out.print(s)
    outputDirty = true

  def atLineStart: Boolean = tail.endsWith("\n")
  def ensureNewline(): Unit = if !atLineStart then write("\n")
  /** Make sure the previous content is followed by an empty line. */
  def blankLine(): Unit = { ensureNewline(); if tail != "\n\n" then write("\n") }

  /** Write text that may arrive in chunks and span lines, putting `gutter` at
    * every line start. Empty lines get the gutter too unless it is blank
    * (indentation), so boxes stay closed and prose has no trailing spaces. */
  def writeGuttered(text: String, gutter: String): Unit =
    if !text.contains('\n') then
      if atLineStart && text.nonEmpty then write(gutter + text) else write(text)
    else
      val parts = text.split("\n", -1)
      val rendered = StringBuilder()
      var lineStart = atLineStart
      var i = 0
      while i < parts.length do
        val seg = parts(i)
        val last = i == parts.length - 1
        if lineStart && (seg.nonEmpty || (!last && !gutter.isBlank)) then rendered.append(gutter)
        rendered.append(seg)
        if !last then rendered.append('\n')
        lineStart = !last
        i += 1
      write(rendered.toString)

  def gutter(code: Int): String = Indent + styled(g.bar, code) + " "
  /** Visible width of `gutter`: the indent plus the bar and its space. */
  val GutterWidth: Int = Indent.length + 2

  /** The ellipsis's actual width: "…" is one cell but ASCII "..." is three, so budgeting a
    * single column would let a truncated ASCII line overflow and wrap. */
  private val EllipsisWidth = math.max(1, Screen.displayWidth(g.ellipsis))

  /** Cut a plain line so it fits on one terminal row (region lines must not wrap). */
  def fit(line: String, used: Int): String =
    val room = width - used - 1
    if room <= 0 then ""
    else if Screen.displayWidth(line) <= room then line
    else if room <= EllipsisWidth then g.ellipsis.take(room)
    else
      // Whole code points until the width budget (minus the ellipsis) is spent.
      val budget = room - EllipsisWidth
      val sb = StringBuilder()
      var w = 0
      var i = 0
      var styledText = false
      while i < line.length && w < budget do
        val styleEnd = Screen.sgrEnd(line, i)
        if styleEnd > 0 then { sb.append(line, i, styleEnd); styledText = true; i = styleEnd } // takes no cells
        else
          val cp = line.codePointAt(i)
          val cw = Screen.cellWidth(cp, w)
          if w + cw > budget then i = line.length
          else { sb.underlying.appendCodePoint(cp); w += cw; i += Character.charCount(cp) }
      // A cut may have dropped the line's own reset: never let its style leak into the next row.
      sb.toString + (if styledText then Reset else "") + g.ellipsis

  /** Update only changed rows in a live preview. Clearing the rest of the screen would
    * also erase the footer, forcing unrelated output to be repainted on every token. */
  final class LiveRegion:
    private var tailBefore = tail
    /** The rows this region owns, and what is on them. */
    private var previousLines = List.empty[String]
    def redraw(lines: List[String], force: Boolean = false): Unit =
      if force || lines != previousLines then
        if previousLines.isEmpty then { ensureNewline(); tailBefore = tail }
        write(Screen.replaceRows(previousLines, lines, force))
        tail = lines.lastOption match
          case Some("") => "\n\n"
          case Some(last) => last.takeRight(1) + "\n"
          case None => tailBefore
        previousLines = lines
    def clear(): Unit = redraw(Nil)
    /** Keep what is drawn as ordinary output. */
    def freeze(): Unit = previousLines = Nil

  // ── spinner ───────────────────────────────────────────────────────

  private var spinner: Option[Spinner] = None

  /** An animated "the agent is working" line (`prefix ⠋ text… 12 s`) that
    * lives on the current (empty) line until something else is written. */
  private final class Spinner(prefix: String, text: String) extends Thread("atc-spinner"):
    @volatile var running = true
    private val started = System.nanoTime()
    override def run(): Unit =
      var i = 0
      while running do
        val secs = (System.nanoTime() - started) / 1_000_000_000L
        val elapsed = if secs >= 2 then s" $secs s" else ""
        Screen.this.synchronized:
          if running then
            out.print(ClearLine + prefix +
              styled(s"${g.spinner(i % g.spinner.length)} $text${g.ellipsis}$elapsed", Dim))
            out.flush()
        i += 1
        try Thread.sleep(80)
        catch case _: InterruptedException => running = false
    def stopAndClear(): Unit =
      running = false
      interrupt()
      out.print(ClearLine) // back to the line start we began on; `tail` still ends with "\n"
      out.flush()

  def spin(prefix: String, text: String): Unit =
    val s = Spinner(prefix, text)
    s.setDaemon(true)
    spinner = Some(s)
    s.start()

  def stopSpinner(): Unit = synchronized:
    spinner.foreach(_.stopAndClear())
    spinner = None

object Screen:
  /** Replace owned rows with the cursor initially just below them. Leave everything below
    * that region intact and finish immediately below the replacement. */
  private[atc] def replaceRows(before: List[String], after: List[String], force: Boolean = false): String =
    val prefix = if force then 0 else before.zip(after).takeWhile((a, b) => a == b).size
    val update = StringBuilder()
    if before.size > prefix then update.append(s"\r${Ansi.Esc}[${before.size - prefix}A")
    after.drop(prefix).foreach(line => update.append(Ansi.ClearLine).append(line).append('\n'))
    val removed = (before.size - after.size).max(0)
    for row <- 0 until removed do
      if row > 0 then update.append(s"${Ansi.Esc}[1B")
      update.append(Ansi.ClearLine)
    if removed > 1 then update.append(s"${Ansi.Esc}[${removed - 1}A")
    update.toString

  /** Where a chunk of text lands on screen: the number of terminal rows it
    * adds to a section whose last row already holds `column` characters, and
    * the column it ends at (0 when it ended the line). A fresh row starts with
    * a gutter, hence `indent`; a line always takes at least one row. */
  def place(column: Int, text: String, width: Int, indent: Int): (rows: Int, column: Int) =
    val body = text.stripSuffix("\n")
    val start = if column == 0 then indent else column
    val end = start + displayWidth(body)
    val rows = math.max(1, (end + width - 1) / width)
    (
      rows = if column == 0 then rows else rows - 1, // the row we started on was already counted
      column = if text.endsWith("\n") then 0 else { val c = end % width; if c == 0 then width else c },
    )

  /** Width in terminal cells of one code point placed at column `col`: a tab
    * advances to the next multiple of 8, wide (CJK) code points count 2, other
    * controls 0. `String.length` counts UTF-16 units and undercounts all of these,
    * which would let "one row" lines wrap and corrupt the live regions. */
  private[ui] def cellWidth(cp: Int, col: Int): Int =
    if cp == '\t' then 8 - (col % 8) else math.max(0, WCWidth.wcwidth(cp))

  /** Display width in terminal cells of `s` starting at column 0; SGR sequences take none. */
  def displayWidth(s: String): Int =
    var w = 0
    var i = 0
    while i < s.length do
      val styleEnd = sgrEnd(s, i)
      if styleEnd > 0 then i = styleEnd
      else
        val cp = s.codePointAt(i)
        w += cellWidth(cp, w)
        i += Character.charCount(cp)
    w

  /** The end of the SGR sequence (`ESC [ … m`) starting at `i`, or -1 when there is none. */
  private[atc] def sgrEnd(s: String, i: Int): Int =
    if i + 1 < s.length && s.charAt(i) == '\u001b' && s.charAt(i + 1) == '[' then
      var k = i + 2
      while k < s.length && (s.charAt(k).isDigit || s.charAt(k) == ';') do k += 1
      if k < s.length && s.charAt(k) == 'm' then k + 1 else -1
    else -1
