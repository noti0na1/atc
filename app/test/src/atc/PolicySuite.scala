package atc

import atc.perms.*
import atc.platform.{Platform, PlatformPath}
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
    if Platform.isWindows then assert(comp.matches(root.resolve("a/b/C.PEM")))
    val anchored = PathPattern("src/*/A.scala", root)
    assert(anchored.matches(root.resolve("src/main/A.scala")))
    assert(!anchored.matches(root.resolve("other/src/main/A.scala")))
    if Platform.isWindows then
      val windowsSpelling = PathPattern("src\\*\\A.scala", root)
      assert(windowsSpelling.matches(root.resolve("src/main/A.scala")))
      intercept[IllegalArgumentException](PathPattern("C:work*", root))
    else
      assert(!anchored.matches(root.resolve("src\\main\\A.scala")), "a Unix backslash is part of one component")
    val star = PathPattern("**/inner", root)
    assert(star.matches(root.resolve("secrets/inner/deep/x")))
    val abs = PathPattern(root.resolve("build").toString, root)
    assert(abs.matches(root.resolve("build/x")))
    assert(!abs.matches(root.resolve("src")))
    val absoluteGlob = PathPattern(s"${PlatformPath.portable(root)}/src/*/A.scala", root)
    assert(absoluteGlob.matches(root.resolve("src/main/A.scala")))
    assert(!absoluteGlob.matches(root.resolve("src/main/B.scala")))
    val fileSystemRoot = PathPattern(root.getRoot.nn.toString, root)
    assert(fileSystemRoot.matches(root))
    val home = PathPattern("~", root)
    assert(home.matches(Path.of(scala.util.Properties.userHome).resolve("x")))
    assertEquals(
      PlatformPath.expandHome("~\\nested\\config.json"),
      Path.of(scala.util.Properties.userHome, "nested", "config.json").toString,
    )
    assertEquals(PlatformPath.portable(Path.of("a", "b", "c")), "a/b/c")
    if !Platform.isWindows then assertEquals(PlatformPath.portable(Path.of("a\\b")), "a\\b")

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
    if Platform.isWindows then
      assert(p.commandAllowed(ScopeId.Base, "GIT.EXE status --short"))
      assert(!p.commandAllowed(ScopeId.Base, "git STATUS"), "Windows executable names, not arguments, ignore case")
      assert(GlobMatcher.matchesCommand(".\\gradlew build", "./gradlew build"))
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

  test("resetSession forgets session grants and open scopes but keeps the rules and the mode"):
    val prompter = ScriptedPrompter(List.fill(4)(Decision.AllowSession))
    val p = Policy(rules((".", Some(Access.Read), None, false)), List("ls"), Nil, prompter)
    val s = p.requestFile(ScopeId.Base, root.resolve("src"), Access.Write, "")
    p.requestExec(ScopeId.Base, List("npm *"), "")
    p.requestNet(ScopeId.Base, List("example.com"), "")
    assertEquals(p.effective(ScopeId.Base, root.resolve("src/main/A.scala")).access, Access.Write) // session grant
    assert(p.commandAllowed(ScopeId.Base, "npm install"))
    assert(p.hostAllowed(ScopeId.Base, "example.com"))
    assertEquals(p.openScopeCount, 3)

    p.mode = Mode.Local
    p.resetSession()
    assertEquals(p.openScopeCount, 0)
    assertEquals(p.effective(ScopeId.Base, root.resolve("src/main/A.scala")).access, Access.Read) // rule only
    assert(!p.commandAllowed(ScopeId.Base, "npm install"))
    assert(p.commandAllowed(ScopeId.Base, "ls -la")) // configured, not a session grant
    assertEquals(p.mode, Mode.Local)
    p.mode = Mode.Full
    assert(!p.hostAllowed(ScopeId.Base, "example.com"))
    // a capability that escaped the old session no longer resolves
    intercept[SecurityException](p.effective(s, root.resolve("src/main/A.scala")))
    // and the next request prompts again rather than reusing the old answer
    p.requestExec(ScopeId.Base, List("npm *"), "")
    assertEquals(prompter.asked.size, 4)

  test("deny patterns refuse commands and hosts whatever the allow list says"):
    val prompter = ScriptedPrompter(Nil)
    val p = Policy(
      rules((".", Some(Access.Read), None, false)),
      List("git *", "rm *"),
      List("*.example.com", "internal.corp"),
      prompter,
      List("git push*", "rm -rf *"),
      List("internal.corp")
    )
    // allowed by `commands`, refused by `denyCommands`
    assert(!p.commandAllowed(ScopeId.Base, "git push origin main"))
    assertEquals(p.commandDenied("git push origin main"), Some("git push*"))
    assert(p.commandAllowed(ScopeId.Base, "git status")) // the allow list still works
    assert(!p.commandAllowed(ScopeId.Base, "rm -rf build"))
    assert(p.commandAllowed(ScopeId.Base, "rm build/x")) // narrower than the deny pattern
    assert(!p.hostAllowed(ScopeId.Base, "internal.corp"))
    assertEquals(p.hostDenied("internal.corp"), Some("internal.corp"))
    assert(p.hostAllowed(ScopeId.Base, "docs.example.com"))
    assertEquals(p.commandDenied("git status"), None)
    assertEquals(p.hostDenied("docs.example.com"), None)
    assertEquals(prompter.asked, Nil) // nothing was ever put to the user

  test("a request that would permit a denied command or host fails without asking"):
    // The prompter would allow everything: only the deny list stops these.
    val prompter = ScriptedPrompter(List.fill(4)(Decision.AllowSession))
    val p = Policy(
      rules((".", Some(Access.Read), None, false)),
      Nil,
      Nil,
      prompter,
      List("rm -rf *"),
      List("*.internal")
    )
    // the deny pattern covers the requested one ...
    val e1 = intercept[SecurityException](p.requestExec(ScopeId.Base, List("rm -rf build"), "clean"))
    assert(e1.getMessage.nn.contains("rm -rf *"), e1.getMessage)
    // ... and the requested one would cover the deny pattern
    intercept[SecurityException](p.requestExec(ScopeId.Base, List("rm *"), "clean"))
    val e2 = intercept[SecurityException](p.requestNet(ScopeId.Base, List("db.internal"), "read"))
    assert(e2.getMessage.nn.contains("*.internal"), e2.getMessage)
    intercept[SecurityException](p.requestNet(ScopeId.Base, List("*"), "read"))
    assertEquals(prompter.asked, Nil)
    assertEquals(p.openScopeCount, 0) // no scope was opened for a refused request
    // an unrelated request still prompts and is granted
    val s = p.requestExec(ScopeId.Base, List("ls*"), "list")
    assert(p.commandAllowed(s, "ls -la"))
    assertEquals(prompter.asked.size, 1)
    p.closeScope(s)

  test("the summary names the always-refused patterns"):
    val p = Policy(Nil, List("ls"), Nil, ScriptedPrompter(Nil), List("rm *"), List("*.internal"))
    val summary = p.summary
    assert(summary.contains("always refused: rm *"), summary)
    assert(summary.contains("always refused: *.internal"), summary)
    assert(!Policy(Nil, Nil, Nil, ScriptedPrompter(Nil)).summary.contains("always refused"))

  test("prompt decisions are logged; session grants show in summary but not in configSummary (the prompt's view)"):
    val p =
      Policy(Nil, List("ls"), Nil, ScriptedPrompter(List(Decision.AllowSession, Decision.AllowOnce, Decision.Deny)))
    assertEquals(p.decisionCount, 0)
    val before = p.configSummary
    p.closeScope(p.requestExec(ScopeId.Base, List("npm *"), "deps")) // allowed for the session
    p.closeScope(p.requestNet(ScopeId.Base, List("example.com"), "docs")) // allowed once
    intercept[SecurityException](p.requestNet(ScopeId.Base, List("evil.example"), "no"))
    assertEquals(
      p.decisionsSince(0),
      List(
        Decision.AllowSession -> "commands npm *",
        Decision.AllowOnce -> "hosts example.com",
        Decision.Deny -> "hosts evil.example",
      )
    )
    assertEquals(p.decisionsSince(2).map(_._1), List(Decision.Deny))
    assert(p.summary.contains("+ session: npm *"), p.summary)
    assertEquals(p.configSummary, before) // the prompt text does not move with a grant
    assert(!p.configSummary.contains("npm"), p.configSummary)
    p.resetSession()
    assertEquals(p.decisionCount, 0)

  test("glob matcher semantics"):
    assert(GlobMatcher.matchesCommand("git diff --stat", "git diff*"))
    assert(GlobMatcher.matchesCommand("git difftool", "git diff*"))
    assert(GlobMatcher.matchesCommand("./mill compile", "./mill *"))
    assert(!GlobMatcher.matchesCommand("./millx compile", "./mill *"))
    assert(GlobMatcher.matchesCommand("ls", "ls"))
    assert(!GlobMatcher.matchesCommand("lsblk", "ls"))
    assert(GlobMatcher.matchesHost("API.GitHub.com", "*.github.com"))
    assert(!GlobMatcher.matchesHost("github.com", "*.github.com"))
