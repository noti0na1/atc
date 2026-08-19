package atc.config

import upickle.default.*

/** A token count in a config, written as a number or as a string with a
  * suffix: `200000`, `"200000"`, `"256k"`, `"1m"`, `"1.5m"` (`k` = 1000,
  * `m` = 1000000, either case). Decimal on purpose: a window given as `"128k"`
  * then never overshoots the model's real one, whichever convention the
  * vendor's figure follows. */
opaque type Tokens = Int
object Tokens:
  def apply(n: Int): Tokens = n
  extension (t: Tokens) def toInt: Int = t

  private val Form = raw"(?i)\s*(\d+(?:\.\d+)?)\s*([km]?)\s*".r

  /** Parse `text`; throws `IllegalArgumentException` for anything else. */
  def parse(text: String): Tokens =
    text match
      case Form(number, unit) =>
        val scale = unit.nn.toLowerCase match
          case "k" => 1e3
          case "m" => 1e6
          case _ => 1.0
        val n = number.nn.toDouble * scale
        if n < 1 || n > Int.MaxValue then throw IllegalArgumentException(s"Token count out of range: '$text'")
        n.round.toInt
      case _ =>
        throw IllegalArgumentException(
          s"Not a token count: '$text' (write a number, or one with k/m: \"256k\", \"1m\")"
        )

  given ReadWriter[Tokens] = readwriter[ujson.Value].bimap[Tokens](
    n => ujson.Num(n.toInt),
    {
      case ujson.Num(n) if n.isWhole && n >= 1 && n <= Int.MaxValue => n.toInt
      case ujson.Num(n) => throw IllegalArgumentException(s"Token count out of range: $n")
      case ujson.Str(s) => parse(s)
      case other => throw IllegalArgumentException(s"Not a token count: $other")
    }
  )
