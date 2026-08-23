package atc.llm

import atc.config.ModelSpec

import com.openai.core.JsonValue
import com.openai.helpers.ChatCompletionAccumulator
import com.openai.models.{FunctionDefinition, FunctionParameters, ReasoningEffort}
import com.openai.models.chat.completions.*

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Using

/** OpenAI Chat Completions API — also the adapter for any OpenAI-compatible
  * server (Ollama, vLLM, LM Studio, OpenRouter, ...) via `baseUrl`. */
final class OpenAIChatModel(spec: ModelSpec) extends OpenAIShapedModel(spec):
  val providerKey: String = "openai"

  /** The effort for a call: as configured, or the lowest the model takes for a
    * non-thinking one (not needed when the model has a thinking switch). */
  private def effort(thinking: Boolean): Option[ReasoningEffort] =
    (if thinking then cfg.reasoning else lowestEffort).map(x => ReasoningEffort.of(x.toLowerCase))

  private def functionTool(t: ToolSpec): ChatCompletionFunctionTool =
    val schema = ujson.read(t.parametersJson)
    val params = FunctionParameters.builder()
    schema.obj.foreach((k, v) => params.putAdditionalProperty(k, JsonValue.from(Json.toJava(v))))
    ChatCompletionFunctionTool.builder()
      .function(FunctionDefinition.builder().name(t.name).description(t.description).parameters(params.build()).build())
      .build()

  private def params(system: SystemPrompt, history: List[Msg], tools: List[ToolSpec]): ChatCompletionCreateParams =
    val b = ChatCompletionCreateParams.builder().model(modelId).addSystemMessage(system.text)
    cfg.maxTokens.foreach(n => b.maxCompletionTokens(n.toLong))
    cfg.temperature.foreach(b.temperature)
    effort(thinking = true).foreach(b.reasoningEffort)
    thinkingSwitch(thinking = true).foreach(b.putAdditionalBodyProperty("thinking", _))
    tools.foreach(t => b.addTool(functionTool(t)))
    if webSearch then b.webSearchOptions(ChatCompletionCreateParams.WebSearchOptions.builder().build())
    history.foreach {
      case Msg.User(text) => b.addUserMessage(text)
      case Msg.Continuation(text) => b.addUserMessage(text)
      case Msg.Assistant(text, calls, native) =>
        native match
          case Some(n) if n.isFor(providerKey, ref) && n.payload.isInstanceOf[ChatCompletionAssistantMessageParam] =>
            b.addMessage(n.payload.asInstanceOf[ChatCompletionAssistantMessageParam])
          case _ =>
            val ab = ChatCompletionAssistantMessageParam.builder()
            if text.nonEmpty then ab.content(text)
            calls.foreach { c =>
              ab.addToolCall(ChatCompletionMessageFunctionToolCall.builder().id(c.id)
                .function(
                  ChatCompletionMessageFunctionToolCall.Function.builder().name(c.name).arguments(c.arguments).build()
                )
                .build())
            }
            b.addMessage(ab.build())
      case Msg.ToolResults(results) =>
        results.foreach { r =>
          b.addMessage(ChatCompletionToolMessageParam.builder().toolCallId(r.callId).content(r.output).build())
        }
    }
    b.build()

  private def extract(c: ChatCompletion): Completion =
    val choice = c.choices().asScala.headOption
    val msg = choice.map(_.message())
    val text = msg.flatMap(_.content().toScala).getOrElse("")
    val calls = msg.flatMap(_.toolCalls().toScala).map(_.asScala.toList).getOrElse(Nil).flatMap { tc =>
      tc.function().toScala.map(f => ToolCall(f.id(), f.function().name(), f.function().arguments()))
    }
    val usage = usageOf(c)
    val stop = choice.map(_.finishReason().toString.toLowerCase).getOrElse("stop")
    Completion(
      text,
      calls,
      msg.map(m => NativeTurn(providerKey, ref, m.toParam())),
      usage,
      stop,
      unfinished = Completion.isTruncatedStop(stop)
    )

  def complete(
    system: SystemPrompt,
    history: List[Msg],
    tools: List[ToolSpec],
    sink: StreamSink,
    cancelled: () => Boolean
  ): Completion =
    val acc = ChatCompletionAccumulator.create()
    Using.resource(client.chat().completions().createStreaming(params(system, history, tools))) { stream =>
      Streaming.drain(stream.stream(), cancelled) { chunk =>
        acc.accumulate(chunk)
        chunk.choices().asScala.headOption.foreach { ch =>
          val delta = ch.delta()
          delta.content().toScala.foreach(sink.text)
          // Reasoning is not part of the official schema: DeepSeek sends `reasoning_content`, OpenRouter `reasoning`.
          List("reasoning_content", "reasoning").foreach { key =>
            Option(delta._additionalProperties().get(key)).flatMap(_.asString().toScala).foreach(sink.thinking)
          }
        }
      }
    }
    extract(acc.chatCompletion())

  private def usageOf(c: ChatCompletion): TokenUsage =
    c.usage().toScala.map { u =>
      val cached = u.promptTokensDetails().toScala.flatMap(_.cachedTokens().toScala).map(_.longValue).getOrElse(0L)
      TokenUsage(u.promptTokens(), u.completionTokens(), cached)
    }.getOrElse(TokenUsage())

  def simple(system: Option[String], prompt: String, thinking: Boolean): Reply =
    def request(effort: Option[ReasoningEffort]): ChatCompletion =
      val b = ChatCompletionCreateParams.builder().model(modelId)
      system.foreach(b.addSystemMessage)
      b.addUserMessage(prompt)
      effort.foreach(b.reasoningEffort)
      thinkingSwitch(thinking).foreach(b.putAdditionalBodyProperty("thinking", _))
      client.chat().completions().create(b.build())
    val c = withEffortFallback(thinking, effort(thinking))(request)
    Reply(c.choices().asScala.headOption.flatMap(_.message().content().toScala).getOrElse(""), usageOf(c))
