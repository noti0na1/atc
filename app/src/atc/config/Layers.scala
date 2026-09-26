package atc.config

import atc.Debug
import atc.perms.{Access, FileRule, Mode, PathPattern}
import atc.platform.PlatformPath

import java.nio.file.{Files, Path}

/** The source and authority of a configuration layer. Global and explicit
  * layers may grant access anywhere. Project file rules grant access within
  * their project and constrain matching paths elsewhere. */
enum Origin(val label: String):
  /** `~/.atc/config.json`: the global policy and settings. */
  case Global extends Origin("global")
  /** The `.atc/config.json` of the project the working directory belongs to. */
  case Project extends Origin("project")
  /** A file named with `-c`. */
  case Explicit extends Origin("explicit")
  /** What `/config` set for the running session, over every file. */
  case Session extends Origin("session")

  /** Whether this layer may grant anything anywhere (the project layer may
    * grant only within its own project, and its scalar settings only narrow). */
  def grants: Boolean = this != Origin.Project

/** One configuration file, as read. `json` says which settings it actually
  * [[defines]], so a narrowing layer only narrows what it mentions (a setting
  * it leaves out is not the same as one it sets to its default). */
final case class ConfigLayer(
  origin: Origin,
  path: Option[Path],
  json: ujson.Obj,
  config: Config,
  /** The directory this layer's *relative* path patterns are read against: the
    * folder holding `.atc` for a project config (so it governs its own project
    * however deep atc runs inside it), the working directory otherwise. */
  base: Option[Path] = None
):
  def defines(key: String): Boolean = json.value.contains(key)
  def describe: String =
    val role =
      if origin == Origin.Session then "set with /config"
      else if origin.grants then "grants anywhere"
      else "grants its own project, otherwise narrows"
    val source = path.map(_.toString).getOrElse(if origin == Origin.Session then "(this session)" else "(bundled)")
    f"  ${origin.label}%-9s $source%-52s $role"

object ConfigLayer:
  /** The config file at `path`, in the role `origin`. */
  def read(origin: Origin, path: Path): ConfigLayer =
    val text =
      try Files.readString(path).nn
      catch
        case e: Exception => throw IllegalArgumentException(s"Cannot read config $path: ${Debug.message(e)}")
    // Relative patterns of a project config are read against the folder its
    // `.atc` sits in; every other layer reads them against the working directory.
    // The policy evaluates canonical paths, so the base is canonical too;
    // otherwise a project reached through a symlink would never grant access.
    val base = Option.when(origin == Origin.Project)(PlatformPath.canonical(path.getParent.nn.getParent.nn))
    val json = ObjectText.parse(text, path.toString)
    ConfigLayer(origin, Some(path), json, settings(json, path.toString), base)

  /** The starting global config as an in-memory layer, for a run without a
    * `~/.atc/config.json`. */
  def bundled: ConfigLayer =
    val where = "the bundled starting config"
    val json = ObjectText.parse(Config.globalTemplate, where)
    ConfigLayer(Origin.Global, None, json, settings(json, where), None)

  /** The settings `/config` changed for this session only, as a layer. */
  def session(json: ujson.Obj): ConfigLayer =
    ConfigLayer(Origin.Session, None, json, settings(json, "the settings of this session"), None)

  private[config] def settings(json: ujson.Obj, where: String): Config =
    try upickle.default.read[Config](json)
    catch case e: Exception => throw IllegalArgumentException(s"Invalid config $where: ${Debug.message(e)}")

/** A file rule with the folder its layer is anchored at: the folder holding
  * `.atc` for a project config (whose rules are read against it and grant only
  * inside it) and `None` for a granting layer, which grants wherever it
  * matches. Either way the rule's access is also a ceiling. */
final case class LayeredRule(rule: FileRuleConfig, base: Option[Path])

/** The configuration in force: every layer, combined.
  *
  * The file rules are [[rules]], kept per layer with their anchor; the `files`
  * field of [[settings]] holds only the granting layers' entries and must not
  * be used to build the policy. `settings.commands` / `settings.hosts` are the
  * union over every layer and are what the policy uses.
  */
final case class Configuration(
  layers: List[ConfigLayer],
  settings: Config,
  rules: List[LayeredRule],
  /** The `${VAR}` bindings from `.atc/keys.properties`; kept apart from the settings. */
  keys: KeyBindings = KeyBindings.empty
):
  def sources: List[Path] = layers.flatMap(_.path)

  /** Whether the bundled starting config stands in for a missing global one. */
  def bundledGlobal: Boolean = layers.exists(l => l.origin == Origin.Global && l.path.isEmpty)

  /** The configured file rules, in layer order. Nothing is granted here or
    * anywhere else in the program; a path is reachable only because a config
    * says so, `~/.atc/config.json` for anything and a project's own
    * `.atc/config.json` for paths inside that project. */
  def fileRules(cwd: Path): List[FileRule] =
    rules.map: r =>
      FileRule(
        // A project layer reads its relative patterns against its own folder,
        // and grants only inside it.
        PathPattern(r.rule.path, r.base.getOrElse(cwd)),
        r.rule.access.map(Access.parse),
        r.rule.classified,
        r.rule.locked,
        grantsWithin = r.base,
      )

  /** The environment variables that hold provider credentials, which commands must not inherit. */
  def keyVariables: Set[String] = settings.providers.values.flatMap(KeyBindings.variables).toSet

  /** Every configured model, resolved, with its provider's key. */
  def catalog: ModelCatalog = ModelCatalog.from(settings, keys)

  /** As [[catalog]], with the providers that configure no models listed by `discover`
    * and their lists kept in `store` between sessions. */
  def catalog(discover: ModelSpec => List[ModelSpec], store: ModelListStore): ModelCatalog =
    ModelCatalog.from(settings, keys, Some(discover), Some(store))

object Configuration:
  /** Settings that are policy: a narrowing layer may only make these stricter,
    * so they are taken from the granting layers and then tightened. Everything
    * else (models, providers, instructions, and the `commands` / `hosts` lists,
    * which every layer may add to) merges in layer order. */
  private val PolicyKeys =
    Set("files", "denyCommands", "denyHosts") ++
      Set("mode", "safeMode", "respectGitignore") ++
      Set("executionTimeoutMs", "maxToolCalls", "maxToolOutputChars")

  /** Combine the layers.
    *
    *  - **models, providers, instructions** (nothing to do with permissions)
    *    merge in layer order, the later layer winning; providers merge per
    *    provider and then per model alias, so a project config can add a model
    *    to a provider the global config defined. A project config may set
    *    nothing else of a provider (see [[requireOwnProviders]]).
    *  - **`commands` / `hosts`** are the union of every layer's list: a project
    *    config may pre-approve the commands and hosts its work needs, the way
    *    it may open its own files. Deny rules restrict all grants.
    *  - **policy settings** come from the *granting* layers (global, `-c`)
    *    merged the same way, and are then narrowed by the project layer:
    *    limits and the sandbox mode by the stricter value, `safeMode` /
    *    `respectGitignore` only towards "on".
    *  - **file rules** from every layer are kept with their anchor: a project
    *    layer's rules grant only inside its own folder, and clamp everywhere
    *    (see [[LayeredRule]] and `Policy.configPerm`).
    *  - **`denyCommands` / `denyHosts`** are refusals, so every layer's patterns
    *    apply; a narrowing layer can add to them but never drop one.
    *
    * Narrowing is order-independent (every combination is a min, an `or` or an
    * intersection), so only the granting layers care about their order.
    */
  def combine(layers: List[ConfigLayer], keys: KeyBindings = KeyBindings.empty): Configuration =
    // Validate each layer before merging so an invalid mode is attributed to the
    // file that contains it rather than to whichever layer narrows it later.
    layers.foreach(ConfigValidation.validateLayerMode)
    val (granting, narrowing) = layers.partition(_.origin.grants)
    def merged(ls: List[ConfigLayer]) = ls.map(_.json).foldLeft(ujson.Obj())(mergeJson)
    val everything = merged(layers)
    val granted = merged(granting)
    narrowing.foreach(requireOwnProviders(_, granted))
    // Non-policy settings from every layer, policy settings from the granting ones.
    val effective = ujson.Obj()
    for (k, v) <- everything.value do if !PolicyKeys.contains(k) then effective(k) = v
    for (k, v) <- granted.value do if PolicyKeys.contains(k) then effective(k) = v
    val base = ConfigLayer.settings(effective, "the merged configuration")
    val settings = narrowing.foldLeft(base)(tighten)
    val rules = layers.flatMap(l => l.config.files.map(LayeredRule(_, base = l.base)))
    rules.foreach(ConfigValidation.validateRule)
    Configuration(layers, ConfigValidation.validate(settings), rules, keys)

  /** What a narrowing layer may set on a provider: which models it offers and whether it is on. */
  private val NarrowingProviderKeys = Set("models", "enabled")

  /** A project config may add models to the providers a granting layer defines, or turn them
    * off, and nothing else: with a provider's `url`, `key` or `headers`, or a provider of its
    * own, a cloned repository could send the user's keys and prompts wherever it names. */
  private def requireOwnProviders(layer: ConfigLayer, granted: ujson.Obj): Unit =
    def refuse(what: String, why: String) = throw IllegalArgumentException(
      s"Invalid config ${layer.path.getOrElse("")}: $what: $why; endpoints and keys belong in ~/.atc/config.json"
    )
    val known = granted.value.get("providers").flatMap(_.objOpt).fold(Set.empty[String])(_.keySet.toSet)
    for
      providers <- layer.json.value.get("providers").flatMap(_.objOpt)
      (name, provider) <- providers
    do
      if !known.contains(name) then
        refuse(s"providers.$name", "a project config may not define a provider, only add models to one")
      for fields <- provider.objOpt; key <- fields.keys.find(!NarrowingProviderKeys.contains(_)) do
        refuse(s"providers.$name.$key", "a project config may set only a provider's models and enabled")

  /** Apply one narrowing layer to the settings it defines. Every field moves
    * towards "stricter" or stays put, so this can never widen the policy. */
  private def tighten(base: Config, layer: ConfigLayer): Config =
    val n = layer.config
    def onlyIfSet[T](key: String)(stricter: => T)(keep: => T): T = if layer.defines(key) then stricter else keep
    base.copy(
      mode = onlyIfSet("mode")(stricterMode(base.mode, n.mode))(base.mode),
      safeMode = base.safeMode || (layer.defines("safeMode") && n.safeMode),
      respectGitignore = base.respectGitignore || (layer.defines("respectGitignore") && n.respectGitignore),
      // A missing timeout means "no limit", so it is the *least* strict value.
      executionTimeoutMs = onlyIfSet("executionTimeoutMs") {
        (base.executionTimeoutMs, n.executionTimeoutMs) match
          case (Some(a), Some(b)) => Some(a.min(b))
          case (a, None) => a
          case (None, b) => b
      }(base.executionTimeoutMs),
      maxToolCalls = onlyIfSet("maxToolCalls")(base.maxToolCalls.min(n.maxToolCalls))(base.maxToolCalls),
      maxToolOutputChars =
        onlyIfSet("maxToolOutputChars")(base.maxToolOutputChars.min(n.maxToolOutputChars))(base.maxToolOutputChars),
      // Refusals only ever add (the same pattern in two layers is still one rule).
      denyCommands = (base.denyCommands ++ n.denyCommands).distinct,
      denyHosts = (base.denyHosts ++ n.denyHosts).distinct,
    )

  /** The stricter of two sandbox modes (`readonly` < `local` < `full`); an unset
    * mode means the most permissive one. Both are already validated per layer. */
  private def stricterMode(a: Option[String], b: Option[String]): Option[String] =
    def parsed(o: Option[String]) = o.map(Mode.parse).getOrElse(Mode.Full)
    Some(Mode.fromOrdinal(parsed(a).ordinal.min(parsed(b).ordinal)).label)

  /** List settings extend rather than replace (a later layer can add a deny
    * pattern, and cannot drop one an earlier layer set). */
  private val ListKeys = Set("files", "commands", "hosts", "denyCommands", "denyHosts")

  /** `over` on top of `base`: list settings are concatenated, `providers` are
    * merged by name (and within one, its `models` by alias, a redefined alias
    * replacing the whole model entry), everything else is overwritten. So a
    * project config can add a model to a provider the global config defined
    * without repeating its url and key. */
  def mergeJson(base: ujson.Obj, over: ujson.Obj): ujson.Obj =
    overlay(base, over): (k, a, b) =>
      (a, b) match
        case (x: ujson.Arr, y: ujson.Arr) if ListKeys.contains(k) => ujson.Arr(x.value ++ y.value)
        case (x: ujson.Obj, y: ujson.Obj) if k == "providers" => mergeProviders(x, y)
        case _ => b

  private def mergeProviders(base: ujson.Obj, over: ujson.Obj): ujson.Obj =
    overlay(base, over): (_, a, b) =>
      (a, b) match
        case (x: ujson.Obj, y: ujson.Obj) => mergeProvider(x, y)
        case _ => b

  private def mergeProvider(base: ujson.Obj, over: ujson.Obj): ujson.Obj =
    overlay(base, over): (k, a, b) =>
      (a, b) match
        case (x: ujson.Obj, y: ujson.Obj) if k == "models" => overlay(x, y)((_, _, m) => m)
        case _ => b

  /** `over` laid over `base` key by key; `join` decides what happens where
    * both define the same key. */
  private def overlay(base: ujson.Obj, over: ujson.Obj)(
    join: (String, ujson.Value, ujson.Value) => ujson.Value
  ): ujson.Obj =
    val out = ujson.Obj()
    for (k, v) <- base.value do out(k) = v
    for (k, v) <- over.value do out(k) = out.value.get(k).fold(v)(old => join(k, old, v))
    out
