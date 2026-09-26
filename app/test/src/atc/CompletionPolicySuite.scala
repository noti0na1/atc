package atc

import atc.agent.{AgentMessages, CompletionPolicy}
import atc.llm.*

class CompletionPolicySuite extends munit.FunSuite:
  private val usage = TokenUsage(1, 1)
  private def call(id: String = "call-1") = ToolCall(id, "run_scala", "{}")
  private def completion(
    text: String = "answer",
    calls: List[ToolCall] = Nil,
    native: Option[NativeTurn] = None,
    reason: String = "end_turn",
    stop: CompletionStop = CompletionStop.Complete,
  ): Completion = Completion(text, calls, native, usage, reason, stop)

  test("a complete tool request is preserved and selected for execution"):
    val native = NativeTurn("provider", "model", "payload")
    val calls = List(call())
    val decision = CompletionPolicy(completion(calls = calls, native = Some(native), reason = "tool_use"))
    assertEquals(decision.message, Msg.Assistant("answer", calls, Some(native)))
    assertEquals(decision.next, CompletionPolicy.Next.RunTools(calls))
    assertEquals(decision.warnings, Nil)

  test("server pauses and output truncation select distinct resume steps"):
    val paused = CompletionPolicy(completion(reason = "pause_turn", stop = CompletionStop.Resume))
    assertEquals(paused.next, CompletionPolicy.Next.Resume(None))

    val truncated = CompletionPolicy(completion(reason = "max_tokens", stop = CompletionStop.Truncated))
    assertEquals(truncated.next, CompletionPolicy.Next.Resume(Some(AgentMessages.truncationContinuation)))

  test("calls on resumable responses are stripped with native replay and warned about"):
    val native = NativeTurn("provider", "model", "payload")
    val decision = CompletionPolicy(completion(
      text = "",
      calls = List(call()),
      native = Some(native),
      reason = "max_tokens",
      stop = CompletionStop.Truncated,
    ))
    assert(decision.message.text.contains("tool calls were not executed"), decision.message.text)
    assertEquals(decision.message.toolCalls, Nil)
    assertEquals(decision.message.native, None)
    assertEquals(decision.next, CompletionPolicy.Next.Resume(Some(AgentMessages.truncatedToolCall)))
    assert(decision.warnings.exists(_.contains("ignored 1 tool call")), decision.warnings.toString)
    // with text before the cut call, the marker still says the call did not run
    val withText = CompletionPolicy(completion(
      text = "Writing the file now.",
      calls = List(call()),
      reason = "max_tokens",
      stop = CompletionStop.Truncated,
    ))
    assert(withText.message.text.startsWith("Writing the file now."), withText.message.text)
    assert(withText.message.text.contains("tool calls were not executed"), withText.message.text)

  test("a blocked response stops and never exposes accompanying calls"):
    val decision = CompletionPolicy(completion(
      text = "refused",
      calls = List(call()),
      native = Some(NativeTurn("provider", "model", "payload")),
      reason = "content_filter",
      stop = CompletionStop.Blocked,
    ))
    assertEquals(decision.message, Msg.Assistant("refused", Nil, None))
    assertEquals(decision.next, CompletionPolicy.Next.Blocked)
    assertEquals(decision.warnings.size, 2)
    assert(decision.warnings.exists(_.contains("blocked this request")), decision.warnings.toString)

  test("an incomplete stream resumes without replaying tool calls or an empty assistant message"):
    val decision = CompletionPolicy(completion(
      text = "",
      calls = List(call()),
      native = Some(NativeTurn("provider", "model", "partial payload")),
      reason = "stream_incomplete",
      stop = CompletionStop.Incomplete,
    ))
    assert(decision.message.text.contains("stream ended"))
    assertEquals(decision.message.toolCalls, Nil)
    assertEquals(decision.message.native, None)
    assertEquals(decision.next, CompletionPolicy.Next.Resume(Some(AgentMessages.truncationContinuation)))
    assert(decision.warnings.exists(_.contains("finish marker")))
    val partial = CompletionPolicy(completion(
      text = "partial answer",
      native = Some(NativeTurn("provider", "model", "partial payload")),
      reason = "stream_incomplete",
      stop = CompletionStop.Incomplete,
    ))
    assertEquals(partial.message, Msg.Assistant("partial answer", Nil, None))

  test("an empty terminal response gets a visible neutral marker"):
    val decision = CompletionPolicy(completion(
      text = "  ",
      native = Some(NativeTurn("provider", "model", "payload")),
    ))
    assert(decision.message.text.contains("model returned no response"), decision.message.text)
    assertEquals(decision.message.native, None)
    assertEquals(decision.next, CompletionPolicy.Next.Finish)
    assertEquals(decision.warnings, List("The model returned no response."))
