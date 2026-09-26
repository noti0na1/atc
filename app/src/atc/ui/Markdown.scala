package atc.ui

import java.util.Locale
import scala.collection.mutable

/** Streaming Markdown → ANSI, tuned for what models write in a terminal:
  * headings, `- `/`* `/`1. ` lists, `> ` quotes, `---` rules, fenced code
  * blocks (coloured only when the fence says `scala`; other languages and
  * untagged blocks are shown verbatim), pipe tables, and inline `**bold**` /
  * `` `code` ``. Text may arrive in arbitrary chunks: `push` returns what can
  * be rendered now and holds back only what is still ambiguous (the first
  * characters of a line, a trailing `*`, an unfinished fenced line, and a
  * table, whose column widths need every row); `finish` flushes the rest.
  *
  * Styles are scoped to one line: an unclosed `**` never bleeds into the
  * next line. Everything is emitted as raw SGR sequences.
  *
  * Paragraph, heading, list and quote text is wrapped at word boundaries to `columns`,
  * a list item or quote continuing under its text: left to the terminal, a long line
  * would continue at column 0, outside the block's gutter. The word being written is
  * held back until it ends, since only then is it known whether it fits.
  *
  * @param glyphs    what to draw bullets, quote bars, rules, code gutters and tables with
  * @param highlight colours the last line of a fenced Scala block given its context lines,
  *                  and says whether a comment or string is still open after it
  */
class MarkdownStream(
  glyphs: MarkdownStream.Glyphs,
  highlight: String => (String, Boolean),
  columns: () => Int = () => Int.MaxValue,
):
  import MarkdownStream.*
  import Ansi.{sgr, Bold, Dim, Reset}

  private val pending = StringBuilder()
  private var decided = false
  private var lineStyle: List[Int] = Nil // heading → bold, quote → dim
  private var bold = false
  private var code = false
  private var inFence = false
  /** Inside a dropped ```markdown wrapper: its bare closing fence must not open a code block. */
  private var droppedFence = false
  private var fenceScala = false
  /** The context the next fenced line is highlighted with (see [[fenceLine]]). */
  private val fenceLines = mutable.ArrayBuffer[String]()
  private var restStart = 0
  /** The current line produces no output at all (a dropped fence marker). */
  private var dropLine = false
  /** Terminal cells written on the current line, its prefix included. */
  private var col = 0
  /** What a wrapped row of the current line starts with: the width of a list marker, or the quote bar. */
  private var hang = ""
  /** The rendered word being written (with its style sequences), and its width in cells. */
  private val word = StringBuilder()
  private var wordCells = 0
  /** Spaces before the held word: written with it, or dropped when it starts a new row. */
  private var spaces = 0
  /** A `|` line held back until the next line tells whether a table starts (a delimiter row). */
  private var tableHead: Option[String] = None
  /** The rows (header, delimiter, body) of the table being collected. */
  private val table = mutable.ListBuffer[String]()

  /** Render what `chunk` completes; may return "" (waiting for more). */
  def push(chunk: String): String =
    pending.append(chunk)
    val out = StringBuilder()
    var progress = true
    while progress do
      progress = false
      val nl = pending.indexOf("\n")
      if inFence then
        if nl >= 0 then
          val line = takeLine(nl)
          if FenceRe.matches(line) then inFence = false
          else out.append(fenceLine(line))
          progress = true
      else if !decided then
        if nl >= 0 then
          out.append(completeLine(takeLine(nl)))
          progress = true
        else if !couldStillBeMarker(pending.toString) then
          val line = pending.toString; pending.clear()
          out.append(leaveTable()) // not a `|` line (those wait): a table in progress ends here
          out.append(startLine(line))
          if !inFence && !dropLine then
            decided = true
            out.append(styledText(withhold(line.drop(restStart))))
      else if nl >= 0 then
        val line = takeLine(nl)
        out.append(styledText(line)).append(endLine())
        progress = true
      else
        val text = pending.toString; pending.clear()
        out.append(styledText(withhold(text)))
    out.toString

  /** Flush whatever is still held (end of the message). */
  def finish(): String =
    val out = StringBuilder()
    if pending.nonEmpty then
      val text = pending.toString; pending.clear()
      if inFence then out.append(fenceLine(text))
      else if !decided then out.append(completeLine(text))
      else out.append(styledText(text)).append(endLine())
    else if decided then out.append(endLine())
    out.append(leaveTable())
    decided = false
    inFence = false
    droppedFence = false
    out.toString

  // ── lines ─────────────────────────────────────────────────────────

  private def takeLine(nl: Int): String =
    val line = pending.substring(0, nl)
    pending.delete(0, nl + 1)
    decided = false
    line

  /** A whole line (outside a fence) whose start has not been rendered yet:
    * table bookkeeping first, then ordinary line rendering. */
  private def completeLine(line: String): String =
    if table.nonEmpty then
      if isTableRow(line) then { table += line; "" }
      else renderTable() + renderLine(line)
    else
      tableHead match
        case Some(head) if isDelimiterRow(line) => tableHead = None; table += head; table += line; ""
        case Some(head) => tableHead = None; renderLine(head) + completeLine(line)
        case None if isTableRow(line) => tableHead = Some(line); ""
        case None => renderLine(line)

  /** End a table in progress (or release a held `|` line that never became one). */
  private def leaveTable(): String =
    val held = tableHead.map(renderLine).getOrElse("")
    tableHead = None
    held + (if table.nonEmpty then renderTable() else "")

  private def renderLine(line: String): String =
    val prefix = startLine(line)
    if inFence || dropLine then prefix else prefix + styledText(line.drop(restStart)) + endLine()

  /** Decide the kind of a line and emit its prefix; sets `restStart` to where the text begins. */
  private def startLine(line: String): String =
    restStart = 0
    lineStyle = Nil
    dropLine = false
    val prefix = linePrefix(line)
    col = TextLayout.width(prefix)
    hang = if lineStyle == List(Dim) && prefix.nonEmpty then sgr(Dim) + glyphs.quote + " " else " " * col
    prefix

  private def linePrefix(line: String): String =
    line match
      case FenceRe(lang0) =>
        val lang = lang0.nn.toLowerCase(Locale.ROOT)
        restStart = line.length
        // A whole answer wrapped in ```markdown: render its content, drop the fence.
        if lang == "markdown" || lang == "md" then { droppedFence = true; dropLine = true; "" }
        else if droppedFence && lang.isEmpty then { droppedFence = false; dropLine = true; "" }
        else
          inFence = true
          fenceScala = lang == "scala" || lang == "sc" // anything else: no colouring, verbatim
          fenceLines.clear()
          ""
      case RuleRe() =>
        restStart = line.length
        sgr(Dim) + glyphs.rule * 40 + Reset
      case HeadingRe(hashes0, _) =>
        val hashes = hashes0.nn
        restStart = line.indexOf(hashes) + hashes.length
        while restStart < line.length && line(restStart) == ' ' do restStart += 1
        lineStyle = List(Bold)
        sgr(Bold)
      case BulletRe(indent0, _) =>
        val indent = indent0.nn
        restStart = indent.length + 2
        indent + glyphs.bullet + " "
      case OrderedRe(indent0, num0) =>
        val (indent, num) = (indent0.nn, num0.nn)
        restStart = indent.length + num.length + 1
        indent + num + " "
      case QuoteRe(_) =>
        restStart = line.indexOf('>') + 1
        if restStart < line.length && line(restStart) == ' ' then restStart += 1
        lineStyle = List(Dim)
        sgr(Dim) + glyphs.quote + " "
      case _ => ""

  /** Close inline styles at the end of a line so mistakes stay local. */
  private def endLine(): String =
    val rest = endWord()
    val close = if bold || code || lineStyle.nonEmpty then Reset else ""
    bold = false; code = false; lineStyle = Nil
    col = 0
    rest + close + "\n"

  /** Inline text, rendered and wrapped (see the class comment). */
  private def styledText(text: String): String = flow(spans(text))

  /** Lay rendered text out in rows of `columns` cells, breaking before a word that does not
    * fit; style sequences take no cells and travel with their word. */
  private def flow(rendered: String): String =
    if columns() == Int.MaxValue then return rendered // rows never end: nothing to hold back
    val out = StringBuilder()
    var i = 0
    while i < rendered.length do
      val c = rendered.charAt(i)
      if c == '\u001b' then
        val end = rendered.indexOf('m', i)
        val stop = if end < 0 then rendered.length else end + 1
        word.append(rendered.substring(i, stop))
        i = stop
      else
        val cp = rendered.codePointAt(i)
        val size = Character.charCount(cp)
        if cp == ' ' then
          out.append(endWord())
          spaces += 1
        else
          word.append(rendered.substring(i, i + size))
          wordCells += Screen.cellWidth(cp, col + wordCells)
        i += size
    out.toString

  /** Write the held word after its spaces, or start a new row with it when it does not fit on
    * this one. Spaces with no word after them end the line and are dropped. */
  private def endWord(): String =
    val hangCells = TextLayout.width(hang)
    val wrap = wordCells > 0 && col > hangCells && col + spaces + wordCells > columns()
    val lead = if wrap then "\n" + hang else if word.isEmpty then "" else " " * spaces
    col = (if wrap then hangCells else col + TextLayout.width(lead)) + wordCells
    val text = lead + word
    word.clear()
    wordCells = 0
    spaces = 0
    text

  /** A fenced line is coloured with the lines since a comment or string opened as
    * context, up to [[OpenFenceContext]], or else the last [[FenceContext]] lines (a
    * definition may span a few), so a long block costs the same per line as a short one. */
  private def fenceLine(line: String): String =
    val shown =
      if fenceScala then
        fenceLines += line
        val (coloured, open) = highlight(fenceLines.mkString("\n"))
        val keep = if open then OpenFenceContext else FenceContext
        fenceLines.dropInPlace((fenceLines.size - keep).max(0))
        coloured
      else line
    glyphs.codeGutter + shown + "\n"

  // ── tables ────────────────────────────────────────────────────────

  private def isTableRow(line: String): Boolean = TableRow.matches(line) // the same shape `WholeLine` holds back
  private def isDelimiterRow(line: String): Boolean = DelimiterRow.matches(line)

  /** The cells of a row: outer pipes dropped, `\|` kept as a literal pipe. */
  private def cells(row: String): List[String] =
    val inner = row.trim.stripPrefix("|").stripSuffix("|")
    inner.split("""(?<!\\)\|""", -1).toList.map(_.trim.replace("\\|", "|"))

  private def alignmentOf(delimiter: String): Align =
    val d = delimiter.trim
    if d.startsWith(":") && d.endsWith(":") then Align.Center
    else if d.endsWith(":") then Align.Right
    else Align.Left

  /** Terminal cell width after removing inline style markers. */
  private def visibleLength(cell: String): Int = Screen.displayWidth(cell.replace("**", "").replace("`", ""))

  /** Draw the collected table: bold header, a rule with junctions, cells
    * padded to the column width and aligned as the delimiter row says. */
  private def renderTable(): String =
    val rows = table.toList.map(cells)
    table.clear()
    val aligns = rows(1).map(alignmentOf)
    val (header, body) = (rows.head, rows.drop(2))
    val columns = (header :: body).map(_.length).max
    val natural = (0 until columns).map(c => (header :: body).map(r => visibleLength(r.lift(c).getOrElse(""))).max)
    val available = this.columns().max(10) - (columns - 1) * 3
    val minimum = natural.map(_.min(12))
    def renderCell(text: String, isHeader: Boolean): String =
      lineStyle = if isHeader then List(Bold) else Nil
      val rendered = current() + spans(text) + Reset
      bold = false; code = false; lineStyle = Nil
      rendered
    val cramped = natural.sum > available && columns >= 3 && available / columns < 24
    if minimum.sum > available || cramped then
      // Too narrow for a grid: each row becomes a record of labeled fields.
      val records = body.map: row =>
        val fields = header.zipAll(row, "", "").map((label, text) => renderCell(label, true) -> renderCell(text, false))
        TextLayout.fields(fields, this.columns()).mkString("\n") + "\n"
      records.mkString("\n")
    else
      // Find a common column cap, allowing short columns to leave room for longer text.
      var low = 0
      var high = natural.max
      while low < high do
        val mid = low + (high - low + 1) / 2
        if natural.indices.map(c => natural(c).min(mid).max(minimum(c))).sum <= available then low = mid
        else high = mid - 1
      val widths = natural.indices.map(c => natural(c).min(low).max(minimum(c)))
      def edge(c: Int): (String, String) = (if c == 0 then "" else " ", if c == columns - 1 then "" else " ")
      val bar = Reset + sgr(Dim) + glyphs.bar + Reset
      def draw(row: List[String], isHeader: Boolean): String =
        val cells = (0 until columns).map: c =>
          TextLayout.wrap(renderCell(row.lift(c).getOrElse(""), isHeader), widths(c))
        (0 until cells.map(_.size).max).map { line =>
          (0 until columns).map { c =>
            val text = cells(c).lift(line).getOrElse("")
            val padding = (widths(c) - TextLayout.width(text)).max(0)
            val (left, right) = aligns.lift(c).getOrElse(Align.Left) match
              case Align.Left => (0, padding)
              case Align.Right => (padding, 0)
              case Align.Center => (padding / 2, padding - padding / 2)
            val (before, after) = edge(c)
            before + " " * left + text + " " * (if c == columns - 1 then 0 else right) + after
          }.mkString(bar) + "\n"
        }.mkString
      val rule = sgr(Dim) + (0 until columns).map { c =>
        val (before, after) = edge(c)
        glyphs.rule * (widths(c) + before.length + after.length)
      }.mkString(glyphs.junction) + Reset + "\n"
      draw(header, isHeader = true) + rule + body.map(draw(_, isHeader = false)).mkString

  // ── inline ────────────────────────────────────────────────────────

  /** Keep a trailing lone `*` (maybe half of `**`) for the next chunk. */
  private def withhold(text: String): String =
    if text.endsWith("*") && !text.endsWith("**") then { pending.insert(0, "*"); text.dropRight(1) }
    else text

  private def spans(text: String): String =
    val out = StringBuilder()
    var i = 0
    while i < text.length do
      val c = text(i)
      if c == '`' then
        code = !code
        out.append(current())
        i += 1
      else if c == '*' && i + 1 < text.length && text(i + 1) == '*' && !code then
        bold = !bold
        out.append(current())
        i += 2
      else
        out.append(c)
        i += 1
    out.toString

  /** The SGR sequence for the styles in force (reset first: SGR has no portable "bold off"). */
  private def current(): String =
    val codes = lineStyle ++ (if bold then List(Bold) else Nil) ++ (if code then List(CodeColor) else Nil)
    if codes.isEmpty then Reset else Reset + sgr(codes*)

object MarkdownStream:
  /** Rendered pieces the stream needs from the terminal layer. `codeGutter`
    * is the already-styled gutter put before fenced code lines; `bar` and
    * `junction` draw table columns and the header rule (`│`, `┼`). */
  final case class Glyphs(
    bullet: String,
    quote: String,
    rule: String,
    codeGutter: String,
    bar: String = "|",
    junction: String = "+"
  )

  /** Lines of context kept for the highlighter after every comment and string is closed. */
  private val FenceContext = 8
  /** Lines of an open comment or string kept as context; an unclosed one must not make
    * every further line cost the whole block. */
  private val OpenFenceContext = 200

  /** A highlighter that colours nothing: the last line as it is. */
  val verbatim: String => (String, Boolean) = code => (code.substring(code.lastIndexOf('\n') + 1), false)

  /** No rendering at all (no colours available): text passes through untouched. */
  def plain: MarkdownStream = new MarkdownStream(Glyphs("-", ">", "-", ""), verbatim):
    override def push(chunk: String): String = chunk
    override def finish(): String = ""

  private enum Align:
    case Left, Right, Center

  /** Inline `` `code` ``. */
  private val CodeColor = Ansi.Cyan

  private val FenceRe = """^ {0,3}```+\s*([A-Za-z0-9_+-]*).*$""".r
  private val RuleRe = """^ {0,3}(?:-{3,}|\*{3,}|_{3,}) *$""".r
  private val HeadingRe = """^ {0,3}(#{1,6}) +(.*)$""".r
  private val BulletRe = """^( *)[-*+] (.*)$""".r
  private val OrderedRe = """^( *)(\d{1,3}\.) .*$""".r
  private val QuoteRe = """^ {0,3}> ?(.*)$""".r
  /** `| --- | :---: | ---: |`, outer pipes optional. Some pipe must appear
    * (the lookahead): a bare `---` is a horizontal rule, not a one-column
    * delimiter row. */
  private val DelimiterRow = """^(?=[^\n]*\|)\s*\|?(?:\s*:?-+:?\s*\|)*\s*:?-+:?\s*\|?\s*$""".r

  /** Whether `s` (a line so far, without newline) may still turn into a line
    * marker once more characters arrive, in which case it is held back rather
    * than rendered. Fence lines (their language matters) and `|` lines (table
    * rows are laid out together) are always waited for in full. */
  private val MarkerPrefix = """^ {0,3}(?:#{0,6} ?|[-*+] ?|\d{0,3}\.? ?|> ?|`{0,3}|-{0,3}|\*{0,3}|_{0,3})$""".r
  private val TableRow = """^ {0,3}\|.*$""".r
  private val WholeLine = """^ {0,3}(?:```|\|).*$""".r
  def couldStillBeMarker(s: String): Boolean = (s.length < 8 && MarkerPrefix.matches(s)) || WholeLine.matches(s)
