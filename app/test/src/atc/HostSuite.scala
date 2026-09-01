package atc

import atc.host.*
import atc.lib.{Exec, FileSystem, IOCap, Network, UserIO}
import atc.perms.*
import atc.platform.{PathGlob, Platform, PlatformPath}
import java.nio.file.{Files, LinkOption, Path}

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
    def classifiedChat(m: String) = s"s:$m"

  var decisions: List[Decision] = Nil
  val prompter: PermissionPrompter = _ =>
    decisions match
      case d :: rest => decisions = rest; d
      case Nil => Decision.Deny

  def rule(p: String, a: Option[Access] = None, c: Option[Boolean] = None, locked: Boolean = false) =
    FileRule(PathPattern(p, root), a, c, locked)

  private val echoCommand = ProcessFixture.command("echo")
  private val echoPattern = ProcessFixture.pattern("echo")

  val policy = Policy(
    List(
      rule(".", Some(Access.Write)),
      rule("secrets", c = Some(true)),
      rule("./private", Some(Access.None)),
    ),
    List(echoPattern),
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
    if path.isAbsolute then PlatformPath.portable(root.relativize(path)) else p

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

  test("cat retains only a capped prefix of a giant line and preserves CR/LF line semantics"):
    agentOut.clear(); userOut.clear()
    val giantChars = 2 * 1024 * 1024 + 17
    val chunk = "x" * 8192
    val writer = Files.newBufferedWriter(root.resolve("giant-line.txt"))
    try
      var remaining = giantChars
      while remaining > 0 do
        val count = math.min(remaining, chunk.length)
        writer.write(chunk, 0, count)
        remaining -= count
      writer.write("\r\nshort\rthird\nlast\n")
    finally writer.close()
    cat("giant-line.txt")
    assertEquals(
      agentOut.toString,
      "     1\t" + "x" * Host.CatMaxLineChars +
        s" ... [+${giantChars - Host.CatMaxLineChars} chars]\n" +
        "     2\tshort\n     3\tthird\n     4\tlast\n",
    )
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
      assertEquals(
        ls(outside.toString),
        List(PlatformPath.portable(outside.resolve("o.txt")))
      ) // absolute: not under cwd
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

  test("self-copy preserves content but still checks source and target permissions; exact self-move stays a no-op"):
    Files.writeString(root.resolve("self-copy.txt"), "payload")
    copy("self-copy.txt", "./self-copy.txt")
    assertEquals(Files.readString(root.resolve("self-copy.txt")), "payload")

    val secretBefore = Files.readString(root.resolve("secrets/key.txt"))
    intercept[SecurityException](copy("secrets/key.txt", "secrets/./key.txt"))
    assertEquals(Files.readString(root.resolve("secrets/key.txt")), secretBefore)
    move("secrets/key.txt", "secrets/./key.txt")
    assertEquals(Files.readString(root.resolve("secrets/key.txt")), secretBefore)

    val readOnlyRoot = Files.createTempDirectory("atc-self-copy-read-only").nn.toRealPath().nn
    Files.writeString(readOnlyRoot.resolve("same.txt"), "read only")
    val readOnlyPolicy = Policy(
      List(FileRule(PathPattern(".", readOnlyRoot), Some(Access.Read), None)),
      Nil,
      Nil,
      prompter,
    )
    val readOnlyHost = Host(readOnlyPolicy, readOnlyRoot, output, llm, hostUi)
    val readOnlyFs = readOnlyHost.fileSystem
    intercept[SecurityException](readOnlyHost.copy("same.txt", "same.txt")(using readOnlyFs))
    assertEquals(Files.readString(readOnlyRoot.resolve("same.txt")), "read only")

  test("copy and move preserve same-inode hard links and enforce alias permissions"):
    val original = root.resolve("hard-original.txt")
    Files.writeString(original, "hard-linked payload")
    val copyAlias = root.resolve("hard-copy.txt")
    val hardLinksSupported =
      try
        Files.createLink(copyAlias, original)
        true
      catch
        case _: UnsupportedOperationException | _: java.io.IOException | _: SecurityException => false
    if !hardLinksSupported then Files.deleteIfExists(original)
    assume(hardLinksSupported, "hard links are not supported on this filesystem")

    copy("hard-original.txt", "hard-copy.txt")
    assertEquals(Files.readString(original), "hard-linked payload")
    assertEquals(Files.readString(copyAlias), "hard-linked payload")

    val moveSource = root.resolve("hard-move-source.txt")
    val moveTarget = root.resolve("hard-move-target.txt")
    Files.writeString(moveSource, "move payload")
    Files.createLink(moveTarget, moveSource)
    move("hard-move-source.txt", "hard-move-target.txt")
    assert(!Files.exists(moveSource))
    assertEquals(Files.readString(moveTarget), "move payload")

    val classifiedSource = root.resolve("secrets/hard-source.txt")
    Files.createLink(classifiedSource, original)
    intercept[SecurityException](move("secrets/hard-source.txt", "hard-original.txt"))
    assert(Files.exists(classifiedSource))
    assertEquals(Files.readString(original), "hard-linked payload")

    val classifiedTarget = root.resolve("secrets/hard-target.txt")
    Files.createLink(classifiedTarget, original)
    intercept[SecurityException](copy("hard-original.txt", "secrets/hard-target.txt"))
    assertEquals(Files.readString(original), "hard-linked payload")

    val deniedTarget = root.resolve("private/hard-target.txt")
    Files.createLink(deniedTarget, original)
    intercept[SecurityException](copy("hard-original.txt", "private/hard-target.txt"))
    assertEquals(Files.readString(original), "hard-linked payload")

    val readOnlySource = root.resolve("hard-read-only-source.txt")
    val writableTarget = root.resolve("hard-writable-target.txt")
    Files.writeString(readOnlySource, "guarded move")
    Files.createLink(writableTarget, readOnlySource)
    val aliasPolicy = Policy(
      List(
        FileRule(PathPattern("hard-read-only-source.txt", root), Some(Access.Read), None),
        FileRule(PathPattern("hard-writable-target.txt", root), Some(Access.Write), None),
      ),
      Nil,
      Nil,
      prompter,
    )
    val aliasHost = Host(aliasPolicy, root, output, llm, hostUi)
    val aliasFs = aliasHost.fileSystem
    intercept[SecurityException](aliasHost.move("hard-read-only-source.txt", "hard-writable-target.txt")(using aliasFs))
    assertEquals(Files.readString(readOnlySource), "guarded move")
    assertEquals(Files.readString(writableTarget), "guarded move")

    List(
      classifiedSource,
      classifiedTarget,
      deniedTarget,
      copyAlias,
      moveTarget,
      original,
      readOnlySource,
      writableTarget,
    ).foreach(Files.deleteIfExists(_))

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
    Files.writeString(root.resolve("mixed.txt"), "a\r\nb\nc\rd")
    assertEquals(replaceLines("mixed.txt", 2, 3, "B\r\nC\n"), "b\r\nc")
    assertEquals(Files.readString(root.resolve("mixed.txt")), "a\r\nB\r\nC\r\nd")
    Files.writeString(root.resolve("bare-cr.txt"), "a\rb\r")
    insertLines("bare-cr.txt", 2, "middle\r\n")
    assertEquals(Files.readString(root.resolve("bare-cr.txt")), "a\rmiddle\rb\r")
    Files.writeString(root.resolve("empty2.txt"), "")
    insertLines("empty2.txt", 1, "first")
    assertEquals(Files.readString(root.resolve("empty2.txt")), "first\n")

  test("parseCommandLine, globRegex and splitLines (pure helpers)"):
    val escapedTail = if Platform.isWindows then List("e\\", "f") else List("e f")
    assertEquals(
      CommandLine.parseCommandLine("""git commit -m 'a b' --x="c d" e\ f"""),
      List("git", "commit", "-m", "a b", "--x=c d") ++ escapedTail
    )
    intercept[IllegalArgumentException](CommandLine.parseCommandLine("a > b")) // one program only
    intercept[IllegalArgumentException](CommandLine.parseCommandLine("a | b"))
    val p = CommandLine.parsePipeline("cat < in.txt | sort -u 2>&1 | head -3 >> out.txt")
    assertEquals(p.stages.map(_.argv), List(List("cat"), List("sort", "-u"), List("head", "-3")))
    assertEquals(p.stages.map(_.mergeErr), List(false, true, false))
    assertEquals((p.stdinFile, p.stdoutFile, p.append), (Some("in.txt"), Some("out.txt"), true))
    assertEquals(p.line, "cat | sort -u 2>&1 | head -3 < in.txt >> out.txt")
    val q = CommandLine.parsePipeline("echo 'a | b' >out.txt")
    assertEquals((q.stages.head.argv, q.stdoutFile, q.append), (List("echo", "a | b"), Some("out.txt"), false))
    assertEquals(
      CommandLine.parsePipeline("printf 2 > two.txt").stages.head.argv,
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
      intercept[IllegalArgumentException](CommandLine.parsePipeline(bad))
    intercept[IllegalArgumentException](CommandLine.parseCommandLine("a 'unterminated"))
    assertEquals(CommandLine.parseCommandLine("'/path with space/x' arg"), List("/path with space/x", "arg"))
    assertEquals(CommandLine.Stage(List("echo", "a b", "")).line, "echo \"a b\" \"\"")
    intercept[IllegalArgumentException](
      CommandLine.parsePipeline(List.fill(CommandLine.MaxPipelineStages + 1)("echo").mkString(" | "))
    )
    val r = PathGlob.regex("src/**/*.scala")
    assert(
      r.matches("src/X.scala") && r.matches("src/a/b/X.scala") && !r.matches("lib/X.scala") && !r.matches("src/X.java")
    )
    assert(
      PathGlob.regex("**/test/*.py").matches("test/a.py") && PathGlob.regex("**/test/*.py").matches("x/y/test/a.py")
    )
    assert(
      PathGlob.regex("a/[!x]*.{md,txt}").matches("a/b.md") &&
        !PathGlob.regex("a/[!x]*.{md,txt}").matches("a/x.md")
    )
    assert(!PathGlob.regex("a/*.md").matches("a/b/c.md"))
    if Platform.isWindows then assert(PathGlob.regex("SRC/**/*.SCALA").matches("src/main/A.scala"))

  test("Windows path validation rejects device aliases and ambiguous components"):
    for path <- List(
        "NUL.txt",
        "NUL .txt",
        "CON",
        "COM1.log",
        "COM¹.txt",
        "LPT³",
        "dir/name.",
        "dir/name ",
        "//?/C:/work/x",
        "//./pipe/x",
        "C:work/x",
        "C:/work/file.txt:stream",
      )
    do
      assert(PlatformPath.windowsValidationError(path).nonEmpty, path)
    for path <- List("C:/work/file.txt", ".env") do
      assertEquals(PlatformPath.windowsValidationError(path), None, path)
    assertEquals(
      ScalaSource.stringLiteral("C:\\Users\\alice\nnotes\".txt"),
      "\"C:\\\\Users\\\\alice\\nnotes\\\".txt\"",
    )

  test("readBytes/writeBytes round-trip a binary file byte for byte"):
    val bytes = Array[Byte](0, 1, 2, -1, -128, 127, 10, 13)
    Files.write(root.resolve("bin.dat"), bytes)
    assert(readBytes("bin.dat").sameElements(bytes))
    writeBytes("copy.dat", readBytes("bin.dat"))
    assert(Files.readAllBytes(root.resolve("copy.dat")).nn.sameElements(bytes))

  test("a literal Unix backslash in a filename survives an API round-trip"):
    assume(!Platform.isWindows)
    val name = "back\\slash.txt"
    write(name, "content")
    val returned = access(name).path
    assert(returned.endsWith(name), returned)
    assertEquals(read(returned), "content")

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
    val r = exec(echoCommand, List("hi there"))
    assertEquals(r.exitCode, 0)
    assertEquals(r.stdout.trim, "hi there")
    val pwd = ProcessFixture.command("pwd")
    val pattern = ProcessFixture.pattern("pwd")
    val e = intercept[SecurityException](exec(pwd))
    assert(e.getMessage.nn.contains("requestExec"))
    decisions = List(Decision.AllowSession)
    assertEquals(requestExec(Set(pattern), "inspect cwd") { exec(pwd).exitCode }, 0)
    assertEquals(exec(pwd, Nil, root.toString).exitCode, 0) // session grant persists

  test("a denied executable path gets a copyable requestExec hint"):
    val executable = if Platform.isWindows then "C:\\Program Files\\Example\\tool.exe" else "/opt/Example Tools/tool"
    val error = intercept[SecurityException](exec(s"'$executable' --status"))
    val literal = ScalaSource.stringLiteral(CommandLine.Stage(List(executable, "--status")).line)
    assert(error.getMessage.nn.contains(s"requestExec(Set($literal)"), error.getMessage)

  test("a command that runs long is shown live after Processes.LiveAfterMs, a quick one is not"):
    assume(!Platform.isWindows) // intentional integration with the real POSIX shell
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
    import env.host as h
    h.exec("sh", List("-c", "echo a; sleep 1.3; echo b"))
    assertEquals(env.liveCommands.toList, List("sh -c \"echo a; sleep 1.3; echo b\""))
    assertEquals(env.liveCommandOut.toString, "a\nb\n")
    h.exec("sh", List("-c", "echo nope"))
    assertEquals(env.liveCommands.size, 1)

  test("deny lists refuse commands and hosts, and cannot be granted"):
    val denied = ProcessFixture.command("echo", "secret") + "*"
    val denyPolicy = Policy(
      List(rule(".", Some(Access.Write))),
      List(echoPattern), // allowed by the allow list ...
      List("*"),
      _ => Decision.AllowSession, // ... and the user would say yes to anything
      List(denied), // ... but these are refused outright
      List("*.internal")
    )
    val denyHost = Host(denyPolicy, root, output, llm, hostUi)
    given fs: FileSystem = denyHost.fileSystem
    given ex: Exec = denyHost.processes
    given net: Network = denyHost.network
    assertEquals(denyHost.exec(echoCommand, List("ok")).stdout.trim, "ok")
    val e = intercept[SecurityException](denyHost.exec(echoCommand, List("secret", "key")))
    assert(e.getMessage.nn.contains(s"denyCommands pattern '$denied'"), e.getMessage)
    assert(!e.getMessage.nn.contains("requestExec"), e.getMessage) // asking cannot help
    val e2 = intercept[SecurityException](denyHost.requestExec(Set(denied), "try") { 1 })
    assert(e2.getMessage.nn.contains("may not be granted"), e2.getMessage)
    val e3 = intercept[SecurityException](denyHost.httpGet("http://db.internal/x"))
    assert(e3.getMessage.nn.contains("denyHosts pattern '*.internal'"), e3.getMessage)
    val e4 = intercept[SecurityException](denyHost.requestNetwork(Set("db.internal"), "try") { 1 })
    assert(e4.getMessage.nn.contains("may not be granted"), e4.getMessage)
    assertEquals(denyPolicy.openScopeCount, 0)

  test("normalizeHost canonicalises equivalent spellings so a deny rule cannot be dodged"):
    // Normalize non-canonical decimal IPv4 forms and trailing dots.
    assertEquals(Host.normalizeHost("2852039166"), "169.254.169.254")
    assertEquals(Host.normalizeHost("169.254.169.254."), "169.254.169.254")
    // `URI.getHost` returns bracketed IPv4-mapped IPv6 addresses. Both forms of
    // the metadata address must normalize to the IPv4 literal used by deny rules.
    assertEquals(Host.normalizeHost("[::ffff:169.254.169.254]"), "169.254.169.254")
    assertEquals(Host.normalizeHost("[::ffff:a9fe:a9fe]"), "169.254.169.254")
    // Expand a pure IPv6 literal, but only lowercase a hostname; never resolve it.
    assertEquals(Host.normalizeHost("[::1]"), "0:0:0:0:0:0:0:1")
    assertEquals(Host.normalizeHost("Example.COM"), "example.com")

  test("an IPv6-mapped spelling of a denied IPv4 host is refused, not just the IPv4 form"):
    val denyPolicy = Policy(
      List(rule(".", Some(Access.Read))),
      Nil,
      List("*"), // every host allowed ...
      _ => Decision.AllowSession,
      Nil,
      List("169.254.169.254") // ... except this one, however it is spelled
    )
    val denyHost = Host(denyPolicy, root, output, llm, hostUi)
    given net: Network = denyHost.network
    val e = intercept[SecurityException](denyHost.httpGet("http://[::ffff:169.254.169.254]/latest/meta-data/"))
    assert(e.getMessage.nn.contains("denyHosts pattern"), e.getMessage)

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
    assume(
      TestEnv.trySymbolicLink(env.root.resolve("pub/link.txt"), env.root.resolve("secrets/key.txt")),
      "symbolic links are unavailable for this account",
    )
    val target = PlatformPath.portable(env.root.resolve("secrets/key.txt"))
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
    assume(TestEnv.trySymbolicLink(root.resolve("link"), outside), "symbolic links are unavailable for this account")
    intercept[SecurityException](read("link/t.txt"))
    val top = ls(".").map(p => Path.of(p).getFileName.toString)
    assert(!top.contains("link"), top.toString)

  test("a Windows directory junction escaping cwd is judged by its target"):
    assume(Platform.isWindows, "Windows junction integration test")
    val outside = Files.createTempDirectory("atc-junction-target").nn.toRealPath().nn
    Files.writeString(outside.resolve("secret.txt"), "outside")
    val junction = root.resolve("junction-out").nn
    val process = ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", junction.toString, outside.toString)
      .redirectErrorStream(true).start().nn
    val output = String(process.getInputStream.nn.readAllBytes(), java.nio.charset.Charset.defaultCharset())
    val exit = process.waitFor()
    assertEquals(exit, 0, s"could not create a junction: $output")
    assert(Files.isDirectory(junction, LinkOption.NOFOLLOW_LINKS), s"junction was not created: $output")
    intercept[SecurityException](read("junction-out/secret.txt"))
    assert(!ls(".").exists(_.contains("junction-out")), ls(".").toString)

  test("a dangling symlink is judged by its (non-existent) target"):
    // Writing through a dangling link creates its target. The policy must evaluate
    // that target even before it exists, or a link in an allowed tree could create
    // a file anywhere.
    val outside = Files.createTempDirectory("atc-dangling").nn.toRealPath().nn
    val target = outside.resolve("created.txt") // does not exist
    assume(
      TestEnv.trySymbolicLink(root.resolve("dangling.txt"), target),
      "symbolic links are unavailable for this account",
    )
    intercept[SecurityException](write("dangling.txt", "PWNED"))
    intercept[SecurityException](append("dangling.txt", "PWNED"))
    assert(!Files.exists(target))
    // A dangling symlink into the writable tree remains writable.
    val inner = root.resolve("inner-created.txt")
    assert(TestEnv.trySymbolicLink(root.resolve("dangling-ok.txt"), inner))
    write("dangling-ok.txt", "fine")
    assertEquals(Files.readString(inner), "fine")

  test("a readable symlinked directory is listed as its target but never entered"):
    Files.createDirectories(root.resolve("real/sub"))
    Files.writeString(root.resolve("real/sub/f.txt"), "f")
    assume(
      TestEnv.trySymbolicLink(root.resolve("dirlink"), root.resolve("real")),
      "symbolic links are unavailable for this account",
    )
    // List and evaluate the link as its target.
    val top = ls(".")
    assert(top.contains("real"), top.toString)
    assert(!top.exists(_.contains("dirlink")), top.toString)
    // Walk the real directory once without traversing the link.
    val walked = walk(".").map(rel)
    assert(walked.contains("real/sub/f.txt"), walked.toString)
    assert(!walked.exists(_.contains("dirlink")), walked.toString)
    assertEquals(walked.count(_ == "real/sub/f.txt"), 1, walked.toString)
    // Evaluate access through the link at its target.
    assertEquals(read("dirlink/sub/f.txt"), "f")
    assertEquals(access("dirlink/sub/f.txt").path, PlatformPath.portable(root.resolve("real/sub/f.txt")))

  test("move of a file onto itself is a no-op"):
    write("self.txt", "data")
    move("self.txt", "./self.txt")
    assertEquals(read("self.txt"), "data")

  test("TextSink incrementally decodes UTF-8, UTF-16 BOMs, and malformed bytes"):
    val sb = StringBuilder()
    val sink = TextSink(s => sb.append(s))
    for b <- "héllo 🙂 x".getBytes("UTF-8") do sink.write(Array(b)) // one byte at a time
    sink.finish()
    assertEquals(sb.toString, "héllo 🙂 x")
    val sb2 = StringBuilder()
    val sink2 = TextSink(s => sb2.append(s))
    sink2.write(Array(0xc3.toByte, 0x28.toByte)) // malformed UTF-8, not an encoding marker
    sink2.finish()
    assertEquals(sb2.toString, "\uFFFD(")
    def bomText(bom: Array[Byte], encoded: Array[Byte]): String =
      val out = StringBuilder()
      val sink = TextSink(s => out.append(s))
      for byte <- bom ++ encoded do sink.write(Array(byte))
      sink.finish()
      out.toString
    val sample = "bom 🙂"
    assertEquals(
      bomText(Array(0xef.toByte, 0xbb.toByte, 0xbf.toByte), sample.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
      sample,
    )
    assertEquals(
      bomText(Array(0xff.toByte, 0xfe.toByte), sample.getBytes(java.nio.charset.StandardCharsets.UTF_16LE)),
      sample,
    )
    assertEquals(
      bomText(Array(0xfe.toByte, 0xff.toByte), sample.getBytes(java.nio.charset.StandardCharsets.UTF_16BE)),
      sample,
    )
