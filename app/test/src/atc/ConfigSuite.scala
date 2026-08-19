package atc

import atc.config.*
import atc.perms.{Access, Decision, PathPattern, Policy}

import java.nio.file.{Files, Path}

/** ATC's configuration: JSON parsing, layered merge, file-rule construction,
  * access-level parsing and API-key resolution. (ATC has no scopt CLI config
  * like TACIT; this covers the equivalent surface.) */
class ConfigSuite extends munit.FunSuite:
  /** Load with an absent global layer, so a test never depends on the machine's
    * own `~/.atc/config.json`. Layer combination itself is `LayerSuite`. */
  private def load(dir: Path, explicit: Option[Path]): Configuration =
    Config.load(dir, explicit, dir.resolve("no-such-global.json").nn)

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
    assertEquals(c.executionTimeoutMs, Some(180000L))
    assertEquals(c.maxToolCalls, 60)
    assertEquals(c.maxToolOutputChars, 40000)
    assert(c.files.isEmpty && c.commands.isEmpty && c.hosts.isEmpty)
    assert(c.denyCommands.isEmpty && c.denyHosts.isEmpty)
    assertEquals(c.respectGitignore, true)

  test("the starting global config protects the usual secrets and its own directory"):
    // Asserted as properties, not as a list: the template is meant to be edited.
    val start = upickle.default.read[Config](ujson.read(Config.globalTemplate))
    val classified = start.files.filter(_.classified.contains(true)).map(_.path)
    for expected <- List(".ssh", ".env", ".env.*", "*.pem", "id_rsa", ".aws") do
      assert(classified.contains(expected), s"missing $expected")
    val ownConfig = start.files.find(_.path == ".atc").get
    assertEquals(ownConfig.access, Some("none"))
    assert(ownConfig.locked, "the config that grants everything must not be reachable")
    assert(start.commands.isEmpty, "a fresh config grants no command")

  test("a provider may list no models: an endpoint written down, ready to be filled in"):
    val dir = Files.createTempDirectory("atc-cfg-empty-provider").nn
    val cfg = writeCfg(
      dir,
      "config.json",
      """{ "providers": { "openrouter": { "api": "openai", "url": "https://openrouter.ai/api/v1" } } }"""
    )
    val loaded = load(dir, Some(cfg))
    assertEquals(loaded.settings.providers("openrouter").models, Map.empty[String, ModelConfig])
    assert(ModelCatalog.from(loaded.settings).isEmpty, "it contributes no model until one is added")
    // and a later layer can add one without repeating the endpoint
    val withModel = upickle.default.read[Config](Config.mergeJson(
      ujson.read(Files.readString(cfg)).obj,
      ujson.read(
        """{ "providers": { "openrouter": { "models": { "sonnet": { "name": "anthropic/claude-sonnet-4.5" } } } } }"""
      ).obj
    ))
    val spec = ModelCatalog.from(withModel).find("sonnet")
    assertEquals(spec.modelId, "anthropic/claude-sonnet-4.5") // a model *name* may contain a slash
    assertEquals(spec.baseUrl, Some("https://openrouter.ai/api/v1"))

  test("the starting global config is a usable set of providers"):
    val start = upickle.default.read[Config](ujson.read(Config.globalTemplate))
    assert(start.providers.nonEmpty)
    assert(start.providers.forall((_, p) => p.api.exists(_.nonEmpty)), start.toString)
    assert(start.providers.exists((_, p) => p.models.nonEmpty), "at least one provider must be usable as it stands")
    val catalog = ModelCatalog.from(start)
    assert(catalog.models.forall(_.modelId.nonEmpty))
    assert(catalog.labels.distinct == catalog.labels, s"ambiguous aliases: ${catalog.labels}")
    // the roles it names have to resolve
    start.model.foreach(catalog.find)
    start.classifiedModel.foreach(catalog.find)

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
          "ollama": { "api": "openai", "url": "http://localhost:11434/v1",
            "models": { "local": {} } }
        },
        "files": [ { "path": ".", "access": "write" }, { "path": "secrets", "classified": true } ],
        "commands": ["git status"], "hosts": ["*.scala-lang.org"],
        "safeMode": false, "maxToolCalls": 10
      }
    """
    )
    val loaded = load(dir, Some(cfg))
    val c = loaded.settings
    assertEquals(loaded.sources, List(cfg))
    assertEquals(c.model, Some("claude"))
    assertEquals(c.classifiedModel, Some("local"))
    assertEquals(c.providers("anthropic").models("claude").name, Some("claude-opus-5"))
    // `name` defaults to the alias, and provider settings reach the resolved model
    val local = ModelCatalog.from(c).find("local")
    assertEquals(local.modelId, "local")
    assertEquals(local.api, "openai")
    assertEquals(local.baseUrl, Some("http://localhost:11434/v1"))
    assertEquals(local.apiKey, None) // keys live in keys.properties, never in a config
    // the file's own rules come after the built-in layer's
    assertEquals(loaded.rules.takeRight(2).map(_.rule.path), List(".", "secrets"))
    assertEquals(loaded.rules.last.rule.classified, Some(true))
    assertEquals(c.commands, List("git status"))
    assertEquals(c.safeMode, false)
    assertEquals(c.maxToolCalls, 10)

  test("a malformed config file is a clear error"):
    val dir = Files.createTempDirectory("atc-cfg-bad").nn
    val bad = writeCfg(dir, "config.json", "{ not json ]")
    val e = intercept[IllegalArgumentException](load(dir, Some(bad)))
    assert(e.getMessage.nn.contains("Cannot parse config"), e.getMessage)

  test("a non-object config file is rejected"):
    val dir = Files.createTempDirectory("atc-cfg-arr").nn
    val arr = writeCfg(dir, "config.json", "[1, 2, 3]")
    val e = intercept[IllegalArgumentException](load(dir, Some(arr)))
    assert(e.getMessage.nn.contains("must be a JSON object"), e.getMessage)

  test("invalid runtime limits are rejected while loading config"):
    val dir = Files.createTempDirectory("atc-cfg-limits").nn
    val bad = writeCfg(dir, "config.json", """{ "maxToolOutputChars": 0 }""")
    val e = intercept[IllegalArgumentException](load(dir, Some(bad)))
    assert(e.getMessage.nn.contains("maxToolOutputChars"), e.getMessage)

  test("with no config file at all, nothing is configured and nothing is permitted"):
    val dir = Files.createTempDirectory("atc-cfg-none").nn
    val loaded = load(dir, None)
    assert(loaded.sources.isEmpty)
    assertEquals(loaded.layers, Nil)
    assertEquals(loaded.rules, Nil)
    assertEquals(loaded.settings.commands, Nil)

  test("the same config path is not merged twice"):
    val dir = Files.createTempDirectory("atc-cfg-dedup").nn
    val projectDir = Files.createDirectories(dir.resolve(".atc")).nn
    val project = writeCfg(projectDir, "config.json", """{ "commands": ["git status"] }""")
    val loaded = load(dir, Some(project))
    assertEquals(loaded.sources, List(project))
    // read once, in its project role
    assertEquals(loaded.layers.map(_.origin), List(Origin.Project))
    assertEquals(loaded.settings.commands, List("git status"))

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
    assertEquals(p.url, Some("http://new")) // provider scalars still override
    assertEquals(p.models.keySet, Set("old", "fresh")) // model added, not replaced wholesale
    assertEquals(p.models("old").name, Some("v2")) // redefined alias replaced entirely
    assertEquals(p.models("old").webSearch, false) // ... including its dropped settings
    assertEquals(c.providers("anthropic").models.keySet, Set("claude")) // other providers untouched

  // ── API-key resolution ──────────────────────────────────────────

  test("a keys file is a properties file, and an empty value is not a binding"):
    val unset = "ATC_TEST_KEY_" + ProcessHandle.current().pid()
    val dir = Files.createTempDirectory("atc-keys").nn
    val file = dir.resolve(Config.KeysFile).nn
    Files.writeString(
      file,
      s"""|# a comment
          |! also a comment
          |
          |DEEPSEEK_API_KEY=sk-secret
          |COLON_KEY: colon value
          |SPACED_KEY = spaced
          |ESCAPED_KEY=with\\=sign
          |BLANK_KEY=
          |""".stripMargin
    )
    val keys = KeyBindings.load(List(file))
    assertEquals(keys.get("DEEPSEEK_API_KEY"), Some("sk-secret"))
    assertEquals(keys.get("COLON_KEY"), Some("colon value")) // `:` separates too, as in any .properties
    assertEquals(keys.get("SPACED_KEY"), Some("spaced"))
    assertEquals(keys.get("ESCAPED_KEY"), Some("with=sign"))
    assertEquals(keys.get("BLANK_KEY"), None) // an empty value means "not set"
    assertEquals(keys.get(unset), None)
    // the blank one is not listed
    assertEquals(keys.names, List("COLON_KEY", "DEEPSEEK_API_KEY", "ESCAPED_KEY", "SPACED_KEY"))

  test("a ${VAR} in a provider's key is resolved through the bindings, then the environment"):
    val dir = Files.createTempDirectory("atc-keys-provider").nn
    val file = dir.resolve(Config.KeysFile).nn
    Files.writeString(file, "DEEPSEEK_API_KEY=sk-secret\n")
    val keys = KeyBindings.load(List(file))
    def spec(p: ProviderConfig) =
      ModelCatalog.from(Config(providers = Map("deepseek" -> p)), keys).find("m")
    val models = Map("m" -> ModelConfig())
    assertEquals(
      spec(ProviderConfig(Some("openai"), key = Some("${DEEPSEEK_API_KEY}"), models = models)).apiKey,
      Some("sk-secret")
    )
    assertEquals(
      spec(ProviderConfig(Some("openai"), keyEnv = Some("DEEPSEEK_API_KEY"), models = models)).apiKey,
      Some("sk-secret")
    )
    assertEquals(spec(ProviderConfig(Some("openai"), key = Some("literal"), models = models)).apiKey, Some("literal"))
    assertEquals(spec(ProviderConfig(Some("openai"), models = models)).apiKey, None)
    // and it never reaches a message or a log through toString
    val s = spec(ProviderConfig(Some("openai"), key = Some("${DEEPSEEK_API_KEY}"), models = models))
    assert(!s.toString.contains("sk-secret"), s.toString)

  test("the starting keys file binds the variables the starting config names"):
    val named = "\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}".r
      .findAllMatchIn(Config.globalTemplate).map(_.group(1).nn).toSet
    val bound = Config.keysTemplate.linesIterator.filter(_.contains("=")).map(_.takeWhile(_ != '=').trim).toSet
    assertEquals(named -- bound, Set.empty[String], "every ${VAR} the config names should have a line to fill in")

  // ── App.fileRules ──────────────────────────────────────────────

  test("fileRules carries each rule's origin and parses its access level"):
    val dir = Files.createTempDirectory("atc-rules").nn
    val cfg = writeCfg(
      dir,
      "config.json",
      """
      { "files": [ { "path": ".", "access": "read" },
                   { "path": "build", "access": "write", "locked": true },
                   { "path": "secrets", "classified": true } ] }
    """
    )
    val rules = App.fileRules(load(dir, Some(cfg)), dir)
    val configured = rules
    assertEquals(configured.map(_.access), List(Some(Access.Read), Some(Access.Write), None))
    assert(configured.forall(_.grantsWithin.isEmpty), "an explicit -c file grants wherever it matches")
    assert(configured(1).locked)
    assert(configured(2).pattern.matches(dir.resolve("sub/secrets/k")))
    // the summary folds the classified-only rules into one line
    val summary = Policy(rules, Nil, Nil, _ => Decision.Deny).summary
    assertEquals(summary.linesIterator.count(_.contains("classified patterns")), 1, summary)
    assert(summary.contains("classified patterns: secrets"), summary)

  // ── template ────────────────────────────────────────────────────

  test("the --init-global template is valid JSON that parses into a Config"):
    val json = Config.globalTemplate
    val c = upickle.default.read[Config](ujson.read(json))
    assert(c.providers.nonEmpty, "template should define providers")
    val catalog = ModelCatalog.from(c)
    assert(catalog.models.size >= 4, catalog.labels.toString)
    // the roles the template names must resolve
    c.model.foreach(catalog.find)
    c.classifiedModel.foreach(catalog.find)
    assert(c.files.nonEmpty, "template should define file rules")
