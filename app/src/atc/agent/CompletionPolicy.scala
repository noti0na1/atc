package atc.agent

import atc.llm.{Completion, CompletionStop, Msg, ToolCall}

/** Pure interpretation of one provider completion. Provider adapters normalize
  * their wire-specific stop reasons into [[CompletionStop]]; this policy turns
  * that neutral value into the next agent-loop step and a safe history message. */
private[atc] object CompletionPolicy:
  enum Next:
    case RunTools(calls: List[ToolCall])
    /** Resume the model; an output-limit stop first needs a user-role bridge. */
    case Resume(needsContinuation: Boolean)
    case Finish
    case Blocked

  final case class Decision(message: Msg.Assistant, next: Next, warnings: List[String])

  def apply(raw: Completion): Decision =
    val resumable = raw.stop == CompletionStop.Resume || raw.stop == CompletionStop.Truncated
    val blocked = raw.stop == CompletionStop.Blocked
    // Calls accompanying a partial or blocked response may themselves be
    // partial or contradict the provider's safety decision.
    val unsafeCalls = raw.toolCalls.nonEmpty && (resumable || blocked)
    val emptyTerminal = raw.text.trim.isEmpty && raw.toolCalls.isEmpty && !resumable

    val text =
      if unsafeCalls && raw.text.trim.isEmpty then
        AgentMessages.unsafeResponse(raw.stopReason)
      else if emptyTerminal then AgentMessages.emptyResponse(raw.stopReason)
      else raw.text
    val calls = if unsafeCalls then Nil else raw.toolCalls
    val native = if unsafeCalls || emptyTerminal then None else raw.native

    val next = raw.stop match
      case CompletionStop.Blocked => Next.Blocked
      case _ if calls.nonEmpty => Next.RunTools(calls)
      case CompletionStop.Resume => Next.Resume(needsContinuation = false)
      case CompletionStop.Truncated => Next.Resume(needsContinuation = true)
      case CompletionStop.Complete => Next.Finish

    val warnings =
      Option.when(unsafeCalls)(
        AgentMessages.unsafeToolCallsWarning(raw.toolCalls.size, raw.stopReason)
      ).toList ++
        Option.when(blocked)(AgentMessages.blockedResponseWarning(raw.stopReason)) ++
        Option.when(raw.stop == CompletionStop.Complete && raw.toolCalls.isEmpty && raw.text.trim.isEmpty)(
          AgentMessages.emptyResponseWarning
        )

    Decision(Msg.Assistant(text, calls, native), next, warnings)
