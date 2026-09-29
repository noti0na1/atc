package atc

import atc.checkpoint.{CheckpointStore, Checkpoints, Entry}
import atc.platform.Platform

import java.nio.file.attribute.{FileTime, PosixFilePermissions}
import java.nio.file.{Files, LinkOption, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.Using

class CheckpointSuite extends munit.FunSuite:
  private lazy val gitAvailable: Boolean =
    try ProcessBuilder("git", "--version").start().nn.waitFor(30, TimeUnit.SECONDS)
    catch case _: java.io.IOException => false

  override def beforeEach(context: BeforeEach): Unit = assume(gitAvailable, "git is not installed")

  private def tempDir(prefix: String): Path = Files.createTempDirectory(prefix).nn.toRealPath().nn

  private final class Project(git: Boolean = false, exclude: String => Boolean = _ => false):
    val root: Path = tempDir("atc-checkpoint-project")
    val state: Path = tempDir("atc-checkpoint-state")
    if git then run("init", "-q")
    lazy val store: CheckpointStore = CheckpointStore(state.resolve("store").nn, root, exclude)

    def write(rel: String, content: String): Path =
      val path = root.resolve(rel).nn
      Files.createDirectories(path.getParent)
      Files.writeString(path, content)
    def read(rel: String): String = Files.readString(root.resolve(rel)).nn
    def exists(rel: String): Boolean = Files.exists(root.resolve(rel), LinkOption.NOFOLLOW_LINKS)
    def delete(rel: String): Unit = Files.delete(root.resolve(rel))

    /** Every file under the root except `.git`, with its content. */
    def listing: Map[String, String] =
      Using.resource(Files.walk(root).nn): paths =>
        paths.iterator.nn.asScala
          .filter(p => Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
          .map(p => root.relativize(p).nn.toString.replace('\\', '/') -> Files.readString(p).nn)
          .filterNot(_._1.startsWith(".git/"))
          .toMap

    def run(args: String*): Unit =
      val process = ProcessBuilder(("git" +: "-C" +: root.toString +: args).asJava).redirectErrorStream(true).start().nn
      process.getInputStream.nn.readAllBytes()
      assertEquals(process.waitFor(), 0, s"git ${args.mkString(" ")}")

    /** The state before and after the turn of each path changed between `a` and `b`. */
    def turn(a: String, b: String): Map[String, (Option[Entry], Option[Entry])] =
      store.changes(a, b).map(c => c.path -> (c.before, c.after)).toMap

  test("a snapshot records edits, new files and deletions"):
    val p = Project()
    p.write("a.txt", "one\ntwo\n")
    p.write("b.txt", "gone soon\n")
    p.write("sub/c.txt", "c\n")
    val before = p.store.snapshot()
    p.write("a.txt", "one\n2\nthree\n")
    p.delete("b.txt")
    p.write("sub/d.txt", "new\n")
    val after = p.store.snapshot()
    val changes = p.store.changes(before, after).map(c => c.path -> c).toMap
    assertEquals(changes.keySet, Set("a.txt", "b.txt", "sub/d.txt"))
    assertEquals(changes("a.txt").lines, Some((2, 1)))
    assert(changes("b.txt").after.isEmpty && changes("sub/d.txt").before.isEmpty)
    assertEquals(p.store.snapshot(), after, "an unchanged project gives the same tree")

  test("revert restores the project as it was before the turn"):
    val p = Project()
    p.write("keep.txt", "keep\n")
    p.write("edit.txt", "original\n")
    p.write("remove.txt", "remove\n")
    p.write("dir/inner.txt", "inner\n")
    val original = p.listing
    val before = p.store.snapshot()
    p.write("edit.txt", "changed\n")
    p.delete("remove.txt")
    p.delete("dir/inner.txt")
    p.write("created/deep/new.txt", "new\n")
    val after = p.store.snapshot()
    val report = p.store.revert(p.turn(before, after), before)
    assertEquals(report.conflicts, Nil)
    assertEquals(report.deleted, List("created/deep/new.txt"))
    assertEquals(report.restored, List("dir/inner.txt", "edit.txt", "remove.txt"))
    assertEquals(p.listing, original)
    assert(!p.exists("created"), "directories the revert emptied are removed")

  test("revert keeps the user's later edits and leaves overlapping ones alone"):
    val p = Project()
    val lines = (1 to 20).map(i => s"line $i").mkString("", "\n", "\n")
    p.write("merge.txt", lines)
    p.write("clash.txt", "a\nb\nc\n")
    p.write("untouched.txt", "same\n")
    val before = p.store.snapshot()
    p.write("merge.txt", lines.replace("line 2\n", "agent 2\n"))
    p.write("clash.txt", "a\nagent\nc\n")
    val after = p.store.snapshot()
    val time = Files.getLastModifiedTime(p.root.resolve("untouched.txt"))
    p.write("merge.txt", lines.replace("line 2\n", "agent 2\n").replace("line 19\n", "user 19\n"))
    p.write("clash.txt", "a\nuser\nc\n")
    val report = p.store.revert(p.turn(before, after), before)
    assertEquals(report.merged, List("merge.txt"))
    assertEquals(p.read("merge.txt"), lines.replace("line 19\n", "user 19\n"))
    assertEquals(report.conflicts.map(_._1), List("clash.txt"))
    assertEquals(p.read("clash.txt"), "a\nuser\nc\n")
    assertEquals(Files.getLastModifiedTime(p.root.resolve("untouched.txt")), time)

  test("a file the agent created and the user then changed is not deleted"):
    val p = Project()
    p.write("base.txt", "base\n")
    val before = p.store.snapshot()
    p.write("made.txt", "agent\n")
    val after = p.store.snapshot()
    p.write("made.txt", "agent\nuser\n")
    val report = p.store.revert(p.turn(before, after), before)
    assertEquals(report.deleted, Nil)
    assertEquals(report.conflicts.map(_._1), List("made.txt"))
    assertEquals(p.read("made.txt"), "agent\nuser\n")

  test("revert never writes through a symbolic link to a directory"):
    val p = Project()
    val outside = tempDir("atc-checkpoint-outside")
    p.write("sub/x.txt", "x\n")
    val before = p.store.snapshot()
    p.delete("sub/x.txt")
    val after = p.store.snapshot()
    Files.delete(p.root.resolve("sub"))
    assume(TestEnv.trySymbolicLink(p.root.resolve("sub").nn, outside), "symbolic links are not available")
    val report = p.store.revert(p.turn(before, after), before)
    assertEquals(report.conflicts.map(_._1), List("sub/x.txt"))
    assert(!Files.exists(outside.resolve("x.txt")))

  test("excluded, large and nested-repository paths are not recorded"):
    val p = Project(exclude = _.startsWith("secret"))
    val before = p.store.snapshot()
    p.write("secret.txt", "token\n")
    p.write("ok.txt", "ok\n")
    Files.write(p.root.resolve("big.bin"), Array.fill[Byte]((CheckpointStore.MaxNewFileBytes + 1).toInt)(1))
    p.write("nested/inner.txt", "inner\n")
    val process = ProcessBuilder("git", "init", "-q", p.root.resolve("nested").toString).start().nn
    assertEquals(process.waitFor(), 0)
    val after = p.store.snapshot()
    assertEquals(p.store.changes(before, after).map(_.path), List("ok.txt"))

  test("a git project lends its objects to the store"):
    val p = Project(git = true)
    p.write("a.txt", "committed\n")
    p.run("add", "a.txt")
    p.run("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "c")
    val before = p.store.snapshot()
    assert(Files.readString(p.state.resolve("store/objects/info/alternates")).nn.contains(".git/objects"))
    p.write("a.txt", "edited\n")
    val after = p.store.snapshot()
    assertEquals(p.store.changes(before, after).map(_.path), List("a.txt"))
    p.store.keep("1", List(before, after), p.turn(before, after).values.flatMap(_._1).map(_.oid))
    p.store.revert(p.turn(before, after), before)
    assertEquals(p.read("a.txt"), "committed\n")

  test("a forced path is hashed again even when its size and time did not change"):
    val p = Project()
    val file = p.write("same.txt", "aaaa")
    val time = FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis / 1000 * 1000 - 5000)
    Files.setLastModifiedTime(file, time)
    val before = p.store.snapshot()
    Files.writeString(file, "bbbb")
    Files.setLastModifiedTime(file, time)
    val after = p.store.snapshot(List("same.txt"))
    assertEquals(p.store.changes(before, after).map(_.path), List("same.txt"))

  test("revert restores the executable bit"):
    assume(!Platform.isWindows, "file modes are not recorded on Windows")
    val p = Project()
    val script = p.write("run.sh", "echo hi\n")
    val before = p.store.snapshot()
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
    val after = p.store.snapshot()
    val change = p.store.changes(before, after).head
    assertEquals((change.before.map(_.mode), change.after.map(_.mode)), (Some("100644"), Some("100755")))
    p.store.revert(p.turn(before, after), before)
    assert(!Files.isExecutable(script))

  test("a case-only rename is recorded and reverted on a case-insensitive file system"):
    assume(Platform.caseInsensitivePaths, "the file system distinguishes case")
    val p = Project()
    p.write("Readme.md", "text\n")
    val before = p.store.snapshot()
    // Files.move does nothing when the two names are the same file; rename(2) changes the case.
    assert(p.root.resolve("Readme.md").nn.toFile.renameTo(p.root.resolve("README.md").nn.toFile))
    val after = p.store.snapshot()
    assertEquals(p.store.changes(before, after).map(_.path).toSet, Set("README.md", "Readme.md"))
    p.store.revert(p.turn(before, after), before)
    assertEquals(Files.list(p.root).nn.iterator.nn.asScala.map(_.getFileName.toString).toList, List("Readme.md"))

  // ── the session: calls, turns and /undo ─────────────────────────────

  private def session(env: TestEnv): Checkpoints =
    Checkpoints(env.root, tempDir("atc-checkpoint-home"), env.host, env.policy)

  test("a turn records the agent's writes and /undo reverts them"):
    val env = TestEnv(prefix = "atc-checkpoint-turn")
    import env.given
    env.file("b.txt", "before\n")
    val checkpoints = session(env)
    val fs = env.host.fileSystem
    checkpoints.beginTurn()
    checkpoints.beforeCall()
    env.host.write("a.txt", "new\n")(using fs)
    env.host.write("b.txt", "after\n")(using fs)
    checkpoints.afterCall()
    val changes = checkpoints.endTurn()
    assertEquals(changes.map(Checkpoints.describe), List("a.txt (new)", "b.txt (+1 -1)"))
    val report = checkpoints.undo(Nil).toOption.get
    assertEquals((report.deleted, report.restored), (List("a.txt"), List("b.txt")))
    assert(!env.existsOnDisk("a.txt"))
    assertEquals(env.contents("b.txt"), "before\n")
    assert(checkpoints.undo(Nil).isLeft, "a reverted turn is not reverted again")

  test("changes made between tool calls belong to the user"):
    val env = TestEnv(prefix = "atc-checkpoint-between")
    import env.given
    val checkpoints = session(env)
    val fs = env.host.fileSystem
    checkpoints.beginTurn()
    checkpoints.beforeCall()
    env.host.write("agent1.txt", "1\n")(using fs)
    checkpoints.afterCall()
    env.file("user.txt", "typed meanwhile\n")
    checkpoints.beforeCall()
    env.host.write("agent2.txt", "2\n")(using fs)
    checkpoints.afterCall()
    assertEquals(checkpoints.endTurn().map(_.path), List("agent1.txt", "agent2.txt"))
    checkpoints.undo(List("agent2.txt"))
    assert(env.existsOnDisk("agent1.txt") && !env.existsOnDisk("agent2.txt") && env.existsOnDisk("user.txt"))

  test("a call that changes nothing records no turn"):
    val env = TestEnv(prefix = "atc-checkpoint-quiet")
    import env.given
    env.file("a.txt", "a\n")
    val checkpoints = session(env)
    checkpoints.beginTurn()
    checkpoints.beforeCall()
    env.host.read("a.txt")(using env.host.fileSystem)
    checkpoints.afterCall()
    assertEquals(checkpoints.endTurn(), Nil)
    assert(checkpoints.undo(Nil).isLeft)
