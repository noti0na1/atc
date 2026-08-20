package atc

import atc.agent.*
import atc.config.Config
import atc.llm.*
import atc.perms.Decision
import atc.sandbox.{ExecutionResult, ReplSession}

import scala.collection.mutable.ListBuffer

/** A programmable model: returns a scripted sequence of completions, records
  * the history it is asked to complete, and can throw on demand. */
final class ScriptedModel(
  val alias: String,
  steps: Seq[ScriptedModel.Step],
  override val contextWindow: Option[Int] = None
) extends ChatModel:
  val modelId = "scripted"; val providerKey = "scripted"; val webSearch = false
  val seenHistories: ListBuffer[List[Msg]] = ListBuffer()
  var i = 0
  def complete(
    system: SystemPrompt,
    history: List[Msg],
    tools: List[ToolSpec],
    sink: StreamSink,
    cancelled: () => Boolean
  ): Completion =
    def onText(t: String): Unit = sink.text(t)
    seenHistories += history
    val step = if i < steps.length then steps(i) else ScriptedModel.Reply("(no more steps)")
    i += 1
    step match
      case ScriptedModel.Throw(e) => throw e
      case ScriptedModel.Comp(c) => onText(c.text); c
      case ScriptedModel.Reply(t) => onText(t); Completion(t, Nil, None, TokenUsage(1, 1), "end_turn")
  def simple(system: Option[String], prompt: String, thinking: Boolean): Reply =
    Reply(s"scripted: $prompt", TokenUsage(1, 1))

object ScriptedModel:
  sealed trait Step
  case class Comp(c: Completion) extends Step
  case class Reply(text: String) extends Step
  case class Throw(e: Throwable) extends Step
  private var seq = 0
  private def nextId(): String = { seq += 1; s"call-$seq" }
  /** A completion that calls `run_scala` with `code`. */
  def tool(code: String, text: String = "running"): Comp =
    Comp(Completion(
      text,
      List(ToolCall(nextId(), Prompts.ToolName, ujson.write(ujson.Obj("code" -> code)))),
      None,
      TokenUsage(1, 1),
      "tool_use"
    ))
  /** A completion with several tool calls at once. */
  def tools(codes: String*): Comp =
    Comp(Completion(
      "multi",
      codes.toList.map(c => ToolCall(nextId(), Prompts.ToolName, ujson.write(ujson.Obj("code" -> c)))),
      None,
      TokenUsage(1, 1),
      "tool_use"
    ))
  def unfinished(text: String): Comp =
    Comp(Completion(text, Nil, None, TokenUsage(1, 1), "pause_turn", unfinished = true))
  def refusal(text: String): Comp = Comp(Completion(text, Nil, None, TokenUsage(1, 1), "refusal"))

/** A recording AgentUI. */
final class RecordingUI extends AgentUI:
  /** What the double answers when the tool budget is used up, and how often it was asked. */
  var allowMoreToolCalls: Boolean = false
  var budgetAsks: Int = 0
  override def confirmMoreToolCalls(used: Int, budget: Int): Boolean =
    budgetAsks += 1
    allowMoreToolCalls
  val deltas, notes, statuses, warnings, toolStarts = ListBuffer[String]()
  val toolEnds = ListBuffer[Boolean]()
  var ends = 0
  def assistantDelta(text: String): Unit = deltas += text
  def assistantNote(text: String): Unit = notes += text
  def assistantEnd(): Unit = ends += 1
  def toolStart(code: String): Unit = toolStarts += code
  def toolEnd(result: ExecutionResult, millis: Long): Unit = toolEnds += result.success
  def status(text: String): Unit = statuses += text
  def warn(text: String): Unit = warnings += text
  def thinkingDelta(text: String): Unit = ()

/** The agent loop mechanics driven by a scripted model (resume, tool
  * budget, multi-tool, cancellation, refusal, usage accounting). */
class AgentLoopSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private def setup(
    model: ChatModel,
    cfg: Config = Config(),
    classified: Option[ChatModel] = None
  ): (TestEnv, ReplSession, RecordingUI, Agent) =
    val env = TestEnv(prefix = "atc-loop")
    val s = env.newSession()
    val ui = RecordingUI()
    val agent = Agent(cfg, env.root, env.policy, ui, model, classified, None)
    (env, s, ui, agent)

  private def assistants(a: Agent): List[Msg.Assistant] = a.history.collect { case m: Msg.Assistant => m }
  private def toolResults(a: Agent): List[Msg.ToolResults] = a.history.collect { case m: Msg.ToolResults => m }
  private def never(): Boolean = false

  test("a sandbox restart is announced to the model on the next turn, once"):
    // A mode switch or /reset replaces the REPL, so the agent's `val`s and `def`s
    // are gone. Without a notice the model concludes that the documented
    // "definitions persist" guarantee is broken and wastes a round on it.
    val (_, s, _, agent) = setup(ScriptedModel("m", Seq(ScriptedModel.Reply("a"), ScriptedModel.Reply("b"))))
    agent.noteSandboxRestarted("the sandbox mode changed to read-only")
    agent.turn(s, "what is left?", never)
    val first = agent.history.head.asInstanceOf[Msg.User].text
    assert(first.contains("[sandbox notice]"), first)
    assert(first.contains("read-only"), first)
    assert(first.endsWith("what is left?"), first)
    // The notice is not repeated on later turns.
    agent.turn(s, "and now?", never)
    val second = agent.history.collect { case u: Msg.User => u }.last.text
    assertEquals(second, "and now?")

  test("code the user ran (/run) is reported to the model on the next turn, after a restart notice, once"):
    // The REPL is shared: the user's definitions now exist in the session the
    // model continues in, so it must hear what was run and what came of it.
    val (_, s, _, agent) = setup(ScriptedModel("m", Seq(ScriptedModel.Reply("a"), ScriptedModel.Reply("b"))))
    agent.noteSandboxRestarted("you asked for /reset")
    agent.noteUserRan("val answer = 42", s.run("val answer = 42"))
    agent.turn(s, "double it", never)
    val first = agent.history.head.asInstanceOf[Msg.User].text
    val (restart, ran) = (first.indexOf("[sandbox notice]"), first.indexOf("[user ran code]"))
    assert(restart >= 0 && ran > restart, first)
    assert(first.contains("```scala\nval answer = 42\n```"), first)
    assert(first.contains("answer: Int = 42"), first)
    assert(first.endsWith("double it"), first)
    agent.turn(s, "and now?", never)
    assertEquals(agent.history.collect { case u: Msg.User => u }.last.text, "and now?")

  test("clear() drops a pending restart notice"):
    val (_, s, _, agent) = setup(ScriptedModel("m", Seq(ScriptedModel.Reply("a"))))
    agent.noteSandboxRestarted("whatever")
    agent.clear()
    agent.turn(s, "fresh", never)
    assertEquals(agent.history.head, Msg.User("fresh"))

  test("a plain reply becomes one assistant message and streams its text"):
    val (_, s, ui, agent) = setup(ScriptedModel("m", Seq(ScriptedModel.Reply("hello there"))))
    agent.turn(s, "hi", never)
    assertEquals(agent.history.head, Msg.User("hi"))
    assertEquals(agent.history.last, Msg.Assistant("hello there", Nil, None))
    assert(ui.deltas.contains("hello there"))
    assertEquals(ui.ends, 1)

  test("a tool call runs, its result is appended, then the model answers"):
    val (env, s, ui, agent) = setup(ScriptedModel(
      "m",
      Seq(
        ScriptedModel.tool("""println("side effect"); 21 * 2"""),
        ScriptedModel.Reply("the answer is 42"),
      )
    ))
    agent.turn(s, "compute", never)
    val trs = toolResults(agent)
    assertEquals(trs.size, 1)
    assertEquals(trs.head.results.size, 1)
    assert(!trs.head.results.head.isError, trs.toString)
    assert(trs.head.results.head.output.contains("42"), trs.head.results.head.output)
    assert(env.agentOut.toString.contains("side effect"))
    assertEquals(ui.toolStarts.size, 1)
    assertEquals(ui.toolEnds.toList, List(true))
    assertEquals(agent.history.last, Msg.Assistant("the answer is 42", Nil, None))
    // the model saw the tool result before its final reply: it was in the history
    // passed to the second complete() call.
    val model = agent.model.asInstanceOf[ScriptedModel]
    assert(model.seenHistories(1).exists(_.isInstanceOf[Msg.ToolResults]))

  test("a failing tool call is reported as an error result"):
    val (_, s, ui, agent) = setup(ScriptedModel(
      "m",
      Seq(
        ScriptedModel.tool("""throw new RuntimeException("boom")"""),
        ScriptedModel.Reply("noted"),
      )
    ))
    agent.turn(s, "go", never)
    val tr = toolResults(agent).head.results.head
    assert(tr.isError)
    assert(tr.output.contains("boom"), tr.output)
    assertEquals(ui.toolEnds.toList, List(false))

  test("several tool calls in one completion all run, in order"):
    val (_, s, _, agent) = setup(ScriptedModel(
      "m",
      Seq(
        ScriptedModel.tools("val a = 1", "val b = a + 1", "println(b)"),
        ScriptedModel.Reply("done"),
      )
    ))
    agent.turn(s, "go", never)
    val results = toolResults(agent).head.results
    assertEquals(results.size, 3)
    assert(results.forall(!_.isError), results.toString)
    assert(results.last.output.contains("2"), results.last.output)

  test("empty / missing code argument is rejected without touching the REPL"):
    val (_, s, _, agent) = setup(ScriptedModel(
      "m",
      Seq(
        ScriptedModel.Comp(Completion(
          "x",
          List(ToolCall("c1", Prompts.ToolName, "{}")),
          None,
          TokenUsage(1, 1),
          "tool_use"
        )),
        ScriptedModel.Reply("ok"),
      )
    ))
    agent.turn(s, "go", never)
    val tr = toolResults(agent).head.results.head
    assert(tr.isError)
    assert(tr.output.contains("Missing 'code'"), tr.output)

  test("an unknown tool name is reported back to the model"):
    val (_, s, _, agent) = setup(ScriptedModel(
      "m",
      Seq(
        ScriptedModel.Comp(Completion(
          "x",
          List(ToolCall("c1", "delete_everything", "{}")),
          None,
          TokenUsage(1, 1),
          "tool_use"
        )),
        ScriptedModel.Reply("ok"),
      )
    ))
    agent.turn(s, "go", never)
    val tr = toolResults(agent).head.results.head
    assert(tr.isError)
    assert(tr.output.contains("Unknown tool"), tr.output)
    assert(tr.output.contains(Prompts.ToolName), tr.output)

  test("the per-turn tool budget stops extra calls with an explanatory result"):
    val (_, s, _, agent) = setup(
      ScriptedModel(
        "m",
        Seq(
          ScriptedModel.tool("1 + 1"),
          ScriptedModel.tool("2 + 2"),
          ScriptedModel.Reply("stopping"),
        )
      ),
      cfg = Config(maxToolCalls = 1)
    )
    agent.turn(s, "go", never)
    val allResults = toolResults(agent).flatMap(_.results)
    assertEquals(allResults.size, 2)
    assert(!allResults.head.isError)
    assert(allResults(1).isError)
    assert(allResults(1).output.contains("budget"), allResults(1).output)
    assertEquals(agent.toolCalls, 1) // only the first actually ran

  test("the tool budget is a checkpoint: when the UI agrees, the turn continues for another budget"):
    val steps = Seq(
      ScriptedModel.tool("1 + 1"),
      ScriptedModel.tool("2 + 2"),
      ScriptedModel.tool("3 + 3"),
      ScriptedModel.Reply("done"),
    )
    val (_, s, ui, agent) = setup(ScriptedModel("m", steps), cfg = Config(maxToolCalls = 1))
    ui.allowMoreToolCalls = true
    agent.turn(s, "go", never)
    assertEquals(agent.toolCalls, 3) // every call ran
    assertEquals(ui.budgetAsks, 2) // asked before the 2nd and the 3rd
    assert(toolResults(agent).flatMap(_.results).forall(!_.isError))
    // a budget of 0 means "no tools" and is never extended
    val (_, s2, ui2, agent2) =
      setup(ScriptedModel("m", Seq(ScriptedModel.tool("1"), ScriptedModel.Reply("x"))), cfg = Config(maxToolCalls = 0))
    ui2.allowMoreToolCalls = true
    agent2.turn(s2, "go", never)
    assertEquals(agent2.toolCalls, 0)
    assertEquals(ui2.budgetAsks, 0)

  test("a model cannot loop forever after exhausting the tool budget"):
    val steps = Seq.fill(10)(ScriptedModel.tool("1 + 1"))
    val (_, s, ui, agent) = setup(ScriptedModel("m", steps), cfg = Config(maxToolCalls = 0))
    agent.turn(s, "go", never)
    assertEquals(toolResults(agent).size, Agent.MaxBudgetRejections)
    assertEquals(agent.toolCalls, 0)
    assert(ui.warnings.exists(_.contains("tool budget")))

  test("an unfinished completion is resumed"):
    val (_, s, ui, agent) = setup(ScriptedModel(
      "m",
      Seq(
        ScriptedModel.unfinished("searching the web…"),
        ScriptedModel.Reply("Here is what I found."),
      )
    ))
    agent.turn(s, "look it up", never)
    assert(!agent.history.exists {
      case Msg.User(t) => t != "look it up"; case _ => false
    }) // no injected user messages
    assertEquals(assistants(agent).map(_.text), List("searching the web…", "Here is what I found."))
    assert(ui.statuses.exists(_.contains("resuming")))

  test("resuming is bounded by MaxResumes"):
    val steps = Seq.fill(Agent.MaxResumes + 3)(ScriptedModel.unfinished("still going"))
    val (_, s, _, agent) = setup(ScriptedModel("m", steps))
    agent.turn(s, "go", never)
    // MaxResumes resume-rounds after the first completion → MaxResumes+1 assistant messages, then stop
    assertEquals(assistants(agent).size, Agent.MaxResumes + 1)

  test("a refusal stop reason warns the user"):
    val (_, s, ui, agent) = setup(ScriptedModel("m", Seq(ScriptedModel.refusal("I can't help with that."))))
    agent.turn(s, "do something", never)
    assert(ui.warnings.exists(_.toLowerCase.contains("refus")))
    assertEquals(agent.history.last, Msg.Assistant("I can't help with that.", Nil, None))

  test("cancellation before a tool runs yields a cancelled result and interrupted turn"):
    val (_, s, _, agent) = setup(ScriptedModel(
      "m",
      Seq(
        ScriptedModel.tool("1 + 1"),
        ScriptedModel.Reply("unreached"),
      )
    ))
    agent.turn(s, "go", () => true) // cancelled from the start
    val tr = toolResults(agent).head.results.head
    assert(tr.isError)
    assert(tr.output.contains("Cancelled"), tr.output)
    assertEquals(agent.history.last, Msg.Assistant("[interrupted by user]", Nil, None))

  test("a CancelledException during streaming ends the turn cleanly and tells the user"):
    val (_, s, ui, agent) = setup(ScriptedModel("m", Seq(ScriptedModel.Throw(new CancelledException))))
    agent.turn(s, "go", never)
    assertEquals(agent.history.last, Msg.Assistant("[interrupted by user]", Nil, None))
    assert(ui.warnings.exists(_.contains("interrupted")), ui.warnings.toString)

  test("fitToContext drops whole exchanges from the front and always keeps the last user message"):
    def u(t: String) = Msg.User(t)
    def a(t: String) = Msg.Assistant(t, Nil, None)
    def tr(t: String) = Msg.ToolResults(List(ToolResult("c", t, false)))
    val big = "x" * 4000 // ~1000 tokens
    val history = List(u("q1"), a(big), tr(big), a("a1"), u("q2"), a(big), a("a2"), u("q3"), a("a3"))
    val total = history.map(Agent.estimateTokens).sum
    assertEquals(Agent.fitToContext(history, total), (history, 0)) // fits: untouched
    // dropping the first exchange (q1 .. a1) is enough
    val (kept1, d1) = Agent.fitToContext(history, total - 100)
    assertEquals(d1, 4)
    assertEquals(kept1.head, u("q2"))
    // the cut never separates a tool result from its call: it starts at a user message
    assert(kept1.head.isInstanceOf[Msg.User])
    // far too small: only the last user message and what follows survive
    val (kept2, d2) = Agent.fitToContext(history, 1)
    assertEquals(kept2, List(u("q3"), a("a3")))
    assertEquals(d2, 7)
    assertEquals(Agent.fitToContext(Nil, 1), (Nil, 0))

  test("a model with a context window sees the oldest exchanges dropped, and is told"):
    val big = "y" * 8000 // ~2000 tokens
    val model = ScriptedModel(
      "m",
      Seq(ScriptedModel.Reply(big), ScriptedModel.Reply(big), ScriptedModel.Reply("third")),
      contextWindow = Some(6000)
    )
    val (_, s, ui, agent) = setup(model)
    agent.turn(s, "first question", never)
    agent.turn(s, "second question", never)
    // by now: user, big, user, big (~4000 tokens of history) + system prompt; the third turn must cut
    agent.turn(s, "third question", never)
    val seen = model.seenHistories.last
    assert(seen.size < 5, seen.map(_.toString.take(40)).toString)
    assert(seen.head.isInstanceOf[Msg.User])
    assert(seen.head.asInstanceOf[Msg.User].text.startsWith("[context notice]"), seen.head.toString.take(120))
    assert(ui.warnings.exists(_.contains("context window")), ui.warnings.toString)
    // the history itself is cut (what was shown stays in the terminal), and starts with the notice
    assertEquals(agent.history.count(_.isInstanceOf[Msg.User]), 1)
    assert(agent.history.head.asInstanceOf[Msg.User].text.contains("oldest messages"), agent.history.head.toString)

  test("contextUsage estimates the next request, grows with the history, and follows the provider's count"):
    // TokenUsage(1, 1) per completion is below CalibrationMinTokens, so the estimate stays chars/4.
    val model = ScriptedModel("m", Seq(ScriptedModel.Reply("a" * 4000), ScriptedModel.Reply("b")), Some(100_000))
    val (_, s, _, agent) = setup(model)
    val before = agent.contextUsage
    assertEquals(before.window, Some(100_000))
    assert(before.tokens > 0, before.toString) // the system prompt and tool schema alone count
    agent.turn(s, "hello", never)
    val after = agent.contextUsage
    assert(after.tokens >= before.tokens + 1000, s"$before -> $after") // ~1000 tokens of reply appended
    // A real provider count calibrates the estimate: a completion reporting a
    // prompt twice the estimate doubles what is shown.
    val big = ScriptedModel.Comp(Completion("c", Nil, None, TokenUsage(after.tokens * 2, 1), "end_turn"))
    val (_, s2, _, agent2) = setup(ScriptedModel("m", Seq(big), Some(100_000)))
    agent2.turn(s2, "hello", never)
    val estimate = agent2.contextUsage.tokens
    val raw = Agent.estimateTokens(agent2.systemPrompt.text) + agent2.history.map(Agent.estimateTokens).sum
    assert(estimate > raw * 3 / 2, s"estimate $estimate raw $raw")
    agent2.clear()
    assertEquals(agent2.contextUsage.window, Some(100_000))
    assert(agent2.contextUsage.tokens < after.tokens, "clear() leaves only the fixed part")

  test("what the user decided at a prompt is reported in that tool result; the system prompt never changes"):
    // The model cannot see the pop-ups, so each decision is said in the result
    // (once vs. session vs. denied); the prompt stays the same for the whole
    // session so every request keeps its cacheable prefix.
    val code = """requestExec(Set("ls*"), "list the directory") { exec("ls", List(".")).exitCode }"""
    val (env, s, _, agent) = setup(ScriptedModel(
      "m",
      Seq(ScriptedModel.tool(code), ScriptedModel.tool(code), ScriptedModel.tool(code), ScriptedModel.Reply("done"))
    ))
    env.decisions = List(Decision.AllowOnce, Decision.AllowSession)
    val promptBefore = agent.systemPrompt.text
    assert(promptBefore.contains("Current permissions"), promptBefore)
    agent.turn(s, "list it", never)
    val results = toolResults(agent).flatMap(_.results)
    assertEquals(results.size, 3)
    assert(
      results(0).output.contains("[permissions: the user allowed commands ls* once (this call only"),
      results(0).output
    )
    assert(
      results(1).output.contains("[permissions: the user allowed commands ls* for the rest of this session"),
      results(1).output
    )
    assert(!results(2).output.contains("[permissions:"), results(2).output) // covered by the session grant: no prompt
    assertEquals(env.requests.size, 2) // once, then session, then nothing to ask
    assertEquals(agent.systemPrompt.text, promptBefore)
    assert(!agent.systemPrompt.text.contains("ls*"))

  test("usage is accumulated across the turn and reset by clear()"):
    val (_, s, _, agent) = setup(ScriptedModel(
      "m",
      Seq(
        ScriptedModel.tool("1 + 1"),
        ScriptedModel.Reply("done"),
      )
    ))
    agent.turn(s, "go", never)
    assertEquals(agent.usage.input, 2L) // two completions, TokenUsage(1,1) each
    assertEquals(agent.usage.output, 2L)
    assert(agent.toolCalls >= 1)
    // other model calls (chat(), predictions) are recorded under their own purpose and add to the total
    agent.recordUsage(Agent.Prediction, TokenUsage(10, 5))
    agent.recordUsage(Agent.Prediction, TokenUsage(10, 5))
    assertEquals(agent.usage, TokenUsage(22, 12))
    assertEquals(agent.usageByPurpose, List(Agent.Turns -> TokenUsage(2, 2), Agent.Prediction -> TokenUsage(20, 10)))
    agent.clear()
    assertEquals(agent.history, Nil)
    assertEquals(agent.usage, TokenUsage())
    assertEquals(agent.usageByPurpose, Nil)
    assertEquals(agent.toolCalls, 0)

  test("history threads across turns and state persists in the session"):
    val (_, s, _, agent) = setup(ScriptedModel(
      "m",
      Seq(
        ScriptedModel.tool("val remembered = 7"),
        ScriptedModel.Reply("stored"),
        ScriptedModel.tool("remembered * 6"),
        ScriptedModel.Reply("42"),
      )
    ))
    agent.turn(s, "store", never)
    agent.turn(s, "use it", never)
    assertEquals(agent.history.count { case _: Msg.User => true; case _ => false }, 2)
    assert(toolResults(agent).last.results.head.output.contains("42"))

  test("the native replay payload is carried on the assistant message"):
    val native = NativeTurn("scripted", "opaque-payload")
    val (_, s, _, agent) = setup(ScriptedModel(
      "m",
      Seq(
        ScriptedModel.Comp(Completion("with native", Nil, Some(native), TokenUsage(1, 1), "end_turn")),
      )
    ))
    agent.turn(s, "go", never)
    assertEquals(agent.history.last, Msg.Assistant("with native", Nil, Some(native)))

  test("the system prompt says whether a classified model exists, never which one"):
    val (_, _, _, without) = setup(ScriptedModel("m", Nil))
    assert(without.systemPrompt.text.contains("classified model"), "the line is there either way")
    assert(without.systemPrompt.text.contains("none configured"), without.systemPrompt.text)
    val (_, _, _, withClassified) =
      setup(ScriptedModel("m", Nil), classified = Some(ScriptedModel("private-llm", Nil)))
    assert(
      withClassified.systemPrompt.text.contains("`chat(Classified)`): configured"),
      withClassified.systemPrompt.text
    )
    assert(!withClassified.systemPrompt.text.contains("private-llm"), "the agent model is not told which model it is")
