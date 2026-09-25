package atc.llm

import atc.{Debug, Main}
import atc.config.{ModelConfig, ModelSpec, ProviderConfig, Tokens}

import com.openai.client.{OpenAIClient, OpenAIClientImpl}
import com.openai.core.{ClientOptions, JsonValue, RequestOptions, Timeout}
import com.openai.core.http.{HttpClient, HttpRequest}
import com.openai.errors.BadRequestException

import java.time.Duration
import java.util.{Locale, UUID}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.reflect.ClassTag
import scala.util.control.NonFatal

/** What every provider adapter takes from its [[ModelSpec]]: the names, the
  * per-model settings and the two settings the agent loop reads. */
private[llm] abstract class SpecModel(val spec: ModelSpec) extends ChatModel:
  val alias: String = spec.alias
  override val ref: String = spec.ref
  val modelId: String = spec.modelId
  protected val settings: ModelConfig = spec.settings
  override val contextWindow: Option[Int] = settings.contextWindow.map(_.toInt)
  override val maxOutputTokens: Option[Int] = settings.maxTokens
  override val efforts: List[String] = settings.efforts.getOrElse(knownEfforts).map(_.toLowerCase(Locale.ROOT))
  override val defaultEffort: Option[String] = settings.reasoning.map(_.toLowerCase(Locale.ROOT))
  effort = defaultEffort

  /** Every effort the provider's api accepts, for a model whose config lists none. */
  protected def knownEfforts: List[String]

  /** Set once the provider rejected its web search tool for this model. */
  @volatile private var webSearchRejected = false
  def webSearch: Boolean = settings.webSearch.getOrElse(false) && !webSearchRejected

  /** Run one streaming request. Web search is best effort: when the provider
    * rejects the tool before anything was streamed, this model continues
    * without it and the request is sent again. */
  protected def withWebSearchFallback(sink: StreamSink)(request: StreamSink => Completion): Completion =
    if !webSearch then request(sink)
    else
      val streamed = AtomicBoolean(false)
      val tracking = StreamSink(
        t => { streamed.set(true); sink.text(t) },
        n => { streamed.set(true); sink.note(n) },
        d => { streamed.set(true); sink.thinking(d) },
      )
      try request(tracking)
      catch
        case NonFatal(e) if !streamed.get() && Providers.isWebSearchRejection(e) =>
          webSearchRejected = true
          Debug.log(s"$ref: the provider rejected web search; continuing without it (${Debug.message(e)})")
          request(sink)

  /** The provider-native form of an assistant turn this model produced, to replay
    * as it was; `None` for a turn of another model or protocol. */
  protected def replay[P: ClassTag](turn: Option[NativeTurn]): Option[P] =
    turn.filter(_.isFor(providerKey, ref)).map(_.payload).collect { case p: P => p }

/** Shared by the two OpenAI-shaped adapters (Chat Completions and Responses):
  * the client, the vendor `thinking` switch, and the guessed lowest reasoning
  * effort for non-thinking calls with its one-time fallback. */
private[llm] abstract class OpenAIShapedModel(spec: ModelSpec) extends SpecModel(spec):
  private final case class Connection(client: OpenAIClient, http: okhttp3.OkHttpClient)
  private var opened: Option[Connection] = None
  private def connection: Connection = synchronized:
    opened.getOrElse:
      val timeout = Timeout.builder().request(Providers.RequestTimeout).build()
      val plain = Providers.httpClient(timeout.connect(), timeout.read(), timeout.write(), timeout.request())
      val http = authorization.fold(plain)(plain.newBuilder().addInterceptor(_).build())
      val created = Connection(Providers.openAiClient(spec, com.openai.client.okhttp.OkHttpClient(http)), http)
      opened = Some(created)
      created
  protected def client: OpenAIClient = connection.client
  /** The model's HTTP client, for requests the SDK does not make. */
  protected def http: okhttp3.OkHttpClient = connection.http
  /** Authorizes every request when the provider's credential is not a fixed key. */
  protected def authorization: Option[okhttp3.Interceptor] = None
  protected def knownEfforts: List[String] = ModelConfig.ReasoningEfforts
  protected def streamingClient: OpenAIClient =
    val current = connection
    val transport = com.openai.client.okhttp.OkHttpClient(ModelRequest.scopedHttpClient(current.http))
    current.client.withOptions(_.httpClient(Providers.borrowed(transport)))
  override def close(): Unit = synchronized:
    opened.foreach(_.client.close())
    opened = None

  /** Set once the model rejected the reasoning-effort parameter: it takes no such parameter. */
  @volatile private var effortRejected = false

  /** The reasoning effort a request sends: the current [[effort]] for a thinking
    * call. A non-thinking call sends the lowest effort the model takes when
    * guessing one makes sense: the model is known to reason, has no `thinking`
    * switch, and has not rejected the parameter. */
  protected def requestEffort(thinking: Boolean): Option[String] =
    if thinking then effort.map(_.toLowerCase(Locale.ROOT))
    else if effortRejected || settings.thinking.isDefined then None
    else Providers.lowestEffort(modelId, effort.isDefined)

  /** `thinking: {"type": "enabled"|"disabled"}`, the switch of the
    * OpenAI-compatible vendors that have one (DeepSeek, GLM, Kimi, MiniMax).
    * Sent only when the config sets `thinking` for the model: OpenAI itself
    * rejects the parameter. A non-thinking call always says `disabled`. */
  protected def thinkingSwitch(thinking: Boolean): Option[JsonValue] =
    settings.thinking.map(on => Providers.thinkingSwitch(thinking && on))

  /** `GET /models`. Besides the id, reads the context window that OpenRouter
    * (`context_length`) and vLLM (`max_model_len`) report, and OpenRouter's `name`. */
  private[llm] def listModels(): List[ModelSpec] =
    val options = RequestOptions.builder().timeout(Providers.ListTimeout).build()
    client.models().list(options).items().asScala.toList.map: m =>
      // Gemini's compatible list names models `models/<id>`; its chat endpoint takes the bare id.
      val id = m.id().stripPrefix("models/")
      val extra = m._additionalProperties().asScala
      def number(key: String) = extra.get(key).flatMap(_.asNumber().toScala).map(_.longValue)
      val window = number("context_length").orElse(number("max_model_len")).flatMap(Tokens.from)
      val name = extra.get("name").flatMap(_.asString().toScala).map(_.trim).filter(_.nonEmpty)
      spec.listed(id, spec.settings.copy(contextWindow = window, displayName = name))

  /** Send `request` with `effort` (the reasoning setting of a one-shot call).
    * When that was a guessed lowest effort (a non-thinking call) and the model
    * rejects it, remember that and ask again without it. */
  protected def withEffortFallback[E, R](thinking: Boolean, effort: Option[E])(request: Option[E] => R): R =
    try request(effort)
    catch
      case e: BadRequestException
          if !thinking && effort.isDefined &&
            Providers.isReasoningEffortRejection(e.param().toScala, Option(e.getMessage).getOrElse("")) =>
        effortRejected = true
        request(None)

/** Settings and client construction shared by the provider adapters. */
private[atc] object Providers:
  /** Generous, because a reasoning model with tools can take many minutes. */
  val RequestTimeout: Duration = Duration.ofMinutes(15)

  /** For listing a provider's models, which a user waits for. */
  val ListTimeout: Duration = Duration.ofSeconds(20)

  /** How ATC identifies itself; a configured `User-Agent` header replaces it. */
  lazy val UserAgent: String = s"atc/${Main.Version}"

  /** A random id of the current conversation, what `${ATC_SESSION}` in a
    * provider header stands for. */
  @volatile private var conversationId: String = newId()
  private def newId(): String = UUID.randomUUID().toString

  /** The current conversation id. */
  def conversation: String = conversationId

  /** Start a new conversation id (`/new`, `/clear`). */
  def newConversation(): Unit = conversationId = newId()

  /** The headers of one request to `spec`'s provider: the user agent, then the
    * configured ones with the conversation id filled in. */
  def headers(spec: ModelSpec): Map[String, String] =
    val defaults =
      if spec.headers.keysIterator.exists(_.equalsIgnoreCase("User-Agent")) then Map.empty[String, String]
      else Map("User-Agent" -> UserAgent)
    defaults ++ spec.headers.map: (name, value) =>
      name -> (if value == ProviderConfig.SessionRef then conversationId else value)

  def httpClient(connect: Duration, read: Duration, write: Duration, request: Duration): okhttp3.OkHttpClient =
    okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false)
      .connectTimeout(connect).readTimeout(read).writeTimeout(write).callTimeout(request).build()

  /** Request clients share the model's connection pool and executor. SDK cleanup, including
    * garbage collection, must not close these resources; the model owns their lifetime. */
  def borrowed(transport: HttpClient): HttpClient =
    new HttpClient:
      def execute(request: HttpRequest, options: RequestOptions) = transport.execute(request, options)
      def executeAsync(request: HttpRequest, options: RequestOptions) = transport.executeAsync(request, options)
      def close(): Unit = ()

  /** Whether a 400 specifically rejects the guessed reasoning-effort setting.
    * Arbitrary bad requests are not retried: that duplicates traffic and can
    * permanently misclassify a model as not supporting effort. */
  def isReasoningEffortRejection(param: Option[String], message: String): Boolean =
    def mentions(value: String): Boolean =
      val s = value.trim.toLowerCase(Locale.ROOT)
      s.contains("reasoning_effort") || s.contains("reasoning.effort") || s.contains("reasoning effort")
    param.exists(p => p.trim.equalsIgnoreCase("reasoning") || mentions(p)) || mentions(message)

  /** Whether a failed request is the provider refusing its web search tool or
    * parameter: a 400 or 422 whose message names it. */
  def isWebSearchRejection(error: Throwable): Boolean =
    Iterator.iterate[Throwable | Null](error)(_.nn.getCause).takeWhile(_ != null).take(8).exists:
      case e: com.anthropic.errors.AnthropicServiceException => rejectsWebSearch(e.statusCode(), e.getMessage)
      case e: com.openai.errors.OpenAIServiceException => rejectsWebSearch(e.statusCode(), e.getMessage)
      case _ => false

  private def rejectsWebSearch(status: Int, message: String | Null): Boolean =
    val text = Option(message).getOrElse("").toLowerCase(Locale.ROOT)
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
    val id = modelId.toLowerCase(Locale.ROOT)
    if id.matches("^(o[1-9]|.*/o[1-9]).*") then Some("low")
    else if id.matches("^(.*/)?gpt-5\\.[1-9].*") then Some("none")
    else if id.matches("^(.*/)?gpt-5(-.*)?$") then Some("minimal")
    else if configuredEffort then Some("low")
    else None

  /** A client for the two OpenAI-shaped providers. The key is the configured
    * one, else the SDK's own environment resolution. Against a custom `url`
    * (Ollama, vLLM, LM Studio, ...) a placeholder stands in for the key, which
    * such servers ignore. */
  private[llm] def openAiClient(spec: ModelSpec, transport: HttpClient): OpenAIClient =
    val b = ClientOptions.builder().httpClient(transport).timeout(RequestTimeout)
    spec.apiKey match
      case Some(key) => b.apiKey(key)
      case None if spec.baseUrl.isDefined => b.apiKey("none")
      case None => b.fromEnv()
    spec.baseUrl.foreach(b.baseUrl)
    OpenAIClientImpl(b.build())
