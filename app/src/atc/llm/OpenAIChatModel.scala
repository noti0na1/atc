package atc.llm

import atc.config.ModelSpec

import com.openai.core.JsonValue
import com.openai.helpers.ChatCompletionAccumulator
import com.openai.models.{FunctionDefinition, FunctionParameters, ReasoningEffort}
import com.openai.models.chat.completions.*
import com.openai.models.completions.CompletionUsage

import java.util.Locale
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** OpenAI Chat Completions API. Also the adapter for any OpenAI-compatible
  * server (Ollama, vLLM, LM Studio, OpenRouter, ...) reached through `baseUrl`. */
final class OpenAIChatModel(spec: ModelSpec) extends OpenAIShapedModel(spec):
  val providerKey: String = "openai"

  private def reasoningEffort(thinking: Boolean): Option[ReasoningEffort] =
    requestEffort(thinking).map(ReasoningEffort.of)

  private def functionTool(t: ToolSpec): ChatCompletionFunctionTool =
    val schema = ujson.read(t.parametersJson)
    val params = FunctionParameters.builder()
    schema.obj.foreach((k, v) => params.putAdditionalProperty(k, JsonValue.from(Json.toJava(v))))
    ChatCompletionFunctionTool.builder()
      .function(FunctionDefinition.builder().name(t.name).description(t.description).parameters(params.build()).build())
      .build()

  private def params(system: SystemPrompt, history: List[Msg], tools: List[ToolSpec]): ChatCompletionCreateParams =
    val b = ChatCompletionCreateParams.builder().model(modelId).addSystemMessage(system)
    b.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
    Providers.headers(spec).foreach((n, v) => b.putAdditionalHeader(n, v))
    settings.maxTokens.foreach(n => b.maxCompletionTokens(n.toLong))
    settings.temperature.foreach(b.temperature)
    reasoningEffort(thinking = true).foreach(b.reasoningEffort)
    thinkingSwitch(thinking = true).foreach(b.putAdditionalBodyProperty("thinking", _))
    tools.foreach(t => b.addTool(functionTool(t)))
    if webSearch then b.webSearchOptions(ChatCompletionCreateParams.WebSearchOptions.builder().build())
    history.foreach:
      case Msg.User(text) => b.addUserMessage(text)
      case Msg.Continuation(text) => b.addUserMessage(text)
      case Msg.Assistant(text, calls, native) =>
        replay[ChatCompletionAssistantMessageParam](native) match
          case Some(turn) => b.addMessage(turn)
          case None =>
            val ab = ChatCompletionAssistantMessageParam.builder()
            if text.nonEmpty then ab.content(text)
            calls.foreach: c =>
              val function =
                ChatCompletionMessageFunctionToolCall.Function.builder().name(c.name).arguments(c.arguments).build()
              ab.addToolCall(ChatCompletionMessageFunctionToolCall.builder().id(c.id).function(function).build())
            // A resumed pause leaves an empty assistant turn behind; without its native
            // form (a restored session) there is nothing to send for it.
            if text.nonEmpty || calls.nonEmpty then b.addMessage(ab.build())
      case Msg.ToolResults(results) =>
        results.foreach: r =>
          b.addMessage(ChatCompletionToolMessageParam.builder().toolCallId(r.callId).content(r.output).build())
    b.build()

  private def extract(c: ChatCompletion): Completion =
    val choice = c.choices().asScala.headOption
    val msg = choice.map(_.message())
    val text = msg.flatMap(_.content().toScala).getOrElse("")
    val calls = msg.flatMap(_.toolCalls().toScala).map(_.asScala.toList).getOrElse(Nil).flatMap: tc =>
      tc.function().toScala.map(f => ToolCall(f.id(), f.function().name(), f.function().arguments()))
    val stop = choice.map(_.finishReason().toString.toLowerCase(Locale.ROOT)).getOrElse("stop")
    val native = msg.map(m => NativeTurn(providerKey, ref, m.toParam()))
    Completion(text, calls, native, usageOf(c), stop, CompletionStop.fromReason(stop))

  def complete(
    system: SystemPrompt,
    history: List[Msg],
    tools: List[ToolSpec],
    sink: StreamSink,
    cancelled: () => Boolean
  ): Completion =
    withWebSearchFallback(sink): sink =>
      val feed = OpenAIChatModel.ChunkFeed()
      val stream = streamingClient.async().chat().completions().createStreaming(params(system, history, tools))
      ModelRequest.awaitStream(() => stream.close()) {
        stream.subscribe { chunk =>
          if cancelled() then throw CancelledException()
          if feed.accumulate(chunk) then
            chunk.choices().asScala.headOption.foreach: choice =>
              val delta = choice.delta()
              // Reasoning is not part of the official schema: DeepSeek sends `reasoning_content`,
              // OpenRouter `reasoning`. A mixed chunk ends reasoning before starting the answer.
              List("reasoning_content", "reasoning").iterator
                .flatMap(key => Option(delta._additionalProperties().get(key)).flatMap(_.asString().toScala))
                .filter(_.nonEmpty)
                .nextOption().foreach(sink.thinking)
              delta.content().toScala.filter(_.nonEmpty).foreach(sink.text)
        }.onCompleteFuture()
      }
      feed.partialCompletion.getOrElse(extract(feed.completion()))

  private def usageOf(c: ChatCompletion): TokenUsage =
    OpenAIChatModel.usageOf(c.usage().toScala)

  def simple(system: Option[String], prompt: String, thinking: Boolean): Reply =
    def request(effort: Option[ReasoningEffort]): ChatCompletion =
      val b = ChatCompletionCreateParams.builder().model(modelId)
      Providers.headers(spec).foreach((n, v) => b.putAdditionalHeader(n, v))
      system.foreach(b.addSystemMessage)
      b.addUserMessage(prompt)
      settings.maxTokens.foreach(n => b.maxCompletionTokens(n.toLong))
      settings.temperature.foreach(b.temperature)
      effort.foreach(b.reasoningEffort)
      thinkingSwitch(thinking).foreach(b.putAdditionalBodyProperty("thinking", _))
      client.chat().completions().create(b.build())
    val c = withEffortFallback(thinking, reasoningEffort(thinking))(request)
    Reply(c.choices().asScala.headOption.flatMap(_.message().content().toScala).getOrElse(""), usageOf(c))

object OpenAIChatModel:
  private def usageOf(usage: Option[CompletionUsage]): TokenUsage =
    usage.fold(TokenUsage()): u =>
      val cached = u.promptTokensDetails().toScala.flatMap(_.cachedTokens().toScala).map(_.longValue).getOrElse(0L)
      TokenUsage(u.promptTokens(), u.completionTokens(), cached)

  /** Feeds a stream's chunks to the SDK accumulator, which accepts the finish chunk and then
    * at most one choice-less chunk, and only if it carries the usage the completion lacks.
    * Providers differ in where the usage goes: OpenAI sends it in a choice-less chunk after
    * the finish chunk, DeepSeek puts it on the finish chunk itself, and OpenCode's gateway
    * does both for GLM (the finish chunk carries it and a usage chunk follows; a `cost`
    * line after `[DONE]` never reaches the SDK). So usage is never fed as it comes: the
    * last one seen is fed once, as the choice-less chunk the accumulator expects, when the
    * completion is taken. Chunks the accumulator would refuse are dropped: choice-less
    * chunks without usage, and chunks with choices after the finish chunk. One choice per
    * completion is assumed (the request never sets `n`). */
  private[atc] final class ChunkFeed:
    private val acc = ChatCompletionAccumulator.create()
    private var finished = false
    private var usage: Option[CompletionUsage] = None
    private var last: Option[ChatCompletionChunk] = None
    private val text = StringBuilder()

    /** Whether this chunk contributes a delta; ignored chunks must not reach the display either. */
    def accumulate(chunk: ChatCompletionChunk): Boolean =
      chunk.usage().toScala.foreach(u => usage = Some(u))
      if !chunk.choices().isEmpty && !finished then
        last = Some(chunk)
        chunk.choices().asScala.headOption.flatMap(_.delta().content().toScala).foreach(text.append(_))
        acc.accumulate(chunk.toBuilder().usage(java.util.Optional.empty[CompletionUsage]()).build())
        finished = chunk.choices().asScala.exists(_.finishReason().isPresent)
        true
      else false

    /** A clean EOF or [DONE] without finish_reason does not confirm that any tool call is complete. */
    def partialCompletion: Option[Completion] =
      Option.when(!finished)(
        Completion(text.toString, Nil, None, usageOf(usage), "stream_incomplete", CompletionStop.Incomplete)
      )

    /** The accumulated completion, with the usage if any was reported; throws when the
      * stream ended before its finish chunk. */
    def completion(): ChatCompletion =
      if finished then
        for u <- usage; c <- last do
          acc.accumulate(c.toBuilder().choices(java.util.List.of[ChatCompletionChunk.Choice]()).usage(u).build())
      acc.chatCompletion()
