package atc.agent

import atc.llm.{Completion, CompletionStop, Msg, ToolCall}

/** Pure interpretation of one provider completion. Provider adapters normalize
  * their wire-specific stop reasons into [[CompletionStop]]; this policy turns
  * that neutral value into the next agent-loop step and a safe history message. */
private[atc] object CompletionPolicy:
  enum Next:
    case RunTools(calls: List[ToolCall])
    /** Resume the model; an output-limit stop first needs a user-role bridge, this `continuation`. */
    case Resume(continuation: Option[String])
    case Finish
    case Blocked

  final case class Decision(message: Msg.Assistant, next: Next, warnings: List[String])

  def apply(raw: Completion): Decision =
    val resumable = raw.stop match
      case CompletionStop.Resume | CompletionStop.Truncated | CompletionStop.Incomplete => true
      case CompletionStop.Complete | CompletionStop.Blocked => false
    val blocked = raw.stop == CompletionStop.Blocked
    // Calls accompanying a partial or blocked response may themselves be
    // partial or contradict the provider's safety decision.
    val unsafeCalls = raw.toolCalls.nonEmpty && (resumable || blocked)
    val emptyTerminal = raw.text.trim.isEmpty && raw.toolCalls.isEmpty && !resumable

    // A resumed model must learn that its calls were dropped, or it only repeats them.
    val text =
      if raw.stop == CompletionStop.Incomplete && raw.text.trim.isEmpty then AgentMessages.incompleteStream
      else if unsafeCalls && raw.text.trim.isEmpty then AgentMessages.unsafeResponse(raw.stopReason)
      else if unsafeCalls && resumable then s"${raw.text}\n\n${AgentMessages.unsafeResponse(raw.stopReason)}"
      else if emptyTerminal then AgentMessages.emptyResponse(raw.stopReason)
      else raw.text
    val calls = if unsafeCalls then Nil else raw.toolCalls
    val native = if unsafeCalls || emptyTerminal || raw.stop == CompletionStop.Incomplete then None else raw.native

    val next = raw.stop match
      case CompletionStop.Blocked => Next.Blocked
      case _ if calls.nonEmpty => Next.RunTools(calls)
      case CompletionStop.Resume => Next.Resume(None)
      case CompletionStop.Truncated if raw.toolCalls.nonEmpty => Next.Resume(Some(AgentMessages.truncatedToolCall))
      case CompletionStop.Truncated | CompletionStop.Incomplete =>
        Next.Resume(Some(AgentMessages.truncationContinuation))
      case CompletionStop.Complete => Next.Finish

    val warnings = List(
      Option.when(unsafeCalls)(AgentMessages.unsafeToolCallsWarning(raw.toolCalls.size, raw.stopReason)),
      Option.when(raw.stop == CompletionStop.Incomplete)(AgentMessages.incompleteStreamWarning),
      Option.when(blocked)(AgentMessages.blockedResponseWarning(raw.stopReason)),
      Option.when(emptyTerminal && raw.stop == CompletionStop.Complete)(AgentMessages.emptyResponseWarning),
    ).flatten

    Decision(Msg.Assistant(text, calls, native), next, warnings)
