package atc.llm

import atc.config.{Config, ModelConfig, ModelSpec, Tokens}

import com.openai.client.{OpenAIClient, OpenAIClientImpl}
import com.openai.core.JsonValue
import com.openai.errors.BadRequestException

import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** What every provider adapter takes from its [[ModelSpec]]: the names, the
  * per-model settings (`cfg`) and the two settings the agent loop reads. */
private[llm] abstract class SpecModel(val spec: ModelSpec) extends ChatModel:
  val alias: String = spec.alias
  override val ref: String = spec.ref
  val modelId: String = spec.modelId
  protected val cfg: ModelConfig = spec.settings
  /** Set once the provider rejected its web search tool for this model. */
  @volatile private var webSearchRejected = false
  def webSearch: Boolean = cfg.webSearch.getOrElse(false) && !webSearchRejected

  /** Run one streaming request. Web search is best effort: when the provider
    * rejects the tool before anything was streamed, this model continues
    * without it and the request is sent again. */
  protected def withWebSearchFallback(sink: StreamSink)(request: StreamSink => Completion): Completion =
    if !webSearch then request(sink)
    else
      val streamed = java.util.concurrent.atomic.AtomicBoolean(false)
      val tracking = StreamSink(
        t => { streamed.set(true); sink.text(t) },
        n => { streamed.set(true); sink.note(n) },
        d => { streamed.set(true); sink.thinking(d) },
      )
      try request(tracking)
      catch
        case scala.util.control.NonFatal(e) if !streamed.get() && Providers.isWebSearchRejection(e) =>
          webSearchRejected = true
          atc.Debug.log(s"$ref: the provider rejected web search; continuing without it (${e.getMessage})")
          request(sink)
  override val contextWindow: Option[Int] = cfg.contextWindow.map(_.toInt)
  override val maxOutputTokens: Option[Int] = cfg.maxTokens
  override val efforts: List[String] = cfg.efforts.getOrElse(knownEfforts).map(_.toLowerCase(java.util.Locale.ROOT))
  effort = cfg.reasoning.map(_.toLowerCase(java.util.Locale.ROOT))

  /** Every effort the provider's api accepts, for a model whose config lists none. */
  protected def knownEfforts: List[String]

/** Shared by the two OpenAI-shaped adapters (Chat Completions and Responses):
  * the client, the vendor `thinking` switch, and the guessed lowest reasoning
  * effort for non-thinking calls with its one-time fallback. */
private[llm] abstract class OpenAIShapedModel(spec: ModelSpec) extends SpecModel(spec):
  private var openedClient: Option[(OpenAIClient, okhttp3.OkHttpClient)] = None
  private def connection: (OpenAIClient, okhttp3.OkHttpClient) = synchronized {
    openedClient.getOrElse {
      val timeout = com.openai.core.Timeout.builder().request(Providers.RequestTimeout).build()
      val http = Providers.httpClient(timeout.connect(), timeout.read(), timeout.write(), timeout.request())
      val created = (Providers.openAiClient(spec, com.openai.client.okhttp.OkHttpClient(http)), http)
      openedClient = Some(created)
      created
    }
  }
  protected def client: OpenAIClient = connection._1
  protected def knownEfforts: List[String] = Config.ReasoningEfforts
  protected def streamingClient: OpenAIClient =
    val (base, transport) = connection
    base.withOptions(_.httpClient(Providers.borrowed(
      com.openai.client.okhttp.OkHttpClient(ModelRequest.scopedHttpClient(transport))
    )))
  override def close(): Unit = synchronized {
    openedClient.foreach(_._1.close())
    openedClient = None
  }

  /** Set once the model rejected the reasoning-effort parameter: it takes no such parameter. */
  @volatile private var effortRejected = false

  /** The lowest effort to send on a non-thinking call, when guessing one makes
    * sense (the model is known to reason, has no `thinking` switch, and has
    * not rejected the parameter). Only for `thinking = false`. */
  protected def lowestEffort: Option[String] =
    if effortRejected || cfg.thinking.isDefined then None
    else Providers.lowestEffort(modelId, effort.isDefined)

  /** `thinking: {"type": "enabled"|"disabled"}`, the switch of the
    * OpenAI-compatible vendors that have one (DeepSeek, GLM, Kimi, MiniMax).
    * Sent only when the config sets `thinking` for the model: OpenAI itself
    * rejects the parameter. A non-thinking call always says `disabled`. */
  protected def thinkingSwitch(thinking: Boolean): Option[JsonValue] =
    cfg.thinking.map(on => Providers.thinkingSwitch(thinking && on))

  /** `GET /models`. Besides the id, reads the context window that OpenRouter
    * (`context_length`) and vLLM (`max_model_len`) report, and OpenRouter's `name`. */
  private[llm] def listModels(): List[ModelSpec] =
    val options = com.openai.core.RequestOptions.builder().timeout(Providers.ListTimeout).build()
    client.models().list(options).items().asScala.toList.map { m =>
      // Gemini's compatible list names models `models/<id>`; its chat endpoint takes the bare id.
      val id = m.id().stripPrefix("models/")
      val extra = m._additionalProperties().asScala
      def number(key: String) = extra.get(key).flatMap(_.asNumber().toScala).map(_.longValue)
      val window = number("context_length").orElse(number("max_model_len")).flatMap(Tokens.from)
      val name = extra.get("name").flatMap(_.asString().toScala).map(_.trim).filter(_.nonEmpty)
      spec.copy(
        alias = id,
        modelId = id,
        settings = spec.settings.copy(contextWindow = window, displayName = name),
      )
    }

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
  /** Deliberately generous: a reasoning model with tools can take many minutes. */
  val RequestTimeout: Duration = Duration.ofMinutes(15)

  /** For listing a provider's models, which a user waits for. */
  val ListTimeout: Duration = Duration.ofSeconds(20)

  /** How ATC identifies itself; a configured `User-Agent` header replaces it. */
  lazy val UserAgent: String = s"atc/${atc.Main.Version}"

  /** A random id of the current conversation, what `${ATC_SESSION}` in a
    * provider header stands for. */
  @volatile private var conversationId: String = newId()
  private def newId(): String = java.util.UUID.randomUUID().toString

  /** Start a new conversation id (`/new`, `/clear`). */
  def newConversation(): Unit = conversationId = newId()

  /** The headers of one request to `spec`'s provider: the user agent, then the
    * configured ones with the conversation id filled in. */
  def headers(spec: ModelSpec): Map[String, String] =
    val defaults = if spec.headers.keysIterator.exists(_.equalsIgnoreCase("User-Agent")) then Map.empty[String, String]
    else Map("User-Agent" -> UserAgent)
    defaults ++ spec.headers.map { (name, value) =>
      name -> (if value == atc.config.Config.SessionRef then conversationId else value)
    }

  def httpClient(connect: Duration, read: Duration, write: Duration, request: Duration): okhttp3.OkHttpClient =
    okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false)
      .connectTimeout(connect).readTimeout(read).writeTimeout(write).callTimeout(request).build()

  /** Request clients share the model's connection pool and executor. SDK cleanup, including
    * garbage collection, must not close these resources; the model owns their lifetime. */
  def borrowed(transport: com.openai.core.http.HttpClient): com.openai.core.http.HttpClient =
    new com.openai.core.http.HttpClient:
      def execute(request: com.openai.core.http.HttpRequest, options: com.openai.core.RequestOptions) =
        transport.execute(request, options)
      def executeAsync(request: com.openai.core.http.HttpRequest, options: com.openai.core.RequestOptions) =
        transport.executeAsync(request, options)
      def close(): Unit = ()

  /** Whether a 400 specifically rejects the guessed reasoning-effort setting.
    * Do not retry arbitrary bad requests: that duplicates traffic and can
    * permanently misclassify a model as not supporting effort. */
  def isReasoningEffortRejection(param: Option[String], message: String): Boolean =
    def mentions(value: String): Boolean =
      val s = value.trim.toLowerCase(java.util.Locale.ROOT)
      s.contains("reasoning_effort") || s.contains("reasoning.effort") || s.contains("reasoning effort")
    param.exists(p => p.trim.equalsIgnoreCase("reasoning") || mentions(p)) || mentions(message)

  /** Whether a failed request is the provider refusing its web search tool or
    * parameter: a 400 or 422 whose message names it. */
  def isWebSearchRejection(error: Throwable): Boolean =
    Iterator.iterate[Throwable | Null](error)(_.nn.getCause).takeWhile(_ != null).take(8).exists {
      case e: com.anthropic.errors.AnthropicServiceException => rejectsWebSearch(e.statusCode(), e.getMessage)
      case e: com.openai.errors.OpenAIServiceException => rejectsWebSearch(e.statusCode(), e.getMessage)
      case _ => false
    }

  def rejectsWebSearch(status: Int, message: String | Null): Boolean =
    val text = Option(message).getOrElse("").toLowerCase(java.util.Locale.ROOT)
    (status == 400 || status == 422) &&
    List("web_search", "web search", "websearch", "web-search").exists(text.contains)

  /** The body of a `thinking` switch: `{"type": "enabled"}` / `{"type": "disabled"}`. */
  def thinkingSwitch(on: Boolean): JsonValue =
    JsonValue.from(java.util.Map.of("type", if on then "enabled" else "disabled"))

  /** The lowest `reasoning_effort` an OpenAI model accepts, for a call that
    * should not think: `none` from GPT-5.1 on, `minimal` for the GPT-5 family
    * before it, `low` for the o-series and for anything else the config says
    * takes an effort at all. `None` when the model is not known to reason
    * (sending the parameter to such a model is an error). */
  def lowestEffort(modelId: String, configuredEffort: Boolean): Option[String] =
    val id = modelId.toLowerCase(java.util.Locale.ROOT)
    if id.matches("^(o[1-9]|.*/o[1-9]).*") then Some("low")
    else if id.matches("^(.*/)?gpt-5\\.[1-9].*") then Some("none")
    else if id.matches("^(.*/)?gpt-5(-.*)?$") then Some("minimal")
    else if configuredEffort then Some("low")
    else None

  /** A client for the two OpenAI-shaped providers. The key is the configured
    * one, else the SDK's own environment resolution. Against a custom `url`
    * (Ollama, vLLM, LM Studio, ...) a placeholder stands in for the key, which
    * such servers ignore. */
  def openAiClient(spec: ModelSpec, transport: com.openai.core.http.HttpClient): OpenAIClient =
    val b = com.openai.core.ClientOptions.builder().httpClient(transport).timeout(RequestTimeout)
    spec.apiKey match
      case Some(key) => b.apiKey(key)
      case None if spec.baseUrl.isDefined => b.apiKey("none")
      case None => b.fromEnv()
    spec.baseUrl.foreach(b.baseUrl)
    OpenAIClientImpl(b.build())
