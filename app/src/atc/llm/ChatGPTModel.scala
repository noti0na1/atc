package atc.llm

import atc.config.{ModelConfig, ModelSpec, Tokens}

import com.openai.models.responses.{Response, ResponseCreateParams}

import java.io.IOException
import java.util.Locale
import scala.util.Using
import scala.util.control.NonFatal

/** The models of a ChatGPT plan, through the backend the Codex CLI uses (the
  * provider's `url`, else [[ChatGPTModel.BackendUrl]]). It speaks the Responses
  * API with the signed-in user's token instead of a key. It only streams, and it
  * rejects an output limit and a temperature, so neither is sent (`maxTokens`
  * still reserves room for the answer). */
final class ChatGPTModel(configured: ModelSpec, auth: ChatGPTAuth)
    extends OpenAIResponsesModel(configured.copy(baseUrl = configured.baseUrl.orElse(Some(ChatGPTModel.BackendUrl)))):
  override protected def authorization: Option[okhttp3.Interceptor] = Some: chain =>
    def authorized(t: ChatGPTAuth.Tokens) =
      val b = chain.request().newBuilder().header("Authorization", s"Bearer ${t.access}")
      t.accountId.foreach(b.header("ChatGPT-Account-ID", _))
      b.build()
    // OkHttp reports only an `IOException` as a failed call; anything else from an asynchronous call is
    // thrown again on its dispatcher thread. The cause is left out, since its message may quote a token.
    def signedIn(step: => ChatGPTAuth.Tokens) =
      try step
      catch
        case e: IOException => throw e
        case NonFatal(e) => throw IOException(s"Could not read the ChatGPT sign-in (${e.getClass.getSimpleName})")
    val tokens = signedIn(auth.current())
    val response = chain.proceed(authorized(tokens))
    if response.code != 401 then response
    else
      response.close()
      chain.proceed(authorized(signedIn(auth.renewed(tokens))))

  /** Codex asks for summaries, the only reasoning text the backend shows. */
  override protected def defaultReasoningSummary: Option[String] = Some("auto")

  override protected def limits(b: ResponseCreateParams.Builder): Unit =
    b.promptCacheKey(Providers.conversation)

  override protected def send(params: ResponseCreateParams): Response =
    val complete =
      if params.instructions().isPresent then params
      else params.toBuilder().instructions(ChatGPTModel.DefaultInstructions).build()
    val acc = OpenAIResponsesModel.Accumulator()
    Using.resource(client.responses().createStreaming(complete))(_.stream().forEach(acc.accumulate(_)))
    acc.response()

  /** `GET /models`, the list Codex shows: the models marked for listing, in
    * the backend's order, with their context windows and efforts. */
  override private[llm] def listModels(): List[ModelSpec] =
    val base = spec.baseUrl.getOrElse(ChatGPTModel.BackendUrl).stripSuffix("/")
    val request = okhttp3.Request.Builder().url(s"$base/models?client_version=${ChatGPTModel.ClientVersion}")
    Providers.headers(spec).foreach((n, v) => request.header(n, v))
    val call = http.newBuilder().callTimeout(Providers.ListTimeout).build().newCall(request.build())
    val text = Using.resource(call.execute()): response =>
      val body = Option(response.body).fold("")(_.string())
      if !response.isSuccessful then throw IOException(s"HTTP ${response.code}: ${body.take(300)}")
      body
    ChatGPTModel.models(spec, ujson.read(text))

object ChatGPTModel:
  val BackendUrl = "https://chatgpt.com/backend-api/codex"
  /** The backend lists the models a Codex release of this version may use. */
  val ClientVersion = "0.156.1"
  /** Instructions for a one-shot call that has none: the backend requires them. */
  private val DefaultInstructions = "Answer the request."

  /** The models of a `GET /models` answer, as listed models of `spec`'s provider. */
  private[atc] def models(spec: ModelSpec, json: ujson.Value): List[ModelSpec] =
    def str(o: ujson.Value, key: String) = o.obj.get(key).flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)
    def effort(level: ujson.Value) =
      try str(level, "effort")
      catch case NonFatal(_) => None
    json.obj.get("models").flatMap(_.arrOpt).toList.flatten
      .filter(m => m.objOpt.isDefined && str(m, "visibility").forall(_ == "list"))
      .sortBy(m => m.obj.get("priority").flatMap(_.numOpt).getOrElse(0.0))
      .flatMap: m =>
        str(m, "slug").map: id =>
          val efforts = m.obj.get("supported_reasoning_levels").flatMap(_.arrOpt).toList.flatten
            .flatMap(effort).map(_.toLowerCase(Locale.ROOT)).filter(ModelConfig.ReasoningEfforts.contains)
          val reasoning = str(m, "default_reasoning_level").map(_.toLowerCase(Locale.ROOT))
            .filter(e => efforts.isEmpty || efforts.contains(e))
          spec.listed(
            id,
            spec.settings.copy(
              contextWindow = m.obj.get("context_window").flatMap(_.numOpt).flatMap(n => Tokens.from(n.toLong)),
              displayName = str(m, "display_name"),
              efforts = Option.when(efforts.nonEmpty)(efforts),
              reasoning = reasoning,
            ),
          )
