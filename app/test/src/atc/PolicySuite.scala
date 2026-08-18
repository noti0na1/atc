package atc

import atc.perms.*
import java.nio.file.{Files, Path}

class PolicySuite extends munit.FunSuite:
  val root: Path = Files.createTempDirectory("atc-policy").toRealPath()
  Files.createDirectories(root.resolve("src/main"))
  Files.createDirectories(root.resolve("build"))
  Files.createDirectories(root.resolve("secrets/inner"))
  Files.writeString(root.resolve("src/main/A.scala"), "a")
  Files.writeString(root.resolve("secrets/key.txt"), "k")
  Files.writeString(root.resolve(".env"), "X=1")

  class ScriptedPrompter(var decisions: List[Decision]) extends PermissionPrompter:
    var asked = List.empty[PermissionRequest]
    def ask(r: PermissionRequest): Decision =
      asked ::= r
      decisions match
        case d :: rest => decisions = rest; d
        case Nil => Decision.Deny

  def rules(specs: (String, Option[Access], Option[Boolean], Boolean)*): List[FileRule] =
    specs.toList.map((p, a, c, l) => FileRule(PathPattern(p, root), a, c, l))

  test("meet semantics: sub-folder inherits and can only get stricter"):
    val p = Policy(
      rules(
        (".", Some(Access.Write), None, false),
        ("build", Some(Access.Read), None, false),
        ("secrets", None, Some(true), false),
        (".env", None, Some(true), false),
        ("build/generated", Some(Access.Write), None, false), // wider than parent: ignored (clamped)
      ),
      Nil,
      Nil,
      ScriptedPrompter(Nil)
    )
    assertEquals(p.configPerm(root.resolve("src/main/A.scala")), Perm(Access.Write, false))
    assertEquals(p.configPerm(root.resolve("build/x.jar")), Perm(Access.Read, false))
    assertEquals(p.configPerm(root.resolve("build/generated/y")), Perm(Access.Read, false))
    assertEquals(p.configPerm(root.resolve("secrets/key.txt")), Perm(Access.Write, true))
    assertEquals(p.configPerm(root.resolve("secrets/inner/deep")), Perm(Access.Write, true))
    assertEquals(p.configPerm(root.resolve(".env")), Perm(Access.Write, true))
    // unmatched path: no access
    assertEquals(p.configPerm(Path.of("/etc/passwd")), Perm(Access.None, false))
    // component pattern applies anywhere
    assertEquals(p.configPerm(Path.of("/tmp/other/.env")), Perm(Access.None, true))

  test("component, anchored and absolute patterns"):
    val comp = PathPattern("*.pem", root)
    assert(comp.matches(root.resolve("a/b/c.pem")))
    assert(!comp.matches(root.resolve("a/b/c.txt")))
    val anchored = PathPattern("src/*/A.scala", root)
    assert(anchored.matches(root.resolve("src/main/A.scala")))
    assert(!anchored.matches(root.resolve("other/src/main/A.scala")))
    val star = PathPattern("**/inner", root)
    assert(star.matches(root.resolve("secrets/inner/deep/x")))
    val abs = PathPattern(root.resolve("build").toString, root)
    assert(abs.matches(root.resolve("build/x")))
    assert(!abs.matches(root.resolve("src")))
    val absoluteGlob = PathPattern(root.resolve("src/*/A.scala").toString, root)
    assert(absoluteGlob.matches(root.resolve("src/main/A.scala")))
    assert(!absoluteGlob.matches(root.resolve("src/main/B.scala")))
    val home = PathPattern("~", root)
    assert(home.matches(Path.of(System.getProperty("user.home")).resolve("x")))

  test("requests widen access once or for the session, deny throws"):
    val prompter = ScriptedPrompter(List(Decision.AllowOnce, Decision.Deny, Decision.AllowSession))
    val p = Policy(rules((".", Some(Access.Read), None, false)), Nil, Nil, prompter)
    val f = root.resolve("src/main/A.scala")
    assertEquals(p.effective(ScopeId.Base, f).access, Access.Read)
    // read is already held: no prompt, scope opened
    val s0 = p.requestFile(ScopeId.Base, root.resolve("src"), Access.Read, "")
    assert(prompter.asked.isEmpty)
    p.closeScope(s0)
    // once
    val s1 = p.requestFile(ScopeId.Base, root.resolve("src"), Access.Write, "edit")
    assertEquals(p.effective(s1, f).access, Access.Write)
    assertEquals(p.effective(ScopeId.Base, f).access, Access.Read) // base unchanged
    p.closeScope(s1)
    intercept[SecurityException](p.effective(s1, f)) // closed scope is unusable
    // deny
    intercept[SecurityException](p.requestFile(ScopeId.Base, root.resolve("src"), Access.Write, "again"))
    // session
    val s3 = p.requestFile(ScopeId.Base, root.resolve("src"), Access.Write, "third")
    p.closeScope(s3)
    assertEquals(p.effective(ScopeId.Base, f).access, Access.Write)
    assertEquals(prompter.asked.size, 3)

  test("locked rules cannot be widened, classified stays classified"):
    val prompter = ScriptedPrompter(List(Decision.AllowSession, Decision.AllowSession))
    val p = Policy(
      rules(
        (".", Some(Access.Read), None, false),
        ("build", Some(Access.Read), None, true),
        ("secrets", None, Some(true), false),
      ),
      Nil,
      Nil,
      prompter
    )
    intercept[SecurityException](p.requestFile(ScopeId.Base, root.resolve("build"), Access.Write, ""))
    assert(prompter.asked.isEmpty)
    val s = p.requestFile(ScopeId.Base, root.resolve("secrets"), Access.Write, "")
    val pm = p.effective(s, root.resolve("secrets/key.txt"))
    assertEquals(pm, Perm(Access.Write, true))

  test("child scopes inherit parent grants; commands and hosts"):
    val prompter = ScriptedPrompter(List(Decision.AllowOnce, Decision.AllowOnce, Decision.AllowOnce))
    val p =
      Policy(rules((".", Some(Access.Read), None, false)), List("git status", "ls"), List("*.scala-lang.org"), prompter)
    assert(p.commandAllowed(ScopeId.Base, "git status --short"))
    assert(p.commandAllowed(ScopeId.Base, "ls -la"))
    assert(!p.commandAllowed(ScopeId.Base, "git push"))
    assert(!p.commandAllowed(ScopeId.Base, "lsof"))
    assert(p.hostAllowed(ScopeId.Base, "docs.scala-lang.org"))
    assert(!p.hostAllowed(ScopeId.Base, "example.com"))
    val s1 = p.requestFile(ScopeId.Base, root.resolve("src"), Access.Write, "")
    val s2 = p.requestFile(s1, root.resolve("build"), Access.Read, "")
    assertEquals(p.effective(s2, root.resolve("src/main/A.scala")).access, Access.Write) // inherited from s1
    val e = p.requestExec(ScopeId.Base, List("npm *"), "")
    assert(p.commandAllowed(e, "npm install"))
    assert(!p.commandAllowed(ScopeId.Base, "npm install"))
    // already-permitted patterns do not prompt
    val e2 = p.requestExec(ScopeId.Base, List("ls"), "")
    assertEquals(prompter.asked.size, 2)
    p.closeScope(e2)

  test("glob matcher semantics"):
    assert(GlobMatcher.matchesCommand("git diff --stat", "git diff*"))
    assert(GlobMatcher.matchesCommand("git difftool", "git diff*"))
    assert(GlobMatcher.matchesCommand("./mill compile", "./mill *"))
    assert(!GlobMatcher.matchesCommand("./millx compile", "./mill *"))
    assert(GlobMatcher.matchesCommand("ls", "ls"))
    assert(!GlobMatcher.matchesCommand("lsblk", "ls"))
    assert(GlobMatcher.matchesHost("API.GitHub.com", "*.github.com"))
    assert(!GlobMatcher.matchesHost("github.com", "*.github.com"))
