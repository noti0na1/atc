package atc.llm

import atc.Debug
import atc.config.{ModelSpec, Tokens}

import com.anthropic.backends.AnthropicBackend
import com.anthropic.client.{AnthropicClient, AnthropicClientImpl}
import com.anthropic.core.{ClientOptions, JsonValue, RequestOptions, Timeout}
import com.anthropic.core.http.HttpClient
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.*
import com.anthropic.models.models.ModelListParams

import java.util.Locale
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try

/** Anthropic Messages API (official Java SDK), streaming, with the
  * server-side web-search tool when enabled. */
final class AnthropicModel(spec: ModelSpec) extends SpecModel(spec):
  val providerKey: String = "anthropic"
  protected def knownEfforts: List[String] = List("low", "medium", "high", "xhigh", "max")
  /** What the request asks for: the context fitter reserves exactly this. */
  override val maxOutputTokens: Option[Int] = Some(settings.maxTokens.getOrElse(AnthropicModel.DefaultMaxTokens))

  private final case class Connection(client: AnthropicClient, transport: HttpClient)
  private var opened: Option[Connection] = None
  private def connection: Connection = synchronized:
    opened.getOrElse:
      val backendBuilder = AnthropicBackend.builder()
      spec.apiKey match
        case Some(key) => backendBuilder.apiKey(key)
        case None => backendBuilder.fromEnv()
      spec.baseUrl.foreach(backendBuilder.baseUrl)
      val backend = backendBuilder.build()
      val timeout = Timeout.builder().request(Providers.RequestTimeout).build()
      // The SDK builds and owns the OkHttp client (since 2.63 its transport takes no outside one);
      // cancellation goes through `ModelRequest.scopedTransport` instead of an OkHttp event listener.
      val transport = com.anthropic.client.okhttp.OkHttpClient.builder().timeout(timeout).backend(backend).build()
      val options = ClientOptions.builder().httpClient(transport)
        .baseUrl(backend.baseUrl()).timeout(Providers.RequestTimeout)
      backend.applyCredentials(transport, options)
      val created = Connection(AnthropicClientImpl(options.build()), transport)
      opened = Some(created)
      created
  private def client: AnthropicClient = connection.client
  private def streamingClient: AnthropicClient =
    val current = connection
    current.client.withOptions(_.httpClient(ModelRequest.scopedTransport(current.transport)))

  override def close(): Unit = synchronized:
    opened.foreach(_.client.close())
    opened = None

  private def toolUnion(t: ToolSpec): Tool =
    val schema = ujson.read(t.parametersJson)
    val props = Tool.InputSchema.Properties.builder()
    for properties <- schema.obj.get("properties"); (k, v) <- properties.obj do
      props.putAdditionalProperty(k, JsonValue.from(Json.toJava(v)))
    val input = Tool.InputSchema.builder().properties(props.build())
    schema.obj.get("required").foreach(r => input.required(r.arr.map(_.str).toList.asJava))
    // The rest of the schema (`additionalProperties: false`, ...) as the OpenAI adapters send it.
    for (k, v) <- schema.obj if k != "type" && k != "properties" && k != "required" do
      input.putAdditionalProperty(k, JsonValue.from(Json.toJava(v)))
    Tool.builder().name(t.name).description(t.description).inputSchema(input.build()).build()

  private def params(system: SystemPrompt, history: List[Msg], tools: List[ToolSpec]): MessageCreateParams =
    // Two cache breakpoints: the system prompt, which is large and the same for the
    // whole session, and the last user-role message of the history, so each round
    // reads the previous round's prefix from the cache and writes only what was added.
    val cache = CacheControlEphemeral.builder().build()
    val systemBlock = TextBlockParam.builder().text(system).cacheControl(cache).build()
    val b = MessageCreateParams.builder()
      .model(modelId)
      .maxTokens(maxOutputTokens.get.toLong)
      .systemOfTextBlockParams(List(systemBlock).asJava)
    Providers.headers(spec).foreach((n, v) => b.putAdditionalHeader(n, v))
    configuredThinking(b)
    // `temperature` is not applied: current Anthropic models reject sampling parameters.
    tools.foreach(t => b.addTool(toolUnion(t)))
    if webSearch then
      settings.webSearchVersion.getOrElse("20260209") match
        case "20250305" => b.addTool(ToolUnion.ofWebSearchTool20250305(WebSearchTool20250305.builder().build()))
        case _ => b.addTool(ToolUnion.ofWebSearchTool20260209(WebSearchTool20260209.builder().build()))
    def addUserText(text: String, mark: Boolean): Unit =
      val block = TextBlockParam.builder().text(text)
      if mark then block.cacheControl(cache)
      b.addUserMessageOfBlockParams(List(ContentBlockParam.ofText(block.build())).asJava)
    // Only a user-role block can carry the breakpoint, and a round resumed after a
    // server-side pause re-sends a history that ends with an assistant message.
    val last = AnthropicModel.cacheBreakpoint(history)
    history.zipWithIndex.foreach: (msg, i) =>
      val mark = i == last
      msg match
        case Msg.User(text) => addUserText(text, mark)
        case Msg.Continuation(text) => addUserText(text, mark)
        case Msg.Assistant(text, calls, native) =>
          replay[MessageParam](native) match
            case Some(turn) => b.addMessage(turn)
            case None =>
              val blocks = List.newBuilder[ContentBlockParam]
              if text.nonEmpty then blocks += ContentBlockParam.ofText(text)
              calls.foreach: c =>
                val input = ToolUseBlockParam.Input.builder()
                Json.parseObject(c.arguments).value
                  .foreach((k, v) => input.putAdditionalProperty(k, JsonValue.from(Json.toJava(v))))
                blocks += ContentBlockParam.ofToolUse(
                  ToolUseBlockParam.builder().id(c.id).name(c.name).input(input.build()).build()
                )
              val content = blocks.result()
              if content.nonEmpty then
                b.addMessage(
                  MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(content.asJava).build()
                )
        case Msg.ToolResults(results) =>
          val blocks = results.zipWithIndex.map: (r, j) =>
            val block = ToolResultBlockParam.builder().toolUseId(r.callId).content(r.output).isError(r.isError)
            if mark && j == results.length - 1 then block.cacheControl(cache)
            ContentBlockParam.ofToolResult(block.build())
          b.addUserMessageOfBlockParams(blocks.asJava)
    b.build()

  private def extract(m: Message): Completion =
    val text = StringBuilder()
    val calls = List.newBuilder[ToolCall]
    m.content().asScala.foreach: block =>
      block.text().toScala.foreach(t => text.append(t.text()))
      block.toolUse().toScala.foreach: tu =>
        val json =
          try ujson.write(Json.fromJava(tu._input().convert(classOf[java.util.Map[String, AnyRef]])))
          catch case _: Exception => "{}"
        calls += ToolCall(tu.id(), tu.name(), json)
    val stop = m.stopReason().toScala.map(_.toString).getOrElse("end_turn").toLowerCase(Locale.ROOT)
    val toolCalls = calls.result()
    val lastBlock = m.content().asScala.lastOption
    val paused = toolCalls.isEmpty &&
      lastBlock.exists(b => b.serverToolUse().isPresent || b.webSearchToolResult().isPresent)
    val status = CompletionStop.fromReason(stop, paused)
    Completion(text.toString, toolCalls, Some(NativeTurn(providerKey, ref, m.toParam())), usageOf(m), stop, status)

  def complete(
    system: SystemPrompt,
    history: List[Msg],
    tools: List[ToolSpec],
    sink: StreamSink,
    cancelled: () => Boolean
  ): Completion =
    withWebSearchFallback(sink): sink =>
      val acc = MessageAccumulator.create()
      val stream = streamingClient.async().messages().createStreaming(params(system, history, tools))
      ModelRequest.awaitStream(() => stream.close()) {
        stream.subscribe { ev =>
          if cancelled() then throw CancelledException()
          acc.accumulate(ev)
          ev.contentBlockStart().toScala.foreach: start =>
            val block = start.contentBlock()
            if block.serverToolUse().isPresent then sink.note("web search")
            else if block.text().isPresent then sink.note("") // a new text block
          ev.contentBlockDelta().toScala.foreach: d =>
            d.delta().text().toScala.foreach(t => sink.text(t.text()))
            d.delta().thinking().toScala.foreach(t => sink.thinking(t.thinking()))
        }.onCompleteFuture()
      }
      val m = acc.message()
      Debug.log {
        val blocks = m.content().asScala.map(_.toString.takeWhile(_ != '{'))
        s"anthropic stop=${m.stopReason().toScala} blocks=${blocks.mkString(",")}"
      }
      extract(m)

  /** Thinking and effort as the model is configured (adaptive thinking unless
    * `"thinking": false`, `output_config.effort` from the current [[effort]]). */
  private def configuredThinking(b: MessageCreateParams.Builder): Unit =
    if settings.thinking.getOrElse(true) then b.thinking(ThinkingConfigAdaptive.builder().build())
    effort.foreach: e =>
      b.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(e.toLowerCase(Locale.ROOT))).build())

  /** `input` is the whole prompt (Anthropic reports the uncached part, the
    * cache reads and the cache writes separately; OpenAI's `prompt_tokens`
    * already includes cached tokens), so the two providers read alike. */
  private def usageOf(m: Message): TokenUsage =
    val u = m.usage()
    val cacheRead = u.cacheReadInputTokens().toScala.map(_.longValue).getOrElse(0L)
    val cacheWrite = u.cacheCreationInputTokens().toScala.map(_.longValue).getOrElse(0L)
    TokenUsage(u.inputTokens() + cacheRead + cacheWrite, u.outputTokens(), cacheRead)

  def simple(system: Option[String], prompt: String, thinking: Boolean): Reply =
    val b = MessageCreateParams.builder().model(modelId)
      .maxTokens(settings.maxTokens.getOrElse(AnthropicModel.SimpleMaxTokens).toLong).addUserMessage(prompt)
    Providers.headers(spec).foreach((n, v) => b.putAdditionalHeader(n, v))
    system.foreach(b.system)
    if thinking then configuredThinking(b) else b.thinking(ThinkingConfigDisabled.builder().build())
    val m = client.messages().create(b.build())
    Reply(m.content().asScala.flatMap(_.text().toScala).map(_.text()).mkString, usageOf(m))

  /** `GET /v1/models`, with the display name, input limit, effort levels and
    * whether adaptive thinking is supported (the only kind this adapter asks for).
    * Capabilities a response leaves out are left to the defaults. */
  private[llm] def listModels(): List[ModelSpec] =
    val options = RequestOptions.builder().timeout(Providers.ListTimeout).build()
    val params = ModelListParams.builder().limit(1000L).build()
    def optional[T](read: => T): Option[T] = Try(read).toOption
    client.models().list(params, options).autoPager().asScala.toList.map: info =>
      val capabilities = optional(info.capabilities().toScala).flatten
      val efforts = capabilities.flatMap: c =>
        optional:
          val e = c.effort()
          if !e.supported() then Nil
          else
            val levels = List(
              "low" -> Some(e.low()),
              "medium" -> Some(e.medium()),
              "high" -> Some(e.high()),
              "xhigh" -> e.xhigh().toScala,
              "max" -> Some(e.max()),
            )
            levels.collect { case (level, Some(s)) if s.supported() => level }
      val adaptive =
        capabilities.flatMap(c => optional(c.thinking().supported() && c.thinking().types().adaptive().supported()))
      spec.listed(
        info.id(),
        spec.settings.copy(
          efforts = efforts,
          thinking = adaptive.filterNot(identity),
          contextWindow = info.maxInputTokens().toScala.flatMap(n => Tokens.from(n)),
          displayName = optional(info.displayName().linesIterator.nextOption()).flatten.map(_.trim).filter(_.nonEmpty),
        ),
      )

private[atc] object AnthropicModel:
  private val DefaultMaxTokens = 32000
  /** The output limit of a one-shot call whose model configures none. */
  private val SimpleMaxTokens = 16000

  /** Where the second cache breakpoint goes: the index of the last user-role
    * message (a request, a continuation or tool results), or `-1` when the
    * history holds none. The system block carries the first breakpoint. */
  def cacheBreakpoint(history: List[Msg]): Int =
    history.lastIndexWhere {
      case _: Msg.Assistant => false
      case _ => true
    }
