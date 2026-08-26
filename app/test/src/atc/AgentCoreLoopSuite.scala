package atc

import atc.agent.{Agent, AgentEnvironment, AgentMessages, ToolRunner}
import atc.config.Config
import atc.llm.*
import atc.perms.{Decision, Policy}

import scala.collection.mutable.ListBuffer

/** Agent-loop contract tests with all tool effects supplied by a small in-memory
  * runner. These deliberately do not construct a sandbox or REPL session. */
class AgentCoreLoopSuite extends munit.FunSuite:
  private val toolSpec = ToolSpec("test_tool", "A deterministic test tool.", "{\"type\":\"object\"}")

  private final class RecordingRunner(
    result: ToolCall => ToolResult = call => ToolResult(call.id, s"result:${call.id}", isError = false),
  ) extends ToolRunner:
    val tools: List[ToolSpec] = List(toolSpec)
    val calls: ListBuffer[ToolCall] = ListBuffer()

    def run(call: ToolCall): ToolResult =
      calls += call
      result(call)

  private def toolStep(ids: String*): ScriptedModel.Comp =
    ScriptedModel.Comp(Completion(
      "running tools",
      ids.toList.map(id => ToolCall(id, toolSpec.name, s"{\"id\":\"$id\"}")),
      None,
      TokenUsage(1, 1),
      "tool_use",
      CompletionStop.Complete,
    ))

  private def setup(
    steps: Seq[ScriptedModel.Step],
    config: Config = Config(),
  ): (ScriptedModel, RecordingUI, Agent) =
    val model = ScriptedModel("core", steps)
    val ui = RecordingUI()
    val policy = Policy(Nil, Nil, Nil, _ => Decision.Deny)
    val environment = AgentEnvironment("/test", "test-os")
    (model, ui, Agent(config, environment, policy, ui, model, None, None))

  private def toolResults(agent: Agent): List[Msg.ToolResults] =
    agent.history.collect { case results: Msg.ToolResults => results }

  test("multi-call results retain request order and call ids"):
    val ids = List("first", "second", "third")
    val (model, _, agent) = setup(Seq(toolStep(ids*), ScriptedModel.Reply("done")))
    val runner = RecordingRunner()

    agent.runTurn(runner, "go", () => false)

    assertEquals(runner.calls.map(_.id).toList, ids)
    val results = toolResults(agent).head.results
    assertEquals(results.map(_.callId), ids)
    assertEquals(results.map(_.output), ids.map(id => s"result:$id"))
    assertEquals(agent.toolCalls, ids.size)
    assertEquals(model.seenHistories(1).last, Msg.ToolResults(results))

  test("budget errors preserve the complete call/result alignment"):
    val ids = List("allowed", "over-one", "over-two")
    val (_, ui, agent) = setup(
      Seq(toolStep(ids*), ScriptedModel.Reply("done")),
      Config(maxToolCalls = 1),
    )
    val runner = RecordingRunner()

    agent.runTurn(runner, "go", () => false)

    assertEquals(runner.calls.map(_.id).toList, List("allowed"))
    val results = toolResults(agent).head.results
    assertEquals(results.map(_.callId), ids)
    assert(!results.head.isError, results.toString)
    assert(results.tail.forall(r => r.isError && r.output.contains("budget")), results.toString)
    assertEquals(ui.budgetAsks, 1)
    assertEquals(agent.toolCalls, 1)

  test("mid-batch cancellation preserves the complete call/result alignment"):
    val ids = List("ran", "cancelled-one", "cancelled-two")
    val (_, ui, agent) = setup(Seq(toolStep(ids*)))
    val runner = RecordingRunner()
    var checks = 0
    def cancelled(): Boolean =
      checks += 1
      checks >= 3 // round check, first call check, then cancellation

    agent.runTurn(runner, "go", cancelled)

    assertEquals(runner.calls.map(_.id).toList, List("ran"))
    val results = toolResults(agent).head.results
    assertEquals(results.map(_.callId), ids)
    assert(!results.head.isError, results.toString)
    assertEquals(
      results.tail.map(r => (r.isError, r.output)),
      List.fill(2)((true, AgentMessages.cancelledBeforeExecution)),
    )
    assertEquals(agent.history.last, Msg.Assistant(AgentMessages.interrupted, Nil, None))
    assert(ui.warnings.contains(AgentMessages.interruptedWarning), ui.warnings.toString)
    assertEquals(agent.toolCalls, 1)

  test("a runner exception repairs every pending tool call in the transcript"):
    val ids = List("completed-before-throw", "throws", "not-reached")
    val (_, _, agent) = setup(Seq(toolStep(ids*)))
    val runner = RecordingRunner(call =>
      if call.id == "throws" then throw RuntimeException("runner exploded")
      else ToolResult(call.id, s"result:${call.id}", isError = false)
    )

    val error = intercept[RuntimeException](agent.runTurn(runner, "go", () => false))

    assertEquals(error.getMessage, "runner exploded")
    assertEquals(runner.calls.map(_.id).toList, ids.take(2))
    val results = toolResults(agent).last.results
    assertEquals(results.map(_.callId), ids)
    assert(results.forall(_.isError), results.toString)
    assert(results.forall(_.output == "[turn failed: runner exploded]"), results.toString)
    val requestIndex = agent.history.indexWhere {
      case Msg.Assistant(_, calls, _) => calls.nonEmpty
      case _ => false
    }
    assertEquals(agent.history(requestIndex + 1), Msg.ToolResults(results))
