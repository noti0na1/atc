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
final class AnthropicModel(val spec: ModelSpec) extends ChatModel:
  val alias: String = spec.alias
  override val ref: String = spec.ref
  val modelId: String = spec.modelId
  val providerKey: String = "anthropic"
  private val cfg = spec.settings
  val webSearch: Boolean = cfg.webSearch

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

  private def params(system: String, history: List[Msg], tools: List[ToolSpec]): MessageCreateParams =
    val b = MessageCreateParams.builder()
      .model(modelId)
      .maxTokens(cfg.maxTokens.map(_.toLong).getOrElse(32000L))
      // The system prompt (API reference + policy) is large and stable: cache it.
      .systemOfTextBlockParams(List(
        TextBlockParam.builder().text(system).cacheControl(CacheControlEphemeral.builder().build()).build()
      ).asJava)
    if cfg.thinking.getOrElse(true) then b.thinking(ThinkingConfigAdaptive.builder().build())
    cfg.reasoning.foreach(e =>
      b.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(e.toLowerCase)).build())
    )
    // `temperature` is not applied: current Anthropic models reject sampling parameters.
    tools.foreach(t => b.addTool(toolUnion(t)))
    if webSearch then
      cfg.webSearchVersion.getOrElse("20260209") match
        case "20250305" => b.addTool(ToolUnion.ofWebSearchTool20250305(WebSearchTool20250305.builder().build()))
        case _ => b.addTool(ToolUnion.ofWebSearchTool20260209(WebSearchTool20260209.builder().build()))
    history.foreach {
      case Msg.User(text) => b.addUserMessage(text)
      case Msg.Assistant(text, calls, native) =>
        native match
          case Some(NativeTurn(`providerKey`, p: MessageParam)) => b.addMessage(p)
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
        val blocks = results.map { r =>
          ContentBlockParam.ofToolResult(
            ToolResultBlockParam.builder().toolUseId(r.callId).content(r.output).isError(r.isError).build()
          )
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
    val u = m.usage()
    val usage =
      TokenUsage(u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().toScala.map(_.longValue).getOrElse(0L))
    val stop = m.stopReason().toScala.map(_.toString).getOrElse("end_turn").toLowerCase
    val lastBlock = m.content().asScala.lastOption
    val unfinished = stop == "pause_turn" ||
      (calls.result().isEmpty && lastBlock.exists(b =>
        b.serverToolUse().isPresent || b.webSearchToolResult().isPresent
      ))
    Completion(text.toString, calls.result(), Some(NativeTurn(providerKey, m.toParam())), usage, stop, unfinished)

  def complete(
    system: String,
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

  def simple(system: Option[String], prompt: String): String =
    val b = MessageCreateParams.builder().model(
      modelId
    ).maxTokens(cfg.maxTokens.map(_.toLong).getOrElse(16000L)).addUserMessage(prompt)
    system.foreach(b.system)
    val m = client.messages().create(b.build())
    m.content().asScala.flatMap(_.text().toScala).map(_.text()).mkString
