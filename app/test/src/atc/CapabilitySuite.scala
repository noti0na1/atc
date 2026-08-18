package atc

import atc.perms.*
import atc.sandbox.*

/** The capability *type system* as agent code sees it, exercised through a real
  * sandbox REPL in full mode (where every capability exists, so what is rejected
  * is rejected by the types, not by a missing given).
  *
  * The rules under test:
  *
  *  - a bare capability type is the **read-only** view, `^` the full one, and
  *    only the full one reaches the `update` methods (writes);
  *  - `fs`/`ex`/`net` derive from `IOCap`, and only a full `IOCap^` yields a
  *    writing/executing/networking one;
  *  - talking to the user is a **separate** capability, `UserIO`, which neither
  *    substitutes for `IOCap` nor is substituted by it;
  *  - a capability granted by a `request*` block cannot escape that block;
  *  - capabilities cannot be forged, and the sandbox's injection point is
  *    unreachable;
  *  - `Classified.map` admits read-only captures only, so no outward channel
  *    (print, ask, chat, write, exec, network) can run on confidential data.
  *
  * Mode-specific behaviour (what each mode hands out) lives in [[ModeSuite]]. */
class CapabilitySuite extends munit.FunSuite, ReplAssertions:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private val env = TestEnv(commands = List("echo"), hosts = List("example.com"), prefix = "atc-cap")
  private lazy val session: ReplSession = env.newSession(mode = Mode.Full)
  private def run(code: String): ExecutionResult = { env.activate(); session.run(code) }

  override def beforeAll(): Unit =
    env.file("a.txt", "content")
    env.decisions = List.fill(20)(Decision.AllowOnce)
    session // force the session before the first test

  // ── Read-only vs full views ─────────────────────────────────────

  test("a full capability widens to its read-only view"):
    assertOk(run("""val ro: IOCap^{io.rd} = io; val rofs: FileSystem^{io.rd} = readOnlyFileSystem; 1"""))

  test("reading works through either view"):
    assertOk(run("""read("a.txt").length + readOnlyFileSystem.access("a.txt").read().length"""))

  test("a read-only FileEntry cannot be mutated"):
    // Every `update def` of FileEntry is unreachable through a read-only file system.
    for op <- List("""write("x")""", """append("x")""", "delete()", "mkdir()") do
      assertFails(run(s"""readOnlyFileSystem.access("a.txt").$op"""), "read-only")

  test("a read-only FileSystem cannot be used for the writing path helpers"):
    for call <- List(
        """write("a.txt", "x")""",
        """writeBytes("a.txt", Array[Byte](1))""",
        """replace("a.txt", "content", "x")""",
        """append("a.txt", "x")""",
        """mkdir("d")""",
        """delete("a.txt")""",
        """writeClassified("a.txt", classify("x"))""",
      )
    do assertFails(run(s"""$call(using readOnlyFileSystem)"""))

  test("a bare FileSystem parameter is read-only; FileSystem^ can write"):
    assertFails(run("""def h(using fs: FileSystem) = write("a.txt", "x"); h(using fs)"""))
    assertOk(run("""def h2(using fs: FileSystem^) = write("a.txt", "x"); h2(using fs)"""))

  test("access(...) needs a full file system, fs.access(...) mirrors the view"):
    assertOk(run("""access("a.txt").read().length"""))
    assertFails(run("""access("a.txt")(using readOnlyFileSystem)"""))

  // ── Deriving capabilities from `io` ─────────────────────────────

  test("a read-only io cannot derive a writing file system, Exec or Network"):
    assertFails(run("""val ro: IOCap^{io.rd} = io; processes(using ro)"""), "read-only")
    assertFails(run("""val ro: IOCap^{io.rd} = io; network(using ro)"""), "read-only")
    assertFails(run("""val ro: IOCap^{io.rd} = io; fileSystem(using ro)"""), "read-only")

  test("a read-only io can still derive the read-only file system"):
    assertOk(run("""val ro: IOCap^{io.rd} = io; readOnlyFileSystem(using ro).access("a.txt").read().length"""))

  test("a file system derived from a read-only io cannot write"):
    assertFails(run("""val ro: IOCap^{io.rd} = io; write("a.txt", "x")(using readOnlyFileSystem(using ro))"""))

  test("Exec and Network have no read-only view: a bare Exec is already full"):
    // They are plain exclusive capabilities (not `Stateful`), because there is no
    // meaningful "observe without acting" for running a command or a request.
    assertFails(run("""val e2: Exec^{ex.rd} = ex"""))
    assertFails(run("""val n2: Network^{net.rd} = net"""))
    assertOk(run("""def f(using x: Exec) = exec("echo", List("hi")).exitCode; f(using ex)"""))

  // ── UserIO: talking to the user is a separate capability ────────

  test("the user-facing effects need a full UserIO"):
    for call <- List(
        """println("x")""",
        """print("x")""",
        """printf("%d", 1)""",
        """ask("q?")""",
        """setTodos(Nil)""",
        """markTodo("t", TodoStatus.Done)""",
        """chat("hi")""",
      )
    do assertFails(run(s"""val ru: UserIO^{user.rd} = user; $call(using ru)"""))

  test("reading the TODO list needs only a read-only UserIO"):
    assertOk(run("""val ru: UserIO^{user.rd} = user; todos(using ru).size"""))

  test("IOCap does not substitute for UserIO, and UserIO does not substitute for IOCap"):
    assertFails(run("""println("x")(using io)"""))
    assertFails(run("""fileSystem(using user)"""))
    assertFails(run("""processes(using user)"""))
    assertFails(run("""network(using user)"""))

  test("the user capability is what request* blocks consume, not io"):
    // `request*` prompt the user, so they take UserIO^. That is also what keeps
    // them out of a pure `Classified.map` (see the capture-contract section).
    assertOk(run(s"""requestFiles("${env.root}") { read("a.txt").length }"""))
    assertFails(run(s"""val ru: UserIO^{user.rd} = user; requestFiles("${env.root}")(using ru, fs) { 1 }"""))

  // ── Granted capabilities cannot escape their block ──────────────

  test("requestFiles opens a permission scope, prompts, and closes it again"):
    val outside = TestEnv.outsideDir("outside-data")
    env.requests.clear()
    val r = assertOk(run(s"""requestFiles("$outside", Access.Read, "why") { read("$outside/o.txt") }"""))
    assert(r.output.contains("outside-data"), r.output)
    assert(env.requests.exists { case f: FileRequest => f.reason == "why"; case _ => false }, env.requests.toString)
    assertEquals(env.policy.openScopeCount, 0)
    // ...and the grant is gone once the block ends (it was "allow once").
    assertFails(run(s"""read("$outside/o.txt")"""))

  test("a FileEntry from a requestFiles block cannot escape it"):
    assertFails(run("""val leaked = requestFiles("/tmp") { access("/tmp") }"""), "leak")
    assertFails(run("""val e: FileEntry^{fs} = requestFiles("/tmp") { fs2 ?=> fs2.access("/tmp/x") }"""), "leak")

  test("a closure over the block's file system cannot escape it"):
    // `read` needs only read-only access, so this surfaces as the REPL's
    // "needs an explicit type" rejection rather than the leak message. Either
    // way the capability does not cross the block boundary.
    assertFails(run("""val fn: () -> String = requestFiles("/tmp") { () => read("/tmp/x") }"""))
    assertFails(run("""val fn2 = requestFiles("/tmp") { fs2 ?=> () => read("/tmp/x")(using fs2) }"""))

  test("requestFiles hands the block a file system as capable as the caller's"):
    // Full mode: the caller's `fs` is full, so the block's is too and may write.
    assertOk(run(s"""requestFiles("${env.root}", Access.Write) { write("granted.txt", "ok"); read("granted.txt") }"""))

  // ── Capabilities cannot be forged, internals are unreachable ────

  test("capability implementations cannot be constructed"):
    assertFails(run("""new atc.host.FileSystemImpl(0, null)"""))
    assertFails(run("""new atc.lib.IOCap()"""))
    assertFails(run("""new atc.lib.UserIO()"""))

  test("the sandbox injection point is unreachable"):
    for name <- List("current", "rootIO", "rootUser", "install(null)") do
      assertFails(run(s"""atc.lib.Runtime.$name"""), "atc-runtime")

  // ── Classified.map: the capture contract ────────────────────────

  test("map admits pure computation and local mutable state"):
    assertOk(run("""classify("x").map(s => s.length * 2)"""))
    assertOk(run("""classify("x").map(s => { var n = 0; n += s.length; n })"""))
    assertOk(run("""classify("x").map(s => { val a = Array(1, 2); a(0) + s.length })"""))

  test("map rejects every outward channel"):
    // The security property: nothing that leaves the process (or reaches the
    // user, or the normal model) can run on confidential data.
    val channels = List(
      "print to the user" -> """classify("s").map(s => { println(s); s })""",
      "printf" -> """classify("s").map(s => { printf("%s", s); s })""",
      "ask the user" -> """classify("s").map(s => ask(s))""",
      "set todos" -> """classify("s").map(s => { setTodos(List(Todo(s))); s })""",
      "mark todo" -> """classify("s").map(s => { markTodo(s, TodoStatus.Done); s })""",
      "normal model" -> """classify("s").map(s => chat(s))""",
      "write a file" -> """classify("s").map(s => { write("leak.txt", s); s })""",
      "targeted replace" -> """classify("s").map(s => { replace("a.txt", "content", s); s })""",
      "write bytes" -> """classify("s").map(s => { writeBytes("leak.bin", s.getBytes); s })""",
      "append to a file" -> """classify("s").map(s => { append("leak.txt", s); s })""",
      "delete a file" -> """classify("s").map(s => { delete(s); s })""",
      "write classified" -> """classify("s").map(s => { writeClassified(s, classify(s)); s })""",
      "run a command" -> """classify("s").map(s => exec("echo", List(s)).stdout)""",
      "run a command (execOutput)" -> """classify("s").map(s => execOutput(s))""",
      "http GET" -> """classify("s").map(s => httpGet(s))""",
      "http POST" -> """classify("s").map(s => httpPost("http://example.com", s))""",
      "ask for permissions" -> """classify("s").map(s => requestFiles(s) { read(s) })""",
      "take the root capability" -> """classify("s").map(s => { println(s)(using atc.lib.Runtime.rootUser); s })""",
    )
    for (what, code) <- channels do
      val r = run(code)
      assert(!r.success, s"$what leaked into Classified.map:\n${r.output}")

  test("map rejects outward channels wrapped in a def (eta-expansion)"):
    // Regression guard for the default-argument leak: an API method with a
    // defaulted parameter used to eta-expand to a *pure* function here, which
    // let `exec`/`httpGet`/... run on the secret. The API now uses overloads.
    val wrappers = List(
      """def w1(s: String) = exec("echo", List(s)).stdout""" -> "w1",
      """def w2(s: String) = execOutput(s)""" -> "w2",
      """def w3(s: String) = httpGet(s)""" -> "w3",
      """def w4(s: String) = { println(s); s }""" -> "w4",
      """def w5(s: String) = ask(s)""" -> "w5",
      """def w6(s: String) = chat(s)""" -> "w6",
      """def w7(s: String) = grepRecursive(".", s).size""" -> "w7",
    )
    for (definition, name) <- wrappers do
      assertOk(run(definition))
      val r = run(s"""classify("s").map($name)""")
      assert(!r.success, s"$name leaked into Classified.map:\n${r.output}")

  test("no capability-taking API method declares a default argument"):
    // Structural guard behind the test above: a default argument on a method
    // that takes a capability re-opens the eta-expansion leak, so the API uses
    // telescoping overloads instead. (Data types like `Todo` may have defaults.)
    val defaults = classOf[atc.lib.Interface].getMethods.map(_.getName).filter(_.contains("$default$")).distinct
    assertEquals(defaults.toList.sorted, Nil, "atc.lib.Interface must not use default arguments")

  test("chat(Classified) needs no capability at all, so it is usable on classified data"):
    // The safe-model overload is the one sanctioned way to ask about a secret:
    // it takes no capability, so it also passes the `map` capture contract.
    assertOk(run("""classify("x").map(c => 1)"""))
    assertOk(run("""val answer: Classified[String] = chat(classify("topsecret")); answer.toString"""))
    assert(env.safeChats.contains("topsecret"), env.safeChats.toString)
