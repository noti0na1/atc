package atc

import atc.config.{Config, ModelSpec, Origin}
import atc.llm.ChatModel

import java.nio.file.{Files, Path}

/** `/model`, `/models`, `/effort` and `/classifiedmodel`: which models the
  * agent uses, and what happens to them when the configuration changes. */
final class ModelCommands(app: App):
  import app.{agent, models, tui}

  /** One line per model: its selectable name, friendly name (or `provider/model-id`
    * fallback, left out when that is the name already), and the role it currently plays. */
  private def row(spec: ModelSpec): String =
    val marks = List(
      Option.when(agent.model.ref == spec.ref)("agent"),
      Option.when(agent.classifiedModel.exists(_.ref == spec.ref))("classified"),
    ).flatten
    val role = if marks.isEmpty then "" else s"  [${marks.mkString(", ")}]"
    val label = models.catalog.label(spec)
    val detail = Models.detail(spec)
    if detail == label then label + role else s"${label.padTo(labelWidth, ' ')}  $detail$role"

  /** The name column's width: the configured models' names, as a listed name can be very long. */
  private def labelWidth: Int =
    models.catalog.configured.map(models.catalog.label(_).length).maxOption.getOrElse(0).max(24)

  /** `/models`. */
  def show(): Unit = models.catalog.models.foreach(m => tui.println("  " + row(m)))

  /** Pick a model from the list. Without a menu (plain mode) the list is
    * printed instead, so the user can name one with `/model <ref>`. */
  private def pick(title: String): Option[ModelSpec] =
    val all = models.catalog.models
    val rows = all.map(row)
    tui.choose(title, rows) match
      case Some(chosen) => all.zip(rows).collectFirst { case (m, r) if r == chosen => m }
      case None =>
        if !tui.menusAvailable then show()
        None

  /** `/model`: pick from the list, or switch to the named one. */
  def switchModel(arg: String): Unit =
    choose(arg, "model", models.describe(agent.model)) { spec =>
      agent.model = models.client(spec)
      Models.rememberLast(spec.ref)
      app.updateStatus()
      app.predictor.start()
      tui.success(s"model -> ${models.describe(agent.model)}" + remember("model", Some(spec)))
    }

  /** `/classifiedmodel`: the trusted isolated model used by `classifiedChat`. `off` unsets it. */
  def switchClassified(arg: String): Unit =
    if Set("off", "none").contains(arg.trim.toLowerCase(java.util.Locale.ROOT)) then
      agent.classifiedModel = None
      app.predictor.start()
      tui.success(
        "classified model -> (none): classified data is no longer sent to any model" + remember("classifiedModel", None)
      )
    else
      val current = agent.classifiedModel.map(models.describe).getOrElse("(none)")
      choose(arg, "classified model", current) { spec =>
        val m = models.client(spec)
        agent.classifiedModel = Some(m)
        app.predictor.start()
        tui.success(s"classified model -> ${models.describe(m)}" + remember("classifiedModel", Some(spec)))
      }

  /** Shared by the two switches: an argument names a model, no argument opens
    * the picker; the current one is reported when nothing is chosen. */
  private def choose(arg: String, what: String, current: String)(use: ModelSpec => Unit): Unit =
    if arg.nonEmpty then
      try use(models.catalog.find(arg))
      catch case e: IllegalArgumentException => tui.error(e.getMessage)
    else
      pick(s"Choose the $what") match
        case Some(spec) => use(spec)
        case None => tui.info(s"$what: $current")

  /** What `/effort` offers for the agent model: its efforts, and `default`, which sends none. */
  def effortChoices: List[String] =
    if agent.model.efforts.isEmpty then Nil else agent.model.efforts :+ ModelCommands.DefaultEffort

  /** `/effort`: pick the agent model's reasoning effort, or set the named one,
    * for the rest of the session. */
  def switchEffort(arg: String): Unit =
    val model = agent.model
    val current = model.effort.getOrElse(ModelCommands.DefaultEffort)
    val choices = effortChoices
    if choices.isEmpty then tui.info(s"${model.ref} takes no reasoning effort")
    else
      val chosen =
        if arg.nonEmpty then Some(arg.toLowerCase(java.util.Locale.ROOT))
        else tui.choose(s"Choose the reasoning effort of ${model.ref}", choices)
      chosen match
        case None => tui.info(s"effort: $current (${choices.mkString(" | ")})")
        case Some(e) if !choices.contains(e) => tui.error(s"${model.ref} takes ${choices.mkString(" | ")}, not '$e'")
        case Some(e) =>
          model.effort = Option.when(e != ModelCommands.DefaultEffort)(e)
          app.updateStatus()
          tui.success(s"effort -> $e")

  /** The models the session or the config uses, with the role each plays. */
  def inUse: List[(ModelSpec, String)] =
    def spec(ref: String) = scala.util.Try(models.catalog.find(ref)).toOption
    val settings = models.configuration.settings
    spec(agent.model.ref).map(_ -> "the agent model").toList ++
      agent.classifiedModel.flatMap(m => spec(m.ref)).map(_ -> "the classified model") ++
      settings.model.flatMap(spec).map(_ -> "the config's model") ++
      settings.classifiedModel.flatMap(spec).map(_ -> "the config's classified model")

  /** Load the configuration again. A model in use that the change renamed (a
    * listed model now an entry) moves to its new name. */
  def reload(): Unit =
    models.reload()
    val next = models.catalog
    def moved(m: ChatModel): ChatModel =
      if scala.util.Try(next.find(m.ref)).isSuccess then m
      else
        val provider = m.ref.takeWhile(_ != '/')
        next.configured.find(s => s.provider == provider && s.modelId == m.modelId).fold(m) { spec =>
          val renamed = models.client(spec)
          renamed.effort = m.effort.filter(renamed.efforts.contains).orElse(renamed.effort)
          renamed
        }
    val agentModel = moved(agent.model)
    if agentModel ne agent.model then
      agent.model = agentModel
      Models.rememberLast(agentModel.ref)
    agent.classifiedModel = agent.classifiedModel.map(moved)
    next.refresh()
    app.updateStatus()

  /** Keep a model choice in the working directory's own config, so the next run
    * here starts with it (`None` unsets the role: `"classifiedModel": null`).
    * Only that file is ever written: a project config found in a parent
    * directory governs this run but is not touched from a sub-directory.
    * Returns the note to append to the confirmation. */
  private def remember(key: String, choice: Option[ModelSpec]): String =
    val cwd = app.cwd
    def show(p: Path): String =
      val abs = p.toAbsolutePath.nn.normalize.nn
      if abs.startsWith(cwd) then cwd.relativize(abs).toString else App.pretty(abs)
    Some(Config.projectPath(cwd)).filter(Files.isRegularFile(_)) match
      case None => ""
      case Some(path) =>
        val value = choice.map(m => ujson.Str(models.catalog.label(m))).getOrElse(ujson.Null)
        try
          Config.setTopLevel(path, key, value, after = List("model"))
          // A `-c` file that sets the same key wins over the project config on the next start.
          val overridden = app.configuration.layers
            .filter(l => l.origin == Origin.Explicit && l.defines(key))
            .flatMap(_.path)
            .filterNot(_.toAbsolutePath.nn.normalize == path.toAbsolutePath.nn.normalize)
            .headOption
            .map(p => s"; ${show(p)} also sets $key and wins over it")
            .getOrElse("")
          s" (saved to ${show(path)}$overridden)"
        catch
          case e: Exception =>
            tui.error(s"could not save the choice to ${show(path)}: ${e.getMessage}")
            ""

object ModelCommands:
  /** The `/effort` choice that sends no effort, leaving it to the provider. */
  val DefaultEffort = "default"
