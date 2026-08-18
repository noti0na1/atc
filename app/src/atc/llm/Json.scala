package atc.llm

import scala.jdk.CollectionConverters.*

/** ujson ↔ plain Java values, for the SDKs' `JsonValue.from(...)` builders
  * (tool schemas, tool-call inputs) and back (tool-use arguments). */
object Json:
  def toJava(v: ujson.Value): AnyRef | Null = v match
    case ujson.Str(s) => s
    case ujson.Num(n) => if n == n.toLong then java.lang.Long.valueOf(n.toLong) else java.lang.Double.valueOf(n)
    case ujson.Bool(b) => java.lang.Boolean.valueOf(b)
    case ujson.Null => null
    case ujson.Arr(a) => a.map(toJava).asJava
    case ujson.Obj(o) => o.map((k, x) => k -> toJava(x)).asJava

  def fromJava(v: AnyRef | Null): ujson.Value = v match
    case null => ujson.Null
    case s: String => ujson.Str(s)
    case n: java.lang.Number => ujson.Num(n.doubleValue)
    case b: java.lang.Boolean => ujson.Bool(b)
    case l: java.util.List[?] => ujson.Arr(l.asScala.map(x => fromJava(x.asInstanceOf[AnyRef | Null])).toSeq*)
    case m: java.util.Map[?, ?] =>
      val o = ujson.Obj()
      m.asScala.foreach((k, x) => o(String.valueOf(k)) = fromJava(x.asInstanceOf[AnyRef | Null]))
      o
    case other => ujson.Str(other.toString)

  /** Parse tool-call arguments leniently: empty or malformed → `{}`. */
  def parseObject(arguments: String): ujson.Obj =
    try
      ujson.read(if arguments.trim.isEmpty then "{}" else arguments) match
        case o: ujson.Obj => o
        case _ => ujson.Obj()
    catch case _: Exception => ujson.Obj()
