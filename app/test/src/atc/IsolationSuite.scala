package atc

import atc.checkpoint.Isolation
import atc.platform.Platform

import java.nio.channels.FileChannel
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.util.concurrent.TimeUnit

/** Isolate mode's copy: made as a clone, kept between sessions, applied to the project with
  * a merge, and reset to the project's state. */
class IsolationSuite extends munit.FunSuite:
  private lazy val gitAvailable: Boolean =
    try ProcessBuilder("git", "--version").start().nn.waitFor(30, TimeUnit.SECONDS)
    catch case _: java.io.IOException => false

  override def beforeEach(context: BeforeEach): Unit =
    assume(gitAvailable, "git is not installed")
    assume(!Platform.isWindows, "copies are made with cp")

  private def tempDir(prefix: String): Path = Files.createTempDirectory(prefix).nn.toRealPath().nn

  private final class Setup(excluded: String => Boolean = _ => false):
    val project: Path = tempDir("atc-isolate-project")
    val state: Path = tempDir("atc-isolate-state")
    val data: Path = tempDir("atc-isolate-data")
    def isolation = Isolation(project, state, data, excluded)
    def write(root: Path, rel: String, content: String): Unit =
      val path = root.resolve(rel).nn
      Files.createDirectories(path.getParent)
      Files.writeString(path, content)
    def read(root: Path, rel: String): String = Files.readString(root.resolve(rel)).nn
    def exists(root: Path, rel: String): Boolean = Files.exists(root.resolve(rel), LinkOption.NOFOLLOW_LINKS)
    def git(root: Path, args: String*): String =
      val process =
        ProcessBuilder(("git" :: "-C" :: root.toString :: args.toList)*).redirectErrorStream(true).start().nn
      val out = String(process.getInputStream.nn.readAllBytes(), "UTF-8")
      assertEquals(process.waitFor(), 0, out)
      out.trim
    def repository(): Unit =
      git(project, "init", "-q")
      git(project, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "start")

  test("entering makes the copy, and apply writes its changes, deletions included, into the project"):
    val s = Setup()
    s.write(s.project, "a.txt", "one\n")
    s.write(s.project, "gone.txt", "bye\n")
    val i = s.isolation
    assertEquals(i.enter(), 0)
    assert(i.copy.startsWith(s.data), i.copy.toString)
    assertEquals(s.read(i.copy, "a.txt"), "one\n")
    s.write(i.copy, "a.txt", "two\n")
    s.write(i.copy, "new/b.txt", "bee\n")
    Files.delete(i.copy.resolve("gone.txt"))
    assertEquals(i.unapplied.map(_.path).toSet, Set("a.txt", "new/b.txt", "gone.txt"))
    assertEquals(s.read(s.project, "a.txt"), "one\n", "the project is untouched until applied")
    val report = i.apply(i.preview.tree)
    assertEquals(report.restored.toSet, Set("a.txt", "new/b.txt"))
    assertEquals(report.deleted, List("gone.txt"))
    assertEquals(s.read(s.project, "a.txt"), "two\n")
    assertEquals(s.read(s.project, "new/b.txt"), "bee\n")
    assert(!s.exists(s.project, "gone.txt"))
    assert(i.unapplied.isEmpty)
    assert(i.apply(i.preview.tree).reverted.isEmpty, "applying again changes nothing")

  test("a text file changed on both sides is merged, and a clash is left alone and reported"):
    val s = Setup()
    s.write(s.project, "m.txt", "a\nb\nc\nd\ne\n")
    s.write(s.project, "c.txt", "same\n")
    val i = s.isolation
    i.enter()
    s.write(i.copy, "m.txt", "A\nb\nc\nd\ne\n")
    s.write(s.project, "m.txt", "a\nb\nc\nd\nE\n")
    s.write(i.copy, "c.txt", "copy\n")
    s.write(s.project, "c.txt", "project\n")
    val report = i.apply(i.preview.tree)
    assertEquals(report.merged, List("m.txt"))
    assertEquals(s.read(s.project, "m.txt"), "A\nb\nc\nd\nE\n")
    assertEquals(report.conflicts.map(_._1), List("c.txt"))
    assertEquals(s.read(s.project, "c.txt"), "project\n")

  test("discard puts the copy back to the project's state"):
    val s = Setup()
    s.write(s.project, "a.txt", "one\n")
    val i = s.isolation
    i.enter()
    s.write(i.copy, "a.txt", "changed\n")
    s.write(i.copy, "extra.txt", "x\n")
    i.discard()
    assertEquals(s.read(i.copy, "a.txt"), "one\n")
    assert(!s.exists(i.copy, "extra.txt"))
    assert(i.pending.isEmpty)

  test("entering again takes the project's changes only when nothing is pending, and keeps ignored output"):
    val s = Setup()
    s.write(s.project, ".gitignore", "out/\n")
    s.write(s.project, "a.txt", "one\n")
    s.write(s.project, ".atc/config.json", "{}\n")
    val i = s.isolation
    i.enter()
    s.write(i.copy, "out/cache.bin", "warm")
    s.write(s.project, "b.txt", "from the project\n")
    s.write(s.project, ".atc/config.json", "{ \"mode\": \"local\" }\n")
    assertEquals(s.isolation.enter(), 0)
    assertEquals(s.read(i.copy, "b.txt"), "from the project\n")
    assertEquals(s.read(i.copy, "out/cache.bin"), "warm", "the copy's build output stays")
    assertEquals(s.read(i.copy, ".atc/config.json"), "{ \"mode\": \"local\" }\n", "the config is copied each time")
    s.write(i.copy, "a.txt", "pending\n")
    s.write(s.project, "c.txt", "later\n")
    assertEquals(s.isolation.enter(), 1, "one pending change is kept")
    assert(!s.exists(i.copy, "c.txt"), "the project's change waits until the copy's is applied or discarded")

  test("excluded paths are neither applied nor taken"):
    val s = Setup(excluded = _.startsWith("secrets/"))
    s.write(s.project, "secrets/key", "k1")
    val i = s.isolation
    i.enter()
    s.write(i.copy, "secrets/key", "k2")
    assert(i.pending.isEmpty)
    i.apply(i.preview.tree)
    assertEquals(s.read(s.project, "secrets/key"), "k1")

  test("the preview lists what the project lacks and marks what the project changed too"):
    val s = Setup()
    s.write(s.project, "both.txt", "a\nb\nc\n")
    s.write(s.project, "copy.txt", "x\n")
    val i = s.isolation
    i.enter()
    s.write(i.copy, "both.txt", "A\nb\nc\n")
    s.write(i.copy, "copy.txt", "y\n")
    s.write(s.project, "both.txt", "a\nb\nC\n")
    assertEquals(
      i.preview.changes.map((c, there) => c.path -> there).toMap,
      Map("both.txt" -> true, "copy.txt" -> false)
    )

  test("after apply the copy takes the project's later changes, and a change the user undoes is not applied again"):
    val s = Setup()
    s.write(s.project, "a.txt", "one\n")
    val i = s.isolation
    i.enter()
    s.write(i.copy, "a.txt", "two\n")
    i.apply(i.preview.tree)
    s.write(s.project, "a.txt", "one\n")
    assert(i.unapplied.isEmpty, "the applied change is not offered again")
    s.write(s.project, "b.txt", "later\n")
    assertEquals(s.isolation.enter(), 0)
    assertEquals(s.read(i.copy, "a.txt"), "one\n")
    assertEquals(s.read(i.copy, "b.txt"), "later\n")

  test("apply writes the state of the copy the preview showed"):
    val s = Setup()
    s.write(s.project, "a.txt", "one\n")
    val i = s.isolation
    i.enter()
    s.write(i.copy, "a.txt", "shown\n")
    val shown = i.preview
    s.write(i.copy, "a.txt", "later\n")
    i.apply(shown.tree)
    assertEquals(s.read(s.project, "a.txt"), "shown\n")
    assertEquals(i.unapplied.map(_.path), List("a.txt"), "the later change is still to apply")

  test("a project change the copy could not take is not later offered as the copy's change"):
    assume(!Platform.isWindows, "POSIX permissions")
    val s = Setup()
    s.write(s.project, "locked/a.txt", "a\n")
    val i = s.isolation
    i.enter()
    val locked = i.copy.resolve("locked").nn
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"))
    try
      s.write(s.project, "locked/new.txt", "the user's\n")
      s.isolation.enter()
      assert(i.unapplied.isEmpty, i.unapplied.toString)
      i.apply(i.preview.tree)
      assertEquals(s.read(s.project, "locked/new.txt"), "the user's\n")
    finally Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))

  test("discard takes the project's changes as well"):
    val s = Setup()
    s.write(s.project, "a.txt", "one\n")
    val i = s.isolation
    i.enter()
    s.write(i.copy, "a.txt", "changed\n")
    s.write(s.project, "b.txt", "the user's\n")
    i.discard()
    assertEquals(s.read(i.copy, "a.txt"), "one\n")
    assertEquals(s.read(i.copy, "b.txt"), "the user's\n")

  test("an object planted in the copy's .git does not change what apply writes"):
    val s = Setup()
    s.repository()
    s.write(s.project, "a.txt", "one\n")
    val i = s.isolation
    i.enter()
    val benign = "benign\n".getBytes("UTF-8")
    val id = java.security.MessageDigest.getInstance("SHA-1").nn
      .digest(s"blob ${benign.length}\u0000".getBytes("UTF-8") ++ benign).map("%02x".format(_)).mkString
    val evil = "evil\n".getBytes("UTF-8")
    val deflater = java.util.zip.Deflater()
    deflater.setInput(s"blob ${evil.length}\u0000".getBytes("UTF-8") ++ evil)
    deflater.finish()
    val packed = new Array[Byte](256)
    val size = deflater.deflate(packed)
    val planted = i.copy.resolve(s".git/objects/${id.take(2)}/${id.drop(2)}").nn
    Files.createDirectories(planted.getParent)
    Files.write(planted, packed.take(size))
    Files.write(i.copy.resolve("a.txt"), benign)
    i.apply(i.preview.tree)
    assertEquals(s.read(s.project, "a.txt"), "benign\n")

  test("the copy's git state follows the project's when it takes the project's changes"):
    val s = Setup()
    s.repository()
    s.write(s.project, "a.txt", "one\n")
    val i = s.isolation
    i.enter()
    s.git(s.project, "add", "a.txt")
    s.git(s.project, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "second")
    s.isolation.enter()
    assertEquals(s.git(i.copy, "rev-parse", "HEAD"), s.git(s.project, "rev-parse", "HEAD"))
    assertEquals(s.git(i.copy, "status", "--porcelain"), "")

  test("large files are applied, and a rewritten file keeps its permissions"):
    val s = Setup()
    s.write(s.project, "private.txt", "one\n")
    if !Platform.isWindows then
      Files.setPosixFilePermissions(s.project.resolve("private.txt"), PosixFilePermissions.fromString("rw-------"))
    val i = s.isolation
    i.enter()
    s.write(i.copy, "private.txt", "two\n")
    Files.write(i.copy.resolve("big.bin"), Array.fill[Byte](3 * 1024 * 1024)(7))
    i.apply(i.preview.tree)
    assertEquals(Files.size(s.project.resolve("big.bin")), 3L * 1024 * 1024)
    if !Platform.isWindows then
      assertEquals(
        PosixFilePermissions.toString(Files.getPosixFilePermissions(s.project.resolve("private.txt"))),
        "rw-------"
      )

  test("a copy another process holds is refused, and a removed project config leaves the copy too"):
    val s = Setup()
    s.write(s.project, ".atc/config.json", "{}\n")
    val i = s.isolation
    i.enter()
    assert(s.exists(i.copy, ".atc/config.json"))
    Files.delete(s.project.resolve(".atc/config.json"))
    s.isolation.enter()
    assert(!s.exists(i.copy, ".atc/config.json"))
    val other = Setup()
    val lock = other.state.resolve("isolate").nn.resolve(atc.checkpoint.Checkpoints.digest(other.project)).nn
    Files.createDirectories(lock)
    val channel = FileChannel.open(lock.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).nn
    try
      channel.lock()
      val refused = intercept[IllegalStateException](other.isolation.enter())
      assert(refused.getMessage.nn.contains("another ATC session"), refused.getMessage)
    finally channel.close()
