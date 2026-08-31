package atc.config

import upickle.default.*

import atc.TextFiles
import atc.perms.{Access, Mode, PathPattern}
import atc.platform.PlatformPath

import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, Paths, StandardCopyOption}

/** One model of a provider: the id the provider knows it by, plus the
  * settings that apply to this model only. Everything about *where* to send
  * the request (API shape, URL, key) belongs to its [[ProviderConfig]]. */
case class ModelConfig(
  /** The provider's model id. Defaults to the alias the model is listed under,
    * so `"models": { "gpt-5": {} }` needs no `name`. */
  name: Option[String] = None,
  /** Enable the provider's built-in web search tool (Anthropic
    * `web_search`, OpenAI Responses `web_search`, Chat Completions
    * `web_search_options`). */
  webSearch: Boolean = false,
  /** Reasoning effort: OpenAI `none|minimal|low|medium|high|xhigh|max`,
    * Anthropic `low|medium|high|xhigh|max` (`output_config.effort`). */
  reasoning: Option[String] = None,
  /** Anthropic: adaptive thinking (default on); `false` disables it. OpenAI-
    * compatible vendors with a `thinking: {"type": ...}` switch (DeepSeek, GLM,
    * Kimi, MiniMax): sends `enabled`/`disabled`; leave it unset for OpenAI
    * itself, which rejects the parameter. */
  thinking: Option[Boolean] = None,
  /** OpenAI Responses: stream reasoning summaries (`auto|concise|detailed`);
    * OpenAI only sends them when asked. */
  reasoningSummary: Option[String] = None,
  maxTokens: Option[Int] = None,
  /** The model's context window, in tokens (`200000`, `"256k"`, `"1m"`; see
    * [[Tokens]]). When a conversation would exceed it, the oldest exchanges
    * are dropped from what the model sees. Unset: never cut. */
  contextWindow: Option[Tokens] = None,
  temperature: Option[Double] = None,
  /** Anthropic web-search tool version: `20260209` (default) or `20250305`. */
  webSearchVersion: Option[String] = None,
  /** Optional human-facing name used in the banner and model list. It never
    * changes the alias used to select the model or the id sent to the provider. */
  displayName: Option[String] = None,
) derives ReadWriter

/** One LLM endpoint and the models reachable through it. `api` is the wire
  * protocol: `anthropic`, `openai` (Chat Completions; also any
  * OpenAI-compatible server such as Ollama, vLLM, LM Studio via `url`),
  * `openai-responses` (the Responses API), or `echo` (the key-less test
  * model). */
case class ProviderConfig(
  /** Optional only so a later layer can add models to a provider an earlier
    * one defined; every provider needs an `api` once the layers are combined. */
  api: Option[String] = None,
  /** Base URL override for this endpoint. */
  url: Option[String] = None,
  /** The key: a literal, or a `${VAR}` reference resolved from `.atc/keys.properties`
    * and then the environment (see [[KeyBindings]]). */
  key: Option[String] = None,
  /** The name of a variable holding the key, resolved the same way. */
  keyEnv: Option[String] = None,
  /** The provider's models, by alias. */
  models: Map[String, ModelConfig] = Map.empty,
) derives ReadWriter

/** One file-permission rule. See `atc.perms.Policy` for the semantics. */
case class FileRuleConfig(
  path: String,
  access: Option[String] = None,
  classified: Option[Boolean] = None,
  locked: Boolean = false,
) derives ReadWriter

case class Config(
  /** The agent's model: a model alias, or `provider/alias` when two providers
    * use the same alias. Unset picks the first configured model. */
  model: Option[String] = None,
  /** The isolated, effect-free model trusted by `classifiedChat`, named the
    * same way. Unset means classified data is never sent to a model. */
  classifiedModel: Option[String] = None,
  /** LLM endpoints by name, each with its own models. */
  providers: Map[String, ProviderConfig] = Map.empty,
  files: List[FileRuleConfig] = Nil,
  /** Command-line patterns the agent may run without asking (`*` wildcard;
    * a pattern without `*` also matches by word prefix). */
  commands: List[String] = Nil,
  /** Host patterns the agent may reach without asking. */
  hosts: List[String] = Nil,
  /** Command-line patterns that are always refused (same syntax as `commands`).
    * A matching command fails, and a `requestExec` that would permit one is
    * rejected without asking the user. Deny wins over every allow rule. */
  denyCommands: List[String] = Nil,
  /** Host patterns that are always refused, like `denyCommands` for `hosts`. */
  denyHosts: List[String] = Nil,
  /** Hide paths ignored by `.gitignore` (and `.git` itself) from directory
    * listings and searches — default true. Reading such a path by name still
    * works; this only keeps build output and dependencies out of the way. */
  respectGitignore: Boolean = true,
  /** Compile agent code with `import language.experimental.safe`. On unless a
    * *granting* layer turns it off explicitly: it is a latch, so a narrowing
    * layer can switch it on and never back off. */
  safeMode: Boolean = true,
  /** Initial sandbox mode: `readonly` (read files only), `local` (read/write
    * files, run commands) or `full` (also network). `/mode` switches at run time. */
  mode: Option[String] = None,
  /** Wall-clock limit for one snippet, excluding time spent waiting for the user
    * or for a command (`exec` has its own `ExecOptions.timeoutMs`). */
  executionTimeoutMs: Option[Long] = Some(300000L),
  /** Tool calls per user turn before the agent checks in: an interactive session
    * asks the user whether to continue for another `maxToolCalls`, a `-p` run stops. */
  maxToolCalls: Int = 200,
  /** Max characters of tool output returned to the model. */
  maxToolOutputChars: Int = 40000,
  /** Extra text appended to the system prompt (project conventions etc.). */
  instructions: Option[String] = None,
  /** After each turn, ask the agent model to guess the next request and offer
    * it as ghost text at the prompt (Tab / → accepts). One extra, small model
    * call per turn; `false` turns it off. */
  predictInput: Boolean = true,
) derives ReadWriter

object Config:
  /** `~/.atc/config.json`: the base of the whole policy. Nothing is permitted
    * behind it, so it is also where every grant has to be written. */
  def globalPath: Path = PlatformPath.userHome.resolve(".atc").nn.resolve("config.json").nn

  /** `<dir>/.atc/config.json`, the project config of `dir`. */
  def projectPath(dir: Path): Path = dir.resolve(".atc").resolve("config.json")

  /** `<dir>/.atc/keys.properties`, the key bindings of `dir`. */
  def keysPath(dir: Path): Path = dir.resolve(".atc").resolve(KeysFile)

  /** The name of the key bindings file, in `.atc` beside a `config.json`. */
  val KeysFile = "keys.properties"

  /** The project a directory belongs to: the nearest ancestor of `from`
    * (itself included) whose `.atc` holds a `config.json` or a `keys.properties`. A
    * project config governs the folder its `.atc` sits in, so running atc in a
    * sub-directory still picks up (and is bound by) the project's own
    * configuration and keys. */
  def projectRoot(from: Path): Option[Path] =
    def isProject(d: Path) = Files.isRegularFile(projectPath(d)) || Files.isRegularFile(keysPath(d))
    def up(p: Path | Null): Option[Path] = p match
      case null => None
      case d: Path => if isProject(d) then Some(d) else up(d.getParent)
    up(from.toAbsolutePath.nn.normalize)

  /** Create the global configuration and adjacent key bindings if they are
    * missing, ensuring that narrowing layers have a base. Key bindings are
    * readable only by the owner because they may contain API keys. Returns the
    * paths created. */
  def ensureGlobal(path: Path = globalPath): List[Path] =
    val keys = path.getParent.nn.resolve(KeysFile).nn
    List(
      writeIfMissing(path, globalTemplate, ownerOnly = false),
      writeIfMissing(keys, keysTemplate, ownerOnly = true),
    ).flatten

  private def writeIfMissing(target: Path, content: String, ownerOnly: Boolean): Option[Path] =
    Option.when(!Files.exists(target)) {
      Option(target.getParent).foreach(Files.createDirectories(_))
      if ownerOnly then writeOwnerOnly(target, content) else Files.writeString(target, content)
      target
    }

  /** Write `content` to `target` with owner-only access on POSIX file systems,
    * falling back to a regular write when POSIX permissions are unavailable. */
  private def writeOwnerOnly(target: Path, content: String): Unit =
    import java.nio.file.attribute.PosixFilePermissions
    val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    try
      Files.createFile(target, ownerOnly) // ensureGlobal/initProject call this only for missing files
      Files.writeString(target, content)
    catch case _: UnsupportedOperationException => Files.writeString(target, content)

  /** Create the starter project configuration for `dir` and ensure that its
    * `.gitignore` excludes `keys.properties`. The configuration belongs in the
    * repository; `.atc/keys.properties` does not. Returns the paths created or
    * modified, or an empty list if the configuration already exists. */
  def initProject(dir: Path): List[Path] =
    val config = projectPath(dir)
    if Files.exists(config) then Nil
    else
      Files.createDirectories(config.getParent)
      Files.writeString(config, projectTemplate)
      val ignore = config.getParent.nn.resolve(".gitignore").nn
      val ignoreChanged = ensureIgnored(ignore, KeysFile)
      config :: Option.when(ignoreChanged)(ignore).toList

  private def ensureIgnored(path: Path, entry: String): Boolean =
    val current = Option.when(Files.exists(path))(Files.readString(path).nn)
    // Git uses the last matching rule; an earlier exclusion followed by
    // `!keys.properties` does not actually protect the key file.
    val lastRule = current.toList.flatMap(text => TextFiles.splitLines(TextFiles.stripBom(text)).lines)
      .map(_.trim).filter(line => line == entry || line == s"!$entry").lastOption
    if lastRule.contains(entry) then false
    else
      Files.writeString(path, TextFiles.appendLine(current.getOrElse(""), entry))
      true

  // ── layers ────────────────────────────────────────────────────────

  /** Load every layer and combine them: `~/.atc/config.json` ← the project's
    * `.atc/config.json` ← `-c <file>`. See [[combine]] for what "later" means
    * per setting. With `bundledGlobal`, the starting config stands in for a
    * missing `~/.atc/config.json` (the user declined to write it), as a layer
    * with no path. */
  def load(cwd: Path, explicit: Option[Path], bundledGlobal: Boolean): Configuration =
    load(cwd, explicit, globalPath, bundledGlobal)

  /** As [[load]], with the global path given explicitly (tests). */
  def load(cwd: Path, explicit: Option[Path], global: Path, bundledGlobal: Boolean = false): Configuration =
    explicit.foreach { path =>
      if !Files.exists(path) then throw IllegalArgumentException(s"Explicit config does not exist: $path")
      if !Files.isRegularFile(path) then throw IllegalArgumentException(s"Explicit config is not a regular file: $path")
    }
    val root = projectRoot(cwd)
    val project = root.map(projectPath)
    val candidates =
      List(Origin.Global -> global) ++ project.map(Origin.Project -> _) ++ explicit.map(Origin.Explicit -> _)
    // A path named twice is read once, in the first role it appears in. That
    // keeps `~/.atc/config.json` a granting layer when atc runs in the home
    // directory, and keeps `-c ./.atc/config.json` a narrowing one.
    val present = candidates
      .filter((_, p) => Files.isRegularFile(p))
      .distinctBy((_, p) => p.toAbsolutePath.normalize)
    val layers = present.map((origin, path) => readLayer(origin, path))
    val bundled = Option.when(bundledGlobal && !layers.exists(_.origin == Origin.Global))(bundledLayer)
    // Keys are read separately, most specific first: they are secrets, not
    // settings, so they never take part in the layer merge.
    val keys = KeyBindings.load(root.map(keysPath).toList :+ global.getParent.nn.resolve(KeysFile).nn)
    combine(bundled.toList ++ layers, keys)

  /** The starting global config as an in-memory layer, for a run without a
    * `~/.atc/config.json`. */
  private def bundledLayer: ConfigLayer =
    val where = "the bundled starting config"
    ConfigLayer(Origin.Global, None, readObj(globalTemplate, where), parse(globalTemplate, where), None)

  private def readLayer(origin: Origin, path: Path): ConfigLayer =
    val text =
      try Files.readString(path).nn
      catch
        case e: Exception => throw IllegalArgumentException(s"Cannot read config $path: ${e.getMessage}")
    // Relative patterns of a project config are read against the folder its
    // `.atc` sits in; every other layer reads them against the working directory.
    // The policy evaluates canonical paths, so canonicalize the base as well.
    // Otherwise, a project reached through a symlink would never grant access.
    val base = Option.when(origin == Origin.Project)(PlatformPath.canonical(path.getParent.nn.getParent.nn))
    ConfigLayer(origin, Some(path), readObj(text, path.toString), parse(text, path.toString), base)

  private def readObj(text: String, where: String): ujson.Obj =
    val parsed =
      try ujson.read(TextFiles.stripBom(text))
      catch case e: Exception => throw IllegalArgumentException(s"Cannot parse config $where: ${e.getMessage}")
    parsed match
      case o: ujson.Obj => o
      case _ => throw IllegalArgumentException(s"Config $where must be a JSON object")

  private def parse(text: String, where: String): Config =
    try read[Config](readObj(text, where))
    catch case e: Exception => throw IllegalArgumentException(s"Invalid config $where: ${e.getMessage}")

  /** Settings that are policy: a narrowing layer may only make these stricter,
    * so they are taken from the granting layers and then tightened. Everything
    * else (models, providers, instructions, and the `commands` / `hosts` lists,
    * which every layer may add to) simply merges in layer order. */
  private val PolicyKeys =
    Set("files", "denyCommands", "denyHosts") ++
      Set("mode", "safeMode", "respectGitignore") ++
      Set("executionTimeoutMs", "maxToolCalls", "maxToolOutputChars")

  /** Combine the layers.
    *
    *  - **models, providers, instructions** (nothing to do with permissions)
    *    merge in layer order, the later layer winning; providers merge per
    *    provider and then per model alias, so a project config can add a model
    *    to a provider the global config defined.
    *  - **`commands` / `hosts`** are the union of every layer's list: a project
    *    config may pre-approve the commands and hosts its work needs, the way
    *    it may open its own files. The deny lists are the backstop.
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
    layers.foreach(validateLayerMode)
    val (granting, narrowing) = layers.partition(_.origin.grants)
    def merged(ls: List[ConfigLayer]) = ls.map(_.json).foldLeft(ujson.Obj())(mergeJson)
    val everything = merged(layers)
    val granted = merged(granting)
    // Non-policy settings from every layer, policy settings from the granting ones.
    val effective = ujson.Obj()
    for (k, v) <- everything.value do if !PolicyKeys.contains(k) then effective(k) = v
    for (k, v) <- granted.value do if PolicyKeys.contains(k) then effective(k) = v
    val base = parse(ujson.write(effective), "the merged configuration")
    val settings = narrowing.foldLeft(base)(tighten)
    val rules = layers.flatMap(l => l.config.files.map(LayeredRule(_, base = l.base)))
    rules.foreach(validateRule)
    Configuration(layers, validate(settings), rules, keys)

  /** Apply one narrowing layer to the settings it defines. Every field moves
    * towards "stricter" or stays put, so this can never widen the policy. */
  private def tighten(base: Config, layer: ConfigLayer): Config =
    val n = layer.config
    def onlyIfSet[T](key: String)(stricter: => T)(keep: => T): T = if layer.defines(key) then stricter else keep
    base.copy(
      mode = onlyIfSet("mode")(stricterMode(base.mode, n.mode))(base.mode),
      // A latch: `|| ` can only move it towards on, whatever the layer says.
      safeMode = base.safeMode || onlyIfSet("safeMode")(n.safeMode)(false),
      respectGitignore = base.respectGitignore || onlyIfSet("respectGitignore")(n.respectGitignore)(false),
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

  /** Reject an invalid `mode` and identify the layer that defines it. `combine`
    * validates every layer up front, so `stricterMode` can assume valid input. */
  private def validateLayerMode(layer: ConfigLayer): Unit =
    layer.config.mode.foreach { m =>
      try Mode.parse(m)
      catch
        case e: IllegalArgumentException =>
          throw IllegalArgumentException(
            s"Invalid config ${layer.path.map(_.toString).getOrElse(s"(${layer.origin.label} layer)")}: ${e.getMessage}"
          )
    }

  /** The stricter of two sandbox modes (`readonly` < `local` < `full`); an unset
    * mode means the most permissive one. Both are already validated per layer. */
  private def stricterMode(a: Option[String], b: Option[String]): Option[String] =
    def parsed(o: Option[String]) = o.map(Mode.parse).getOrElse(Mode.Full)
    Some(Mode.fromOrdinal(parsed(a).ordinal.min(parsed(b).ordinal)).label)

  /** A `files` entry of any layer (the project layer's included, which
    * `settings.files` leaves out): the path must be a usable pattern and the
    * access level one the policy knows, so a typo is reported as a config
    * error here rather than when the policy is built. */
  private def validateRule(r: LayeredRule): Unit =
    val path = r.rule.path
    def invalid(what: String) = IllegalArgumentException(s"Invalid config: files entry '$path': $what")
    if path.trim.isEmpty then throw invalid("the path must not be blank")
    try PathPattern(path, r.base.getOrElse(Paths.get("").toAbsolutePath))
    catch case e: Exception => throw invalid(s"not a valid pattern (${e.getMessage})")
    r.rule.access.foreach { a =>
      try Access.parse(a)
      catch case e: IllegalArgumentException => throw invalid(e.getMessage.nn)
    }

  /** Reject limits that would otherwise fail much later in output slicing,
    * timeout accounting, or provider request construction. */
  private val ReasoningEfforts = Set("none", "minimal", "low", "medium", "high", "xhigh", "max")
  private val ReasoningSummaries = Set("auto", "concise", "detailed")
  private val ProviderApis =
    Set("anthropic", "claude", "openai-responses", "responses", "openai", "openai-chat", "chat", "echo")
  private val AnthropicWebSearchVersions = Set("20250305", "20260209")

  private def invalid(message: String): Nothing = throw IllegalArgumentException(s"Invalid config: $message")

  private def requireValid(condition: Boolean, message: => String): Unit =
    if !condition then invalid(message)

  private def requirePositive(name: String, value: Long): Unit =
    requireValid(value > 0, s"$name must be greater than zero (was $value)")

  private def validateChoice(where: String, value: String, allowed: Set[String]): Unit =
    requireValid(value == value.trim, s"$where must not start or end with whitespace (was '$value')")
    requireValid(
      allowed.contains(value.trim.toLowerCase(java.util.Locale.ROOT)),
      s"$where must be one of ${allowed.mkString("|")} (was '$value')"
    )

  private def validateModel(provider: String, alias: String, model: ModelConfig): Unit =
    val where = s"providers.$provider.models.$alias"
    requireValid(alias.trim.nonEmpty, s"model aliases of provider '$provider' must not be blank")
    requireValid(alias == alias.trim, s"model alias '$alias' must not start or end with whitespace")
    requireValid(!alias.contains('/'), s"model alias '$alias' must not contain '/'")
    requireValid(!model.name.exists(_.trim.isEmpty), s"$where.name must not be blank")
    requireValid(!model.displayName.exists(_.trim.isEmpty), s"$where.displayName must not be blank")
    requireValid(
      !model.displayName.exists(name => name != name.trim),
      s"$where.displayName must not start or end with whitespace"
    )
    requireValid(
      !model.displayName.exists(name => name.contains('\n') || name.contains('\r')),
      s"$where.displayName must be a single line"
    )
    model.maxTokens.foreach(requirePositive(s"$where.maxTokens", _))
    model.contextWindow.foreach(tokens => requirePositive(s"$where.contextWindow", tokens.toInt))
    model.temperature.foreach(value => requireValid(value.isFinite, s"$where.temperature must be finite"))
    model.reasoning.foreach(validateChoice(s"$where.reasoning", _, ReasoningEfforts))
    model.reasoningSummary.foreach(validateChoice(s"$where.reasoningSummary", _, ReasoningSummaries))
    model.webSearchVersion.foreach(validateChoice(s"$where.webSearchVersion", _, AnthropicWebSearchVersions))

  private def validateProvider(name: String, provider: ProviderConfig): Unit =
    requireValid(name.trim.nonEmpty, "provider names must not be blank")
    requireValid(name == name.trim, s"provider name '$name' must not start or end with whitespace")
    // `api` may be absent from a layer that only extends an earlier provider, but
    // the fully merged provider must define it.
    requireValid(
      provider.api.exists(_.trim.nonEmpty),
      s"provider '$name' has no api (expected anthropic | openai | openai-responses | echo)"
    )
    provider.api.foreach(api => validateChoice(s"providers.$name.api", api, ProviderApis))
    provider.models.foreach((alias, model) => validateModel(name, alias, model))

  def validate(config: Config): Config =
    requirePositive("maxToolOutputChars", config.maxToolOutputChars)
    requireValid(config.maxToolCalls >= 0, s"maxToolCalls must be non-negative (was ${config.maxToolCalls})")
    config.executionTimeoutMs.foreach(requirePositive("executionTimeoutMs", _))
    config.mode.foreach { m =>
      try Mode.parse(m)
      catch case e: IllegalArgumentException => invalid(e.getMessage.nn)
    }
    config.providers.foreach(validateProvider)
    // Fail here rather than at the first request: a typo in `model` is a
    // config error, and the catalog message lists what is configured.
    val catalog = ModelCatalog.from(config)
    val duplicateRefs =
      catalog.models.groupBy(_.ref.toLowerCase(java.util.Locale.ROOT)).values.filter(_.size > 1).toList
    requireValid(
      duplicateRefs.isEmpty,
      s"model references must be unique ignoring case: ${duplicateRefs.flatten.map(_.ref).sorted.mkString(", ")}"
    )
    config.model.foreach(catalog.find)
    config.classifiedModel.foreach(catalog.find)
    config

  /** List settings extend rather than replace (a later layer can add a deny
    * pattern, and cannot drop one an earlier layer set). */
  private val ListKeys = Set("files", "commands", "hosts", "denyCommands", "denyHosts")

  /** `over` on top of `base`: list settings are concatenated, `providers` are
    * merged by name (and within one, its `models` by alias, a redefined alias
    * replacing the whole model entry), everything else is overwritten. So a
    * project config can add a model to a provider the global config defined
    * without repeating its url and key. */
  def mergeJson(base: ujson.Obj, over: ujson.Obj): ujson.Obj =
    overlay(base, over) { (k, a, b) =>
      (a, b) match
        case (x: ujson.Arr, y: ujson.Arr) if ListKeys.contains(k) => ujson.Arr(x.value ++ y.value)
        case (x: ujson.Obj, y: ujson.Obj) if k == "providers" => mergeProviders(x, y)
        case _ => b
    }

  private def mergeProviders(base: ujson.Obj, over: ujson.Obj): ujson.Obj =
    overlay(base, over) { (_, a, b) =>
      (a, b) match
        case (x: ujson.Obj, y: ujson.Obj) => mergeProvider(x, y)
        case _ => b
    }

  private def mergeProvider(base: ujson.Obj, over: ujson.Obj): ujson.Obj =
    overlay(base, over) { (k, a, b) =>
      (a, b) match
        case (x: ujson.Obj, y: ujson.Obj) if k == "models" => overlay(x, y)((_, _, m) => m)
        case _ => b
    }

  /** `over` laid over `base` key by key; `join` decides what happens where
    * both define the same key. */
  private def overlay(base: ujson.Obj, over: ujson.Obj)(
    join: (String, ujson.Value, ujson.Value) => ujson.Value
  ): ujson.Obj =
    val out = ujson.Obj()
    for (k, v) <- base.value do out(k) = v
    for (k, v) <- over.value do out(k) = out.value.get(k).fold(v)(old => join(k, old, v))
    out

  private val EnvRef = """\$\{([A-Za-z_][A-Za-z0-9_]*)\}""".r

  /** A `${VAR}` reference resolved through `bindings`; anything else is the
    * literal value. `None` when nothing binds the variable. */
  def resolveEnvRef(value: String, bindings: KeyBindings = KeyBindings.empty): Option[String] = value match
    case EnvRef(name) => bindings.get(name.nn)
    case literal => Some(literal)

  /** The provider's key: `key` (a literal or `${VAR}`), else the variable
    * `keyEnv` names, else none — leaving the SDK to resolve its own default
    * variable. `${VAR}` and `keyEnv` are looked up in `.atc/keys.properties`
    * before the environment. */
  def resolveApiKey(p: ProviderConfig, bindings: KeyBindings = KeyBindings.empty): Option[String] =
    p.key.flatMap(resolveEnvRef(_, bindings)).orElse(p.keyEnv.flatMap(bindings.get))

  // ── editing a config file in place ────────────────────────────────

  /** Set one top-level key of a config file, keeping the rest of the text as
    * it is (a config is hand-formatted: blank lines, several patterns per
    * line, and re-serialising it would lose that). See [[withTopLevel]]; the
    * positions come from the [[ObjectText]] scanner. */
  def setTopLevel(path: Path, key: String, value: ujson.Value, after: List[String] = Nil): Unit =
    val text =
      try Files.readString(path).nn
      catch case e: Exception => throw IllegalArgumentException(s"Cannot read config $path: ${e.getMessage}")
    val updated = withTopLevel(text, key, value, after, path.toString)
    // Preserve intentional shared configs: resolve an existing symlink and
    // atomically replace its target, rather than replacing the link itself.
    val target = path.toRealPath().nn
    val temp = Files.createTempFile(target.getParent.nn, s".${target.getFileName}.", ".tmp").nn
    try
      Files.writeString(temp, updated)
      try Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target))
      catch case _: UnsupportedOperationException => ()
      try Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      catch case _: AtomicMoveNotSupportedException => Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(temp)

  /** `text` (a JSON object) with the top-level `key` set to `value`: an
    * existing key keeps its place and only its value changes; a new one is
    * added after the first of `after` that is present, else first, indented
    * like the others. Everything else in the text is untouched. */
  def withTopLevel(
    text: String,
    key: String,
    value: ujson.Value,
    after: List[String] = Nil,
    where: String = "config"
  ): String =
    readObj(text, where) // fail clearly on anything that is not a JSON object
    val obj = ObjectText.scan(text)
    val rendered = ujson.write(value)
    // ujson honors the final occurrence of a duplicate key. Rewrite that occurrence;
    // changing an earlier one would have no effect on the parsed value.
    obj.members.findLast(_.key == key) match
      case Some(m) => text.substring(0, m.valueStart) + rendered + text.substring(m.valueEnd)
      case None =>
        val entry = s"${ujson.write(ujson.Str(key))}: $rendered"
        after.flatMap(k => obj.members.find(_.key == k)).headOption match
          case Some(prev) =>
            text.substring(0, prev.valueEnd) + s",${obj.separator}$entry" + text.substring(prev.valueEnd)
          case None if obj.members.isEmpty =>
            val separator = obj.separator
            val newline = TextFiles.firstLineEnding(separator).fold(TextFiles.DefaultLineEnding)(_.text)
            text.substring(0, obj.open + 1) + separator + entry + newline + text.substring(obj.close)
          case None =>
            text.substring(0, obj.open + 1) + s"${obj.separator}$entry," + text.substring(obj.open + 1)

  /** The starter global config written by `--init-global`. */
  def globalTemplate: String = resource("/atc/config-template.json")

  /** The starter key bindings written beside it. */
  def keysTemplate: String = resource("/atc/keys-template.properties")

  /** The starter project config written by `--init`. */
  def projectTemplate: String = resource("/atc/project-template.json")

  private def resource(path: String): String = atc.Resources.text(path).getOrElse(
    throw IllegalStateException(s"config template resource missing ($path)")
  )
