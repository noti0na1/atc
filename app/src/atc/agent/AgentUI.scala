package atc.agent

import atc.sandbox.ExecutionResult

/** Events the agent and its tool adapter report to the terminal. */
trait AgentUI:
  def assistantDelta(text: String): Unit
  /** Out-of-band note during streaming ("web search"); empty = paragraph break. */
  def assistantNote(text: String): Unit
  def assistantEnd(): Unit
  /** A piece of the model's reasoning, when the provider streams it. */
  def thinkingDelta(text: String): Unit
  def toolStart(code: String): Unit
  /** `millis` is execution time excluding the time spent waiting for the user. */
  def toolEnd(result: ExecutionResult, millis: Long): Unit
  def status(text: String): Unit
  def warn(text: String): Unit
  /** The turn has used its tool budget (`used` calls; `budget` is the configured
    * allocation): may it go on for another `budget`? */
  def confirmMoreToolCalls(used: Int, budget: Int): Boolean = false
