package atc

import atc.host.*
import atc.lib.{Classified, Exec, ExecOptions, FileSystem, Network}
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
  val echoRequests = java.util.concurrent.atomic.AtomicInteger(0)

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
    server.createContext(
      "/echo",
      handler(
        200,
        ex => {
          echoRequests.incrementAndGet()
          String(ex.getRequestBody.nn.readAllBytes(), "UTF-8")
        }
      )
    )
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
    assertEquals(env.commandsWrapped, 2) // both ran inside the clock-pausing hook

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
    val r = env.host.exec("pwd", Nil, env.root.resolve("sub").toString)
    assert(r.stdout.trim.endsWith("/sub"), r.stdout)

  test("exec rejects a working directory the agent cannot read"):
    val env = TestEnv(commands = List("pwd"))
    val outside = TestEnv.outsideDir()
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    val e = intercept[SecurityException](env.host.exec("pwd", Nil, outside.toString))
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
    val e = intercept[SecurityException](env.host.exec("pwd", Nil, secrets))
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
      env.host.exec("pwd", Nil, outside.toString).stdout.trim
    }
    assertEquals(out, outside.toString)
    assertEquals(env.requests.size, 1)
    // the grant was for the block only
    intercept[SecurityException](env.host.exec("pwd", Nil, outside.toString))

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
    val e = intercept[RuntimeException](env.host.exec("sleep", List("30"), ExecOptions(timeoutMs = 150L)))
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
    env.decisions = List(Decision.Deny)
    var ran = false
    intercept[SecurityException](env.host.requestExec(Set("rm")) { ran = true })
    assert(!ran)

  test("a process spawned in a requestExec block cannot be driven after the block closes"):
    // A one-time grant must not leave a drivable process handle behind: the handle
    // carries its spawning scope and is refused once the block has closed, and the
    // base capability does not list it.
    val env = TestEnv(commands = List("cat"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    env.decisions = List(Decision.AllowOnce)
    val (handle, visibleInside) = env.host.requestExec(Set("cat"), "interactive") {
      val p = env.host.spawn("cat")
      (p, env.host.runningProcesses.exists(_.id == p.id)) // the block's own scope sees it
    }
    assert(visibleInside)
    assertEquals(env.policy.openScopeCount, 0)
    assert(!env.host.runningProcesses.exists(_.id == handle.id)) // the base scope does not
    intercept[SecurityException](handle.sendLine("x"))
    intercept[SecurityException](handle.read())
    intercept[SecurityException](handle.waitFor(10))
    intercept[SecurityException](handle.kill())
    env.host.killProcesses() // host-internal cleanup still works

  test("a process spawned on the base scope stays usable for the session"):
    val env = TestEnv(commands = List("cat"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    val cat = env.host.spawn("cat")
    cat.sendLine("hi")
    assertEquals(cat.readUntil("hi\n", 5000), "hi\n")
    assert(env.host.runningProcesses.exists(_.id == cat.id))
    env.host.killProcesses()

  // ── Network, at the host level ──────────────────────────────────

  test("httpGet returns the body when the host is allowed"):
    val env = TestEnv(hosts = List(host))
    import env.given
    given net: Network = env.host.network
    assertEquals(env.host.httpGet(url("/ok")), "hello")

  test("httpGet/httpPost throw on an HTTP error with the status and the body; httpRequest reports it raw"):
    val env = TestEnv(hosts = List(host))
    import env.given
    given net: Network = env.host.network
    val e = intercept[RuntimeException](env.host.httpGet(url("/not-found")))
    assert(e.getMessage.nn.contains("HTTP 404") && e.getMessage.nn.contains("not found"), e.getMessage)
    val e2 = intercept[RuntimeException](env.host.httpPost(url("/boom"), "x"))
    assert(e2.getMessage.nn.contains("HTTP 500") && e2.getMessage.nn.contains("broke"), e2.getMessage)
    val raw = env.host.httpRequest("GET", url("/not-found"))
    assertEquals(raw.status, 404)
    assert(raw.body.contains("not found"))
    // the new overloads without secretHeaders
    assertEquals(env.host.httpPost(url("/echo"), "ping", "text/plain", Map("X-A" -> "1")), "ping")
    assertEquals(env.host.httpRequest("POST", url("/echo"), "pong", Map("X-A" -> "1")).body, "pong")
    // the classified POST stays status-blind: no throw, the body stays classified
    val c = env.host.httpPostClassified(url("/not-found"), env.host.classify("secret"))
    assertEquals(c.toString, "Classified(***)")

  test("exec splits a command line like a shell, honouring quotes, but runs no shell"):
    val env = TestEnv(commands = List("echo", "cat", "sleep", "false"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    assertEquals(env.host.exec("echo hello   world").stdout.trim, "hello world")
    assertEquals(env.host.exec("""echo 'a b' "c \"d\" e" f\ g""").stdout.trim, "a b c \"d\" e f g")
    assertEquals(env.host.exec("echo", List("x", "y z")).stdout.trim, "x y z") // args stay verbatim
    assertEquals(env.host.exec("echo a | cat").stdout.trim, "a") // a pipe is part of the grammar
    val e = intercept[IllegalArgumentException](env.host.exec("echo a && echo b"))
    assert(e.getMessage.nn.contains("no shell"), e.getMessage)
    intercept[IllegalArgumentException](env.host.exec("echo $(whoami)"))
    assertEquals(env.host.exec("echo 'a | b'").stdout.trim, "a | b") // quoted: literal
    intercept[IllegalArgumentException](env.host.exec("   "))

  test("pipelines: stages are connected, checked one by one, exit code pipefail-style, stderr labelled"):
    val env = TestEnv(commands = List("echo", "cat", "sort", "tr", "false", "ls"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    assertEquals(env.host.exec("echo hello | tr a-z A-Z").stdout.trim, "HELLO")
    assertEquals(env.host.exec("echo 'c\nb\na' | sort | cat").stdout, "a\nb\nc\n")
    assertEquals(env.host.exec("false | cat").exitCode, 1) // pipefail: the failing stage wins
    val err = env.host.exec("ls /definitely/not/here | cat")
    assert(err.exitCode != 0)
    assert(err.stderr.startsWith("[stage 1: ls /definitely/not/here]\n"), err.stderr)
    val e = intercept[SecurityException](env.host.exec("echo hi | sort | head"))
    assert(
      e.getMessage.nn.contains("'head'") && e.getMessage.nn.contains("""requestExec(Set("head *")"""),
      e.getMessage
    )
    assertEquals(env.commandsWrapped, 4) // each pipeline ran inside the clock-pausing hook once
    val denying = TestEnv(commands = List("echo", "cat"), denyCommands = List("cat"))
    val d = intercept[SecurityException] {
      given Exec = denying.host.processes
      given FileSystem = denying.host.fileSystem
      denying.host.exec("echo hi | cat")
    }
    assert(d.getMessage.nn.contains("denyCommands"), d.getMessage)

  test("an allowed command can read a classified file (the command grant is the user's decision)"):
    // The classified boundary holds for the file API; a granted command is
    // trusted with whatever it can read — that is what granting it means.
    val env = TestEnv(TestEnv.withSecrets, commands = List("cat"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    env.file("secrets/key.txt", "s3cret")
    intercept[SecurityException](env.host.read("secrets/key.txt"))
    assertEquals(env.host.exec("cat", List("secrets/key.txt")).stdout, "s3cret")

  test("redirections are file operations: checked like read/write, streamed, classified refused"):
    val env = TestEnv(TestEnv.withSecrets, commands = List("echo", "cat", "ls"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    env.file("in.txt", "from a file\n")
    assertEquals(env.host.exec("cat < in.txt").stdout, "from a file\n")
    env.host.exec("echo first > out/o.txt") // parent directories are created, like write
    env.host.exec("echo second >> out/o.txt")
    assertEquals(env.contents("out/o.txt"), "first\nsecond\n")
    assertEquals(env.host.exec("cat < in.txt > out/copy.txt").stdout, "") // streamed to the file, not captured
    assertEquals(env.contents("out/copy.txt"), "from a file\n")
    env.file("secrets/key.txt", "s3cret")
    val e = intercept[SecurityException](env.host.exec("cat < secrets/key.txt"))
    assert(e.getMessage.nn.contains("classified"), e.getMessage)
    intercept[SecurityException](env.host.exec("echo leak > secrets/out.txt"))
    assert(!Files.exists(env.root.resolve("secrets/out.txt")))
    val merged = env.host.exec("ls /definitely/not/here 2>&1 | cat") // 2>&1 sends that stage's stderr down the pipe
    assert(merged.stdout.nonEmpty && merged.stderr.isEmpty, merged.toString)
    intercept[IllegalArgumentException](env.host.exec("cat < in.txt", Nil, ExecOptions(stdin = "x"))) // two inputs
    intercept[IllegalArgumentException](env.host.exec("echo a | cat", List("x"))) // args need one program

  test("spawn: talk to a process while it runs, read what it says, and it is reported to the user"):
    val env = TestEnv(commands = List("cat", "sort", "sleep", "echo"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    val cat = env.host.spawn("cat")
    assert(cat.isAlive && cat.exitCode.isEmpty)
    assertEquals(cat.read(), "") // nothing yet, and read never blocks
    cat.sendLine("hello")
    assertEquals(cat.readUntil("hello\n", 5000), "hello\n")
    cat.send("a b ")
    cat.send("c\n")
    assertEquals(cat.readUntil("c\n", 5000), "a b c\n")
    assertEquals(env.host.runningProcesses.map(_.id), List(cat.id))
    cat.closeStdin() // EOF: cat exits
    val r = cat.waitFor(5000)
    assertEquals(r.map(_.exitCode), Some(0))
    assert(!cat.isAlive && cat.exitCode.contains(0))
    assertEquals(env.host.runningProcesses, Nil)
    Thread.sleep(100) // the exit watcher reports asynchronously
    assertEquals(
      env.processEvents.toList,
      List(
        s"p${cat.id} started: cat",
        s"p${cat.id} < hello\n",
        s"p${cat.id} < a b ",
        s"p${cat.id} < c\n",
        s"p${cat.id} exited 0"
      ),
    )
    // a process that only answers at EOF (sort), and a pipeline as a process
    val sorter = env.host.spawn("sort | cat")
    sorter.send("b\na\n")
    sorter.closeStdin()
    assertEquals(sorter.readUntil("(?s)a\nb\n", 5000), "a\nb\n")
    assertEquals(sorter.waitFor(5000).map(_.exitCode), Some(0))
    assertEquals(cat.toString, s"Process(p${cat.id}, \"cat\", exited 0)")

  test("spawn: waits time out with the output so far, kill works, the count is bounded, the session end kills all"):
    val env = TestEnv(commands = List("cat", "sleep", "echo"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    val sleeper = env.host.spawn("sleep 30")
    val e = intercept[RuntimeException](sleeper.readUntil("never", 300))
    assert(e.getMessage.nn.contains("timed out"), e.getMessage)
    assertEquals(sleeper.waitFor(100), None)
    sleeper.kill()
    assert(sleeper.waitFor(5000).isDefined && !sleeper.isAlive)
    val cat = env.host.spawn("cat")
    cat.send("partial")
    cat.closeStdin()
    Thread.sleep(200)
    val e2 = intercept[RuntimeException](cat.readUntil("never", 2000)) // exited before the match
    assert(e2.getMessage.nn.contains("exited") && e2.getMessage.nn.contains("partial"), e2.getMessage)
    assertEquals(cat.read(), "partial") // the unread output is still there
    val many = (1 to Host.MaxProcesses).map(_ => env.host.spawn("sleep 30"))
    val e3 = intercept[IllegalStateException](env.host.spawn("sleep 30"))
    assert(e3.getMessage.nn.contains("kill()"), e3.getMessage)
    assertEquals(env.host.runningProcesses.size, Host.MaxProcesses)
    assertEquals(env.host.killProcess("p1"), "no running process 'p1' (see /ps)") // p1 (the sleeper) is gone
    assert(env.host.killProcess(s"p${many.head.id}").startsWith("killed p"))
    assert(env.host.processSummary.linesIterator.size == Host.MaxProcesses - 1)
    env.host.killProcesses() // what the app does when the session ends
    assert(many.forall(p => p.waitFor(5000).isDefined))
    assertEquals(env.host.runningProcesses, Nil)
    assertEquals(env.host.processSummary, "no process is running")
    intercept[SecurityException](env.host.spawn("python3 -i")) // same checks as exec

  test("execOutput throws on a non-zero exit; exec reports it"):
    val env = TestEnv(commands = List("echo", "cat", "sleep", "false"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    assertEquals(env.host.exec("false").exitCode, 1)
    val e = intercept[RuntimeException](env.host.execOutput("false"))
    assert(e.getMessage.nn.contains("exited with 1"), e.getMessage)
    assertEquals(env.host.execOutput("echo ok").trim, "ok")

  test("ExecOptions: stdin is fed to the command and closed, a timeout quotes the output so far"):
    val env = TestEnv(commands = List("echo", "cat", "sleep", "false"))
    import env.given
    given ex: Exec = env.host.processes
    given fs: FileSystem = env.host.fileSystem
    assertEquals(env.host.exec("cat", Nil, ExecOptions(stdin = "fed\nin")).stdout, "fed\nin")
    assertEquals(env.host.exec("cat").stdout, "") // stdin closed: EOF at once, no hang
    val e = intercept[RuntimeException](env.host.exec("sleep", List("5"), ExecOptions(timeoutMs = 300)))
    assert(e.getMessage.nn.contains("timed out") && e.getMessage.nn.contains("ExecOptions"), e.getMessage)
    val r = env.host.exec("echo", List("hi"), ExecOptions(workingDir = env.root.toString, timeoutMs = 10_000))
    assertEquals(r.stdout.trim, "hi")

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

  test("a failed classified secret header aborts the request without leaking the failure bit"):
    val env = TestEnv(hosts = List(host))
    import env.given
    given net: Network = env.host.network
    env.clearOutput()
    val before = echoRequests.get()
    val failed = env.host.classify("s3cr3t").map(s => throw RuntimeException(s"boom $s"))
    // The request is NOT sent with the header dropped: a request missing its auth
    // header returns a different response (a 401 vs a 200), itself signalling the
    // failure. The agent gets a generic error (no secret, no which-header); the
    // user a sanitized note; the server sees nothing.
    val e = intercept[SecurityException](env.host.httpGet(url("/header"), Map.empty, Map("X-Token" -> failed)))
    assert(e.getMessage.nn.contains("could not be computed"), e.getMessage)
    assert(!e.getMessage.nn.contains("s3cr3t"), e.getMessage)
    assertEquals(echoRequests.get(), before) // no request went out
    assert(env.userOut.toString.contains("failed computation"), env.userOut.toString)
    assert(!env.agentOut.toString.contains("failed computation"), env.agentOut.toString)

  test("httpPostClassified with a failed body makes no request and returns the failure unchanged"):
    val env = TestEnv(hosts = List(host))
    import env.given
    given net: Network = env.host.network
    val before = echoRequests.get()
    val failed = env.host.classify("secret").map(s => throw RuntimeException(s"boom $s"))
    val r = env.host.httpPostClassified(url("/echo"), failed)
    assert(ClassifiedImpl.unwrap(r).isFailure)
    assertEquals(echoRequests.get(), before) // no request was sent

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

  test("denyHosts cannot be dodged by an equivalent host spelling"):
    val env = TestEnv(hosts = List("*"), denyHosts = List("169.254.169.254", "evil.com"))
    import env.given
    given net: Network = env.host.network
    // a trailing dot names the same host but is not the same string
    val e1 = intercept[SecurityException](env.host.httpGet("http://EVIL.COM./x"))
    assert(e1.getMessage.nn.contains("denyHosts"), e1.getMessage)
    // a single decimal number is an IPv4 literal the JDK resolves — 2852039166 = 169.254.169.254
    val e2 = intercept[SecurityException](env.host.httpGet("http://2852039166/latest/meta-data"))
    assert(e2.getMessage.nn.contains("denyHosts"), e2.getMessage)

  test("normalizeHost: case, trailing dot, numeric IP literals; hostnames untouched"):
    assertEquals(Host.normalizeHost("Example.COM"), "example.com")
    assertEquals(Host.normalizeHost("evil.com."), "evil.com")
    assertEquals(Host.normalizeHost("2852039166"), "169.254.169.254")
    assertEquals(Host.normalizeHost("127.1"), "127.0.0.1")
    assertEquals(Host.normalizeHost("127.0.0.1"), "127.0.0.1")
    assertEquals(Host.normalizeHost("example.com"), "example.com")
    assertEquals(Host.normalizeHost("999.1.2.3"), "999.1.2.3") // out of range: not a literal
    assertEquals(Host.normalizeHost(""), "")

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
