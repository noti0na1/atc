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
):
  /** The unambiguous name of this model, `provider/alias`. */
  def ref: String = s"$provider/$alias"

  /** Never print the key: a `ModelSpec` travels through error messages. */
  override def toString: String =
    s"ModelSpec($ref, api=$api, model=$modelId, url=${baseUrl.getOrElse("default")}, " +
      s"key=${if apiKey.isDefined then "<set>" else "<from environment>"})"

/** Every configured model, in a stable order (provider, then alias).
  *
  * A model is named by its bare `alias`, or by `provider/alias` when several
  * providers use the same alias; [[label]] gives the shortest unambiguous name
  * and [[find]] accepts either spelling (case-insensitively).
  */
final class ModelCatalog(val models: List[ModelSpec]):
  private lazy val aliasCount: Map[String, Int] =
    models.groupBy(_.alias.toLowerCase).map((a, ms) => a -> ms.size)

  def isEmpty: Boolean = models.isEmpty

  /** The shortest name that identifies `m` on its own. */
  def label(m: ModelSpec): String = if aliasCount.getOrElse(m.alias.toLowerCase, 0) > 1 then m.ref else m.alias

  /** All models' labels, for messages and menus. */
  def labels: List[String] = models.map(label)

  /** The model a reference names. Throws `IllegalArgumentException` naming the
    * configured models when it matches none, or both candidates when a bare
    * alias is ambiguous. */
  def find(reference: String): ModelSpec =
    val wanted = reference.trim.toLowerCase
    if wanted.isEmpty then throw IllegalArgumentException("No model given")
    models.find(_.ref.toLowerCase == wanted) match
      case Some(m) => m
      case None =>
        models.filter(_.alias.toLowerCase == wanted) match
          case one :: Nil => one
          case Nil =>
            throw IllegalArgumentException(
              s"Unknown model '${reference.trim}'. Configured: ${if isEmpty then "(none)" else labels.mkString(", ")}"
            )
          case many =>
            throw IllegalArgumentException(
              s"Ambiguous model '${reference.trim}': defined by ${many.map(_.ref).mkString(" and ")}. Use the full name."
            )

  /** The model used when the config and the command line name none. */
  def default: ModelSpec =
    models.headOption.getOrElse(
      throw IllegalArgumentException("No models configured (see \"providers\" in the config)")
    )

object ModelCatalog:
  /** Resolve every model of every provider. `providers` falls back to
    * [[Config.DefaultProviders]] when the config defines none. */
  def from(config: Config): ModelCatalog =
    val providers = if config.providers.isEmpty then Config.DefaultProviders else config.providers
    ModelCatalog(
      providers.toList.sortBy(_._1).flatMap { (name, p) =>
        p.models.toList.sortBy(_._1).map { (alias, m) =>
          ModelSpec(name, alias, p.api, m.name.getOrElse(alias), p.url, Config.resolveApiKey(p), m)
        }
      }
    )
