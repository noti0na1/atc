package atc

import atc.confine.{Bubblewrap, CommandSandbox, SandboxPlan, Seatbelt}
import atc.confine.SandboxPlan.{Level, Restriction, Target}
import atc.lib.{Exec, FileSystem}
import atc.perms.*
import atc.platform.{Platform, PlatformPath}

import java.nio.file.{Files, Path}

class ConfinementSuite extends munit.FunSuite:
  private val home = PlatformPath.canonical(PlatformPath.userHome)
  private def cache = Files.createTempDirectory("atc-sandbox-cache").nn.toRealPath().nn

  private def plan(env: TestEnv, network: Boolean = false, scope: ScopeId = ScopeId.Base): SandboxPlan =
    SandboxPlan(env.policy, scope, env.root, home, network, cache)

  private val secretsAndGit: Path => List[FileRule] = root =>
    TestEnv.withSecrets(root) ++ List(
      FileRule(PathPattern(".git", root), Some(Access.Read), None),
      FileRule(PathPattern("./private", root), Some(Access.None), None, locked = true),
    )

  // ── the plan ─────────────────────────────────────────────────────

  test("the plan turns granting rules into roots and narrowing rules into restrictions"):
    val env = TestEnv(mkRules = secretsAndGit)
    val p = plan(env)
    assertEquals(p.writable, List(env.root))
    val restrictions = p.restrictions.toSet
    assert(restrictions.contains(Restriction(Level.Secret, Target.Component("secrets"))))
    assert(restrictions.contains(Restriction(Level.Secret, Target.Component(".env"))))
    assert(restrictions.contains(Restriction(Level.ReadOnly, Target.Exact(env.root.resolve(".git").nn))))
    assert(restrictions.contains(Restriction(Level.Hidden, Target.Exact(env.root.resolve("private").nn))))
    assert(restrictions.contains(Restriction(Level.ReadOnly, Target.Exact(env.root.resolve(".vscode").nn))))
    assert(restrictions.contains(Restriction(Level.Hidden, Target.Exact(home.resolve(".ssh").nn))))
    assert(!p.network)

  test("a writable .git keeps only its hooks and configuration read-only"):
    val env = TestEnv()
    val restrictions = plan(env).restrictions.toSet
    val git = env.root.resolve(".git").nn
    assert(restrictions.contains(Restriction(Level.ReadOnly, Target.Exact(git.resolve("hooks").nn))))
    assert(restrictions.contains(Restriction(Level.ReadOnly, Target.Exact(git.resolve("config").nn))))
    assert(!restrictions.contains(Restriction(Level.ReadOnly, Target.Exact(git))))

  test("a grant of the command's scope adds a root, and the mode decides the network"):
    val env = TestEnv()
    val outside = TestEnv.outsideDir()
    env.decisions = List(Decision.AllowOnce)
    val scope = env.policy.requestFile(ScopeId.Base, outside, Access.Write, "test")
    assert(plan(env, scope = scope).writable.contains(outside))
    assert(!plan(env).writable.contains(outside), "the base scope does not see the block's grant")
    assert(plan(env, network = true).network)

  test("toolchain directories never include the home directory or a directory above it"):
    val bundle = SandboxPlan.toolchain(home)
    assert(bundle.forall(p => !home.startsWith(p)), bundle.toString)

  // ── rendering ───────────────────────────────────────────────────

  test("globs become regular expressions that match whole names"):
    val pem = Seatbelt.globRegex("*.pem")
    val letters = if Platform.caseInsensitivePaths then "[pP][eE][mM]" else "pem"
    assertEquals(pem, s"[^/]*\\.$letters")
    assertEquals(
      Seatbelt.globRegex("{a,b}/**/x?"),
      if Platform.caseInsensitivePaths then "([aA]|[bB])/(.*/)?[xX][^/]" else "(a|b)/(.*/)?x[^/]"
    )

  test("bubblewrap masks glob matches that exist and skips dependency directories"):
    val env = TestEnv(mkRules = secretsAndGit)
    env.file("a/.env", "x")
    env.file("node_modules/pkg/.env", "x")
    val masks = Bubblewrap.masks(plan(env)).map(_._2).toSet
    assert(masks.contains(env.root.resolve("a/.env").nn))
    assert(!masks.contains(env.root.resolve("node_modules/pkg/.env").nn))

  test("a required sandbox that is unavailable refuses commands"):
    val env = TestEnv(commands = List("sh"), commandSandbox = CommandSandbox.Refusing("it is not available"))
    import env.given
    given Exec = env.host.processes
    given FileSystem = env.host.fileSystem
    val error = intercept[SecurityException](env.host.exec("sh", List("-c", "true")))
    assert(error.getMessage.contains("required"))

  // ── running commands ────────────────────────────────────────────

  private lazy val sandbox: CommandSandbox = CommandSandbox.detect("auto")

  /** A host whose commands run in the platform's sandbox; skips when there is none. */
  private def confined(mode: Mode = Mode.Local): TestEnv =
    assume(sandbox.confined, s"no command sandbox here: ${sandbox.describe}")
    val env = TestEnv(mkRules = secretsAndGit, commands = List("sh"), commandSandbox = sandbox)
    env.policy.mode = mode
    env

  private def sh(env: TestEnv, script: String): lib.ProcessResult =
    import env.given
    given Exec = env.host.processes
    given FileSystem = env.host.fileSystem
    env.host.exec("sh", List("-c", script))

  test("a confined command writes in the project and nowhere else"):
    val env = confined()
    val outside = TestEnv.outsideDir()
    assertEquals(sh(env, "echo inside > made.txt").exitCode, 0)
    assertEquals(env.contents("made.txt"), "inside\n")
    assertNotEquals(sh(env, s"echo x > '${outside.resolve("o.txt")}'").exitCode, 0)
    assertEquals(Files.readString(outside.resolve("o.txt")), "outside")

  test("a confined command reads neither classified nor hidden files"):
    val env = confined()
    env.file("secrets/key", "secret")
    env.file("sub/.env", "TOKEN=1")
    env.file("private/p.txt", "private")
    env.file("open.txt", "open")
    assertEquals(sh(env, "cat open.txt").stdout, "open")
    for path <- List("secrets/key", "sub/.env", "private/p.txt") do
      val result = sh(env, s"cat $path")
      assertNotEquals(result.exitCode, 0, path)
      assert(!result.stdout.contains("secret") && !result.stdout.contains("TOKEN"), path)

  test("a confined command cannot change protected paths"):
    val env = confined()
    env.dir(".git/hooks")
    env.dir(".vscode")
    assertNotEquals(sh(env, "echo x > .git/hooks/pre-commit").exitCode, 0)
    assertNotEquals(sh(env, "echo x > .vscode/tasks.json").exitCode, 0)
    assert(!env.existsOnDisk(".git/hooks/pre-commit") && !env.existsOnDisk(".vscode/tasks.json"))

  test("without network permission a confined command has no network"):
    val env = confined()
    assume(Files.isExecutable(Path.of("/usr/bin/curl")), "no curl")
    assertNotEquals(sh(env, "/usr/bin/curl -s -m 5 -o /dev/null http://example.com").exitCode, 0)

  test("both network profiles are accepted by sandbox-exec"):
    assume(Platform.isMac && sandbox.confined, "macOS only")
    val env = TestEnv(mkRules = secretsAndGit)
    for network <- List(false, true) do
      val tmp = Files.createTempDirectory("atc-profile").nn.toRealPath().nn
      val process = ProcessBuilder(Seatbelt.prefix(plan(env, network), tmp) :+ "/usr/bin/true"*)
        .redirectErrorStream(true).start().nn
      val output = String(process.getInputStream.nn.readAllBytes().nn)
      assertEquals(process.waitFor(), 0, output)
