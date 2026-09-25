package atc

import atc.config.{Config, KeyBindings, ModelCatalog, ModelListStore, ProviderConfig, ProviderEdits, ProviderPreset}
import atc.llm.ChatModel

import java.nio.file.{Files, Path}

/** `/providers`: the provider list, back to it after each change until the
  * user leaves it. A provider or model in use cannot be turned off, so the
  * session and the config never name one that is gone. */
final class ProvidersMenu(app: App):
  import app.{models, tui}

  private def modelCommands = app.modelCommands

  def run(): Unit =
    var open = true
    while open do
      val settings = models.configuration.settings
      val names = settings.providers.keys.toList.sorted
      val width = names.map(_.length).maxOption.getOrElse(0)
      val rows = names.map { name =>
        val p = settings.providers(name)
        val offered =
          if p.models.isEmpty then "offers every model it lists"
          else s"${p.models.count(_._2.enabled)} of ${p.models.size} models on"
        s"${name.padTo(width, ' ')}  ${if p.enabled then "on " else "off"}  $offered"
      }
      val addable = ProviderPreset.all.filterNot(p => settings.providers.contains(p.name))
      val all = rows ++ Option.when(addable.nonEmpty)(ProvidersMenu.AddProvider)
      tui.choose("Providers (Esc when done)", all) match
        case None =>
          if !tui.menusAvailable then all.foreach(r => tui.println("  " + r))
          open = false
        case Some(ProvidersMenu.AddProvider) => add(addable)
        case Some(row) => actions(names(rows.indexOf(row)))

  private def actions(name: String): Unit =
    val p = models.configuration.settings.providers(name)
    val toggle = if p.enabled then "Turn off" else "Turn on"
    val showAll = "Offer every model it lists (drop its model entries)"
    val signIn = "Sign in with ChatGPT"
    val offered = List(toggle, "Choose its models") ++ Option.when(p.models.nonEmpty)(showAll) ++
      Option.when(p.api.exists(_.trim.equalsIgnoreCase("chatgpt")))(signIn)
    tui.choose(name, offered) match
      case Some(`signIn`) => if Setup.signIn(again = true)(Setup.firstRunUi(tui)) then modelCommands.reload()
      case Some(`toggle`) =>
        modelCommands.inUse.find(_._1.provider == name) match
          case Some((m, role)) if p.enabled => tui.error(s"${m.ref} is $role; switch to another model first")
          case _ =>
            applyEdits(ProviderEdits.setEnabled(name, !p.enabled), s"$name turned ${if p.enabled then "off" else "on"}")
      case Some(`showAll`) =>
        if tui.confirm(s"Drop the ${p.models.size} model entries of $name and their settings?") then
          applyEdits(ProviderEdits.showAll(name), s"$name offers every model it lists")
      case Some(_) => chooseModels(name, p)
      case None => ()

  /** Tick the models to offer. A provider without entries starts with only the
    * models in use ticked; ticking none keeps offering everything it lists. */
  private def chooseModels(name: String, p: ProviderConfig): Unit =
    import ProviderEdits.Choice
    val endpoint = ModelCatalog
      .from(Config(providers = Map(name -> p.copy(models = Map.empty, enabled = true))), models.configuration.keys)
      .discoverable.headOption
    val listed = endpoint.fold(Nil) { spec =>
      tui.info(s"Fetching the models of $name...")
      try
        val fetched = ChatModel.listModels(spec)
        ModelListStore.global.save(spec, fetched)
        fetched
      catch
        case scala.util.control.NonFatal(e) =>
          tui.error(s"Could not list the models of $name; showing its configured ones (${e.getMessage})")
          Nil
    }
    val choices = ProviderEdits.choices(p, listed)
    val used = modelCommands.inUse.filter(_._1.provider == name)
    def isUsed(c: Choice) = used.exists((m, _) =>
      c match
        case Choice.Configured(alias, _) => m.alias == alias
        case Choice.Listed(spec) => spec.modelId == m.modelId
    )
    val labels = choices.map {
      case Choice.Configured(alias, m) => s"$alias  ${m.displayName.orElse(m.name).getOrElse("")}".trim
      case Choice.Listed(spec) => spec.displayName.fold(spec.modelId)(n => s"$n  (${spec.modelId})")
    }
    val checked = choices.indices.filter(i =>
      choices(i) match
        case Choice.Configured(_, m) => m.enabled
        case c => isUsed(c)
    ).toSet
    if choices.isEmpty then tui.error(s"$name has no models to choose from")
    else
      tui.chooseMany(s"Models $name offers (type to filter)", labels, checked).foreach { ticked =>
        val chosen = ticked.map(choices)
        choices.filterNot(chosen).find(isUsed) match
          case Some(c) => tui.error(s"${labels(choices.indexOf(c))} is in use; switch to another model first")
          case None =>
            val edits = ProviderEdits.shortlist(name, p, choices, chosen)
            if edits.isEmpty then tui.info("No change.")
            else applyEdits(edits, s"$name: ${chosen.size} model${if chosen.size == 1 then "" else "s"} on")
      }

  private def add(addable: List[ProviderPreset]): Unit =
    tui.choose("Add a provider", addable.map(_.label)).flatMap(l => addable.find(_.label == l)).foreach { preset =>
      val ui = Setup.firstRunUi(tui)
      FirstRun.provider(ui, preset, models.configuration.keys, ChatModel.listModels, Setup.signIn(again = false)) match
        case Some(FirstRun.Outcome.Ready(provider, key, endpoint, listed, model)) =>
          val keysPath = Config.globalPath.getParent.nn.resolve(Config.KeysFile).nn
          for name <- provider.keyVariable; value <- key do KeyBindings.bind(keysPath, name, value)
          ModelListStore.global.save(endpoint, listed)
          val edit =
            ProviderEdits.Edit(List("providers", provider.name), Some(upickle.default.writeJs(provider.config)))
          if applyEdits(List(edit), s"${provider.label} added", into = Some(Config.globalPath)) then
            modelCommands.switchModel(model.ref)
        case _ => tui.info("No provider added.")
    }

  /** Write `edits`, each to the file that owns it (or `into`), and reload the
    * models. A change that leaves an invalid config is undone. */
  private def applyEdits(edits: List[ProviderEdits.Edit], done: String, into: Option[Path] = None): Boolean =
    val global = Config.globalPath
    val byFile = edits.groupBy(e =>
      into.orElse(ProviderEdits.owner(models.configuration.layers, e.path).flatMap(_.path)).getOrElse(global)
    )
    val created = if byFile.contains(global) && !Files.exists(global) then Config.ensureGlobal() else Nil
    val originals = byFile.keys.filterNot(created.contains).map(p => p -> Files.readString(p).nn).toMap
    try
      byFile.foreach((path, es) =>
        Config.editFile(path)(text => es.foldLeft(text)((t, e) => Config.withMember(t, e.path, e.value, path.toString)))
      )
      modelCommands.reload()
      tui.success(s"$done (saved to ${byFile.keys.map(App.pretty).mkString(", ")})")
      true
    catch
      case scala.util.control.NonFatal(e) =>
        originals.foreach((p, text) => Files.writeString(p, text))
        created.foreach(Files.deleteIfExists)
        tui.error(s"Nothing changed: ${Option(e.getMessage).getOrElse(e.toString)}")
        false

object ProvidersMenu:
  val AddProvider = "Add a provider"
