package atc

import atc.host.*
import atc.lib.{Exec, FileSystem, IOCap, Network, UserIO}
import atc.perms.*
import java.nio.file.{Files, Path}

/** The host's `Interface` implementation under the policy, called directly. */
class HostSuite extends munit.FunSuite:
  val root: Path = Files.createTempDirectory("atc-host").nn.toRealPath().nn
  Files.createDirectories(root.resolve("src"))
  Files.createDirectories(root.resolve("secrets/sub"))
  Files.createDirectories(root.resolve("private"))
  Files.writeString(root.resolve("src/A.scala"), "object A")
  Files.writeString(root.resolve("secrets/key.txt"), "s3cret")
  Files.writeString(root.resolve("secrets/sub/deep.txt"), "deep")
  Files.writeString(root.resolve("private/p.txt"), "p")
  Files.writeString(root.resolve("README.md"), "hello")

  val agentOut = StringBuilder()
  val userOut = StringBuilder()
  val output = new HostOutput:
    def print(agentText: String, userText: String): Unit =
      agentOut.append(agentText)
      userOut.append(if agentText == userText then userText else s"<$userText>")
  val llm = new HostLlm:
    def chat(m: String) = s"n:$m"
    def chatClassified(m: String) = s"s:$m"

  var decisions: List[Decision] = Nil
  val prompter: PermissionPrompter = _ =>
    decisions match
      case d :: rest => decisions = rest; d
      case Nil => Decision.Deny

  def rule(p: String, a: Option[Access] = None, c: Option[Boolean] = None, locked: Boolean = false) =
    FileRule(PathPattern(p, root), a, c, locked)

  val policy = Policy(
    List(
      rule(".", Some(Access.Write)),
      rule("secrets", c = Some(true)),
      rule("./private", Some(Access.None)),
    ),
    List("echo"),
    List("example.com"),
    prompter
  )
  val hostUi = new HostUi:
    def askUser(question: String, options: List[String], multiple: Boolean): Option[String] = Some("yes")
    def showTodos(items: List[atc.lib.Todo]): Unit = ()
  val host = Host(policy, root, output, llm, hostUi)

  given io: IOCap = atc.lib.Runtime.rootIO
  given user: UserIO = atc.lib.Runtime.rootUser
  given fs: FileSystem = host.fileSystem
  given ex: Exec = host.processes
  given net: Network = host.network
  import host.*

  private def rel(p: String) =
    val path = Path.of(p)
    if path.isAbsolute then root.relativize(path).toString else p

  test("plain read/write within cwd, relative paths"):
    assertEquals(read("src/A.scala"), "object A")
    write("src/B.scala", "object B")
    assertEquals(Files.readString(root.resolve("src/B.scala")), "object B")
    append("src/B.scala", "\n// more")
    assert(Files.readString(root.resolve("src/B.scala")).nn.endsWith("// more"))
    assert(exists("README.md"))
    assertEquals(access("/a/b/c.txt").name, "c.txt")

  test("sed with quote/quoteReplacement makes a literal edit, metacharacters and all"):
    Files.writeString(root.resolve("edit.txt"), "f(a.b) = $x\nf(a-b) = $x\nf(a.b) = $x\n")
    assertEquals(sed("edit.txt", quote("f(a.b) = $x"), quoteReplacement("g[a.b] = \\1 $y")), 2)
    assertEquals(
      Files.readString(root.resolve("edit.txt")),
      "g[a.b] = \\1 $y\nf(a-b) = $x\ng[a.b] = \\1 $y\n",
    )

  test("sed makes a regex edit with per-line anchors and reports how many matches it changed"):
    Files.writeString(root.resolve("sed.txt"), "x = 1\ny = 2\nz = 3\n")
    assertEquals(sed("sed.txt", """^(\w) = (\d)$""", "$1 := $2"), 3)
    assertEquals(Files.readString(root.resolve("sed.txt")), "x := 1\ny := 2\nz := 3\n")
    // a pattern with an explicit newline may span lines; `.` never does
    assertEquals(sed("sed.txt", ":= 1\ny", ":= 10\ny"), 1)
    assertEquals(Files.readString(root.resolve("sed.txt")), "x := 10\ny := 2\nz := 3\n")
    intercept[IllegalArgumentException](sed("sed.txt", "1.y", "1y"))

  test("sed accepts sed-style \\1, \\n, \\t and \\$ in the replacement"):
    Files.writeString(root.resolve("sed2.txt"), "a=1\nb=2\n")
    assertEquals(sed("sed2.txt", """(\w)=(\d)""", """\2:\1\t\$\n"""), 2)
    assertEquals(Files.readString(root.resolve("sed2.txt")), "1:a\t$\n\n2:b\t$\n\n")
    // a backslash before anything else escapes it (Java), and a trailing one is literal
    Files.writeString(root.resolve("sed2.txt"), "a=1\n")
    assertEquals(sed("sed2.txt", "=", """\=\"""), 1)
    assertEquals(Files.readString(root.resolve("sed2.txt")), "a=\\1\n")

  test("sed refuses a no-op, an empty pattern and a malformed regex, leaving the file alone"):
    Files.writeString(root.resolve("sed3.txt"), "content")
    val e = intercept[IllegalArgumentException](sed("sed3.txt", "absent.*", "x"))
    assert(e.getMessage.nn.contains("matches nothing"), e.getMessage)
    intercept[IllegalArgumentException](sed("sed3.txt", "", "x"))
    intercept[IllegalArgumentException](sed("sed3.txt", "(unclosed", "x"))
    assertEquals(Files.readString(root.resolve("sed3.txt")), "content")

  test("cat prints cat -n numbered lines, capped with a note on how to see the rest"):
    agentOut.clear(); userOut.clear()
    Files.writeString(root.resolve("cat.txt"), "one\ntwo\nthree\n")
    cat("cat.txt")
    assertEquals(agentOut.toString, "     1\tone\n     2\ttwo\n     3\tthree\n")
    assertEquals(userOut.toString, agentOut.toString) // the user sees the same text
    agentOut.clear()
    Files.writeString(root.resolve("big.txt"), (1 to 1000).map(i => s"line $i").mkString("\n"))
    cat("big.txt")
    val out = agentOut.toString
    assert(out.startsWith("     1\tline 1\n"), out.take(40))
    assert(out.contains(f"${Host.CatMaxLines}%6d\tline ${Host.CatMaxLines}\n"), out.takeRight(200))
    assert(!out.contains(s"line ${Host.CatMaxLines + 1}\n"), out.takeRight(200))
    assert(
      out.endsWith(
        s"""... [600 more lines (1000 in all): cat("big.txt", ${Host.CatMaxLines + 1}, ${2 * Host.CatMaxLines}) shows the next]\n"""
      ),
      out.takeRight(200),
    )
    agentOut.clear()
    Files.writeString(root.resolve("empty.txt"), "")
    cat("empty.txt")
    assertEquals(agentOut.toString, "[empty file]\n")
    agentOut.clear(); userOut.clear()

  test("cat(path, from, to) prints a 1-based inclusive window and marks the end of the file"):
    agentOut.clear(); userOut.clear()
    Files.writeString(root.resolve("win.txt"), (1 to 12).map(i => s"l$i").mkString("\n"))
    cat("win.txt", 10, 11)
    assertEquals(agentOut.toString, "    10\tl10\n    11\tl11\n")
    agentOut.clear()
    cat("win.txt", 12, 20) // runs past the end: what exists, then the marker
    assertEquals(agentOut.toString, "    12\tl12\n[end of file: 12 lines]\n")
    agentOut.clear()
    cat("win.txt", 13, 20)
    assertEquals(agentOut.toString, "[nothing to show: win.txt has 12 lines]\n")
    agentOut.clear()
    intercept[IllegalArgumentException](cat("win.txt", 0, 3))
    intercept[IllegalArgumentException](cat("win.txt", 5, 4))
    assertEquals(agentOut.toString, "")
    // very long lines (minified files) are cut with a marker instead of flooding the result
    Files.writeString(root.resolve("long.txt"), "x" * (Host.CatMaxLineChars + 7) + "\nshort\n")
    cat("long.txt", 1, 2)
    assertEquals(agentOut.toString, "     1\t" + "x" * Host.CatMaxLineChars + " ... [+7 chars]\n     2\tshort\n")
    agentOut.clear(); userOut.clear()

  test("listings and grep hits are relative to the working directory, absolute outside it"):
    assert(ls(".").contains("src"), ls(".").toString)
    assert(walk(".").contains("src/A.scala"), walk(".").toString)
    assert(find(".", "*.scala").contains("src/A.scala"))
    assertEquals(grepRecursive("src", "object A").map(_.file), List("src/A.scala"))
    val outside = Files.createTempDirectory("atc-outside").nn.toRealPath().nn
    Files.writeString(outside.resolve("o.txt"), "x")
    decisions = List(Decision.AllowSession)
    requestFiles(outside.toString, atc.lib.Access.Read, "look outside") {
      assertEquals(ls(outside.toString), List(outside.resolve("o.txt").toString)) // absolute: not under cwd
    }

  test("find matches the name for a plain glob and the relative path for one with / or **"):
    Files.createDirectories(root.resolve("g/deep/er"))
    Files.writeString(root.resolve("g/top.scala"), "")
    Files.writeString(root.resolve("g/deep/mid.scala"), "")
    Files.writeString(root.resolve("g/deep/er/low.scala"), "")
    Files.writeString(root.resolve("g/deep/er/note.txt"), "note")
    assertEquals(find("g", "*.scala").sorted, List("g/deep/er/low.scala", "g/deep/mid.scala", "g/top.scala"))
    assertEquals(find("g", "**/*.scala").sorted, List("g/deep/er/low.scala", "g/deep/mid.scala", "g/top.scala"))
    assertEquals(find("g", "deep/**/*.scala").sorted, List("g/deep/er/low.scala", "g/deep/mid.scala"))
    assertEquals(find("g", "deep/*.scala"), List("g/deep/mid.scala"))
    assertEquals(find("g", "**/er/*.{scala,txt}").sorted, List("g/deep/er/low.scala", "g/deep/er/note.txt"))
    assertEquals(find(".", "g/**").sorted, walk("g").filter(p => p.endsWith(".scala") || p.endsWith(".txt")).sorted)
    assertEquals(grepRecursive("g", "note", "**/er/*.txt").map(_.file), List("g/deep/er/note.txt"))

  test("move and copy files; classified and directory sources are refused"):
    Files.writeString(root.resolve("mv.txt"), "payload")
    move("mv.txt", "moved/mv2.txt")
    assert(!Files.exists(root.resolve("mv.txt")))
    assertEquals(Files.readString(root.resolve("moved/mv2.txt")), "payload")
    copy("moved/mv2.txt", "copy.txt")
    assertEquals(Files.readString(root.resolve("copy.txt")), "payload")
    assert(Files.exists(root.resolve("moved/mv2.txt")))
    intercept[IllegalArgumentException](move("moved", "elsewhere"))
    val e = intercept[SecurityException](move("secrets/key.txt", "leaked.txt")) // the read refuses classified
    assert(e.getMessage.nn.contains("readClassified"), e.getMessage)
    assert(Files.exists(root.resolve("secrets/key.txt")) && !Files.exists(root.resolve("leaked.txt")))
    intercept[SecurityException](copy("README.md", "secrets/copy.txt")) // the write refuses a classified target

  test("replaceLines/insertLines edit by cat's line numbers, return the old text, keep the newline style"):
    Files.writeString(root.resolve("lines.txt"), "one\ntwo\nthree\nfour\n")
    assertEquals(replaceLines("lines.txt", 2, 3, "TWO\nTHREE\nextra"), "two\nthree")
    assertEquals(Files.readString(root.resolve("lines.txt")), "one\nTWO\nTHREE\nextra\nfour\n")
    assertEquals(replaceLines("lines.txt", 4, 4, ""), "extra") // empty text deletes
    assertEquals(Files.readString(root.resolve("lines.txt")), "one\nTWO\nTHREE\nfour\n")
    insertLines("lines.txt", 1, "zero")
    insertLines("lines.txt", 6, "five\n") // lineCount + 1 appends; one trailing newline is not an extra line
    assertEquals(Files.readString(root.resolve("lines.txt")), "zero\none\nTWO\nTHREE\nfour\nfive\n")
    val e = intercept[IllegalArgumentException](replaceLines("lines.txt", 5, 9, "x"))
    assert(e.getMessage.nn.contains("has 6 lines"), e.getMessage)
    intercept[IllegalArgumentException](insertLines("lines.txt", 8, "x"))
    intercept[IllegalArgumentException](replaceLines("lines.txt", 0, 1, "x"))
    Files.writeString(root.resolve("crlf.txt"), "a\r\nb\r\n")
    assertEquals(replaceLines("crlf.txt", 2, 2, "B"), "b")
    assertEquals(Files.readString(root.resolve("crlf.txt")), "a\r\nB\r\n")
    Files.writeString(root.resolve("nonl.txt"), "a\nb")
    replaceLines("nonl.txt", 1, 1, "A")
    assertEquals(Files.readString(root.resolve("nonl.txt")), "A\nb") // no trailing newline stays that way
    Files.writeString(root.resolve("empty2.txt"), "")
    insertLines("empty2.txt", 1, "first")
    assertEquals(Files.readString(root.resolve("empty2.txt")), "first\n")

  test("parseCommandLine, globRegex and splitLines (pure helpers)"):
    assertEquals(
      Processes.parseCommandLine("""git commit -m 'a b' --x="c d" e\ f"""),
      List("git", "commit", "-m", "a b", "--x=c d", "e f")
    )
    intercept[IllegalArgumentException](Processes.parseCommandLine("a > b")) // one program only
    intercept[IllegalArgumentException](Processes.parseCommandLine("a | b"))
    val p = Processes.parsePipeline("cat < in.txt | sort -u 2>&1 | head -3 >> out.txt")
    assertEquals(p.stages.map(_.argv), List(List("cat"), List("sort", "-u"), List("head", "-3")))
    assertEquals(p.stages.map(_.mergeErr), List(false, true, false))
    assertEquals((p.stdinFile, p.stdoutFile, p.append), (Some("in.txt"), Some("out.txt"), true))
    assertEquals(p.line, "cat | sort -u 2>&1 | head -3 < in.txt >> out.txt")
    val q = Processes.parsePipeline("echo 'a | b' >out.txt")
    assertEquals((q.stages.head.argv, q.stdoutFile, q.append), (List("echo", "a | b"), Some("out.txt"), false))
    assertEquals(
      Processes.parsePipeline("printf 2 > two.txt").stages.head.argv,
      List("printf", "2")
    ) // a bare 2 before > is a word...
    for bad <- List(
        "a && b",
        "a; b",
        "a || b",
        "a &",
        "a 2> err",
        "a &> all",
        "a > ",
        "a | ",
        "| a",
        "$(x)",
        "a `b`",
        "a << EOF",
        "",
        "a < x < y",
        "a > x > y"
      )
    do
      intercept[IllegalArgumentException](Processes.parsePipeline(bad))
    intercept[IllegalArgumentException](Processes.parseCommandLine("a 'unterminated"))
    assertEquals(Processes.parseCommandLine("'/path with space/x' arg"), List("/path with space/x", "arg"))
    val r = Host.globRegex("src/**/*.scala")
    assert(
      r.matches("src/X.scala") && r.matches("src/a/b/X.scala") && !r.matches("lib/X.scala") && !r.matches("src/X.java")
    )
    assert(
      Host.globRegex("**/test/*.py").matches("test/a.py") && Host.globRegex("**/test/*.py").matches("x/y/test/a.py")
    )
    assert(
      Host.globRegex("a/[!x]*.{md,txt}").matches("a/b.md") && !Host.globRegex("a/[!x]*.{md,txt}").matches("a/x.md")
    )
    assert(!Host.globRegex("a/*.md").matches("a/b/c.md"))
    assertEquals(Host.splitLines("a\nb\n"), (List("a", "b"), "\n", true))
    assertEquals(Host.splitLines("a\r\nb"), (List("a", "b"), "\r\n", false))
    assertEquals(Host.splitLines(""), (Nil, "\n", true))
    assertEquals(Host.splitLines("\n"), (List(""), "\n", true))

  test("readBytes/writeBytes round-trip a binary file byte for byte"):
    val bytes = Array[Byte](0, 1, 2, -1, -128, 127, 10, 13)
    Files.write(root.resolve("bin.dat"), bytes)
    assert(readBytes("bin.dat").sameElements(bytes))
    writeBytes("copy.dat", readBytes("bin.dat"))
    assert(Files.readAllBytes(root.resolve("copy.dat")).nn.sameElements(bytes))

  test("writeBytes is refused on a classified path, like write"):
    intercept[SecurityException](writeBytes("secrets/x.dat", Array[Byte](1, 2)))

  test("forEachLine streams lines with 1-based numbers"):
    write("lines.txt", "alpha\nbeta\ngamma")
    val seen = collection.mutable.ListBuffer[(String, Int)]()
    access("lines.txt").forEachLine((line, n) => seen += ((line, n)))
    assertEquals(seen.toList, List(("alpha", 1), ("beta", 2), ("gamma", 3)))

  test("forEachLine and grep tolerate non-UTF-8 bytes like read() does (no abort on binary files)"):
    val bytes = "ok\n".getBytes("UTF-8") ++ Array[Byte](0xff.toByte, 0xfe.toByte) ++ " bad\nend\n".getBytes("UTF-8")
    Files.write(root.resolve("latin.txt"), bytes)
    val seen = collection.mutable.ListBuffer[String]()
    access("latin.txt").forEachLine((line, _) => seen += line)
    assertEquals(seen.size, 3)
    assertEquals(seen.head, "ok")
    assertEquals(seen.last, "end")
    assertEquals(seen(1), read("latin.txt").linesIterator.toList(1)) // same replacement as read()
    assertEquals(grep("latin.txt", "end").map(_.lineNumber), List(3))
    assert(grepRecursive(".", "ok").exists(_.file.endsWith("latin.txt")))

  test("outside cwd is denied with a request hint"):
    val e = intercept[SecurityException](read("/etc/hosts"))
    assert(e.getMessage.nn.contains("requestFiles"), e.getMessage)

  test("classified: plain read denied, classified read ok, listing rules"):
    val e = intercept[SecurityException](read("secrets/key.txt"))
    assert(e.getMessage.nn.contains("readClassified"))
    assertEquals(ClassifiedImpl.get(readClassified("secrets/key.txt")), "s3cret")
    assert(access("secrets/key.txt").isClassified)
    intercept[SecurityException](access("secrets/key.txt").size)
    // the classified dir itself is visible in its parent, but not enterable
    val top = ls(".").map(p => Path.of(p).getFileName.toString)
    assert(top.contains("secrets"))
    assert(!top.contains("private"), top.toString) // no access -> invisible
    intercept[SecurityException](ls("secrets"))
    val inside = ClassifiedImpl.get(access("secrets").childrenClassified).map(p => Path.of(p).getFileName.toString)
    assertEquals(inside.sorted, List("key.txt", "sub"))
    val walked = walk(".").map(rel)
    assert(walked.contains("secrets"))
    assert(!walked.exists(_.startsWith("secrets/")), walked.toString)
    assert(!walked.exists(_.startsWith("private")))
    val walkedC = ClassifiedImpl.get(access(".").walkClassified()).map(rel)
    assert(walkedC.contains("secrets/sub/deep.txt"))

  test("classified writes"):
    intercept[SecurityException](write("secrets/new.txt", "x"))
    writeClassified("secrets/new.txt", classify("x"))
    assertEquals(Files.readString(root.resolve("secrets/new.txt")), "x")
    // declassification by writing into an unclassified path is refused
    intercept[SecurityException](writeClassified("src/leak.txt", classify("x")))
    assert(!Files.exists(root.resolve("src/leak.txt")))

  test("request scope grants access outside cwd once"):
    val other = Files.createTempDirectory("atc-other").nn.toRealPath().nn
    Files.writeString(other.resolve("o.txt"), "outside")
    decisions = List(Decision.AllowOnce)
    val got = requestFiles(other.toString, atc.lib.Access.Read, "test") {
      intercept[SecurityException](write(s"$other/w.txt", "x"))
      read(s"$other/o.txt")
    }
    assertEquals(got, "outside")
    intercept[SecurityException](read(s"$other/o.txt"))
    decisions = Nil
    intercept[SecurityException](requestFiles(other.toString, atc.lib.Access.Read, "again") { 1 })

  test("exec policy and output"):
    val r = exec("echo", List("hi there"))
    assertEquals(r.exitCode, 0)
    assertEquals(r.stdout.trim, "hi there")
    val e = intercept[SecurityException](exec("ls"))
    assert(e.getMessage.nn.contains("requestExec"))
    decisions = List(Decision.AllowSession)
    assertEquals(requestExec(Set("ls*"), "list") { exec("ls", List(root.toString)).exitCode }, 0)
    assertEquals(exec("ls", Nil, root.toString).exitCode, 0) // session grant persists

  test("a command that runs long is shown live after Processes.LiveAfterMs, a quick one is not"):
    import scala.collection.mutable.ListBuffer
    val begun = ListBuffer[Long]()
    val seen = StringBuilder()
    val live = new Processes.LiveOutput:
      def begin(): Unit = begun += System.nanoTime()
      def output(text: String): Unit = seen.synchronized(seen.append(text))
    def sh(script: String) =
      Processes.run(ProcessBuilder("sh", "-c", script), "sh", 10_000L, Some(live))
    val quick = sh("echo quick")
    assertEquals(quick.stdout, "quick\n")
    assertEquals(begun.size, 0) // done before the live threshold: nothing shown
    assertEquals(seen.toString, "")
    val start = System.nanoTime()
    val slow = sh("echo early; echo err >&2; sleep 1.6; echo late")
    assertEquals(slow.stdout, "early\nlate\n")
    assertEquals(slow.stderr, "err\n")
    assertEquals(begun.size, 1, "went live once")
    val liveAfterMs = (begun.head - start) / 1_000_000L
    assert(liveAfterMs >= Processes.LiveAfterMs - 50 && liveAfterMs < 1500, s"went live after $liveAfterMs ms")
    // The early output (both streams) is delivered when it goes live, the rest as it comes.
    val text = seen.toString
    assert(text.contains("early\n") && text.contains("err\n") && text.endsWith("late\n"), text)
    // Through the host, the port hears the command line and its output.
    val env = TestEnv(commands = List("sh *"))
    import env.{host as h, given}
    h.exec("sh", List("-c", "echo a; sleep 1.3; echo b"))
    assertEquals(env.liveCommands.toList, List("sh -c echo a; sleep 1.3; echo b"))
    assertEquals(env.liveCommandOut.toString, "a\nb\n")
    h.exec("sh", List("-c", "echo nope"))
    assertEquals(env.liveCommands.size, 1)

  test("deny lists refuse commands and hosts, and cannot be granted"):
    val denyPolicy = Policy(
      List(rule(".", Some(Access.Write))),
      List("echo*"), // allowed by the allow list ...
      List("*"),
      _ => Decision.AllowSession, // ... and the user would say yes to anything
      List("echo secret*"), // ... but these are refused outright
      List("*.internal")
    )
    val denyHost = Host(denyPolicy, root, output, llm, hostUi)
    given fs: FileSystem = denyHost.fileSystem
    given ex: Exec = denyHost.processes
    given net: Network = denyHost.network
    assertEquals(denyHost.exec("echo", List("ok")).stdout.trim, "ok")
    val e = intercept[SecurityException](denyHost.exec("echo", List("secret", "key")))
    assert(e.getMessage.nn.contains("denyCommands pattern 'echo secret*'"), e.getMessage)
    assert(!e.getMessage.nn.contains("requestExec"), e.getMessage) // asking cannot help
    val e2 = intercept[SecurityException](denyHost.requestExec(Set("echo secret*"), "try") { 1 })
    assert(e2.getMessage.nn.contains("may not be granted"), e2.getMessage)
    val e3 = intercept[SecurityException](denyHost.httpGet("http://db.internal/x"))
    assert(e3.getMessage.nn.contains("denyHosts pattern '*.internal'"), e3.getMessage)
    val e4 = intercept[SecurityException](denyHost.requestNetwork(Set("db.internal"), "try") { 1 })
    assert(e4.getMessage.nn.contains("may not be granted"), e4.getMessage)
    assertEquals(denyPolicy.openScopeCount, 0)

  test("network host policy"):
    val e = intercept[SecurityException](httpGet("http://nope.invalid/x"))
    assert(e.getMessage.nn.contains("requestNetwork"))

  test("print splits agent and user views"):
    println(classify("real"))
    println("same")
    assertEquals(agentOut.toString, "Classified(***)\nsame\n")
    assertEquals(userOut.toString, "<real\n>same\n")

  test("entries listed through children/walk are judged by their target, like paths given by name"):
    // A link in a readable directory to a classified file: `read` by name is
    // refused, and an entry obtained from a listing must be refused the same
    // way (it used to carry the link's path and be judged at the link's location).
    val env = TestEnv(TestEnv.withSecrets)
    import env.given
    given fs: FileSystem = env.host.fileSystem
    env.file("secrets/key.txt", "THE-SECRET")
    env.dir("pub")
    Files.createSymbolicLink(env.root.resolve("pub/link.txt"), env.root.resolve("secrets/key.txt"))
    val target = env.root.resolve("secrets/key.txt").toString
    intercept[SecurityException](env.host.read("pub/link.txt"))
    val listed = env.host.access("pub").children
    assertEquals(listed.map(_.path), List(target), "a link is listed as its target")
    assert(listed.head.isClassified)
    intercept[SecurityException](listed.head.read())
    intercept[SecurityException](env.host.access("pub").walk().head.read())
    // `ls` shows the same canonical path (relative to the root, as listings are), and
    // `readClassified` stays the way in.
    assertEquals(env.host.ls("pub"), List("secrets/key.txt"))
    env.host.readClassified(listed.head.path)

  test("symlink escaping cwd is judged by its target"):
    val outside = Files.createTempDirectory("atc-link-target").nn.toRealPath().nn
    Files.writeString(outside.resolve("t.txt"), "target")
    Files.createSymbolicLink(root.resolve("link"), outside)
    intercept[SecurityException](read("link/t.txt"))
    val top = ls(".").map(p => Path.of(p).getFileName.toString)
    assert(!top.contains("link"), top.toString)
