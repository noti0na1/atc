package atc.llm

import atc.config.{ModelConfig, ModelSpec}

import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.errors.BadRequestException

import java.time.Duration
import scala.jdk.OptionConverters.*

/** What every provider adapter takes from its [[ModelSpec]]: the names, the
  * per-model settings (`cfg`) and the two settings the agent loop reads. */
private[llm] abstract class SpecModel(val spec: ModelSpec) extends ChatModel:
  val alias: String = spec.alias
  override val ref: String = spec.ref
  val modelId: String = spec.modelId
  protected val cfg: ModelConfig = spec.settings
  val webSearch: Boolean = cfg.webSearch
  override val contextWindow: Option[Int] = cfg.contextWindow.map(_.toInt)
  override val maxOutputTokens: Option[Int] = cfg.maxTokens

/** Shared by the two OpenAI-shaped adapters (Chat Completions and Responses):
  * the client, the vendor `thinking` switch, and the guessed lowest reasoning
  * effort for non-thinking calls with its one-time fallback. */
private[llm] abstract class OpenAIShapedModel(spec: ModelSpec) extends SpecModel(spec):
  protected lazy val client: OpenAIClient = Providers.openAiClient(spec)

  /** Set once the model rejected the reasoning-effort parameter: it takes no such parameter. */
  @volatile private var effortRejected = false

  /** The lowest effort to send on a non-thinking call, when guessing one makes
    * sense (the model is known to reason, has no `thinking` switch, and has
    * not rejected the parameter). Only for `thinking = false`. */
  protected def lowestEffort: Option[String] =
    if effortRejected || cfg.thinking.isDefined then None
    else Providers.lowestEffort(modelId, cfg.reasoning.isDefined)

  /** `thinking: {"type": "enabled"|"disabled"}`, the switch of the
    * OpenAI-compatible vendors that have one (DeepSeek, GLM, Kimi, MiniMax).
    * Sent only when the config sets `thinking` for the model: OpenAI itself
    * rejects the parameter. A non-thinking call always says `disabled`. */
  protected def thinkingSwitch(thinking: Boolean): Option[JsonValue] =
    cfg.thinking.map(on => Providers.thinkingSwitch(thinking && on))

  /** Send `request` with `effort` (the reasoning setting of a one-shot call).
    * When that was a *guessed* lowest effort (a non-thinking call) and the
    * model rejects it, remember that and ask again plainly. */
  protected def withEffortFallback[E, R](thinking: Boolean, effort: Option[E])(request: Option[E] => R): R =
    try request(effort)
    catch
      case e: BadRequestException
          if !thinking && effort.isDefined && Providers.isReasoningEffortRejection(
            e.param().toScala,
            Option(e.getMessage).getOrElse("")
          ) =>
        effortRejected = true
        request(None)

/** Settings and client construction shared by the provider adapters. */
private[atc] object Providers:
  /** Generous on purpose: a reasoning model with tools can take many minutes. */
  val RequestTimeout: Duration = Duration.ofMinutes(15)

  /** Whether a 400 specifically rejects the guessed reasoning-effort setting.
    * Do not retry arbitrary bad requests: that duplicates traffic and can
    * permanently misclassify a model as not supporting effort. */
  def isReasoningEffortRejection(param: Option[String], message: String): Boolean =
    def mentions(value: String): Boolean =
      val s = value.trim.toLowerCase
      s.contains("reasoning_effort") || s.contains("reasoning.effort") || s.contains("reasoning effort")
    param.exists(p => p.trim.equalsIgnoreCase("reasoning") || mentions(p)) || mentions(message)

  /** The body of a `thinking` switch: `{"type": "enabled"}` / `{"type": "disabled"}`. */
  def thinkingSwitch(on: Boolean): JsonValue =
    JsonValue.from(java.util.Map.of("type", if on then "enabled" else "disabled"))

  /** The lowest `reasoning_effort` an OpenAI model accepts, for a call that
    * should not think: `none` from GPT-5.1 on, `minimal` for the GPT-5 family
    * before it, `low` for the o-series and for anything else the config says
    * takes an effort at all. `None` when the model is not known to reason
    * (sending the parameter to such a model is an error). */
  def lowestEffort(modelId: String, configuredEffort: Boolean): Option[String] =
    val id = modelId.toLowerCase
    if id.matches("^(o[1-9]|.*/o[1-9]).*") then Some("low")
    else if id.matches("^(.*/)?gpt-5\\.[1-9].*") then Some("none")
    else if id.matches("^(.*/)?gpt-5(-.*)?$") then Some("minimal")
    else if configuredEffort then Some("low")
    else None

  /** A client for the two OpenAI-shaped providers: the configured key if there
    * is one, else the SDK's own environment resolution — except against a
    * custom `url` (Ollama, vLLM, LM Studio, ...), where a placeholder stands
    * in for the key such servers ignore. */
  def openAiClient(spec: ModelSpec): OpenAIClient =
    val b = OpenAIOkHttpClient.builder().timeout(RequestTimeout)
    spec.apiKey match
      case Some(key) => b.apiKey(key)
      case None if spec.baseUrl.isDefined => b.apiKey("none")
      case None => b.fromEnv()
    spec.baseUrl.foreach(b.baseUrl)
    b.build()
