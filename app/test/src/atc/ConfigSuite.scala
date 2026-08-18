package atc

import atc.config.*
import atc.perms.{Access, Decision, PathPattern, Policy}

import java.nio.file.{Files, Path}

/** ATC's configuration: JSON parsing, layered merge, file-rule construction,
  * access-level parsing and API-key resolution. (ATC has no scopt CLI config
  * like TACIT; this covers the equivalent surface.) */
class ConfigSuite extends munit.FunSuite:

  private def writeCfg(dir: Path, name: String, json: String): Path =
    val p = dir.resolve(name)
    Files.writeString(p, json)
    p

  // ── Access.parse ────────────────────────────────────────────────

  test("Access.parse accepts the documented spellings"):
    assertEquals(Access.parse("none"), Access.None)
    assertEquals(Access.parse("deny"), Access.None)
    assertEquals(Access.parse("read"), Access.Read)
    assertEquals(Access.parse("r"), Access.Read)
    assertEquals(Access.parse("ro"), Access.Read)
    assertEquals(Access.parse("write"), Access.Write)
    assertEquals(Access.parse("rw"), Access.Write)
    assertEquals(Access.parse("read-write"), Access.Write)
    assertEquals(Access.parse("  WRITE "), Access.Write)
  test("Access.parse rejects an unknown level"):
    val e = intercept[IllegalArgumentException](Access.parse("execute"))
    assert(e.getMessage.nn.contains("execute"), e.getMessage)

  // ── defaults ────────────────────────────────────────────────────

  test("an empty config has the documented defaults"):
    val c = Config()
    assertEquals(c.model, None)
    assertEquals(c.safeMode, true)
    assertEquals(c.defaultClassified, true)
    assertEquals(c.executionTimeoutMs, Some(180000L))
    assertEquals(c.maxToolCalls, 60)
    assertEquals(c.maxToolOutputChars, 40000)
    assert(c.files.isEmpty && c.commands.isEmpty && c.hosts.isEmpty)
    assert(c.denyCommands.isEmpty && c.denyHosts.isEmpty)
    assertEquals(c.respectGitignore, true)

  test("default classified patterns cover the common secret files"):
    val p = Config.DefaultClassifiedPatterns
    for expected <- List(".ssh", ".env", ".env.*", "*.pem", "id_rsa", ".aws") do
      assert(p.contains(expected), s"missing $expected")

  test("built-in default providers are Anthropic + OpenAI"):
    assertEquals(Config.DefaultProviders("anthropic").api, "anthropic")
    assertEquals(Config.DefaultProviders("openai").api, "openai-responses")
    val catalog = ModelCatalog.from(Config())
    assertEquals(catalog.labels, List("claude", "gpt"))
    assertEquals(catalog.find("claude").modelId, "claude-opus-5")
    assertEquals(catalog.default.ref, "anthropic/claude")

  // ── parsing a single file ───────────────────────────────────────

  test("a full config parses into the model"):
    val dir = Files.createTempDirectory("atc-cfg").nn
    val cfg = writeCfg(
      dir,
      "config.json",
      """
      {
        "model": "claude", "classifiedModel": "local",
        "providers": {
          "anthropic": { "api": "anthropic",
            "models": { "claude": { "name": "claude-opus-5", "webSearch": true } } },
          "ollama": { "api": "openai", "url": "http://localhost:11434/v1", "key": "ollama",
            "models": { "local": {} } }
        },
        "files": [ { "path": ".", "access": "write" }, { "path": "secrets", "classified": true } ],
        "commands": ["git status"], "hosts": ["*.scala-lang.org"],
        "safeMode": false, "maxToolCalls": 10
      }
    """
    )
    val (c, present) = Config.load(dir, Some(cfg))
    assertEquals(present, List(cfg))
    assertEquals(c.model, Some("claude"))
    assertEquals(c.classifiedModel, Some("local"))
    assertEquals(c.providers("anthropic").models("claude").name, Some("claude-opus-5"))
    // `name` defaults to the alias, and provider settings reach the resolved model
    val local = ModelCatalog.from(c).find("local")
    assertEquals(local.modelId, "local")
    assertEquals(local.api, "openai")
    assertEquals(local.baseUrl, Some("http://localhost:11434/v1"))
    assertEquals(local.apiKey, Some("ollama"))
    assertEquals(c.files.map(_.path), List(".", "secrets"))
    assertEquals(c.files(1).classified, Some(true))
    assertEquals(c.commands, List("git status"))
    assertEquals(c.safeMode, false)
    assertEquals(c.maxToolCalls, 10)

  test("a malformed config file is a clear error"):
    val dir = Files.createTempDirectory("atc-cfg-bad").nn
    val bad = writeCfg(dir, "config.json", "{ not json ]")
    val e = intercept[IllegalArgumentException](Config.load(dir, Some(bad)))
    assert(e.getMessage.nn.contains("Cannot parse config"), e.getMessage)

  test("a non-object config file is rejected"):
    val dir = Files.createTempDirectory("atc-cfg-arr").nn
    val arr = writeCfg(dir, "config.json", "[1, 2, 3]")
    val e = intercept[IllegalArgumentException](Config.load(dir, Some(arr)))
    assert(e.getMessage.nn.contains("must be a JSON object"), e.getMessage)

  test("invalid runtime limits are rejected while loading config"):
    val dir = Files.createTempDirectory("atc-cfg-limits").nn
    val bad = writeCfg(dir, "config.json", """{ "maxToolOutputChars": 0 }""")
    val e = intercept[IllegalArgumentException](Config.load(dir, Some(bad)))
    assert(e.getMessage.nn.contains("maxToolOutputChars"), e.getMessage)

  test("absent config files are simply skipped"):
    val dir = Files.createTempDirectory("atc-cfg-none").nn
    val (c, present) = Config.load(dir, None)
    assert(present.isEmpty)
    assertEquals(c, Config())

  test("the same config path is not merged twice"):
    val dir = Files.createTempDirectory("atc-cfg-dedup").nn
    val projectDir = Files.createDirectories(dir.resolve(".atc")).nn
    val project = writeCfg(projectDir, "config.json", """{ "commands": ["git status"] }""")
    val (config, present) = Config.load(dir, Some(project))
    assertEquals(present, List(project))
    assertEquals(config.commands, List("git status"))

  // ── layered merge (global ← project ← explicit) ─────────────────

  test("later layers override scalars, extend list keys and merge models"):
    val dir = Files.createTempDirectory("atc-cfg-merge").nn
    val base = writeCfg(
      dir,
      "a.json",
      """
      { "model": "a", "safeMode": true, "files": [ {"path": "."} ], "commands": ["ls"],
        "providers": { "anthropic": { "api": "anthropic", "models": { "a": { "name": "m1" } } } } }
    """
    )
    val over = writeCfg(
      dir,
      "b.json",
      """
      { "model": "b", "safeMode": false, "files": [ {"path": "secrets", "classified": true} ],
        "commands": ["git status"],
        "providers": { "openai": { "api": "openai-responses", "models": { "b": { "name": "m2" } } } } }
    """
    )
    // load merges global(a) ← project ← explicit(b); we drive it directly via mergeJson too.
    val merged = Config.mergeJson(ujson.read(Files.readString(base)).obj, ujson.read(Files.readString(over)).obj)
    val c = upickle.default.read[Config](merged)
    assertEquals(c.model, Some("b")) // scalar overridden
    assertEquals(c.safeMode, false) // scalar overridden
    assertEquals(c.files.map(_.path), List(".", "secrets")) // list extended
    assertEquals(c.commands, List("ls", "git status")) // list extended
    assertEquals(c.providers.keySet, Set("anthropic", "openai")) // providers merged
    assertEquals(ModelCatalog.from(c).labels, List("a", "b"))

  test("deny lists extend across layers, so a later layer cannot drop a deny pattern"):
    val a = ujson.read("""{ "commands": ["ls"], "denyCommands": ["rm *"], "denyHosts": ["*.internal"] }""").obj
    val b = ujson.read("""{ "commands": ["cat"], "denyCommands": ["curl *"] }""").obj
    val c = upickle.default.read[Config](Config.mergeJson(a, b))
    assertEquals(c.commands, List("ls", "cat"))
    assertEquals(c.denyCommands, List("rm *", "curl *"))
    assertEquals(c.denyHosts, List("*.internal"))

  test("a provider is merged field by field: models are added, a redefined alias replaced"):
    val a = ujson.read("""{ "providers": {
        "openai": { "api": "openai", "url": "http://old", "key": "k",
          "models": { "old": { "name": "v1", "webSearch": true } } },
        "anthropic": { "api": "anthropic", "models": { "claude": {} } } } }""").obj
    val b = ujson.read("""{ "providers": {
        "openai": { "api": "openai", "url": "http://new",
          "models": { "old": { "name": "v2" }, "fresh": { "name": "v3" } } } } }""").obj
    val c = upickle.default.read[Config](Config.mergeJson(a, b))
    val p = c.providers("openai")
    assertEquals(p.url, Some("http://new")) // provider scalar overridden
    assertEquals(p.key, Some("k")) // untouched field kept from the earlier layer
    assertEquals(p.models.keySet, Set("old", "fresh")) // model added, not replaced wholesale
    assertEquals(p.models("old").name, Some("v2")) // redefined alias replaced entirely
    assertEquals(p.models("old").webSearch, false) // ... including its dropped settings
    assertEquals(c.providers("anthropic").models.keySet, Set("claude")) // other providers untouched

  // ── API-key resolution ──────────────────────────────────────────

  test("resolveApiKey handles literals, ${ENV} refs and keyEnv"):
    val varName = "ATC_TEST_KEY_" + ProcessHandle.current().pid()
    def provider(key: Option[String] = None, keyEnv: Option[String] = None) =
      ProviderConfig("openai", key = key, keyEnv = keyEnv)
    assertEquals(Config.resolveApiKey(provider(key = Some("literal-key"))), Some("literal-key"))
    // an unset ${VAR} resolves to None
    assertEquals(Config.resolveApiKey(provider(key = Some(s"$${$varName}"))), None)
    assertEquals(Config.resolveApiKey(provider()), None)
    // keyEnv falls back to the environment (unset here → None)
    assertEquals(Config.resolveApiKey(provider(keyEnv = Some(varName))), None)
    // a resolved key never reaches a message or a log through toString
    val spec = ModelCatalog.from(Config(providers =
      Map("p" -> ProviderConfig("openai", key = Some("sk-secret"), models = Map("m" -> ModelConfig())))
    )).find("m")
    assertEquals(spec.apiKey, Some("sk-secret"))
    assert(!spec.toString.contains("sk-secret"), spec.toString)

  // ── App.fileRules ──────────────────────────────────────────────

  test("fileRules defaults to a writable cwd plus the built-in classified patterns"):
    val cwd = Files.createTempDirectory("atc-rules").nn
    val rules = App.fileRules(Config(), cwd)
    // first rule: writable cwd
    assertEquals(rules.head.access, Some(Access.Write))
    // the classified defaults are appended, all classified with no access constraint
    val classifiedPatterns = rules.tail
    assertEquals(classifiedPatterns.size, Config.DefaultClassifiedPatterns.size)
    assert(classifiedPatterns.forall(_.classified.contains(true)))
    assert(classifiedPatterns.forall(_.builtin) && !rules.head.builtin)
    // a `.env` file anywhere under cwd is classified
    assert(rules.exists(_.pattern.matches(cwd.resolve("sub/.env"))))
    // the policy summary folds the built-in patterns into one line
    val summary = Policy(rules, Nil, Nil, _ => Decision.Deny).summary
    assert(summary.linesIterator.count(_.contains("classified")) == 1, summary)
    assert(summary.contains("built-in classified patterns: .ssh, .gnupg"), summary)

  test("explicit file rules replace the default cwd rule and parse access levels"):
    val cwd = Files.createTempDirectory("atc-rules2").nn
    val cfg = Config(files =
      List(
        FileRuleConfig(".", access = Some("read")),
        FileRuleConfig("build", access = Some("write"), locked = true),
        FileRuleConfig("secrets", classified = Some(true)),
      )
    )
    val rules = App.fileRules(cfg, cwd)
    assertEquals(rules.head.access, Some(Access.Read))
    val build = rules.find(_.pattern.toString == "build").get
    assertEquals(build.access, Some(Access.Write))
    assert(build.locked)

  test("disabling defaultClassified drops the built-in patterns"):
    val cwd = Files.createTempDirectory("atc-rules3").nn
    val rules = App.fileRules(Config(defaultClassified = false), cwd)
    assertEquals(rules.size, 1)
    assertEquals(rules.head.access, Some(Access.Write))

  // ── template ────────────────────────────────────────────────────

  test("the --init template is valid JSON that parses into a Config"):
    val json = Config.template
    val c = upickle.default.read[Config](ujson.read(json))
    assert(c.providers.nonEmpty, "template should define providers")
    val catalog = ModelCatalog.from(c)
    assert(catalog.models.size >= 4, catalog.labels.toString)
    // the roles the template names must resolve
    c.model.foreach(catalog.find)
    c.classifiedModel.foreach(catalog.find)
    assert(c.files.nonEmpty, "template should define file rules")
