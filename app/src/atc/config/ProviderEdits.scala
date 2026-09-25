package atc.config

import scala.collection.mutable

/** The config changes `/providers` makes. Each goes to the file holding the
  * most of its path: layers merge a model entry as a whole, so writing part of
  * an entry to another file would replace the entry defined there. */
object ProviderEdits:
  /** Set (`Some`) or remove (`None`) the member at `path`. */
  final case class Edit(path: List[String], value: Option[ujson.Value])

  /** A model `/providers` can offer: a configured entry, or one the provider lists. */
  enum Choice:
    case Configured(alias: String, model: ModelConfig)
    case Listed(spec: ModelSpec)

  /** Turn `provider` on or off; on removes the switch, as that is the default. */
  def setEnabled(provider: String, on: Boolean): List[Edit] =
    List(Edit(List("providers", provider, "enabled"), Option.when(!on)(ujson.False)))

  /** Drop every model entry, so the provider offers all the models it lists. */
  def showAll(provider: String): List[Edit] = List(Edit(List("providers", provider, "models"), None))

  /** The configured entries, then the listed models no entry names. */
  def choices(provider: ProviderConfig, listed: List[ModelSpec]): List[Choice] =
    val configured = provider.models.toList.sortBy(_._1)
    val named = configured.map((alias, m) => m.name.getOrElse(alias)).toSet
    configured.map(Choice.Configured(_, _)) ++ listed.filterNot(s => named.contains(s.modelId)).map(Choice.Listed(_))

  /** What a provider with entries offers, for the `/providers` row: how many of its
    * choices (entries, and the `listed` models no entry names) are on. */
  def offered(provider: ProviderConfig, listed: List[ModelSpec]): String =
    val on = provider.models.count(_._2.enabled)
    val all = choices(provider, listed).size
    if on == all then s"$on model${if on == 1 then "" else "s"} on" else s"$on of $all models on"

  /** The edits that leave exactly `chosen` enabled: a configured entry is
    * switched, a listed model becomes an entry with what the list reported. */
  def shortlist(providerName: String, provider: ProviderConfig, choices: List[Choice], chosen: Set[Choice])
    : List[Edit] =
    val models = List("providers", providerName, "models")
    val taken = mutable.Set.from(provider.models.keys)
    choices.flatMap:
      case c @ Choice.Configured(alias, m) =>
        val on = chosen.contains(c)
        Option.when(on != m.enabled)(Edit(models :+ alias :+ "enabled", Option.when(!on)(ujson.False)))
      case c @ Choice.Listed(spec) if chosen.contains(c) =>
        val alias = aliasFor(spec.modelId, taken.toSet)
        taken += alias
        Some(Edit(models :+ alias, Some(entry(alias, spec))))
      case _ => None

  /** An alias for a listed id: aliases cannot contain `/`, so an OpenRouter id
    * such as `vendor/model` becomes `model`, or `vendor-model` when taken. */
  def aliasFor(id: String, taken: Set[String]): String =
    val candidates = LazyList(id.split('/').last, id.replace('/', '-')) ++
      LazyList.from(2).map(n => s"${id.replace('/', '-')}-$n")
    candidates.find(a => a.trim.nonEmpty && !taken.contains(a)).get

  private def entry(alias: String, spec: ModelSpec): ujson.Value =
    val s = spec.settings
    ujson.Obj.from(
      Option.when(alias != spec.modelId)("name" -> ujson.Str(spec.modelId)).toList ++
        s.displayName.map("displayName" -> ujson.Str(_)) ++
        s.contextWindow.map { t =>
          val short = Tokens.format(t)
          "contextWindow" -> short.toIntOption.fold[ujson.Value](ujson.Str(short))(ujson.Num(_))
        } ++
        s.efforts.map(e => "efforts" -> ujson.Arr.from(e.map(ujson.Str(_)))) ++
        s.thinking.map("thinking" -> ujson.Bool(_))
    )

  /** The layer an edit of `path` belongs to: the last one holding the longest
    * prefix of it, at least `providers.<name>`. */
  def owner(layers: List[ConfigLayer], path: List[String]): Option[ConfigLayer] =
    def depth(json: ujson.Value, rest: List[String]): Int = (json, rest) match
      case (o: ujson.Obj, key :: more) if o.value.contains(key) => 1 + depth(o(key), more)
      case _ => 0
    val scored = layers.map(l => l -> depth(l.json, path))
    val best = scored.map(_._2).maxOption.getOrElse(0)
    Option.when(best >= 2)(scored.filter(_._2 == best).last._1)
