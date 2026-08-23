package atc.host

import atc.lib.Todo

// What the host needs from the application: where agent output goes, how to
// talk to the user, and how to reach the LLMs. `App` wires these to the TUI
// and the agent; tests plug in recorders.

/** Where the agent's `println` output goes. */
trait HostOutput:
  /** One print by the agent. `agentText` is what the model sees (appended to
    * the current tool result); `userText` is what the human sees. They differ
    * only for classified values (`Classified(***)` vs. the content). */
  def print(agentText: String, userText: String): Unit
  /** A command the agent runs (`exec`) has been running for a while
    * ([[Processes.LiveAfterMs]]): from now on what it writes is shown to the
    * human as it happens, through [[commandOutput]]. Shown only: the tool
    * result does not carry it (the command's `ProcessResult` does), so a
    * double that ignores both is right. */
  def commandRunning(commandLine: String): Unit = ()
  def commandOutput(text: String): Unit = ()
  /** Runs `body`, a command the agent executes, so the host can keep the time
    * it takes out of the snippet's execution timeout (a command has its own). */
  def whileCommandRuns[T](body: => T): T = body
  /** A process the agent started with `spawn` (`id` is the `pN` the user sees):
    * it started, the agent wrote `text` to its stdin, it exited. Shown only. */
  def processStarted(id: Int, commandLine: String): Unit = ()
  def processInput(id: Int, text: String): Unit = ()
  def processExited(id: Int, exitCode: Int): Unit = ()

/** One-shot LLM completions used by normal `chat` and trusted `classifiedChat`. */
trait HostLlm:
  def chat(message: String): String
  def classifiedChat(message: String): String

/** Interaction with the human on behalf of the agent. */
trait HostUi:
  /** `None` if the user declines/cancels. */
  def askUser(question: String, options: List[String], multiple: Boolean): Option[String]
  def showTodos(items: List[Todo]): Unit
