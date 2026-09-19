package atc

import atc.config.{Config, ModelCatalog, ModelConfig, ModelSpec, ProviderConfig}
import atc.llm.*

/** The model layer: stop-reason normalization, provider settings, the echo
  * model, adapter dispatch and the model catalog. */
class ModelSuite extends munit.FunSuite:

  private def collect(m: ChatModel, history: List[Msg]): (Completion, String) =
    val sb = StringBuilder()
    val c = m.complete("sys", history, Nil, StreamSink(sb.append(_)), () => false)
    (c, sb.toString)

  test("model stop reasons are normalized into typed loop states"):
    assertEquals(CompletionStop.fromReason("pause_turn"), CompletionStop.Resume)
    assertEquals(CompletionStop.fromReason("length"), CompletionStop.Truncated)
    assertEquals(CompletionStop.fromReason("MAX-TOKENS"), CompletionStop.Truncated)
    assertEquals(CompletionStop.fromReason("max_output_tokens"), CompletionStop.Truncated)
    assertEquals(CompletionStop.fromReason("CONTENT-FILTER"), CompletionStop.Blocked)
    assertEquals(CompletionStop.fromReason("refusal"), CompletionStop.Blocked)
    assertEquals(CompletionStop.fromReason("end_turn"), CompletionStop.Complete)

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
    assertEquals(c.stop, CompletionStop.Complete)
    assertEquals(streamed, "echo: hello")

  test("EchoModel turns a `run:` message into a run_scala tool call"):
    val (c, _) = collect(EchoModel("echo"), List(Msg.User("run: 1 + 1")))
    assertEquals(c.toolCalls.size, 1)
    assertEquals(c.toolCalls.head.name, "run_scala")
    assertEquals(c.stopReason, "tool_use")
    assertEquals(c.stop, CompletionStop.Complete)
    assertEquals(ujson.read(c.toolCalls.head.arguments).obj("code").str, "1 + 1")

  test("EchoModel reports tool results back"):
    val (c, _) =
      collect(EchoModel("echo"), List(Msg.ToolResults(List(ToolResult("id", "the output", isError = false)))))
    assert(c.text.contains("the output"))
    assert(c.toolCalls.isEmpty)

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

  test("a display name is presentation-only and formats banner and model-list names"):
    val configured = Config(
      providers = Map(
        "p" -> ProviderConfig(
          Some("openai"),
          models = Map(
            "stable-alias" -> ModelConfig(
              name = Some("backend-id"),
              webSearch = true,
              displayName = Some("Friendly Model"),
            )
          ),
        )
      )
    )
    val c = ModelCatalog.from(configured)
    val modelSpec = c.find("stable-alias")
    val model = ChatModel.create(modelSpec)

    assertEquals(modelSpec.displayName, Some("Friendly Model"))
    assertEquals(modelSpec.modelId, "backend-id")
    assertEquals(c.labels, List("stable-alias"))
    assertEquals(model.ref, "p/stable-alias")
    assertEquals(model.modelId, "backend-id")
    assertEquals(App.describe(model, modelSpec), "p/stable-alias — Friendly Model (web search)")
    assertEquals(App.modelDetail(modelSpec), "Friendly Model")
    intercept[IllegalArgumentException](c.find("Friendly Model"))

  test("model presentation is unchanged without a display name"):
    val modelSpec = spec("echo")
    val model = ChatModel.create(modelSpec)
    assertEquals(App.describe(model, modelSpec), "p/e — echo")
    assertEquals(App.modelDetail(modelSpec), "p/ignored")

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

  // ── Anthropic prompt caching ────────────────────────────────────

  test("the history cache breakpoint is the last user-role message"):
    val user = Msg.User("q")
    val assistant = Msg.Assistant("a", Nil, None)
    val results = Msg.ToolResults(List(ToolResult("id", "out", isError = false)))
    assertEquals(AnthropicModel.cacheBreakpoint(List(user, assistant, results)), 2)
    assertEquals(AnthropicModel.cacheBreakpoint(List(user, assistant, Msg.Continuation("go"))), 2)
    // A round resumed after a server-side pause re-sends a history ending in an
    // assistant message; the breakpoint stays on the last user-role message.
    assertEquals(AnthropicModel.cacheBreakpoint(List(user, assistant)), 0)
    assertEquals(AnthropicModel.cacheBreakpoint(List(assistant)), -1)
    assertEquals(AnthropicModel.cacheBreakpoint(Nil), -1)

  test("request headers: the user agent unless configured, and the conversation id for ${ATC_SESSION}"):
    def spec(headers: Map[String, String]) =
      ModelSpec("p", "m", "openai", "m", None, None, ModelConfig(), headers)
    assertEquals(Providers.headers(spec(Map.empty)), Map("User-Agent" -> Providers.UserAgent))
    assert(Providers.UserAgent.startsWith("atc/"))
    assertEquals(Providers.headers(spec(Map("User-Agent" -> "mine/1")))("User-Agent"), "mine/1")
    assertEquals(Providers.headers(spec(Map("user-agent" -> "mine/1"))), Map("user-agent" -> "mine/1"))
    val withSession = spec(Map("x-session" -> Config.SessionRef, "x-plain" -> "v"))
    val first = Providers.headers(withSession)
    assertEquals(first("x-plain"), "v")
    assert(first("x-session").matches("[0-9a-f-]{36}"), first("x-session"))
    // stable within a conversation, renewed by a new one
    assertEquals(Providers.headers(withSession)("x-session"), first("x-session"))
    Providers.newConversation()
    assertNotEquals(Providers.headers(withSession)("x-session"), first("x-session"))

  test("chat chunks are accumulated whichever way the usage arrives: after, on, or twice around the finish chunk"):
    import com.openai.models.chat.completions.{ChatCompletion, ChatCompletionChunk}
    import com.openai.models.completions.CompletionUsage
    def chunk(content: String, finished: Boolean, usage: Option[CompletionUsage]) =
      val delta = ChatCompletionChunk.Choice.Delta.builder().content(content).build()
      val choice = ChatCompletionChunk.Choice.builder().index(0L).delta(delta)
        .finishReason(if finished then java.util.Optional.of(ChatCompletionChunk.Choice.FinishReason.STOP)
        else java.util.Optional.empty())
        .build()
      ChatCompletionChunk.builder().id("c").created(1L).model("m").addChoice(choice)
        .usage(usage.map(java.util.Optional.of).getOrElse(java.util.Optional.empty())).build()
    def usageOf(total: Long) =
      CompletionUsage.builder().promptTokens(38L).completionTokens(total - 38L).totalTokens(total).build()
    def usageOnly(total: Long) =
      ChatCompletionChunk.builder().id("c").created(1L).model("m").choices(java.util.List.of()).usage(usageOf(
        total
      )).build()
    val bare = ChatCompletionChunk.builder().id("c").created(1L).model("m").choices(java.util.List.of()).build()
    def feed(chunks: ChatCompletionChunk*): ChatCompletion =
      val f = OpenAIChatModel.ChunkFeed()
      chunks.foreach(f.accumulate)
      f.completion()
    def text(c: ChatCompletion) = c.choices().get(0).message().content().get
    def total(c: ChatCompletion) = c.usage().get.totalTokens()
    // OpenAI's shape: finish chunk, then a choice-less usage chunk
    val openai = feed(chunk("po", false, None), chunk("ng", true, None), usageOnly(67))
    assertEquals(text(openai), "pong")
    assertEquals(total(openai), 67L)
    // DeepSeek's shape: the usage rides on the finish chunk
    val deepseek = feed(chunk("po", false, None), chunk("ng", true, Some(usageOf(67))))
    assertEquals(text(deepseek), "pong")
    assertEquals(total(deepseek), 67L)
    // GLM through OpenCode's gateway: both, and the later one wins; a choice-less chunk
    // without usage (a cost line) is ignored
    val glm = feed(chunk("po", false, None), chunk("ng", true, Some(usageOf(67))), usageOnly(68), bare)
    assertEquals(text(glm), "pong")
    assertEquals(total(glm), 68L)
    // running usage on every chunk, and none at all
    assertEquals(total(feed(chunk("po", false, Some(usageOf(40))), chunk("ng", true, Some(usageOf(67))))), 67L)
    assert(feed(chunk("po", false, None), chunk("ng", true, None)).usage().isEmpty)
    // a stream cut before its finish chunk fails clearly instead of building a completion without choices
    val cut = intercept[IllegalStateException](feed(chunk("po", false, None), usageOnly(67)))
    assert(cut.getMessage.nn.contains("not yet received"), cut.getMessage)
