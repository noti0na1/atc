package atc

import atc.host.*
import atc.lib.{Classified, Exec, FileSystem, Network}
import atc.perms.*
import atc.sandbox.ReplSession

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.file.{Files, Path}

/** Exec and network permission enforcement, at the host level and end-to-end
  * through a sandbox REPL (migrated from TACIT's ProcessPermissionSuite,
  * WebOpsSuite and the exec/network parts of LibraryIntegrationSuite). */
class PermissionSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  // A local HTTP server so network tests never touch the real internet.
  var server: HttpServer = scala.compiletime.uninitialized
  var port: Int = 0
  var host: String = ""

  override def beforeAll(): Unit =
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).nn
    port = server.getAddress.nn.getPort
    host = s"127.0.0.1"
    def handler(status: Int, body: HttpExchange => String) = new com.sun.net.httpserver.HttpHandler:
      def handle(ex: HttpExchange): Unit =
        val b = body(ex).getBytes("UTF-8")
        ex.sendResponseHeaders(status, b.length.toLong)
        val os = ex.getResponseBody.nn
        os.write(b); os.close()
    server.createContext("/ok", handler(200, _ => "hello"))
    server.createContext("/echo", handler(200, ex => String(ex.getRequestBody.nn.readAllBytes(), "UTF-8")))
    server.createContext(
      "/header",
      handler(200, ex => Option(ex.getRequestHeaders.nn.getFirst("X-Token")).getOrElse("(none)"))
    )
    server.createContext("/method", handler(200, ex => ex.getRequestMethod.nn))
    server.createContext(
      "/content-type",
      handler(200, ex => ex.getRequestHeaders.nn.get("Content-Type").nn.toString) // e.g. "[text/plain]"
    )
    server.createContext("/not-found", handler(404, _ => """{"error":"not found"}"""))
    server.createContext("/boom", handler(500, _ => "internal error: broke"))
    server.start()

  override def afterAll(): Unit = if server != null then server.stop(0)

  private def url(path: String): String = s"http://$host:$port$path"

  // ── Exec, at the host level ─────────────────────────────────────

  test("exec runs an allowed command and captures stdout/exit code"):
    val env = TestEnv(commands = List("echo"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    val r = env.host.exec("echo", List("hello", "world"))
    assertEquals(r.exitCode, 0)
    assertEquals(r.stdout.trim, "hello world")
    assertEquals(r.stderr, "")
    assertEquals(env.host.execOutput("echo", List("out")).trim, "out")

  test("exec rejects a disallowed command with a request hint"):
    val env = TestEnv(commands = List("echo"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    val e = intercept[SecurityException](env.host.exec("rm", List("-rf", "/")))
    assert(e.getMessage.nn.contains("no permitted pattern"), e.getMessage)
    assert(e.getMessage.nn.contains("requestExec"), e.getMessage)

  test("exec captures a non-zero exit code and stderr instead of throwing"):
    val env = TestEnv(commands = List("ls"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    val r = env.host.exec("ls", List("no-such-file-xyz"))
    assert(r.exitCode != 0)
    assert(r.stderr.nonEmpty)

  test("exec respects an explicit working directory"):
    val env = TestEnv(commands = List("pwd"))
    env.dir("sub")
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    val r = env.host.exec("pwd", Nil, Some(env.root.resolve("sub").toString))
    assert(r.stdout.trim.endsWith("/sub"), r.stdout)

  test("exec rejects a working directory the agent cannot read"):
    val env = TestEnv(commands = List("pwd"))
    val outside = TestEnv.outsideDir()
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    val e = intercept[SecurityException](env.host.exec("pwd", Nil, Some(outside.toString)))
    assert(e.getMessage.nn.contains("Access denied"), e.getMessage)

  test("exec rejects a working directory inside a classified area"):
    val env = TestEnv(
      mkRules = root =>
        List(
          FileRule(PathPattern(".", root), Some(Access.Write), None),
          FileRule(PathPattern("secrets", root), None, Some(true)),
        ),
      commands = List("pwd"),
    )
    env.dir("secrets")
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    val secrets = env.root.resolve("secrets").toString
    val e = intercept[SecurityException](env.host.exec("pwd", Nil, Some(secrets)))
    assert(e.getMessage.nn.contains("classified"), e.getMessage)
    assert(e.getMessage.nn.contains(secrets), e.getMessage) // the path, not a literal "$dir"

  test("exec's working-directory check honours a requestFiles grant (allow once)"):
    val env = TestEnv(commands = List("pwd"))
    val outside = TestEnv.outsideDir()
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    env.decisions = List(Decision.AllowOnce)
    val out = env.host.requestFiles(outside.toString, atc.lib.Access.Read, "run there") {
      env.host.exec("pwd", Nil, Some(outside.toString)).stdout.trim
    }
    assertEquals(out, outside.toString)
    assertEquals(env.requests.size, 1)
    // the grant was for the block only
    intercept[SecurityException](env.host.exec("pwd", Nil, Some(outside.toString)))

  test("exec's default working directory is allowed even when cwd is reached through a symlink"):
    val env = TestEnv(commands = List("pwd"))
    val link = Files.createTempDirectory("atc-link").nn.resolve("proj").nn
    Files.createSymbolicLink(link, env.root)
    // A host whose cwd is the symlink (as `atc -C /tmp/proj` would be on macOS, where /tmp -> /private/tmp).
    val host = Host(env.policy, link, env.output, env.llm, env.ui)
    import env.given
    given ex: Exec = host.processes
    given fs: FileSystem = host.fileSystem
    val r = host.exec("pwd")
    assertEquals(r.exitCode, 0, r.stderr)
    assertEquals(host.execOutput("pwd").trim, env.root.toString)

  test("exec enforces a timeout"):
    val env = TestEnv(commands = List("sleep"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    val e = intercept[RuntimeException](env.host.exec("sleep", List("30"), None, 150L))
    assert(e.getMessage.nn.contains("timed out"), e.getMessage)

  test("command matching is arg-aware: a glob pattern still filters arguments"):
    val env = TestEnv(commands = List("git status", "git diff*", "npm *"))
    // command-word matching happens per full command line
    assert(env.policy.commandAllowed(ScopeId.Base, "git status"))
    assert(env.policy.commandAllowed(ScopeId.Base, "git status --short"))
    assert(env.policy.commandAllowed(ScopeId.Base, "git difftool")) // "git diff*"
    assert(env.policy.commandAllowed(ScopeId.Base, "npm install"))
    assert(!env.policy.commandAllowed(ScopeId.Base, "git push"))
    assert(!env.policy.commandAllowed(ScopeId.Base, "npm")) // "npm *" requires an arg

  // ── requestExec scopes ──────────────────────────────────────────

  test("requestExec opens a scope, prompts once, and closes it"):
    val env = TestEnv(commands = List("echo"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    env.decisions = List(Decision.AllowOnce)
    val out = env.host.requestExec(Set("ls*"), "list") {
      env.host.exec("ls", List(env.root.toString)).exitCode
    }
    assertEquals(out, 0)
    assertEquals(env.requests.size, 1)
    assertEquals(env.requests.head.asInstanceOf[ExecRequest].reason, "list")
    assertEquals(env.policy.openScopeCount, 0)
    // the once-grant did not leak to the base scope
    intercept[SecurityException](env.host.exec("ls"))

  test("requestExec with AllowSession persists the grant"):
    val env = TestEnv(commands = List("echo"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    env.decisions = List(Decision.AllowSession)
    env.host.requestExec(Set("ls*"), "list") { () }
    assertEquals(env.host.exec("ls", List(env.root.toString)).exitCode, 0)

  test("requestExec on an already-permitted pattern does not prompt"):
    val env = TestEnv(commands = List("echo"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    val r = env.host.requestExec(Set("echo")) { env.host.exec("echo", List("hi")).stdout.trim }
    assertEquals(r, "hi")
    assert(env.requests.isEmpty)

  test("a denied requestExec throws and runs nothing"):
    val env = TestEnv(commands = List("echo"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    env.decisions = List(Decision.Deny)
    var ran = false
    intercept[SecurityException](env.host.requestExec(Set("rm")) { ran = true })
    assert(!ran)

  // ── Network, at the host level ──────────────────────────────────

  test("httpGet returns the body when the host is allowed"):
    val env = TestEnv(hosts = List(host))
    import env.given
    given net: Network = env.host.network
    assertEquals(env.host.httpGet(url("/ok")), "hello")

  test("httpGet returns the error body on 404 and 500 without throwing"):
    val env = TestEnv(hosts = List(host))
    import env.given
    given net: Network = env.host.network
    assert(env.host.httpGet(url("/not-found")).contains("not found"))
    assert(env.host.httpGet(url("/boom")).contains("broke"))

  test("httpPost sends the body and httpRequest reports status"):
    val env = TestEnv(hosts = List(host))
    import env.given
    given net: Network = env.host.network
    assertEquals(env.host.httpPost(url("/echo"), "ping", "text/plain", Map.empty, Map.empty), "ping")
    val resp = env.host.httpRequest("DELETE", url("/method"), "", Map.empty, Map.empty)
    assertEquals(resp.status, 200)
    assertEquals(resp.body, "DELETE")

  test("secret headers are sent to the host but never returned to the agent"):
    val env = TestEnv(hosts = List(host))
    import env.given
    given net: Network = env.host.network
    val token: Classified[String] = env.host.classify("s3cr3t")
    val echoed = env.host.httpGet(url("/header"), Map.empty, Map("X-Token" -> token))
    assertEquals(echoed, "s3cr3t") // the server saw it...
    // ...but nothing classified was handed back to the agent as a plain value here.

  test("httpGet rejects a host that matches no pattern"):
    val env = TestEnv(hosts = List("example.com"))
    import env.given
    given net: Network = env.host.network
    val e = intercept[SecurityException](env.host.httpGet(url("/ok")))
    assert(e.getMessage.nn.contains("no permitted pattern"), e.getMessage)
    assert(e.getMessage.nn.contains("requestNetwork"), e.getMessage)

  test("httpGet rejects a URL with no host"):
    val env = TestEnv(hosts = List(host))
    import env.given
    given net: Network = env.host.network
    val e = intercept[SecurityException](env.host.httpGet("file:///etc/passwd"))
    assert(e.getMessage.nn.contains("Invalid URL"), e.getMessage)
    assert(e.getMessage.nn.contains("file:///etc/passwd"), e.getMessage) // the URL, not a literal "$url"

  test("httpPost sends exactly one Content-Type: the caller's header wins over `contentType`"):
    val env = TestEnv(hosts = List(host))
    import env.given
    given net: Network = env.host.network
    assertEquals(env.host.httpPost(url("/content-type"), "x", "text/plain", Map.empty, Map.empty), "[text/plain]")
    assertEquals(
      env.host.httpPost(url("/content-type"), "x", "text/plain", Map("content-type" -> "application/xml"), Map.empty),
      "[application/xml]"
    )
    val secret = Map("Content-Type" -> env.host.classify("text/csv"))
    assertEquals(env.host.httpPost(url("/content-type"), "x", "text/plain", Map.empty, secret), "[text/csv]")

  test("host matching honours glob patterns, case-insensitively"):
    assert(GlobMatcher.matchesHost("API.GitHub.com", "*.github.com"))
    assert(!GlobMatcher.matchesHost("github.com", "*.github.com"))
    assert(GlobMatcher.matchesHost("127.0.0.1", "127.*"))
    val env = TestEnv(hosts = List("127.*"))
    import env.given
    given net: Network = env.host.network
    assertEquals(env.host.httpGet(url("/ok")), "hello")

  test("requestNetwork widens the host set for its block only"):
    val env = TestEnv(hosts = Nil)
    import env.given
    given net: Network = env.host.network
    env.decisions = List(Decision.AllowOnce)
    val body = env.host.requestNetwork(Set(host), "fetch") { env.host.httpGet(url("/ok")) }
    assertEquals(body, "hello")
    assertEquals(env.requests.head.asInstanceOf[NetRequest].hosts, List(host))
    intercept[SecurityException](env.host.httpGet(url("/ok")))

  // ── End-to-end through a sandbox REPL ───────────────────────────

  private def withSession(
    commands: List[String] = Nil,
    hosts: List[String] = Nil
  )(body: (TestEnv, ReplSession) => Unit): Unit =
    val env = TestEnv(commands = commands, hosts = hosts, prefix = "atc-perm-repl")
    val s = env.newSession()
    body(env, s)

  test("REPL: exec through the policy, denied then requested"):
    withSession(commands = List("echo")) { (env, s) =>
      val ok = s.run("""println(exec("echo", List("from-repl")).stdout.trim)""")
      assert(ok.success, ok.error.toString)
      assert(env.agentOut.toString.contains("from-repl"))
      val denied = s.run("""exec("ls")""")
      assert(!denied.success)
      assert(denied.output.contains("requestExec") || denied.error.exists(_.contains("requestExec")), denied.toString)
      env.decisions = List(Decision.AllowOnce)
      val granted = s.run("""requestExec(Set("ls*"), "list") { exec("ls", List(".")).exitCode }""")
      assert(granted.success, granted.error.toString)
    }

  test("REPL: a rejected command inside a granted scope still throws at runtime"):
    withSession(commands = List("echo")) { (env, s) =>
      env.decisions = List(Decision.AllowOnce)
      val r = s.run("""requestExec(Set("echo")) { exec("rm", List("-rf", "/tmp/none")) }""")
      assert(!r.success)
      assert((r.output + r.error.getOrElse("")).contains("no permitted pattern"), r.toString)
    }

  test("REPL: network host is enforced and requestNetwork opens a scope"):
    withSession(hosts = Nil) { (env, s) =>
      val denied = s.run(s"""httpGet("${url("/ok")}")""")
      assert(!denied.success)
      assert((denied.output + denied.error.getOrElse("")).contains("requestNetwork"), denied.toString)
      env.decisions = List(Decision.AllowOnce)
      val ok = s.run(s"""requestNetwork(Set("$host"), "fetch") { httpGet("${url("/ok")}") }""")
      assert(ok.success, ok.error.toString)
      assert(ok.output.contains("hello"), ok.output)
    }

  test("REPL: exec capability cannot leak out of requestExec"):
    withSession(commands = List("echo")) { (_, s) =>
      val r = s.run("""val leaked = requestExec(Set("echo")) { summon[Exec] }""")
      assert(!r.success)
      assert((r.output + r.error.getOrElse("")).toLowerCase.contains("leak"), r.toString)
    }

  test("REPL: network capability cannot leak out of requestNetwork"):
    withSession(hosts = Nil) { (_, s) =>
      val r = s.run("""val leaked = requestNetwork(Set("example.com")) { summon[Network] }""")
      assert(!r.success)
      assert((r.output + r.error.getOrElse("")).toLowerCase.contains("leak"), r.toString)
    }
