package atc

import atc.agent.Agent
import atc.config.ModelConfig
import atc.llm.*
import atc.sandbox.ExecutionResult

/** The model layer: the echo model, provider dispatch, and the pure `Agent`
  * helpers (`looksUnfinished`, `renderForModel`). */
class ModelSuite extends munit.FunSuite:

  private def collect(m: ChatModel, history: List[Msg]): (Completion, String) =
    val sb = StringBuilder()
    val c = m.complete("sys", history, Nil, StreamSink(sb.append(_)), () => false)
    (c, sb.toString)

  // ── EchoModel ───────────────────────────────────────────────────

  test("EchoModel echoes a plain user message"):
    val (c, streamed) = collect(EchoModel("echo"), List(Msg.User("hello")))
    assertEquals(c.text, "echo: hello")
    assert(c.toolCalls.isEmpty)
    assertEquals(c.stopReason, "end_turn")
    assertEquals(streamed, "echo: hello")

  test("EchoModel turns a `run:` message into a run_scala tool call"):
    val (c, _) = collect(EchoModel("echo"), List(Msg.User("run: 1 + 1")))
    assertEquals(c.toolCalls.size, 1)
    assertEquals(c.toolCalls.head.name, "run_scala")
    assertEquals(c.stopReason, "tool_use")
    assertEquals(ujson.read(c.toolCalls.head.arguments).obj("code").str, "1 + 1")

  test("EchoModel reports tool results back"):
    val (c, _) =
      collect(EchoModel("echo"), List(Msg.ToolResults(List(ToolResult("id", "the output", isError = false)))))
    assert(c.text.contains("the output"))
    assert(c.toolCalls.isEmpty)

  test("EchoModel.simple and metadata"):
    val m = EchoModel("myalias")
    assertEquals(m.simple(None, "q"), "echo: q")
    assertEquals(m.alias, "myalias")
    assertEquals(m.providerKey, "echo")
    assertEquals(m.webSearch, false)

  // ── ChatModel.create dispatch ───────────────────────────────────

  test("create resolves the echo provider"):
    val m = ChatModel.create("e", ModelConfig(provider = "echo", model = "ignored"))
    assert(m.isInstanceOf[EchoModel])
    assertEquals(m.alias, "e")

  test("create rejects an unknown provider with a helpful message"):
    val e = intercept[IllegalArgumentException](ChatModel.create("x", ModelConfig(provider = "myllm", model = "m")))
    assert(e.getMessage.nn.contains("myllm"), e.getMessage)
    assert(e.getMessage.nn.contains("anthropic"), e.getMessage)

  // ── Agent.looksUnfinished ───────────────────────────────────────

  test("looksUnfinished detects an announced next step"):
    assert(Agent.looksUnfinished("The docs are YAML-focused. Let me check the classic DSL section."))
    assert(Agent.looksUnfinished("Confirmed. Now let me pin down the Mill version"))
    assert(Agent.looksUnfinished("I'll create the build file next:"))
    assert(Agent.looksUnfinished("I will now run the tests"))
    assert(Agent.looksUnfinished("Next, I check the imports"))
    assert(Agent.looksUnfinished("Let's verify the output"))

  test("looksUnfinished ignores finished or polite closings"):
    assert(!Agent.looksUnfinished("Done. The project compiles and the tests pass."))
    assert(!Agent.looksUnfinished("Let me know if you want anything else."))
    assert(!Agent.looksUnfinished(""))
    assert(!Agent.looksUnfinished("   "))
    // an announcement followed by a question mark / exclamation is not a dangling plan
    assert(!Agent.looksUnfinished("Should I let me check?"))

  // ── Agent.renderForModel ────────────────────────────────────────

  test("Json.parseObject is lenient with tool-call arguments"):
    assertEquals(Json.parseObject("""{"code": "1 + 1"}""").value("code").str, "1 + 1")
    assertEquals(Json.parseObject("").value.size, 0)
    assertEquals(Json.parseObject("not json").value.size, 0)
    assertEquals(Json.parseObject("[1, 2]").value.size, 0)

  test("Json round-trips through Java values"):
    val v = ujson.Obj("s" -> "x", "n" -> 3, "d" -> 1.5, "b" -> true, "l" -> ujson.Arr(1, "a"), "z" -> ujson.Null)
    assertEquals(Json.fromJava(Json.toJava(v)), v)

  test("renderForModel passes short output through"):
    assertEquals(Agent.renderForModel(ExecutionResult(true, "hello"), 1000), "hello")
    assertEquals(Agent.renderForModel(ExecutionResult(true, ""), 1000), "(no output)")

  test("renderForModel adds the explicit-type hint"):
    val r = ExecutionResult(false, "value e needs an explicit type because the inferred type does not conform to ...")
    val out = Agent.renderForModel(r, 10000)
    assert(out.contains("explicit type"), out)
    assert(out.contains("FileEntry^{fs}"), out)

  test("renderForModel adds the safe-mode hint"):
    val r = ExecutionResult(false, "Cannot refer to object ArrayBuffer ... from safe code since it is neither ...")
    val out = Agent.renderForModel(r, 10000)
    assert(out.toLowerCase.contains("not available in safe mode"), out)

  test("renderForModel adds the ambiguous-FileSystem hint"):
    val r = ExecutionResult(false, "Ambiguous given instances: both fs and fs2 match type FileSystem ...")
    val out = Agent.renderForModel(r, 10000)
    assert(out.contains("requestFiles"), out)

  test("renderForModel truncates overlong output keeping head and tail"):
    val big = ("H" * 400) + ("T" * 400)
    val out = Agent.renderForModel(ExecutionResult(true, big), 120)
    assert(out.length < big.length, out.length.toString)
    assert(out.contains("characters omitted"), out)
    assert(out.startsWith("H"))
    assert(out.endsWith("T"))
