package atc.llm

import atc.config.ModelSpec

import java.util.Locale

/** A tool the model may call. `parametersJson` is a JSON-schema object. */
final case class ToolSpec(name: String, description: String, parametersJson: String)

final case class ToolCall(id: String, name: String, arguments: String)

final case class ToolResult(
  callId: String,
  output: String,
  isError: Boolean,
  /** Local scheduling flag: return to the model before executing more calls from this batch. */
  needsReplan: Boolean = false,
)

/** Provider-native replay data for an assistant turn (e.g. Anthropic content
  * blocks including server web-search results, OpenAI Responses output
  * items). Reused only by the exact model endpoint that produced it; another
  * model using the same wire protocol rebuilds the turn from the neutral
  * fields. This matters for model-bound data such as encrypted reasoning. */
final case class NativeTurn(providerKey: String, modelRef: String, payload: Any):
  def isFor(key: String, ref: String): Boolean = providerKey == key && modelRef == ref

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
  /** Internal user-role message asking a provider to continue a response that
    * hit its output limit. Kept distinct from real user input for prediction,
    * context-cut boundaries and transcript accounting. */
  case Continuation(text: String)

final case class TokenUsage(input: Long = 0, output: Long = 0, cacheRead: Long = 0):
  def +(o: TokenUsage): TokenUsage = TokenUsage(input + o.input, output + o.output, cacheRead + o.cacheRead)

/** Why a provider stopped producing the current assistant turn.
  *
  * Tool calls are orthogonal: a [[Complete]] response may contain calls for the
  * agent to run. [[Resume]] means the provider paused after server-side work;
  * [[Truncated]] means an output limit cut the response and a user-role bridge
  * is needed before resuming. Calls on resumable or blocked responses are not
  * safe to execute. [[Incomplete]] means the stream ended without a finish marker;
  * its partial text may be continued, but it cannot authorize tool execution. */
enum CompletionStop:
  case Complete
  case Resume
  case Truncated
  /** The stream ended without confirming completion; received text can be continued, but tool calls cannot run. */
  case Incomplete
  case Blocked

object CompletionStop:
  private val ResumeReasons = Set("pause_turn")
  private val TruncatedReasons = Set("length", "max_tokens", "max_output_tokens")
  private val BlockedReasons = Set("content_filter", "refusal")

  /** Normalize a provider's raw reason at the adapter boundary. */
  def fromReason(reason: String): CompletionStop =
    val normalized = reason.trim.toLowerCase(Locale.ROOT).replace('-', '_')
    if ResumeReasons.contains(normalized) then CompletionStop.Resume
    else if TruncatedReasons.contains(normalized) then CompletionStop.Truncated
    else if BlockedReasons.contains(normalized) then CompletionStop.Blocked
    else CompletionStop.Complete

  /** As [[fromReason]], where the adapter also identified a server-side pause from the
    * response's shape (it ended in a server tool call): a complete-looking stop is a pause. */
  def fromReason(reason: String, paused: Boolean): CompletionStop = fromReason(reason) match
    case CompletionStop.Complete if paused => CompletionStop.Resume
    case other => other

final case class Completion(
  text: String,
  toolCalls: List[ToolCall],
  native: Option[NativeTurn],
  usage: TokenUsage,
  stopReason: String,
  stop: CompletionStop,
)

type SystemPrompt = String

/** What a one-shot [[ChatModel.simple]] call returned, with what it cost. */
final case class Reply(text: String, usage: TokenUsage)

/** Thrown when the user cancels a streaming completion. */
class CancelledException extends RuntimeException("cancelled")

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
  /** Release provider resources when the application closes. */
  def close(): Unit = ()
  /** Alias from the config. */
  def alias: String
  /** The name that identifies this model unambiguously (`provider/alias`). */
  def ref: String = alias
  def modelId: String
  /** The wire protocol, matched together with [[ref]] against [[NativeTurn]]
    * when replaying a turn. */
  def providerKey: String
  def webSearch: Boolean
  /** The context window in tokens, when the config states it (`contextWindow`). */
  def contextWindow: Option[Int] = None
  /** Configured maximum output tokens, when the adapter sends one. Context
    * fitting reserves at least this much room for the answer. */
  def maxOutputTokens: Option[Int] = None
  /** The efforts [[effort]] may be set to; empty when the model takes no effort parameter. */
  def efforts: List[String] = Nil
  /** The effort the model's config sets (`reasoning`); `None` sends no effort. */
  def defaultEffort: Option[String] = None
  /** The reasoning effort later requests ask for, one of [[efforts]]; `None`
    * sends no effort. Starts as [[defaultEffort]]; `/effort` switches it. */
  @volatile var effort: Option[String] = None

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
    spec.api.trim.toLowerCase(Locale.ROOT) match
      case "anthropic" | "claude" => AnthropicModel(spec)
      case "openai-responses" | "responses" => OpenAIResponsesModel(spec)
      case "openai" | "openai-chat" | "chat" => OpenAIChatModel(spec)
      case "chatgpt" => ChatGPTModel(spec, ChatGPTAuth.default)
      case "claude-code" => ClaudeCodeModel(spec)
      case "echo" => EchoModel(spec.alias, spec.ref, spec.settings.contextWindow.map(_.toInt))
      case other => throw IllegalArgumentException(
          s"Unknown api '$other' for provider '${spec.provider}' (expected anthropic | openai | openai-responses | chatgpt | claude-code | echo)"
        )

  /** The models a provider's endpoint lists (`provider` has an empty alias and
    * model id), each as a spec named by its model id. */
  def listModels(provider: ModelSpec): List[ModelSpec] =
    val client = create(provider)
    try
      client match
        case m: AnthropicModel => m.listModels()
        case m: OpenAIShapedModel => m.listModels()
        case m: ClaudeCodeModel => m.listModels()
        case _ => Nil
    finally client.close()
