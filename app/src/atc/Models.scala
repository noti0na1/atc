package atc

import atc.config.{Config, ConfigLayer, Configuration, ModelCatalog, ModelConfig, ModelListStore, ModelSpec}
import atc.llm.ChatModel
import atc.platform.PlatformPath

import java.nio.file.{Files, Path}
import java.util.Locale
import scala.collection.mutable
import scala.util.Try
import scala.util.control.NonFatal

/** The models of a session: every model of every provider, and one client per
  * model, created when first used. `/providers` changes the configuration they
  * come from; the permission policy keeps the one loaded at start. */
final class Models(args: Cli.Args, start: Configuration):
  /** Every model of every configured provider, resolved with its key; a
    * provider that configures none is asked for its list after the start. */
  var catalog: ModelCatalog = start.catalog(ChatModel.listModels, ModelListStore.global)
  /** The configuration as `/providers` and `/config` last left it. */
  var configuration: Configuration = start
  private val clients = mutable.Map[String, ChatModel]()
  /** What `/config` set for this session only: a last layer, over every file. */
  private var session = Map.empty[String, ujson.Value]

  /** The client for one model, created once per session. */
  def client(spec: ModelSpec): ChatModel = clients.getOrElseUpdate(spec.ref, ChatModel.create(spec))

  /** The client for a model reference (`alias` or `provider/alias`). */
  def client(reference: String): ChatModel = client(catalog.find(reference))

  /** `-m`, else the config's `model`, else the model last chosen with `/model` (while
    * it still resolves), else the first model, with the config's `effort`. A `-m`
    * that names no model stops the start; the config's `model` is passed over with
    * a warning, and so is an `effort` the model does not take. */
  def initial(warn: String => Unit): ChatModel =
    def last = Models.last.flatMap(ref => Try(catalog.find(ref)).toOption)
    val model = args.model.map(client).orElse(configured("model", start.settings.model, warn))
      .getOrElse(client(last.getOrElse(catalog.default)))
    start.settings.effort.map(_.toLowerCase(Locale.ROOT)).foreach: effort =>
      if effort == ModelConfig.DefaultEffort then model.effort = None
      else if model.efforts.contains(effort) then model.effort = Some(effort)
      else
        val takes = if model.efforts.isEmpty then "no effort" else model.efforts.mkString(" | ")
        warn(s"Ignoring effort in ${settingFile("effort")}: ${model.ref} takes $takes, not '$effort'")
    model

  /** The client for the model `reference`, the value of a config `setting`
    * (`model`, `classifiedModel`). `None` when it is unset, or names no model:
    * then `warn` says so. */
  def configured(setting: String, reference: Option[String], warn: String => Unit): Option[ChatModel] =
    reference.flatMap: ref =>
      try Some(client(ref))
      catch
        case e: IllegalArgumentException =>
          warn(s"Ignoring $setting in ${settingFile(setting)}: ${Debug.message(e)}")
          None

  /** The file whose value of `setting` is in force, for warnings. */
  private def settingFile(setting: String): String =
    start.layers.findLast(_.defines(setting)).flatMap(_.path).fold("the config")(PlatformPath.display)

  /** `provider/alias — display-name-or-model-id`, how a model in use is named everywhere. */
  def describe(m: ChatModel): String = Models.describe(m, catalog.find(m.ref))

  /** Load the configuration again, with this session's settings over it, and
    * rebuild the catalog. The clients made so far take up the new `webSearch`. */
  def reload(): Unit =
    val files = Config.load(args.cwd, args.config, bundledGlobal = start.bundledGlobal)
    configuration =
      if session.isEmpty then files
      else Configuration.combine(files.layers :+ ConfigLayer.session(ujson.Obj.from(session)), files.keys)
    catalog = configuration.catalog(ChatModel.listModels, ModelListStore.global)
    val default = configuration.settings.webSearch.getOrElse(false)
    clients.values.foreach: client =>
      client.useWebSearch(
        catalog.configured.find(_.ref == client.ref).fold(default)(_.settings.webSearch.contains(true))
      )

  /** Set a top-level `key` for this session only and load the configuration
    * again. A value that leaves the configuration invalid is not kept. */
  def setForSession(key: String, value: ujson.Value): Unit =
    val before = session
    session = session.updated(key, value)
    try reload()
    catch
      case e: Exception =>
        session = before
        throw e

  def close(): Unit =
    clients.values.foreach: model =>
      try model.close()
      catch case NonFatal(error) => Debug.trace(error)

object Models:
  /** For display only: references and provider requests use the configured
    * alias and the provider's model id. */
  private[atc] def describe(model: ChatModel, spec: ModelSpec): String =
    s"${model.ref} — ${spec.displayName.getOrElse(model.modelId)}" +
      (if model.webSearch then " (web search)" else "")

  private def lastPath: Path = Config.globalDir.resolve("last-model").nn

  /** The model last chosen with `/model`, the default when nothing names one. */
  private def last: Option[String] =
    try Some(Files.readString(lastPath).nn.trim).filter(_.nonEmpty)
    catch case NonFatal(_) => None

  /** Remember a model choice; losing it only loses a default. */
  def rememberLast(ref: String): Unit =
    try
      Files.createDirectories(lastPath.getParent)
      Files.writeString(lastPath, ref + "\n")
    catch case NonFatal(_) => ()
