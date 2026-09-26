package atc

import atc.FirstRun.Outcome
import atc.config.*
import atc.platform.Platform

import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import scala.collection.mutable

/** The first-run setup: which questions it asks and what it returns, with a
  * scripted terminal and a model list that needs no network. */
class FirstRunSuite extends munit.FunSuite:
  /** Answers menus and key prompts in order and records what was shown. */
  private final class Script(answers: Option[String]*) extends FirstRun.Ui:
    private val pending = mutable.Queue(answers*)
    val shown = List.newBuilder[String]
    def choose(title: String, options: List[String]): Option[String] =
      shown += s"menu: $title"
      val answer = pending.dequeue()
      answer.foreach(a => assert(options.contains(a), s"'$a' is not offered: $options"))
      answer
    def askSecret(question: String): Option[String] =
      shown += s"secret: $question"
      pending.dequeue()
    def info(text: String): Unit = shown += s"info: $text"
    def error(text: String): Unit = shown += s"error: $text"
    def done(): Unit = assert(pending.isEmpty, s"unused answers: $pending")

  private val noSignIn: FirstRun.Ui => Boolean = _ => fail("only ChatGPT signs in")

  private val presets = ProviderPreset.all
  private def preset(name: String) = presets.find(_.name == name).get

  /** A provider whose list needs the key `good`. */
  private def listing(asked: mutable.ListBuffer[Option[String]])(spec: ModelSpec): List[ModelSpec] =
    asked += spec.apiKey
    if spec.apiKey.contains("good") || spec.provider == "ollama" then
      List("model-a", "model-b").map(id => spec.copy(alias = id, modelId = id))
    else throw RuntimeException("401: invalid x-api-key")

  test("a typed key is checked by listing the models, and a wrong one is asked again"):
    val asked = mutable.ListBuffer[Option[String]]()
    val ui = Script(Some("Anthropic"), Some("wrong"), Some("good"), Some("model-b"))
    val outcome = FirstRun.run(ui, presets, KeyBindings.empty, listing(asked), noSignIn)
    ui.done()
    outcome match
      case Outcome.Ready(provider, key, endpoint, models, model) =>
        assertEquals(provider.name, "anthropic")
        assertEquals(key, Some("good"))
        assertEquals(endpoint.provider, "anthropic")
        assertEquals(models.map(_.modelId), List("model-a", "model-b"))
        assertEquals(model.ref, "anthropic/model-b")
      case other => fail(s"unexpected $other")
    assertEquals(asked.toList, List(Some("wrong"), Some("good")))
    val shown = ui.shown.result()
    assert(shown.contains("info: Create a key at https://console.anthropic.com/settings/keys"), shown)
    assert(shown.exists(_.startsWith("error: Could not list the models of Anthropic: 401")), shown)

  test("a key that is already bound is used without asking"):
    val asked = mutable.ListBuffer[Option[String]]()
    val keys = KeyBindings(List(Path.of("keys.properties") -> Map("OPENAI_API_KEY" -> "good")))
    val ui = Script(Some("OpenAI"), Some("model-a"))
    val outcome = FirstRun.run(ui, presets, keys, listing(asked), noSignIn)
    ui.done()
    assert(outcome.isInstanceOf[Outcome.Ready], outcome.toString)
    assertEquals(outcome.asInstanceOf[Outcome.Ready].key, None, "nothing new to save")
    assert(ui.shown.result().contains("info: Using OPENAI_API_KEY, which is already set."))

  test("cancelling a key or a model goes back to the providers; the other choices end the run"):
    val asked = mutable.ListBuffer[Option[String]]()
    val back = Script(Some("DeepSeek"), None, Some("OpenRouter"), Some("good"), None, Some(FirstRun.NotNowLabel))
    assertEquals(FirstRun.run(back, presets, KeyBindings.empty, listing(asked), noSignIn), Outcome.NotNow)
    back.done()
    val own = Script(Some(FirstRun.ConfigureYourselfLabel))
    assertEquals(FirstRun.run(own, presets, KeyBindings.empty, listing(asked), noSignIn), Outcome.ConfigureYourself)
    val escaped = Script(None)
    assertEquals(FirstRun.run(escaped, presets, KeyBindings.empty, listing(asked), noSignIn), Outcome.NotNow)

  test("a keyless provider that cannot be reached goes back to the providers without asking for a key"):
    val ui = Script(Some(preset("ollama").label), Some(FirstRun.NotNowLabel))
    assertEquals(
      FirstRun.run(ui, presets, KeyBindings.empty, _ => throw RuntimeException("Connection refused"), noSignIn),
      Outcome.NotNow
    )
    ui.done()
    assert(!ui.shown.result().exists(_.startsWith("secret:")))

  test("ChatGPT is signed in to instead of asking for a key, and a failed sign-in goes back to the providers"):
    val label = preset("chatgpt").label
    val signIns = mutable.ListBuffer[Boolean]()
    def signIn(results: Boolean*): FirstRun.Ui => Boolean =
      val pending = mutable.Queue(results*)
      _ => { signIns += pending.head; pending.dequeue() }
    val models = (spec: ModelSpec) => List("gpt-a", "gpt-b").map(id => spec.copy(alias = id, modelId = id))
    val ui = Script(Some(label), Some(label), Some("gpt-b"))
    FirstRun.run(ui, presets, KeyBindings.empty, models, signIn(false, true)) match
      case Outcome.Ready(provider, key, endpoint, _, model) =>
        assertEquals(provider.name, "chatgpt")
        assertEquals(key, None)
        assertEquals(endpoint.baseUrl, Some("https://chatgpt.com/backend-api/codex"))
        assertEquals(model.ref, "chatgpt/gpt-b")
      case other => fail(s"unexpected $other")
    ui.done()
    assertEquals(signIns.toList, List(false, true))
    assert(!ui.shown.result().exists(_.startsWith("secret:")), ui.shown.result())

  // ── what setup writes ───────────────────────────────────────────

  test("the global config written for one provider keeps the protections and names only that provider"):
    val path = Files.createTempDirectory("atc-first").nn.resolve("config.json").nn
    assertEquals(Config.writeGlobalConfig(path, List(preset("deepseek"))), Some(path))
    val c = upickle.default.read[Config](Files.readString(path).nn)
    assertEquals(c.providers.keySet, Set("deepseek"))
    assertEquals(c.providers("deepseek").url, Some("https://api.deepseek.com"))
    assertEquals(c.providers("deepseek").key, Some("${DEEPSEEK_API_KEY}"))
    assertEquals(c.providers("deepseek").models, Map.empty[String, ModelConfig])
    val all = upickle.default.read[Config](Config.globalTemplate)
    assertEquals(c.copy(providers = all.providers), all, "everything else is the starting config")
    assertEquals(all.providers.keySet, presets.map(_.name).toSet)
    assertEquals(Config.writeGlobalConfig(path, presets), None, "an existing config is left alone")

  test("a preset's headers reach the written config and the listing request"):
    val path = Files.createTempDirectory("atc-first").nn.resolve("config.json").nn
    Config.writeGlobalConfig(path, List(preset("opencode-go")))
    val written = upickle.default.read[Config](Files.readString(path).nn).providers("opencode-go")
    assertEquals(written.headers, Map("x-opencode-session" -> ProviderConfig.SessionRef))
    val keys = KeyBindings(List(Path.of("keys.properties") -> Map("OPENCODE_GO_API_KEY" -> "good")))
    val seen = mutable.ListBuffer[ModelSpec]()
    val ui = Script(Some("OpenCode Go"), Some("model-a"))
    FirstRun.run(
      ui,
      presets,
      keys,
      spec => { seen += spec; List(spec.copy(alias = "model-a", modelId = "model-a")) },
      noSignIn
    )
    assertEquals(seen.map(_.headers).toList, List(Map("x-opencode-session" -> ProviderConfig.SessionRef)))

  test("binding a key keeps the other lines of the keys file and replaces the old binding"):
    val path = Files.createTempDirectory("atc-first").nn.resolve("keys.properties").nn
    KeyBindings.bind(path, "A_KEY", "one")
    if !Platform.isWindows then
      assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(path)), "rw-------")
    Files.writeString(path, Files.readString(path).nn + "# a note\nA_KEY_OLD=keep\nB_KEY = two\n")
    KeyBindings.bind(path, "A_KEY", """new\value""")
    KeyBindings.bind(path, "B_KEY", "three")
    val keys = KeyBindings.load(List(path))
    assertEquals(keys.get("A_KEY"), Some("""new\value"""))
    assertEquals(keys.get("A_KEY_OLD"), Some("keep"))
    assertEquals(keys.get("B_KEY"), Some("three"))
    assert(Files.readString(path).nn.contains("# a note"))
    intercept[IllegalArgumentException](KeyBindings.bind(path, "A_KEY", "two\nlines"))
    // a key file others could read is made private by the next binding
    if !Platform.isWindows then
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
      KeyBindings.bind(path, "C_KEY", "four")
      assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(path)), "rw-------")
      assertEquals(KeyBindings.load(List(path)).get("B_KEY"), Some("three"))
