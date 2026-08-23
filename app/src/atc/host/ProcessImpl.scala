package atc.host

import atc.lib.{Process, ProcessResult}
import atc.perms.{Policy, ScopeId}

/** The agent's handle on a spawned process: the [[Processes.ManagedProcess]]
  * plus the session-level id and the hooks that show the user what the agent
  * sends it.
  *
  * The handle records the scope in which `spawn` ran and refuses all operations
  * after that scope closes, matching the "escaped its block" treatment of a
  * leaked capability (`Policy.requireScopeOpen`). Without this check, a process
  * started by a one-time `requestExec` grant could remain controllable through
  * `runningProcesses` for the rest of the session. */
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
