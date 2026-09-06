package atc

import atc.host.{FileSystemImpl, FileChange, Host, HostOutput}
import atc.lib.{FileSystem, SearchOptions}
import atc.perms.*

class HostEditingSuite extends munit.FunSuite:
  test("locked file denials do not suggest an impossible permission request"):
    val env = TestEnv(mkRules =
      root =>
        TestEnv.defaultRules(root) :+
          FileRule(PathPattern("locked", root), Some(Access.None), None, locked = true)
    )
    env.file("locked/note.txt", "unchanged")
    given FileSystem = FileSystemImpl(ScopeId.Base, env.host)
    val read = intercept[SecurityException](env.host.read("locked/note.txt"))
    val write = intercept[SecurityException](env.host.write("locked/note.txt", "changed"))
    for error <- List(read, write) do
      assert(error.getMessage.nn.contains("Permission requests cannot widen access"))
      assert(!error.getMessage.nn.contains("Use requestFiles"))
    assertEquals(env.contents("locked/note.txt"), "unchanged")

  test("literal replacement checks uniqueness before writing and preserves literal replacement characters"):
    val env = TestEnv()
    given FileSystem = FileSystemImpl(ScopeId.Base, env.host)
    env.file("edit.txt", "first\r\ncost = $1\\n\r\nlast\r\n")
    env.host.replaceExact("edit.txt", "cost = $1\\n", "cost = $2\\t")
    assertEquals(env.contents("edit.txt"), "first\r\ncost = $2\\t\r\nlast\r\n")
    val unchanged = env.contents("edit.txt")
    intercept[IllegalArgumentException](env.host.replaceExact("edit.txt", "absent", "changed"))
    assertEquals(env.contents("edit.txt"), unchanged)
    env.file("edit.txt", "same same")
    intercept[IllegalArgumentException](env.host.replaceExact("edit.txt", "same", "changed"))
    assertEquals(env.contents("edit.txt"), "same same")

  test("range reads stop at the requested window and report work limits"):
    val env = TestEnv()
    given FileSystem = FileSystemImpl(ScopeId.Base, env.host)
    env.file("lines.txt", "one\r\ntwo\rthree\nfour")
    assertEquals(env.host.readRange("lines.txt", 2, 3), "two\nthree")
    assertEquals(env.host.readRange("lines.txt", 9, 10), "")
    intercept[IllegalArgumentException](env.host.readRange("lines.txt", 0, 2))
    intercept[IllegalArgumentException](env.host.readRange("lines.txt", 1, 1001))
    env.file("large.txt", "x" * 2100000)
    assert(env.host.readRange("large.txt", 1, 1).contains("read limit reached"))

  test("search enforces match, file, line and character budgets"):
    val env = TestEnv()
    given FileSystem = FileSystemImpl(ScopeId.Base, env.host)
    env.file("a.txt", "match\nmatch\n")
    env.file("b.txt", "match\n")
    val first = env.host.search(".", "match", "*.txt", SearchOptions(maxMatches = 1))
    assertEquals(first.matches.map(_.file), List("a.txt"))
    assertEquals(first.filesScanned, 1)
    assert(first.limited)
    val missing = env.host.search(".", "absent", "*.txt", SearchOptions(maxFiles = 1))
    assertEquals(missing.filesScanned, 1)
    assert(missing.limited)
    val lines = env.host.search(".", "match", "a.txt", SearchOptions(maxLinesPerFile = 1))
    assertEquals(lines.matches.size, 1)
    assert(lines.limited)
    val chars = env.host.search(".", "match", "a.txt", SearchOptions(maxCharsPerFile = 2))
    assertEquals(chars.matches, Nil)
    assert(chars.limited)
    val complete = env.host.search(".", "match", "*.txt", SearchOptions())
    assertEquals(complete.matches.size, 3)
    assert(!complete.limited)
    // A budget reached exactly at the end of the input cut nothing.
    for exact <- List(SearchOptions(maxMatches = 3), SearchOptions(maxFiles = 2), SearchOptions(maxLinesPerFile = 2)) do
      val result = env.host.search(".", "match", "*.txt", exact)
      assertEquals(result.matches.size, 3, exact.toString)
      assert(!result.limited, exact.toString)

  test("search and change previews respect classified paths"):
    val env = TestEnv(mkRules =
      root =>
        TestEnv.defaultRules(root) :+
          FileRule(PathPattern("secrets", root), None, Some(true))
    )
    env.file("public.txt", "before")
    env.file("secrets/key.txt", "PRIVATE_VALUE")
    val changes = collection.mutable.ListBuffer[FileChange]()
    val output = new HostOutput:
      def print(agent: String, user: String): Unit = ()
      override def fileChanged(change: FileChange): Unit = changes += change
    val host = Host(env.policy, env.root, output, env.llm, env.ui)
    given FileSystem = FileSystemImpl(ScopeId.Base, host)
    host.write("public.txt", "after")
    assert(changes.head.preview.contains("- before"))
    assert(changes.head.preview.contains("+ after"))
    assertEquals(host.search(".", "PRIVATE", "*.txt", SearchOptions()).matches, Nil)
    intercept[SecurityException](host.replaceExact("secrets/key.txt", "PRIVATE_VALUE", "changed"))
    host.writeClassified("secrets/key.txt", host.classify("OTHER_PRIVATE_VALUE"))
    assertEquals(changes.size, 1)
