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

  private val echoCommand = ProcessFixture.command("echo")
  private val env =
    TestEnv(commands = List(ProcessFixture.pattern("echo")), hosts = List("example.com"), prefix = "atc-cap")
  private lazy val session: ReplSession = env.newSession(mode = Mode.Full)
  private def run(code: String): ExecutionResult = { env.activate(); session.run(code) }

  override def beforeAll(): Unit =
    env.file("a.txt", "content")
    env.decisions = List.fill(20)(Decision.AllowOnce)
    session // force the session before the first test

  // ── Read-only vs full views ─────────────────────────────────────

  test("a full capability widens to its read-only view"):
    assertOk(run("""val ro: IOCap^{io.rd} = io; val rofs: FileSystem^{fs.rd} = fs; 1"""))

  test("reading works through either view"):
    assertOk(run("""val rofs: FileSystem^{fs.rd} = fs; read("a.txt").length + rofs.access("a.txt").read().length"""))

  test("a read-only FileEntry cannot be mutated"):
    // Every `update def` of FileEntry is unreachable through a read-only file system.
    for op <- List("""write("x")""", """append("x")""", "delete()", "mkdir()") do
      assertFails(run(s"""val rofs: FileSystem^{fs.rd} = fs; rofs.access("a.txt").$op"""), "read-only")

  test("a read-only FileSystem cannot be used for the writing path helpers"):
    for call <- List(
        """write("a.txt", "x")""",
        """writeBytes("a.txt", Array[Byte](1))""",
        """sed("a.txt", "content", "x")""",
        """move("a.txt", "b.txt")""",
        """copy("a.txt", "b.txt")""",
        """replaceLines("a.txt", 1, 1, "x")""",
        """insertLines("a.txt", 1, "x")""",
        """append("a.txt", "x")""",
        """mkdir("d")""",
        """delete("a.txt")""",
        """writeClassified("a.txt", classify("x"))""",
      )
    do assertFails(run(s"""val rofs: FileSystem^{fs.rd} = fs; $call(using rofs)"""))

  test("a bare FileSystem parameter is read-only; FileSystem^ can write"):
    assertFails(run("""def h(using fs: FileSystem) = write("a.txt", "x"); h(using fs)"""))
    assertOk(run("""def h2(using fs: FileSystem^) = write("a.txt", "x"); h2(using fs)"""))

  test("access(...) mirrors the view of the file system it is given"):
    assertOk(run("""access("a.txt").read().length"""))
    assertOk(run("""val rofs: FileSystem^{fs.rd} = fs; access("a.txt")(using rofs).read().length"""))
    assertFails(run("""val rofs: FileSystem^{fs.rd} = fs; access("a.txt")(using rofs).write("x")"""), "read-only")

  test("every exec, execOutput and spawn overload requires a full file system as well as a full Exec"):
    val echo = ujson.write(echoCommand)
    val calls = List(
      "ExecCommand" -> s"exec($echo)",
      "ExecArgs" -> s"exec($echo, Nil)",
      "ExecWorkingDir" -> s"exec($echo, Nil, \".\")",
      "ExecOptions" -> s"exec($echo, Nil, ExecOptions())",
      "ExecOutputCommand" -> s"execOutput($echo)",
      "ExecOutputArgs" -> s"execOutput($echo, Nil)",
      "ExecOutputOptions" -> s"execOutput($echo, Nil, ExecOptions())",
      "SpawnCommand" -> s"spawn($echo)",
      "SpawnOptions" -> s"spawn($echo, ExecOptions())",
    )
    for (name, call) <- calls do
      assertFails(
        run(s"""def reject$name(rofs: FileSystem): Unit = { $call(using ex, rofs); () }; 1"""),
        "read-only",
      )
      assertOk(run(s"""def allow$name(fullFs: FileSystem^): Unit = { $call(using ex, fullFs); () }; 1"""))

  test("a read-only file-system view cannot write through command redirection"):
    val target = env.root.resolve("read-only-redirection.txt").nn
    val command = s"${ProcessFixture.command("echo", "blocked")} > ${ProcessFixture.line(target.toString)}"
    assertFails(
      run(s"""val rofs: FileSystem^{fs.rd} = fs; exec(${ujson.write(command)})(using ex, rofs)"""),
      "read-only",
    )
    assert(!java.nio.file.Files.exists(target))

  // ── Deriving capabilities from `io` ─────────────────────────────

  test("the agent-facing API consumes leaf capabilities, never the io root directly"):
    val consumers = classOf[atc.lib.Interface].getMethods.toList
      .filter(_.getParameterTypes.contains(classOf[atc.lib.IOCap]))
      .map(_.getName)
      .distinct
      .sorted
    assertEquals(consumers, Nil)

  test("the derivations are the sandbox's, not the agent's: unreachable from agent code"):
    // The preamble builds `fs`/`ex`/`net` from `io` through `Runtime`; agent code
    // cannot name them (validator + @rejectSafe), so nothing it can call turns a
    // read-only `io` into a full capability.
    assertFails(run("""atc.lib.Runtime.fileSystem(using io)"""), "atc-runtime")
    assertFails(run("""atc.lib.Runtime.processes(using io)"""), "atc-runtime")
    assertFails(run("""atc.lib.Runtime.network(using io)"""), "atc-runtime")
    assertFails(run("""atc.lib.Runtime.readOnlyFileSystem(using io)"""), "atc-runtime")
    assertFails(run("""val ro: IOCap^{io.rd} = io; fileSystem(using ro)""")) // no such API method
    assertFails(run("""readOnlyFileSystem""")) // nor this one: `val ro: FileSystem^{fs.rd} = fs` is the idiom

  test("Exec and Network have no read-only view at all, so commands and requests stay out of Classified.map"):
    // Unlike the file-system capabilities (Stateful), Exec/Network are exclusive-only:
    // the compiler refuses to form `ex.rd` / `net.rd`, and a bare `Exec`/`Network`
    // cannot alias a capability either. Commands demand `Exec^` plus `FileSystem^`,
    // while network methods demand `Network^`.
    assertFails(run("""val rex: Exec^{ex.rd} = ex; 1"""), "cannot flow into capture set")
    assertFails(run("""val rn: Network^{net.rd} = net; 1"""), "cannot flow into capture set")
    assertOk(run("""val e: Exec = ex; 1""")) // a bare `Exec` type is `Exec^` (full), not a read-only view...
    assertFails(run(
      """val e2: Exec = ex; classify("s").map(s => exec("echo", List(s))(using e2, fs).stdout)"""
    )) // ...so it is just as unusable in map
    assertFails(
      run("""classify("s").map(s => { val r: Exec^{ex.rd} = ex; exec("echo", List(s))(using r, fs).stdout })""")
    )
    assertFails(run("""classify("u").map(u => httpGet(u)(using net))"""))
    assertFails(run(
      """def viaDef(s: String)(using e: Exec^, f: FileSystem^) = exec("echo", List(s))(using e, f).stdout; classify("s").map(viaDef)"""
    ))

  test("a read-only view of fs can read and never write, even inside Classified.map"):
    assertOk(run("""val rofs: FileSystem^{fs.rd} = fs; rofs.access("a.txt").read().length"""))
    assertFails(run("""val rofs: FileSystem^{fs.rd} = fs; write("a.txt", "x")(using rofs)"""), "read-only")
    assertOk(run("""val rofs: FileSystem^{fs.rd} = fs; classify("a.txt").map(p => read(p)(using rofs)).toString"""))
    assertFails(run("""classify("a.txt").map(p => read(p)).toString""")) // the full fs may not be captured

  test("Exec and Network have no read-only view: a bare Exec is already full"):
    // They are plain exclusive capabilities (not `Stateful`), because there is no
    // meaningful "observe without acting" for running a command or a request.
    assertFails(run("""val e2: Exec^{ex.rd} = ex"""))
    assertFails(run("""val n2: Network^{net.rd} = net"""))
    val echo = ujson.write(echoCommand)
    assertOk(run(
      s"""def f(using x: Exec, fullFs: FileSystem^) = exec($echo, List("hi"))(using x, fullFs).exitCode; f(using ex, fs)"""
    ))

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
    assertFails(run("""read("a.txt")(using user)"""))
    assertFails(run("""requestExec(Set("x")) { 1 }(using io, ex)"""))

  test("the user capability is what request* blocks consume, not io"):
    // `request*` prompt the user, so they take UserIO^. That is also what keeps
    // them out of a pure `Classified.map` (see the capture-contract section).
    val root = env.scalaString(env.root)
    assertOk(run(s"""requestFiles($root) { read("a.txt").length }"""))
    assertFails(run(s"""val ru: UserIO^{user.rd} = user; requestFiles($root)(using ru, fs) { 1 }"""))

  // ── Granted capabilities cannot escape their block ──────────────

  test("requestFiles opens a permission scope, prompts, and closes it again"):
    val outside = TestEnv.outsideDir("outside-data")
    val outsideCode = env.scalaString(outside)
    val fileCode = env.scalaString(outside.resolve("o.txt"))
    env.requests.clear()
    val r = assertOk(run(s"""requestFiles($outsideCode, Access.Read, "why") { read($fileCode) }"""))
    assert(r.output.contains("outside-data"), r.output)
    assert(env.requests.exists { case f: FileRequest => f.reason == "why"; case _ => false }, env.requests.toString)
    assertEquals(env.policy.openScopeCount, 0)
    // ...and the grant is gone once the block ends (it was "allow once").
    assertFails(run(s"read($fileCode)"))

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
    assertOk(run(s"""requestFiles(${env.scalaString(
        env.root
      )}, Access.Write) { write("granted.txt", "ok"); read("granted.txt") }"""))

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
    assertOk(run("""classify("a(b)").map(s => quote(s) + quoteReplacement(s))""")) // pure API helpers are fine
    assertOk(run("""classify("{\"a\":1}").map(s => Json.parse(s)("a").int)""")) // JSON is pure too
    assertOk(run("""classify("x").map(s => { var n = 0; n += s.length; n })"""))
    assertOk(run("""classify("x").map(s => { val a = Array(1, 2); a(0) + s.length })"""))

  test("map rejects every outward channel"):
    // The security property: nothing that leaves the process (or reaches the
    // user, or the normal model) can run on confidential data.
    assertOk(
      run(
        s"""val spawned: Process^{ex} = spawn(${ujson.write(echoCommand)}); spawned.waitFor(5000).isDefined"""
      )
    ) // a handle to try to smuggle
    val channels = List(
      "print to the user" -> """classify("s").map(s => { println(s); s })""",
      "cat a file" -> """classify("s").map(s => { cat(s); s })""",
      "printf" -> """classify("s").map(s => { printf("%s", s); s })""",
      "ask the user" -> """classify("s").map(s => ask(s))""",
      "set todos" -> """classify("s").map(s => { setTodos(List(Todo(s))); s })""",
      "mark todo" -> """classify("s").map(s => { markTodo(s, TodoStatus.Done); s })""",
      "normal model" -> """classify("s").map(s => chat(s))""",
      "write a file" -> """classify("s").map(s => { write("leak.txt", s); s })""",
      "regex sed" -> """classify("s").map(s => { sed("a.txt", "content", s); s })""",
      "move a file" -> """classify("s").map(s => { move("a.txt", s); s })""",
      "copy a file" -> """classify("s").map(s => { copy("a.txt", s); s })""",
      "replace lines" -> """classify("s").map(s => { replaceLines("a.txt", 1, 1, s); s })""",
      "insert lines" -> """classify("s").map(s => { insertLines("a.txt", 1, s); s })""",
      "write bytes" -> """classify("s").map(s => { writeBytes("leak.bin", s.getBytes); s })""",
      "append to a file" -> """classify("s").map(s => { append("leak.txt", s); s })""",
      "delete a file" -> """classify("s").map(s => { delete(s); s })""",
      "write classified" -> """classify("s").map(s => { writeClassified(s, classify(s)); s })""",
      "run a command" -> """classify("s").map(s => exec("echo", List(s)).stdout)""",
      "run a command (execOutput)" -> """classify("s").map(s => execOutput(s))""",
      "spawn a process" -> """classify("s").map(s => spawn(s).id)""",
      "talk to a spawned process" -> """classify("s").map(s => { spawned.sendLine(s); s })""",
      "http GET" -> """classify("s").map(s => httpGet(s))""",
      "http POST" -> """classify("s").map(s => httpPost("http://example.com", s))""",
      "ask for permissions" -> """classify("s").map(s => requestFiles(s) { read(s) })""",
      "take the root capability" -> """classify("s").map(s => { println(s)(using atc.lib.Runtime.rootUser); s })""",
    )
    for (what, code) <- channels do
      val r = run(code)
      assert(!r.success, s"$what leaked into Classified.map:\n${r.output}")

  test("capability values cannot be smuggled into Classified.map as its argument"):
    // `classify` does not erase a value's capture provenance. Even though each
    // capability arrives as the lambda parameter rather than a free variable,
    // using it for an effect would make `map` impure and must be rejected.
    val attacks = List(
      "FileSystem" -> """classify(fs).map(hidden => hidden.access("leak.txt").write("x"))""",
      "FileEntry" -> """classify(access("a.txt")).map(hidden => hidden.write("x"))""",
      "Network" -> """classify(net).map(hidden => httpGet("http://example.com")(using hidden))""",
      "UserIO" -> """classify(user).map(hidden => println("leak")(using hidden))""",
      "Exec" -> """classify(ex).map(hidden => exec("echo", List("leak"))(using hidden, fs).stdout)""",
    )
    for (capability, code) <- attacks do
      val result = run(code)
      assert(!result.success, s"$capability was usable after being classified:\n${result.render}")

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

  test("classifiedChat is deliberately pure and its Classified wrapper preserves the label"):
    // The classified model is trusted to run in an isolated, effect-free
    // environment, so its String overload is admitted inside the pure map.
    assertOk(run("""classify("topsecret").map(classifiedChat).toString"""))
    assert(env.classifiedChats.contains("topsecret"), env.classifiedChats.toString)
    assertOk(run("""def trusted(s: String) = classifiedChat(s)"""))
    assertOk(run("""classify("def-secret").map(trusted).toString"""))
    assert(env.classifiedChats.contains("def-secret"), env.classifiedChats.toString)
    // The Classified overload is exactly the convenient label-preserving wrapper.
    assertOk(
      run("""val answer: Classified[String] = classifiedChat(classify("wrapped-secret")); answer.toString""")
    )
    assert(env.classifiedChats.contains("wrapped-secret"), env.classifiedChats.toString)
