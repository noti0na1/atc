package atc.config

import atc.{Debug, Resources, TextFiles}
import atc.platform.PlatformPath

import upickle.default.*

import java.nio.file.{
  AtomicMoveNotSupportedException, FileAlreadyExistsException, Files, Path, StandardCopyOption, StandardOpenOption
}
import java.nio.file.attribute.PosixFilePermissions

/** One model of a provider: the id the provider knows it by, plus the
  * settings that apply to this model only. Everything about *where* to send
  * the request (API shape, URL, key) belongs to its [[ProviderConfig]]. */
final case class ModelConfig(
  /** The provider's model id. Defaults to the alias the model is listed under,
    * so `"models": { "gpt-5": {} }` needs no `name`. */
  name: Option[String] = None,
  /** Enable the provider's built-in web search tool (Anthropic
    * `web_search`, OpenAI Responses `web_search`, Chat Completions
    * `web_search_options`). Unset: the top-level `webSearch`. */
  webSearch: Option[Boolean] = None,
  /** Reasoning effort: OpenAI `none|minimal|low|medium|high|xhigh|max`,
    * Anthropic `low|medium|high|xhigh|max` (`output_config.effort`). The effort
    * a session starts with; `/effort` switches it. */
  reasoning: Option[String] = None,
  /** The efforts the model accepts, offered by `/effort`. Unset: every effort
    * its provider's api knows. `[]`: the model takes no effort setting. */
  efforts: Option[List[String]] = None,
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
  /** `false` hides the model from `/models` and `/model` and keeps its settings. */
  enabled: Boolean = true,
) derives ReadWriter

object ModelConfig:
  /** Every effort a provider api knows, lowest first. */
  val ReasoningEfforts: List[String] = List("none", "minimal", "low", "medium", "high", "xhigh", "max")

/** One LLM endpoint and the models reachable through it. `api` is the wire
  * protocol: `anthropic`, `openai` (Chat Completions; also any
  * OpenAI-compatible server such as Ollama, vLLM, LM Studio via `url`),
  * `openai-responses` (the Responses API), `chatgpt` (the models of a ChatGPT
  * plan, signed in through the browser instead of a key), or `echo` (the
  * key-less test model). */
final case class ProviderConfig(
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
  /** Extra HTTP headers sent with every request to this provider. A value is a
    * literal, a `${VAR}` resolved like [[key]] (a header whose variable is unset
    * is not sent), or `${ATC_SESSION}`, a random id of the current
    * conversation (renewed by `/new` and `/clear`), which gateways such as
    * OpenCode use for routing and prompt caching. */
  headers: Map[String, String] = Map.empty,
  /** The provider's models, by alias. Empty: the models the provider lists
    * (`GET /models`) are fetched the first time they are needed, and each is
    * named `provider/model-id`. */
  models: Map[String, ModelConfig] = Map.empty,
  /** `false` turns the provider off: none of its models is offered or fetched. */
  enabled: Boolean = true,
) derives ReadWriter

object ProviderConfig:
  /** The placeholder in a provider header for the conversation id, filled in
    * per request by `Providers.headers`. */
  val SessionRef = "${ATC_SESSION}"

/** A provider the first run can set up, from `atc/providers.json`; the starting
  * global config lists all of them. */
final case class ProviderPreset(
  name: String,
  label: String,
  api: String,
  url: Option[String] = None,
  key: Option[String] = None,
  /** Where to create a key, shown when the first run asks for one. */
  keyUrl: Option[String] = None,
  headers: Map[String, String] = Map.empty,
) derives ReadWriter:
  def config: ProviderConfig = ProviderConfig(api = Some(api), url = url, key = key, headers = headers)
  /** The variable a `${VAR}` key is read from. */
  def keyVariable: Option[String] = key.flatMap(KeyBindings.envRefName)

object ProviderPreset:
  lazy val all: List[ProviderPreset] = read[List[ProviderPreset]](Config.resource("/atc/providers.json"))

/** One file-permission rule. See `atc.perms.Policy` for the semantics. */
final case class FileRuleConfig(
  path: String,
  access: Option[String] = None,
  classified: Option[Boolean] = None,
  locked: Boolean = false,
) derives ReadWriter

final case class Config(
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
    * listings and searches; default true. Reading such a path by name still
    * works. This only keeps build output and dependencies out of the way. */
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
  /** Enable the provider's web search for every model whose entry does not set
    * `webSearch` (listed models included). Best effort, as is a model's own
    * setting: a model whose provider rejects the tool continues without it. */
  webSearch: Option[Boolean] = None,
  /** Extra text appended to the system prompt (project conventions etc.). */
  instructions: Option[String] = None,
  /** After each turn, ask the agent model to guess the next request and offer
    * it as ghost text at the prompt (Tab / → accepts). One extra, small model
    * call per turn; `false` turns it off. */
  predictInput: Boolean = true,
  /** After a turn, compact when estimated context usage reaches this fraction
    * of the model's context window. Zero disables automatic compaction. */
  autoCompactThreshold: Double = 0.8,
  /** Fraction of the context window reserved for recent verbatim exchanges
    * during manual or automatic compaction. Zero summarizes everything. */
  compactKeepRatio: Double = 0.2,
  /** How to tell the user that a turn ended or a question waits, when they do
    * not type within ten seconds: `auto`, `system` (a desktop notification),
    * `terminal` (the terminal's own notification sequence), `bell` or `off`. */
  notifications: String = "auto",
) derives ReadWriter

/** Where the configuration files are, and how they are created, loaded and edited in place.
  * [[Configuration.combine]] merges the layers and [[ConfigValidation]] checks the result. */
object Config:
  /** `~/.atc`: the global configuration and the state atc keeps between sessions. */
  def globalDir: Path = PlatformPath.userHome.resolve(".atc").nn

  /** The global configuration, loaded before project and explicit layers. */
  def globalPath: Path = globalDir.resolve("config.json").nn

  /** The global key bindings, `~/.atc/keys.properties`. */
  def globalKeysPath: Path = globalDir.resolve(KeysFile).nn

  /** `<dir>/.atc/config.json`, the project config of `dir`. */
  def projectPath(dir: Path): Path = dir.resolve(".atc").resolve("config.json")

  /** `<dir>/.atc/keys.properties`, the key bindings of `dir`. */
  def keysPath(dir: Path): Path = dir.resolve(".atc").resolve(KeysFile)

  /** The name of the key bindings file, in `.atc` beside a `config.json`. */
  val KeysFile = "keys.properties"

  /** The project a directory belongs to: the nearest ancestor of `from`
    * (itself included) whose `.atc` holds a `config.json` or a `keys.properties`. A
    * project config governs the folder its `.atc` sits in, so running atc in a
    * subdirectory still picks up (and is bound by) the project's own
    * configuration and keys. */
  def projectRoot(from: Path): Option[Path] =
    def isProject(d: Path) = Files.isRegularFile(projectPath(d)) || Files.isRegularFile(keysPath(d))
    def up(p: Path | Null): Option[Path] = p match
      case null => None
      case d: Path => if isProject(d) then Some(d) else up(d.getParent)
    up(from.toAbsolutePath.nn.normalize)

  /** Load every layer and combine them: `~/.atc/config.json` ← the project's
    * `.atc/config.json` ← `-c <file>`. See [[Configuration.combine]] for what
    * "later" means per setting. With `bundledGlobal`, the starting config stands
    * in for a missing `~/.atc/config.json` (the user declined to write it), as a
    * layer with no path. */
  def load(cwd: Path, explicit: Option[Path], bundledGlobal: Boolean): Configuration =
    load(cwd, explicit, globalPath, bundledGlobal)

  /** As [[load]], with the global path given explicitly (tests). */
  def load(cwd: Path, explicit: Option[Path], global: Path, bundledGlobal: Boolean = false): Configuration =
    explicit.foreach: path =>
      if !Files.exists(path) then throw IllegalArgumentException(s"Explicit config does not exist: $path")
      if !Files.isRegularFile(path) then throw IllegalArgumentException(s"Explicit config is not a regular file: $path")
    val root = projectRoot(cwd)
    val candidates =
      List(Origin.Global -> global) ++ root.map(Origin.Project -> projectPath(_)) ++ explicit.map(Origin.Explicit -> _)
    // A path named twice is read once, in the first role it appears in. That
    // keeps `~/.atc/config.json` a granting layer when atc runs in the home
    // directory, and keeps `-c ./.atc/config.json` a narrowing one.
    val layers = candidates
      .filter((_, p) => Files.isRegularFile(p))
      .distinctBy((_, p) => p.toAbsolutePath.normalize)
      .map((origin, path) => ConfigLayer.read(origin, path))
    val bundled = Option.when(bundledGlobal && !layers.exists(_.origin == Origin.Global))(ConfigLayer.bundled)
    // Keys are read separately, most specific first: they are secrets, not
    // settings, so they never take part in the layer merge.
    val keys = KeyBindings.load(root.map(keysPath).toList :+ global.getParent.nn.resolve(KeysFile).nn)
    Configuration.combine(bundled.toList ++ layers, keys)

  /** Create the global configuration and adjacent key bindings if they are
    * missing, ensuring that narrowing layers have a base. Key bindings are
    * readable only by the owner because they may contain API keys. Returns the
    * paths created. */
  def ensureGlobal(path: Path = globalPath, providers: List[ProviderPreset] = ProviderPreset.all): List[Path] =
    val keys = path.getParent.nn.resolve(KeysFile).nn
    List(writeGlobalConfig(path, providers), writeIfMissing(keys, keysTemplate, ownerOnly = true)).flatten

  /** Write the starting global config with `providers` unless the file exists. */
  def writeGlobalConfig(path: Path, providers: List[ProviderPreset]): Option[Path] =
    writeIfMissing(path, globalTemplateWith(providers), ownerOnly = false)

  private def writeIfMissing(target: Path, content: String, ownerOnly: Boolean): Option[Path] =
    if Files.exists(target) then None
    else
      Option(target.getParent).foreach(Files.createDirectories(_))
      try
        if ownerOnly then writeOwnerOnly(target, content)
        else Files.writeString(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        Some(target)
      catch case _: FileAlreadyExistsException => None // another atc created it meanwhile; leave it alone

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
    // `!keys.properties` does not protect the key file.
    val lastRule = current.toList.flatMap(text => TextFiles.splitLines(TextFiles.stripBom(text)).lines)
      .map(_.trim).filter(line => line == entry || line == s"!$entry").lastOption
    if lastRule.contains(entry) then false
    else
      Files.writeString(path, TextFiles.appendLine(current.getOrElse(""), entry))
      true

  private val OwnerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

  /** Create `target` with owner-only access on POSIX file systems, falling back
    * to a regular (new-file) write when POSIX permissions are unavailable. */
  private[config] def writeOwnerOnly(target: Path, content: String): Unit =
    try Files.createFile(target, OwnerOnly)
    catch case _: UnsupportedOperationException => Files.createFile(target)
    Files.writeString(target, content)

  /** Replace `target` with `content` through a temporary file beside it, moved into place
    * atomically where the file system allows. On POSIX file systems the new file is readable
    * by its owner only, or with `keepPermissions` has the permissions `target` had. */
  private[atc] def replaceFile(target: Path, content: String, keepPermissions: Boolean): Unit =
    val dir = target.toAbsolutePath.nn.getParent.nn
    Files.createDirectories(dir)
    val prefix = s".${target.getFileName}."
    val temp =
      try Files.createTempFile(dir, prefix, ".tmp", OwnerOnly).nn
      catch case _: UnsupportedOperationException => Files.createTempFile(dir, prefix, ".tmp").nn
    try
      Files.writeString(temp, content)
      if keepPermissions then
        try Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target))
        catch case _: UnsupportedOperationException => ()
      try Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      catch case _: AtomicMoveNotSupportedException => Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(temp)

  /** Set one top-level key of a config file, keeping the rest of the text as
    * it is (a config is hand-formatted: blank lines, several patterns per
    * line, and serializing it again would lose that). See [[ObjectText.withTopLevel]]. */
  def setTopLevel(path: Path, key: String, value: ujson.Value, after: List[String] = Nil): Unit =
    editFile(path)(ObjectText.withTopLevel(_, key, value, after, path.toString))

  /** Replace a config file's text with `update(text)`. A symlinked config stays a
    * symlink: its target is replaced, so a config shared that way stays shared. */
  def editFile(path: Path)(update: String => String): Unit =
    val text =
      try Files.readString(path).nn
      catch case e: Exception => throw IllegalArgumentException(s"Cannot read config $path: ${Debug.message(e)}")
    replaceFile(path.toRealPath().nn, update(text), keepPermissions = true)

  /** The starter global config written by `--init-global`, with every preset provider. */
  def globalTemplate: String = globalTemplateWith(ProviderPreset.all)

  /** The starter global config with `providers` as its providers. */
  def globalTemplateWith(providers: List[ProviderPreset]): String =
    val template = resource("/atc/config-template.json")
    val rendered = ujson.write(ujson.Obj.from(providers.map(p => p.name -> writeJs(p.config))), indent = 2)
    template.replace("\"providers\": {}", "\"providers\": " + rendered.linesIterator.mkString("\n  "))

  /** The starter key bindings written beside it. */
  def keysTemplate: String = resource("/atc/keys-template.properties")

  /** The starter project config written by `--init`. */
  def projectTemplate: String = resource("/atc/project-template.json")

  private[config] def resource(path: String): String =
    Resources.text(path).getOrElse(throw IllegalStateException(s"config template resource missing ($path)"))
