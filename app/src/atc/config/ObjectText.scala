package atc.config

import atc.{Debug, TextFiles}

/** The top-level members of a JSON object's text, with their positions. */
private[config] final case class ObjectText(text: String, open: Int, close: Int, members: List[ObjectText.Member]):
  /** What goes between two members: the newline and indent before the first
    * one, or a single space in a one-line object. */
  def separator: String =
    members.headOption match
      case Some(first) =>
        val before = text.substring(open + 1, first.keyStart)
        TextFiles.lastLineEnding(before).fold(" ")(ending => before.substring(ending.index))
      case None =>
        val inside = text.substring(open + 1, close)
        TextFiles.firstLineEnding(inside).fold(TextFiles.DefaultLineEnding)(_.text) + "  "

/** Edits of a config's text that keep everything they do not change: a config is
  * hand-formatted (blank lines, several patterns per line), and serializing it
  * again would lose that. Positions come from the [[scan]] of a validated object. */
object ObjectText:
  /** A member: `keyStart` is its opening quote, `valueStart`/`valueEnd` bound
    * the value (no surrounding whitespace, no trailing comma). */
  private[config] final case class Member(key: String, keyStart: Int, valueStart: Int, valueEnd: Int)

  /** `text` parsed as a config, which must be a JSON object; `where` names it in errors. */
  private[config] def parse(text: String, where: String): ujson.Obj =
    val parsed =
      try ujson.read(TextFiles.stripBom(text))
      catch case e: Exception => throw IllegalArgumentException(s"Cannot parse config $where: ${Debug.message(e)}")
    parsed match
      case o: ujson.Obj => o
      case _ => throw IllegalArgumentException(s"Config $where must be a JSON object")

  /** `text` (a JSON object) with the top-level `key` set to `value`: an
    * existing key keeps its place and only its value changes; a new one is
    * added after the first of `after` that is present, else first, indented
    * like the others. Everything else in the text is untouched. */
  def withTopLevel(
    text: String,
    key: String,
    value: ujson.Value,
    after: List[String] = Nil,
    where: String = "config"
  ): String =
    parse(text, where) // fail clearly on anything that is not a JSON object
    val obj = scan(text)
    val rendered = ujson.write(value)
    // ujson honors the final occurrence of a duplicate key. Rewrite that occurrence;
    // changing an earlier one would have no effect on the parsed value.
    obj.members.findLast(_.key == key) match
      case Some(m) => text.substring(0, m.valueStart) + rendered + text.substring(m.valueEnd)
      case None =>
        val entry = s"${ujson.write(ujson.Str(key))}: $rendered"
        after.flatMap(k => obj.members.find(_.key == k)).headOption match
          case Some(prev) =>
            text.substring(0, prev.valueEnd) + s",${obj.separator}$entry" + text.substring(prev.valueEnd)
          case None if obj.members.isEmpty =>
            val separator = obj.separator
            val newline = TextFiles.firstLineEnding(separator).fold(TextFiles.DefaultLineEnding)(_.text)
            text.substring(0, obj.open + 1) + separator + entry + newline + text.substring(obj.close)
          case None =>
            text.substring(0, obj.open + 1) + s"${obj.separator}$entry," + text.substring(obj.open + 1)

  /** `text` (a JSON object) with the member at `path` set to `value`, or removed
    * when `value` is `None`. Objects missing on the way are created, and a new
    * member goes last in its object, written on one line. Everything else keeps
    * its text, like [[withTopLevel]]. */
  def withMember(text: String, path: List[String], value: Option[ujson.Value], where: String = "config"): String =
    parse(text, where)
    member(text, path, value)

  private def member(text: String, path: List[String], value: Option[ujson.Value]): String =
    val obj = scan(text)
    val key = path.head
    def replace(m: Member, by: String) = text.substring(0, m.valueStart) + by + text.substring(m.valueEnd)
    def nested(v: ujson.Value) = path.tail.foldRight(v)((k, inner) => ujson.Obj(k -> inner))
    obj.members.findLast(_.key == key) match
      case Some(m) if path.tail.isEmpty => value.fold(removeMember(obj, m))(v => replace(m, oneLine(v)))
      case Some(m) =>
        val inner = text.substring(m.valueStart, m.valueEnd)
        if inner.startsWith("{") then replace(m, member(inner, path.tail, value))
        else value.fold(text)(v => replace(m, oneLine(nested(v))))
      case None =>
        value.fold(text): v =>
          val entry = s"${ujson.write(ujson.Str(key))}: ${oneLine(nested(v))}"
          obj.members.lastOption match
            case Some(last) =>
              text.substring(0, last.valueEnd) + s",${obj.separator}$entry" + text.substring(last.valueEnd)
            case None => text.substring(0, obj.open + 1) + s" $entry " + text.substring(obj.close)

  /** Remove `m` with the comma that separates it from a neighbour. */
  private def removeMember(obj: ObjectText, m: Member): String =
    val text = obj.text
    val i = obj.members.indexOf(m)
    if i + 1 < obj.members.size then text.substring(0, m.keyStart) + text.substring(obj.members(i + 1).keyStart)
    else if i > 0 then text.substring(0, obj.members(i - 1).valueEnd) + text.substring(m.valueEnd)
    else text.substring(0, obj.open + 1) + text.substring(obj.close)

  /** A value on one line, spaced like a hand-written config. */
  private def oneLine(v: ujson.Value): String = v match
    case ujson.Obj(o) if o.isEmpty => "{}"
    case ujson.Obj(o) => o.map((k, x) => s"${ujson.write(ujson.Str(k))}: ${oneLine(x)}").mkString("{ ", ", ", " }")
    case ujson.Arr(a) => a.map(oneLine).mkString("[", ", ", "]")
    case other => ujson.write(other)

  /** Positions of the top-level members of `text`, which must already be
    * known to be a well-formed JSON object. */
  private[config] def scan(text: String): ObjectText =
    var i = TextFiles.bomLength(text)
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
              case '{' | '[' =>
                depth += 1
                i += 1
              case '}' | ']' if depth == 0 => done = true
              case '}' | ']' =>
                depth -= 1
                i += 1
              case ',' if depth == 0 => done = true
              case _ => i += 1
          var valueEnd = i
          while text(valueEnd - 1).isWhitespace do valueEnd -= 1
          members += Member(key, keyStart, valueStart, valueEnd)
    ObjectText(text, open, close, members.result())
