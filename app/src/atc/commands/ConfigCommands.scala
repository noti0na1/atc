package atc.commands

import atc.{App, Debug}
import atc.config.{Config, Origin}
import atc.platform.PlatformPath

import java.nio.file.{Files, Path}
import java.util.Locale
import scala.util.control.NonFatal

/** `/config`: show the configuration, and change the settings that leave the
  * sandbox as it is. A change applies to the running session at once and is
  * kept for this session only, in the project config or in the global one. */
final class ConfigCommands(app: App):
  import app.{models, tui}
  import ConfigCommands.*

  /** No argument opens the settings menu (without menus, shows the configuration);
    * `show` shows it; `<setting> [value [session|project|global]]` changes one. */
  def run(arg: String): Unit =
    arg.trim.split("\\s+").toList.filter(_.nonEmpty) match
      case Nil => if tui.menusAvailable then tui.menuLoop("Settings")(() => entries()) else show()
      case "show" :: Nil => show()
      case name :: rest =>
        Setting.named(name) match
          case None =>
            tui.error(s"Unknown setting '$name'. /config changes ${Setting.values.map(_.key).mkString(", ")}")
          case Some(setting) =>
            rest match
              case Nil if tui.menusAvailable => edit(setting)
              case Nil =>
                val current = setting.current(models.configuration.settings)
                tui.info(s"${setting.key}: $current (${setting.choices.mkString(" | ")})")
              case value :: Nil => set(setting, value, None)
              case value :: scope :: Nil => set(setting, value, Some(scope))
              case _ => tui.error(s"Usage: /config ${setting.key} <value> [${ScopeNames.mkString("|")}]")

  /** The layers, the key bindings and the scalar settings in force. */
  def show(): Unit =
    val current = models.configuration
    tui.println("config layers, in order:")
    current.layers.foreach(l => tui.println(l.describe))
    val keys = current.keys
    if keys.sources.nonEmpty then
      tui.println(s"key bindings: ${keys.names.mkString(", ")} (from ${keys.sources.mkString(", ")})")
    // The sandbox keeps the policy it started with; /config changes only the rest.
    val policy = app.config
    val settings = List(
      "safeMode" -> policy.safeMode.toString,
      "executionTimeoutMs" -> policy.executionTimeoutMs.fold("none")(_.toString),
      "maxToolCalls" -> policy.maxToolCalls.toString,
      "respectGitignore" -> policy.respectGitignore.toString,
    ) ++ Setting.values.map(s => s.key -> s.current(current.settings))
    tui.println(settings.map((key, value) => s"$key=$value").mkString(" "))
    tui.println(s"open permission scopes: ${app.policy.openScopeCount}")

  /** A row per setting, with its value in force, opening [[edit]]. */
  private def entries(): List[(String, () => Unit)] =
    val settings = models.configuration.settings
    val keyWidth = Setting.values.map(_.key.length).max
    val valueWidth = Setting.values.map(_.current(settings).length).max
    Setting.values.toList.map: s =>
      s"${s.key.padTo(keyWidth, ' ')}  ${s.current(settings).padTo(valueWidth, ' ')}  ${s.help}" -> (() => edit(s))

  /** Pick a value, then where to keep it; Back from the second returns to the first. */
  private def edit(setting: Setting): Unit =
    var open = true
    while open do
      val current = setting.current(models.configuration.settings)
      val rows = setting.choices.map(c => if c == current then s"$c  (current)" else c)
      tui.chooseOrBack(s"${setting.key}: ${setting.help}", rows).map(setting.choices) match
        case None => open = false
        case Some(text) =>
          for value <- setting.parse(text); scope <- pickScope(setting, text) do
            apply(setting, value, scope)
            open = false

  private def pickScope(setting: Setting, text: String): Option[Scope] =
    val scopes = available
    tui.chooseOrBack(s"Keep ${setting.key} = $text", scopes.map(_.label)).map(scopes)

  /** Set `setting` to the typed `text` where `scope` names, or where the user
    * picks (this session only without menus). */
  private def set(setting: Setting, text: String, scope: Option[String]): Unit =
    setting.parse(text) match
      case Left(problem) => tui.error(problem)
      case Right(value) =>
        val target = scope match
          case Some(name) =>
            val scopes = available
            val found = scopes.find(_.name == name.toLowerCase(Locale.ROOT))
            if found.isEmpty then
              tui.error(s"'$name' is not where a setting can be saved here (${scopes.map(_.name).mkString("|")})")
            found
          case None if tui.menusAvailable => pickScope(setting, text.trim)
          case None => available.headOption
        target.foreach(apply(setting, value, _))

  /** Where a setting can be kept: this session, the project config in force (when
    * there is one) and the global config. */
  private def available: List[Scope] =
    val project = models.configuration.layers.find(_.origin == Origin.Project).flatMap(_.path)
    List(Scope("session", "This session only", None)) ++
      project.map(p => Scope("project", s"Project config  ${display(p)}", Some(p))) :+
      Scope("global", s"Global config  ${PlatformPath.display(Config.globalPath)}", Some(Config.globalPath))

  /** Write the file of `scope`, if it has one, and use the value in this session
    * too. On failure the file is restored and nothing changes. */
  private def apply(setting: Setting, value: ujson.Value, scope: Scope): Unit =
    val original = scope.path.filter(Files.exists(_)).map(p => p -> Files.readString(p).nn)
    val created = if scope.path.contains(Config.globalPath) then Config.ensureGlobal() else Nil
    try
      scope.path.foreach(Config.setTopLevel(_, setting.key, value))
      models.setForSession(setting.key, value)
      app.useSettings(models.configuration.settings)
      val shown = setting.current(models.configuration.settings)
      tui.success(s"${setting.key} -> $shown" + scope.path.fold(" (this session only)")(saved(setting, _)))
    catch
      case NonFatal(e) =>
        original.foreach((p, text) => Files.writeString(p, text))
        created.foreach(Files.deleteIfExists)
        tui.error(s"Nothing changed: ${Debug.message(e)}")

  /** The note on a saved value, and on a later config file that sets it too and so
    * wins over it at the next start. */
  private def saved(setting: Setting, path: Path): String =
    val later = models.configuration.layers
      .filter(l => l.origin != Origin.Session && l.defines(setting.key))
      .flatMap(_.path)
      .lastOption
      .filterNot(_.toAbsolutePath.nn.normalize == path.toAbsolutePath.nn.normalize)
    s" (saved to ${display(path)}" +
      later.fold("")(p => s"; ${display(p)} also sets ${setting.key} and wins at the next start") + ")"

  /** A path under the working directory relative to it, others as [[PlatformPath.display]] shows them. */
  private def display(path: Path): String =
    val abs = path.toAbsolutePath.nn.normalize.nn
    if abs.startsWith(app.cwd) then app.cwd.relativize(abs).toString else PlatformPath.display(abs)

object ConfigCommands:
  /** The scopes `/config <setting> <value> <scope>` accepts. */
  val ScopeNames: List[String] = List("session", "project", "global")

  private final case class Scope(name: String, label: String, path: Option[Path])

  /** A setting `/config` changes: none of them loosens the sandbox. */
  enum Setting(val key: String, val help: String, val choices: List[String]):
    case PredictInput extends Setting("predictInput", "suggest the next request after each turn", List("true", "false"))
    case Notifications
        extends Setting(
          "notifications",
          "how to tell you a turn ended while away",
          List("auto", "system", "terminal", "bell", "off"),
        )
    case WebSearch
        extends Setting("webSearch", "the provider's web search, unless a model sets it", List("true", "false"))
    case AutoCompactThreshold
        extends Setting(
          "autoCompactThreshold",
          "compact at this share of the context window; 0 is off",
          List("0", "0.5", "0.7", "0.8", "0.9"),
        )
    case CompactKeepRatio
        extends Setting(
          "compactKeepRatio",
          "share of the context window compaction keeps verbatim",
          List("0", "0.1", "0.2", "0.3", "0.5"),
        )

    def current(config: Config): String = this match
      case PredictInput => config.predictInput.toString
      case Notifications => config.notifications
      case WebSearch => config.webSearch.contains(true).toString
      case AutoCompactThreshold => fraction(config.autoCompactThreshold)
      case CompactKeepRatio => fraction(config.compactKeepRatio)

    /** The config value `text` stands for, or why it is not one. */
    def parse(text: String): Either[String, ujson.Value] =
      val typed = text.trim.toLowerCase(Locale.ROOT)
      this match
        case PredictInput | WebSearch =>
          typed match
            case "true" | "on" => Right(ujson.True)
            case "false" | "off" => Right(ujson.False)
            case _ => Left(s"$key is true or false, not '${text.trim}'")
        case Notifications =>
          if choices.contains(typed) then Right(ujson.Str(typed))
          else Left(s"$key is one of ${choices.mkString(" | ")}, not '${text.trim}'")
        case AutoCompactThreshold | CompactKeepRatio =>
          typed.toDoubleOption.filter(d => d >= 0 && d <= 1).map(ujson.Num(_)).toRight:
            s"$key is a number from 0 to 1, not '${text.trim}'"

  object Setting:
    def named(name: String): Option[Setting] = values.find(_.key.equalsIgnoreCase(name))

  private def fraction(value: Double): String = BigDecimal(value).bigDecimal.stripTrailingZeros.nn.toPlainString
