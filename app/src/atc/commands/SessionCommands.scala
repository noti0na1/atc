package atc.commands

import atc.{App, Debug}
import atc.checkpoint.{Change, Checkpoints, Isolation, RevertReport}
import atc.agent.{Agent, ScalaToolRunner, SessionSnapshot, SessionStore}
import atc.llm.CancelledException
import atc.perms.{Access, Mode}
import atc.platform.PlatformPath

import java.nio.file.{FileAlreadyExistsException, Files, Path, Paths}
import scala.util.control.NonFatal

/** The conversation and its REPL: starting over, compacting, saving and
  * resuming, `/run` and `/mode`, which needs a new REPL. */
final class SessionCommands(app: App):
  import app.{agent, cwd, host, policy, predictor, sandbox, tui}

  /** Where the conversation is kept between runs in this directory. */
  private lazy val autoSaveFile = SessionStore.autoSavePath(PlatformPath.userHome, app.sessionRoot)

  /** Clear the conversation, task state, output history and session grants; keep the models and mode. */
  private def startOver(): Boolean =
    predictor.invalidate()
    val replaced = sandbox.replace("could not clear the sandbox")
    if replaced then
      agent.clear()
      host.clearTodos()
      tui.clearOutputHistory()
      policy.resetSession()
    replaced

  /** Replace the REPL; the conversation is kept. `reason` tells the agent why
    * its REPL definitions are gone. */
  private def restartRepl(reason: String): Boolean =
    val ok = sandbox.replace("could not restart the sandbox")
    if ok then
      agent.noteSandboxRestarted(reason)
      // The restart notice is pending, not in history yet; any prediction now
      // would still assume that old REPL definitions exist.
      predictor.invalidate()
    ok

  /** `/new`: also clears the window, so the new session starts on an empty screen. */
  def newSession(): Unit =
    if startOver() then
      // The next start must not offer the conversation the user just discarded.
      try Files.deleteIfExists(autoSaveFile)
      catch case NonFatal(error) => Debug.trace(error)
      tui.clearScreen()
      tui.success("new session: conversation, task notes and session grants cleared")

  /** `/reset`. */
  def reset(): Unit =
    if restartRepl("you asked for /reset") then tui.success("REPL cleared; starts with the next tool call")

  /** `/compact`. */
  def compact(instructions: String): Unit =
    predictor.invalidate()
    tui.beginTurn()
    try
      agent.compact(instructions, () => tui.isInterrupted) match
        case Agent.CompactOutcome.Compacted => tui.success("conversation context compacted")
        case Agent.CompactOutcome.NothingToCompact =>
          tui.info("Nothing to compact: the conversation fits the retention budget; history unchanged.")
        case Agent.CompactOutcome.SummaryNotSmaller =>
          tui.warn("The summary was no smaller than the history it would replace; history unchanged.")
    catch case _: CancelledException => tui.info("Compaction cancelled; history unchanged.")
    finally
      tui.endTurn()
      predictor.start()

  /** `/undo`: revert the file changes of the last recorded turn, or only the given
    * paths of it. The agent hears what was reverted, since its view of those files
    * is now out of date. */
  def undo(arg: String): Unit =
    app.checkpoints match
      case None => tui.info("Checkpoints are off: the \"checkpoints\" setting is false.")
      case Some(checkpoints) =>
        predictor.invalidate()
        checkpoints.undo(arg.split("\\s+").toList.filter(_.nonEmpty)) match
          case Left(message) => tui.info(message)
          case Right(report) =>
            // What the user reads, and what the agent is told, about each group of paths.
            val groups = List(
              ("Restored", "Restored", report.restored),
              ("Deleted", "Deleted", report.deleted),
              ("Reverted, keeping your later edits in", "Reverted, keeping the user's later edits in", report.merged),
            ).filter(_._3.nonEmpty)
            groups.foreach((label, _, paths) => tui.success(s"$label: ${paths.mkString(", ")}"))
            report.conflicts.foreach((path, reason) => tui.warn(s"Left unchanged: $path ($reason)"))
            if groups.isEmpty && report.conflicts.isEmpty then tui.info("The files already match their earlier state.")
            else
              val outcome =
                (groups.map((_, label, paths) => s"$label: ${paths.mkString(", ")}.") ++
                  report.conflicts.map((path, reason) => s"Left unchanged: $path ($reason).")).mkString(" ")
              agent.noteFilesReverted(outcome)

  /** `/mode`: choose the sandbox mode from a menu (no argument), cycle it (`next`, which
    * Shift-Tab sends) or set the named one. A new REPL starts with only that mode's
    * capabilities; its definitions are gone, the conversation stays. */
  def switchMode(arg: String): Unit =
    val target = arg.trim match
      case "" => chooseMode()
      case "next" => Some(policy.mode.next)
      case named =>
        try Some(Mode.parse(named))
        catch
          case e: IllegalArgumentException =>
            tui.error(Debug.message(e))
            None
    target.foreach: m =>
      if m == policy.mode then tui.info(s"mode: ${m.describe}")
      else if m == Mode.Isolate then
        // The session moves to the project's copy; Main starts it there with this conversation.
        try throw App.Restart(app.isolatedArgs(), Some(agent.snapshot).filter(_.nonEmpty))
        catch case e: IllegalStateException => tui.error(Debug.message(e))
      else if app.isolatedFrom.isDefined then leaveIsolation(m)
      else
        val previous = policy.mode
        policy.mode = m
        if restartRepl(s"the sandbox mode changed to ${m.label}") then
          app.models.useMode(m)
          app.updateStatus()
          tui.success(s"mode -> ${m.describe} (fresh REPL)")
        else policy.mode = previous

  /** The mode the user picks from a menu opening on the current one; `None` when they leave it. */
  private def chooseMode(): Option[Mode] =
    val modes = Mode.values.toList
    val width = modes.map(_.label.length).max
    val rows = modes.map(m => s"${m.label.padTo(width, ' ')}  ${m.description}")
    val chosen =
      tui.choose("Choose the sandbox mode", rows, modes.indexOf(policy.mode)).map(row => modes(rows.indexOf(row)))
    if chosen.isEmpty then
      tui.info(s"mode: ${policy.mode.describe}" +
        (if tui.menusAvailable then "" else s" (/mode ${modes.map(_.label).mkString("|")})"))
    chosen

  /** `/auto`: switch (no argument), or turn on or off, the rejection of every
    * permission request without asking. The REPL and its capabilities stay. */
  def switchAuto(arg: String): Unit =
    val target = arg.trim.toLowerCase(java.util.Locale.ROOT) match
      case "" => Some(!policy.auto)
      case "on" => Some(true)
      case "off" => Some(false)
      case _ =>
        tui.error("Usage: /auto [on|off]")
        None
    target.foreach: on =>
      if on != policy.auto then
        predictor.invalidate()
        policy.auto = on
        agent.noteAutoSwitched(on)
        app.updateStatus()
      tui.success(
        if on then "auto on: permission requests are rejected without asking"
        else "auto off: permission requests are asked again"
      )

  /** Leave isolate mode for `mode`, first offering to apply or drop what the copy changed. */
  private def leaveIsolation(mode: Mode): Unit =
    val project = app.isolatedFrom.get
    val waiting = app.isolation.fold(Nil)(listChanges)
    val (applyIt, keep, drop) = (s"Apply them and switch to ${mode.label}", "Keep them in the copy", "Discard them")
    val choice =
      if waiting.isEmpty then Some(keep)
      else
        tui.choose(
          s"The copy differs from the project in ${if waiting.size == 1 then "1 file" else s"${waiting.size} files"}.",
          List(applyIt, keep, drop, "Stay here")
        )
    val go = choice match
      case Some(`applyIt`) => app.isolation.foreach(applyNow); true
      case Some(`drop`) => discardIsolated(); true
      case Some(`keep`) => true
      case _ => false
    if go then throw App.Restart(app.argsLeaving(project, mode), Some(agent.snapshot).filter(_.nonEmpty))

  /** `/apply`: show what the copy would write into the project, and write it once the user agrees. */
  def applyIsolated(): Unit =
    app.isolation match
      case None => tui.info("/apply works in isolate mode (/mode isolate).")
      case Some(isolation) =>
        val waiting = listChanges(isolation)
        if waiting.isEmpty then tui.info("The project already holds the copy's changes.")
        else if tui.confirm(s"Write these ${waiting.size} changes into the project?") then applyNow(isolation)
        else tui.info("Nothing applied; the changes stay in the copy.")

  /** Print the changes the project does not hold yet, marking those that meet the user's own
    * edits and those the project config keeps read-only, and return them. */
  private def listChanges(isolation: Isolation): List[(Change, Boolean)] =
    val waiting = isolation.preview
    val copy = app.isolatedRoots.map(_._2)
    for (change, changedThere) <- waiting do
      val notes = List(
        Option.when(changedThere)("you changed it too: merged if clean"),
        copy.filter(root => policy.ceiling(root.resolve(PlatformPath.native(change.path)).nn) != Access.Write)
          .map(_ => "read-only in your config"),
      ).flatten
      tui.println(s"  ${Checkpoints.describe(change)}${notes.map(n => s" [$n]").mkString}")
    waiting

  private def applyNow(isolation: Isolation): Unit =
    report(
      isolation.apply(),
      List("Applied", "Deleted", "Merged with your edits in"),
      "The copy's changes are already in the project.",
      agent.noteIsolationApplied
    )

  /** `/discard`: put the copy back to the project's state. */
  def discardIsolated(): Unit =
    app.isolation match
      case None => tui.info("/discard works in isolate mode (/mode isolate).")
      case Some(isolation) =>
        report(
          isolation.discard(),
          List("Restored", "Deleted", "Merged in"),
          "The copy has no changes.",
          agent.noteIsolationDiscarded
        )

  /** Show what an apply or discard did, and tell the agent. */
  private def report(done: RevertReport, labels: List[String], nothing: String, note: String => Unit): Unit =
    val groups = labels.zip(List(done.restored, done.deleted, done.merged)).filter(_._2.nonEmpty)
    groups.foreach((label, paths) => tui.success(s"$label: ${paths.mkString(", ")}"))
    done.conflicts.foreach((path, reason) => tui.warn(s"Left unchanged: $path ($reason)"))
    if groups.isEmpty && done.conflicts.isEmpty then tui.info(nothing)
    else
      note(
        (groups.map((label, paths) => s"$label: ${paths.mkString(", ")}.") ++
          done.conflicts.map((path, reason) => s"Left unchanged: $path ($reason).")).mkString(" ")
      )

  /** Continue a conversation carried over from the session this one replaced. */
  def resumeFrom(saved: SessionSnapshot): Unit = restore(saved, withMode = false)

  /** `/run`: the user runs Scala in the sandbox with the same API, givens and
    * permissions as the agent. It is shown as a code block like an agent tool
    * call, with the same keys (Ctrl-C interrupts, Ctrl-O expands). The code is
    * the rest of the line (Enter continues it while brackets are open, see
    * `Continuation`; a pasted block keeps its newlines), or else a block typed
    * next that an empty line submits. The REPL is shared, so the agent is told
    * on its next turn what was run and what came of it. */
  def run(arg: String): Unit =
    // `/run` mutates the persistent REPL and queues a note that is not part of
    // history until the next real user turn. A prediction made before it is stale.
    predictor.invalidate()
    val code = if arg.nonEmpty then arg else readCode()
    if code.trim.nonEmpty then
      tui.beginTurn()
      try
        // The session first: starting it reports progress of its own, and when it fails the
        // code block would otherwise stay open without its closing verdict line.
        val s = sandbox.ensure()
        tui.toolStart(code, "/run")
        val (result, decisions) = ScalaToolRunner.evaluate(s, policy, tui, code)
        agent.noteUserRan(code, result, decisions)
      catch
        case e: Exception =>
          tui.error(Debug.describe(e))
          Debug.trace(e)
      finally tui.endTurn()

  /** The block of code typed after a bare `/run`; empty when cancelled (Ctrl-C, Ctrl-D). */
  private def readCode(): String =
    tui.info("Scala code; Enter on an empty line runs it, Ctrl-C cancels")
    tui.suggest(None) // no ghost text while typing code
    tui.readBlock(app.prompt).getOrElse("")

  /** `/save`. A bare `/save` writes beside the automatic saves, outside the project, where a
    * transcript cannot be committed with the repository or follow a symlink the repository planted. */
  def save(arg: String): Unit =
    val path =
      if arg.isEmpty then autoSaveFile.resolveSibling(s"session-${System.currentTimeMillis()}.json").nn
      else sessionPath(arg)
    try
      SessionStore.write(path, agent.snapshot)
      tui.success(s"Saved conversation to ${PlatformPath.display(path)}")
    catch
      case _: FileAlreadyExistsException =>
        tui.error(s"Save file already exists: ${PlatformPath.display(path)}. Choose another filename.")

  /** `/resume`. */
  def resume(arg: String): Unit =
    val path = if arg.isEmpty then autoSaveFile else sessionPath(arg)
    if arg.isEmpty && !Files.exists(path) then tui.info("No saved session for this directory.")
    else restore(SessionStore.read(path))

  private def sessionPath(value: String): Path =
    val path = Paths.get(PlatformPath.native(PlatformPath.expandHome(value))).nn
    (if path.isAbsolute then path else cwd.resolve(path).nn).normalize.nn

  /** At the start: offer to continue the conversation saved in this directory. */
  def offerResume(): Unit =
    try
      if Files.exists(autoSaveFile) then
        val saved = SessionStore.read(autoSaveFile)
        if saved.nonEmpty then
          saved.userRequests.lastOption.foreach(text => tui.preview(s"Last request: $text"))
          tui.choose(
            "Continue your last session in this directory?",
            List("Resume last session", "Start a new session")
          ) match
            case Some("Resume last session") => restore(saved)
            case _ => ()
    catch
      case NonFatal(error) =>
        tui.warn(s"Could not load the previous session: ${Debug.describe(error)}")
        Debug.trace(error)

  /** At the end: keep the conversation for [[offerResume]]. */
  def saveOnExit(): Unit =
    val saved = agent.snapshot
    if saved.nonEmpty then
      try
        SessionStore.checkpoint(autoSaveFile, saved)
        tui.info("Session saved. Start ATC in this directory to resume.")
      catch
        case NonFatal(error) =>
          tui.error(s"Could not save the session: ${Debug.describe(error)}")
          Debug.trace(error)

  /** Continue `saved`, in the mode it was in when `withMode` (unless the command line named a
    * mode). A mode the REPL takes in place is set before the fresh REPL starts; entering or
    * leaving isolate mode moves the session once the conversation is restored. */
  private def restore(saved: SessionSnapshot, withMode: Boolean = true): Unit =
    val savedMode = Option.when(withMode && app.cliMode.isEmpty)(saved.mode).flatten
      .flatMap(label => scala.util.Try(Mode.parse(label)).toOption).filter(_ != policy.mode)
    val inPlace = savedMode.filter(m => m != Mode.Isolate && app.isolatedFrom.isEmpty)
    val previous = policy.mode
    inPlace.foreach(m => policy.mode = m)
    if startOver() then
      inPlace.foreach: m =>
        app.models.useMode(m)
        app.updateStatus()
      host.restoreTaskState(saved.task, saved.todos)
      agent.restore(saved)
      if saved.model != agent.model.ref then
        tui.info(s"Using ${agent.model.ref}; the saved session used ${saved.model}.")
      tui.success(
        s"Resumed ${saved.history.size} messages with fresh permissions and REPL state. No tool calls were replayed."
      )
      inPlace.foreach(m => tui.info(s"mode -> ${m.describe}, as in the saved session"))
      savedMode.filterNot(inPlace.contains).foreach(m => switchMode(m.label))
    else policy.mode = previous
