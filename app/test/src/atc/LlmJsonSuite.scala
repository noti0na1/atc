package atc

/** ujson ↔ plain Java values for the LLM SDKs (atc.llm.Json): tool schemas out,
  * tool-call inputs back. */
class LlmJsonSuite extends munit.FunSuite:

  private def javaMap(kvs: (String, AnyRef | Null)*): java.util.Map[String, AnyRef | Null] =
    val m = new java.util.LinkedHashMap[String, AnyRef | Null]()
    kvs.foreach((k, v) => m.put(k, v))
    m

  test("integers round-trip without a decimal point; doubles stay doubles"):
    val m = javaMap(
      "n" -> java.lang.Integer.valueOf(5),
      "l" -> java.lang.Long.valueOf(-7L),
      "d" -> java.lang.Double.valueOf(1.5),
      "zero" -> java.lang.Integer.valueOf(0),
    )
    assertEquals(ujson.write(atc.llm.Json.fromJava(m)), """{"n":5,"l":-7,"d":1.5,"zero":0}""")

  test("booleans, null, nested lists and maps survive"):
    val m = javaMap(
      "t" -> java.lang.Boolean.TRUE,
      "nil" -> null,
      "list" -> java.util.List.of(java.lang.Integer.valueOf(1), "two"),
      "obj" -> javaMap("a" -> java.lang.Double.valueOf(2.5)),
    )
    assertEquals(ujson.write(atc.llm.Json.fromJava(m)), """{"t":true,"nil":null,"list":[1,"two"],"obj":{"a":2.5}}""")

  test("an unknown type becomes its toString"):
    val m = javaMap("x" -> new Object() { override def toString = "opaque" })
    assertEquals(ujson.write(atc.llm.Json.fromJava(m)), """{"x":"opaque"}""")

  test("toJava and fromJava round-trip"):
    val v = ujson.read("""{"a":1,"b":[true,null],"c":"s"}""")
    assertEquals(atc.llm.Json.fromJava(atc.llm.Json.toJava(v)), v)

  test("parseObject is lenient: empty or malformed arguments become {}"):
    assertEquals(atc.llm.Json.parseObject("").value.size, 0)
    assertEquals(atc.llm.Json.parseObject("   ").value.size, 0)
    assertEquals(atc.llm.Json.parseObject("{not json").value.size, 0)
    assertEquals(atc.llm.Json.parseObject("[1,2]").value.size, 0)
    assertEquals(atc.llm.Json.parseObject("""{"code":"1 + 1"}""").value("code").str, "1 + 1")
