package atc.llm

import atc.config.ModelConfig

/** A tool the model may call. `parametersJson` is a JSON-schema object. */
case class ToolSpec(name: String, description: String, parametersJson: String)

case class ToolCall(id: String, name: String, arguments: String)

case class ToolResult(callId: String, output: String, isError: Boolean)

/** Provider-native replay data for an assistant turn (e.g. Anthropic content
  * blocks including server web-search results, OpenAI Responses output
  * items). Reused when the same provider continues the conversation; other
  * providers rebuild the turn from the neutral fields. */
case class NativeTurn(providerKey: String, payload: Any)

/** Provider-neutral conversation history. */
enum Msg:
  case User(text: String)
  case Assistant(text: String, toolCalls: List[ToolCall], native: Option[NativeTurn])
  case ToolResults(results: List[ToolResult])

case class TokenUsage(input: Long = 0, output: Long = 0, cacheRead: Long = 0):
  def +(o: TokenUsage): TokenUsage = TokenUsage(input + o.input, output + o.output, cacheRead + o.cacheRead)

/** `unfinished`: the provider stopped the response mid-work (e.g. after a
  * server-side tool call, Anthropic `pause_turn`); the agent should re-send
  * the history so the model resumes. */
case class Completion(
  text: String,
  toolCalls: List[ToolCall],
  native: Option[NativeTurn],
  usage: TokenUsage,
  stopReason: String,
  unfinished: Boolean = false
)

/** Thrown when the user cancels a streaming completion. */
class CancelledException extends RuntimeException("cancelled")

private[llm] object Streaming:
  /** Feed the events of a provider stream to `f`, polling `cancelled` before each one. */
  def drain[E](events: java.util.stream.Stream[E], cancelled: () => Boolean)(f: E => Unit): Unit =
    val it = events.iterator()
    while it.hasNext do
      if cancelled() then throw CancelledException()
      f(it.next())

/** Where a streaming completion reports progress. */
trait StreamSink:
  /** A piece of the answer text. */
  def text(delta: String): Unit
  /** Out-of-band note ("web search"); the empty string marks a new text block. */
  def note(text: String): Unit
  /** A piece of the model's reasoning, when the provider streams it. */
  def thinking(delta: String): Unit

object StreamSink:
  def apply(
    onText: String => Unit,
    onNote: String => Unit = _ => (),
    onThinking: String => Unit = _ => ()
  ): StreamSink =
    new StreamSink:
      def text(delta: String): Unit = onText(delta)
      def note(t: String): Unit = onNote(t)
      def thinking(delta: String): Unit = onThinking(delta)

trait ChatModel:
  /** Alias from the config. */
  def alias: String
  def modelId: String
  def providerKey: String
  def webSearch: Boolean

  /** One agent step. Streams text, notes and reasoning to `sink`;
    * `cancelled` is polled between events. */
  def complete(
    system: String,
    history: List[Msg],
    tools: List[ToolSpec],
    sink: StreamSink,
    cancelled: () => Boolean
  ): Completion

  /** One-shot completion without tools or streaming. */
  def simple(system: Option[String], prompt: String): String

object ChatModel:
  def create(alias: String, cfg: ModelConfig): ChatModel =
    cfg.provider.trim.toLowerCase match
      case "anthropic" | "claude" => AnthropicModel(alias, cfg)
      case "openai-responses" | "responses" => OpenAIResponsesModel(alias, cfg)
      case "openai" | "openai-chat" | "chat" => OpenAIChatModel(alias, cfg)
      case "echo" => EchoModel(alias)
      case other => throw IllegalArgumentException(
          s"Unknown provider '$other' for model '$alias' (expected anthropic | openai | openai-responses)"
        )
