package atc.host

import atc.lib.{Process, ProcessResult}
import atc.perms.{Policy, ScopeId}

/** The agent's handle on a spawned process: the [[Processes.ManagedProcess]]
  * plus the session-level id and the hooks that show the user what the agent
  * sends it.
  *
  * The handle carries the id of the scope its `spawn` ran in and refuses every
  * operation once that scope has closed — the same "escaped its block" refusal
  * a leaked capability gets (`Policy.requireScopeOpen`). Without it, a process
  * spawned inside a `requestExec` granted *once* could still be driven
  * afterwards through `runningProcesses`, a session-long backdoor around the
  * one-time grant. */
final class ProcessImpl(
  val id: Int,
  private[atc] val managed: Processes.ManagedProcess,
  output: HostOutput,
  val scope: ScopeId,
  policy: Policy,
) extends Process:
  private def open(): Unit = policy.requireScopeOpen(scope)
  def commandLine: String = managed.line
  def isAlive: Boolean = { open(); managed.isAlive }
  def exitCode: Option[Int] = { open(); managed.exitCode }
  def send(text: String): Unit =
    open()
    managed.send(text)
    output.processInput(id, text)
  def sendLine(line: String): Unit = send(line + "\n")
  def closeStdin(): Unit = { open(); managed.closeStdin() }
  def read(): String = { open(); managed.read() }
  def readErr(): String = { open(); managed.readErr() }
  def readUntil(regex: String, timeoutMs: Long): String = { open(); managed.readUntil(regex, timeoutMs) }
  def waitFor(timeoutMs: Long): Option[ProcessResult] =
    open()
    if managed.awaitExit(timeoutMs) then Some(managed.result()) else None
  def kill(): Unit = { open(); managed.kill() }
  override def toString: String =
    // No scope check (and `managed` directly): a toString that throws would break
    // the REPL echo of a handle whose block has already closed.
    s"Process(p$id, \"${managed.line}\", ${managed.exitCode.fold("running")(c => s"exited $c")})"
