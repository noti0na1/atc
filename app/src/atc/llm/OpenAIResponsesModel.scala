package atc.llm

import atc.Debug
import atc.config.ModelSpec

import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.errors.BadRequestException
import com.openai.helpers.ResponseAccumulator
import com.openai.models.{Reasoning, ReasoningEffort}
import com.openai.models.responses.*

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Using

/** OpenAI Responses API (official Java SDK), streaming, with the built-in
  * `web_search` tool when enabled. */
final class OpenAIResponsesModel(val spec: ModelSpec) extends ChatModel:
  val alias: String = spec.alias
  override val ref: String = spec.ref
  val modelId: String = spec.modelId
  val providerKey: String = "openai-responses"
  private val cfg = spec.settings
  val webSearch: Boolean = cfg.webSearch
  override val contextWindow: Option[Int] = cfg.contextWindow.map(_.toInt)

  private lazy val client: OpenAIClient = Providers.openAiClient(spec)
  /** Set once the model rejected `reasoning`: it takes no such parameter. */
  @volatile private var effortRejected = false

  /** The `reasoning` block for a call: as configured (effort and summary), or
    * the lowest effort the model takes for a non-thinking one. */
  private def reasoning(thinking: Boolean): Option[Reasoning] =
    if thinking then
      Option.when(cfg.reasoning.isDefined || cfg.reasoningSummary.isDefined) {
        val r = Reasoning.builder()
        cfg.reasoning.foreach(e => r.effort(ReasoningEffort.of(e.toLowerCase)))
        cfg.reasoningSummary.foreach(s => r.summary(Reasoning.Summary.of(s.toLowerCase)))
        r.build()
      }
    else if effortRejected || cfg.thinking.isDefined then None
    else
      Providers.lowestEffort(modelId, cfg.reasoning.isDefined).map(e =>
        Reasoning.builder().effort(ReasoningEffort.of(e)).build()
      )

  /** `thinking: {"type": "enabled"|"disabled"}`, the switch of the
    * OpenAI-compatible vendors that have one (DeepSeek, GLM, Kimi, MiniMax).
    * Sent only when the config sets `thinking` for the model: OpenAI itself
    * rejects the parameter. A non-thinking call always says `disabled`. */
  private def thinkingSwitch(thinking: Boolean): Option[JsonValue] =
    cfg.thinking.map(on => Providers.thinkingSwitch(thinking && on))

  private def functionTool(t: ToolSpec): Tool =
    val schema = ujson.read(t.parametersJson)
    val params = FunctionTool.Parameters.builder()
    schema.obj.foreach((k, v) => params.putAdditionalProperty(k, JsonValue.from(Json.toJava(v))))
    Tool.ofFunction(
      FunctionTool.builder().name(t.name).description(t.description).parameters(params.build()).strict(false).build()
    )

  private def outputItemsToInput(items: java.util.List[ResponseOutputItem]): List[ResponseInputItem] =
    items.asScala.toList.flatMap { it =>
      if it.isMessage then Some(ResponseInputItem.ofResponseOutputMessage(it.asMessage()))
      else if it.isFunctionCall then Some(ResponseInputItem.ofFunctionCall(it.asFunctionCall()))
      else if it.isWebSearchCall then Some(ResponseInputItem.ofWebSearchCall(it.webSearchCall().get()))
      else if it.isReasoning then Some(ResponseInputItem.ofReasoning(it.reasoning().get()))
      else None
    }

  private def params(system: String, history: List[Msg], tools: List[ToolSpec]): ResponseCreateParams =
    val b = ResponseCreateParams.builder().model(modelId).instructions(system).store(false)
    cfg.maxTokens.foreach(n => b.maxOutputTokens(n.toLong))
    cfg.temperature.foreach(b.temperature)
    reasoning(thinking = true).foreach(b.reasoning)
    thinkingSwitch(thinking = true).foreach(b.putAdditionalBodyProperty("thinking", _))
    tools.foreach(t => b.addTool(functionTool(t)))
    if webSearch then b.addTool(Tool.ofWebSearch(WebSearchTool.builder().`type`(WebSearchTool.Type.WEB_SEARCH).build()))
    val input = List.newBuilder[ResponseInputItem]
    history.foreach {
      case Msg.User(text) =>
        input += ResponseInputItem.ofEasyInputMessage(
          EasyInputMessage.builder().role(EasyInputMessage.Role.USER).content(text).build()
        )
      case Msg.Assistant(text, calls, native) =>
        native match
          case Some(NativeTurn(`providerKey`, items: java.util.List[?])) =>
            input ++= outputItemsToInput(items.asInstanceOf[java.util.List[ResponseOutputItem]])
          case _ =>
            if text.nonEmpty then
              input += ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder().role(EasyInputMessage.Role.ASSISTANT).content(text).build()
              )
            calls.foreach { c =>
              input += ResponseInputItem.ofFunctionCall(
                ResponseFunctionToolCall.builder().callId(c.id).name(c.name).arguments(c.arguments).build()
              )
            }
      case Msg.ToolResults(results) =>
        results.foreach { r =>
          input += ResponseInputItem.ofFunctionCallOutput(
            ResponseInputItem.FunctionCallOutput.builder().callId(r.callId).output(r.output).build()
          )
        }
    }
    b.inputOfResponse(input.result().asJava)
    b.build()

  private def extract(r: Response): Completion =
    val text = StringBuilder()
    val calls = List.newBuilder[ToolCall]
    r.output().asScala.foreach { it =>
      if it.isMessage then
        it.asMessage().content().asScala.foreach(c => c.outputText().toScala.foreach(t => text.append(t.text())))
      else if it.isFunctionCall then
        val fc = it.asFunctionCall()
        calls += ToolCall(fc.callId(), fc.name(), fc.arguments())
    }
    val usage = usageOf(r)
    val stop = r.status().toScala.map(_.toString.toLowerCase).getOrElse("completed")
    // A response whose last item is a server-side tool call (web search) was
    // cut off by the server; the model has not produced its answer yet.
    val lastItem = r.output().asScala.lastOption
    val unfinished = calls.result().isEmpty && lastItem.exists(i => !i.isMessage && !i.isFunctionCall && !i.isReasoning)
    Completion(text.toString, calls.result(), Some(NativeTurn(providerKey, r.output())), usage, stop, unfinished)

  def complete(
    system: String,
    history: List[Msg],
    tools: List[ToolSpec],
    sink: StreamSink,
    cancelled: () => Boolean
  ): Completion =
    val acc = ResponseAccumulator.create()
    Using.resource(client.responses().createStreaming(params(system, history, tools))) { stream =>
      Streaming.drain(stream.stream(), cancelled) { ev =>
        acc.accumulate(ev)
        ev.outputItemAdded().toScala.foreach { added =>
          val item = added.item()
          if item.isWebSearchCall then sink.note("web search")
          else if item.isMessage then sink.note("")
        }
        ev.outputTextDelta().toScala.foreach(d => sink.text(d.delta()))
        ev.reasoningTextDelta().toScala.foreach(d => sink.thinking(d.delta()))
        ev.reasoningSummaryTextDelta().toScala.foreach(d => sink.thinking(d.delta()))
        ev.reasoningSummaryPartDone().toScala.foreach(_ => sink.thinking("\n\n"))
        ev.error().toScala.foreach(e => throw RuntimeException(s"OpenAI stream error: ${e.message()}"))
        ev.failed().toScala.foreach(f =>
          throw RuntimeException(
            s"OpenAI response failed: ${f.response().error().toScala.map(_.message()).getOrElse("unknown")}"
          )
        )
      }
    }
    val r = acc.response()
    Debug.log {
      val kinds = r.output().asScala.map(i =>
        if i.isMessage then "message"
        else if i.isFunctionCall then "function_call"
        else if i.isWebSearchCall then "web_search" else if i.isReasoning then "reasoning" else "other"
      )
      s"responses status=${r.status().toScala} incomplete=${r.incompleteDetails().toScala.map(_.toString)} items=${kinds.mkString(",")}"
    }
    extract(r)

  private def usageOf(r: Response): TokenUsage =
    r.usage().toScala.map { u =>
      // `input_tokens_details` is optional on the wire (the SDK throws when it is absent).
      val cached = scala.util.Try(u.inputTokensDetails().cachedTokens()).getOrElse(0L)
      TokenUsage(u.inputTokens(), u.outputTokens(), cached)
    }.getOrElse(TokenUsage())

  def simple(system: Option[String], prompt: String, thinking: Boolean): Reply =
    def request(reasoning: Option[Reasoning]): Response =
      val b = ResponseCreateParams.builder().model(modelId).input(prompt).store(false)
      system.foreach(b.instructions)
      reasoning.foreach(b.reasoning)
      thinkingSwitch(thinking).foreach(b.putAdditionalBodyProperty("thinking", _))
      client.responses().create(b.build())
    val rs = reasoning(thinking)
    val r =
      try request(rs)
      catch
        // A guessed lowest effort the model does not take: remember, and ask plainly.
        case _: BadRequestException if !thinking && rs.isDefined =>
          effortRejected = true
          request(None)
    val text = r.output().asScala.flatMap(it =>
      if it.isMessage then it.asMessage().content().asScala.flatMap(_.outputText().toScala.map(_.text())) else Nil
    ).mkString
    Reply(text, usageOf(r))
