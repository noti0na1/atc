package atc

import atc.config.{Config, Configuration, ModelCatalog, ModelListStore, ModelSpec}
import atc.llm.ChatModel
import atc.platform.PlatformPath

import java.nio.file.{Files, Path}
import scala.collection.mutable

/** The models of a session: every model of every provider, and one client per
  * model, created when first used. `/providers` changes the configuration they
  * come from; the permission policy keeps the one loaded at start. */
final class Models(args: Cli.Args, start: Configuration):
  /** Every model of every configured provider, resolved with its key; a
    * provider that configures none is asked for its list after the start. */
  var catalog: ModelCatalog = start.catalog(ChatModel.listModels, ModelListStore.global)
  /** The configuration as `/providers` last left it. */
  var configuration: Configuration = start
  private val clients = mutable.Map[String, ChatModel]()

  /** The client for one model, created once per session. */
  def client(spec: ModelSpec): ChatModel = clients.getOrElseUpdate(spec.ref, ChatModel.create(spec))

  /** The client for a model reference (`alias` or `provider/alias`). */
  def client(reference: String): ChatModel = client(catalog.find(reference))

  /** `-m`, else the config's `model`, else the model last chosen with `/model` (while
    * it still resolves), else the first model. A `-m` that names no model stops
    * the start; the config's `model` is passed over with a warning. */
  def initial(warn: String => Unit): ChatModel =
    def last = Models.last.flatMap(ref => scala.util.Try(catalog.find(ref)).toOption)
    args.model.map(client).orElse(configured("model", start.settings.model, warn))
      .getOrElse(client(last.getOrElse(catalog.default)))

  /** The client for the model `reference`, the value of a config `setting`
    * (`model`, `classifiedModel`). `None` when it is unset, or names no model:
    * then `warn` says so. */
  def configured(setting: String, reference: Option[String], warn: String => Unit): Option[ChatModel] =
    reference.flatMap { ref =>
      try Some(client(ref))
      catch
        case e: IllegalArgumentException =>
          val file = start.layers.findLast(_.defines(setting)).flatMap(_.path).fold("the config")(App.pretty)
          warn(s"Ignoring $setting in $file: ${e.getMessage}")
          None
    }

  /** `provider/alias — display-name-or-model-id`, how a model in use is named everywhere. */
  def describe(m: ChatModel): String = Models.describe(m, catalog.find(m.ref))

  /** Load the configuration again and rebuild the catalog. */
  def reload(): Unit =
    configuration = Config.load(args.cwd, args.config, bundledGlobal = start.bundledGlobal)
    catalog = configuration.catalog(ChatModel.listModels, ModelListStore.global)

  def close(): Unit =
    clients.values.foreach { model =>
      try model.close()
      catch case scala.util.control.NonFatal(error) => Debug.trace(error)
    }

object Models:
  /** Presentation only: references and provider requests continue to use the
    * configured alias and backend model id. */
  private[atc] def describe(model: ChatModel, spec: ModelSpec): String =
    s"${model.ref} — ${spec.displayName.getOrElse(model.modelId)}" +
      (if model.webSearch then " (web search)" else "")

  /** The detail column of `/models`, with the historical provider/model-id
    * form retained when no friendly name is configured. */
  private[atc] def detail(spec: ModelSpec): String =
    spec.displayName.getOrElse(s"${spec.provider}/${spec.modelId}")

  private def lastPath: Path = PlatformPath.userHome.resolve(".atc").nn.resolve("last-model").nn

  /** The model last chosen with `/model`, the default when nothing names one. */
  private def last: Option[String] =
    try Some(Files.readString(lastPath).nn.trim).filter(_.nonEmpty)
    catch case scala.util.control.NonFatal(_) => None

  /** Remember a model choice; losing it only loses a default. */
  def rememberLast(ref: String): Unit =
    try
      Files.createDirectories(lastPath.getParent)
      Files.writeString(lastPath, ref + "\n")
    catch case scala.util.control.NonFatal(_) => ()
