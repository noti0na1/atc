package atc

import atc.host.*
import atc.lib.{Classified, Exec, FileSystem, IOCap, Network, UserIO}
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

  private def rel(p: String) = root.relativize(Path.of(p)).toString

  test("plain read/write within cwd, relative paths"):
    assertEquals(read("src/A.scala"), "object A")
    write("src/B.scala", "object B")
    assertEquals(Files.readString(root.resolve("src/B.scala")), "object B")
    append("src/B.scala", "\n// more")
    assert(Files.readString(root.resolve("src/B.scala")).nn.endsWith("// more"))
    assert(exists("README.md"))
    assertEquals(access("/a/b/c.txt").name, "c.txt")

  test("replace makes a targeted edit and reports how many occurrences it changed"):
    Files.writeString(root.resolve("edit.txt"), "alpha\nbeta\nalpha\n")
    assertEquals(replace("edit.txt", "alpha", "ALPHA"), 2)
    assertEquals(Files.readString(root.resolve("edit.txt")), "ALPHA\nbeta\nALPHA\n")

  test("replace refuses a no-op instead of rewriting the file unchanged"):
    // `write(p, read(p).replace(...))` would succeed silently and look like an edit.
    Files.writeString(root.resolve("edit2.txt"), "content")
    val e = intercept[IllegalArgumentException](replace("edit2.txt", "absent", "x"))
    assert(e.getMessage.nn.contains("does not occur"), e.getMessage)
    assertEquals(Files.readString(root.resolve("edit2.txt")), "content")
    intercept[IllegalArgumentException](replace("edit2.txt", "", "x"))

  test("readBytes/writeBytes round-trip a binary file byte for byte"):
    val bytes = Array[Byte](0, 1, 2, -1, -128, 127, 10, 13)
    Files.write(root.resolve("bin.dat"), bytes)
    assert(java.util.Arrays.equals(readBytes("bin.dat"), bytes))
    writeBytes("copy.dat", readBytes("bin.dat"))
    assert(java.util.Arrays.equals(Files.readAllBytes(root.resolve("copy.dat")), bytes))

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
    assertEquals(exec("ls", Nil, Some(root.toString)).exitCode, 0) // session grant persists

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

  test("symlink escaping cwd is judged by its target"):
    val outside = Files.createTempDirectory("atc-link-target").nn.toRealPath().nn
    Files.writeString(outside.resolve("t.txt"), "target")
    Files.createSymbolicLink(root.resolve("link"), outside)
    intercept[SecurityException](read("link/t.txt"))
    val top = ls(".").map(p => Path.of(p).getFileName.toString)
    assert(!top.contains("link"), top.toString)
