package atc

import atc.perms.*
import atc.sandbox.*

/** The three sandbox modes. A mode decides which capabilities the REPL preamble
  * hands the agent, so most of what a mode forbids is a *compile* error; the
  * policy then refuses the same things at run time as defence in depth.
  *
  * | mode      | read | write | exec | network | talk to the user |
  * |-----------|------|-------|------|---------|------------------|
  * | read-only | yes  | no    | no   | no      | yes              |
  * | local     | yes  | yes   | yes  | no      | yes              |
  * | full      | yes  | yes   | yes  | yes     | yes              |
  *
  * The capability *typing* rules behind this (read-only views, `update`
  * methods, escapes, the `Classified.map` contract) live in
  * [[CapabilitySuite]]; here we only check what each mode provides.
  *
  * Each mode gets one host and one session, reused across tests. The installed
  * host is process-global (one sandbox per JVM), so `run` re-installs this
  * mode's host before evaluating. Otherwise a session could pick up another
  * mode's host when its `api` object initialises. */
class ModeSuite extends munit.FunSuite, ReplAssertions:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private val echoCommand = ProcessFixture.command("echo")
  private val echoPattern = ProcessFixture.pattern("echo")

  /** One host + one session for a mode, created on first use. */
  private final class ModeEnv(val mode: Mode):
    val env: TestEnv =
      TestEnv(commands = List(echoPattern), hosts = List("example.com"), prefix = s"atc-mode-${mode.label}")
    env.file("a.txt", "hello")
    env.decisions = List.fill(20)(Decision.AllowOnce)
    lazy val session: ReplSession = env.newSession(mode = mode)
    def run(code: String): ExecutionResult =
      env.activate()
      env.policy.mode = mode
      session.run(code)

  private lazy val readOnly = ModeEnv(Mode.ReadOnly)
  private lazy val local = ModeEnv(Mode.Local)
  private lazy val full = ModeEnv(Mode.Full)
  private def of(m: Mode): ModeEnv = m match
    case Mode.ReadOnly => readOnly
    case Mode.Local => local
    case Mode.Full => full
  private val allModes = List(Mode.ReadOnly, Mode.Local, Mode.Full)

  /** Assert `code` compiles+runs in exactly the modes in `allowed`. */
  private def onlyIn(allowed: Set[Mode], code: String)(using munit.Location): Unit =
    for m <- allModes do
      val r = of(m).run(code)
      if allowed(m) then assert(r.success, s"[${m.label}] expected success for `$code`:\n${r.output}\n${r.error}")
      else assert(!r.success, s"[${m.label}] expected rejection for `$code`, got:\n${r.output}")

  // ── Capability hierarchy ──────────────────────────────────────

  test("full and local system capabilities retain the full io root in their capture types"):
    val rooted =
      """val root: IOCap^ = io
        |val files: FileSystem^{io} = fs
        |val commands: Exec^{io} = ex
        |files.access("a.txt").read().length + commands.hashCode + root.hashCode""".stripMargin
    assertOk(full.run(rooted))
    assertOk(local.run(rooted))
    assertOk(full.run("""val network: Network^{io} = net; network.hashCode"""))

  test("read-only system capabilities retain the read-only io root in their capture types"):
    assertOk(readOnly.run(
      """val root: IOCap^{io.rd} = io
        |val files: FileSystem^{io.rd} = fs
        |files.access("a.txt").read().length + root.hashCode""".stripMargin
    ))
    assertFails(readOnly.run("""val root: IOCap^ = io; root.hashCode"""), "read-only")

  test("the preamble declares one consistent io-rooted system capability hierarchy"):
    val fullChunks = ReplSession.preambleChunks(Mode.Full)
    val localChunks = ReplSession.preambleChunks(Mode.Local)
    val readOnlyChunks = ReplSession.preambleChunks(Mode.ReadOnly)

    for chunks <- List(fullChunks, localChunks) do
      assert(chunks.contains("@assumeSafe given io: (IOCap^) = atc.lib.Runtime.rootIO"))
      assert(chunks.contains("@assumeSafe given fs: (FileSystem^{io}) = atc.lib.Runtime.fileSystem"))
      assert(chunks.contains("@assumeSafe given ex: (Exec^{io}) = atc.lib.Runtime.processes"))
    assert(fullChunks.contains("@assumeSafe given net: (Network^{io}) = atc.lib.Runtime.network"))
    assert(!localChunks.exists(_.contains("given net:")))

    assert(readOnlyChunks.contains("@assumeSafe given io: IOCap = atc.lib.Runtime.rootIO"))
    assert(readOnlyChunks.contains(
      "@assumeSafe given fs: (FileSystem^{io.rd}) = atc.lib.Runtime.readOnlyFileSystem"
    ))
    assert(!readOnlyChunks.exists(chunk => chunk.contains("given ex:") || chunk.contains("given net:")))

  // ── Reading and reporting work everywhere ───────────────────────

  test("reading files works in every mode"):
    onlyIn(allModes.toSet, """read("a.txt")""")
    onlyIn(allModes.toSet, """cat("a.txt")""")
    onlyIn(allModes.toSet, """cat("a.txt", 1, 1)""")
    onlyIn(allModes.toSet, """access("a.txt").read()""") // a bare fs is enough for a handle
    onlyIn(allModes.toSet, """Json.parse("{\"a\": [1, 2.5, \"x\"]}")("a")(1).num""")
    onlyIn(allModes.toSet, """Json.obj("k" -> Json.Str("v"), "n" -> Json.Num(1)).render.length""")
    onlyIn(allModes.toSet, """Json.parse("[1]")(5).isNull""")
    onlyIn(allModes.toSet, """ls(".").size + walk(".").size + grepRecursive(".", "hello").size""")

  test("talking to the user works in every mode"):
    // `user: UserIO^` is handed out by every mode, so the agent can always report.
    onlyIn(allModes.toSet, """println("reporting"); print(""); printf("%d", 1); 1""")
    onlyIn(allModes.toSet, """setTodos(List(Todo("t"))); markTodo("t", TodoStatus.Done); todos.size""")
    onlyIn(allModes.toSet, """ask("q?", List("a", "b")).isDefined""")
    onlyIn(allModes.toSet, """chat("hello").length""")

  test("classified reads and the classified model work in every mode"):
    onlyIn(allModes.toSet, """classify("x").map(_.length)""")
    onlyIn(allModes.toSet, """classifiedChat(classify("x")).toString.length""")
    onlyIn(allModes.toSet, """classify("x").map(classifiedChat).toString.length""")

  // ── Writing: local and full only ────────────────────────────────

  test("writing compiles only where the mode grants a full file system"):
    val rw = Set(Mode.Local, Mode.Full)
    onlyIn(rw, """write("w.txt", "x")""")
    onlyIn(rw, """append("a.txt", "!")""")
    onlyIn(rw, """sed("a.txt", "^(h)ello", "$1i")""")
    // the quoting pair the prompt recommends for literal text (safe mode refuses Regex.quote)
    onlyIn(rw, """sed("a.txt", quote("i!"), quoteReplacement("$!"))""")
    onlyIn(rw, """mkdir("sub")""")
    onlyIn(rw, """access("a.txt").write("x")""")

  test("read-only mode: the ambient fs is read-only, so its entries cannot be mutated"):
    assertFails(readOnly.run("""fs.access("a.txt").write("y")"""), "read-only")
    assertEquals(readOnly.env.contents("a.txt"), "hello")

  test("read-only mode: a rejected write really does not touch the file"):
    assertFails(readOnly.run("""write("a.txt", "clobbered")"""))
    assertEquals(readOnly.env.contents("a.txt"), "hello")

  // ── Commands: local and full only ───────────────────────────────

  test("running commands compiles only in local and full mode"):
    val canExec = Set(Mode.Local, Mode.Full)
    val echo = ujson.write(echoCommand)
    val echoLine = ujson.write(ProcessFixture.command("echo", "hi", "there"))
    val spawned = ujson.write(ProcessFixture.command("echo", "spawned"))
    val pattern = ujson.write(echoPattern)
    onlyIn(canExec, s"""exec($echo, List("hi")).exitCode""")
    onlyIn(canExec, s"""execOutput($echo).length""")
    onlyIn(canExec, s"""exec($echoLine).stdout""") // split like a shell line
    onlyIn(canExec, s"""val p: Process^{ex} = spawn($spawned); p.readUntil("spawned", 5000).length""")
    onlyIn(canExec, """runningProcesses.size""")
    onlyIn(canExec, s"""exec($echo, Seq("a"), ExecOptions(stdin = "")).exitCode""")
    onlyIn(canExec, s"""requestExec(List($pattern)) { exec($echo, Vector("x")).exitCode }""") // Iterable / Seq
    onlyIn(canExec, s"""requestExec(Set($pattern)) { exec($echo, List("x")).exitCode }""")

  test("read-only mode: there is no Exec capability, and the derivation is out of reach"):
    assertFails(readOnly.run("""val x: Exec^ = ex; 1"""))
    assertFails(readOnly.run("""val x: Exec^ = atc.lib.Runtime.processes; 1"""))

  // ── Network: full only ──────────────────────────────────────────

  test("network compiles only in full mode"):
    // Defined but never called, so the suite makes no real request: what is
    // under test is whether the `Network` capability exists in the mode.
    val canNet = Set(Mode.Full)
    onlyIn(canNet, """def get(): String = httpGet("http://example.com"); 1""")
    onlyIn(canNet, """def post(): String = httpPost("http://example.com", "b"); 1""")
    onlyIn(canNet, """def req(): HttpResponse = httpRequest("GET", "http://example.com"); 1""")
    onlyIn(canNet, """val n: Network^{io} = net; 1""")
    onlyIn(canNet, """requestNetwork(Set("example.com")) { 1 }""")

  test("local mode: there is no Network capability, and the derivation is out of reach"):
    assertFails(local.run("""val n: Network^{io} = net; 1"""))
    assertFails(local.run("""def get(): String = httpGet("http://example.com"); 1"""))
    assertFails(local.run("""atc.lib.Runtime.fileSystem(using io)"""), "atc-runtime")
    assertFails(local.run("""atc.lib.Runtime.processes(using io)"""), "atc-runtime")
    assertFails(local.run("""atc.lib.Runtime.network(using io)"""), "atc-runtime")

  // ── requestFiles adapts to the mode ─────────────────────────────

  test("requestFiles grants a file system exactly as capable as the mode's"):
    // The block's `fs` inherits the caller's capture set: writable in local/full,
    // read-only in read-only mode. So the same call site works in every mode
    // for reading, and only where writing is allowed for writing.
    onlyIn(allModes.toSet, """requestFiles("/tmp") { 1 }""")
    onlyIn(Set(Mode.Local, Mode.Full), """requestFiles("/tmp", Access.Write) { write("/tmp/atc-mode.txt", "x"); 1 }""")

  test("read-only mode: requestFiles can still ask for more read access"):
    val outside = TestEnv.outsideDir("outside-secret")
    readOnly.env.decisions = List(Decision.AllowOnce)
    val r = assertOk(readOnly.run(
      s"requestFiles(${readOnly.env.scalaString(outside)}) { read(${readOnly.env.scalaString(outside.resolve("o.txt"))}) }"
    ))
    assert(r.output.contains("outside-secret"), r.output)

  // ── The policy enforces the mode at run time too ────────────────

  test("policy downgrades effective file access to read outside of write modes"):
    val e = TestEnv(prefix = "atc-mode-policy")
    val p = e.host.canonical("a.txt")
    e.policy.mode = Mode.ReadOnly
    assertEquals(e.policy.effective(ScopeId.Base, p).access, Access.Read)
    for m <- List(Mode.Local, Mode.Full) do
      e.policy.mode = m
      assertEquals(e.policy.effective(ScopeId.Base, p).access, Access.Write, s"in ${m.label}")

  test("policy refuses writes, commands and hosts that the mode forbids"):
    val e = TestEnv(commands = List("echo"), hosts = List("example.com"), prefix = "atc-mode-policy2")
    val p = e.host.canonical("a.txt")
    e.policy.mode = Mode.ReadOnly
    intercept[SecurityException](e.host.writeFile(ScopeId.Base, p, "x", append = false))
    assert(!e.policy.commandAllowed(ScopeId.Base, "echo hi"))
    assert(!e.policy.hostAllowed(ScopeId.Base, "example.com"))
    intercept[SecurityException](e.policy.requestFile(ScopeId.Base, p, Access.Write, "why"))
    intercept[SecurityException](e.policy.requestExec(ScopeId.Base, List("ls"), "why"))
    intercept[SecurityException](e.policy.requestNet(ScopeId.Base, List("x.com"), "why"))
    e.policy.mode = Mode.Local
    assert(e.policy.commandAllowed(ScopeId.Base, "echo hi"), "local mode allows commands")
    assert(!e.policy.hostAllowed(ScopeId.Base, "example.com"), "local mode still has no network")
    e.policy.mode = Mode.Full
    assert(e.policy.hostAllowed(ScopeId.Base, "example.com"), "full mode allows the configured host")

  test("the policy summary names the mode"):
    val e = TestEnv(prefix = "atc-mode-summary")
    for m <- allModes do
      e.policy.mode = m
      assert(e.policy.summary.contains(m.label), s"summary should mention ${m.label}")

  // ── A pure Classified.map may still read ────────────────────────

  test("only read-only mode may capture the ambient fs to read inside a pure map"):
    // `Classified.map` admits read-only captures (`->{any.rd}`), and in read-only
    // mode the ambient `fs` is itself read-only, so reading inside a map is
    // allowed. Local/full retain full `fs: FileSystem^{io}`, so even a read
    // through their ambient capability remains excluded from the pure callback.
    onlyIn(Set(Mode.ReadOnly), """classify("x").map(s => read("a.txt").length + s.length)""")
    onlyIn(Set(Mode.ReadOnly), """classify("x").map(s => ls(".").size)""")
    assertFails(readOnly.run("""classify("s").map(s => { println(s); s })"""))

  // ── Mode plumbing: config and command line ──────────────────────

  test("the mode can be set in the config and is validated"):
    assertEquals(atc.config.Config().mode, None)
    val cfg = upickle.default.read[atc.config.Config]("""{ "mode": "readonly" }""")
    assertEquals(cfg.mode, Some("readonly"))
    assertEquals(cfg.mode.map(Mode.parse), Some(Mode.ReadOnly))
    atc.config.Config.validate(cfg) // accepted
    val bad = upickle.default.read[atc.config.Config]("""{ "mode": "sideways" }""")
    val e = intercept[IllegalArgumentException](atc.config.Config.validate(bad))
    assert(e.getMessage.nn.contains("sideways"), e.getMessage)

  test("--mode selects the sandbox mode on the command line"):
    assertEquals(Cli.parse(Nil).mode, None)
    assertEquals(Cli.parse(List("--mode", "readonly")).mode, Some(Mode.ReadOnly))
    assertEquals(Cli.parse(List("--mode", "local")).mode, Some(Mode.Local))
    assertEquals(Cli.parse(List("--mode", "full")).mode, Some(Mode.Full))
    intercept[IllegalArgumentException](Cli.parse(List("--mode", "sideways")))

  test("every mode has a human-readable description naming it"):
    for m <- allModes do
      val d = m.describe
      assert(d.contains(m.label), s"description of ${m.label} should name it: $d")

  // ── The Mode value itself ───────────────────────────────────────

  test("Mode.parse accepts the documented spellings and rejects others"):
    for s <- List("readonly", "read-only", "ro", "read", "READONLY", " ReadOnly ") do
      assertEquals(Mode.parse(s), Mode.ReadOnly, s)
    for s <- List("local", "rw", "LOCAL") do assertEquals(Mode.parse(s), Mode.Local, s)
    for s <- List("full", "all", "Full") do assertEquals(Mode.parse(s), Mode.Full, s)
    intercept[IllegalArgumentException](Mode.parse("nonsense"))

  test("cycling visits every mode and the flags match the table"):
    assertEquals(Mode.ReadOnly.next, Mode.Local)
    assertEquals(Mode.Local.next, Mode.Full)
    assertEquals(Mode.Full.next, Mode.ReadOnly)
    assertEquals(allModes.map(_.allowsWrite), List(false, true, true))
    assertEquals(allModes.map(_.allowsExec), List(false, true, true))
    assertEquals(allModes.map(_.allowsNetwork), List(false, false, true))
