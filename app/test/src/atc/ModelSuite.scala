package atc

import atc.agent.Agent
import atc.config.{Config, ModelCatalog, ModelConfig, ModelSpec, ProviderConfig}
import atc.llm.*
import atc.sandbox.ExecutionResult

/** The model layer: the echo model, provider dispatch, and the pure `Agent`
  * helpers (`looksUnfinished`, `renderForModel`). */
class ModelSuite extends munit.FunSuite:

  private def collect(m: ChatModel, history: List[Msg]): (Completion, String) =
    val sb = StringBuilder()
    val c = m.complete(SystemPrompt("sys"), history, Nil, StreamSink(sb.append(_)), () => false)
    (c, sb.toString)

  // ── OpenAI reasoning effort for non-thinking calls ──────────────

  test("the lowest reasoning effort follows the model family, and is not sent to models not known to reason"):
    def lowest(id: String, configured: Boolean = false) = Providers.lowestEffort(id, configured)
    assertEquals(lowest("gpt-5.1"), Some("none"))
    assertEquals(lowest("gpt-5.2-codex"), Some("none"))
    assertEquals(lowest("gpt-5"), Some("minimal"))
    assertEquals(lowest("gpt-5-mini"), Some("minimal"))
    assertEquals(lowest("gpt-5-mini-2025-08-07"), Some("minimal"))
    assertEquals(lowest("o3"), Some("low"))
    assertEquals(lowest("o4-mini"), Some("low"))
    assertEquals(lowest("openai/o1"), Some("low"))
    assertEquals(lowest("gpt-4.1"), None)
    assertEquals(lowest("gpt-4o-mini"), None)
    assertEquals(lowest("llama3.1"), None)
    // a model the config gives an effort to takes the parameter, so ask for the universal minimum
    assertEquals(lowest("deepseek-v4-pro", configured = true), Some("low"))

  test("the thinking switch of OpenAI-compatible vendors is `{\"type\": \"enabled\"|\"disabled\"}`"):
    import scala.jdk.OptionConverters.*
    def typeOf(on: Boolean) = Providers.thinkingSwitch(on).asObject().toScala.get.get("type").asString().toScala
    assertEquals(typeOf(true), Some("enabled"))
    assertEquals(typeOf(false), Some("disabled"))

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
    assertEquals(m.simple(None, "q").text, "echo: q")
    assertEquals(m.alias, "myalias")
    assertEquals(m.providerKey, "echo")
    assertEquals(m.webSearch, false)

  // ── ChatModel.create dispatch ───────────────────────────────────

  private def spec(api: String, provider: String = "p", alias: String = "e") =
    ModelSpec(provider, alias, api, "ignored", None, None, ModelConfig())

  test("create dispatches on the provider's api and keeps the model's reference"):
    val m = ChatModel.create(spec("echo"))
    assert(m.isInstanceOf[EchoModel], m.getClass.getName)
    assertEquals(m.alias, "e")
    assertEquals(m.ref, "p/e")

  test("create rejects an unknown api with a helpful message"):
    val e = intercept[IllegalArgumentException](ChatModel.create(spec("myllm")))
    assert(e.getMessage.nn.contains("myllm"), e.getMessage)
    assert(e.getMessage.nn.contains("anthropic"), e.getMessage)

  // ── ModelCatalog ────────────────────────────────────────────────

  private def catalog(providers: (String, String, List[String])*): ModelCatalog =
    ModelCatalog.from(Config(providers = providers.map { (name, api, aliases) =>
      name -> ProviderConfig(Some(api), models = aliases.map(_ -> ModelConfig()).toMap)
    }.toMap))

  test("a model is found by its alias, or by provider/alias"):
    val c = catalog(("anthropic", "anthropic", List("claude", "sonnet")), ("ollama", "openai", List("llama")))
    assertEquals(c.find("claude").ref, "anthropic/claude")
    assertEquals(c.find("anthropic/claude").ref, "anthropic/claude")
    assertEquals(c.find("Claude").ref, "anthropic/claude") // case-insensitive
    assertEquals(c.find("llama").provider, "ollama")
    // stable order (provider, then alias) and short labels while they are unique
    assertEquals(c.labels, List("claude", "sonnet", "llama"))
    assertEquals(c.default.ref, "anthropic/claude")
    // `name` defaults to the alias
    assertEquals(c.find("llama").modelId, "llama")

  test("a bare alias two providers share is ambiguous; the qualified name is not"):
    val c = catalog(("ollama", "openai", List("llama")), ("vllm", "openai", List("llama")))
    val e = intercept[IllegalArgumentException](c.find("llama"))
    assert(e.getMessage.nn.contains("Ambiguous"), e.getMessage)
    assert(e.getMessage.nn.contains("ollama/llama") && e.getMessage.nn.contains("vllm/llama"), e.getMessage)
    assertEquals(c.find("vllm/llama").provider, "vllm")
    // an ambiguous alias is labelled with its provider everywhere
    assertEquals(c.labels, List("ollama/llama", "vllm/llama"))

  test("an unknown model names the configured ones"):
    val e = intercept[IllegalArgumentException](catalog(("p", "openai", List("a", "b"))).find("nope"))
    assert(e.getMessage.nn.contains("Unknown model 'nope'"), e.getMessage)
    assert(e.getMessage.nn.contains("a, b"), e.getMessage)

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
