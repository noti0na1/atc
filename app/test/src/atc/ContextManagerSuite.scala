package atc

import atc.agent.{AgentMessages, ContextManager}
import atc.agent.ContextManager.ModelContext
import atc.llm.{Msg, NativeTurn, ToolCall, ToolResult}

class ContextManagerSuite extends munit.FunSuite:
  private def user(text: String): Msg = Msg.User(text)
  private def assistant(text: String): Msg = Msg.Assistant(text, Nil, None)

  test("token estimation preserves message framing and model-native replay semantics"):
    assertEquals(ContextManager.estimateTokens(""), 0L)
    assertEquals(ContextManager.estimateTokens("a"), 1L)
    assertEquals(ContextManager.estimateTokens("abcd"), 1L)
    assertEquals(ContextManager.estimateTokens("abcde"), 2L)
    assertEquals(ContextManager.estimateTokens(user("abcd")), 5L)
    assertEquals(ContextManager.estimateTokens(Msg.Continuation("abcd")), 5L)
    assertEquals(
      ContextManager.estimateTokens(Msg.ToolResults(List(ToolResult("id", "abcd", isError = false)))),
      17L,
    )
    val call = ToolCall("id", "run_scala", "abcd")
    assertEquals(ContextManager.estimateTokens(Msg.Assistant("abcd", List(call), None)), 18L)

    val native = NativeTurn("anthropic", "anthropic/sonnet", "x" * 400)
    val message = Msg.Assistant("abcd", Nil, Some(native))
    assertEquals(ContextManager.estimateTokens(message), 104L)
    assertEquals(ContextManager.estimateTokens(message, "anthropic", "anthropic/sonnet"), 104L)
    assertEquals(ContextManager.estimateTokens(message, "openai", "openai/gpt"), 5L)

  test("fitToContext drops only whole exchanges and keeps the last real user message"):
    val history = List(
      user("q1"),
      assistant("a1"),
      Msg.ToolResults(List(ToolResult("id", "r1", isError = false))),
      Msg.Continuation("continue"),
      user("q2"),
      assistant("a2"),
    )
    val tenEach: Msg => Long = _ => 10L
    assertEquals(ContextManager.fitToContext(history, 60, tenEach), (history, 0))
    assertEquals(ContextManager.fitToContext(history, 35, tenEach), (history.drop(4), 4))
    assertEquals(ContextManager.fitToContext(history, 1, tenEach), (history.drop(4), 4))
    val withoutUser = List(assistant("a"), Msg.Continuation("continue"))
    assertEquals(ContextManager.fitToContext(withoutUser, 0, tenEach), (withoutUser, 0))
    assertEquals(ContextManager.fitToContext(Nil, 0, tenEach), (Nil, 0))

  test("prepare leaves an unbounded model unchanged and reports both estimates"):
    val manager = ContextManager()
    val model = ModelContext("m", "p", "p/m")
    val history = List(user("abcd"), assistant("abcdefgh"))
    val prepared = manager.prepare(10, history, model)
    assertEquals(prepared.history, history)
    assertEquals(prepared.estimatedInput, 21L)
    assertEquals(prepared.calibratedInput, 21L)
    assertEquals(prepared.dropped, 0)
    assertEquals(prepared.totalDropped, 0)
    assertEquals(prepared.warnings, Nil)
    assertEquals(manager.contextUsage(10, history, model), ContextManager.ContextUsage(21, None))

  test("prepare drops old exchanges, inserts a cumulative notice and returns the warning"):
    val manager = ContextManager()
    val model = ModelContext("small", "p", "p/small", contextWindow = Some(400))
    val history = List(user("q1"), assistant("x" * 1600), user("q2"), assistant("done"))

    val first = manager.prepare(0, history, model)
    assertEquals(first.dropped, 2)
    assertEquals(first.totalDropped, 2)
    assertEquals(first.history.tail, history.drop(3))
    assertEquals(
      first.history.head,
      Msg.User(s"${AgentMessages.contextCutNotice(2)}\n\nq2"),
    )
    assertEquals(first.warnings, List(AgentMessages.contextDroppedWarning("small", 400, 2)))

    // A later cut reports the conversation-wide total, not merely this round's count.
    val second = manager.prepare(0, history, model)
    assertEquals(second.dropped, 2)
    assertEquals(second.totalDropped, 4)
    assertEquals(second.history.head, Msg.User(s"${AgentMessages.contextCutNotice(4)}\n\nq2"))

  test("an unavoidable overflow warns at most once per user turn"):
    val manager = ContextManager()
    val model = ModelContext(
      "tiny",
      "p",
      "p/tiny",
      contextWindow = Some(1000),
      maxOutputTokens = Some(600),
    )
    val expected = AgentMessages.contextOverflowWarning(
      "tiny",
      1000,
      AgentMessages.ContextOverflowCause.FixedPrompt,
      500,
      400,
      600,
      Some(600),
    )
    assertEquals(manager.prepare(500, Nil, model).warnings, List(expected))
    assertEquals(manager.prepare(500, Nil, model).warnings, Nil)
    manager.beginTurn()
    assertEquals(manager.prepare(500, Nil, model).warnings, List(expected))

  test("calibration follows provider counts, clamps extremes, and resets for a model change"):
    val manager = ContextManager()
    val model = ModelContext("m", "p", "p/m")
    val history = List(user("abcd"))
    assertEquals(manager.contextUsage(5, history, model).tokens, 10L)

    manager.calibrate(inputTokens = ContextManager.CalibrationMinTokens - 1, estimatedInput = 10)
    assertEquals(manager.calibration, 1.0)
    manager.calibrate(inputTokens = 400, estimatedInput = 100)
    assertEquals(manager.calibration, 4.0)
    assertEquals(manager.contextUsage(5, history, model).tokens, 40L)

    manager.modelChanged()
    assertEquals(manager.calibration, 1.0)
    assertEquals(manager.contextUsage(5, history, model).tokens, 10L)
    manager.calibrate(inputTokens = 10_000, estimatedInput = 100)
    assertEquals(manager.calibration, 8.0)
    manager.calibrate(inputTokens = 200, estimatedInput = 10_000)
    assertEquals(manager.calibration, 0.25)

    manager.reset()
    assertEquals(manager.calibration, 1.0)
    assertEquals(manager.droppedMessages, 0)
