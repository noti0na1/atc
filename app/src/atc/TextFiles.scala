package atc

/** Small, platform-independent text-file conventions used at file-format
  * boundaries. Line splitting accepts LF, CRLF, and bare CR; rewriting uses
  * the first ending found so mixed files have a stable convention. */
private[atc] object TextFiles:
  val DefaultLineEnding = "\n"

  /** Number of characters occupied by a leading Unicode byte-order mark. */
  def bomLength(text: String): Int =
    if text.nonEmpty && text.charAt(0) == '\uFEFF' then 1 else 0

  /** Drop one leading Unicode byte-order mark, leaving embedded marks alone. */
  def stripBom(text: String): String = text.substring(bomLength(text))

  /** One line ending and its position in the containing string. */
  case class LineEnding(index: Int, text: String)

  def firstLineEnding(text: String): Option[LineEnding] =
    var index = 0
    while index < text.length do
      lineEndingAt(text, index) match
        case Some(ending) => return Some(ending)
        case None => index += 1
    None

  def lastLineEnding(text: String): Option[LineEnding] =
    var last: Option[LineEnding] = None
    var index = 0
    while index < text.length do
      lineEndingAt(text, index) match
        case Some(ending) =>
          last = Some(ending)
          index += ending.text.length
        case None => index += 1
    last

  /** Lines plus enough source formatting to join them again. A mixed-ending
    * input is normalized to its first ending when joined. */
  case class LineSplit(lines: List[String], lineEnding: String, trailingLineEnding: Boolean):
    def join: String = joinLines(lines, lineEnding, trailingLineEnding)

  def splitLines(text: String): LineSplit =
    if text.isEmpty then LineSplit(Nil, DefaultLineEnding, trailingLineEnding = true)
    else
      val lines = collection.mutable.ListBuffer[String]()
      var firstEnding: Option[String] = None
      var start = 0
      var index = 0
      while index < text.length do
        lineEndingAt(text, index) match
          case None => index += 1
          case Some(ending) =>
            if firstEnding.isEmpty then firstEnding = Some(ending.text)
            lines += text.substring(start, index)
            index += ending.text.length
            start = index
      val trailing = start == text.length
      if !trailing then lines += text.substring(start)
      LineSplit(lines.toList, firstEnding.getOrElse(DefaultLineEnding), trailing)

  def joinLines(lines: List[String], lineEnding: String, trailingLineEnding: Boolean): String =
    if lines.isEmpty then ""
    else lines.mkString(lineEnding) + (if trailingLineEnding then lineEnding else "")

  /** Append one logical line, preserving the existing text and its first line
    * ending. A missing final ending is supplied; an existing one is replaced
    * only when a mixed-ending file ends differently from its first ending. */
  def appendLine(text: String, line: String): String =
    val lineEnding = firstLineEnding(text).fold(DefaultLineEnding)(_.text)
    val prefix =
      if text.isEmpty then ""
      else
        lastLineEnding(text).filter(e => e.index + e.text.length == text.length) match
          case Some(ending) => text.substring(0, ending.index) + lineEnding
          case None => text + lineEnding
    prefix + line + lineEnding

  private def lineEndingAt(text: String, index: Int): Option[LineEnding] =
    text.charAt(index) match
      case '\r' if index + 1 < text.length && text.charAt(index + 1) == '\n' =>
        Some(LineEnding(index, "\r\n"))
      case '\r' | '\n' => Some(LineEnding(index, text.charAt(index).toString))
      case _ => None
