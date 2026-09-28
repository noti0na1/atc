package atc

import atc.config.{ProjectRules, ProjectTrust}
import atc.perms.*

import java.nio.file.{Files, Path}

/** "Always allow in this project": what a saved grant writes to the project config, and when
  * it cannot be saved. The global directory is a temporary one, so trust records stay out of
  * the real `~/.atc`. */
class ProjectRulesSuite extends munit.FunSuite:
  private def dirs(): (Path, Path) =
    val project = Files.createTempDirectory("atc-rules-project").nn.toRealPath().nn
    val global = Files.createTempDirectory("atc-rules-global").nn.toRealPath().nn
    (project, global)
  private def config(project: Path): String = Files.readString(project.resolve(".atc/config.json")).nn
  private def json(project: Path): ujson.Value = ujson.read(config(project))
  private val readPerm = Perm(Access.Read, classified = false)

  test("a command grant creates the project config, and the project stays trusted"):
    val (project, global) = dirs()
    val request = ExecRequest(List("npm test"), "run the tests")
    val note = ProjectRules.save(project, request, global)
    assert(note.startsWith("saved to"), note)
    assertEquals(json(project)("commands").arr.map(_.str).toList, List("npm test"))
    assertEquals(ProjectTrust.pending(project, global), None)
    assertEquals(ProjectRules.plan(project, request, global), None, "nothing new to save a second time")

  test("hosts join an existing list without reformatting the file"):
    val (project, global) = dirs()
    Files.createDirectories(project.resolve(".atc"))
    val original = "{\n  \"hosts\": [\n    \"docs.rs\",\n\n    \"go.dev\"\n  ]\n}\n"
    Files.writeString(project.resolve(".atc/config.json"), original)
    ProjectTrust.trust(project, global)
    ProjectRules.save(project, NetRequest(List("go.dev", "pypi.org"), "fetch"), global)
    assertEquals(config(project), original.replace("\"go.dev\"\n", "\"go.dev\", \"pypi.org\"\n"))
    assertEquals(ProjectTrust.pending(project, global), None)

  test("a file grant inside the project becomes a rule anchored to the project"):
    val (project, global) = dirs()
    val src = Files.createDirectories(project.resolve("src")).nn
    ProjectRules.save(project, FileRequest(src, Access.Write, readPerm, "edit"), global)
    assertEquals(json(project)("files").arr.toList, List(ujson.Obj("path" -> "./src", "access" -> "write")))

  test("a file grant outside the project, capped lower, or named like a glob is not offered"):
    val (project, global) = dirs()
    val outside = Files.createTempDirectory("atc-rules-outside").nn.toRealPath().nn
    assertEquals(ProjectRules.plan(project, FileRequest(outside, Access.Read, readPerm, "r"), global), None)
    val git = project.resolve(".git").nn
    assertEquals(
      ProjectRules.plan(project, FileRequest(git, Access.Write, readPerm, "w", ceiling = Access.Read), global),
      None
    )
    val globby = project.resolve("a[1]").nn
    assertEquals(ProjectRules.plan(project, FileRequest(globby, Access.Read, readPerm, "r"), global), None)

  test("saving to an untrusted project does not trust what it already grants"):
    val (project, global) = dirs()
    Files.createDirectories(project.resolve(".atc"))
    Files.writeString(project.resolve(".atc/config.json"), """{ "commands": ["make deploy"] }""")
    val note = ProjectRules.save(project, ExecRequest(List("make test"), "test"), global)
    assert(note.contains("once you trust the project"), note)
    assert(ProjectTrust.pending(project, global).isDefined, "still untrusted")

  test("the home directory's config is the global one, so nothing is saved there"):
    val (home, _) = dirs()
    Files.createDirectories(home.resolve(".atc"))
    Files.writeString(home.resolve(".atc/config.json"), "{}")
    assertEquals(ProjectRules.plan(home, ExecRequest(List("ls"), "list"), home.resolve(".atc").nn), None)
