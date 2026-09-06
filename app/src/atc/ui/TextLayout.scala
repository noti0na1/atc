package atc.ui

import org.jline.utils.{AttributedString, WCWidth}

/** Width-aware wrapping for complete terminal lines. ANSI styles do not consume columns. */
private[atc] object TextLayout:
  def width(text: String): Int = AttributedString.fromAnsi(text).nn.columnLength()

  def wrap(text: String, columns: Int): List[String] =
    val room = columns.max(2)
    text.split("\n", -1).toList.flatMap { line =>
      if width(line) <= room then List(line)
      else
        val lines = List.newBuilder[String]
        val styled = AttributedString.fromAnsi(line).nn
        val plain = styled.toString
        var start = 0
        while start < plain.length do
          var end = start
          var used = 0
          var space = -1
          var full = false
          while end < plain.length && !full do
            val cp = plain.codePointAt(end)
            val size = if cp == '\t' then 8 - used % 8 else WCWidth.wcwidth(cp).max(0)
            if used + size > room then full = true
            else
              if Character.isWhitespace(cp) then space = end
              used += size
              end += Character.charCount(cp)
          val cut = if full && space > start && space - start >= (end - start) / 2 then space else end
          lines += styled.subSequence(start, cut).nn.toAnsi().nn
          start = cut
          while start < plain.length && plain.charAt(start).isWhitespace do start += 1
        lines.result()
    }

  /** Keep labels beside their values when there is room, otherwise place values below them. */
  def fields(rows: List[(String, String)], columns: Int): List[String] =
    val labelWidth = rows.map(row => width(row._1)).maxOption.getOrElse(0)
    val valueWidth = columns - labelWidth - 5
    rows.flatMap { (label, value) =>
      if valueWidth < 28 then
        wrap(label, columns - 3).map("  " + _) ++ wrap(value, columns - 5).map("    " + _)
      else
        val prefix = "  " + label + " " * (labelWidth - width(label) + 2)
        wrap(value, valueWidth).zipWithIndex.map((line, index) =>
          (if index == 0 then prefix else " " * (labelWidth + 4)) + line
        )
    }
