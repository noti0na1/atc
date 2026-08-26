package atc.ui

/** Streaming Markdown → ANSI, tuned for what models write in a terminal:
  * headings, `- `/`* `/`1. ` lists, `> ` quotes, `---` rules, fenced code
  * blocks (coloured only when the fence says `scala`; other languages and
  * untagged blocks are shown verbatim), pipe tables, and inline `**bold**` /
  * `` `code` ``. Text may arrive in arbitrary chunks: `push` returns what can
  * be rendered now and holds back only what is still ambiguous (the first
  * characters of a line, a trailing `*`, an unfinished fenced line, a table
  * — its column widths need every row); `finish` flushes the rest.
  *
  * Styles are scoped to one line: an unclosed `**` never bleeds into the
  * next line. Everything is emitted as raw SGR sequences.
  *
  * @param glyphs    what to draw bullets, quote bars, rules, code gutters and tables with
  * @param highlight colours a whole fenced Scala block; only its last line is used per push
  */
class MarkdownStream(glyphs: MarkdownStream.Glyphs, highlight: String => List[String]):
  import MarkdownStream.*
  import Ansi.{sgr, Bold, Dim, Reset}

  private val pending = StringBuilder()
  private var decided = false
  private var lineStyle: List[Int] = Nil // heading → bold, quote → dim
  private var bold = false
  private var code = false
  private var inFence = false
  private var fenceScala = false
  private val fenceText = StringBuilder()
  private var restStart = 0
  /** The current line produces no output at all (a dropped fence marker). */
  private var dropLine = false
  /** A `|` line held back until the next line tells whether a table starts (a delimiter row). */
  private var tableHead: Option[String] = None
  /** The rows (header, delimiter, body) of the table being collected. */
  private val table = collection.mutable.ListBuffer[String]()

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
            out.append(spans(withhold(line.drop(restStart))))
      else if nl >= 0 then
        val line = takeLine(nl)
        out.append(spans(line)).append(endLine())
        progress = true
      else
        val text = pending.toString; pending.clear()
        out.append(spans(withhold(text)))
    out.toString

  /** Flush whatever is still held (end of the message). */
  def finish(): String =
    val out = StringBuilder()
    if pending.nonEmpty then
      val text = pending.toString; pending.clear()
      if inFence then out.append(fenceLine(text))
      else if !decided then out.append(completeLine(text))
      else out.append(spans(text)).append(endLine())
    else if decided then out.append(endLine())
    out.append(leaveTable())
    decided = false
    inFence = false
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
    if inFence || dropLine then prefix else prefix + spans(line.drop(restStart)) + endLine()

  /** Decide the kind of a line and emit its prefix; sets `restStart` to where the text begins. */
  private def startLine(line: String): String =
    restStart = 0
    lineStyle = Nil
    dropLine = false
    line match
      case FenceRe(lang0) =>
        val lang = lang0.nn.toLowerCase(java.util.Locale.ROOT)
        restStart = line.length
        // Models like to wrap a whole answer in ```markdown: render its content, drop the fence.
        if lang == "markdown" || lang == "md" then { dropLine = true; "" }
        else
          inFence = true
          fenceScala = lang == "scala" || lang == "sc" // anything else: no colouring, verbatim
          fenceText.clear()
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
    val close = if bold || code || lineStyle.nonEmpty then Reset else ""
    bold = false; code = false; lineStyle = Nil
    close + "\n"

  private def fenceLine(line: String): String =
    fenceText.append(line).append("\n")
    // The block so far ends with "\n", so its highlighted lines map 1:1 onto the fenced lines; take the newest.
    val shown = if fenceScala then highlight(fenceText.toString).lastOption.getOrElse(line) else line
    glyphs.codeGutter + shown + "\n"

  // ── tables ────────────────────────────────────────────────────────

  private def isTableRow(line: String): Boolean = line.trim.startsWith("|")
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

  /** Length as shown: inline markers take no room. */
  private def visibleLength(cell: String): Int = cell.replace("**", "").replace("`", "").length

  /** Draw the collected table: bold header, a rule with junctions, cells
    * padded to the column width and aligned as the delimiter row says. */
  private def renderTable(): String =
    val rows = table.toList.map(cells)
    table.clear()
    val aligns = rows(1).map(alignmentOf)
    val (header, body) = (rows.head, rows.drop(2))
    val columns = (header :: body).map(_.length).max
    val widths = (0 until columns).map(c => (header :: body).map(r => visibleLength(r.lift(c).getOrElse(""))).max)
    def edge(c: Int): (String, String) = (if c == 0 then "" else " ", if c == columns - 1 then "" else " ")
    val bar = Reset + sgr(Dim) + glyphs.bar + Reset
    def draw(row: List[String], isHeader: Boolean): String =
      lineStyle = if isHeader then List(Bold) else Nil
      val line = (0 until columns).map { c =>
        val text = row.lift(c).getOrElse("")
        val padding = widths(c) - visibleLength(text)
        val (left, right) = aligns.lift(c).getOrElse(Align.Left) match
          case Align.Left => (0, padding)
          case Align.Right => (padding, 0)
          case Align.Center => (padding / 2, padding - padding / 2)
        val (before, after) = edge(c)
        val rendered = current() + spans(text) + Reset
        bold = false; code = false // styles do not cross cells
        val trailing = if c == columns - 1 then 0 else right // no padding after the last column
        before + " " * left + rendered + " " * trailing + after
      }.mkString(bar)
      lineStyle = Nil
      line + "\n"
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

  /** No rendering at all (no colours available): text passes through untouched. */
  def plain: MarkdownStream = new MarkdownStream(Glyphs("-", ">", "-", ""), _.linesIterator.toList):
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
    * marker once more characters arrive — then we wait rather than render.
    * Fence lines (their language matters) and `|` lines (table rows are laid
    * out together) are always waited for in full. */
  private val MarkerPrefix = """^ {0,3}(?:#{0,6} ?|[-*+] ?|\d{0,3}\.? ?|> ?|`{0,3}|-{0,3}|\*{0,3}|_{0,3})$""".r
  private val WholeLine = """^ {0,3}(?:```|\|).*$""".r
  def couldStillBeMarker(s: String): Boolean = (s.length < 8 && MarkerPrefix.matches(s)) || WholeLine.matches(s)
