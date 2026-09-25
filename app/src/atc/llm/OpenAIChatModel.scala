package atc.llm

import atc.config.{ModelSpec, ReasoningStyle}

import com.openai.core.{JsonField, JsonMissing, JsonValue}
import com.openai.helpers.ChatCompletionAccumulator
import com.openai.models.{FunctionDefinition, FunctionParameters, ReasoningEffort}
import com.openai.models.chat.completions.*
import com.openai.models.completions.CompletionUsage

import java.util.Locale
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** OpenAI Chat Completions API. Also the adapter for any OpenAI-compatible
  * server (Ollama, vLLM, LM Studio, OpenRouter, ...) reached through `baseUrl`. */
final class OpenAIChatModel(spec: ModelSpec) extends OpenAIShapedModel(spec):
  val providerKey: String = "openai"

  private def reasoningEffort(thinking: Boolean): Option[ReasoningEffort] =
    requestEffort(thinking).map(ReasoningEffort.of)

  /** The provider's `reasoningStyle` fragment for a call, which then goes in place of
    * `reasoning_effort`: `request` when the call reasons, `requestOff` when it does not. */
  private def styleFragment(thinking: Boolean): Option[ujson.Value] =
    spec.reasoningStyle.flatMap(s => if thinking then s.request else s.requestOff)
      .map(ReasoningStyle.withEffort(_, if thinking then requestEffort(thinking) else None))

  private def putFragment(b: ChatCompletionCreateParams.Builder, fragment: ujson.Value): Unit =
    fragment.obj.foreach((key, value) => b.putAdditionalBodyProperty(key, JsonValue.from(Json.toJava(value))))

  /** The reasoning tags of the provider's `reasoningStyle`, when it writes reasoning into the text. */
  private val tags: Option[(String, String)] =
    spec.reasoningStyle.flatMap(_.tags).collect { case List(open, close) => (open, close) }

  /** `text` without the reasoning between the provider's tags. */
  private def answer(text: String): String = tags.fold(text)((open, close) => OpenAIChatModel.answer(text, open, close))

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
    styleFragment(thinking = true) match
      case Some(fragment) => putFragment(b, fragment)
      case None => reasoningEffort(thinking = true).foreach(b.reasoningEffort)
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

  private def extract(c: ChatCompletion, extras: Map[Long, JsonValue]): Completion =
    val choice = c.choices().asScala.headOption
    val msg = choice.map(_.message())
    val text = answer(msg.flatMap(_.content().toScala).getOrElse(""))
    val calls = msg.flatMap(_.toolCalls().toScala).map(_.asScala.toList).getOrElse(Nil).flatMap: tc =>
      tc.function().toScala.map(f => ToolCall(f.id(), f.function().name(), f.function().arguments()))
    val stop = choice.map(_.finishReason().toString.toLowerCase(Locale.ROOT)).getOrElse("stop")
    val native = msg.map(m => NativeTurn(providerKey, ref, OpenAIChatModel.replayable(m.toParam(), text, extras)))
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
      val split = tags.map((open, close) => OpenAIChatModel.TagSplitter(open, close))
      def show(parts: List[(Boolean, String)]) =
        parts.foreach((thinking, t) => if thinking then sink.thinking(t) else sink.text(t))
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
              delta.content().toScala.filter(_.nonEmpty).foreach: text =>
                split.fold(sink.text(text))(s => show(s.push(text)))
        }.onCompleteFuture()
      }
      split.foreach(s => show(s.finish()))
      feed.partialCompletion.map(c => c.copy(text = answer(c.text)))
        .getOrElse(extract(feed.completion(), feed.toolExtras))

  private def usageOf(c: ChatCompletion): TokenUsage =
    OpenAIChatModel.usageOf(c.usage().toScala)

  def simple(system: Option[String], prompt: String, thinking: Boolean): Reply =
    val fragment = styleFragment(thinking)
    def request(effort: Option[ReasoningEffort]): ChatCompletion =
      val b = ChatCompletionCreateParams.builder().model(modelId)
      fragment.foreach(putFragment(b, _))
      Providers.headers(spec).foreach((n, v) => b.putAdditionalHeader(n, v))
      system.foreach(b.addSystemMessage)
      b.addUserMessage(prompt)
      settings.maxTokens.foreach(n => b.maxCompletionTokens(n.toLong))
      settings.temperature.foreach(b.temperature)
      effort.foreach(b.reasoningEffort)
      thinkingSwitch(thinking).foreach(b.putAdditionalBodyProperty("thinking", _))
      client.chat().completions().create(b.build())
    val c = withEffortFallback(thinking, if fragment.isDefined then None else reasoningEffort(thinking))(request)
    Reply(answer(c.choices().asScala.headOption.flatMap(_.message().content().toScala).getOrElse("")), usageOf(c))

object OpenAIChatModel:
  /** An assistant turn as it is sent again: its answer `text`, without reasoning written
    * between tags. A turn of only tool calls has no text, and the SDK writes absent fields
    * as `null`, which Gemini refuses where it expects a string: they are left out. Each call
    * gets back the `extra_content` its stream carried (Gemini's thought signature, which
    * Gemini requires with the call). */
  private[atc] def replayable(
    turn: ChatCompletionAssistantMessageParam,
    text: String,
    extras: Map[Long, JsonValue],
  ): ChatCompletionAssistantMessageParam =
    def absent[T]: JsonField[T] = JsonMissing.of().asInstanceOf[JsonField[T]]
    val b = turn.toBuilder()
    if text.nonEmpty then b.content(text) else b.content(absent)
    if turn._refusal().isNull then b.refusal(absent)
    if turn._audio().isNull then b.audio(absent)
    if turn._functionCall().isNull then b.functionCall(absent)
    turn.toolCalls().toScala.foreach: calls =>
      val restored = calls.asScala.toList.zipWithIndex.map: (call, i) =>
        val withExtra =
          for extra <- extras.get(i.toLong); f <- call.function().toScala
          yield ChatCompletionMessageToolCall.ofFunction(f.toBuilder().putAdditionalProperty(
            "extra_content",
            extra
          ).build())
        withExtra.getOrElse(call)
      b.toolCalls(restored.asJava)
    b.build()

  private def usageOf(usage: Option[CompletionUsage]): TokenUsage =
    usage.fold(TokenUsage()): u =>
      val cached = u.promptTokensDetails().toScala.flatMap(_.cachedTokens().toScala).map(_.longValue).getOrElse(0L)
      TokenUsage(u.promptTokens(), u.completionTokens(), cached)

  /** Splits streamed text into reasoning, between `open` and `close`, and answer. A tag
    * may arrive split across chunks, so a tail that could begin one waits for the next. */
  private[atc] final class TagSplitter(open: String, close: String):
    private var inside = false
    private var held = ""

    /** The parts of the text so far that are decided, each `true` when it is reasoning. */
    def push(text: String): List[(Boolean, String)] =
      val parts = List.newBuilder[(Boolean, String)]
      var rest = held + text
      var more = true
      while more do
        val tag = if inside then close else open
        val at = rest.indexOf(tag)
        if at >= 0 then
          if at > 0 then parts += inside -> rest.take(at)
          rest = rest.drop(at + tag.length)
          inside = !inside
        else
          val keep = (tag.length - 1 to 1 by -1).find(n => rest.endsWith(tag.take(n))).getOrElse(0)
          if rest.length > keep then parts += inside -> rest.dropRight(keep)
          held = rest.takeRight(keep)
          more = false
      parts.result()

    /** What was held back, once the stream has ended. */
    def finish(): List[(Boolean, String)] =
      val last = Option.when(held.nonEmpty)(inside -> held).toList
      held = ""
      last

  /** The answer in `text`: what is not between `open` and `close`. */
  private[atc] def answer(text: String, open: String, close: String): String =
    val split = TagSplitter(open, close)
    (split.push(text) ++ split.finish()).collect { case (false, part) => part }.mkString

  /** Feeds a stream's chunks to the SDK accumulator, which accepts the finish chunk and then
    * at most one choice-less chunk, and only if it carries the usage the completion lacks.
    * Providers differ in where the usage goes: OpenAI sends it in a choice-less chunk after
    * the finish chunk, DeepSeek puts it on the finish chunk itself, and OpenCode's gateway
    * does both for GLM (the finish chunk carries it and a usage chunk follows; a `cost`
    * line after `[DONE]` never reaches the SDK). So usage is never fed as it comes: the
    * last one seen is fed once, as the choice-less chunk the accumulator expects, when the
    * completion is taken. Chunks the accumulator would refuse are dropped: choice-less
    * chunks without usage, and chunks with choices after the finish chunk. One choice per
    * completion is assumed (the request never sets `n`).
    *
    * Gemini sends each tool call whole and without the `index` the accumulator requires
    * to join a call's fragments; such a fragment is numbered here (see [[numbered]]). */
  private[atc] final class ChunkFeed:
    private val acc = ChatCompletionAccumulator.create()
    private var finished = false
    private var usage: Option[CompletionUsage] = None
    private var last: Option[ChatCompletionChunk] = None
    private val text = StringBuilder()
    /** The tool calls the stream has numbered so far. */
    private var calls = 0L
    private val extras = mutable.Map[Long, JsonValue]()

    /** Whether this chunk contributes a delta; ignored chunks must not reach the display either. */
    def accumulate(chunk: ChatCompletionChunk): Boolean =
      chunk.usage().toScala.foreach(u => usage = Some(u))
      if !chunk.choices().isEmpty && !finished then
        last = Some(chunk)
        chunk.choices().asScala.headOption.flatMap(_.delta().content().toScala).foreach(text.append(_))
        acc.accumulate(numbered(chunk).toBuilder().usage(java.util.Optional.empty[CompletionUsage]()).build())
        finished = chunk.choices().asScala.exists(_.finishReason().isPresent)
        true
      else false

    /** `chunk` with every tool-call fragment numbered: one without an `index` starts the
      * next call when it has an id, and continues the last one otherwise. Each call's
      * `extra_content` is kept on the way ([[toolExtras]]). */
    private def numbered(chunk: ChatCompletionChunk): ChatCompletionChunk =
      // `asKnown`: Gemini's fragments may lack the field or carry it as null.
      def index(f: ChatCompletionChunk.Choice.Delta.ToolCall) = f._index().asKnown().toScala
      val choices = chunk.choices().asScala.toList
      val renumbered = choices.map: choice =>
        val fragments = choice.delta().toolCalls().toScala.map(_.asScala.toList).getOrElse(Nil)
        val fixed = fragments.map: f =>
          val (i, numberedFragment) = index(f) match
            case Some(i) => (i.longValue, f)
            case None =>
              if f.id().isPresent || calls == 0 then calls += 1
              (calls - 1, f.toBuilder().index(calls - 1).build())
          calls = calls.max(i + 1)
          Option(f._additionalProperties().get("extra_content")).foreach(extra => extras(i) = extra)
          numberedFragment
        if fragments.lazyZip(fixed).forall(_ eq _) then choice
        else choice.toBuilder().delta(choice.delta().toBuilder().toolCalls(fixed.asJava).build()).build()
      if choices.lazyZip(renumbered).forall(_ eq _) then chunk else chunk.toBuilder().choices(renumbered.asJava).build()

    /** Each call's `extra_content`, by number: Gemini's thought signature, which the
      * accumulator drops and Gemini requires back with the call. */
    def toolExtras: Map[Long, JsonValue] = extras.toMap

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
