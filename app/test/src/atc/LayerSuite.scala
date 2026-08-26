package atc

import atc.config.*
import atc.perms.*
import atc.platform.PlatformPath

import java.nio.file.{Files, Path}

/** How the configuration layers combine: `~/.atc/config.json` ←
  * `<project>/.atc/config.json` ← `-c <file>`. The guarantees under test are
  * that the global config is the only default there is, and that the project
  * layer can open only its own project (its files, and the commands and hosts
  * its work needs) and otherwise only make the policy stricter. */
class LayerSuite extends munit.FunSuite:

  /** A layer that grants the working directory, and nothing else. */
  private val GrantCwd = """{ "files": [ { "path": ".", "access": "write" } ] }"""

  /** A temp world: a "home" holding the global config, a project directory
    * holding the project one, and a working directory `run` levels inside it. */
  private class World(
    global: String = GrantCwd,
    project: String = "",
    explicit: String = "",
    runIn: String = ""
  ):
    val home: Path = Files.createTempDirectory("atc-layer-home").nn.toRealPath().nn
    val cwd: Path = Files.createTempDirectory("atc-layer-cwd").nn.toRealPath().nn
    /** `$HOME` and `$CWD` in a layer's text become this world's directories. */
    private def jsonStringContent(value: String): String =
      val quoted = ujson.write(value)
      quoted.substring(1, quoted.length - 1)
    private def write(p: Path, text: String): Option[Path] =
      if text.isEmpty then None
      else
        Option(p.getParent).foreach(Files.createDirectories(_))
        Files.writeString(
          p,
          text.replace("$HOME", jsonStringContent(home.toString)).replace("$CWD", jsonStringContent(cwd.toString)),
        )
        Some(p)
    val globalPath: Path = home.resolve(".atc").resolve("config.json").nn
    write(globalPath, global)
    write(Config.projectPath(cwd), project)
    val explicitPath: Option[Path] = write(cwd.resolve("explicit.json").nn, explicit)

    /** Where atc runs: the project root, or a directory inside it. */
    val runDir: Path =
      if runIn.isEmpty then cwd else Files.createDirectories(cwd.resolve(runIn)).nn.toRealPath().nn
    val configuration: Configuration = Config.load(runDir, explicitPath, globalPath)
    def settings: Config = configuration.settings
    val policy: Policy = Policy(
      App.fileRules(configuration, cwd),
      settings.commands,
      settings.hosts,
      _ => Decision.Deny,
      settings.denyCommands,
      settings.denyHosts,
    )
    def access(rel: String): Access = policy.effective(ScopeId.Base, PlatformPath.canonical(cwd.resolve(rel).nn)).access
    def perm(rel: String): Perm = policy.effective(ScopeId.Base, PlatformPath.canonical(cwd.resolve(rel).nn))
    def outside(abs: Path): Access = policy.effective(ScopeId.Base, PlatformPath.canonical(abs)).access

  // ── the working directory ───────────────────────────────────────

  test("with no configuration at all, nothing is permitted: no path is granted by the program"):
    val w = World(global = "")
    assertEquals(w.configuration.layers, Nil)
    assertEquals(w.access("src/A.scala"), Access.None)
    assertEquals(w.outside(Path.of("/etc/passwd")), Access.None)
    assert(!w.policy.commandAllowed(ScopeId.Base, "ls"))
    assert(ModelCatalog.from(w.settings).isEmpty)

  test("a project config grants its own tree"):
    val w = World(global = "", project = """{ "files": [ { "path": ".", "access": "write" } ] }""")
    assertEquals(w.access("src/A.scala"), Access.Write)
    assertEquals(w.access("."), Access.Write)
    // ... and nothing outside it, however the rule is written
    assertEquals(w.outside(Path.of("/etc/passwd")), Access.None)
    val reaching = World(
      global = "",
      project = """{ "files": [ { "path": ".", "access": "write" }, { "path": "$HOME", "access": "write" } ] }"""
    )
    assertEquals(reaching.outside(reaching.home.resolve("secret.txt").nn), Access.None)
    assertEquals(reaching.access("src/A.scala"), Access.Write)

  test("a project grant cannot exceed a limit a granting layer set"):
    val w = World(
      global = """{ "files": [ { "path": ".", "access": "read" } ] }""",
      project = """{ "files": [ { "path": ".", "access": "write" } ] }"""
    )
    assertEquals(w.access("src/A.scala"), Access.Read)
    // and it still cannot open what the global config shut
    val shut = World(
      global = """{ "files": [ { "path": ".", "access": "read" }, { "path": "./vendor", "access": "none" } ] }""",
      project = """{ "files": [ { "path": ".", "access": "write" }, { "path": "./vendor", "access": "write" } ] }"""
    )
    assertEquals(shut.access("vendor/lib.jar"), Access.None)

  test("a project config grants nothing it does not mention"):
    val w = World(global = "", project = """{ "files": [ { "path": "./src", "access": "write" } ] }""")
    assertEquals(w.access("src/A.scala"), Access.Write)
    assertEquals(w.access("other.txt"), Access.None)

  test("the protective rules hold whatever grants a project writes"):
    val w = World(global = Config.globalTemplate, project = """{ "files": [ { "path": ".", "access": "write" } ] }""")
    assertEquals(w.access(".atc/config.json"), Access.None)
    assert(w.perm(".atc").locked)
    assert(w.perm(".env").classified)

  test("the starting global config protects without granting; a project config opens its tree"):
    val alone = World(global = Config.globalTemplate)
    assertEquals(alone.configuration.layers.map(_.origin), List(Origin.Global))
    assertEquals(alone.access("src/A.scala"), Access.None) // it grants nothing by itself
    assert(alone.perm(".env").classified) // but its protections are in force
    assertEquals(alone.settings.mode, Some("full"))
    assert(alone.settings.safeMode && alone.settings.respectGitignore)
    assert(ModelCatalog.from(alone.settings).models.nonEmpty)
    // add the starting project config and the project becomes workable
    // (what that config contains is the next test)
    val opened = World(global = Config.globalTemplate, project = Config.projectTemplate)
    assertEquals(opened.access("src/A.scala"), Access.Write)
    assertEquals(opened.outside(Path.of("/etc/passwd")), Access.None)

  test("the starting project config reaches language documentation, and nothing adjacent"):
    val w = World(global = Config.globalTemplate, project = Config.projectTemplate)
    for host <- List(
        "developer.mozilla.org",
        "docs.python.org",
        "peps.python.org", // *.python.org
        "doc.rust-lang.org",
        "docs.rs",
        "pkg.go.dev",
        "go.dev", // the bare domain needs its own entry
        "DOCS.ORACLE.COM", // matching is case-insensitive
        "arxiv.org", // a paper host
      )
    do assert(w.policy.hostAllowed(ScopeId.Base, host), host)
    // a wildcard must not swallow a look-alike domain, and nothing else is granted
    for host <- List(
        "python.org.evil.com",
        "evil-rust-lang.org",
        "notgo.dev",
        "example.com",
        "api.openai.com",
        "raw.githubusercontent.com",
      )
    do assert(!w.policy.hostAllowed(ScopeId.Base, host), host)
    // the hosts are the project's: the global config alone reaches nothing
    val alone = World(global = Config.globalTemplate)
    assert(!alone.policy.hostAllowed(ScopeId.Base, "docs.python.org"))

  test("the bundled starting config stands in for a missing ~/.atc/config.json only when asked to"):
    val w = World(global = "", project = GrantCwd)
    assertEquals(w.configuration.layers.map(_.origin), List(Origin.Project)) // not by default
    val bundled = Config.load(w.cwd, None, w.globalPath, bundledGlobal = true)
    assertEquals(
      bundled.layers.map(l => (l.origin, l.path)),
      List(Origin.Global -> None, Origin.Project -> Some(Config.projectPath(w.cwd)))
    )
    assert(bundled.layers.head.describe.contains("(bundled)"))
    assertEquals(bundled.sources, List(Config.projectPath(w.cwd))) // no file to report for it
    // it is the config the file would hold, and nothing was written
    assertEquals(bundled.settings.providers.keySet, ujson.read(Config.globalTemplate)("providers").obj.keySet)
    assert(!Files.exists(w.globalPath))
    // with the file present, the flag changes nothing
    Files.createDirectories(w.globalPath.getParent)
    Files.writeString(w.globalPath, """{ "commands": ["ls"] }""")
    assertEquals(Config.load(w.cwd, None, w.globalPath, bundledGlobal = true).layers.head.path, Some(w.globalPath))

  test("initProject writes the starting project config and its .gitignore once, and never again"):
    val w = World(global = "")
    val config = Config.projectPath(w.cwd)
    val ignore = config.getParent.nn.resolve(".gitignore").nn
    assertEquals(Config.initProject(w.cwd), List(config, ignore))
    assertEquals(Files.readString(config), Config.projectTemplate)
    assertEquals(Files.readString(ignore), Config.KeysFile + "\n")
    assertEquals(Config.initProject(w.cwd), Nil) // refuses to overwrite
    assertEquals(Config.projectRoot(w.cwd), Some(w.cwd)) // and the directory is a project now

  test("ensureGlobal writes the starting config and key bindings once, and never again"):
    val home = Files.createTempDirectory("atc-ensure").nn
    val path = home.resolve(".atc").resolve("config.json").nn
    val keys = home.resolve(".atc").resolve(Config.KeysFile).nn
    assertEquals(Config.ensureGlobal(path), List(path, keys))
    assertEquals(Files.readString(path), Config.globalTemplate)
    assertEquals(Files.readString(keys), Config.keysTemplate)
    Files.writeString(path, GrantCwd)
    Files.writeString(keys, "MINE=x")
    assertEquals(Config.ensureGlobal(path), Nil) // already there: left alone
    assertEquals(Files.readString(path), GrantCwd)
    assertEquals(Files.readString(keys), "MINE=x")

  test("the configuration itself is out of reach, and locked so no prompt can open it"):
    val w = World(global = Config.globalTemplate)
    for path <- List(".atc", ".atc/config.json", "sub/.atc/config.json") do
      assertEquals(w.access(path), Access.None, path)
      assert(w.perm(path).locked, path)
    // ~/.atc, where the grants live, is covered by the same rule
    assertEquals(w.outside(w.home.resolve(".atc/config.json").nn), Access.None)
    // and a locked path cannot be widened by answering a prompt
    val allowing = Policy(w.policy.rules, Nil, Nil, _ => Decision.AllowSession)
    val e = intercept[SecurityException](
      allowing.requestFile(ScopeId.Base, PlatformPath.canonical(w.cwd.resolve(".atc").nn), Access.Read, "peek")
    )
    assert(e.getMessage.nn.contains("locked"), e.getMessage)

  // ── the global layer grants ─────────────────────────────────────

  test("the global layer adds access, commands and hosts"):
    val w = World(global = """
      { "files": [ { "path": "$HOME/notes", "access": "read" } ],
        "commands": ["git *", "ls"], "hosts": ["*.example.com"] }
    """)
    assertEquals(w.outside(w.home.resolve("notes/x.md").nn), Access.Read)
    assert(w.policy.commandAllowed(ScopeId.Base, "git status"))
    assert(w.policy.hostAllowed(ScopeId.Base, "docs.example.com"))

  // ── the project layer narrows ───────────────────────────────────

  test("a project rule lowers access for the subtree it matches, and only that"):
    val w = World(project = """{ "files": [ { "path": "./build", "access": "read" } ] }""")
    assertEquals(w.access("build"), Access.Read)
    assertEquals(w.access("build/deep/x.jar"), Access.Read) // inherited by the whole subtree
    assertEquals(w.access("src/A.scala"), Access.Write) // untouched paths keep the granted access

  test("a project rule can lower the whole working directory"):
    val w = World(project = """{ "files": [ { "path": ".", "access": "read" } ] }""")
    assertEquals(w.access("src/A.scala"), Access.Read)
    assertEquals(w.access("."), Access.Read)

  test("a project rule cannot raise access the granting layers gave"):
    val w = World(
      global = """{ "files": [ { "path": ".", "access": "read" } ] }""",
      project = """{ "files": [ { "path": ".", "access": "write" }, { "path": "./src", "access": "write" } ] }"""
    )
    // the built-in grants write and the global lowers it to read; the project cannot undo either
    assertEquals(w.access("src/A.scala"), Access.Read)
    assertEquals(w.access("other.txt"), Access.Read)

  test("a project rule cannot grant access to a path no granting layer covers"):
    val w = World(project = """
      { "files": [ { "path": "~/.ssh", "access": "read" }, { "path": "/etc", "access": "read" } ] }
    """)
    assertEquals(w.outside(Path.of(scala.util.Properties.userHome, ".ssh")), Access.None)
    assertEquals(w.outside(Path.of("/etc/passwd")), Access.None)

  test("a project rule can add classification and locking, which only restrict"):
    val w = World(project = """
      { "files": [ { "path": "./vault", "classified": true }, { "path": "./vendor", "access": "read", "locked": true } ] }
    """)
    assert(w.perm("vault/key.txt").classified)
    assert(w.perm("vendor/lib.jar").locked)
    assertEquals(w.access("vendor/lib.jar"), Access.Read)

  test("a project layer cannot declassify what a granting layer classified"):
    val w = World(
      global = """{ "files": [ { "path": "./secrets", "classified": true } ] }""",
      project = """{ "files": [ { "path": "./secrets", "classified": false, "access": "write" } ] }"""
    )
    assert(w.perm("secrets/key.txt").classified)

  // ── commands and hosts ──────────────────────────────────────────

  test("commands and hosts are the union of every layer's list: a project may add its own"):
    val w = World(
      global = """{ "commands": ["ls"], "hosts": ["docs.oracle.com"] }""",
      project = """{ "commands": ["git status"], "hosts": ["*.example.com"] }"""
    )
    assertEquals(w.settings.commands, List("ls", "git status"))
    assert(w.policy.commandAllowed(ScopeId.Base, "ls -la"))
    assert(w.policy.commandAllowed(ScopeId.Base, "git status --short"), "pre-approved by the project")
    assert(!w.policy.commandAllowed(ScopeId.Base, "git push"))
    assert(w.policy.hostAllowed(ScopeId.Base, "docs.oracle.com"))
    assert(w.policy.hostAllowed(ScopeId.Base, "api.example.com"))
    assert(!w.policy.hostAllowed(ScopeId.Base, "example.org"))

  test("deny patterns are collected from every layer, and no layer's grant passes them"):
    val w = World(
      global = """{ "denyCommands": ["sudo *"], "denyHosts": ["*.internal"] }""",
      project = """{ "commands": ["sudo apt"], "hosts": ["*.internal"], "denyCommands": ["git push*"] }"""
    )
    assertEquals(w.settings.denyCommands, List("sudo *", "git push*"))
    assert(w.policy.commandDenied("git push origin").isDefined)
    assert(w.policy.commandDenied("sudo rm").isDefined)
    // the project granted them, the global deny list still wins
    assert(!w.policy.commandAllowed(ScopeId.Base, "sudo apt install x"))
    assert(!w.policy.hostAllowed(ScopeId.Base, "wiki.internal"))

  // ── scalars ─────────────────────────────────────────────────────

  test("safe mode is on unless a granting layer turns it off, and a project layer cannot"):
    def safeMode(global: String, project: String = "") = World(global = global, project = project).settings.safeMode
    assert(safeMode(""), "on when no layer mentions it")
    assert(safeMode(GrantCwd), "on when the config is silent about it")
    assert(!safeMode("""{ "safeMode": false }"""), "off when the global config says so")
    assert(safeMode("""{ "safeMode": false }""", """{ "safeMode": true }"""), "a project may switch it on")
    assert(safeMode("""{ "safeMode": true }""", """{ "safeMode": false }"""), "a project may not switch it off")
    assert(safeMode(GrantCwd, """{ "safeMode": false }"""), "not even when the global left it at the default")
    // an explicit -c file is the user's own choice, so it may turn it off
    assert(!World(global = """{ "safeMode": true }""", explicit = """{ "safeMode": false }""").settings.safeMode)

  test("the starting project config grants the project and protects its history"):
    val w = World(global = Config.globalTemplate, project = Config.projectTemplate)
    assertEquals(w.access("src/A.scala"), Access.Write)
    assertEquals(w.access(".git/config"), Access.Read) // git metadata is readable, not writable
    assertEquals(w.access(".git/hooks/pre-commit"), Access.Read)
    assert(w.perm("secrets/token").classified)
    assert(w.settings.safeMode && w.settings.respectGitignore)
    assert(w.policy.commandDenied("git push origin main").isDefined)
    assert(w.policy.commandDenied("git reset --hard HEAD~1").isDefined)
    assertEquals(w.outside(Path.of("/etc/passwd")), Access.None)
    // the read-only git commands are pre-approved (word-prefix match), nothing else is
    for cmd <- List(
        "git status",
        "git status --short",
        "git log --oneline -20",
        "git diff HEAD~1",
        "git show HEAD",
        "git blame src/A.scala",
        "git branch --list",
        "git rev-parse HEAD",
        "git ls-files",
        "git stash list"
      )
    do assert(w.policy.commandAllowed(ScopeId.Base, cmd), cmd)
    for cmd <- List(
        "git",
        "git statusx",
        "git branch -D main",
        "git remote add o url",
        "git stash",
        "git checkout .",
        "git commit -m x",
        "git difftool",
        "ls",
        "rm -rf x"
      )
    do assert(!w.policy.commandAllowed(ScopeId.Base, cmd), cmd)

  test("the project layer may only tighten the scalar settings"):
    val w = World(
      global = """
        { "mode": "full", "safeMode": false, "respectGitignore": false,
          "maxToolCalls": 100, "maxToolOutputChars": 50000, "executionTimeoutMs": 200000 }
      """,
      project = """
        { "mode": "readonly", "safeMode": true, "respectGitignore": true,
          "maxToolCalls": 10, "maxToolOutputChars": 1000, "executionTimeoutMs": 5000 }
      """
    )
    assertEquals(w.settings.mode, Some("read-only"))
    assert(w.settings.safeMode && w.settings.respectGitignore)
    assertEquals(w.settings.maxToolCalls, 10)
    assertEquals(w.settings.maxToolOutputChars, 1000)
    assertEquals(w.settings.executionTimeoutMs, Some(5000L))

  test("the project layer cannot loosen the scalar settings"):
    val w = World(
      global = """
        { "mode": "readonly", "safeMode": true, "respectGitignore": true,
          "maxToolCalls": 10, "maxToolOutputChars": 1000, "executionTimeoutMs": 5000 }
      """,
      project = """
        { "mode": "full", "safeMode": false, "respectGitignore": false,
          "maxToolCalls": 999, "maxToolOutputChars": 99999, "executionTimeoutMs": 999999 }
      """
    )
    assertEquals(w.settings.mode, Some("read-only"))
    assert(w.settings.safeMode && w.settings.respectGitignore)
    assertEquals(w.settings.maxToolCalls, 10)
    assertEquals(w.settings.maxToolOutputChars, 1000)
    assertEquals(w.settings.executionTimeoutMs, Some(5000L))

  test("a timeout the project adds applies; one it removes does not"):
    val noLimit = World(global = """{ "executionTimeoutMs": null }""", project = """{ "executionTimeoutMs": 9000 }""")
    assertEquals(noLimit.settings.executionTimeoutMs, Some(9000L))
    val limited = World(global = """{ "executionTimeoutMs": 9000 }""", project = """{ "executionTimeoutMs": null }""")
    assertEquals(limited.settings.executionTimeoutMs, Some(9000L))

  // ── models are not policy ───────────────────────────────────────

  test("a project layer may add models and pick one, without repeating the provider"):
    val w = World(
      global = """
        { "providers": { "ollama": { "api": "openai", "url": "http://localhost:11434/v1", "key": "k",
            "models": { "local": { "name": "llama3.1" } } } } }
      """,
      project = """
        { "model": "extra", "providers": { "ollama": { "models": { "extra": { "name": "qwen" } } } } }
      """
    )
    val catalog = ModelCatalog.from(w.settings)
    assertEquals(catalog.find("extra").modelId, "qwen")
    assertEquals(catalog.find("extra").api, "openai") // inherited from the global provider
    assertEquals(catalog.find("extra").baseUrl, Some("http://localhost:11434/v1"))
    assertEquals(catalog.find("local").modelId, "llama3.1") // the global model survives
    assertEquals(w.settings.model, Some("extra"))

  test("an explicit -c file outranks the project layer for the model, and cannot undo its narrowing"):
    val w = World(
      global = """
        { "commands": ["git *"], "files": [ { "path": ".", "access": "write" } ],
          "providers": { "p": { "api": "echo", "models": { "one": {}, "two": {} } } } }
      """,
      project = """{ "model": "one", "commands": ["ls"], "files": [ { "path": ".", "access": "read" } ] }""",
      explicit = """{ "model": "two", "commands": ["rm *"], "files": [ { "path": ".", "access": "write" } ] }"""
    )
    assertEquals(w.settings.model, Some("two")) // -c wins for what is not policy
    // every layer's commands add up ...
    assertEquals(w.settings.commands, List("git *", "ls", "rm *"))
    assert(w.policy.commandAllowed(ScopeId.Base, "rm x"))
    // ... but the project layer's file cap holds against `-c` as it does against the global config
    assertEquals(w.access("src/A.scala"), Access.Read)

  // ── order and sources ───────────────────────────────────────────

  test("layers are reported in order, and a path named twice is read once"):
    val w = World(global = """{ "maxToolCalls": 9 }""", project = """{ "maxToolCalls": 8 }""")
    assertEquals(w.configuration.layers.map(_.origin), List(Origin.Global, Origin.Project))
    assertEquals(w.configuration.sources, List(w.globalPath, Config.projectPath(w.cwd)))
    // naming the project file with -c keeps it in its narrowing role
    val again = Config.load(w.cwd, Some(Config.projectPath(w.cwd)), w.globalPath)
    assertEquals(again.layers.map(_.origin), List(Origin.Global, Origin.Project))
    assertEquals(again.settings.maxToolCalls, 8)

  test("running in the home directory keeps ~/.atc/config.json a granting layer"):
    val home = Files.createTempDirectory("atc-home-cwd").nn.toRealPath().nn
    val globalPath = home.resolve(".atc").resolve("config.json").nn
    Files.createDirectories(globalPath.getParent)
    Files.writeString(globalPath, GrantCwd)
    val loaded = Config.load(home, None, globalPath)
    // it is found by both the global path and the upward search; the grant wins
    assertEquals(loaded.layers.map(_.origin), List(Origin.Global))
    assertEquals(loaded.rules.map(_.base), List(None)) // granting, not anchored to a project

  // ── keys ────────────────────────────────────────────────────────

  /** A world with the two `keys` files, and a provider whose key is a
    * `${VAR}` reference for them to bind. */
  private def keyed(project: String = "", global: String = ""): ModelSpec =
    val w = World(global =
      """{ "providers": { "p": { "api": "echo", "key": "${ATC_TEST_BINDING}", "models": { "m": {} } } } }"""
    )
    if global.nonEmpty then Files.writeString(w.globalPath.getParent.nn.resolve(Config.KeysFile), global)
    if project.nonEmpty then
      Files.createDirectories(Config.projectPath(w.cwd).getParent)
      Files.writeString(Config.keysPath(w.cwd), project)
    Config.load(w.cwd, None, w.globalPath).catalog.find("m")

  test("a binding comes from the project's keys file, then the global one, then the environment"):
    val bind = (v: String) => s"ATC_TEST_BINDING=$v"
    assertEquals(keyed(project = bind("project"), global = bind("global")).apiKey, Some("project"))
    assertEquals(keyed(global = bind("global")).apiKey, Some("global"))
    assertEquals(keyed(project = bind("project")).apiKey, Some("project"))
    // nothing binds it here, and the variable is not in the environment either
    assertEquals(keyed().apiKey, None)

  test("an empty binding is passed over, so the next source gets a turn"):
    assertEquals(keyed(project = "ATC_TEST_BINDING=", global = "ATC_TEST_BINDING=global").apiKey, Some("global"))
    assertEquals(keyed(project = "# nothing here", global = "ATC_TEST_BINDING=global").apiKey, Some("global"))
    assertEquals(keyed(global = "ATC_TEST_BINDING=").apiKey, None)

  test("keys are not settings: they never reach the config, the layers or a message"):
    val w = World(global =
      """{ "providers": { "p": { "api": "echo", "key": "${ATC_TEST_BINDING}", "models": { "m": {} } } } }"""
    )
    Files.writeString(w.globalPath.getParent.nn.resolve(Config.KeysFile), "ATC_TEST_BINDING=sk-secret\n")
    val loaded = Config.load(w.cwd, None, w.globalPath)
    assertEquals(loaded.keys.names, List("ATC_TEST_BINDING"))
    assert(!loaded.settings.toString.contains("sk-secret"), "a key is not part of the settings")
    assert(!loaded.layers.map(_.json.toString).mkString.contains("sk-secret"), "nor of any layer")
    val spec = loaded.catalog.find("m")
    assertEquals(spec.apiKey, Some("sk-secret"))
    assert(!spec.toString.contains("sk-secret"), spec.toString)

  test("a keys file that is not a properties file is a clear error"):
    val w = World()
    Files.writeString(w.globalPath.getParent.nn.resolve(Config.KeysFile), "BAD_KEY=\\uZZZZ\n")
    val e = intercept[IllegalArgumentException](Config.load(w.cwd, None, w.globalPath))
    assert(e.getMessage.nn.contains("Cannot read keys"), e.getMessage)

  test("the starting policy keeps the agent out of the keys"):
    val w = World(global = Config.globalTemplate, project = Config.projectTemplate)
    assertEquals(w.access(".atc/keys.properties"), Access.None)
    assert(w.perm(".atc/keys.properties").locked)
    assertEquals(w.outside(w.home.resolve(".atc/keys.properties").nn), Access.None)

  // ── the project a directory belongs to ──────────────────────────

  test("the project config is found by walking up, and governs the folder its .atc sits in"):
    val w = World(project = """{ "files": [ { "path": "./build", "access": "read" } ] }""", runIn = "src/main")
    assertEquals(w.configuration.layers.map(_.origin), List(Origin.Global, Origin.Project))
    assertEquals(Config.projectRoot(w.runDir), Some(w.cwd))
    // "./build" is the project's build directory, not one under the working directory
    assertEquals(w.access("build/x.jar"), Access.Read)
    assertEquals(w.access("src/main/build/x.jar"), Access.Write)

  test("a project rule reaches the whole project, however deep atc runs inside it"):
    val w = World(project = """{ "files": [ { "path": ".", "access": "read" } ] }""", runIn = "a/b/c")
    assertEquals(w.access("a/b/c/x.txt"), Access.Read)
    assertEquals(w.access("x.txt"), Access.Read)

  test("a project reached through a symlink still grants its own tree"):
    // The policy evaluates canonical paths, so the project layer's base must also
    // be canonical. Otherwise, a project reached through a symlink never grants.
    val real = Files.createTempDirectory("atc-symlink-real").nn.toRealPath().nn
    val linkParent = Files.createTempDirectory("atc-symlink-base").nn.toRealPath().nn
    val link = linkParent.resolve("proj").nn
    assume(TestEnv.trySymbolicLink(link, real), "symbolic links are unavailable for this account")
    Files.createDirectories(real.resolve(".atc"))
    Files.writeString(real.resolve(".atc/config.json"), """{ "files": [ { "path": ".", "access": "write" } ] }""")
    // Load the project through the symlink, before the caller canonicalizes it.
    val configuration = Config.load(link, None, linkParent.resolve("no-global.json").nn)
    val policy = Policy(App.fileRules(configuration, link), Nil, Nil, _ => Decision.Deny)
    assertEquals(
      policy.effective(ScopeId.Base, PlatformPath.canonical(real.resolve("src/x.txt").nn)).access,
      Access.Write
    )
    assertEquals(
      policy.effective(ScopeId.Base, PlatformPath.canonical(linkParent.resolve("other.txt").nn)).access,
      Access.None
    )

  test("with no .atc anywhere above, there is no project layer"):
    val w = World(runIn = "sub")
    assertEquals(w.configuration.layers.map(_.origin), List(Origin.Global))
    assertEquals(Config.projectRoot(w.runDir), None)

  // ── parent / child ──────────────────────────────────────────────

  test("access is the strictest rule on the path or any ancestor, whatever layer wrote it"):
    val w = World(
      global = """{ "files": [ { "path": ".", "access": "write" }, { "path": "./a/b", "access": "read" } ] }""",
      project = """{ "files": [ { "path": "./a", "access": "read" }, { "path": "./a/b/c", "access": "none" } ] }"""
    )
    assertEquals(w.access("x.txt"), Access.Write) // outside both narrowings
    assertEquals(w.access("a/x.txt"), Access.Read) // the project's cap on the parent
    assertEquals(w.access("a/b/x.txt"), Access.Read) // global read and project read agree
    assertEquals(w.access("a/b/c/x.txt"), Access.None) // the deepest cap wins
    assertEquals(w.access("a/b/c/deep/x.txt"), Access.None) // and covers its subtree

  test("a cap on a child does not lower its parent or its siblings"):
    val w = World(project = """{ "files": [ { "path": "./src/generated", "access": "none" } ] }""")
    assertEquals(w.access("src/A.scala"), Access.Write)
    assertEquals(w.access("src/generated/G.scala"), Access.None)

  test("what the user grants at a prompt may exceed a project cap: the human decides"):
    val w = World(project = """{ "files": [ { "path": "./build", "access": "read" } ] }""")
    val allowing = Policy(w.policy.rules, Nil, Nil, _ => Decision.AllowSession)
    val build = PlatformPath.canonical(w.cwd.resolve("build").nn)
    assertEquals(allowing.effective(ScopeId.Base, build).access, Access.Read)
    val scope = allowing.requestFile(ScopeId.Base, build, Access.Write, "generate")
    assertEquals(allowing.effective(scope, build).access, Access.Write)
    allowing.closeScope(scope)
    // unless the cap also locks it, which no prompt can widen
    val locked = World(project = """{ "files": [ { "path": "./build", "access": "read", "locked": true } ] }""")
    val lockedPolicy = Policy(locked.policy.rules, Nil, Nil, _ => Decision.AllowSession)
    val lockedBuild = PlatformPath.canonical(locked.cwd.resolve("build").nn)
    intercept[SecurityException](lockedPolicy.requestFile(ScopeId.Base, lockedBuild, Access.Write, "generate"))

  // ── .gitignore ──────────────────────────────────────────────────

  test("gitignore hides, after the policy has decided, and only ever hides"):
    val w = World(project = """{ "respectGitignore": true }""")
    Files.createDirectories(w.cwd.resolve(".git"))
    Files.writeString(w.cwd.resolve(".gitignore"), "out/" + scala.util.Properties.lineSeparator)
    Files.createDirectories(w.cwd.resolve("out"))
    Files.writeString(w.cwd.resolve("out/log.txt"), "x")
    Files.writeString(w.cwd.resolve("kept.txt"), "y")
    val ignore = GitIgnore(w.cwd)
    // the policy still permits the ignored path: gitignore is visibility only
    assertEquals(w.access("out/log.txt"), Access.Write)
    assert(ignore.ignores(w.cwd.resolve("out")))
    assert(!ignore.ignores(w.cwd.resolve("kept.txt")))

  test("the project layer can switch gitignore filtering on, but not off"):
    assert(World(global = """{ "respectGitignore": false }""", project = """{ "respectGitignore": true }""")
      .settings.respectGitignore)
    assert(World(global = """{ "respectGitignore": true }""", project = """{ "respectGitignore": false }""")
      .settings.respectGitignore)

  test("a broken layer names the file it came from"):
    val e = intercept[IllegalArgumentException](World(project = "{ not json ]"))
    assert(e.getMessage.nn.contains("Cannot parse config"), e.getMessage)
    assert(e.getMessage.nn.contains(".atc"), e.getMessage)

  test("an invalid mode in a project layer is a config error naming the file"):
    val e = intercept[IllegalArgumentException](World(project = """{ "mode": "bogus" }"""))
    assert(e.getMessage.nn.contains("Invalid config"), e.getMessage)
    assert(e.getMessage.nn.contains("Unknown mode"), e.getMessage)
    assert(e.getMessage.nn.contains(".atc"), e.getMessage)
