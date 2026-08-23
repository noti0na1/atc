package atc.llm

import atc.config.ModelSpec

/** A tool the model may call. `parametersJson` is a JSON-schema object. */
case class ToolSpec(name: String, description: String, parametersJson: String)

case class ToolCall(id: String, name: String, arguments: String)

case class ToolResult(callId: String, output: String, isError: Boolean)

/** Provider-native replay data for an assistant turn (e.g. Anthropic content
  * blocks including server web-search results, OpenAI Responses output
  * items). Reused when the same provider continues the conversation; other
  * providers rebuild the turn from the neutral fields. */
case class NativeTurn(providerKey: String, payload: Any):
  /** Cache the rendered size of `payload`. The context-window estimator revisits
    * every retained message before each request, and a Responses reasoning payload
    * can be tens of kilobytes. Rendering it on every round would make estimation
    * quadratic over a session; the immutable payload makes this cached value exact. */
  lazy val payloadChars: Int = String.valueOf(payload).length

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

/** The system prompt of a request. It is one text on purpose: everything in
  * it changes only together with a sandbox restart (configuration, mode), so
  * every request starts with the same prefix and whatever cache the provider
  * has (a marker, automatic prefix caching, none) works as well as it can.
  * Anything that changes during a session (a permission granted at a prompt)
  * is reported in the history instead, which is append-only. */
final case class SystemPrompt(text: String)

/** What a one-shot [[ChatModel.simple]] call returned, with what it cost. */
case class Reply(text: String, usage: TokenUsage)

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
  /** The name that identifies this model unambiguously (`provider/alias`). */
  def ref: String = alias
  def modelId: String
  /** The wire protocol, matched against [[NativeTurn]] when replaying a turn. */
  def providerKey: String
  def webSearch: Boolean
  /** The context window in tokens, when the config states it (`contextWindow`). */
  def contextWindow: Option[Int] = None

  /** One agent step. Streams text, notes and reasoning to `sink`;
    * `cancelled` is polled between events. */
  def complete(
    system: SystemPrompt,
    history: List[Msg],
    tools: List[ToolSpec],
    sink: StreamSink,
    cancelled: () => Boolean
  ): Completion

  /** One-shot completion without tools or streaming. `thinking = false` asks
    * the model not to reason (Anthropic: thinking disabled; OpenAI: the lowest
    * reasoning effort the model accepts), for small side questions such as the
    * next-input prediction where speed matters more than depth; `true` uses
    * the model's configured settings. */
  def simple(system: Option[String], prompt: String, thinking: Boolean = true): Reply

object ChatModel:
  /** The client for one configured model, chosen by its provider's `api`. */
  def create(spec: ModelSpec): ChatModel =
    spec.api.trim.toLowerCase match
      case "anthropic" | "claude" => AnthropicModel(spec)
      case "openai-responses" | "responses" => OpenAIResponsesModel(spec)
      case "openai" | "openai-chat" | "chat" => OpenAIChatModel(spec)
      case "echo" => EchoModel(spec.alias, spec.ref, spec.settings.contextWindow.map(_.toInt))
      case other => throw IllegalArgumentException(
          s"Unknown api '$other' for provider '${spec.provider}' (expected anthropic | openai | openai-responses | echo)"
        )
