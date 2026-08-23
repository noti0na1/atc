package atc

import atc.agent.Agent
import atc.config.{Config, ModelCatalog, ModelConfig, ModelSpec, ProviderConfig}
import atc.llm.*
import atc.sandbox.ExecutionResult

/** The model layer: the echo model, provider dispatch, and the pure `Agent`
  * helpers (`renderForModel`). */
class ModelSuite extends munit.FunSuite:

  private def collect(m: ChatModel, history: List[Msg]): (Completion, String) =
    val sb = StringBuilder()
    val c = m.complete(SystemPrompt("sys"), history, Nil, StreamSink(sb.append(_)), () => false)
    (c, sb.toString)

  test("model stop reasons distinguish resumable truncation from safety blocks"):
    assert(Completion.isTruncatedStop("length"))
    assert(Completion.isTruncatedStop("MAX-TOKENS"))
    assert(Completion.isTruncatedStop("max_output_tokens"))
    assert(!Completion.isTruncatedStop("content_filter"))
    assert(Completion.isBlockedStop("CONTENT-FILTER"))
    assert(Completion.isBlockedStop("refusal"))
    assert(!Completion.isBlockedStop("stop"))

  test("stream cancellation is checked before probing the network-backed iterator"):
    var generated = false
    val events = java.util.stream.Stream.generate(() => { generated = true; "event" })
    try intercept[CancelledException](Streaming.drain(events, () => true)(_ => ()))
    finally events.close()
    assert(!generated)

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

  test("effort fallback recognizes only reasoning-effort bad requests"):
    assert(Providers.isReasoningEffortRejection(Some("reasoning_effort"), "unsupported parameter"))
    assert(Providers.isReasoningEffortRejection(Some("reasoning"), "unsupported block"))
    assert(Providers.isReasoningEffortRejection(None, "The reasoning.effort field is not supported"))
    assert(!Providers.isReasoningEffortRejection(Some("input"), "input is too long"))
    assert(!Providers.isReasoningEffortRejection(None, "unknown model"))

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

  test("provider models expose the output allowance used by context fitting"):
    val capped = ModelSpec("p", "gpt", "openai", "gpt", None, None, ModelConfig(maxTokens = Some(1234)))
    assertEquals(ChatModel.create(capped).maxOutputTokens, Some(1234))
    // Anthropic requires max_tokens; the adapter sends 32k when it is not configured.
    assertEquals(ChatModel.create(spec("anthropic")).maxOutputTokens, Some(32000))

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

  test("renderForModel gives precise safe-mode hints for StringBuilder and top-level var"):
    val builder = Agent.renderForModel(
      ExecutionResult(false, "Cannot refer to object StringBuilder ... from safe code since it is neither ..."),
      10000
    )
    assert(builder.contains("new StringBuilder()"), builder)
    assert(builder.contains("val b: StringBuilder"), builder)
    val variable = Agent.renderForModel(
      ExecutionResult(false, "Mutable variable counter is defined in a class that does not extend Stateful"),
      10000
    )
    assert(variable.contains("top-level `var`"), variable)
    assert(variable.contains("inside a `def`"), variable)

  test("renderForModel adds the ambiguous-FileSystem hint"):
    val r = ExecutionResult(false, "Ambiguous given instances: both fs and fs2 match type FileSystem ...")
    val out = Agent.renderForModel(r, 10000)
    assert(out.contains("requestFiles"), out)

  test("the system prompt really bundles the API reference"):
    // `Prompts.interfaceSource` falls back to "(API reference unavailable)" if the
    // packaged resource is missing, so verify that packaging succeeds.
    val src = atc.agent.Prompts.interfaceSource
    assert(src.contains("def httpPostClassified"), src.take(200))
    assert(!src.contains("API reference unavailable"), "the Interface.scala resource was not bundled")

  test("renderForModel adds the PATH/no-shell hint for a program that cannot run"):
    val r = ExecutionResult(false, "Cannot run program \"gti\": error=2, No such file or directory")
    val out = Agent.renderForModel(r, 10000)
    assert(out.contains("PATH"), out)
    assert(out.contains("no shell"), out)

  test("renderForModel adds the switch-mode hint for read-only capture errors"):
    val out1 = Agent.renderForModel(ExecutionResult(false, "... cannot subsume a read-only capture set ..."), 10000)
    assert(out1.contains("/mode"), out1)
    val out2 = Agent.renderForModel(ExecutionResult(false, "... Cannot call update method ..."), 10000)
    assert(out2.contains("/mode"), out2)

  test("renderForModel adds the mode hint for a capability the mode does not hand out"):
    val out1 = Agent.renderForModel(ExecutionResult(false, "No given instance of type atc.lib.Network ..."), 10000)
    assert(out1.contains("/mode"), out1)
    val out2 = Agent.renderForModel(ExecutionResult(false, "No given instance of type atc.lib.Exec ..."), 10000)
    assert(out2.contains("/mode"), out2)

  test("renderForModel reports a denial in the tool result"):
    val out = Agent.renderForModel(
      ExecutionResult(true, "ok"),
      10000,
      List(atc.perms.Decision.Deny -> "write on '/x'")
    )
    assert(out.contains("the user denied write on '/x' (do not ask again for the same thing)"), out)

  // ── EchoModel ───────────────────────────────────────────────────

  test("echo: a run: message calls run_scala, even with a prepended agent note"):
    val m = EchoModel("echo")
    // The agent prepends `/new` and `/run` notes; the trigger must still be detected.
    val noted = "[sandbox notice] The Scala REPL was restarted (x).\n\nrun: 1 + 1"
    val (c, _) = collect(m, List(Msg.User(noted)))
    assertEquals(c.toolCalls.size, 1)
    val code = Json.parseObject(c.toolCalls.head.arguments).value("code").str
    assertEquals(code, "1 + 1")
    // The trigger also works without a note, while a regular message is echoed.
    assertEquals(collect(m, List(Msg.User("run: 2 + 2")))._1.toolCalls.size, 1)
    val (plain, _) = collect(m, List(Msg.User("hello")))
    assertEquals(plain.text, "echo: hello")
    assert(plain.toolCalls.isEmpty)

  test("echo: a configured contextWindow is honored (so context-fitting demos work key-less)"):
    val m = EchoModel("echo", "echo", Some(5000))
    assertEquals(m.contextWindow, Some(5000))
    assertEquals(EchoModel("echo").contextWindow, None)

  test("renderForModel truncates overlong output keeping head and tail"):
    val big = ("H" * 400) + ("T" * 400)
    val out = Agent.renderForModel(ExecutionResult(true, big), 120)
    assert(out.length < big.length, out.length.toString)
    assert(out.contains("characters omitted"), out)
    assert(out.startsWith("H"))
    assert(out.endsWith("T"))
