package atc.host

import atc.lib.{Process, ProcessResult}

/** The agent's handle on a spawned process: the [[Processes.ManagedProcess]]
  * plus the session-level id and the hooks that show the user what the agent
  * sends it. */
final class ProcessImpl(val id: Int, managed: Processes.ManagedProcess, output: HostOutput) extends Process:
  def commandLine: String = managed.line
  def isAlive: Boolean = managed.isAlive
  def exitCode: Option[Int] = managed.exitCode
  def send(text: String): Unit =
    managed.send(text)
    output.processInput(id, text)
  def sendLine(line: String): Unit = send(line + "\n")
  def closeStdin(): Unit = managed.closeStdin()
  def read(): String = managed.read()
  def readErr(): String = managed.readErr()
  def readUntil(regex: String, timeoutMs: Long): String = managed.readUntil(regex, timeoutMs)
  def waitFor(timeoutMs: Long): Option[ProcessResult] =
    if managed.awaitExit(timeoutMs) then Some(managed.result()) else None
  def kill(): Unit = managed.kill()
  override def toString: String =
    s"Process(p$id, \"${managed.line}\", ${exitCode.fold("running")(c => s"exited $c")})"
