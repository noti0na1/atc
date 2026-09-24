package atc.llm

import atc.Debug
import atc.config.{ModelSpec, Tokens}

import com.anthropic.client.{AnthropicClient, AnthropicClientImpl}
import com.anthropic.core.JsonValue
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.*

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Anthropic Messages API (official Java SDK), streaming, with the
  * server-side web-search tool when enabled. */
final class AnthropicModel(spec: ModelSpec) extends SpecModel(spec):
  val providerKey: String = "anthropic"
  private val DefaultMaxTokens = 32000
  protected def knownEfforts: List[String] = List("low", "medium", "high", "xhigh", "max")
  /** What the request asks for: the context fitter reserves exactly this. */
  override val maxOutputTokens: Option[Int] = Some(cfg.maxTokens.getOrElse(DefaultMaxTokens))

  private final case class Connection(client: AnthropicClient, transport: com.anthropic.core.http.HttpClient)
  private var openedClient: Option[Connection] = None
  private def connection: Connection = synchronized {
    openedClient.getOrElse {
      val backendBuilder = com.anthropic.backends.AnthropicBackend.builder()
      spec.apiKey match
        case Some(key) => backendBuilder.apiKey(key)
        case None => backendBuilder.fromEnv()
      spec.baseUrl.foreach(backendBuilder.baseUrl)
      val backend = backendBuilder.build()
      val timeout = com.anthropic.core.Timeout.builder().request(Providers.RequestTimeout).build()
      // The SDK builds and owns the OkHttp client (since 2.63 its transport takes no outside one);
      // cancellation goes through `ModelRequest.scopedTransport` instead of an OkHttp event listener.
      val transport = com.anthropic.client.okhttp.OkHttpClient.builder().timeout(timeout).backend(backend).build()
      val options = com.anthropic.core.ClientOptions.builder().httpClient(transport)
        .baseUrl(backend.baseUrl()).timeout(Providers.RequestTimeout)
      backend.applyCredentials(transport, options)
      val created = Connection(AnthropicClientImpl(options.build()), transport)
      openedClient = Some(created)
      created
    }
  }
  private def client: AnthropicClient = connection.client
  private def streamingClient: AnthropicClient =
    val opened = connection
    opened.client.withOptions(_.httpClient(ModelRequest.scopedTransport(opened.transport)))

  override def close(): Unit = synchronized {
    openedClient.foreach(_.client.close())
    openedClient = None
  }

  private def toolUnion(t: ToolSpec): Tool =
    val schema = ujson.read(t.parametersJson)
    val props = Tool.InputSchema.Properties.builder()
    schema.obj.get("properties").foreach(_.obj.foreach((k, v) =>
      props.putAdditionalProperty(k, JsonValue.from(Json.toJava(v)))
    ))
    val is = Tool.InputSchema.builder().properties(props.build())
    schema.obj.get("required").foreach(r => is.required(r.arr.map(_.str).toList.asJava))
    // The rest of the schema (`additionalProperties: false`, ...) as the OpenAI adapters send it.
    schema.obj.filterNot((k, _) => k == "type" || k == "properties" || k == "required").foreach((k, v) =>
      is.putAdditionalProperty(k, JsonValue.from(Json.toJava(v)))
    )
    Tool.builder().name(t.name).description(t.description).inputSchema(is.build()).build()

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
      cfg.webSearchVersion.getOrElse("20260209") match
        case "20250305" => b.addTool(ToolUnion.ofWebSearchTool20250305(WebSearchTool20250305.builder().build()))
        case _ => b.addTool(ToolUnion.ofWebSearchTool20260209(WebSearchTool20260209.builder().build()))
    def addUserText(text: String, mark: Boolean): Unit =
      val block = TextBlockParam.builder().text(text)
      if mark then block.cacheControl(cache)
      b.addUserMessageOfBlockParams(List(ContentBlockParam.ofText(block.build())).asJava)
    // Only a user-role block can carry the breakpoint, and a round resumed after a
    // server-side pause re-sends a history that ends with an assistant message.
    val last = AnthropicModel.cacheBreakpoint(history)
    history.zipWithIndex.foreach { (msg, i) =>
      val mark = i == last
      msg match
        case Msg.User(text) => addUserText(text, mark)
        case Msg.Continuation(text) => addUserText(text, mark)
        case Msg.Assistant(text, calls, native) =>
          native match
            case Some(n) if n.isFor(providerKey, ref) && n.payload.isInstanceOf[MessageParam] =>
              b.addMessage(n.payload.asInstanceOf[MessageParam])
            case _ =>
              val blocks = List.newBuilder[ContentBlockParam]
              if text.nonEmpty then blocks += ContentBlockParam.ofText(text)
              calls.foreach { c =>
                val input = ToolUseBlockParam.Input.builder()
                Json.parseObject(c.arguments).value.foreach((k, v) =>
                  input.putAdditionalProperty(k, JsonValue.from(Json.toJava(v)))
                )
                blocks += ContentBlockParam.ofToolUse(
                  ToolUseBlockParam.builder().id(c.id).name(c.name).input(input.build()).build()
                )
              }
              val bs = blocks.result()
              if bs.nonEmpty then
                b.addMessage(
                  MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(bs.asJava).build()
                )
        case Msg.ToolResults(results) =>
          val blocks = results.zipWithIndex.map { (r, j) =>
            val block = ToolResultBlockParam.builder().toolUseId(r.callId).content(r.output).isError(r.isError)
            if mark && j == results.length - 1 then block.cacheControl(cache)
            ContentBlockParam.ofToolResult(block.build())
          }
          b.addUserMessageOfBlockParams(blocks.asJava)
    }
    b.build()

  private def extract(m: Message): Completion =
    val text = StringBuilder()
    val calls = List.newBuilder[ToolCall]
    m.content().asScala.foreach { block =>
      block.text().toScala.foreach(t => text.append(t.text()))
      block.toolUse().toScala.foreach { tu =>
        val input = tu._input()
        val json =
          try ujson.write(Json.fromJava(input.convert(classOf[java.util.Map[String, AnyRef]])))
          catch case _: Exception => "{}"
        calls += ToolCall(tu.id(), tu.name(), json)
      }
    }
    val usage = usageOf(m)
    val stop = m.stopReason().toScala.map(_.toString).getOrElse("end_turn").toLowerCase(java.util.Locale.ROOT)
    val toolCalls = calls.result()
    val lastBlock = m.content().asScala.lastOption
    val paused = toolCalls.isEmpty &&
      lastBlock.exists(b => b.serverToolUse().isPresent || b.webSearchToolResult().isPresent)
    val status = CompletionStop.fromReason(stop, paused)
    Completion(text.toString, toolCalls, Some(NativeTurn(providerKey, ref, m.toParam())), usage, stop, status)

  def complete(
    system: SystemPrompt,
    history: List[Msg],
    tools: List[ToolSpec],
    sink: StreamSink,
    cancelled: () => Boolean
  ): Completion =
    withWebSearchFallback(sink) { sink =>
      val acc = MessageAccumulator.create()
      val stream = streamingClient.async().messages().createStreaming(params(system, history, tools))
      ModelRequest.awaitStream(() => stream.close()) {
        stream.subscribe { ev =>
          if cancelled() then throw CancelledException()
          acc.accumulate(ev)
          ev.contentBlockStart().toScala.foreach { start =>
            val cb = start.contentBlock()
            if cb.serverToolUse().isPresent then sink.note("web search")
            else if cb.text().isPresent then sink.note("") // new text block: separator
          }
          ev.contentBlockDelta().toScala.foreach { d =>
            d.delta().text().toScala.foreach(t => sink.text(t.text()))
            d.delta().thinking().toScala.foreach(t => sink.thinking(t.thinking()))
          }
        }.onCompleteFuture()
      }
      val m = acc.message()
      Debug.log(
        s"anthropic stop=${m.stopReason().toScala} blocks=${m.content().asScala.map(b => b.toString.takeWhile(_ != '{')).mkString(",")}"
      )
      extract(m)
    }

  /** Thinking and effort as the model is configured (adaptive thinking unless
    * `"thinking": false`, `output_config.effort` from the current [[effort]]). */
  private def configuredThinking(b: MessageCreateParams.Builder): Unit =
    if cfg.thinking.getOrElse(true) then b.thinking(ThinkingConfigAdaptive.builder().build())
    effort.foreach(e =>
      b.outputConfig(
        OutputConfig.builder().effort(OutputConfig.Effort.of(e.toLowerCase(java.util.Locale.ROOT))).build()
      )
    )

  /** `input` is the whole prompt (Anthropic reports the uncached part, the
    * cache reads and the cache writes separately; OpenAI's `prompt_tokens`
    * already includes cached tokens), so the two providers read alike. */
  private def usageOf(m: Message): TokenUsage =
    val u = m.usage()
    val cacheRead = u.cacheReadInputTokens().toScala.map(_.longValue).getOrElse(0L)
    val cacheWrite = u.cacheCreationInputTokens().toScala.map(_.longValue).getOrElse(0L)
    TokenUsage(u.inputTokens() + cacheRead + cacheWrite, u.outputTokens(), cacheRead)

  def simple(system: Option[String], prompt: String, thinking: Boolean): Reply =
    val b = MessageCreateParams.builder().model(
      modelId
    ).maxTokens(cfg.maxTokens.map(_.toLong).getOrElse(16000L)).addUserMessage(prompt)
    Providers.headers(spec).foreach((n, v) => b.putAdditionalHeader(n, v))
    system.foreach(b.system)
    if thinking then configuredThinking(b) else b.thinking(ThinkingConfigDisabled.builder().build())
    val m = client.messages().create(b.build())
    Reply(m.content().asScala.flatMap(_.text().toScala).map(_.text()).mkString, usageOf(m))

  /** `GET /v1/models`, with the display name, input limit, effort levels and
    * whether adaptive thinking is supported (the only kind this adapter asks for).
    * Capabilities a response leaves out are left to the defaults. */
  private[llm] def listModels(): List[ModelSpec] =
    val options = com.anthropic.core.RequestOptions.builder().timeout(Providers.ListTimeout).build()
    val params = com.anthropic.models.models.ModelListParams.builder().limit(1000L).build()
    def optional[T](read: => T): Option[T] = scala.util.Try(read).toOption
    client.models().list(params, options).autoPager().asScala.toList.map { info =>
      val capabilities = optional(info.capabilities().toScala).flatten
      val efforts = capabilities.flatMap { c =>
        optional {
          val e = c.effort()
          if !e.supported() then Nil
          else
            List(
              "low" -> Some(e.low()),
              "medium" -> Some(e.medium()),
              "high" -> Some(e.high()),
              "xhigh" -> e.xhigh().toScala,
              "max" -> Some(e.max())
            )
              .collect { case (level, Some(s)) if s.supported() => level }
        }
      }
      val adaptive = capabilities.flatMap(c =>
        optional(c.thinking().supported() && c.thinking().types().adaptive().supported())
      )
      spec.copy(
        alias = info.id(),
        modelId = info.id(),
        settings = spec.settings.copy(
          efforts = efforts,
          thinking = adaptive.filterNot(identity),
          contextWindow = info.maxInputTokens().toScala.map(n => Tokens(n.toInt)),
          displayName = optional(info.displayName().linesIterator.nextOption()).flatten.map(_.trim).filter(_.nonEmpty),
        ),
      )
    }

private[atc] object AnthropicModel:
  /** Where the second cache breakpoint goes: the index of the last user-role
    * message (a request, a continuation or tool results), or `-1` when the
    * history holds none. The system block carries the first breakpoint. */
  def cacheBreakpoint(history: List[Msg]): Int =
    history.lastIndexWhere {
      case _: Msg.Assistant => false
      case _ => true
    }
