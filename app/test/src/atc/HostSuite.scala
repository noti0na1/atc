package atc

import atc.host.*
import atc.lib.{Classified, Exec, FileSystem, IOCap, Network}
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

  given io: IOCap = atc.lib.Interface.takeRootIO()
  given fs: FileSystem = host.defaultFiles
  given ex: Exec = host.defaultExec
  given net: Network = host.defaultNetwork
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
