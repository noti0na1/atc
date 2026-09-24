package atc.config

/** One model resolved against its provider: everything a client adapter needs
  * to talk to it. */
case class ModelSpec(
  /** The provider's name in the config (`providers` key). */
  provider: String,
  /** The model's alias within that provider (`models` key). */
  alias: String,
  /** The wire protocol (`ProviderConfig.api`). */
  api: String,
  /** The id the provider knows the model by. */
  modelId: String,
  baseUrl: Option[String],
  /** The resolved key, if the config supplies one. */
  apiKey: Option[String],
  /** The model's own settings (web search, reasoning, limits). */
  settings: ModelConfig,
  /** The provider's extra request headers (`ProviderConfig.headers`, resolved). */
  headers: Map[String, String] = Map.empty,
):
  /** The unambiguous name of this model, `provider/alias`. */
  def ref: String = s"$provider/$alias"
  /** Optional human-facing name; model identity and lookup never use it. */
  def displayName: Option[String] = settings.displayName

  /** Redact the API key from diagnostics. */
  override def toString: String =
    s"ModelSpec($ref, api=$api, model=$modelId, url=${baseUrl.getOrElse("default")}, " +
      s"key=${if apiKey.isDefined then "<set>" else "<from environment>"})"

/** Every model: the configured ones in a stable order (provider, then alias),
  * then those of the providers that list none, as each provider lists them.
  *
  * A configured model is named by its bare `alias`, or by `provider/alias` when
  * several providers use the same alias; [[label]] gives the shortest
  * unambiguous name and [[find]] accepts either spelling (case-insensitively).
  * A listed model is named `provider/model-id`; [[find]] also accepts its bare
  * id when no configured alias and no other listed model has it.
  *
  * A provider's list is fetched by `discover` at most once per catalog: by
  * [[refresh]] in the background, or when [[models]] needs it. Until then a
  * lookup uses the list `store` kept from an earlier session, and takes a
  * `provider/model-id` it does not know as given; so do lookups after a failed
  * fetch, which is otherwise ignored: the provider keeps its stored list, if any.
  */
final class ModelCatalog(
  val configured: List[ModelSpec],
  /** Providers without configured models, each as a spec with an empty alias
    * and model id and the default settings: what `discover` receives. */
  val discoverable: List[ModelSpec],
  discover: Option[ModelSpec => List[ModelSpec]] = None,
  store: Option[ModelListStore] = None,
):
  import java.util.concurrent.CompletableFuture

  private def lower(s: String) = s.toLowerCase(java.util.Locale.ROOT)

  private lazy val aliasCount: Map[String, Int] =
    configured.groupBy(m => lower(m.alias)).map((a, ms) => a -> ms.size)

  /** The lists kept from earlier sessions, read once. */
  private lazy val stored: Map[String, List[ModelSpec]] =
    discoverable.flatMap(p => store.flatMap(_.load(p)).map(p.provider -> _)).toMap

  /** This session's fetches by provider name, `None` for one that failed. */
  private val fetches = scala.collection.mutable.Map[String, CompletableFuture[Option[List[ModelSpec]]]]()

  private def fetching(provider: ModelSpec): Option[CompletableFuture[Option[List[ModelSpec]]]] =
    discover.map { list =>
      synchronized {
        fetches.getOrElseUpdate(
          provider.provider,
          CompletableFuture.supplyAsync(() =>
            try
              val models = list(provider)
              store.foreach(_.save(provider, models))
              Some(models)
            catch
              case scala.util.control.NonFatal(e) =>
                atc.Debug.log(s"could not list the models of ${provider.provider}: ${atc.Debug.describe(e)}")
                None
          )
        )
      }
    }

  /** The finished fetch of `provider`, if any. */
  private def fetched(provider: ModelSpec): Option[Option[List[ModelSpec]]] =
    synchronized(fetches.get(provider.provider)).filter(_.isDone).map(_.join())

  /** The newest list of `provider` known without waiting: this session's, else the stored one. */
  private def known(provider: ModelSpec): Option[List[ModelSpec]] =
    fetched(provider).flatten.orElse(stored.get(provider.provider))

  /** Start fetching every provider's list in the background; returns at once. */
  def refresh(): Unit = discoverable.foreach(fetching)

  /** Every model, waiting for the fetches of this session (a failed one leaves the stored list). */
  def models: List[ModelSpec] =
    discoverable.flatMap(fetching).foreach(_.join())
    configured ++ discoverable.flatMap(known(_).getOrElse(Nil))

  /** The shortest name that identifies `m` on its own. */
  def label(m: ModelSpec): String =
    if !configured.exists(_.ref == m.ref) || aliasCount.getOrElse(lower(m.alias), 0) > 1 then m.ref else m.alias

  /** All models' labels, for messages and menus. */
  def labels: List[String] = models.map(label)

  /** The model a reference names. Throws `IllegalArgumentException` naming the
    * configured models when it matches none, or both candidates when a name is
    * ambiguous. */
  def find(reference: String): ModelSpec =
    val wanted = lower(reference.trim)
    if wanted.isEmpty then throw IllegalArgumentException("No model given")
    findConfigured(reference).getOrElse {
      provided(wanted) match
        case Some((provider, id)) =>
          known(provider).flatMap(_.find(m => lower(m.alias) == id)).getOrElse {
            if fetched(provider).exists(_.isDefined) then throw unknown(reference)
            val named = reference.trim.drop(provider.provider.length + 1)
            provider.copy(alias = named, modelId = named)
          }
        case None =>
          def matching = discoverable.flatMap(known(_).getOrElse(Nil)).filter(m => lower(m.alias) == wanted)
          (if matching.nonEmpty then matching else models.filter(m => lower(m.alias) == wanted)) match
            case one :: Nil => one
            case Nil => throw unknown(reference)
            case many => throw ambiguous(reference, many)
    }

  /** Check `reference` without fetching anything: it must name a configured
    * model unless a provider's list could hold it. */
  def check(reference: String): Unit =
    if reference.trim.isEmpty then throw IllegalArgumentException("No model given")
    if findConfigured(reference).isEmpty && discoverable.isEmpty then throw unknown(reference)

  private def findConfigured(reference: String): Option[ModelSpec] =
    val wanted = lower(reference.trim)
    configured.find(m => lower(m.ref) == wanted).orElse {
      configured.filter(m => lower(m.alias) == wanted) match
        case Nil => None
        case one :: Nil => Some(one)
        case many => throw ambiguous(reference, many)
    }

  /** The discoverable provider a `provider/model-id` reference names, with the id. */
  private def provided(wanted: String): Option[(ModelSpec, String)] =
    discoverable.collectFirst {
      case p if wanted.startsWith(lower(p.provider) + "/") && wanted.length > p.provider.length + 1 =>
        p -> wanted.drop(p.provider.length + 1)
    }

  /** Names the configured models; listed ones can be too many to name. */
  private def unknown(reference: String) =
    val known = if configured.isEmpty then "(none)" else configured.map(label).mkString(", ")
    val lists =
      if discoverable.isEmpty then ""
      else s"; ${discoverable.map(_.provider).mkString(", ")} list their own (see /models)"
    IllegalArgumentException(s"Unknown model '${reference.trim}'. Configured: $known$lists")

  private def ambiguous(reference: String, many: List[ModelSpec]) =
    IllegalArgumentException(
      s"Ambiguous model '${reference.trim}': defined by ${many.map(_.ref).mkString(" and ")}. Use the full name."
    )

  /** The model used when the config and the command line name none. */
  def default: ModelSpec =
    configured.headOption.orElse(discoverable.flatMap(known(_).getOrElse(Nil)).headOption)
      .orElse(models.headOption).getOrElse(
        throw IllegalArgumentException("No models configured (see \"providers\" in the config)")
      )

object ModelCatalog:
  /** Resolve every model of every provider. A provider's `${VAR}` key is
    * resolved through `keys` (a project's `.atc/keys.properties`, then the global one)
    * and then the environment. A provider without models is listed through
    * `discover` when it can be reached: not `echo`, and not one whose configured
    * key is unset. */
  def from(
    config: Config,
    keys: KeyBindings = KeyBindings.empty,
    discover: Option[ModelSpec => List[ModelSpec]] = None,
    store: Option[ModelListStore] = None,
  ): ModelCatalog =
    val providers = config.providers.toList.sortBy(_._1).map { (name, p) =>
      val key = Config.resolveApiKey(p, keys)
      val defaults = ModelConfig(webSearch = config.webSearch)
      val endpoint =
        ModelSpec(name, "", p.api.getOrElse(""), "", p.url, key, defaults, Config.resolveHeaders(p, keys))
      (p, endpoint)
    }
    val configured = providers.flatMap { (p, endpoint) =>
      p.models.toList.sortBy(_._1).map((alias, m) =>
        endpoint.copy(
          alias = alias,
          modelId = m.name.getOrElse(alias),
          settings = m.copy(webSearch = m.webSearch.orElse(config.webSearch))
        )
      )
    }
    val discoverable = providers.collect {
      case (p, endpoint)
          if p.models.isEmpty && !endpoint.api.trim.equalsIgnoreCase("echo") &&
            (endpoint.apiKey.isDefined || (p.key.isEmpty && p.keyEnv.isEmpty)) => endpoint
    }
    ModelCatalog(configured, discoverable, discover, store)

/** The model lists providers returned, kept in one JSON file (`~/.atc/model-lists.json`)
  * so that a session can name and describe listed models before fetching them
  * again. A list is used again only for a provider of the same name, api and url;
  * the current defaults (`webSearch`) apply to it, not the ones it was saved with.
  * A file that cannot be read or written is only a missing list. */
final class ModelListStore(path: java.nio.file.Path):
  import ModelListStore.*
  import upickle.default.*

  private def all(): Map[String, Saved] =
    try read[Map[String, Saved]](java.nio.file.Files.readString(path).nn)
    catch case scala.util.control.NonFatal(_) => Map.empty

  def load(provider: ModelSpec): Option[List[ModelSpec]] =
    all().get(provider.provider).filter(s => s.api == provider.api && s.url == provider.baseUrl).map(_.models.map {
      m =>
        provider.copy(alias = m.id, modelId = m.id, settings = m.settings.copy(webSearch = provider.settings.webSearch))
    })

  def save(provider: ModelSpec, models: List[ModelSpec]): Unit = ModelListStore.synchronized {
    try
      val saved =
        Saved(provider.api, provider.baseUrl, models.map(m => Model(m.modelId, m.settings.copy(webSearch = None))))
      val text = write(all().updated(provider.provider, saved))
      java.nio.file.Files.createDirectories(path.getParent)
      val temp = java.nio.file.Files.createTempFile(path.getParent, s".${path.getFileName}.", ".tmp").nn
      try
        java.nio.file.Files.writeString(temp, text)
        java.nio.file.Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      finally java.nio.file.Files.deleteIfExists(temp)
    catch case scala.util.control.NonFatal(_) => ()
  }

object ModelListStore:
  private case class Model(id: String, settings: ModelConfig) derives upickle.default.ReadWriter
  private case class Saved(api: String, url: Option[String], models: List[Model]) derives upickle.default.ReadWriter

  /** `~/.atc/model-lists.json`. */
  def global: ModelListStore =
    ModelListStore(atc.platform.PlatformPath.userHome.resolve(".atc").nn.resolve("model-lists.json").nn)
