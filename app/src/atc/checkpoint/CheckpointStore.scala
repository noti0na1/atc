package atc.checkpoint

import atc.platform.{Platform, PlatformPath}

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.{BasicFileAttributes, PosixFilePermissions}
import java.nio.file.{AtomicMoveNotSupportedException, Files, LinkOption, Path, Paths, StandardCopyOption}
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/** A recorded path: git's mode (`100644`, `100755` or `120000`) and object id. */
final case class Entry(mode: String, oid: String):
  def isRegular: Boolean = mode == "100644" || mode == "100755"

/** A path that differs between two snapshots, with added and deleted line counts
  * (`None` for binary content). */
final case class Change(path: String, before: Option[Entry], after: Option[Entry], lines: Option[(Int, Int)])

/** What a revert did, per project-relative path. */
final case class RevertReport(
  restored: List[String],
  deleted: List[String],
  merged: List[String],
  conflicts: List[(String, String)],
):
  def reverted: List[String] = restored ++ deleted ++ merged

/** Snapshots of a project's files in a git object store kept outside the project.
  *
  * The store has its own index, which serves as a stat cache, and borrows the
  * project's objects through `objects/info/alternates` when the project is the
  * root of a git work tree. Recorded: tracked and untracked files git does not
  * ignore, except new files over [[CheckpointStore.MaxNewFileBytes]], nested
  * repositories and the paths `exclude` names. Git runs with the store as its
  * directory, never with the project's repository, whose configuration the agent
  * may have written. All operations are serialized. */
final class CheckpointStore(val dir: Path, project: Path, exclude: String => Boolean):
  private val git = Git(dir, project)
  private var headTree: Option[String] = None

  /** Record the project and return the tree id. `force` lists project-relative
    * paths written since the last snapshot: git compares timestamps in whole
    * seconds, so a same-size rewrite within one second is otherwise missed. */
  def snapshot(force: Iterable[String] = Nil): String = synchronized:
    open()
    val status =
      git("status", "--porcelain=v2", "-z", "--no-renames", "--ignore-submodules=all", "--untracked-files=all")
    val (changed, deleted, untracked) = CheckpointStore.parseStatus(status)
    if untracked.size > CheckpointStore.MaxNewFiles then
      throw IllegalStateException(
        s"${untracked.size} files are new to the checkpoint store, more than ${CheckpointStore.MaxNewFiles}"
      )
    val added = untracked.filter(recordable)
    val kept = changed.filter(!exclude(_))
    val dropped = deleted ++ changed.filter(exclude) ++ caseStale(added)
    if dropped.nonEmpty then git.withInput(nul(dropped), "update-index", "-z", "--force-remove", "--stdin")
    val updates = kept ++ added
    if updates.nonEmpty then
      // A loose object costs about half a millisecond on macOS; bulk-checkin writes one pack.
      val bulk = if updates.size >= 200 then List("-c", "core.bigFileThreshold=1") else Nil
      git.withInput(nul(updates), bulk ++ List("update-index", "-z", "--add", "--remove", "--stdin")*)
    rehash(force.filter(path => !updates.contains(path) && !dropped.contains(path)))
    val tree = text(git("write-tree"))
    if !headTree.contains(tree) then
      val commit = text(git("commit-tree", tree, "-m", "atc checkpoint"))
      git("update-ref", "HEAD", commit)
      headTree = Some(tree)
    tree

  /** The paths that differ between trees `a` and `b`. */
  def changes(a: String, b: String): List[Change] = synchronized:
    if a == b then Nil
    else
      val raw = split(git("diff-tree", "-r", "-z", "--no-renames", "--no-commit-id", "--raw", a, b))
      val counts = CheckpointStore.parseNumstat(
        split(git(
          "diff-tree",
          "-r",
          "-z",
          "--no-renames",
          "--no-commit-id",
          "--no-ext-diff",
          "--no-textconv",
          "--numstat",
          a,
          b
        ))
      )
      raw.grouped(2).collect {
        case List(header, path) if header.startsWith(":") =>
          val fields = header.drop(1).split(' ')
          Change(path, entry(fields(0), fields(2)), entry(fields(1), fields(3)), counts.getOrElse(path, None))
      }.toList

  /** Keep the objects of `trees` and `before` alive under `name`, and copy the
    * contents in `before` into the store's own objects, so that a revert does not
    * depend on the project's repository keeping them. */
  def keep(name: String, trees: Seq[String], before: Iterable[String]): Unit = synchronized:
    val listing = trees.distinct.zipWithIndex.map((tree, i) => s"040000 tree $tree\t$i\n").mkString
    val holder = text(git.withInput(listing.getBytes(UTF_8), "mktree"))
    git("update-ref", s"refs/atc/turns/$name", holder)
    val blobs = before.toList.distinct
    if blobs.nonEmpty then
      git.withInput(
        (blobs.mkString("\n") + "\n").getBytes(UTF_8),
        "pack-objects",
        "-q",
        dir.resolve("objects/pack/pack").toString
      )
    ()

  /** Keep the newest `count` recorded turns and remove unreachable objects older than a day. */
  def prune(count: Int): Unit = synchronized:
    open()
    val refs = text(git("for-each-ref", "--format=%(refname)", "refs/atc/turns/")).linesIterator.toList.sorted
    refs.dropRight(count).foreach(ref => git("update-ref", "-d", ref))
    git("prune", "--expire=1.day.ago")

  /** Put each path back to `target`, its state before the agent changed it, where
    * the project still holds `expected`, the agent's last state. A text file the
    * user edited since gets a three-way merge with `expected` as the base, written
    * only when clean; every other difference is left alone and reported. `dirs` is
    * a tree whose directories are kept even when a revert empties them. */
  def revert(paths: Map[String, (Option[Entry], Option[Entry])], dirs: String): RevertReport = synchronized:
    val current = entries(snapshot(), paths.keys)
    var deletes = List.empty[String]
    var writes = List.empty[(String, Entry, Option[Array[Byte]])]
    var merged = List.empty[String]
    var conflicts = List.empty[(String, String)]
    for (path, (target, expected)) <- paths.toList.sortBy(_._1) do
      val now = current.get(path).orElse(Option.when(unrecorded(path))(Entry("?", "?")))
      if now == target then ()
      else if now == expected then
        target match
          case None => deletes ::= path
          case Some(entry) => writes ::= (path, entry, None)
      else
        mergeOrConflict(path, target, expected, now) match
          case Right((entry, content)) =>
            writes ::= (path, entry, Some(content))
            merged ::= path
          case Left(reason) => conflicts ::= (path, reason)
    val contents = blobs(writes.collect { case (_, entry, None) => entry.oid })
    var deleted = List.empty[String]
    for path <- deletes do
      if symlinkedParent(path) then conflicts ::= (path, "a parent directory is a symbolic link")
      else
        try
          Files.delete(resolve(path))
          deleted ::= path
        catch case NonFatal(e) => conflicts ::= (path, s"could not delete it: ${e.getMessage}")
    removeEmptyDirectories(deleted, dirs)
    var restored = List.empty[String]
    for (path, entry, content) <- writes do
      val bytes = content.orElse(contents.get(entry.oid))
      if symlinkedParent(path) then conflicts ::= (path, "a parent directory is a symbolic link")
      else if bytes.isEmpty then conflicts ::= (path, "its earlier content is no longer available")
      else
        try
          write(path, entry, bytes.get)
          if content.isEmpty then restored ::= path
        catch
          case NonFatal(e) =>
            conflicts ::= (path, s"could not write it: ${e.getMessage}")
            merged = merged.filterNot(_ == path)
    RevertReport(restored.sorted, deleted.sorted, merged.sorted, conflicts.sortBy(_._1))

  /** Create the store on first use. */
  private def open(): Unit =
    if !Files.isRegularFile(dir.resolve("HEAD")) then
      Files.createDirectories(dir.getParent)
      val objects = CheckpointStore.projectObjects(project)
      val format = objects.flatMap(CheckpointStore.objectFormat).getOrElse("sha1")
      Git.run(List("git", "init", "-q", "--bare", s"--object-format=$format", dir.toString), Array.emptyByteArray, None)
      if !Platform.isWindows then Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
      Files.writeString(dir.resolve("config"), Files.readString(dir.resolve("config")) + CheckpointStore.config)
      // The highest-precedence attributes: exact bytes, no filters and no line-ending conversion.
      Files.createDirectories(dir.resolve("info"))
      Files.writeString(dir.resolve("info/attributes"), "* -text -eol -filter -ident -working-tree-encoding\n")
      objects.foreach(o => Files.writeString(dir.resolve("objects/info/alternates"), s"${PlatformPath.portable(o)}\n"))
    if headTree.isEmpty then
      headTree = Option(text(git.check(false, "rev-parse", "-q", "--verify", "HEAD^{tree}"))).filter(_.nonEmpty)

  /** A new untracked path worth recording. */
  private def recordable(path: String): Boolean =
    !path.endsWith("/") && !exclude(path) &&
      (try
        val attributes = Files.readAttributes(resolve(path), classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS).nn
        attributes.isSymbolicLink || (attributes.isRegularFile && attributes.size <= CheckpointStore.MaxNewFileBytes)
      catch case NonFatal(_) => false)

  /** On a case-insensitive file system, recorded paths whose exact spelling is gone
    * because a new path differs from them only by case (a case-only rename). */
  private def caseStale(added: List[String]): List[String] =
    if !Platform.caseInsensitivePaths || added.isEmpty then Nil
    else
      val fold = (s: String) => Normalizer.normalize(s, Normalizer.Form.NFC).nn.toLowerCase(Locale.ROOT)
      val newFolds = added.map(fold).toSet
      val addedSet = added.toSet
      split(git("ls-files", "-z")).filter(p => !addedSet.contains(p) && newFolds.contains(fold(p)) && !existsExactly(p))

  /** Hash `paths` again and record them, when they are regular files already recorded. */
  private def rehash(paths: Iterable[String]): Unit =
    val candidates = paths.toList.distinct.filter: path =>
      !path.contains('\n') && !exclude(path) && Files.isRegularFile(resolve(path), LinkOption.NOFOLLOW_LINKS)
    if candidates.nonEmpty then
      val recorded = split(git(List("ls-files", "-z", "-s", "--") ++ candidates.map(":(literal)" + _)*)).flatMap:
        line =>
          line.split('\t') match
            case Array(meta, path) => Some(path -> meta.takeWhile(_ != ' '))
            case _ => None
      .toMap
      val known = candidates.filter(recorded.contains)
      if known.nonEmpty then
        val oids = text(git.withInput(
          (known.mkString("\n") + "\n").getBytes(UTF_8),
          "hash-object",
          "-w",
          "--no-filters",
          "--stdin-paths"
        ))
          .linesIterator.toList
        val info = known.zip(oids).map: (path, oid) =>
          val mode =
            if Platform.isWindows then recorded(path)
            else if Files.isExecutable(resolve(path)) then "100755"
            else "100644"
          s"$mode $oid\t$path"
        git.withInput(nul(info), "update-index", "-z", "--index-info")
    ()

  /** Map each of `paths` present in `tree` to its entry. */
  private def entries(tree: String, paths: Iterable[String]): Map[String, Entry] =
    val wanted = paths.toSet
    val listing =
      if wanted.size > 100 then git("ls-tree", "-r", "-z", "--full-tree", tree)
      else git(List("ls-tree", "-r", "-z", "--full-tree", tree, "--") ++ wanted.map(":(literal)" + _)*)
    split(listing).flatMap: line =>
      line.split("\t", 2) match
        case Array(meta, path) if wanted.contains(path) =>
          meta.split(' ') match
            case Array(mode, _, oid) => Some(path -> Entry(mode, oid))
            case _ => None
        case _ => None
    .toMap

  /** The content of each object id, read through one `cat-file --batch`; missing objects are left out. */
  private def blobs(oids: List[String]): Map[String, Array[Byte]] =
    if oids.isEmpty then Map.empty
    else
      val out = git.withInput((oids.distinct.mkString("\n") + "\n").getBytes(UTF_8), "cat-file", "--batch")
      var result = Map.empty[String, Array[Byte]]
      var at = 0
      while at < out.length && out.indexOf('\n'.toByte, at) >= 0 do
        val end = out.indexOf('\n'.toByte, at)
        val header = String(out, at, end - at, UTF_8).split(' ')
        at = end + 1
        if header.length == 3 then
          val size = header(2).toInt
          result += header(0) -> java.util.Arrays.copyOfRange(out, at, at + size).nn
          at += size + 1
      result

  /** Merge the user's later edit of a text file with the undo of the agent's change. */
  private def mergeOrConflict(
    path: String,
    target: Option[Entry],
    expected: Option[Entry],
    now: Option[Entry],
  ): Either[String, (Entry, Array[Byte])] =
    (target, expected, now) match
      case (Some(t), Some(e), Some(n)) if t.isRegular && e.isRegular && n.isRegular =>
        val mode = if n.mode == e.mode then t.mode else n.mode
        val contents = blobs(List(t.oid, e.oid, n.oid))
        if List(t, e, n).exists(x => !contents.contains(x.oid)) then Left("its earlier content is no longer available")
        else if t.oid == e.oid then Right((Entry(mode, n.oid), contents(n.oid))) // only the mode changed
        else
          val temp = Files.createTempDirectory(dir, "merge").nn
          try
            val files = List("current" -> n, "agent" -> e, "before" -> t).map: (name, entry) =>
              val file = temp.resolve(name).nn
              Files.write(file, contents(entry.oid))
              file.toString
            val result = Git.run(
              List("git", "merge-file", "-p", "-L", "current", "-L", "agent", "-L", "before") ++ files,
              Array.emptyByteArray,
              None,
              check = false,
            )
            if result.exit == 0 then Right((Entry(mode, ""), result.out))
            else if result.exit > 0 && result.exit < 128 then Left("the same lines were changed after the turn")
            else Left("it was changed after the turn and cannot be merged")
          finally
            Using.resource(Files.list(temp).nn)(_.iterator.nn.asScala.foreach(Files.delete))
            Files.delete(temp)
      case (_, _, Some(Entry("?", _))) => Left("it exists but is not recorded (ignored, too large or excluded)")
      case (None, _, _) => Left("the agent created it and it was changed afterwards")
      case (_, None, _) => Left("the agent deleted it and it was created again afterwards")
      case (_, _, None) => Left("it was deleted afterwards")
      case _ => Left("it is not a regular file and was changed afterwards")

  private def write(path: String, entry: Entry, content: Array[Byte]): Unit =
    val target = resolve(path)
    Files.createDirectories(target.getParent)
    // A directory here was emptied by the deletions of this revert; delete fails if it is not empty.
    if Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) then Files.delete(target)
    if entry.mode == "120000" then
      Files.deleteIfExists(target)
      Files.createSymbolicLink(target, Paths.get(String(content, UTF_8)))
    else
      val temp = Files.createTempFile(target.getParent, ".atc-", ".tmp").nn
      try
        Files.write(temp, content)
        if !Platform.isWindows then
          val permissions = if entry.mode == "100755" then "rwxr-xr-x" else "rw-r--r--"
          Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString(permissions))
        try Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        catch case _: AtomicMoveNotSupportedException => Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
      finally Files.deleteIfExists(temp)
    ()

  /** Remove the directories that deleting `deleted` left empty and that `dirs` does not hold. */
  private def removeEmptyDirectories(deleted: List[String], dirs: String): Unit =
    val parents = deleted.flatMap(path =>
      Iterator.iterate(path)(p => p.take(p.lastIndexOf('/').max(0))).drop(1).takeWhile(_.nonEmpty)
    )
    if parents.nonEmpty then
      val keep = split(git("ls-tree", "-d", "-r", "-z", "--name-only", "--full-tree", dirs)).toSet
      for directory <- parents.distinct.sortBy(-_.count(_ == '/')) if !keep.contains(directory) do
        try Files.delete(resolve(directory))
        catch case NonFatal(_) => ()

  /** A path on disk with this exact spelling that the current snapshot does not record. */
  private def unrecorded(path: String): Boolean =
    val file = resolve(path)
    Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) &&
    existsExactly(path)

  /** Whether each component of `path` exists with exactly this spelling. */
  private def existsExactly(path: String): Boolean =
    if !Platform.caseInsensitivePaths then Files.exists(resolve(path), LinkOption.NOFOLLOW_LINKS)
    else
      val nfc = (s: String) => Normalizer.normalize(s, Normalizer.Form.NFC).nn
      path.split('/').foldLeft(Option(project)): (directory, name) =>
        directory.filter: d =>
          try
            Using.resource(Files.list(d).nn)(_.iterator.nn.asScala.exists(p =>
              nfc(p.getFileName.toString) == nfc(name)
            ))
          catch case NonFatal(_) => false
        .map(_.resolve(name).nn)
      .isDefined

  /** Whether a directory on the way to `path` is a symbolic link, which could lead outside the project. */
  private def symlinkedParent(path: String): Boolean =
    path.split(
      '/'
    ).dropRight(1).scanLeft(project)((dir, name) => dir.resolve(name).nn).drop(1).exists(Files.isSymbolicLink)

  private def resolve(path: String): Path = project.resolve(PlatformPath.native(path)).nn
  private def entry(mode: String, oid: String): Option[Entry] = Option.when(mode != "000000")(Entry(mode, oid))
  private def nul(paths: Iterable[String]): Array[Byte] = paths.mkString("", "\u0000", "\u0000").getBytes(UTF_8)
  private def split(out: Array[Byte]): List[String] = String(out, UTF_8).split('\u0000').toList.filter(_.nonEmpty)
  private def text(out: Array[Byte]): String = String(out, UTF_8).trim

private[atc] object CheckpointStore:
  /** New files larger than this are not recorded. */
  val MaxNewFileBytes: Long = 2L * 1024 * 1024
  /** A snapshot that finds more new files than this gives up: the directory is
    * probably not a project (a home directory, a disk root). */
  val MaxNewFiles: Int = 100_000

  /** Settings appended to the store's configuration. `ignorecase` is off so that
    * case-only renames are visible; the file system monitor could run a program. */
  val config: String =
    s"""
       |[core]
       |	bare = false
       |	autocrlf = false
       |	safecrlf = false
       |	ignorecase = false
       |	precomposeunicode = ${Platform.isMac}
       |	filemode = ${!Platform.isWindows}
       |	untrackedCache = true
       |	fsmonitor = false
       |	bigFileThreshold = 512m
       |[status]
       |	showUntrackedFiles = all
       |	renames = false
       |[index]
       |	version = 4
       |[gc]
       |	auto = 0
       |[maintenance]
       |	auto = false
       |""".stripMargin

  /** Changed, deleted and untracked paths from `status --porcelain=v2 -z`. */
  def parseStatus(out: Array[Byte]): (List[String], List[String], List[String]) =
    val records = String(out, UTF_8).split('\u0000').toList
    val tracked = records.filter(_.startsWith("1 ")).flatMap: record =>
      val fields = record.split(" ", 9)
      Option.when(fields.length == 9 && fields(1)(1) != '.')((fields(1)(1), fields(8)))
    (
      tracked.collect { case (state, path) if state != 'D' => path },
      tracked.collect { case ('D', path) => path },
      records.filter(_.startsWith("? ")).map(_.drop(2)),
    )

  /** Line counts per path from `diff-tree --numstat -z`; binary files map to `None`. */
  def parseNumstat(records: List[String]): Map[String, Option[(Int, Int)]] =
    records.flatMap: record =>
      record.split("\t", 3) match
        case Array(added, deleted, path) => Some(path -> added.toIntOption.zip(deleted.toIntOption))
        case _ => None
    .toMap

  /** The object directory of the repository whose work tree root is `project`,
    * found without running git in the project. */
  def projectObjects(project: Path): Option[Path] =
    val dotGit = project.resolve(".git").nn
    val gitDir =
      if Files.isDirectory(dotGit) then Some(dotGit)
      else if Files.isRegularFile(dotGit) then
        Files.readString(dotGit).linesIterator.collectFirst {
          case line if line.startsWith("gitdir:") => project.resolve(line.drop(7).trim).nn.normalize.nn
        }
      else None
    val common = gitDir.map: d =>
      val file = d.resolve("commondir").nn
      if Files.isRegularFile(file) then d.resolve(Files.readString(file).trim).nn.normalize.nn else d
    common.map(_.resolve("objects").nn).filter(Files.isDirectory(_))

  /** `sha256` when the repository owning `objects` uses it. */
  def objectFormat(objects: Path): Option[String] =
    val config = objects.getParent.resolve("config").nn
    try
      Option.when(Files.isRegularFile(config) &&
        Files.readString(config).linesIterator.exists(_.trim.matches("(?i)objectformat\\s*=\\s*sha256")))(
        "sha256"
      )
    catch case NonFatal(_) => None

/** `git` with the store as its directory and the project as its work tree. */
private[checkpoint] final class Git(gitDir: Path, workTree: Path):
  private val prefix =
    List("git", s"--git-dir=$gitDir", s"--work-tree=$workTree", "-c", "core.fsmonitor=false") ++
      List("-c", "user.name=atc", "-c", "user.email=atc@localhost", "-c", "commit.gpgSign=false")

  def apply(args: String*): Array[Byte] = Git.run(prefix ++ args, Array.emptyByteArray, Some(workTree)).out

  def withInput(input: Array[Byte], args: String*): Array[Byte] = Git.run(prefix ++ args, input, Some(workTree)).out

  def check(fail: Boolean, args: String*): Array[Byte] =
    Git.run(prefix ++ args, Array.emptyByteArray, Some(workTree), check = fail).out

private[checkpoint] object Git:
  final case class Result(exit: Int, out: Array[Byte], err: String)

  /** How long one git command may take. */
  private val TimeoutSeconds = 300L

  /** Run a git command line. The environment loses every `GIT_` variable, which
    * would redirect git to another repository, index or object directory. */
  def run(command: List[String], input: Array[Byte], cwd: Option[Path], check: Boolean = true): Result =
    val builder = ProcessBuilder(command.asJava)
    cwd.foreach(d => builder.directory(d.toFile))
    val environment = builder.environment().nn
    environment.keySet().nn.removeIf(_.toUpperCase(Locale.ROOT).startsWith("GIT_"))
    environment.put("GIT_TERMINAL_PROMPT", "0")
    val process = builder.start().nn
    val out = drain(process.getInputStream.nn)
    val err = drain(process.getErrorStream.nn)
    val writer = Thread(() =>
      try Using.resource(process.getOutputStream.nn)(_.write(input))
      catch case NonFatal(_) => ()
    )
    writer.setDaemon(true)
    writer.start()
    if !process.waitFor(TimeoutSeconds, TimeUnit.SECONDS) then
      process.destroyForcibly()
      throw IllegalStateException(s"${command.drop(3).mkString(" ")} did not finish in $TimeoutSeconds s")
    val result = Result(process.exitValue, out(), String(err(), UTF_8).trim)
    if check && result.exit != 0 then
      val shown = command.filterNot(a => a.startsWith("--git-dir=") || a.startsWith("--work-tree=")).mkString(" ")
      throw IllegalStateException(s"$shown failed (${result.exit}): ${result.err.take(500)}")
    result

  /** Read `stream` to its end on a daemon thread; the returned function waits for the bytes. */
  private def drain(stream: InputStream): () => Array[Byte] =
    val bytes = AtomicReference(Array.emptyByteArray)
    val thread = Thread(() => bytes.set(Using.resource(stream)(_.readAllBytes().nn)))
    thread.setDaemon(true)
    thread.start()
    () =>
      thread.join()
      bytes.get().nn
