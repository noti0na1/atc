package atc

import atc.lib.Json

/** The agent-facing JSON type: parsing, navigation, errors, rendering. */
class JsonSuite extends munit.FunSuite:

  test("parse and render round-trip, whole numbers without a decimal point"):
    val text = """{"a":1,"b":[true,null,"s"],"c":{"d":1.5,"e":-2e3},"f":"q\"\\\n\u00e9"}"""
    val j = Json.parse(text)
    assertEquals(j("a").int, 1)
    assertEquals(j("b")(0).bool, true)
    assert(j("b")(1).isNull)
    assertEquals(j("b")(2).str, "s")
    assertEquals(j("c")("d").num, 1.5)
    assertEquals(j("c")("e").num, -2000.0)
    assertEquals(j("f").str, "q\"\\\n\u00e9")
    assertEquals(
      j.render,
      """{"a":1,"b":[true,null,"s"],"c":{"d":1.5,"e":-2000},"f":"q\"\\\né"}"""
    ) // only controls are escaped
    assertEquals(Json.parse(j.render), j)
    assertEquals(j.toString, j.render)

  test("missing keys and indices give Null so chains are safe; leaf readers throw on the wrong kind"):
    val j = Json.parse("""{"a": {"b": [10, 20]}}""")
    assert(j("x")("y")(3).isNull)
    assertEquals(j("a")("b")(1).int, 20)
    assert(j("a")("b")(2).isNull)
    val e = intercept[IllegalArgumentException](j("a").str)
    assert(e.getMessage.nn.contains("expected a string, got an object"), e.getMessage)
    intercept[IllegalArgumentException](j("a")("b")(0).bool)
    intercept[IllegalArgumentException](Json.Num(1.5).int)
    assertEquals(Json.Num(3.0).int, 3)
    assertEquals(j("a").keys, List("b"))
    assertEquals(j.keys, List("a"))
    assertEquals(Json.Str("x").keys, Nil)

  test("updated/removed edit objects in place, preserving field order"):
    val j = Json.parse("""{"name":"x","version":"1.0.0","deps":{}}""")
    assertEquals(j.updated("version", Json.Str("2.0.0")).render, """{"name":"x","version":"2.0.0","deps":{}}""")
    assertEquals(j.updated("new", Json.Bool(true)).keys, List("name", "version", "deps", "new"))
    assertEquals(j.removed("deps").render, """{"name":"x","version":"1.0.0"}""")
    intercept[IllegalArgumentException](Json.Arr(Nil).updated("k", Json.Null))
    assertEquals(Json.obj("a" -> Json.arr(Json.Num(1), Json.Null)).render, """{"a":[1,null]}""")

  test("pretty prints with two-space indentation"):
    val j = Json.parse("""{"a":[1,{"b":"c"}],"d":{},"e":[]}""")
    assertEquals(
      j.pretty,
      """{
        |  "a": [
        |    1,
        |    {
        |      "b": "c"
        |    }
        |  ],
        |  "d": {},
        |  "e": []
        |}""".stripMargin,
    )

  test("bad input throws with the offset; a trailing comma is tolerated"):
    val e = intercept[IllegalArgumentException](Json.parse("""{"a": 1,, "b": 2}"""))
    assert(e.getMessage.nn.contains("offset 8"), e.getMessage)
    intercept[IllegalArgumentException](Json.parse("""{"a": 1} trailing"""))
    intercept[IllegalArgumentException](Json.parse("""[1, 2"""))
    intercept[IllegalArgumentException](Json.parse("""{"a" 1}"""))
    intercept[IllegalArgumentException](Json.parse(""))
    assertEquals(Json.parse("""[1, 2, ]""").arr.length, 2)
    assertEquals(Json.parse("""{"a": 1, }""").keys, List("a"))

  test("control characters and non-finite numbers render as valid JSON"):
    assertEquals(Json.Str("tab\t bell\u0007").render, "\"tab\\t bell\\u0007\"")
    assertEquals(Json.Num(Double.NaN).render, "null")
    assertEquals(Json.Num(1e20).render, "1.0E20")
    assertEquals(Json.Num(-0.5).render, "-0.5")
