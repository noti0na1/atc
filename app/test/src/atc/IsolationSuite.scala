package atc

import atc.checkpoint.Isolation
import atc.platform.Platform

import java.nio.file.{Files, LinkOption, Path}
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
    val report = i.apply()
    assertEquals(report.restored.toSet, Set("a.txt", "new/b.txt"))
    assertEquals(report.deleted, List("gone.txt"))
    assertEquals(s.read(s.project, "a.txt"), "two\n")
    assertEquals(s.read(s.project, "new/b.txt"), "bee\n")
    assert(!s.exists(s.project, "gone.txt"))
    assert(i.unapplied.isEmpty)
    assert(i.apply().reverted.isEmpty, "applying again changes nothing")

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
    val report = i.apply()
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
    i.apply()
    assertEquals(s.read(s.project, "secrets/key"), "k1")
