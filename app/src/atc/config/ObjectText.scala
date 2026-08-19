package atc.config

/** The top-level members of a JSON object's text, with their positions. */
private[config] case class ObjectText(text: String, open: Int, close: Int, members: List[ObjectText.Member]):
  /** What goes between two members: the newline and indent before the first
    * one, or a single space in a one-line object. */
  def separator: String =
    members.headOption match
      case Some(first) =>
        val nl = text.lastIndexOf('\n', first.keyStart)
        if nl > open then text.substring(nl, first.keyStart) else " "
      case None => "\n  "

private[config] object ObjectText:
  /** A member: `keyStart` is its opening quote, `valueStart`/`valueEnd` bound
    * the value (no surrounding whitespace, no trailing comma). */
  case class Member(key: String, keyStart: Int, valueStart: Int, valueEnd: Int)

  /** Positions of the top-level members of `text`, which must already be
    * known to be a well-formed JSON object. */
  def scan(text: String): ObjectText =
    var i = 0
    def skipSpace(): Unit = while i < text.length && text(i).isWhitespace do i += 1
    /** From an opening quote at `i` to just past the closing one. */
    def skipString(): Unit =
      i += 1
      while text(i) != '"' do i += (if text(i) == '\\' then 2 else 1)
      i += 1
    skipSpace()
    val open = i
    i += 1
    val members = List.newBuilder[Member]
    var close = -1
    while close < 0 do
      skipSpace()
      text(i) match
        case '}' => close = i
        case ',' => i += 1
        case _ =>
          val keyStart = i
          skipString()
          val key = ujson.read(text.substring(keyStart, i)).str
          skipSpace()
          i += 1 // the colon
          skipSpace()
          val valueStart = i
          var depth = 0
          var done = false
          while !done do
            text(i) match
              case '"' => skipString()
              case '{' | '[' => depth += 1; i += 1
              case '}' | ']' if depth == 0 => done = true
              case '}' | ']' => depth -= 1; i += 1
              case ',' if depth == 0 => done = true
              case _ => i += 1
          var valueEnd = i
          while text(valueEnd - 1).isWhitespace do valueEnd -= 1
          members += Member(key, keyStart, valueStart, valueEnd)
    ObjectText(text, open, close, members.result())
