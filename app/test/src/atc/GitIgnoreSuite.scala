package atc

import atc.host.*
import atc.lib.{FileSystem, IOCap}
import atc.perms.*

import java.nio.file.{Files, Path}

/** What the agent sees: `respectGitignore` hides ignored paths from listings
  * and searches, without changing what may be read or written by name. */
class GitIgnoreSuite extends munit.FunSuite:

  /** A repository-shaped temp tree; `ignore` is written as the root `.gitignore`. */
  private def repo(ignore: String, files: (String, String)*): Path =
    val root = Files.createTempDirectory("atc-gitignore").nn.toRealPath().nn
    Files.createDirectories(root.resolve(".git"))
    Files.writeString(root.resolve(".gitignore"), ignore)
    for (rel, content) <- files do
      val p = root.resolve(rel).nn
      Option(p.getParent).foreach(Files.createDirectories(_))
      Files.writeString(p, content)
    root

  private def ignoredIn(root: Path, rels: String*): List[Boolean] =
    val gi = GitIgnore(root)
    rels.toList.map(r => gi.ignores(root.resolve(r).nn))

  // ── pattern semantics ──

  test("component patterns match at any depth, `.git` always"):
    val root = repo("target\n*.log\n", "a.log" -> "", "src/b.log" -> "", "src/keep.txt" -> "", "target/x" -> "")
    assertEquals(
      ignoredIn(root, "a.log", "src/b.log", "src/keep.txt", "target", "target/x", ".git", ".git/HEAD"),
      List(true, true, false, true, true, true, true)
    )

  test("anchored patterns are relative to the .gitignore's directory"):
    val root = repo("/out\nbuild/gen\n", "out/x" -> "", "src/out/y" -> "", "build/gen/z" -> "", "src/build/gen/w" -> "")
    assertEquals(
      ignoredIn(root, "out", "out/x", "src/out/y", "build/gen/z", "src/build/gen/w"),
      List(true, true, false, true, false)
    )

  test("directory-only patterns, `**` and character classes"):
    val root = repo(
      "logs/\ndocs/**/draft.md\nnote?.txt\ntmp[0-9]\n",
      "logs" -> "", // a *file* named logs: the `logs/` rule must not match it
      "docs/a/b/draft.md" -> "",
      "docs/draft.md" -> "",
      "note1.txt" -> "",
      "note10.txt" -> "", // `?` is exactly one character
      "tmp7" -> "",
      "tmpx" -> "",
    )
    assertEquals(
      ignoredIn(root, "logs", "docs/a/b/draft.md", "docs/draft.md", "note1.txt", "note10.txt", "tmp7", "tmpx"),
      List(false, true, true, true, false, true, false)
    )
    val dirRoot = repo("logs/\n", "logs/a.txt" -> "")
    assertEquals(ignoredIn(dirRoot, "logs", "logs/a.txt"), List(true, true))

  test("negation re-includes, but not inside an ignored directory"):
    val root = repo("*.log\n!keep.log\nout/\n!out/keep.txt\n", "a.log" -> "", "keep.log" -> "", "out/keep.txt" -> "")
    assertEquals(ignoredIn(root, "a.log", "keep.log", "out/keep.txt"), List(true, false, true))

  test("comments, blank lines and escaped markers"):
    val root = repo("# a comment\n\n\\#hash\n!*.keep\ntrailing   \n", "#hash" -> "", "trailing" -> "", "x.keep" -> "")
    assertEquals(ignoredIn(root, "#hash", "trailing", "x.keep"), List(true, true, false))

  test("nested .gitignore files apply below themselves, deepest wins"):
    val root = repo("*.tmp\n", "src/a.tmp" -> "", "src/b.tmp" -> "", "other/c.tmp" -> "")
    Files.writeString(root.resolve("src/.gitignore"), "!b.tmp\n")
    assertEquals(ignoredIn(root, "src/a.tmp", "src/b.tmp", "other/c.tmp"), List(true, false, true))

  test("patterns and .git use Windows filesystem case semantics"):
    assume(java.io.File.separatorChar == '\\')
    val root = repo("SRC/*.LOG\n", "src/a.log" -> "")
    assertEquals(ignoredIn(root, "src/a.log", ".GIT/HEAD"), List(true, true))

  test("paths outside the repository, and the root itself, are never ignored"):
    val root = repo("*.log\n", "a.log" -> "")
    val gi = GitIgnore(root)
    assert(!gi.ignores(root))
    assert(!gi.ignores(root.getParent.nn.resolve("elsewhere.log")))

  test("the enclosing repository's .gitignore applies to a sub-directory cwd"):
    val root = repo("*.log\n", "sub/a.log" -> "", "sub/b.txt" -> "")
    val gi = GitIgnore(root.resolve("sub").nn)
    assert(gi.ignores(root.resolve("sub/a.log")))
    assert(!gi.ignores(root.resolve("sub/b.txt")))

  test("a missing or unreadable .gitignore ignores nothing but `.git`"):
    val root = Files.createTempDirectory("atc-gitignore-none").nn.toRealPath().nn
    Files.createDirectories(root.resolve(".git"))
    Files.writeString(root.resolve("a.log"), "")
    val gi = GitIgnore(root)
    assert(!gi.ignores(root.resolve("a.log")))
    assert(gi.ignores(root.resolve(".git")))

  test("one malformed pattern disables only its own line, not the whole file"):
    // `a[]b` produces an invalid regex; git skips just that line.
    val root = repo("a[]b\n*.log\n", "x.log" -> "", "keep.txt" -> "")
    assertEquals(ignoredIn(root, "x.log", "keep.txt", "a[]b"), List(true, false, false))

  // ── the host's listings ──

  private def hostOn(root: Path, gitIgnore: GitIgnore): Host =
    val output = new HostOutput:
      def print(agentText: String, userText: String): Unit = ()
    val llm = new HostLlm:
      def chat(m: String) = m
      def classifiedChat(m: String) = m
    val ui = new HostUi:
      def askUser(question: String, options: List[String], multiple: Boolean): Option[String] = None
      def showTodos(items: List[atc.lib.Todo]): Unit = ()
    val policy = Policy(List(FileRule(PathPattern(".", root), Some(Access.Write), None)), Nil, Nil, _ => Decision.Deny)
    Host(policy, root, output, llm, ui, gitIgnore)

  test("ls/walk/find/grepRecursive skip ignored paths; read and write still work"):
    val root =
      repo("out/\n*.log\n", "src/A.scala" -> "object A // needle", "out/A.class" -> "needle", "a.log" -> "needle")
    val host = hostOn(root, GitIgnore(root))
    given IOCap = atc.lib.Runtime.rootIO
    given fs: FileSystem = host.fileSystem
    import host.*
    def names(ps: List[String]) = ps.sorted // listings inside the working directory come relative
    assertEquals(names(ls(root.toString)), List(".gitignore", "src"))
    assertEquals(names(walk(root.toString)), List(".gitignore", "src", "src/A.scala"))
    assertEquals(names(find(root.toString, "*")), List(".gitignore", "src/A.scala"))
    assertEquals(
      grepRecursive(root.toString, "needle").map(_.file),
      List("src/A.scala")
    )
    // Visibility only: an ignored file is still readable and writable by name.
    assertEquals(read("a.log"), "needle")
    write("out/B.class", "b")
    assertEquals(read("out/B.class"), "b")

  test("without the setting nothing is hidden"):
    val root = repo("out/\n", "src/A.scala" -> "a", "out/A.class" -> "c")
    val host = hostOn(root, GitIgnore.Disabled)
    given IOCap = atc.lib.Runtime.rootIO
    given fs: FileSystem = host.fileSystem
    import host.*
    assertEquals(
      ls(root.toString).sorted,
      List(".git", ".gitignore", "out", "src")
    )

  test("config default is on and the flag round-trips"):
    assert(atc.config.Config().respectGitignore)
    assert(!upickle.default.read[atc.config.Config]("""{"respectGitignore": false}""").respectGitignore)
