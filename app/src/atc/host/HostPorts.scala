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

/** One-shot LLM completions used by the library's `chat`. */
trait HostLlm:
  def chat(message: String): String
  def chatClassified(message: String): String

/** Interaction with the human on behalf of the agent. */
trait HostUi:
  /** `None` if the user declines/cancels. */
  def askUser(question: String, options: List[String], multiple: Boolean): Option[String]
  def showTodos(items: List[Todo]): Unit
