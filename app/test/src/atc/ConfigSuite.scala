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

  test("built-in default models are Anthropic + OpenAI"):
    assertEquals(Config.DefaultModels("claude").provider, "anthropic")
    assertEquals(Config.DefaultModels("gpt").provider, "openai-responses")

  // ── parsing a single file ───────────────────────────────────────

  test("a full config parses into the model"):
    val dir = Files.createTempDirectory("atc-cfg").nn
    val cfg = writeCfg(
      dir,
      "config.json",
      """
      {
        "model": "claude", "safeModel": "local",
        "models": { "claude": { "provider": "anthropic", "model": "claude-opus-5", "webSearch": true } },
        "files": [ { "path": ".", "access": "write" }, { "path": "secrets", "classified": true } ],
        "commands": ["git status"], "hosts": ["*.scala-lang.org"],
        "safeMode": false, "maxToolCalls": 10
      }
    """
    )
    val (c, present) = Config.load(dir, Some(cfg))
    assertEquals(present, List(cfg))
    assertEquals(c.model, Some("claude"))
    assertEquals(c.safeModel, Some("local"))
    assertEquals(c.models("claude").model, "claude-opus-5")
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
        "models": { "a": { "provider": "anthropic", "model": "m1" } } }
    """
    )
    val over = writeCfg(
      dir,
      "b.json",
      """
      { "model": "b", "safeMode": false, "files": [ {"path": "secrets", "classified": true} ],
        "commands": ["git status"], "models": { "b": { "provider": "openai", "model": "m2" } } }
    """
    )
    // load merges global(a) ← project ← explicit(b); we drive it directly via mergeJson too.
    val merged = Config.mergeJson(ujson.read(Files.readString(base)).obj, ujson.read(Files.readString(over)).obj)
    val c = upickle.default.read[Config](merged)
    assertEquals(c.model, Some("b")) // scalar overridden
    assertEquals(c.safeMode, false) // scalar overridden
    assertEquals(c.files.map(_.path), List(".", "secrets")) // list extended
    assertEquals(c.commands, List("ls", "git status")) // list extended
    assertEquals(c.models.keySet, Set("a", "b")) // models merged
    assertEquals(c.models("a").model, "m1")

  test("deny lists extend across layers, so a later layer cannot drop a deny pattern"):
    val a = ujson.read("""{ "commands": ["ls"], "denyCommands": ["rm *"], "denyHosts": ["*.internal"] }""").obj
    val b = ujson.read("""{ "commands": ["cat"], "denyCommands": ["curl *"] }""").obj
    val c = upickle.default.read[Config](Config.mergeJson(a, b))
    assertEquals(c.commands, List("ls", "cat"))
    assertEquals(c.denyCommands, List("rm *", "curl *"))
    assertEquals(c.denyHosts, List("*.internal"))

  test("merging the same model key keeps the later definition"):
    val a = ujson.read("""{ "models": { "m": { "provider": "anthropic", "model": "old" } } }""").obj
    val b = ujson.read("""{ "models": { "m": { "provider": "openai", "model": "new" } } }""").obj
    val c = upickle.default.read[Config](Config.mergeJson(a, b))
    assertEquals(c.models("m").provider, "openai")
    assertEquals(c.models("m").model, "new")

  // ── API-key resolution ──────────────────────────────────────────

  test("resolveApiKey handles literals, ${ENV} refs and apiKeyEnv"):
    val varName = "ATC_TEST_KEY_" + ProcessHandle.current().pid()
    assertEquals(Config.resolveApiKey(ModelConfig("openai", "m", apiKey = Some("literal-key"))), Some("literal-key"))
    // an unset ${VAR} resolves to None
    assertEquals(Config.resolveApiKey(ModelConfig("openai", "m", apiKey = Some(s"$${$varName}"))), None)
    assertEquals(Config.resolveApiKey(ModelConfig("openai", "m", apiKey = None)), None)
    // apiKeyEnv falls back to the environment (unset here → None)
    assertEquals(Config.resolveApiKey(ModelConfig("openai", "m", apiKeyEnv = Some(varName))), None)

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
    assert(c.models.nonEmpty, "template should define models")
    assert(c.files.nonEmpty, "template should define file rules")
