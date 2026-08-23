package atc

import atc.sandbox.{CodeValidator, Violation}

/** Static validation of agent code (migrated from TACIT's CodeValidatorSuite
  * and CodeValidatorEvasionSuite, plus the ATC-specific rules). */
class CodeValidatorSuite extends munit.FunSuite:
  private def ids(code: String): List[String] = CodeValidator.validate(code).map(_.ruleId)
  private def assertRejected(code: String, rule: String)(using munit.Location): Unit =
    val got = ids(code)
    assert(got.contains(rule), s"expected rule '$rule' for:\n$code\ngot: $got")
  private def assertAllowed(code: String)(using munit.Location): Unit =
    assertEquals(ids(code), Nil, s"expected no violation for:\n$code")

  // ── Rejection by category ────────────────────────────────────────

  test("reject java.io") { assertRejected("import java.io.File", "file-io-java") }
  test("reject java.nio") { assertRejected("import java.nio.file.Files", "file-io-nio") }
  test("reject scala.io") { assertRejected("import scala.io.Source", "file-io-scala") }
  test("reject ProcessBuilder") { assertRejected("new ProcessBuilder(\"ls\").start()", "proc-builder") }
  test("reject Runtime.getRuntime") { assertRejected("Runtime.getRuntime.exec(\"ls\")", "proc-runtime") }
  test("reject scala.sys.process") { assertRejected("import scala.sys.process._", "proc-scala") }
  test("reject java.net") { assertRejected("import java.net.URL", "net-java") }
  test("reject javax.net") { assertRejected("import javax.net.ssl.SSLContext", "net-javax") }
  test("reject HttpClient") { assertRejected("val c = HttpClient.newHttpClient()", "net-http-client") }
  test("reject HttpURLConnection") { assertRejected("val c: HttpURLConnection = ???", "net-http-conn") }
  test("reject .asInstanceOf") { assertRejected("x.asInstanceOf[String]", "cast-escape") }
  test("reject asInstanceOf with spaces and generic type"):
    assertRejected("x.asInstanceOf [List[String]]", "cast-escape")
  test("reject caps.unsafe") { assertRejected("import caps.unsafe.given", "cc-unsafe-caps") }
  test("reject unsafeAssumePure") { assertRejected("val x = unsafeAssumePure(ref)", "cc-unsafe-pure") }
  test("reject other unsafeAssume* helpers") { assertRejected("x.unsafeAssumeSeparate", "cc-unsafe-assume") }
  test("reject @assumeSafe in agent code") { assertRejected("@assumeSafe object O", "cc-assume-safe") }
  test("reject getDeclaredMethod") { assertRejected("cls.getDeclaredMethod(\"foo\")", "reflect-method") }
  test("reject getDeclaredField") { assertRejected("cls.getDeclaredField(\"bar\")", "reflect-field") }
  test("reject getDeclaredConstructor") { assertRejected("cls.getDeclaredConstructor()", "reflect-ctor") }
  test("reject setAccessible") { assertRejected("field.setAccessible(true)", "reflect-accessible") }
  test("reject java.lang.reflect") { assertRejected("import java.lang.reflect.Method", "reflect-java") }
  test("reject scala.reflect.runtime") { assertRejected("import scala.reflect.runtime.universe._", "reflect-scala") }
  test("reject Class.forName") { assertRejected("Class.forName(\"java.lang.String\")", "reflect-forname") }
  test("reject getClass") { assertRejected("x.getClass.getName", "reflect-getclass") }
  test("reject sun.misc") { assertRejected("import sun.misc.Unsafe", "jvm-sun") }
  test("reject sun.misc.Signal") { assertRejected("import sun.misc.Signal", "jvm-sun") }
  test("reject sun.reflect") { assertRejected("import sun.reflect.Reflection", "jvm-sun") }
  test("reject jdk.internal") { assertRejected("import jdk.internal.misc.Unsafe", "jvm-jdk-internal") }
  test("reject com.sun.*") { assertRejected("import com.sun.net.httpserver.HttpServer", "jvm-com-sun") }
  test("reject java.lang.invoke") { assertRejected("import java.lang.invoke.MethodHandles", "jvm-invoke") }
  test("reject System.out") { assertRejected("System.out.println(\"hello\")", "io-system-out") }
  test("reject System.err") { assertRejected("System.err.println(\"hello\")", "io-system-err") }
  test("reject System.in") { assertRejected("System.in.read()", "io-system-in") }
  test("reject Console") { assertRejected("Console.println(\"hello\")", "io-console") }
  test("reject Predef.print") { assertRejected("scala.Predef.println(\"hello\")", "io-predef-print") }
  test("reject System.exit") { assertRejected("System.exit(0)", "sys-exit") }
  test("reject System.setProperty") { assertRejected("System.setProperty(\"foo\", \"bar\")", "sys-setprop") }
  test("reject System.getenv") { assertRejected("System.getenv(\"PATH\")", "sys-getenv") }
  test("reject System.getProperty") { assertRejected("System.getProperty(\"user.home\")", "sys-getprop") }
  test("reject System.load / loadLibrary"):
    assertRejected("System.load(\"/tmp/x.so\")", "sys-load")
    assertRejected("System.loadLibrary(\"x\")", "sys-load")
  test("reject new Thread") { assertRejected("new Thread(() => println(\"hi\")).start()", "sys-thread") }
  test("reject Thread(...) constructor without new") { assertRejected("Thread(() => ()).start()", "sys-thread2") }
  test("reject Thread creation with extra whitespace") { assertRejected("new   Thread(() => ())", "sys-thread") }
  test("reject //> using directive") {
    assertRejected("//> using dep \"com.lihaoyi::os-lib:0.9.1\"", "directive-using")
  }
  test("reject import $ directive") { assertRejected("import $ivy.`com.lihaoyi::os-lib:0.9.1`", "directive-import") }
  test("reject ClassLoader") { assertRejected("Thread.currentThread().getContextClassLoader", "classloader") }
  test("reject URLClassLoader") { assertRejected("new URLClassLoader(Array())", "classloader") }
  test("reject dotty.tools") { assertRejected("import dotty.tools.repl.ReplDriver", "dotty-tools") }
  test("reject scala.tools") { assertRejected("import scala.tools.nsc.Global", "scala-tools") }
  test("reject scala.quoted") { assertRejected("import scala.quoted.*", "scala-quoted") }
  test("reject language imports (managed by the sandbox)"):
    assertRejected("import language.experimental.captureChecking", "language-import")
    assertRejected("import scala.language.experimental.safe", "language-import")

  // ── ATC-specific: application internals and the sandbox injection point ──

  test("reject application packages"):
    assertRejected("atc.host.Host", "atc-host")
    assertRejected("import atc.agent.*", "atc-host")
    assertRejected("atc.sandbox.Sandbox.newLoader()", "atc-host")
    assertRejected("atc.perms.Policy", "atc-host")
    assertRejected("atc.config.Config()", "atc-host")
    assertRejected("atc.llm.EchoModel(\"x\")", "atc-host")
    assertRejected("atc.ui.Tui", "atc-host")
  test("allow the library package"):
    assertAllowed("val c: atc.lib.Classified[String] = classify(\"x\")")
  test("reject Runtime.current / rootIO / install"):
    assertRejected("Runtime.current", "atc-runtime")
    assertRejected("atc.lib.Runtime.rootIO", "atc-runtime")
    assertRejected("Runtime.install(null)", "atc-runtime")
    assertRejected("atc.lib.Runtime.fileSystem(using io)", "atc-runtime")
    assertRejected("Runtime.processes", "atc-runtime")
    assertRejected("Runtime.readOnlyFileSystem", "atc-runtime")
    assertRejected("Runtime.network", "atc-runtime")
  test("allow the Interface type name itself"):
    assertAllowed("val i: Interface = ???")

  // ── Allowlist ────────────────────────────────────────────────────

  test("allow simple arithmetic") { assertAllowed("1 + 1") }
  test("allow val/def definitions"):
    assertAllowed("""
      val x = 42
      def add(a: Int, b: Int): Int = a + b
      add(x, 1)
    """)
  test("allow java.time") { assertAllowed("import java.time.LocalDate") }
  test("allow java.util") { assertAllowed("import java.util.ArrayList") }
  test("allow scala.collection") { assertAllowed("import scala.collection.mutable.ListBuffer") }
  test("allow scala.util.Try") { assertAllowed("import scala.util.Try") }
  test("allow List/Map/Set usage"):
    assertAllowed("""
      val xs = List(1, 2, 3).map(_ * 2)
      val m = Map("a" -> 1)
      val s = Set(1, 2, 3)
    """)
  test("allow the sandbox API"):
    assertAllowed("""
      requestFiles("/tmp", Access.Write, "cache") { write("/tmp/x", read("a.txt")) }
      requestExec(Set("git status")) { println(exec("git", List("status")).stdout) }
      requestNetwork(Set("example.com")) { httpGet("https://example.com") }
      readClassified("secrets/k").map(_.trim)
      setTodos(List(Todo("a"))); markTodo("a", TodoStatus.Done); ask("q", List("x"))
    """)
  test("allow Thread.currentThread and threadless names containing 'Thread'"):
    assertAllowed("val t = Thread.currentThread().getName")
    assertAllowed("val myThreadCount = 3")
  test("allow user identifiers that merely contain forbidden words"):
    assertAllowed("val consoleOutput = 1; val systemName = 2; val processBuilderLike = 3")
  test("allow System.currentTimeMillis / nanoTime / lineSeparator"):
    assertAllowed("val t = System.currentTimeMillis(); val n = System.nanoTime(); System.lineSeparator()")

  // ── String and comment stripping ─────────────────────────────────

  test("allow forbidden pattern inside double-quoted string") { assertAllowed("""println("java.io is blocked")""") }
  test("allow System.out in string literal") { assertAllowed("""println("System.out is just text")""") }
  test("allow Console in string literal") { assertAllowed("""println("Console is just text")""") }
  test("allow forbidden pattern inside triple-quoted string"):
    assertAllowed("val s = \"\"\"java.io.File is mentioned here\"\"\"")
  test("allow forbidden pattern inside line comment") { assertAllowed("val x = 1 // java.io.File is fine here") }
  test("allow forbidden pattern inside block comment") { assertAllowed("val x = 1 /* java.io.File */ + 2") }
  test("allow forbidden pattern inside multi-line block comment"):
    assertAllowed("val x = 1 /* line one\n java.io.File\n System.exit(0) */ + 2")
  test("allow forbidden pattern inside a char literal context"):
    assertAllowed("val c = '\"'; val d = \"java.io\"")
  test("reject forbidden pattern on code line even if comment exists on another line"):
    assertRejected("\n      // This is a comment about java.io\n      import java.io.File\n    ", "file-io-java")
  test("reject forbidden pattern between two string literals"):
    assertRejected("""val s = "safe"; import java.io.File; val t = "safe"""", "file-io-java")
  test("allow forbidden pattern inside nested string with escapes"):
    assertAllowed("""val s = "foo \"java.io.File\" bar"""")
  test("directive inside a string literal is allowed (strings are stripped, comments kept)"):
    assertAllowed("""val s = "//> using dep foo"""")
  test("directive in a comment is still rejected"):
    assertRejected("val x = 1\n//> using dep foo", "directive-using")

  // ── Strip function unit tests ────────────────────────────────────

  test("stripLiteralsAndComments preserves line count"):
    val code = "line1\nline2 \"a\nb\"\nline3 /* x\ny */"
    assertEquals(CodeValidator.stripLiteralsAndComments(code).count(_ == '\n'), code.count(_ == '\n'))
  test("stripLiteralsAndComments preserves character offsets"):
    val code = """val s = "java.io"; val t = 1"""
    val stripped = CodeValidator.stripLiteralsAndComments(code)
    assertEquals(stripped.length, code.length)
    assertEquals(stripped.indexOf("val t"), code.indexOf("val t"))
  test("stripLiteralsAndComments blanks string content"):
    assert(!CodeValidator.stripLiteralsAndComments("""val s = "java.io.File"""").contains("java.io"))
  test("stripLiteralsAndComments blanks block comment"):
    assert(!CodeValidator.stripLiteralsAndComments("val x = /* java.io.File */ 1").contains("java.io"))
  test("stripLiteralsAndComments blanks line comment"):
    assert(!CodeValidator.stripLiteralsAndComments("val x = 1 // java.io.File").contains("java.io"))
  test("stripLiteralsAndComments handles escape sequences"):
    assert(!CodeValidator.stripLiteralsAndComments("""val s = "foo\"bar java.io" """).contains("java.io"))
  test("stripLiteralsAndComments handles unclosed string"):
    assert(CodeValidator.stripLiteralsAndComments("""val s = "unclosed string""").nonEmpty)
  test("stripLiteralsAndComments handles unclosed triple-quoted string"):
    assert(CodeValidator.stripLiteralsAndComments("val s = \"\"\"unclosed").nonEmpty)
  test("stripLiteralsAndComments handles unclosed block comment"):
    assert(CodeValidator.stripLiteralsAndComments("val x = 1 /* unclosed").nonEmpty)
  test("stripLiteralsAndComments handles empty string literal"):
    assert(!CodeValidator.stripLiteralsAndComments("val s = \"\"; val t = \"java\"").contains("java"))
  test("stripLiteralsAndComments handles adjacent strings"):
    val stripped = CodeValidator.stripLiteralsAndComments(""""foo" + "bar"""")
    assert(!stripped.contains("foo") && !stripped.contains("bar"))
  test("stripLiteralsAndComments preserves code between strings"):
    assert(CodeValidator.stripLiteralsAndComments("""val a = "x"; java.io.File; val b = "y"""").contains("java.io"))
  test("stripLiteralsAndComments keeps interpolation expressions"):
    val stripped = CodeValidator.stripLiteralsAndComments("""val s = s"text ${a.b} more $name""""")
    assert(stripped.contains("${a.b}"), stripped)
    assert(stripped.contains("$name"), stripped)
    assert(!stripped.contains("text"), stripped)
  test("stripLiteralsAndComments handles nested braces inside interpolation"):
    val stripped = CodeValidator.stripLiteralsAndComments("""val s = s"${ xs.map { x => x + 1 } } tail"""")
    assert(stripped.contains("xs.map { x => x + 1 }"), stripped)
    assert(!stripped.contains("tail"), stripped)
  test("stripLiteralsAndComments handles a string inside an interpolation expression"):
    val stripped = CodeValidator.stripLiteralsAndComments("""val s = s"${ identity("java.io.File") } end"""")
    assert(!stripped.contains("java.io"), stripped)
  test("stripStringLiteralsOnly keeps comments"):
    val stripped = CodeValidator.stripStringLiteralsOnly("""val s = "x" // keep me""")
    assert(stripped.contains("// keep me"))
    assert(!stripped.contains("x\""))

  // ── Violation details ────────────────────────────────────────────

  test("violation includes correct line number"):
    val vs = CodeValidator.validate("val x = 1\nimport java.io.File\nval y = 2")
    assert(vs.exists(_.lineNumber == 2), vs.toString)
  test("multiple violations reported"):
    assert(CodeValidator.validate("import java.io.File\nimport java.net.URL").size >= 2)
  test("multiple forbidden patterns on the same line"):
    assert(CodeValidator.validate("import java.io.File; import java.net.URL").size >= 2)
  test("violation snippet is trimmed and comes from the original line"):
    val vs = CodeValidator.validate("   import java.io.File   // \"quoted\"")
    assertEquals(vs.head.snippet, "import java.io.File   // \"quoted\"")
  test("violation description explains the alternative"):
    val v = CodeValidator.validate("import java.io.File").head
    assert(v.description.contains("file API"), v.description)
  test("formatErrors produces readable output"):
    val out = CodeValidator.formatErrors(List(Violation(
      "file-io-java",
      "Direct java.io access is forbidden",
      1,
      "import java.io.File"
    )))
    assert(out.contains("1 violation"))
    assert(!out.contains("1 violations"))
    assert(out.contains("file-io-java"))
    assert(out.contains("Line 1"))
    assert(out.contains("> import java.io.File"))
  test("formatErrors with multiple violations uses plural"):
    val out =
      CodeValidator.formatErrors(List(Violation("a", "desc a", 1, "code a"), Violation("b", "desc b", 2, "code b")))
    assert(out.contains("2 violations"))
    assert(out.contains("desc a") && out.contains("desc b"))

  // ── Boundary input ───────────────────────────────────────────────

  test("empty code is valid") { assertAllowed("") }
  test("whitespace-only code is valid") { assertAllowed("   \n\n  ") }
  test("comment-only code is valid") { assertAllowed("// just a comment\n/* block comment */") }
  test("reject pattern at very start of code") { assertRejected("java.io.File", "file-io-java") }
  test("reject pattern at very end of code") { assertRejected("val x = 1; System.exit", "sys-exit") }
  test("reject forbidden pattern after safe code on same line") {
    assertRejected("val x = 42; import java.io.File", "file-io-java")
  }

  // ── Evasion: dotted tokens split across lines ────────────────────

  test("split: import java.<nl>io.File") { assertRejected("import java.\n  io.File", "file-io-java") }
  test("split: trailing-dot System.getProperty") {
    assertRejected("System.\ngetProperty(\"user.home\")", "sys-getprop")
  }
  test("split: leading-dot System.exit") { assertRejected("val z = 1\nSystem\n.exit(0)", "sys-exit") }
  test("split: Runtime.<nl>getRuntime") { assertRejected("Runtime.\ngetRuntime.exec(\"id\")", "proc-runtime") }
  test("split: caps.<nl>unsafe") { assertRejected("import caps.\nunsafe.unsafeAssumePure", "cc-unsafe-caps") }
  test("split: three-line scala.sys.process") { assertRejected("import scala.\n  sys.\n  process._", "proc-scala") }
  test("split: extra whitespace java.nio") { assertRejected("import java   .\n      nio.file.Files", "file-io-nio") }
  test("split: line number points at the first physical line"):
    val vs = CodeValidator.validate("val a = 1\nimport java.\n  io.File")
    assertEquals(vs.map(_.lineNumber), List(2))

  // ── Evasion: comments cannot hide the member-access dot ─────────

  test("comment between the name and the dot"):
    assertRejected("import java./*x*/io.File", "file-io-java")
    assertRejected("import java./**/io.File", "file-io-java")
  test("comment between the dot and the member"):
    assertRejected("import java/*x*/.io.File", "file-io-java")
  test("comment in a multi-line split"):
    assertRejected("import java./* c */\nio.File", "file-io-java")
  test("unrelated comments around a forbidden call are still caught"):
    assertRejected("System./*a*/getenv(\"HOME\")", "sys-getenv")

  // ── Evasion: string interpolation is code ────────────────────────

  test("interp: System.getProperty") {
    assertRejected("""val x = s"${System.getProperty("user.home")}"""", "sys-getprop")
  }
  test("interp: java.lang.Runtime") { assertRejected("""val x = s"${java.lang.Runtime.getRuntime}"""", "proc-runtime") }
  test("interp: new java.io.File") { assertRejected("""val x = s"${new java.io.File("/x")}"""", "file-io-java") }
  test("interp: triple-quoted") { assertRejected("val x = s\"\"\"${System.exit(0)}\"\"\"", "sys-exit") }
  test("interp: f-interpolator") { assertRejected("""val x = f"${System.exit(0)}%s"""", "sys-exit") }
  test("interp: raw-interpolator") { assertRejected("""val x = raw"${System.exit(0)}"""", "sys-exit") }
  test("interp: dot-split inside ${}") { assertRejected("val x = s\"${System.\nexit(0)}\"", "sys-exit") }
  test("interp: forbidden call inside a nested block"):
    assertRejected("""val x = s"${ List(1).map { _ => System.getenv("HOME") } }"""", "sys-getenv")

  // ── Evasion: scala.sys.* re-exposes System hooks ─────────────────

  test("sys.process import") { assertRejected("import sys.process._", "proc-scala") }
  test("scala.sys.process import") { assertRejected("import scala.sys.process.*", "proc-scala") }
  test("sys.exit") { assertRejected("sys.exit(0)", "sys-scala") }
  test("sys.env") { assertRejected("sys.env.get(\"PATH\")", "sys-scala") }
  test("sys.props") { assertRejected("sys.props(\"user.home\")", "sys-scala") }
  test("sys.runtime") { assertRejected("sys.runtime.halt(0)", "sys-scala") }
  test("sys.allThreads / addShutdownHook"):
    assertRejected("sys.allThreads()", "sys-scala")
    assertRejected("sys.addShutdownHook(())", "sys-scala")

  // ── False-positive guards ────────────────────────────────────────

  test("no-fp: leading-dot method chaining") { assertAllowed("List(1,2,3)\n  .map(_ * 2)\n  .filter(_ > 2)\n  .sum") }
  test("no-fp: trailing-dot method chaining") { assertAllowed("val r = List(1,2,3).\n  map(_ * 2).\n  filter(_ > 2)") }
  test("no-fp: forbidden token quoted inside interpolation expr") {
    assertAllowed("""val x = s"${ identity("java.io.File") }"""")
  }
  test("no-fp: forbidden token in interpolation literal text") {
    assertAllowed("""val x = s"java.io.File is just text ${1 + 1}"""")
  }
  test("no-fp: dollar in a plain (non-interpolated) string") { assertAllowed("""val x = "$java.io is literal"""") }
  test("no-fp: a user value named like sys is not scala.sys") { assertAllowed("val mysys = obj; mysys.exit") }
  test("no-fp: sys.error is allowed by the validator") { assertAllowed("""sys.error("boom")""") }
  test("no-fp: names ending in Interface members are fine") {
    assertAllowed("val currentInterface = 1; val install = 2")
  }
  test("no-fp: 'atc' inside other identifiers") { assertAllowed("val matcher = 1; val watch = 2; val patch_host = 3") }

  // ── Catching fatal throwables ────────────────────────────────────

  test("reject catch of Throwable") { assertRejected("try f() catch case _: Throwable => ()", "catch-fatal") }
  test("reject catch of Throwable with a binder"):
    assertRejected("try f() catch case e: Throwable => ()", "catch-fatal")
  test("reject catch of Error") { assertRejected("try f() catch case _: Error => ()", "catch-fatal") }
  test("reject catch of StackOverflowError"):
    assertRejected("try f() catch case _: StackOverflowError => ()", "catch-fatal")
  test("reject catch of OutOfMemoryError"):
    assertRejected("try f() catch case _: OutOfMemoryError => ()", "catch-fatal")
  test("reject catch of VirtualMachineError"):
    assertRejected("try f() catch case _: VirtualMachineError => ()", "catch-fatal")
  test("reject catch of Any") { assertRejected("try f() catch case _: Any => ()", "catch-fatal") }
  test("reject catch of a fully-qualified Throwable"):
    assertRejected("try f() catch case _: java.lang.Throwable => ()", "catch-fatal")
  test("reject catch of Throwable split across lines"):
    assertRejected("try\n  f()\ncatch\n  case _: Throwable => ()", "catch-fatal")

  test("reject bare catch-all (wildcard)") { assertRejected("try f() catch case _ => ()", "catch-all") }
  test("reject bare catch-all (binder)") { assertRejected("try f() catch case e => ()", "catch-all") }
  test("reject bare catch-all with a guard") { assertRejected("try f() catch case e if true => ()", "catch-all") }
  test("reject braced bare catch-all") { assertRejected("try f() catch { case _ => () }", "catch-all") }
  test("reject bare catch-all split across lines"):
    assertRejected("try\n  f()\ncatch\n  case _ => ()", "catch-all")
  test("reject a bare catch-all fallback after typed arms"):
    assertRejected("try f() catch {\n  case _: RuntimeException => 1\n  case _ => 2\n}", "catch-all")
  test("reject a braceless multi-arm catch whose LAST arm is the catch-all"):
    assertRejected("try f()\ncatch\n  case _: RuntimeException => 1\n  case _ => 2", "catch-all")
  test("reject a braced catch-all behind two levels of nested braces"):
    assertRejected(
      "try f() catch { case _: RuntimeException => { val x = { 1 }; x }; case _ => 2 }",
      "catch-all"
    )
  test("reject a catch-all in a deeply nested braced catch"):
    assertRejected("try f() catch { case _: Exception => { { { 1 } } }; case e => 2 }", "catch-all")
  test("reject a catch-all with a guarded binder arm after a typed arm"):
    assertRejected("try f() catch { case _: IllegalArgumentException => 1; case e if true => 2 }", "catch-all")
  test("reject an underscore-prefixed binder catch-all"):
    assertRejected("try f() catch case _ignored => ()", "catch-all")
  test("reject a nested catch's catch-all inside a typed catch arm"):
    assertRejected("try f() catch { case _: Exception => (try g() catch { case _ => 1 }) }", "catch-all")
  test("allow a multi-arm catch when every arm is typed"):
    assertAllowed(
      "try read(\"x\") catch {\n  case _: IllegalArgumentException => \"a\"\n  case _: IllegalStateException => \"b\"\n}"
    )
  test("allow a braceless multi-arm catch when every arm is typed"):
    assertAllowed(
      "try read(\"x\")\ncatch\n  case _: IllegalArgumentException => \"a\"\n  case _: RuntimeException => \"b\""
    )
  test("allow a nested braced match with a wildcard inside a catch arm"):
    assertAllowed(
      "try read(\"x\") catch { case _: IllegalArgumentException => List(1) match { case _ => 0 } }"
    )
  test("allow a typed catch followed by finally"):
    assertAllowed("try read(\"x\") catch { case _: Exception => \"d\" } finally println(\"done\")")
  test("allow a braced catch-all in a comment"):
    assertAllowed("val x = 1 // try f() catch { case _ => 2 }")
  test("allow a braced catch-all inside a string"):
    assertAllowed("""val s = "try f() catch { case _ => 2 }"""")

  test("reject a type alias of Throwable (would defeat catch-fatal)"):
    assertRejected("type T = Throwable\ntry f() catch case _: T => 2", "catch-fatal-alias")
  test("reject a type alias of a fatal Error"):
    assertRejected("type S = StackOverflowError", "catch-fatal-alias")
  test("reject a type alias of a qualified fatal type"):
    assertRejected("type T = java.lang.Throwable", "catch-fatal-alias")
  test("reject a type alias whose union has a fatal member"):
    assertRejected("type T = RuntimeException | Throwable", "catch-fatal-alias")
  test("allow a benign type alias"):
    assertAllowed("type S = String\ntype L = List[Int]")
  test("no-fp: a fatal type nested in type arguments is not an alias of it"):
    // `case _: M` catches Map, not AnyRef — the RHS scan must not cross a '['.
    assertAllowed("type M = Map[String, AnyRef]")
    assertAllowed("type L = List[Any]")
    assertAllowed("type R = Either[Throwable, Int]")

  // ── Catch-fatal evasions: parenthesised / union types and erased bounds ──

  test("reject catch of a parenthesised Throwable"):
    assertRejected("try f() catch case _: (Throwable) => ()", "catch-fatal")
  test("reject catch of a union type containing a fatal type"):
    assertRejected("try f() catch case _: (RuntimeException | Throwable) => ()", "catch-fatal")
    assertRejected("try f() catch case _: (Throwable | RuntimeException) => ()", "catch-fatal")
  test("reject a type parameter bounded by a fatal type (erased catch of the bound)"):
    assertRejected("def g[T <: Throwable](b: => String) = try b catch case _: T => \"x\"", "catch-fatal-bound")
    assertRejected("def g[E <: Error] = 1", "catch-fatal-bound")
  test("no-fp: an AnyRef upper bound is a normal, allowed bound"):
    assertAllowed("def g[T <: AnyRef](x: T): T = x")
  test("reject catching an unbounded type parameter (erases to a fatal-catching bound)"):
    // Demonstrated to swallow a real StackOverflowError in the sandbox.
    assertRejected("def g[T](b: => Int): String = try b.toString catch case _: T => \"x\"", "catch-type-param")
  test("reject catching a class/enum type parameter"):
    assertRejected(
      "class Box[E]:\n  def run(b: => Int): String = try b.toString catch case _: E => \"x\"",
      "catch-type-param"
    )
  test("no-fp: a generic def that does not catch its parameter is fine"):
    assertAllowed("def id[A](a: A): A = a\ndef first[A, B](p: (A, B)): A = p._1")
  test("no-fp: a generic def catching a concrete non-fatal type is fine"):
    assertAllowed("def f[A](b: => Int): String = try b.toString catch case _: RuntimeException => \"x\"")
  test("no-fp: a catch guard that merely mentions a fatal type is not a fatal catch"):
    assertAllowed("try read(\"x\") catch case _: RuntimeException if List[Throwable]().isEmpty => \"d\"")

  // ── Catch-all evasions: @-binder and parenthesised patterns ──────

  test("reject an @-binder catch-all"):
    assertRejected("try f() catch case e @ _ => ()", "catch-all")
  test("reject a parenthesised binder catch-all"):
    assertRejected("try f() catch case (e) => ()", "catch-all")
    assertRejected("try f() catch case (_) => ()", "catch-all")
  test("allow an @-binder over a typed (non-fatal) pattern"):
    assertAllowed("try read(\"x\") catch case e @ (_: RuntimeException) => e.getMessage")

  test("reject backquoted getClass") { assertRejected("x.`getClass`", "reflect-getclass") }
  test("allow getClass inside a string") { assertAllowed("""val s = "x.getClass"""") }

  test("reject throwing InterruptedException") {
    assertRejected("throw new InterruptedException()", "throwable-interrupted")
  }
  test("reject catching InterruptedException"):
    assertRejected("try f() catch case _: InterruptedException => ()", "throwable-interrupted")
  test("reject any use of ThreadDeath") { assertRejected("throw new ThreadDeath()", "throwable-threaddeath") }

  test("allow catch of a specific non-fatal exception"):
    assertAllowed("try read(\"x\") catch case _: RuntimeException => \"d\"")
  test("allow catch of Exception (InterruptedException is banned separately)"):
    assertAllowed("try read(\"x\") catch case _: Exception => \"d\"")
  test("validator does not flag NonFatal (though safe mode rejects it; prefer case _: Exception)"):
    assertAllowed("try read(\"x\") catch case NonFatal(e) => \"d\"")
  test("allow a typed catch with a binder"):
    assertAllowed("try read(\"x\") catch case e: IllegalStateException => e.getMessage")
  test("allow a match with a wildcard arm (not a catch)"):
    assertAllowed("val any: Any = 42\nany match\n  case i: Int => i\n  case _ => 0")
  test("allow .recover with a wildcard (not a catch)"):
    assertAllowed("scala.util.Try(read(\"x\")).recover { case _ => \"d\" }")
  test("allow a case class with a Throwable field"):
    assertAllowed("case class Wrapped(e: Throwable)")
  test("allow identifiers containing Error/Throwable"):
    assertAllowed("val parseError = 1; val myThrowable = 2")

  // ── Multiple violations of one rule count separately per line ────

  test("same rule on several lines yields one violation per line"):
    val vs = CodeValidator.validate("import java.io.File\nval f = java.io.File(\"x\")")
    assertEquals(vs.filter(_.ruleId == "file-io-java").map(_.lineNumber), List(1, 2))
