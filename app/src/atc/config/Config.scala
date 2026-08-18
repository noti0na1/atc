package atc.config

import upickle.default.*

import java.nio.file.{Files, Path, Paths}

/** One LLM endpoint. `provider` is one of `anthropic`, `openai` (Chat
  * Completions; also for any OpenAI-compatible server such as Ollama, vLLM,
  * LM Studio via `baseUrl`), or `openai-responses` (the Responses API). */
case class ModelConfig(
  provider: String,
  model: String,
  /** Literal key, or `${ENV_VAR}`. When absent, `apiKeyEnv` / the provider's
    * default environment variable / SDK credential resolution is used. */
  apiKey: Option[String] = None,
  apiKeyEnv: Option[String] = None,
  baseUrl: Option[String] = None,
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

/** One file-permission rule. See `atc.perms.Policy` for the semantics. */
case class FileRuleConfig(
  path: String,
  access: Option[String] = None,
  classified: Option[Boolean] = None,
  locked: Boolean = false,
) derives ReadWriter

case class Config(
  /** Alias (key of `models`) of the agent's model. */
  model: Option[String] = None,
  /** Alias of the model that handles classified data (`chat(Classified)`). */
  safeModel: Option[String] = None,
  models: Map[String, ModelConfig] = Map.empty,
  files: List[FileRuleConfig] = Nil,
  /** Command-line patterns the agent may run without asking (`*` wildcard;
    * a pattern without `*` also matches by word prefix). */
  commands: List[String] = Nil,
  /** Host patterns the agent may reach without asking. */
  hosts: List[String] = Nil,
  /** Add the built-in classified patterns (`.ssh`, `.env`, ...) — default true. */
  defaultClassified: Boolean = true,
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

  /** Built-in model definitions used when the config defines none. */
  val DefaultModels: Map[String, ModelConfig] = Map(
    "claude" -> ModelConfig(provider = "anthropic", model = "claude-opus-5", webSearch = true),
    "gpt" -> ModelConfig(provider = "openai-responses", model = "gpt-5", webSearch = true),
  )

  def globalPath: Path =
    val xdg = Option(System.getenv("XDG_CONFIG_HOME")).filter(_.nonEmpty).map(Paths.get(_))
    xdg.getOrElse(Paths.get(System.getProperty("user.home"), ".config")).resolve("atc").resolve("config.json")

  def projectPath(cwd: Path): Path = cwd.resolve(".atc").resolve("config.json")

  /** Load and merge: global ← project ← explicit. Later layers override
    * scalars, extend lists (`files`, `commands`, `hosts`) and merge `models`. */
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
    config.models.foreach { (alias, model) =>
      if alias.trim.isEmpty then throw IllegalArgumentException("Invalid config: model aliases must not be blank")
      if model.provider.trim.isEmpty then
        throw IllegalArgumentException(s"Invalid config: provider for model '$alias' must not be blank")
      if model.model.trim.isEmpty then
        throw IllegalArgumentException(s"Invalid config: model id for '$alias' must not be blank")
      model.maxTokens.foreach(positive(s"models.$alias.maxTokens", _))
      model.temperature.foreach { value =>
        if !value.isFinite then
          throw IllegalArgumentException(s"Invalid config: models.$alias.temperature must be finite")
      }
    }
    config

  /** List settings extend rather than replace. */
  private val ListKeys = Set("files", "commands", "hosts")

  /** `over` on top of `base`: list settings are concatenated, `models` is
    * merged by alias (a redefined alias replaces the whole entry), everything
    * else is overwritten. */
  def mergeJson(base: ujson.Obj, over: ujson.Obj): ujson.Obj =
    val out = ujson.Obj()
    for (k, v) <- base.value do out(k) = v
    for (k, v) <- over.value do
      out(k) = (out.value.get(k), v) match
        case (Some(a: ujson.Arr), b: ujson.Arr) if ListKeys.contains(k) => ujson.Arr(a.value ++ b.value)
        case (Some(a: ujson.Obj), b: ujson.Obj) if k == "models" => mergeJson(a, b)
        case _ => v
    out

  private val EnvRef = """\$\{([A-Za-z_][A-Za-z0-9_]*)\}""".r

  /** Resolve `${VAR}` references and `apiKeyEnv`. */
  def resolveApiKey(m: ModelConfig): Option[String] =
    m.apiKey.flatMap {
      case EnvRef(name) => Option(System.getenv(name)).filter(_.nonEmpty)
      case literal => Some(literal)
    }.orElse(m.apiKeyEnv.flatMap(n => Option(System.getenv(n)).filter(_.nonEmpty)))

  /** The starter config written by `--init` (`app/resources/atc/config-template.json`). */
  def template: String = atc.Resources.text("/atc/config-template.json").getOrElse(
    throw IllegalStateException("config template resource missing (atc/config-template.json)")
  )
