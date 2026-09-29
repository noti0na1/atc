package atc.checkpoint

import atc.platform.{Platform, PlatformPath}

import java.nio.channels.{FileChannel, OverlappingFileLockException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Isolate mode's copy of a project and the stores that track what changed in it.
  *
  * The copy lives at a fixed place per project, outside `~/.atc`, which the OS sandbox
  * hides from commands. It is kept between sessions, so build output stays warm, and it
  * starts as a copy-on-write clone where the file system offers one. Two checkpoint
  * stores record the project and the copy; `base` is the tree both sides last agreed on.
  * The copy's pending changes are its differences from `base`: [[apply]] writes them into
  * the project, merging a text file the user changed meanwhile, and adds what the project
  * then holds to `base`; [[discard]] resets the copy to `base`. The copy takes the
  * project's changes since `base`, and its git state, only while the project holds every
  * change of the copy, so that no change of the copy is lost; `base` is then the copy's
  * tree. `excluded` names project-relative paths neither store records (classified and
  * no-access paths); they are neither applied nor taken, and `.atc` is copied whole.
  * One process at a time uses a copy. */
final class Isolation(val project: Path, stateDir: Path, dataDir: Path, excluded: String => Boolean):
  private val name = Checkpoints.digest(project)
  val copy: Path = dataDir.resolve(name).nn.resolve(Option(project.getFileName).fold("project")(_.toString)).nn
  private val storeDir = stateDir.resolve("isolate").nn.resolve(name).nn
  private def skip(path: String) = path == ".atc" || path.startsWith(".atc/") || excluded(path)
  private lazy val projectStore = store("project", project, "copy")
  private lazy val copyStore = store("copy", copy, "project")
  private val baseFile = storeDir.resolve("base").nn
  private val gitStampFile = storeDir.resolve("git").nn

  /** A store over `root` that reads the project repository's objects and the other store's,
    * never the copy's `.git`: the agent can write objects there, and one planted under the
    * id of a file's content would change what [[apply]] writes. Files of any size are
    * recorded, since an unrecorded change would not be applied. */
  private def store(side: String, root: Path, other: String) =
    CheckpointStore(
      storeDir.resolve(side).nn,
      root,
      skip,
      CheckpointStore.projectObjects(project).toList :+ storeDir.resolve(other).nn.resolve("objects").nn,
      Long.MaxValue
    )

  /** Make the copy current: create it when there is none, otherwise take the project's changes
    * when the project holds all of the copy's. Returns the number of changes it does not. */
  def enter(): Int =
    if !Isolation.hold(storeDir.resolve("lock").nn) then
      throw IllegalStateException(s"another ATC session is using the copy of ${PlatformPath.display(project)}")
    val waiting =
      if !Files.isDirectory(copy) then
        create()
        0
      else
        val count = unapplied.size
        if count == 0 then sync()
        count
    copyConfig()
    waiting

  /** What the copy changed since `base`. */
  def pending: List[Change] = copyStore.changes(base, copyStore.snapshot())

  /** The pending changes the project does not hold yet. */
  def unapplied: List[Change] = preview.changes.map(_._1)

  /** The copy's current tree, with the pending changes the project does not hold yet, each
    * with whether the project changed that path since `base` too, so that applying it means
    * a merge. */
  def preview: Isolation.Preview =
    val copyTree = copyStore.snapshot()
    val changes = copyStore.changes(base, copyTree)
    if changes.isEmpty then Isolation.Preview(copyTree, Nil)
    else
      val projectTree = projectStore.snapshot()
      val differing = projectStore.changes(projectTree, copyTree).map(_.path).toSet
      val changedThere = projectStore.changes(base, projectTree).map(_.path).toSet
      Isolation.Preview(
        copyTree,
        changes.filter(c => differing.contains(c.path)).map(c => c -> changedThere.contains(c.path))
      )

  /** Write the changes of `tree`, a state of the copy [[preview]] showed, into the project.
    * Where the project still holds `base` it takes the copy's version; a text file changed on
    * both sides gets a three-way merge, written only when clean; anything else is reported
    * and left alone. Every path the project now agrees on takes the copy's version in `base`. */
  def apply(tree: String): RevertReport =
    val changes = copyStore.changes(base, tree)
    val report = projectStore.revert(changes.map(c => c.path -> (c.after, c.before)).toMap, tree)
    val conflicted = report.conflicts.map(_._1).toSet
    setBase(copyStore.edited(base, changes.filterNot(c => conflicted(c.path)).map(c => c.path -> c.after).toMap))
    report

  /** Put the copy back to `base`, then let it take the project's changes. */
  def discard(): RevertReport =
    val report = copyStore.revert(pending.map(c => c.path -> (c.before, c.after)).toMap, base)
    if unapplied.isEmpty then sync()
    report

  private def base: String =
    if !Files.isRegularFile(baseFile) then
      throw IllegalStateException(
        s"the copy at ${PlatformPath.display(copy)} has lost the record of what it started from; " +
          "move the copy away to start a fresh one"
      )
    Files.readString(baseFile, UTF_8).nn.trim

  private def setBase(tree: String): Unit =
    Files.createDirectories(storeDir)
    Files.writeString(baseFile, tree + "\n", UTF_8)
    ()

  /** Take the project's changes since `base` and its git state; the copy's tree becomes `base`. */
  private def sync(): Unit =
    val now = projectStore.snapshot()
    copyStore.revert(projectStore.changes(base, now).map(c => c.path -> (c.after, c.before)).toMap, now)
    refreshGit()
    setBase(copyStore.snapshot())

  private def create(): Unit =
    val parent = copy.getParent.nn
    Files.createDirectories(parent)
    val staging = parent.resolve(copy.getFileName.toString + ".partial").nn
    Isolation.deleteTree(staging)
    Isolation.cloneTree(project, staging)
    projectStore.snapshot()
    // The clone's own tree, so that a file the user saves meanwhile is the project's change,
    // not the copy's. The store's index stays valid when the directory moves.
    setBase(store("copy", staging, "project").snapshot())
    Files.writeString(gitStampFile, Isolation.gitStamp(project.resolve(".git").nn), UTF_8)
    Files.move(staging, copy)
    ()

  /** Give the copy the project's `.git` when it changed. The copy's files now match the
    * project's, and a stale index or `HEAD` would let `git checkout` or `git stash` there
    * bring back old content that [[apply]] would then write. */
  private def refreshGit(): Unit =
    val source = project.resolve(".git").nn
    if Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) then
      val stamp = Isolation.gitStamp(source)
      if !Files.isRegularFile(gitStampFile) || Files.readString(gitStampFile, UTF_8) != stamp then
        val staging = copy.resolveSibling(s"${copy.getFileName}.git.partial").nn
        Isolation.deleteTree(staging)
        Isolation.cloneTree(source, staging)
        Isolation.deleteTree(copy.resolve(".git").nn)
        Files.move(staging, copy.resolve(".git"))
        Files.writeString(gitStampFile, stamp, UTF_8)
        ()

  /** The project's configuration and keys, which the stores do not record: copied each time,
    * and removed from the copy when the project no longer has them. */
  private def copyConfig(): Unit =
    val target = copy.resolve(".atc").nn
    if Files.isSymbolicLink(target) then Files.delete(target)
    for file <- List("config.json", "keys.properties") do
      val source = project.resolve(".atc").nn.resolve(file).nn
      if Files.isRegularFile(source) then
        Files.createDirectories(target)
        Files.copy(
          source,
          target.resolve(file),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.COPY_ATTRIBUTES,
          LinkOption.NOFOLLOW_LINKS
        )
      else Files.deleteIfExists(target.resolve(file))

object Isolation:
  /** A state of the copy, as a tree, and its changes the project does not hold yet. */
  final case class Preview(tree: String, changes: List[(Change, Boolean)])

  /** Where copies live: the platform's per-user application data directory. */
  def dataDir(home: Path): Path =
    if Platform.isMac then home.resolve("Library/Application Support/atc/isolate").nn
    else if Platform.isWindows then
      Option(System.getenv("LOCALAPPDATA")).map(Paths.get(_).nn).getOrElse(home.resolve("AppData/Local"))
        .resolve("atc/isolate").nn
    else
      Option(System.getenv("XDG_DATA_HOME")).filter(_.nonEmpty).map(Paths.get(_).nn)
        .getOrElse(home.resolve(".local/share")).resolve("atc/isolate").nn

  /** The locks this process holds, kept until it exits: a session that moves in and out of
    * isolate mode stays one user of the copy. */
  private val held = ConcurrentHashMap[Path, FileChannel]()

  /** Hold `file`'s lock for the rest of this process; false when another process holds it. */
  private def hold(file: Path): Boolean = synchronized:
    held.containsKey(file) || {
      Files.createDirectories(file.getParent)
      val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE).nn
      val lock =
        try channel.tryLock()
        catch case _: OverlappingFileLockException => null
      if lock == null then
        channel.close()
        false
      else
        held.put(file, channel)
        true
    }

  /** What of a repository's git state [[Isolation.refreshGit]] compares: `HEAD` and the size and
    * time of the index, the packed references and `HEAD`'s log. */
  private def gitStamp(git: Path): String =
    val head = git.resolve("HEAD").nn
    val headText = if Files.isRegularFile(head) then Files.readString(head, UTF_8).nn.trim else ""
    (headText :: List("index", "packed-refs", "logs/HEAD").map: name =>
      val file = git.resolve(name).nn
      if Files.isRegularFile(file) then s"$name ${Files.size(file)} ${Files.getLastModifiedTime(file).toMillis}"
      else name
    ).mkString("\n")

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
