package atc

import atc.agent.{Agent, ScalaToolRunner, SessionSnapshot, SessionStore}
import atc.perms.Mode
import atc.platform.PlatformPath

import java.nio.file.{Files, Path}

/** The conversation and its REPL: starting over, compacting, saving and
  * resuming, `/run` and `/mode`, which needs a new REPL. */
final class SessionCommands(app: App):
  import app.{agent, cwd, host, policy, predictor, sandbox, tui}

  /** Where the conversation is kept between runs in this directory. */
  private lazy val autoSaveFile = SessionStore.autoSavePath(PlatformPath.userHome, cwd)

  /** Clear conversation, task state, output history and grants while retaining configured models and mode. */
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

  /** `/new`. */
  def newSession(): Unit =
    if startOver() then tui.success("new session: conversation, task notes and session grants cleared")

  /** `/reset`. */
  def reset(): Unit =
    if restartRepl("you asked for /reset") then tui.success("REPL cleared; starts with the next tool call")

  /** `/clear`. */
  def clear(): Unit =
    agent.clear()
    predictor.invalidate()
    tui.success("conversation cleared")

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
    catch case _: atc.llm.CancelledException => tui.info("Compaction cancelled; history unchanged.")
    finally
      tui.endTurn()
      predictor.start()

  /** `/mode`: cycle (no argument) or set the sandbox mode; a new REPL is
    * started with only that mode's capabilities (definitions are gone, the
    * conversation stays). */
  def switchMode(arg: String): Unit =
    val target =
      if arg.isEmpty then Some(policy.mode.next)
      else
        try Some(Mode.parse(arg))
        catch
          case e: IllegalArgumentException =>
            tui.error(e.getMessage)
            None
    target.foreach { m =>
      if m == policy.mode then tui.info(s"mode: ${m.describe}")
      else
        val previous = policy.mode
        policy.mode = m
        if restartRepl(s"the sandbox mode changed to ${m.label}") then
          app.updateStatus()
          tui.success(s"mode -> ${m.describe} (fresh REPL)")
        else policy.mode = previous
    }

  /** `/run`: the user runs Scala in the sandbox themselves, against the same
    * API, givens and permissions as the agent, shown as a code block like an
    * agent tool call and with the same keys (Ctrl-C interrupts, Ctrl-O
    * expands). The code is on the line (Enter continues it while brackets
    * are open, see `Continuation`; a pasted block keeps its newlines) or,
    * with none, typed as a block that an empty line submits. The REPL is
    * shared, so the agent is told what was run and what came of it on its
    * next turn. */
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

  /** `/save`. */
  def save(arg: String): Unit =
    val path = if arg.isEmpty then cwd.resolve(s".atc/sessions/session-${System.currentTimeMillis()}.json").nn
    else sessionPath(arg)
    try
      SessionStore.write(path, agent.snapshot)
      tui.success(s"Saved conversation to ${App.pretty(path)}")
    catch
      case _: java.nio.file.FileAlreadyExistsException =>
        tui.error(s"Save file already exists: ${App.pretty(path)}. Choose another filename.")

  /** `/resume`. */
  def resume(arg: String): Unit =
    val path = if arg.isEmpty then autoSaveFile else sessionPath(arg)
    if arg.isEmpty && !Files.exists(path) then tui.info("No saved session for this directory.")
    else restore(SessionStore.read(path))

  private def sessionPath(value: String): Path =
    val path = java.nio.file.Paths.get(PlatformPath.native(PlatformPath.expandHome(value))).nn
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
      case scala.util.control.NonFatal(error) =>
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
        case scala.util.control.NonFatal(error) =>
          tui.error(s"Could not save the session: ${Debug.describe(error)}")
          Debug.trace(error)

  private def restore(saved: SessionSnapshot): Unit =
    if startOver() then
      host.restoreTaskState(saved.task, saved.todos)
      agent.restore(saved)
      if saved.model != agent.model.ref then
        tui.info(s"Using ${agent.model.ref}; the saved session used ${saved.model}.")
      tui.success(
        s"Resumed ${saved.history.size} messages with fresh permissions and REPL state. No tool calls were replayed."
      )
