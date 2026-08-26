package atc

import atc.agent.CompletionPolicy
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
    assertEquals(paused.next, CompletionPolicy.Next.Resume(needsContinuation = false))

    val truncated = CompletionPolicy(completion(reason = "max_tokens", stop = CompletionStop.Truncated))
    assertEquals(truncated.next, CompletionPolicy.Next.Resume(needsContinuation = true))

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
    assertEquals(decision.next, CompletionPolicy.Next.Resume(needsContinuation = true))
    assert(decision.warnings.exists(_.contains("ignored 1 tool call")), decision.warnings.toString)

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

  test("an empty terminal response gets a visible neutral marker"):
    val decision = CompletionPolicy(completion(
      text = "  ",
      native = Some(NativeTurn("provider", "model", "payload")),
    ))
    assert(decision.message.text.contains("model returned no response"), decision.message.text)
    assertEquals(decision.message.native, None)
    assertEquals(decision.next, CompletionPolicy.Next.Finish)
    assertEquals(decision.warnings, List("The model returned no response."))
