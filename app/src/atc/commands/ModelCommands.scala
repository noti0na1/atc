package atc.commands

import atc.{App, Debug, Models}
import atc.config.{Config, ModelConfig, ModelSpec, ObjectText, Origin}
import atc.llm.ChatModel
import atc.platform.PlatformPath

import java.nio.file.{Files, Path}
import java.util.Locale
import scala.util.Try

/** `/model`, `/models`, `/effort` and `/classifiedmodel`: which models the
  * agent uses, and what happens to them when the configuration changes. */
final class ModelCommands(app: App):
  import app.{agent, models, tui}

  /** One line per model: its `provider/alias` name, its display name when it has one,
    * and the role it plays now. `width` aligns the display names of a whole list. */
  private def row(spec: ModelSpec, width: Int): String =
    val marks = List(
      Option.when(agent.model.ref == spec.ref)("agent"),
      Option.when(agent.classifiedModel.exists(_.ref == spec.ref))("classified"),
    ).flatten
    val role = if marks.isEmpty then "" else s"  [${marks.mkString(", ")}]"
    spec.displayName.fold(spec.ref)(n => s"${spec.ref.padTo(width, ' ')}  $n") + role

  private def rows(all: List[ModelSpec]): List[String] =
    val width = all.map(_.ref.length).maxOption.getOrElse(0)
    all.map(row(_, width))

  /** `/models`. */
  def show(): Unit = rows(models.catalog.models).foreach(r => tui.println("  " + r))

  /** Pick a model from the list, after the `none` row when there is one: `Some(None)`
    * when that was chosen. The menu opens on the model `inUse` (the `none` row when there
    * is none). Without a menu (plain mode) the list is printed instead, so the user can
    * name one with `/model <ref>`. */
  private def pick(title: String, none: Option[String], inUse: Option[String]): Option[Option[ModelSpec]] =
    val all = models.catalog.models
    val listed = rows(all)
    val initial = inUse.map(ref => all.indexWhere(_.ref == ref)).filter(_ >= 0).fold(0)(_ + none.size)
    tui.choose(title, none.toList ++ listed, initial) match
      case Some(chosen) if none.contains(chosen) => Some(None)
      case Some(chosen) => all.zip(listed).collectFirst { case (m, r) if r == chosen => Some(m) }
      case None =>
        if !tui.menusAvailable then show()
        None

  /** `/model`: pick from the list, or switch to the named one. The model starts
    * with its configured effort, and a saved `effort` is removed. */
  def switchModel(arg: String): Unit =
    choose(arg, "model", models.describe(agent.model), Some(agent.model.ref)): spec =>
      val model = models.client(spec)
      model.effort = model.defaultEffort
      agent.model = model
      Models.rememberLast(spec.ref)
      app.updateStatus()
      app.predictor.start()
      val saved = remember("model", Some(ujson.Str(models.catalog.label(spec))))
      remember("effort", None)
      tui.success(s"model -> ${models.describe(model)}" + saved)

  /** `/classifiedmodel`: the trusted isolated model used by `classifiedChat`. `none` (or
    * `off`), also the picker's first row, unsets it. */
  def switchClassified(arg: String): Unit =
    val current = agent.classifiedModel.map(models.describe).getOrElse("(none)")
    def disable(): Unit =
      agent.classifiedModel = None
      app.predictor.start()
      tui.success(
        "classified model -> (none): classified data is no longer sent to any model" +
          remember("classifiedModel", Some(ujson.Null))
      )
    val noneRow = "none  classifiedChat off: classified data goes to no model" +
      (if agent.classifiedModel.isEmpty then "  [classified]" else "")
    if Set("off", "none").contains(arg.trim.toLowerCase(Locale.ROOT)) then disable()
    else
      choose(arg, "classified model", current, agent.classifiedModel.map(_.ref), Some(noneRow), disable): spec =>
        val m = models.client(spec)
        agent.classifiedModel = Some(m)
        app.predictor.start()
        val saved = remember("classifiedModel", Some(ujson.Str(models.catalog.label(spec))))
        tui.success(s"classified model -> ${models.describe(m)}" + saved)

  /** Shared by the two switches: an argument names a model, no argument opens
    * the picker (with a `none` row that runs `disable`, when given); the current
    * one is reported when nothing is chosen. */
  private def choose(
    arg: String,
    what: String,
    current: String,
    inUse: Option[String],
    none: Option[String] = None,
    disable: () => Unit = () => (),
  )(use: ModelSpec => Unit): Unit =
    if arg.nonEmpty then
      try use(models.catalog.find(arg))
      catch case e: IllegalArgumentException => tui.error(Debug.message(e))
    else
      pick(s"Choose the $what", none, inUse) match
        case Some(Some(spec)) => use(spec)
        case Some(None) => disable()
        case None => tui.info(s"$what: $current")

  /** What `/effort` offers for the agent model: its efforts, and `default`, which sends no effort. */
  def effortChoices: List[String] =
    if agent.model.efforts.isEmpty then Nil else agent.model.efforts :+ ModelConfig.DefaultEffort

  /** `/effort`: pick the agent model's reasoning effort, or set the named one,
    * and save it as the project's `effort`. */
  def switchEffort(arg: String): Unit =
    val model = agent.model
    val current = model.effort.getOrElse(ModelConfig.DefaultEffort)
    val choices = effortChoices
    if choices.isEmpty then tui.info(s"${model.ref} takes no reasoning effort")
    else
      val chosen =
        if arg.nonEmpty then Some(arg.toLowerCase(Locale.ROOT))
        else tui.choose(s"Choose the reasoning effort of ${model.ref}", choices, choices.indexOf(current).max(0))
      chosen match
        case None => tui.info(s"effort: $current (${choices.mkString(" | ")})")
        case Some(e) if !choices.contains(e) => tui.error(s"${model.ref} takes ${choices.mkString(" | ")}, not '$e'")
        case Some(e) =>
          model.effort = Option.when(e != ModelConfig.DefaultEffort)(e)
          app.updateStatus()
          tui.success(s"effort -> $e" + remember("effort", Some(ujson.Str(e))))

  /** The models the session or the config uses, with the role each plays. */
  def inUse: List[(ModelSpec, String)] =
    def spec(ref: String) = Try(models.catalog.find(ref)).toOption
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
      if Try(next.find(m.ref)).isSuccess then m
      else
        val provider = m.ref.takeWhile(_ != '/')
        next.configured.find(s => s.provider == provider && s.modelId == m.modelId).fold(m): spec =>
          val renamed = models.client(spec)
          renamed.effort = m.effort.filter(renamed.efforts.contains).orElse(renamed.effort)
          renamed
    val agentModel = moved(agent.model)
    if agentModel ne agent.model then
      agent.model = agentModel
      Models.rememberLast(agentModel.ref)
    agent.classifiedModel = agent.classifiedModel.map(moved)
    next.refresh()
    app.updateStatus()

  /** Keep a choice as the top-level `key` of the working directory's own config,
    * so the next run here starts with it; `None` removes the key. Only that file
    * is ever written: a project config found in a parent directory governs this
    * run but is not touched from a sub-directory. Returns the note to append to
    * the confirmation. */
  private def remember(key: String, value: Option[ujson.Value]): String =
    val cwd = app.cwd
    def show(p: Path): String =
      val abs = p.toAbsolutePath.nn.normalize.nn
      if abs.startsWith(cwd) then cwd.relativize(abs).toString else PlatformPath.display(abs)
    Some(Config.projectPath(cwd)).filter(Files.isRegularFile(_)) match
      case None => ""
      case Some(path) =>
        try
          value match
            case Some(v) => Config.setTopLevel(path, key, v, after = List("model"))
            case None => Config.editFile(path)(ObjectText.withMember(_, List(key), None, path.toString))
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
            tui.error(s"could not save the choice to ${show(path)}: ${Debug.message(e)}")
            ""
