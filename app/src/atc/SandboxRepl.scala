package atc

import atc.config.Config
import atc.confine.CommandSandbox
import atc.evaluator.EvaluatorSession
import atc.host.Host
import atc.llm.CancelledException
import atc.perms.Policy
import atc.sandbox.{ReplSession, SandboxConfig, SandboxSession}
import atc.ui.Tui

import java.util.concurrent.{ExecutionException, FutureTask}

/** The sandbox's REPL session. It starts on a background thread so that
  * compiling its preamble (about two seconds) overlaps the user's typing and
  * the model's answer instead of the first tool call, and is replaced when the
  * mode changes or the conversation starts over. Where the OS sandbox can
  * confine it, the REPL runs in a separate evaluator process
  * ([[EvaluatorSession]]); otherwise, or with `osSandbox` off, in this JVM. The
  * policy, the host and the OS sandbox are passed by name: they are built after
  * this, and the host's ports use it. */
final class SandboxRepl(config: Config, policy: => Policy, host: => Host, tui: Tui, osSandbox: => CommandSandbox):
  @volatile private var live: Option[SandboxSession] = None

  /** A session starting in the background, adopted by [[ensure]] or dropped by
    * [[discardWarming]]. Touched by the main thread only. */
  private var warming: Option[FutureTask[SandboxSession]] = None

  /** The session in use, if one has started. */
  def session: Option[SandboxSession] = live

  /** Exclude user input and operations with their own timeout from the snippet clock. */
  def withClockPaused[T](body: => T): T =
    live.foreach(_.clock.pause())
    try body
    finally live.foreach(_.clock.resume())

  def interrupt(): Unit = live.foreach(_.interrupt())

  private def start(): SandboxSession =
    val sandboxConfig = SandboxConfig(config.safeMode, policy.mode, config.executionTimeoutMs)
    osSandbox match
      case refusing: CommandSandbox.Refusing =>
        throw SecurityException(s"The REPL needs the OS sandbox (${refusing.describe}).")
      case sandbox if sandbox.confined => EvaluatorSession.start(sandboxConfig, host, sandbox)
      case _ => ReplSession(sandboxConfig, host).init()

  /** Start a session in the background; nothing happens when one exists or is already starting. */
  def warm(): Unit = if live.isEmpty && warming.isEmpty then
    val task = FutureTask[SandboxSession](() => start())
    daemon("atc-sandbox-warmup")(task)
    warming = Some(task)

  /** Drop a session still starting in the background: a daemon thread closes it once it
    * is ready, so a mode switch does not wait for a compiler it no longer needs. */
  private def discardWarming(): Unit =
    warming.foreach: task =>
      warming = None
      daemon("atc-sandbox-discard"): () =>
        try task.get().close()
        catch case _: Exception => ()

  /** The live session: the one warming in the background once it is ready (the status
    * line says so only if the wait is real), else one started here and now. A session
    * whose evaluator process stopped is replaced; its result already told the model that
    * its definitions are gone. */
  def ensure(): SandboxSession =
    if live.exists(!_.alive) then
      live.foreach(_.close())
      live = None
    live.getOrElse(started())

  private def started(): SandboxSession =
    val created = warming match
      case Some(task) =>
        warming = None
        if !task.isDone then tui.status(s"starting sandbox (${policy.mode.label} mode)")
        try task.get()
        catch case e: ExecutionException => throw e.getCause.nn
      case None =>
        tui.status(s"starting sandbox (${policy.mode.label} mode)")
        start()
    if tui.isInterrupted then
      created.close()
      throw CancelledException()
    live = Some(created)
    created

  /** Discard the REPL (live or still warming) and its processes, and start warming the
    * next one, so a mode switch or `/new` costs the first tool call nothing either.
    * `false`, with `failure` reported, when that failed. */
  def replace(failure: String): Boolean =
    try
      host.killProcesses()
      close()
      warm()
      true
    catch
      case e: Exception =>
        tui.error(s"$failure: ${Debug.message(e)}")
        Debug.trace(e)
        false

  def close(): Unit =
    discardWarming()
    live.foreach(_.close())
    live = None

  private def daemon(name: String)(work: Runnable): Unit =
    val thread = Thread(work, name)
    thread.setDaemon(true)
    thread.start()
