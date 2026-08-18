package atc.config

import upickle.default.*

import java.nio.file.{Files, Path, Paths}

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
  /** Anthropic: adaptive thinking (default on). Set `false` to disable. */
  thinking: Option[Boolean] = None,
  /** OpenAI Responses: stream reasoning summaries (`auto|concise|detailed`);
    * OpenAI only sends them when asked. */
  reasoningSummary: Option[String] = None,
  maxTokens: Option[Int] = None,
  temperature: Option[Double] = None,
  /** Anthropic web-search tool version: `20260209` (default) or `20250305`. */
  webSearchVersion: Option[String] = None,
) derives ReadWriter

/** One LLM endpoint and the models reachable through it. `api` is the wire
  * protocol: `anthropic`, `openai` (Chat Completions; also any
  * OpenAI-compatible server such as Ollama, vLLM, LM Studio via `url`),
  * `openai-responses` (the Responses API), or `echo` (the key-less test
  * model). */
case class ProviderConfig(
  api: String,
  /** Base URL override for this endpoint. */
  url: Option[String] = None,
  /** Literal key, or `${ENV_VAR}`. When absent, `keyEnv` / the SDK's own
    * credential resolution (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, ...) is used. */
  key: Option[String] = None,
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
  /** The model that handles classified data (`chat(Classified)`), named the
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
  /** Add the built-in classified patterns (`.ssh`, `.env`, ...) — default true. */
  defaultClassified: Boolean = true,
  /** Hide paths ignored by `.gitignore` (and `.git` itself) from directory
    * listings and searches — default true. Reading such a path by name still
    * works; this only keeps build output and dependencies out of the way. */
  respectGitignore: Boolean = true,
  /** Compile agent code with `import language.experimental.safe`. */
  safeMode: Boolean = true,
  /** Initial sandbox mode: `readonly` (read files only), `local` (read/write
    * files, run commands) or `full` (also network). `/mode` switches at run time. */
  mode: Option[String] = None,
  executionTimeoutMs: Option[Long] = Some(180000L),
  /** Max tool calls per user turn before the agent is stopped. */
  maxToolCalls: Int = 60,
  /** Max characters of tool output returned to the model. */
  maxToolOutputChars: Int = 40000,
  /** Extra text appended to the system prompt (project conventions etc.). */
  instructions: Option[String] = None,
) derives ReadWriter

object Config:
  val DefaultClassifiedPatterns: List[String] = List(
    ".ssh",
    ".gnupg",
    ".env",
    ".env.*",
    ".netrc",
    ".npmrc",
    ".pypirc",
    ".docker",
    ".kube",
    ".aws",
    ".azure",
    ".gcloud",
    "*.pem",
    "id_rsa",
    "id_ed25519",
  )

  /** Built-in providers used when the config defines none. */
  val DefaultProviders: Map[String, ProviderConfig] = Map(
    "anthropic" -> ProviderConfig(
      api = "anthropic",
      models = Map("claude" -> ModelConfig(name = Some("claude-opus-5"), webSearch = true))
    ),
    "openai" -> ProviderConfig(
      api = "openai-responses",
      models = Map("gpt" -> ModelConfig(name = Some("gpt-5"), webSearch = true))
    ),
  )

  def globalPath: Path =
    val xdg = Option(System.getenv("XDG_CONFIG_HOME")).filter(_.nonEmpty).map(Paths.get(_))
    xdg.getOrElse(Paths.get(System.getProperty("user.home"), ".config")).resolve("atc").resolve("config.json")

  def projectPath(cwd: Path): Path = cwd.resolve(".atc").resolve("config.json")

  /** Load and merge: global ← project ← explicit. Later layers override
    * scalars, extend the list settings and merge `providers` (see [[mergeJson]]). */
  def load(cwd: Path, explicit: Option[Path]): (Config, List[Path]) =
    // The explicit path may name the project/global file again. Since list
    // settings are additive, loading a layer twice would duplicate its rules.
    val candidates = (List(globalPath, projectPath(cwd)) ++ explicit.toList)
      .distinctBy(_.toAbsolutePath.normalize)
    val present = candidates.filter(Files.isRegularFile(_))
    val merged = present.foldLeft(ujson.Obj()) { (acc, p) =>
      val parsed =
        try ujson.read(Files.readString(p))
        catch case e: Exception => throw IllegalArgumentException(s"Cannot parse config $p: ${e.getMessage}")
      parsed match
        case o: ujson.Obj => mergeJson(acc, o)
        case _ => throw IllegalArgumentException(s"Config $p must be a JSON object")
    }
    val cfg =
      try read[Config](merged)
      catch case e: Exception => throw IllegalArgumentException(s"Invalid config: ${e.getMessage}")
    (validate(cfg), present)

  /** Reject limits that would otherwise fail much later in output slicing,
    * timeout accounting, or provider request construction. */
  def validate(config: Config): Config =
    def positive(name: String, value: Long): Unit =
      if value <= 0 then throw IllegalArgumentException(s"Invalid config: $name must be greater than zero (was $value)")

    positive("maxToolOutputChars", config.maxToolOutputChars)
    if config.maxToolCalls < 0 then
      throw IllegalArgumentException(s"Invalid config: maxToolCalls must be non-negative (was ${config.maxToolCalls})")
    config.executionTimeoutMs.foreach(positive("executionTimeoutMs", _))
    config.mode.foreach { m =>
      try atc.perms.Mode.parse(m)
      catch case e: IllegalArgumentException => throw IllegalArgumentException(s"Invalid config: ${e.getMessage}")
    }
    config.providers.foreach { (name, provider) =>
      if name.trim.isEmpty then throw IllegalArgumentException("Invalid config: provider names must not be blank")
      if provider.api.trim.isEmpty then
        throw IllegalArgumentException(s"Invalid config: api for provider '$name' must not be blank")
      if provider.models.isEmpty then
        throw IllegalArgumentException(s"Invalid config: provider '$name' defines no models")
      provider.models.foreach { (alias, model) =>
        val where = s"providers.$name.models.$alias"
        if alias.trim.isEmpty then
          throw IllegalArgumentException(s"Invalid config: model aliases of provider '$name' must not be blank")
        // `/` separates provider from alias in a model reference.
        if alias.contains('/') then
          throw IllegalArgumentException(s"Invalid config: model alias '$alias' must not contain '/'")
        if model.name.exists(_.trim.isEmpty) then
          throw IllegalArgumentException(s"Invalid config: $where.name must not be blank")
        model.maxTokens.foreach(positive(s"$where.maxTokens", _))
        model.temperature.foreach { value =>
          if !value.isFinite then throw IllegalArgumentException(s"Invalid config: $where.temperature must be finite")
        }
      }
    }
    // Fail here rather than at the first request: a typo in `model` is a
    // config error, and the catalog message lists what is configured.
    val catalog = ModelCatalog.from(config)
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

  /** The provider's key: `key` (a literal or `${VAR}`), else `keyEnv`, else
    * none — leaving the SDK to resolve its own default environment variable. */
  def resolveApiKey(p: ProviderConfig): Option[String] =
    p.key.flatMap {
      case EnvRef(name) => Option(System.getenv(name)).filter(_.nonEmpty)
      case literal => Some(literal)
    }.orElse(p.keyEnv.flatMap(n => Option(System.getenv(n)).filter(_.nonEmpty)))

  /** The starter config written by `--init` (`app/resources/atc/config-template.json`). */
  def template: String = atc.Resources.text("/atc/config-template.json").getOrElse(
    throw IllegalStateException("config template resource missing (atc/config-template.json)")
  )
