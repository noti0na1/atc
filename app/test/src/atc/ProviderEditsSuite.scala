package atc

import atc.config.*
import atc.config.ProviderEdits.{Choice, Edit}

/** What `/providers` writes: member edits that keep a hand-formatted config's
  * text, the shortlist of a provider's models, and which layer owns an edit. */
class ProviderEditsSuite extends munit.FunSuite:
  private val text =
    """{
      |  "model": "a",
      |  "providers": {
      |    "p": {
      |      "api": "openai",
      |      "models": {
      |        "a":    { "name": "model-a",  "contextWindow": "1m" },
      |        "b":    { "name": "model-b" }
      |      }
      |    }
      |  }
      |}""".stripMargin

  private def apply(t: String, edits: Edit*) =
    edits.foldLeft(t)((acc, e) => ObjectText.withMember(acc, e.path, e.value))

  test("a nested member is set, added and removed without touching the rest of the text"):
    val off = ObjectText.withMember(text, List("providers", "p", "models", "b", "enabled"), Some(ujson.False))
    assertEquals(off, text.replace("""{ "name": "model-b" }""", """{ "name": "model-b", "enabled": false }"""))
    assertEquals(ObjectText.withMember(off, List("providers", "p", "models", "b", "enabled"), None), text)
    val added = ObjectText.withMember(text, List("providers", "p", "enabled"), Some(ujson.False))
    assert(added.contains("      }," + "\n      \"enabled\": false\n    }"), added)
    val created = ObjectText.withMember(text, List("providers", "q", "models", "x"), Some(ujson.Obj("name" -> "y")))
    assert(created.contains("""    "q": { "models": { "x": { "name": "y" } } }"""), created)
    assertEquals(ujson.read(created)("providers")("q")("models")("x")("name").str, "y")

  test("removing a member drops the comma next to it, wherever it stands"):
    val models = List("providers", "p", "models")
    val noA = ObjectText.withMember(text, models :+ "a", None)
    assertEquals(ujson.read(noA)("providers")("p")("models").obj.keySet, Set("b"))
    val noB = ObjectText.withMember(text, models :+ "b", None)
    assertEquals(ujson.read(noB)("providers")("p")("models").obj.keySet, Set("a"))
    val none = ObjectText.withMember(noA, models :+ "b", None)
    assertEquals(ujson.read(none)("providers")("p")("models").obj.size, 0)
    assert(
      noB.contains(""""a":    { "name": "model-a",  "contextWindow": "1m" }"""),
      "the kept entry keeps its spacing"
    )
    assertEquals(
      ObjectText.withMember(text, List("providers", "absent", "x"), None),
      text,
      "removing nothing changes nothing"
    )

  private val provider = upickle.default.read[Config](text).providers("p")
  private def listed(ids: String*) = ids.toList.map(id =>
    ModelSpec(
      "p",
      id,
      "openai",
      id,
      None,
      None,
      ModelConfig(contextWindow = Some(Tokens(200000)), efforts = Some(List("low")))
    )
  )

  test("a shortlist switches configured entries and writes listed models as entries"):
    val choices = ProviderEdits.choices(provider, listed("model-a", "vendor/big", "small"))
    assertEquals(choices.size, 4, "the listed model-a is the configured entry a")
    val chosen = choices.filter {
      case Choice.Configured(alias, _) => alias == "a"
      case Choice.Listed(spec) => spec.modelId == "vendor/big"
    }.toSet
    val edits = ProviderEdits.shortlist("p", provider, choices, chosen)
    assertEquals(
      edits,
      List(
        Edit(List("providers", "p", "models", "b", "enabled"), Some(ujson.False)),
        Edit(
          List("providers", "p", "models", "big"),
          Some(ujson.Obj("name" -> "vendor/big", "contextWindow" -> "200k", "efforts" -> ujson.Arr("low")))
        ),
      )
    )
    val written = upickle.default.read[Config](apply(text, edits*))
    assertEquals(ModelCatalog.from(written).configured.map(_.ref), List("p/a", "p/big"))

  test("a listed id gets an alias without a slash that no entry uses"):
    assertEquals(ProviderEdits.aliasFor("vendor/big", Set.empty), "big")
    assertEquals(ProviderEdits.aliasFor("vendor/big", Set("big")), "vendor-big")
    assertEquals(ProviderEdits.aliasFor("vendor/big", Set("big", "vendor-big")), "vendor-big-2")
    assertEquals(ProviderEdits.aliasFor("plain", Set.empty), "plain")

  test("an edit goes to the last layer holding the longest part of its path"):
    def layer(origin: Origin, json: String) =
      val obj = ujson.read(json).obj
      ConfigLayer(origin, Some(java.nio.file.Path.of(origin.label)), obj, upickle.default.read[Config](obj))
    val global = layer(Origin.Global, """{ "providers": { "p": { "api": "openai", "models": { "a": {} } } } }""")
    val project = layer(Origin.Project, """{ "providers": { "p": { "models": { "b": {} } } } }""")
    val layers = List(global, project)
    def owner(path: String*) = ProviderEdits.owner(layers, path.toList).map(_.origin)
    assertEquals(owner("providers", "p", "models", "a", "enabled"), Some(Origin.Global), "the file defining entry a")
    assertEquals(owner("providers", "p", "models", "b", "enabled"), Some(Origin.Project))
    assertEquals(owner("providers", "p", "enabled"), Some(Origin.Project), "the last file naming the provider")
    assertEquals(owner("providers", "q", "enabled"), None, "a provider no file defines")

  test("a provider or model turned off is not offered, and an all-off provider lists nothing"):
    val c = upickle.default.read[Config](
      """{ "providers": {
        "on":  { "api": "openai", "models": { "a": {}, "b": { "enabled": false } } },
        "off": { "api": "openai", "enabled": false, "models": { "c": {} } },
        "quiet": { "api": "openai", "url": "http://localhost:1", "models": { "d": { "enabled": false } } },
        "lists": { "api": "openai", "url": "http://localhost:2", "enabled": false } } }"""
    )
    val catalog = ModelCatalog.from(c)
    assertEquals(catalog.configured.map(_.ref), List("on/a"))
    assertEquals(catalog.discoverable, Nil)

  test("token counts are written the way a config would"):
    assertEquals(Tokens.format(Tokens(1000000)), "1m")
    assertEquals(Tokens.format(Tokens(200000)), "200k")
    assertEquals(Tokens.format(Tokens(131072)), "131072")
