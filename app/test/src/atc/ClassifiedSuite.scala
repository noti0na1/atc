package atc

import atc.host.*
import atc.lib.{Classified, FileEntry, FileSystem}
import atc.perms.*

import java.nio.file.{Files, Path}

/** `Classified` semantics: the host implementation as a value, and the file
  * system / output / LLM sinks that enforce the classified boundary
  * (migrated from TACIT's ClassifiedSuite, adapted to ATC's policy model). */
class ClassifiedSuite extends munit.FunSuite:

  private val nl = System.lineSeparator

  // ── ClassifiedImpl as a value ────────────────────────────────────

  test("wrap creates a classified value whose toString hides the content"):
    val c = ClassifiedImpl.wrap("secret-password")
    assertEquals(c.toString, "Classified(***)")
    assertEquals(s"$c", "Classified(***)")
    assertEquals(ClassifiedImpl.get(c), "secret-password")

  test("map transforms with a pure function and stays classified"):
    val upper = ClassifiedImpl.wrap("hello").map(_.toUpperCase)
    assertEquals(upper.toString, "Classified(***)")
    assertEquals(ClassifiedImpl.get(upper), "HELLO")

  test("flatMap chains classified computations"):
    val r = ClassifiedImpl.wrap(21).flatMap(x => ClassifiedImpl.wrap(x * 2))
    assertEquals(r.toString, "Classified(***)")
    assertEquals(ClassifiedImpl.get(r), 42)

  test("zip combines two classified values"):
    val z = ClassifiedImpl.wrap("a").zip(ClassifiedImpl.wrap(1))
    assertEquals(ClassifiedImpl.get(z), ("a", 1))

  test("an exception thrown inside map does not leak the value"):
    val secret = ClassifiedImpl.wrap("super-secret-password")
    val failed = secret.map(s => throw RuntimeException(s"leaked: $s"))
    assertEquals(failed.toString, "Classified(***)")
    assert(ClassifiedImpl.unwrap(failed).isFailure)
    // the sink accessor raises a sanitized error, never the original one
    val e = intercept[IllegalStateException](ClassifiedImpl.get(failed))
    assert(!e.getMessage.nn.contains("super-secret"), e.getMessage)
    assert(e.getMessage.nn.toLowerCase.contains("failed computation"), e.getMessage)

  test("an exception thrown inside flatMap does not leak the value"):
    val failed = ClassifiedImpl.wrap("s3cret").flatMap(s => throw RuntimeException(s"leaked: $s"))
    assertEquals(failed.toString, "Classified(***)")
    assert(ClassifiedImpl.unwrap(failed).isFailure)

  test("a fatal throwable inside map propagates, it is not masked as a classified failure"):
    // `Try` traps only `NonFatal`, so a fatal throwable escapes `map` and aborts the
    // evaluation (rather than becoming a masked failure). It cannot be turned into a
    // per-bit oracle because agent code may not *catch* it — see CodeValidator. Here
    // (host code, not agent code) we can catch it to observe that it did propagate.
    // (Caught by hand rather than `intercept`, so no fatal error reaches the runner.)
    var propagated = false
    try ClassifiedImpl.wrap("x").map[Int](_ => throw OutOfMemoryError("boom"))
    catch case _: OutOfMemoryError => propagated = true
    assert(propagated)

  test("ThreadDeath (the sandbox stop signal) propagates through map, never swallowed"):
    var propagated = false
    try ClassifiedImpl.wrap("x").map[Int](_ => throw ThreadDeath())
    catch case _: ThreadDeath => propagated = true
    assert(propagated)

  test("map on a failed value short-circuits without running the function"):
    val failed = ClassifiedImpl.wrap("secret").map(_ => throw RuntimeException("boom"))
    var executed = false
    val r = failed.map { _ =>
      executed = true; "should not run"
    }
    assert(!executed)
    assert(ClassifiedImpl.unwrap(r).isFailure)

  test("fromTry classifies a failed effect"):
    val c = ClassifiedImpl.fromTry(scala.util.Try(throw IllegalArgumentException("nope")))
    assert(ClassifiedImpl.unwrap(c).isFailure)
    assertEquals(c.toString, "Classified(***)")

  test("classified values compare by reference only (no equality oracle)"):
    val a = ClassifiedImpl.wrap("guess")
    val b = ClassifiedImpl.wrap("guess")
    assert(a != b)
    assert(a == a)

  test("a foreign Classified implementation is rejected at the sinks"):
    val foreign = new Classified[String]:
      def map[B](op: String => B): Classified[B] = this.asInstanceOf[Classified[B]]
      def flatMap[B](op: String => Classified[B]): Classified[B] = this.asInstanceOf[Classified[B]]
    intercept[SecurityException](ClassifiedImpl.unwrap(foreign))
    intercept[SecurityException](ClassifiedImpl.get(foreign))

  // ── Host-level enforcement ──────────────────────────────────────

  val env = TestEnv(mkRules = TestEnv.withSecrets, prefix = "atc-classified")
  import env.given
  import env.host.*
  given fs: FileSystem = env.host.fileSystem

  env.file("public.txt", "public data")
  env.file("secrets/data.txt", "TOP SECRET DATA")
  env.file("secrets/docs/deep.txt", "DEEPER SECRET")
  env.file(".env", "API_KEY=abc")
  env.file("config/.env", "NESTED=1")

  private def secret(rel: String): FileEntry = access(env.root.resolve(rel).toString)

  test("isClassified reflects the policy"):
    assert(secret("secrets/data.txt").isClassified)
    assert(secret("secrets/docs/deep.txt").isClassified)
    assert(secret("secrets").isClassified)
    assert(secret(".env").isClassified)
    assert(secret("config/.env").isClassified)
    assert(!secret("public.txt").isClassified)
    assert(!secret("config").isClassified)

  test("content-revealing operations on a classified file throw and name the alternative"):
    val f = secret("secrets/data.txt")
    for op <- List[() => Any](
        () => f.read(),
        () => f.readBytes(),
        () => f.readLines(),
        () => f.size,
        () => f.forEachLine((_, _) => ()),
        () => read("secrets/data.txt"),
        () => readLines("secrets/data.txt"),
        () => grep("secrets/data.txt", "SECRET"),
        () => cat("secrets/data.txt"),
        () => sed("secrets/data.txt", "S", "x"),
        () => replaceLines("secrets/data.txt", 1, 1, "x"),
        () => insertLines("secrets/data.txt", 1, "x"),
      )
    do
      val e = intercept[SecurityException](op())
      assert(e.getMessage.nn.contains("classified"), e.getMessage)
      assert(e.getMessage.nn.contains("readClassified"), e.getMessage)

  test("move/copy out of a classified path are refused (the read check fires first), nothing is created"):
    intercept[SecurityException](move("secrets/data.txt", "moved.txt"))
    intercept[SecurityException](copy("secrets/data.txt", "copied.txt"))
    assert(!env.existsOnDisk("moved.txt") && !env.existsOnDisk("copied.txt"))
    assertEquals(env.contents("secrets/data.txt"), "TOP SECRET DATA") // and the source is untouched

  test("plain writes into a classified path are refused"):
    val f = secret("secrets/data.txt")
    val e1 = intercept[SecurityException](f.write("nope"))
    assert(e1.getMessage.nn.contains("writeClassified"), e1.getMessage)
    intercept[SecurityException](f.append("nope"))
    intercept[SecurityException](write("secrets/other.txt", "x"))
    intercept[SecurityException](append("secrets/other.txt", "x"))
    assertEquals(env.contents("secrets/data.txt"), "TOP SECRET DATA")
    assert(!env.existsOnDisk("secrets/other.txt"))

  test("structure of a classified directory is hidden from plain listings"):
    val d = secret("secrets")
    val e = intercept[SecurityException](d.children)
    assert(e.getMessage.nn.contains("childrenClassified"), e.getMessage)
    intercept[SecurityException](d.walk())
    intercept[SecurityException](ls("secrets"))
    intercept[SecurityException](walk("secrets"))
    // ... but its existence in the parent is visible, and it is not entered
    val top = ls(".").map(p => Path.of(p).getFileName.toString)
    assert(top.contains("secrets"), top.toString)
    val walked = walk(".").map(env.rel)
    assert(walked.contains("secrets"))
    assert(!walked.exists(_.startsWith("secrets/")), walked.toString)
    // classified files under a non-classified directory are listed but unreadable
    assert(walked.contains(".env"), walked.toString)
    assert(walked.contains("config/.env"), walked.toString)
    intercept[SecurityException](read(".env"))

  test("grepRecursive and find skip / respect classified files"):
    val matches = grepRecursive(".", "SECRET|API_KEY|public")
    assert(matches.exists(_.file.endsWith("public.txt")), matches.toString)
    assert(!matches.exists(_.line.contains("SECRET")), matches.toString)
    assert(!matches.exists(_.line.contains("API_KEY")), matches.toString)
    val found = find(".", "*.txt").map(env.rel)
    assert(found.contains("public.txt"))
    assert(!found.exists(_.startsWith("secrets/")), found.toString)

  test("metadata operations work on classified paths"):
    val f = secret("secrets/data.txt")
    assert(f.exists)
    assert(!f.isDirectory)
    assertEquals(f.name, "data.txt")
    assert(f.path.endsWith("secrets/data.txt"))
    assert(secret("secrets").isDirectory)
    assert(!secret("secrets/missing.txt").exists)
    assert(exists("secrets/data.txt"))
    assert(isDirectory("secrets"))

  test("classified read/write round-trip through map"):
    writeClassified("secrets/round.txt", classify("original-secret"))
    val read1 = readClassified("secrets/round.txt")
    assertEquals(read1.toString, "Classified(***)")
    val transformed = read1.map(s => s"processed: $s")
    secret("secrets/round2.txt").writeClassified(transformed)
    assertEquals(env.contents("secrets/round2.txt"), "processed: original-secret")
    val check = readClassified("secrets/round2.txt").map(_.startsWith("processed:"))
    assertEquals(ClassifiedImpl.get(check), true)
    // writing creates parent directories inside the classified subtree
    writeClassified("secrets/new/dir/x.txt", classify("x"))
    assertEquals(env.contents("secrets/new/dir/x.txt"), "x")

  test("readClassified works on any readable file; writeClassified only on classified paths"):
    assertEquals(ClassifiedImpl.get(readClassified("public.txt")), "public data")
    val e = intercept[SecurityException](writeClassified("public-copy.txt", classify("x")))
    assert(e.getMessage.nn.contains("declassify"), e.getMessage)
    assert(!env.existsOnDisk("public-copy.txt"))
    intercept[SecurityException](secret("public.txt").writeClassified(classify("x")))
    assertEquals(env.contents("public.txt"), "public data")

  test("a classified value cannot be laundered through a non-classified path"):
    val s = readClassified("secrets/data.txt")
    intercept[SecurityException](writeClassified("leak.txt", s))
    intercept[SecurityException](writeClassified(env.root.resolve("leak2.txt").toString, s.map(_.toUpperCase)))
    assert(!env.existsOnDisk("leak.txt") && !env.existsOnDisk("leak2.txt"))

  test("writeClassified of a failed computation is failure-blind for the agent"):
    // The failure bit must not reach the agent (a pure `map` failing conditionally
    // on the secret would be a per-bit oracle). It must not leak through a thrown
    // exception NOR through the target's existence: the file is created either way
    // (empty on failure), so `exists` cannot distinguish success from failure. The
    // user sees a sanitized note; the agent gets nothing.
    env.clearOutput()
    val failed = readClassified("secrets/data.txt").map(s => throw RuntimeException(s"oops $s"))
    writeClassified("secrets/failed.txt", failed) // no throw
    assert(env.existsOnDisk("secrets/failed.txt")) // created, so existence does not reveal the failure
    assertEquals(env.contents("secrets/failed.txt"), "") // but empty: no content was written
    assert(env.userOut.toString.contains("failed computation"), env.userOut.toString)
    assert(!env.userOut.toString.contains("TOP SECRET"), env.userOut.toString)
    assert(!env.agentOut.toString.contains("failed computation"), env.agentOut.toString)

  test("writeClassified masks post-unwrapping I/O failures"):
    // A directory is a predictably invalid write target on every supported
    // platform. Success and classified-computation failure must both return
    // normally and expose no bit through the agent channel.
    env.dir("secrets/write-target")
    env.clearOutput()
    writeClassified("secrets/write-target", classify("secret-value"))
    val failed = classify("secret-value").map(value => throw RuntimeException(value))
    writeClassified("secrets/write-target", failed)
    assertEquals(env.agentOut.toString, "")
    assertEquals(env.userOut.toString.linesIterator.count(_.contains("writing")), 2)

  test("readClassified of an unreadable path fails inside the Classified"):
    val outside = TestEnv.outsideDir("nope")
    val c = readClassified(s"$outside/o.txt")
    assertEquals(c.toString, "Classified(***)")
    assert(ClassifiedImpl.unwrap(c).isFailure)

  test("childrenClassified and walkClassified reveal structure only as Classified"):
    val kids = ClassifiedImpl.get(secret("secrets").childrenClassified).map(p => Path.of(p).getFileName.toString)
    assert(kids.contains("data.txt") && kids.contains("docs"), kids.toString)
    val all = ClassifiedImpl.get(secret(".").walkClassified()).map(env.rel)
    assert(all.contains("secrets/docs/deep.txt"), all.toString)
    assert(all.contains("public.txt"))

  test("delete and mkdir on classified paths need only write access (content is not revealed)"):
    writeClassified("secrets/tmp.txt", classify("x"))
    delete("secrets/tmp.txt")
    assert(!env.existsOnDisk("secrets/tmp.txt"))
    mkdir("secrets/made")
    assert(Files.isDirectory(env.root.resolve("secrets/made")))

  test("classified sub-directory cannot be opened wider through requestFiles"):
    // Granting more access to `secrets/docs` (a classified subtree) still keeps it classified.
    env.decisions = List(Decision.AllowSession)
    val got = requestFiles(env.root.resolve("secrets/docs").toString, atc.lib.Access.Write, "bypass attempt") {
      intercept[SecurityException](read(env.root.resolve("secrets/docs/deep.txt").toString))
      intercept[SecurityException](ls(env.root.resolve("secrets/docs").toString))
      ClassifiedImpl.get(readClassified(env.root.resolve("secrets/docs/deep.txt").toString))
    }
    assertEquals(got, "DEEPER SECRET")
    assert(env.requests.isEmpty, "write on the cwd is already held: no prompt expected")

  // ── Output sinks ────────────────────────────────────────────────

  test("println: the agent sees a mask, the user sees the content"):
    env.clearOutput()
    println(classify("real"))
    println("same")
    print(classify("p"))
    println()
    printf("score=%d name=%s%n", 42, classify("alice"))
    assertEquals(env.agentOut.toString, s"Classified(***)\nsame\nClassified(***)\nscore=42 name=Classified(***)$nl")
    assertEquals(env.userOut.toString, s"<real\n>same\n<p>\n<score=42 name=alice$nl>")

  test("println of a failed classified value shows the error to the user only"):
    env.clearOutput()
    println(classify("v").map(s => throw RuntimeException(s"boom-$s")))
    assertEquals(env.agentOut.toString, "Classified(***)\n")
    assert(env.userOut.toString.contains("<classified error: boom-v>"), env.userOut.toString)

  test("classified rendering failures cannot escape through println, print, or printf"):
    env.clearOutput()
    val value = classify("RENDER-SECRET").map(secret =>
      new Object:
        override def toString: String = throw RuntimeException(secret)
    )
    println(value)
    print(value)
    printf("%s%n", value)
    assertEquals(
      env.agentOut.toString,
      s"Classified(***)\nClassified(***)Classified(***)$nl",
    )
    assert(!env.agentOut.toString.contains("RENDER-SECRET"), env.agentOut.toString)
    assert(env.userOut.toString.contains("RENDER-SECRET"), env.userOut.toString)

  test("a malicious Throwable.getMessage stays behind the classified output boundary"):
    env.clearOutput()
    val failed = classify("MESSAGE-SECRET").map(secret =>
      throw new RuntimeException("outer"):
        override def getMessage: String = throw RuntimeException(secret)
    )
    println(failed)
    printf("%s%n", failed)
    assertEquals(env.agentOut.toString, s"Classified(***)\nClassified(***)$nl")
    assert(!env.agentOut.toString.contains("MESSAGE-SECRET"), env.agentOut.toString)
    assert(env.userOut.toString.contains("error details could not be rendered"), env.userOut.toString)

  test("printf with no classified arguments is identical for both"):
    env.clearOutput()
    printf("%s-%d%n", "a", 1)
    assertEquals(env.agentOut.toString, s"a-1$nl")
    assertEquals(env.userOut.toString, s"a-1$nl")

  // ── LLM sink ────────────────────────────────────────────────────

  test("classifiedChat(Classified) goes to the classified model and stays classified"):
    val answer = classifiedChat(readClassified("secrets/data.txt").map(_.toLowerCase))
    assertEquals(answer.toString, "Classified(***)")
    assertEquals(env.classifiedChats.toList, List("top secret data"))
    assertEquals(ClassifiedImpl.get(answer), "safe:top secret data")
    assert(env.chats.isEmpty)

  test("classifiedChat(Classified) with a failed value does not call the model"):
    val before = env.classifiedChats.size
    val failed = classify("s").map(_ => throw RuntimeException("x"))
    val r = classifiedChat(failed)
    assert(ClassifiedImpl.unwrap(r).isFailure)
    assertEquals(env.classifiedChats.size, before)

  test("chat(String) goes to the normal model"):
    assertEquals(chat("hello"), "normal:hello")
    assertEquals(env.chats.toList, List("hello"))

  test("classifiedChat(String) returns the trusted classified model's plain response"):
    assertEquals(classifiedChat("hello"), "safe:hello")
    assert(env.classifiedChats.contains("hello"), env.classifiedChats.toString)

  test("classifiedChat(Classified) masks a trusted-model failure through map"):
    val throwing = new HostLlm:
      def chat(message: String): String = message
      def classifiedChat(message: String): String = throw RuntimeException(s"provider failed on $message")
    val host = Host(env.policy, env.root, env.output, throwing, env.ui)
    val direct = intercept[RuntimeException](host.classifiedChat("PLAIN"))
    assert(direct.getMessage.nn.contains("PLAIN")) // the String overload is an ordinary pure primitive
    val wrapped = host.classifiedChat(host.classify("WRAPPED-SECRET"))
    assertEquals(wrapped.toString, "Classified(***)")
    assert(ClassifiedImpl.unwrap(wrapped).isFailure)
    assert(!env.agentOut.toString.contains("WRAPPED-SECRET"), env.agentOut.toString)
