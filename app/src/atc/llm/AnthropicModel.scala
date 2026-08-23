package atc.llm

import atc.Debug
import atc.config.ModelSpec

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.*

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Using

/** Anthropic Messages API (official Java SDK), streaming, with the
  * server-side web-search tool when enabled. */
final class AnthropicModel(spec: ModelSpec) extends SpecModel(spec):
  val providerKey: String = "anthropic"
  override val maxOutputTokens: Option[Int] = Some(cfg.maxTokens.getOrElse(32000))

  private lazy val client: AnthropicClient =
    val b = AnthropicOkHttpClient.builder().timeout(Providers.RequestTimeout)
    spec.apiKey match
      case Some(key) => b.apiKey(key)
      case None => b.fromEnv()
    spec.baseUrl.foreach(b.baseUrl)
    b.build()

  private def toolUnion(t: ToolSpec): Tool =
    val schema = ujson.read(t.parametersJson)
    val props = Tool.InputSchema.Properties.builder()
    schema.obj.get("properties").foreach(_.obj.foreach((k, v) =>
      props.putAdditionalProperty(k, JsonValue.from(Json.toJava(v)))
    ))
    val is = Tool.InputSchema.builder().properties(props.build())
    schema.obj.get("required").foreach(r => is.required(r.arr.map(_.str).toList.asJava))
    Tool.builder().name(t.name).description(t.description).inputSchema(is.build()).build()

  private def params(system: SystemPrompt, history: List[Msg], tools: List[ToolSpec]): MessageCreateParams =
    // Two cache breakpoints: the system prompt (large, the same for the whole
    // session) and the last message of the history, so each round reads the
    // previous round's prefix from the cache and writes only what was added.
    val cache = CacheControlEphemeral.builder().build()
    val systemBlock = TextBlockParam.builder().text(system.text).cacheControl(cache).build()
    val b = MessageCreateParams.builder()
      .model(modelId)
      .maxTokens(cfg.maxTokens.map(_.toLong).getOrElse(32000L))
      .systemOfTextBlockParams(List(systemBlock).asJava)
    configuredThinking(b)
    // `temperature` is not applied: current Anthropic models reject sampling parameters.
    tools.foreach(t => b.addTool(toolUnion(t)))
    if webSearch then
      cfg.webSearchVersion.getOrElse("20260209") match
        case "20250305" => b.addTool(ToolUnion.ofWebSearchTool20250305(WebSearchTool20250305.builder().build()))
        case _ => b.addTool(ToolUnion.ofWebSearchTool20260209(WebSearchTool20260209.builder().build()))
    val last = history.length - 1
    history.zipWithIndex.foreach { (msg, i) =>
      // The request always ends with a user-role message (a request or tool results): mark it.
      val mark = i == last
      msg match
        case Msg.User(text) =>
          val block = TextBlockParam.builder().text(text)
          if mark then block.cacheControl(cache)
          b.addUserMessageOfBlockParams(List(ContentBlockParam.ofText(block.build())).asJava)
        case Msg.Continuation(text) =>
          val block = TextBlockParam.builder().text(text)
          if mark then block.cacheControl(cache)
          b.addUserMessageOfBlockParams(List(ContentBlockParam.ofText(block.build())).asJava)
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
    val stop = m.stopReason().toScala.map(_.toString).getOrElse("end_turn").toLowerCase
    val lastBlock = m.content().asScala.lastOption
    val unfinished = stop == "pause_turn" || Completion.isTruncatedStop(stop) ||
      (calls.result().isEmpty && lastBlock.exists(b =>
        b.serverToolUse().isPresent || b.webSearchToolResult().isPresent
      ))
    Completion(text.toString, calls.result(), Some(NativeTurn(providerKey, ref, m.toParam())), usage, stop, unfinished)

  def complete(
    system: SystemPrompt,
    history: List[Msg],
    tools: List[ToolSpec],
    sink: StreamSink,
    cancelled: () => Boolean
  ): Completion =
    val acc = MessageAccumulator.create()
    Using.resource(client.messages().createStreaming(params(system, history, tools))) { stream =>
      Streaming.drain(stream.stream(), cancelled) { ev =>
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
      }
    }
    val m = acc.message()
    Debug.log(
      s"anthropic stop=${m.stopReason().toScala} blocks=${m.content().asScala.map(b => b.toString.takeWhile(_ != '{')).mkString(",")}"
    )
    extract(m)

  /** Thinking and effort as the model is configured (adaptive thinking unless
    * `"thinking": false`, `output_config.effort` from `reasoning`). */
  private def configuredThinking(b: MessageCreateParams.Builder): Unit =
    if cfg.thinking.getOrElse(true) then b.thinking(ThinkingConfigAdaptive.builder().build())
    cfg.reasoning.foreach(e =>
      b.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(e.toLowerCase)).build())
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
    system.foreach(b.system)
    if thinking then configuredThinking(b) else b.thinking(ThinkingConfigDisabled.builder().build())
    val m = client.messages().create(b.build())
    Reply(m.content().asScala.flatMap(_.text().toScala).map(_.text()).mkString, usageOf(m))
