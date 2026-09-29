package atc.checkpoint

import atc.platform.{Platform, PlatformPath}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Isolate mode's copy of a project and the stores that track what changed in it.
  *
  * The copy lives at a fixed place per project, outside `~/.atc`, which the OS sandbox
  * hides from commands. It is kept between sessions, so build output stays warm, and it
  * starts as a copy-on-write clone where the file system offers one. Two checkpoint
  * stores record the project and the copy; `base` is the project's tree when the copy last
  * took the project's changes. The copy's pending changes are its differences from `base`:
  * [[apply]] writes them into the project, merging a text file the user changed meanwhile,
  * and [[discard]] resets the copy to `base`. [[enter]] takes the project's changes since
  * `base` only while nothing is pending, so `base` stays the ancestor of both sides.
  * `excluded` names project-relative paths neither store records (classified and
  * no-access paths); they are neither applied nor taken, and `.atc` is copied whole. */
final class Isolation(val project: Path, stateDir: Path, dataDir: Path, excluded: String => Boolean):
  private val name = Checkpoints.digest(project)
  val copy: Path = dataDir.resolve(name).nn.resolve(Option(project.getFileName).fold("project")(_.toString)).nn
  private val storeDir = stateDir.resolve("isolate").nn.resolve(name).nn
  private def skip(path: String) = path == ".atc" || path.startsWith(".atc/") || excluded(path)
  private lazy val projectStore = linked(CheckpointStore(storeDir.resolve("project").nn, project, skip), "copy")
  private lazy val copyStore = linked(CheckpointStore(storeDir.resolve("copy").nn, copy, skip), "project")
  private val baseFile = storeDir.resolve("base").nn

  /** Make the copy current: create it when there is none, otherwise take the project's changes
    * since `base` if nothing is pending. Returns the number of pending changes kept. */
  def enter(): Int =
    val pendingNow =
      if !Files.isDirectory(copy) then
        create()
        0
      else
        val waiting = pending.size
        if waiting == 0 then
          val now = projectStore.snapshot()
          val taken = projectStore.changes(base, now)
          copyStore.revert(taken.map(c => c.path -> (c.after, c.before)).toMap, now)
          setBase(now)
        waiting
    copyConfig()
    pendingNow

  /** What the copy changed since `base`. */
  def pending: List[Change] = copyStore.changes(base, copyStore.snapshot())

  /** The pending changes the project does not hold yet. */
  def unapplied: List[Change] = preview.map(_._1)

  /** [[unapplied]], each with whether the project changed that path since `base` too, so that
    * applying it means a merge. */
  def preview: List[(Change, Boolean)] =
    val copyTree = copyStore.snapshot()
    val changes = copyStore.changes(base, copyTree)
    if changes.isEmpty then Nil
    else
      val projectTree = projectStore.snapshot()
      val differing = projectStore.changes(projectTree, copyTree).map(_.path).toSet
      val changedThere = projectStore.changes(base, projectTree).map(_.path).toSet
      changes.filter(c => differing.contains(c.path)).map(c => c -> changedThere.contains(c.path))

  /** Write the copy's changes into the project. Where the project still holds `base` it
    * takes the copy's version; a text file changed on both sides gets a three-way merge,
    * written only when clean; anything else is reported and left alone. */
  def apply(): RevertReport =
    val tree = copyStore.snapshot()
    projectStore.revert(copyStore.changes(base, tree).map(c => c.path -> (c.after, c.before)).toMap, tree)

  /** Put the copy back to `base`. */
  def discard(): RevertReport =
    val changes = pending
    copyStore.revert(changes.map(c => c.path -> (c.before, c.after)).toMap, base)

  private def base: String = Files.readString(baseFile, UTF_8).nn.trim

  private def setBase(tree: String): Unit =
    Files.createDirectories(storeDir)
    Files.writeString(baseFile, tree + "\n", UTF_8)
    ()

  private def create(): Unit =
    val parent = copy.getParent.nn
    Files.createDirectories(parent)
    val staging = parent.resolve(copy.getFileName.toString + ".partial").nn
    Isolation.deleteTree(staging)
    Isolation.cloneTree(project, staging)
    Files.move(staging, copy)
    setBase(projectStore.snapshot())
    copyStore.snapshot()
    ()

  /** The project's configuration and keys, which the stores do not record. */
  private def copyConfig(): Unit =
    val target = copy.resolve(".atc").nn
    for file <- List("config.json", "keys.properties") do
      val source = project.resolve(".atc").nn.resolve(file).nn
      if Files.isRegularFile(source) then
        Files.createDirectories(target)
        Files.copy(
          source,
          target.resolve(file),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.COPY_ATTRIBUTES
        )

  /** Let `store` read the other store's objects, which apply and the project's changes need. */
  private def linked(store: CheckpointStore, other: String): CheckpointStore =
    store.snapshot() // creates the store
    val alternates = store.dir.resolve("objects/info/alternates").nn
    val line = PlatformPath.portable(storeDir.resolve(other).nn.resolve("objects").nn)
    val lines = if Files.exists(alternates) then Files.readAllLines(alternates).nn.asScala.toList else Nil
    if !lines.contains(line) then Files.writeString(alternates, (lines :+ line).mkString("", "\n", "\n"), UTF_8)
    store

object Isolation:
  /** Where copies live: the platform's per-user application data directory. */
  def dataDir(home: Path): Path =
    if Platform.isMac then home.resolve("Library/Application Support/atc/isolate").nn
    else if Platform.isWindows then
      Option(System.getenv("LOCALAPPDATA")).map(Paths.get(_).nn).getOrElse(home.resolve("AppData/Local"))
        .resolve("atc/isolate").nn
    else
      Option(System.getenv("XDG_DATA_HOME")).filter(_.nonEmpty).map(Paths.get(_).nn)
        .getOrElse(home.resolve(".local/share")).resolve("atc/isolate").nn

  /** Copy `source` to the new directory `target`, as copy-on-write clones where the file
    * system can make them (APFS, btrfs, XFS), keeping links as links. */
  def cloneTree(source: Path, target: Path): Unit =
    def run(command: List[String]): Boolean =
      val process = ProcessBuilder(command.asJava).redirectErrorStream(true).start().nn
      process.getInputStream.nn.readAllBytes()
      process.waitFor() == 0
    val cloned =
      if Platform.isMac then
        run(List("/bin/cp", "-c", "-R", "-p", source.toString, target.toString)) || {
          deleteTree(target)
          run(List("/bin/cp", "-R", "-p", source.toString, target.toString))
        }
      else if Platform.isWindows then false
      else run(List("cp", "-a", "--reflink=auto", source.toString, target.toString))
    if !cloned then throw IllegalStateException(s"could not copy $source to $target")

  def deleteTree(root: Path): Unit =
    if Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS) then
      Using.resource(Files.walk(root).nn)(_.iterator.nn.asScala.toList).reverse.foreach(Files.deleteIfExists)
