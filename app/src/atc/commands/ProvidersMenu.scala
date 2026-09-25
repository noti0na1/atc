package atc.commands

import atc.{App, Debug, FirstRun, Setup}
import atc.config.{
  Config, ModelCatalog, ModelListStore, ModelSpec, ObjectText, ProviderConfig, ProviderEdits, ProviderPreset
}
import atc.llm.ChatModel
import atc.platform.PlatformPath

import upickle.default.writeJs

import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

/** `/providers`: the provider list, back to it after each change until the
  * user leaves it. A provider or model in use cannot be turned off, so the
  * session and the config never name one that is gone. */
final class ProvidersMenu(app: App, modelCommands: ModelCommands):
  import app.{models, tui}

  def run(): Unit =
    if tui.menusAvailable then tui.menuLoop("Providers")(() => entries())
    else entries().foreach((row, _) => tui.println("  " + row))

  /** A row per provider, opening its actions, and one to add a provider. */
  private def entries(): List[(String, () => Unit)] =
    val settings = models.configuration.settings
    val names = settings.providers.keys.toList.sorted
    val width = names.map(_.length).maxOption.getOrElse(0)
    val providers = names.map: name =>
      val p = settings.providers(name)
      val offered =
        if p.models.isEmpty then "offers every model it lists"
        else ProviderEdits.offered(p, endpoint(name, p).flatMap(ModelListStore.global.load).getOrElse(Nil))
      s"${name.padTo(width, ' ')}  ${if p.enabled then "on " else "off"}  $offered" -> (() => actions(name))
    val addable = ProviderPreset.all.filterNot(p => settings.providers.contains(p.name))
    providers ++ Option.when(addable.nonEmpty)("Add a provider" -> (() => add(addable)))

  /** The actions on one provider, until one of them is carried out (back to the
    * provider list, which shows the change) or the user goes back. An action
    * returns whether it was carried out. */
  private def actions(name: String): Unit =
    var open = true
    while open do
      val p = models.configuration.settings.providers(name)
      val toggle = (if p.enabled then "Turn off" else "Turn on") ->
        (() =>
          modelCommands.inUse.find(_._1.provider == name) match
            case Some((m, role)) if p.enabled =>
              tui.error(s"${m.ref} is $role; switch to another model first")
              false
            case _ =>
              applyEdits(
                ProviderEdits.setEnabled(name, !p.enabled),
                s"$name turned ${if p.enabled then "off" else "on"}"
              )
        )
      val showAll = "Offer every model it lists (drop its model entries)" ->
        (() =>
          val question = s"Drop the ${p.models.size} model entries of $name and their settings?"
          tui.chooseOrBack(question, List("Drop them")).isDefined &&
          applyEdits(ProviderEdits.showAll(name), s"$name offers every model it lists")
        )
      val signIn = "Sign in with ChatGPT" ->
        (() =>
          val signedIn = Setup.signIn(again = true)(Setup.firstRunUi(tui))
          if signedIn then modelCommands.reload()
          signedIn
        )
      val offered = List(toggle, "Choose its models" -> (() => chooseModels(name, p))) ++
        Option.when(p.models.nonEmpty)(showAll) ++
        Option.when(p.api.exists(_.trim.equalsIgnoreCase("chatgpt")))(signIn)
      tui.chooseOrBack(name, offered.map(_._1)) match
        case Some(index) => open = !offered(index)._2()
        case None => open = false

  /** Tick the models to offer. A provider without entries starts with only the
    * models in use ticked; ticking none keeps offering everything it lists.
    * Whether the choice was confirmed and kept. */
  private def chooseModels(name: String, p: ProviderConfig): Boolean =
    import ProviderEdits.Choice
    val listed = endpoint(name, p).fold(Nil): spec =>
      tui.info(s"Fetching the models of $name...")
      try
        val fetched = ChatModel.listModels(spec)
        ModelListStore.global.save(spec, fetched)
        fetched
      catch
        case NonFatal(e) =>
          tui.error(s"Could not list the models of $name; showing its configured ones (${Debug.message(e)})")
          Nil
    val choices = ProviderEdits.choices(p, listed)
    val used = modelCommands.inUse.filter(_._1.provider == name)
    def isUsed(c: Choice) = used.exists: (m, _) =>
      c match
        case Choice.Configured(alias, _) => m.alias == alias
        case Choice.Listed(spec) => spec.modelId == m.modelId
    val labels = choices.map {
      case Choice.Configured(alias, m) => s"$alias  ${m.displayName.orElse(m.name).getOrElse("")}".trim
      case Choice.Listed(spec) => spec.displayName.fold(spec.modelId)(n => s"$n  (${spec.modelId})")
    }
    val checked = choices.indices.filter { i =>
      choices(i) match
        case Choice.Configured(_, m) => m.enabled
        case c => isUsed(c)
    }.toSet
    if choices.isEmpty then
      tui.error(s"$name has no models to choose from")
      false
    else
      tui.chooseMany(s"Models $name offers (type to filter)", labels, checked).exists: ticked =>
        val chosen = ticked.map(choices)
        choices.filterNot(chosen).find(isUsed) match
          case Some(c) =>
            tui.error(s"${labels(choices.indexOf(c))} is in use; switch to another model first")
            false
          case None =>
            val edits = ProviderEdits.shortlist(name, p, choices, chosen)
            if edits.isEmpty then
              tui.info("No change.")
              true
            else applyEdits(edits, s"$name: ${chosen.size} model${if chosen.size == 1 then "" else "s"} on")

  /** The provider as an endpoint whose models can be listed; `None` when its key is not bound. */
  private def endpoint(name: String, p: ProviderConfig): Option[ModelSpec] =
    ModelCatalog
      .from(Config(providers = Map(name -> p.copy(models = Map.empty, enabled = true))), models.configuration.keys)
      .discoverable.headOption

  private def add(addable: List[ProviderPreset]): Unit =
    tui.chooseOrBack("Add a provider", addable.map(_.label)).flatMap(addable.lift).foreach: preset =>
      val ui = Setup.firstRunUi(tui)
      FirstRun.provider(ui, preset, models.configuration.keys, ChatModel.listModels, Setup.signIn(again = false)) match
        case Some(ready: FirstRun.Outcome.Ready) =>
          Setup.saveKeyAndModels(ready)
          val edit = ProviderEdits.Edit(List("providers", ready.provider.name), Some(writeJs(ready.provider.config)))
          if applyEdits(List(edit), s"${ready.provider.label} added", into = Some(Config.globalPath)) then
            modelCommands.switchModel(ready.model.ref)
        case _ => tui.info("No provider added.")

  /** Write `edits`, each to the file that owns it (or `into`), and reload the
    * models. A change that leaves an invalid config is undone. */
  private def applyEdits(edits: List[ProviderEdits.Edit], done: String, into: Option[Path] = None): Boolean =
    val global = Config.globalPath
    val byFile = edits.groupBy: e =>
      into.orElse(ProviderEdits.owner(models.configuration.layers, e.path).flatMap(_.path)).getOrElse(global)
    val created = if byFile.contains(global) && !Files.exists(global) then Config.ensureGlobal() else Nil
    val originals = byFile.keys.filterNot(created.contains).map(p => p -> Files.readString(p).nn).toMap
    try
      byFile.foreach: (path, es) =>
        Config.editFile(path): text =>
          es.foldLeft(text)((t, e) => ObjectText.withMember(t, e.path, e.value, path.toString))
      modelCommands.reload()
      tui.success(s"$done (saved to ${byFile.keys.map(PlatformPath.display).mkString(", ")})")
      true
    catch
      case NonFatal(e) =>
        originals.foreach((p, text) => Files.writeString(p, text))
        created.foreach(Files.deleteIfExists)
        tui.error(s"Nothing changed: ${Debug.message(e)}")
        false
