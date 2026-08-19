package atc.config

import upickle.default.*

import java.nio.file.{Files, Path}

/** Where a configuration layer comes from, and what it is allowed to do.
  *
  * A layer that [[grants]] sets the policy. The project layer ships inside the
  * repository being worked on, so it may open only *that* project (files inside
  * its own folder, plus the commands and hosts its work needs) and otherwise
  * only narrows what the granting layers permitted.
  */
enum Origin(val label: String):
  /** `~/.atc/config.json`: the base of the policy, and the only default there
    * is: nothing is permitted behind it. */
  case Global extends Origin("global")
  /** The `.atc/config.json` of the project the working directory belongs to. */
  case Project extends Origin("project")
  /** A file named with `-c`. */
  case Explicit extends Origin("explicit")

  /** Whether this layer may grant anything anywhere (the project layer may
    * grant only within its own project, and its scalar settings only narrow). */
  def grants: Boolean = this != Origin.Project

/** One configuration file, as read. `keys` are the settings it actually
  * defines, so a narrowing layer only narrows what it mentions (a setting it
  * leaves out is not the same as one it sets to its default). */
case class ConfigLayer(
  origin: Origin,
  path: Option[Path],
  json: ujson.Obj,
  config: Config,
  /** The directory this layer's *relative* path patterns are read against: the
    * folder holding `.atc` for a project config (so it governs its own project
    * however deep atc runs inside it), the working directory otherwise. */
  base: Option[Path] = None
):
  def keys: Set[String] = json.value.keySet.toSet
  def defines(key: String): Boolean = json.value.contains(key)
  def describe: String =
    val role = if origin.grants then "grants" else "narrows only"
    f"  ${origin.label}%-9s ${path.map(_.toString).getOrElse("(bundled)")}%-52s $role"

/** A file rule with the folder its layer is anchored at: the folder holding
  * `.atc` for a project config (whose rules are read against it and grant only
  * inside it) and `None` for a granting layer, which grants wherever it
  * matches. Either way the rule's access is also a ceiling. */
case class LayeredRule(rule: FileRuleConfig, base: Option[Path])

/** The configuration in force: every layer, combined.
  *
  * The file rules are [[rules]], kept per layer with their anchor; the `files`
  * field of [[settings]] holds only the granting layers' entries and must not
  * be used to build the policy. `settings.commands` / `settings.hosts` are the
  * union over every layer and are what the policy uses.
  */
final case class Configuration(
  layers: List[ConfigLayer],
  settings: Config,
  rules: List[LayeredRule],
  /** The `${VAR}` bindings from `.atc/keys.properties`; kept apart from the settings. */
  keys: KeyBindings = KeyBindings.empty
):
  def sources: List[Path] = layers.flatMap(_.path)

  /** Every configured model, resolved, with its provider's key. */
  def catalog: ModelCatalog = ModelCatalog.from(settings, keys)
