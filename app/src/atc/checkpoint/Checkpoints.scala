package atc.checkpoint

import atc.agent.ToolCallHooks
import atc.host.Host
import atc.perms.{Mode, Policy, ScopeId}
import atc.platform.{Platform, PlatformPath}

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Path, Paths}
import java.security.MessageDigest
import java.util.{HexFormat, UUID}
import scala.collection.mutable
import scala.util.control.NonFatal

/** Records what the agent changes in the project during each turn, so that `/undo`
  * can revert it. A snapshot is taken before and after each tool call; the agent's
  * changes are the union of the differences within its calls, while changes made
  * between calls are the user's. Between calls of one turn, changes still count as
  * the agent's while a process it spawned is running. The first failure turns
  * checkpoints off for the session instead of disturbing the turn. `project` is a
  * canonical path; the store lives in `stateDir/checkpoints` (`~/.atc` in ATC). */
final class Checkpoints(project: Path, stateDir: Path, host: Host, policy: Policy) extends ToolCallHooks:
  private val store =
    CheckpointStore(stateDir.resolve("checkpoints").nn.resolve(Checkpoints.digest(project)).nn, project, excluded)
  private val turns = mutable.ArrayBuffer[Checkpoints.Turn]()
  private var calls = Vector.empty[(String, String)]
  private var before: Option[String] = None
  private var effectsBefore = 0L
  private var lastAfter: Option[String] = None
  @volatile private var failure: Option[String] = None
  private var failureShown = false

  /** Take the first snapshot in the background, so that the first tool call finds the
    * store's index filled, and prune old turns. */
  def warm(): Unit =
    val work: Runnable = () =>
      attempt:
        store.snapshot()
        store.prune(Checkpoints.KeptTurns)
      ()
    val thread = Thread(work, "atc-checkpoints")
    thread.setDaemon(true)
    thread.start()

  def beginTurn(): Unit = synchronized:
    calls = Vector.empty
    lastAfter = None

  def beforeCall(): Unit = synchronized:
    before = None
    if active then
      host.takeWrittenPaths() // written before this call (by `/run`): the user's
      effectsBefore = host.effects
      before = lastAfter.filter(_ => host.hasRunningProcesses).orElse(attempt(store.snapshot()))

  def afterCall(): Unit = synchronized:
    for start <- before do
      val end =
        if host.effects == effectsBefore && !host.hasRunningProcesses then Some(start)
        else attempt(store.snapshot(relative(host.takeWrittenPaths())))
      for finish <- end do
        if finish != start then calls :+= (start, finish)
        lastAfter = Some(finish)
    before = None

  /** Record the turn that just ended and return what the agent changed in it. */
  def endTurn(): List[Change] = synchronized:
    if calls.isEmpty then Nil
    else
      val recorded = attempt:
        val paths = mutable.LinkedHashMap[String, (Option[Entry], Option[Entry])]()
        for (start, finish) <- calls; change <- store.changes(start, finish) do
          val first = paths.get(change.path).fold(change.before)(_._1)
          paths(change.path) = (first, change.after)
        val net = paths.filter((_, states) => states._1 != states._2).toMap
        if net.isEmpty then Nil
        else
          val lines = store.changes(calls.head._1, calls.last._2).map(c => c.path -> c.lines).toMap
          val name = f"${System.currentTimeMillis()}%013d-${UUID.randomUUID().toString.take(8)}"
          store.keep(name, calls.flatMap((a, b) => List(a, b)), net.values.flatMap(_._1).map(_.oid))
          turns += Checkpoints.Turn(mutable.Map.from(net), calls.head._1)
          net.toList.sortBy(_._1).map((path, states) => Change(path, states._1, states._2, lines.getOrElse(path, None)))
      calls = Vector.empty
      recorded.getOrElse(Nil)

  /** Revert the most recent turn whose changes have not been reverted, or only the
    * given paths of it (directories include what lies beneath). */
  def undo(requested: List[String]): Either[String, RevertReport] = synchronized:
    failure match
      case Some(reason) => Left(s"Checkpoints are off for this session: $reason")
      case None =>
        turns.findLast(_.pending.nonEmpty) match
          case None => Left("Nothing to undo: no recorded turn changed files.")
          case Some(turn) =>
            val wanted = requested.map(normalize)
            val selected = turn.pending.filter((path, _) => wanted.isEmpty || wanted.exists(covers(_, path))).toMap
            if selected.isEmpty then Left(s"The last turn with changes did not change ${requested.mkString(", ")}.")
            else
              attempt(store.revert(selected, turn.baseline)) match
                case Some(report) =>
                  turn.pending --= selected.keys
                  Right(report)
                case None => Left(s"Undo failed: ${failure.getOrElse("unknown error")}")

  /** The reason checkpoints stopped, once: the caller shows it to the user. */
  def takeFailure(): Option[String] = synchronized:
    if failureShown then None
    else
      failureShown = failure.isDefined
      failure

  private def active: Boolean = failure.isEmpty && policy.mode != Mode.ReadOnly

  private def attempt[T](body: => T): Option[T] =
    if failure.isDefined then None
    else
      try Some(body)
      catch
        case e: IOException if Option(e.getMessage).exists(_.contains("Cannot run program")) =>
          failure = Some("git is not installed or not on the PATH")
          None
        case NonFatal(e) =>
          failure = Some(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
          None

  /** Paths never recorded: ATC's own state (the store included), classified paths and
    * paths under a locked rule that grants no access (such as `.atc`). */
  private def excluded(path: String): Boolean =
    val absolute = project.resolve(PlatformPath.native(path)).nn
    absolute.startsWith(stateDir) || {
      val permission = policy.effective(ScopeId.Base, absolute)
      permission.classified || (permission.locked && !permission.canRead)
    }

  private def relative(paths: Set[Path]): Set[String] =
    paths.filter(_.startsWith(project)).map(p => PlatformPath.portable(project.relativize(p).nn))

  /** A path the user typed, relative to the project with `/` separators. */
  private def normalize(typed: String): String =
    val raw = Paths.get(PlatformPath.native(PlatformPath.expandHome(typed.trim))).nn
    val absolute = (if raw.isAbsolute then raw else project.resolve(raw).nn).normalize.nn
    PlatformPath.portable(project.relativize(absolute).nn).stripSuffix("/")

  private def covers(prefix: String, path: String): Boolean =
    prefix.isEmpty || Platform.samePathName(prefix, path) ||
      (path.length > prefix.length && path(prefix.length) == '/' &&
        Platform.samePathName(prefix, path.take(prefix.length)))

object Checkpoints:
  /** Recorded turns kept in a project's store between sessions. */
  val KeptTurns: Int = 50

  /** A turn's changes still to revert: each path's state before the agent's first
    * change in the turn and after its last one. `baseline` is the first snapshot. */
  final case class Turn(pending: mutable.Map[String, (Option[Entry], Option[Entry])], baseline: String)

  /** The store of the project at `project`, one per canonical project path. */
  def digest(project: Path): String =
    HexFormat.of().nn.formatHex(MessageDigest.getInstance("SHA-256").nn.digest(project.toString.getBytes(UTF_8)))

  /** One line per change for the terminal: the path and what happened to it. */
  def describe(change: Change): String =
    val what = (change.before, change.after) match
      case (None, _) => "new"
      case (_, None) => "deleted"
      case (Some(a), Some(b)) if a.oid == b.oid => "mode changed"
      case _ => change.lines.fold("binary")((added, deleted) => s"+$added -$deleted")
    s"${change.path} ($what)"
