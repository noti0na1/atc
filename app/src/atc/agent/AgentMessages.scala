package atc.agent

/** Text exchanged between the agent loop, the model and the UI.
  *
  * Keeping these protocol-like messages here makes the loop about state
  * transitions rather than string construction. Newlines are deliberately LF:
  * model history has one stable representation on every host platform.
  */
object AgentMessages:
  val interrupted: String = "[interrupted by user]"
  val interruptedWarning: String = "interrupted"
  val emptyResponseWarning: String = "The model returned no response."
  val resumingStatus: String = "resuming"
  val toolBudgetLoopWarning: String =
    "model kept requesting tools after exhausting the tool budget; stopping this turn"
  val cancelledBeforeExecution: String = "Cancelled by the user before execution."
  val missingCodeArgument: String = "Missing 'code' argument."

  def thinkingStatus(modelAlias: String): String = s"$modelAlias is thinking"

  val truncationContinuation: String =
    "[continuation request] Continue exactly where the previous response was truncated. " +
      "Do not repeat completed work; finish the user's original request."

  def sandboxRestarted(reason: String): String =
    s"[sandbox notice] The Scala REPL was restarted ($reason). Every `val`, `def` and `import` " +
      "you defined earlier is gone, so re-create anything you still need. The conversation itself is unchanged."

  /** Report a user's `/run` snippet to the model.
    *
    * Markdown permits a backtick fence to be longer than three characters. A
    * fence longer than every backtick run in the snippet cannot be closed by
    * snippet text that happens to contain ``` or a longer fence.
    */
  def userRan(code: String, renderedResult: String): String =
    val fence = "`" * (3 max (longestBacktickRun(code) + 1))
    s"[user ran code] The user ran this in the sandbox REPL themselves (its definitions persist for you too):\n" +
      s"${fence}scala\n$code\n$fence\nResult:\n$renderedResult"

  /** Prepend queued notes to the user's input without creating adjacent user
    * messages in provider history. */
  def userMessage(notes: List[String], input: String): String =
    (notes :+ input).mkString("\n\n")

  def turnFailed(error: Throwable): String =
    val detail = Option(error.getMessage).getOrElse(error.toString)
    s"[turn failed: $detail]"

  def emptyResponse(stopReason: String): String =
    s"[model returned no response; stop_reason=$stopReason]"

  def unsafeResponse(stopReason: String): String =
    s"[$stopReason model response; tool calls were not executed]"

  def unsafeToolCallsWarning(count: Int, stopReason: String): String =
    s"ignored $count tool call(s) from a $stopReason response"

  def blockedResponseWarning(stopReason: String): String =
    s"The model blocked this request (stop_reason=$stopReason)."

  def resumeExhaustedWarning(modelAlias: String, maxResumes: Int): String =
    s"$modelAlias remained unfinished after $maxResumes resume attempts"

  def toolBudgetExhausted(maxToolCalls: Int): String =
    s"Tool budget of $maxToolCalls calls per turn exhausted; answer the user now."

  def unknownTool(name: String, available: String): String =
    s"Unknown tool '$name'. Only $available is available; everything else is a Scala function."

  def contextCutNotice(dropped: Int): String =
    s"[context notice] The $dropped oldest messages of this conversation were dropped to fit your context window; " +
      "if you need something from them, ask the user or read it again."

  def contextDroppedWarning(modelAlias: String, window: Int, dropped: Int): String =
    s"context window of $modelAlias ($window tokens): the oldest $dropped messages were dropped from what the model sees"

  enum ContextOverflowCause:
    case FixedPrompt, RetainedExchange

  def contextOverflowWarning(
    modelAlias: String,
    window: Int,
    cause: ContextOverflowCause,
    estimatedInput: Long,
    availableInput: Long,
    reserve: Long,
    maxOutputTokens: Option[Int],
  ): String =
    val causeText = cause match
      case ContextOverflowCause.FixedPrompt => "the system prompt and tool schema alone"
      case ContextOverflowCause.RetainedExchange => "the latest retained exchange (which cannot be dropped)"
    val reserveWhy = maxOutputTokens match
      case Some(n) if n.toLong >= window.toLong / 8 => s"$reserve tokens reserved for configured maxTokens=$n"
      case _ => s"$reserve tokens reserved for the answer and estimation slack"
    s"context window of $modelAlias ($window tokens): $causeText needs an estimated $estimatedInput input tokens, " +
      s"but only ${availableInput.max(0L)} remain with $reserveWhy; the provider may reject this request. " +
      "Shorten the request or configure a larger contextWindow/maxTokens combination."

  private def longestBacktickRun(text: String): Int =
    var longest = 0
    var current = 0
    text.foreach { char =>
      if char == '`' then
        current += 1
        longest = longest.max(current)
      else current = 0
    }
    longest
