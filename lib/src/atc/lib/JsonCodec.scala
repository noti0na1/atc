package atc.lib

import language.experimental.captureChecking

/** Parser and renderer behind [[Json]]; not part of the agent-visible API (the
  * agent sees `Json.parse`/`render`/`pretty`), so it is not in `Interface.scala`. */
private[atc] object JsonCodec:

  def kind(j: Json): String = j match
    case Json.Null => "null"
    case _: Json.Bool => "a boolean"
    case _: Json.Num => "a number"
    case _: Json.Str => "a string"
    case _: Json.Arr => "an array"
    case _: Json.Obj => "an object"

  def render(j: Json, pretty: Boolean): String =
    val sb = StringBuilder()
    def newline(depth: Int): Unit =
      if pretty then sb.append('\n').append("  " * depth)
    def go(j: Json, depth: Int): Unit = j match
      case Json.Null => sb.append("null")
      case Json.Bool(b) => sb.append(b)
      case Json.Num(d) => sb.append(number(d))
      case Json.Str(s) => quote(sb, s)
      case Json.Arr(items) =>
        if items.isEmpty then sb.append("[]")
        else
          sb.append('[')
          var first = true
          for it <- items do
            if !first then sb.append(',')
            first = false
            newline(depth + 1)
            go(it, depth + 1)
          newline(depth)
          sb.append(']')
      case Json.Obj(fields) =>
        if fields.isEmpty then sb.append("{}")
        else
          sb.append('{')
          var first = true
          for (k, v) <- fields do
            if !first then sb.append(',')
            first = false
            newline(depth + 1)
            quote(sb, k)
            sb.append(if pretty then ": " else ":")
            go(v, depth + 1)
          newline(depth)
          sb.append('}')
    go(j, 0)
    sb.toString

  /** JSON has no NaN/Infinity (rendered as null); whole numbers print as integers. */
  private def number(d: Double): String =
    if d.isNaN || d.isInfinite then "null"
    else if d.isWhole && d.abs < 1e15 then d.toLong.toString
    else d.toString

  private def quote(sb: StringBuilder, s: String): Unit =
    sb.append('"')
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      c match
        case '"' => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case c if c < ' ' => sb.append(f"\\u${c.toInt}%04x")
        case c => sb.append(c)
      i += 1
    sb.append('"')

  def parse(text: String): Json =
    val p = Parser(text)
    p.skipWs()
    val v = p.value()
    p.skipWs()
    if p.i < text.length then p.fail("unexpected text after the value")
    v

  private final class Parser(s: String):
    var i: Int = 0

    def fail(msg: String): Nothing =
      val near = s.slice(i, math.min(s.length, i + 20))
      throw IllegalArgumentException(s"invalid JSON at offset $i: $msg (near '$near')")

    def skipWs(): Unit =
      while i < s.length && (s.charAt(i) == ' ' || s.charAt(i) == '\n' || s.charAt(i) == '\r' || s.charAt(i) == '\t')
      do i += 1

    def value(): Json =
      if i >= s.length then fail("unexpected end of input")
      s.charAt(i) match
        case '{' => obj()
        case '[' => arr()
        case '"' => Json.Str(str())
        case 't' => lit("true", Json.Bool(true))
        case 'f' => lit("false", Json.Bool(false))
        case 'n' => lit("null", Json.Null)
        case c if c == '-' || (c >= '0' && c <= '9') => number()
        case c => fail(s"unexpected character '$c'")

    private def lit(word: String, v: Json): Json =
      if s.startsWith(word, i) then
        i += word.length
        v
      else fail(s"expected $word")

    private def number(): Json =
      val start = i
      if s.charAt(i) == '-' then i += 1
      if i >= s.length then fail("expected a digit after '-'")
      if s.charAt(i) == '0' then
        i += 1
        if i < s.length && s.charAt(i) >= '0' && s.charAt(i) <= '9' then fail("leading zero in number")
      else if s.charAt(i) >= '1' && s.charAt(i) <= '9' then
        while i < s.length && s.charAt(i) >= '0' && s.charAt(i) <= '9' do i += 1
      else fail("expected a digit")
      if i < s.length && s.charAt(i) == '.' then
        i += 1
        val fraction = i
        while i < s.length && s.charAt(i) >= '0' && s.charAt(i) <= '9' do i += 1
        if i == fraction then fail("expected a digit after the decimal point")
      if i < s.length && (s.charAt(i) == 'e' || s.charAt(i) == 'E') then
        i += 1
        if i < s.length && (s.charAt(i) == '+' || s.charAt(i) == '-') then i += 1
        val exponent = i
        while i < s.length && s.charAt(i) >= '0' && s.charAt(i) <= '9' do i += 1
        if i == exponent then fail("expected a digit in the exponent")
      val t = s.slice(start, i)
      t.toDoubleOption match
        case Some(d) if d.isFinite => Json.Num(d)
        case Some(_) => fail(s"number out of range '$t'")
        case None => fail(s"bad number '$t'")

    private def str(): String =
      i += 1 // opening quote
      val sb = StringBuilder()
      var done = false
      while !done do
        if i >= s.length then fail("unterminated string")
        val c = s.charAt(i)
        if c == '"' then
          done = true
          i += 1
        else if c == '\\' then
          if i + 1 >= s.length then fail("unterminated escape")
          val e = s.charAt(i + 1)
          i += 2
          e match
            case '"' => sb.append('"')
            case '\\' => sb.append('\\')
            case '/' => sb.append('/')
            case 'b' => sb.append('\b')
            case 'f' => sb.append('\f')
            case 'n' => sb.append('\n')
            case 'r' => sb.append('\r')
            case 't' => sb.append('\t')
            case 'u' =>
              if i + 4 > s.length then fail("bad \\u escape")
              val hex = s.slice(i, i + 4)
              val code =
                try Integer.parseInt(hex, 16)
                catch case _: NumberFormatException => fail(s"bad \\u escape '$hex'")
              sb.append(code.toChar)
              i += 4
            case other => fail(s"bad escape '\\$other'")
        else if c < ' ' then fail("unescaped control character in string")
        else
          sb.append(c)
          i += 1
      sb.toString

    private def arr(): Json =
      i += 1 // [
      skipWs()
      if i < s.length && s.charAt(i) == ']' then
        i += 1
        Json.Arr(Nil)
      else
        var items = List.empty[Json]
        var more = true
        while more do
          skipWs()
          items = value() :: items
          skipWs()
          if i >= s.length then fail("unterminated array")
          s.charAt(i) match
            case ',' =>
              i += 1
              skipWs()
              if i < s.length && s.charAt(i) == ']' then // tolerated trailing comma
                i += 1
                more = false
            case ']' =>
              i += 1
              more = false
            case c => fail(s"expected ',' or ']' but found '$c'")
        Json.Arr(items.reverse)

    private def obj(): Json =
      i += 1 // {
      skipWs()
      if i < s.length && s.charAt(i) == '}' then
        i += 1
        Json.Obj(Nil)
      else
        var fields = List.empty[(String, Json)]
        var more = true
        while more do
          skipWs()
          if i >= s.length || s.charAt(i) != '"' then fail("expected a quoted field name")
          val k = str()
          skipWs()
          if i >= s.length || s.charAt(i) != ':' then fail("expected ':' after the field name")
          i += 1
          skipWs()
          val v = value()
          fields = (k, v) :: fields
          skipWs()
          if i >= s.length then fail("unterminated object")
          s.charAt(i) match
            case ',' =>
              i += 1
              skipWs()
              if i < s.length && s.charAt(i) == '}' then // tolerated trailing comma
                i += 1
                more = false
            case '}' =>
              i += 1
              more = false
            case c => fail(s"expected ',' or '}' but found '$c'")
        Json.Obj(fields.reverse)
