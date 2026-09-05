package atc.host

import atc.lib.{Process, ProcessResult}
import atc.perms.{Policy, ScopeId}

/** A spawned-process handle with a session ID, output callbacks and permission
  * scope. Operations require the originating scope to remain open. */
final class ProcessImpl(
  val id: Int,
  private[atc] val managed: Processes.ManagedProcess,
  output: HostOutput,
  val scope: ScopeId,
  policy: Policy,
) extends Process:
  private def open(): Unit = policy.requireScopeOpen(scope)
  def commandLine: String = managed.line
  def isAlive: Boolean =
    open()
    managed.isAlive
  def exitCode: Option[Int] =
    open()
    managed.exitCode
  def send(text: String): Unit =
    open()
    managed.send(text)
    output.processInput(id, text)
  def sendLine(line: String): Unit = send(line + "\n")
  def closeStdin(): Unit =
    open()
    managed.closeStdin()
  def read(): String =
    open()
    managed.read()
  def readErr(): String =
    open()
    managed.readErr()
  def readUntil(regex: String, timeoutMs: Long): String =
    open()
    if timeoutMs < 0 then throw IllegalArgumentException(s"readUntil: timeoutMs must not be negative (got $timeoutMs)")
    managed.readUntil(regex, timeoutMs)
  def waitFor(timeoutMs: Long): Option[ProcessResult] =
    open()
    if timeoutMs < 0 then throw IllegalArgumentException(s"waitFor: timeoutMs must not be negative (got $timeoutMs)")
    if managed.awaitExit(timeoutMs) then Some(managed.result()) else None
  def kill(): Unit =
    open()
    managed.kill()
  override def toString: String =
    // Bypass the scope check so the REPL can still render a handle after its block closes.
    s"Process(p$id, \"${managed.line}\", ${managed.exitCode.fold("running")(c => s"exited $c")})"
