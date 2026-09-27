package atc

import atc.confine.{Bubblewrap, CommandProxy, CommandSandbox, SandboxPlan, Seatbelt}
import atc.confine.SandboxPlan.{Level, Restriction, Target}
import atc.lib.{Exec, FileSystem}
import atc.perms.*
import atc.platform.{Platform, PlatformPath}

import com.sun.net.httpserver.HttpServer

import java.io.{BufferedReader, InputStreamReader}
import java.net.{InetAddress, InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

class ConfinementSuite extends munit.FunSuite:
  private val home = PlatformPath.canonical(PlatformPath.userHome)
  private def cache = Files.createTempDirectory("atc-sandbox-cache").nn.toRealPath().nn

  private def plan(env: TestEnv, network: Boolean = false, scope: ScopeId = ScopeId.Base, mayWrite: Boolean = true)
    : SandboxPlan =
    SandboxPlan(env.policy, scope, env.root, home, network, mayWrite, cache)

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

  /** `sh -c script` through `execReadOnly`. */
  private def shReadOnly(env: TestEnv, script: String): lib.ProcessResult =
    import env.given
    given Exec = env.host.processes
    given FileSystem = env.host.fileSystem
    env.host.execReadOnly("sh", List("-c", script))

  /** `sh -c script` inside `withNetwork`. */
  private def shNetwork(env: TestEnv, script: String): lib.ProcessResult =
    import env.given
    given Exec = env.host.processes
    given FileSystem = env.host.fileSystem
    given lib.Network = env.host.network
    env.host.withNetwork(env.host.exec("sh", List("-c", script)))

  test("a read-only plan keeps the policy's writable roots readable only"):
    val env = TestEnv()
    val p = plan(env, mayWrite = false)
    assertEquals(p.writable, Nil)
    assert(p.readable.contains(env.root))

  test("a read-only command reads the project, writes only its temporary directory, and works in read-only mode"):
    val env = confined(Mode.ReadOnly)
    env.file("open.txt", "open")
    assertEquals(shReadOnly(env, "cat open.txt").stdout, "open")
    assertEquals(shReadOnly(env, "echo t > \"$TMPDIR/t\" && cat \"$TMPDIR/t\"").stdout, "t\n")
    assertNotEquals(shReadOnly(env, "echo x > made.txt").exitCode, 0)
    assert(!env.existsOnDisk("made.txt"))
    import env.given
    given Exec = env.host.processes
    given FileSystem = env.host.fileSystem
    intercept[IllegalArgumentException](env.host.execReadOnly("sh -c true > out.txt"))

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
      val process = ProcessBuilder(Seatbelt.prefix(plan(env, network), tmp, Some(40000)) :+ "/usr/bin/true"*)
        .redirectErrorStream(true).start().nn
      val output = String(process.getInputStream.nn.readAllBytes().nn)
      assertEquals(process.waitFor(), 0, output)

  // ── the network proxy ───────────────────────────────────────────

  /** A local web server answering every request with `hello`. */
  private lazy val web: HttpServer =
    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0).nn
    server.createContext(
      "/",
      exchange =>
        val body = "hello".getBytes(UTF_8)
        exchange.sendResponseHeaders(200, body.length.toLong)
        exchange.getResponseBody.nn.write(body)
        exchange.close()
    )
    server.start()
    server
  private def webPort: Int = web.getAddress.nn.getPort

  override def afterAll(): Unit = web.stop(0)

  /** Send `request` to the proxy and return everything it answers. */
  private def through(proxy: CommandProxy, request: String, afterwards: Option[String] = None): String =
    val socket = Socket(InetAddress.getLoopbackAddress, proxy.port)
    try
      socket.setSoTimeout(10_000)
      val out = socket.getOutputStream.nn
      out.write(request.getBytes(UTF_8))
      out.flush()
      val in = BufferedReader(InputStreamReader(socket.getInputStream.nn, UTF_8))
      afterwards match
        case None => Iterator.continually(in.readLine()).takeWhile(_ != null).mkString("\n")
        case Some(next) =>
          val status = in.readLine()
          Iterator.continually(in.readLine()).takeWhile(line => line != null && line.nonEmpty).foreach(_ => ())
          out.write(next.getBytes(UTF_8))
          out.flush()
          (status +: Iterator.continually(in.readLine()).takeWhile(_ != null).toList).mkString("\n")
    finally socket.close()

  private def networkEnv(hosts: List[String]): TestEnv =
    val env = TestEnv(hosts = hosts)
    env.policy.mode = Mode.Full
    env

  private def get(host: String) = s"GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"

  test("the proxy forwards requests and opens tunnels to allowed hosts"):
    val env = networkEnv(List("127.0.0.1"))
    val proxy = CommandProxy.tcp(env.policy, ScopeId.Base, "curl")
    try
      val direct =
        through(proxy, s"GET http://127.0.0.1:$webPort/ HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
      assert(direct.startsWith("HTTP/1.1 200") && direct.endsWith("hello"), direct)
      val tunneled = through(proxy, s"CONNECT 127.0.0.1:$webPort HTTP/1.1\r\n\r\n", Some(get("127.0.0.1")))
      assert(tunneled.startsWith("HTTP/1.1 200 Connection Established") && tunneled.endsWith("hello"), tunneled)
    finally proxy.close()

  test("the proxy asks about a host the policy does not allow, and refuses it when denied"):
    val env = networkEnv(Nil)
    val proxy = CommandProxy.tcp(env.policy, ScopeId.Base, "curl")
    try
      val refused = through(proxy, s"CONNECT 127.0.0.1:$webPort HTTP/1.1\r\n\r\n")
      assert(refused.startsWith("HTTP/1.1 403"), refused)
      assertEquals(env.requests.size, 1, "one question")
      through(proxy, s"CONNECT 127.0.0.1:$webPort HTTP/1.1\r\n\r\n")
      assertEquals(env.requests.size, 1, "the answer holds for the rest of the command")
    finally proxy.close()

  test("a host approved once is reachable until the command ends"):
    val env = networkEnv(Nil)
    env.decisions = List(Decision.AllowOnce)
    val proxy = CommandProxy.tcp(env.policy, ScopeId.Base, "curl")
    val answer = through(proxy, s"CONNECT 127.0.0.1:$webPort HTTP/1.1\r\n\r\n", Some(get("127.0.0.1")))
    assert(answer.endsWith("hello"), answer)
    proxy.close()
    assertEquals(env.policy.openScopeCount, 0, "the once-grant's scope closes with the command")
    assert(!env.policy.hostAllowed(ScopeId.Base, "127.0.0.1"))

  test("an allowed name that resolves to this machine is refused"):
    val env = networkEnv(List("localhost"))
    val proxy = CommandProxy.tcp(env.policy, ScopeId.Base, "curl")
    try
      val answer = through(proxy, s"CONNECT localhost:$webPort HTTP/1.1\r\n\r\n")
      assert(answer.startsWith("HTTP/1.1 403") && answer.contains("local"), answer)
    finally proxy.close()

  test("a confined command with network permission gets out only through the proxy"):
    assume(
      sandbox.confined && !sandbox.notice.exists(_.contains("socat")),
      s"no filtered network here: ${sandbox.describe}"
    )
    assume(Files.isExecutable(Path.of("/usr/bin/curl")), "no curl")
    val env = TestEnv(commands = List("sh"), hosts = List("127.0.0.1"), commandSandbox = sandbox)
    env.policy.mode = Mode.Full
    val url = s"http://127.0.0.1:$webPort/"
    assertEquals(shNetwork(env, s"/usr/bin/curl -s -m 10 --noproxy '' $url").stdout, "hello")
    assertNotEquals(shNetwork(env, s"/usr/bin/curl -s -m 5 --noproxy '*' $url").exitCode, 0, "a direct connection")
    assertNotEquals(
      sh(env, s"/usr/bin/curl -s -m 5 $url").exitCode,
      0,
      "a plain Exec has no network, even in full mode"
    )
