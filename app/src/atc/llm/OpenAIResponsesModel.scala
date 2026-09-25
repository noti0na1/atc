package atc.llm

import atc.Debug
import atc.config.ModelSpec

import com.openai.core.JsonValue
import com.openai.helpers.ResponseAccumulator
import com.openai.models.{Reasoning, ReasoningEffort}
import com.openai.models.responses.*

import java.util.Locale
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try

/** OpenAI Responses API (official Java SDK), streaming, with the built-in
  * `web_search` tool when enabled. */
class OpenAIResponsesModel(spec: ModelSpec) extends OpenAIShapedModel(spec):
  val providerKey: String = "openai-responses"

  /** The `reasoning` block for a call: the effort, and the configured summary
    * when the call thinks. */
  private def reasoning(thinking: Boolean): Option[Reasoning] =
    val effort = requestEffort(thinking)
    val summary = settings.reasoningSummary.filter(_ => thinking)
    Option.when(effort.isDefined || summary.isDefined):
      val r = Reasoning.builder()
      effort.foreach(e => r.effort(ReasoningEffort.of(e)))
      summary.foreach(s => r.summary(Reasoning.Summary.of(s.toLowerCase(Locale.ROOT))))
      r.build()

  private def functionTool(t: ToolSpec): Tool =
    val schema = ujson.read(t.parametersJson)
    val params = FunctionTool.Parameters.builder()
    schema.obj.foreach((k, v) => params.putAdditionalProperty(k, JsonValue.from(Json.toJava(v))))
    Tool.ofFunction(
      FunctionTool.builder().name(t.name).description(t.description).parameters(params.build()).strict(false).build()
    )

  private def outputItemsToInput(items: java.util.List[ResponseOutputItem]): List[ResponseInputItem] =
    items.asScala.toList.flatMap: it =>
      if it.isMessage then Some(ResponseInputItem.ofResponseOutputMessage(it.asMessage()))
      else if it.isFunctionCall then Some(ResponseInputItem.ofFunctionCall(it.asFunctionCall()))
      else if it.isWebSearchCall then Some(ResponseInputItem.ofWebSearchCall(it.asWebSearchCall()))
      else if it.isReasoning then Some(ResponseInputItem.ofReasoning(it.asReasoning()))
      else None

  private def params(system: SystemPrompt, history: List[Msg], tools: List[ToolSpec]): ResponseCreateParams =
    val b = ResponseCreateParams.builder().model(modelId).instructions(system).store(false)
    Providers.headers(spec).foreach((n, v) => b.putAdditionalHeader(n, v))
    // Stateless (store(false)) replay must carry the reasoning between calls: ask
    // for the encrypted reasoning content, or the replayed reasoning items are
    // invalid (backends answer HTTP 400 invalid_encrypted_content).
    b.addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)
    limits(b)
    reasoning(thinking = true).foreach(b.reasoning)
    thinkingSwitch(thinking = true).foreach(b.putAdditionalBodyProperty("thinking", _))
    tools.foreach(t => b.addTool(functionTool(t)))
    if webSearch then b.addTool(Tool.ofWebSearch(WebSearchTool.builder().`type`(WebSearchTool.Type.WEB_SEARCH).build()))
    val input = List.newBuilder[ResponseInputItem]
    def message(role: EasyInputMessage.Role, text: String): ResponseInputItem =
      ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder().role(role).content(text).build())
    history.foreach:
      case Msg.User(text) => input += message(EasyInputMessage.Role.USER, text)
      case Msg.Continuation(text) => input += message(EasyInputMessage.Role.USER, text)
      case Msg.Assistant(text, calls, native) =>
        replay[java.util.List[ResponseOutputItem]](native) match
          case Some(items) => input ++= outputItemsToInput(items)
          case None =>
            if text.nonEmpty then input += message(EasyInputMessage.Role.ASSISTANT, text)
            calls.foreach: c =>
              input += ResponseInputItem.ofFunctionCall(
                ResponseFunctionToolCall.builder().callId(c.id).name(c.name).arguments(c.arguments).build()
              )
      case Msg.ToolResults(results) =>
        results.foreach: r =>
          input += ResponseInputItem.ofFunctionCallOutput(
            ResponseInputItem.FunctionCallOutput.builder().callId(r.callId).output(r.output).build()
          )
    b.inputOfResponse(input.result().asJava)
    b.build()

  /** The configured output limit and sampling of a request. */
  protected def limits(b: ResponseCreateParams.Builder): Unit =
    settings.maxTokens.foreach(n => b.maxOutputTokens(n.toLong))
    settings.temperature.foreach(b.temperature)

  /** Send a one-shot request. */
  protected def send(params: ResponseCreateParams): Response = client.responses().create(params)

  private def extract(r: Response): Completion =
    val text = StringBuilder()
    val calls = List.newBuilder[ToolCall]
    r.output().asScala.foreach: it =>
      if it.isMessage then
        it.asMessage().content().asScala.foreach(c => c.outputText().toScala.foreach(t => text.append(t.text())))
      else if it.isFunctionCall then
        val fc = it.asFunctionCall()
        calls += ToolCall(fc.callId(), fc.name(), fc.arguments())
    // `status=incomplete` alone loses why generation stopped. Preserve the
    // reason so max-output truncation can resume while a content filter cannot.
    val incompleteReason = r.incompleteDetails().toScala.flatMap(_.reason().toScala).map(_.asString())
    val stop =
      incompleteReason.orElse(r.status().toScala.map(_.toString)).getOrElse("completed").toLowerCase(Locale.ROOT)
    // A response whose last item is a server-side tool call (web search) was
    // cut off by the server; the model has not produced its answer yet.
    val toolCalls = calls.result()
    val lastItem = r.output().asScala.lastOption
    val paused = toolCalls.isEmpty && lastItem.exists(i => !i.isMessage && !i.isFunctionCall && !i.isReasoning)
    val status = CompletionStop.fromReason(stop, paused)
    Completion(text.toString, toolCalls, Some(NativeTurn(providerKey, ref, r.output())), usageOf(r), stop, status)

  def complete(
    system: SystemPrompt,
    history: List[Msg],
    tools: List[ToolSpec],
    sink: StreamSink,
    cancelled: () => Boolean
  ): Completion =
    withWebSearchFallback(sink): sink =>
      val acc = OpenAIResponsesModel.Accumulator()
      val stream = streamingClient.async().responses().createStreaming(params(system, history, tools))
      ModelRequest.awaitStream(() => stream.close()) {
        stream.subscribe { ev =>
          if cancelled() then throw CancelledException()
          acc.accumulate(ev)
          ev.outputItemAdded().toScala.foreach: added =>
            val item = added.item()
            if item.isWebSearchCall then sink.note("web search")
            else if item.isMessage then sink.note("")
          ev.outputTextDelta().toScala.foreach(d => sink.text(d.delta()))
          ev.reasoningTextDelta().toScala.foreach(d => sink.thinking(d.delta()))
          ev.reasoningSummaryTextDelta().toScala.foreach(d => sink.thinking(d.delta()))
          ev.reasoningSummaryPartDone().toScala.foreach(_ => sink.thinking("\n\n"))
          ev.error().toScala.foreach(e => throw RuntimeException(s"OpenAI stream error: ${e.message()}"))
          ev.failed().toScala.foreach: f =>
            val why = f.response().error().toScala.map(_.message()).getOrElse("unknown")
            throw RuntimeException(s"OpenAI response failed: $why")
        }.onCompleteFuture()
      }
      val r = acc.response()
      Debug.log {
        val kinds = r.output().asScala.map: i =>
          if i.isMessage then "message"
          else if i.isFunctionCall then "function_call"
          else if i.isWebSearchCall then "web_search"
          else if i.isReasoning then "reasoning"
          else "other"
        val incomplete = r.incompleteDetails().toScala.map(_.toString)
        s"responses status=${r.status().toScala} incomplete=$incomplete items=${kinds.mkString(",")}"
      }
      extract(r)

  private def usageOf(r: Response): TokenUsage =
    r.usage().toScala.fold(TokenUsage()): u =>
      // `input_tokens_details` is optional on the wire (the SDK throws when it is absent).
      val cached = Try(u.inputTokensDetails().cachedTokens()).getOrElse(0L)
      TokenUsage(u.inputTokens(), u.outputTokens(), cached)

  def simple(system: Option[String], prompt: String, thinking: Boolean): Reply =
    def request(reasoning: Option[Reasoning]): Response =
      val b = ResponseCreateParams.builder().model(modelId).input(prompt).store(false)
      Providers.headers(spec).foreach((n, v) => b.putAdditionalHeader(n, v))
      system.foreach(b.instructions)
      limits(b)
      reasoning.foreach(b.reasoning)
      thinkingSwitch(thinking).foreach(b.putAdditionalBodyProperty("thinking", _))
      send(b.build())
    val r = withEffortFallback(thinking, reasoning(thinking))(request)
    val text = r.output().asScala.filter(_.isMessage)
      .flatMap(_.asMessage().content().asScala.flatMap(_.outputText().toScala.map(_.text())))
      .mkString
    Reply(text, usageOf(r))

object OpenAIResponsesModel:
  /** The SDK's accumulator, keeping the finished output items as well: the
    * ChatGPT backend streams them but ends with a `response.completed` whose
    * `output` is empty, as Codex expects. */
  final class Accumulator:
    private val acc = ResponseAccumulator.create()
    private val done = mutable.TreeMap[Long, ResponseOutputItem]()

    def accumulate(ev: ResponseStreamEvent): Unit =
      acc.accumulate(ev)
      ev.outputItemDone().toScala.foreach(d => done(d.outputIndex()) = d.item())

    def response(): Response =
      val r = acc.response()
      if !r.output().isEmpty || done.isEmpty then r else r.toBuilder().output(done.values.toList.asJava).build()
