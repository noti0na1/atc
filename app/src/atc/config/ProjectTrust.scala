package atc.config

import atc.Debug
import atc.platform.PlatformPath

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.HexFormat

/** Whether the user trusts what a project's configuration adds beyond its own files: the
  * commands and hosts its `.atc/config.json` pre-approves, the classified model it names and
  * the bindings of its `.atc/keys.properties`. A cloned repository can ship both files, so
  * [[Config.load]] leaves these out until the user has trusted them. Trust is a fingerprint of
  * exactly these grants, kept per project in `trusted-projects.json` beside the global config,
  * so a change to them (a `git pull`, say) is put to the user again and other edits are not. */
object ProjectTrust:
  val StoreFile = "trusted-projects.json"

  /** The grants of one project that need trust. `keys` are the names its key file binds. */
  final case class Grants(
    commands: List[String],
    hosts: List[String],
    classifiedModel: Option[String],
    keys: List[String],
    fingerprint: String,
  ):
    def isEmpty: Boolean = commands.isEmpty && hosts.isEmpty && classifiedModel.isEmpty && keys.isEmpty

    /** What the user is asked to trust, one row per kind. */
    def describe: List[String] =
      def listed(values: List[String], shown: Int) =
        values.take(shown).mkString(", ") + (if values.sizeIs > shown then s" and ${values.size - shown} more" else "")
      List(
        Option.when(commands.nonEmpty)(s"pre-approves the commands ${listed(commands, 20)}"),
        Option.when(hosts.nonEmpty)(s"allows the hosts ${listed(hosts, 12)}"),
        classifiedModel.map(model => s"sends classified data to the model $model"),
        Option.when(keys.nonEmpty)(s"binds the keys ${listed(keys, 12)} in .atc/keys.properties"),
      ).flatten

  /** Grants of the project at `root` the user has not trusted, and whether an earlier
    * version of them was trusted. */
  final case class Pending(root: Path, grants: Grants, changed: Boolean)

  /** The untrusted grants of the project `cwd` belongs to, with the store in `atcDir`; `None`
    * when it has none or they are trusted. In the home directory the "project" is the global
    * config itself, which is the user's own. */
  def pending(cwd: Path, atcDir: Path): Option[Pending] =
    val global = atcDir.toAbsolutePath.nn.normalize
    Config.projectRoot(cwd).filterNot(_.resolve(".atc").toAbsolutePath.nn.normalize == global).flatMap: root =>
      val grants = of(root)
      val recorded = read(atcDir).get(key(root))
      Option.when(!grants.isEmpty && !recorded.contains(grants.fingerprint))(
        Pending(root, grants, changed = recorded.isDefined)
      )

  /** Trust what the project `cwd` belongs to grants now. */
  def trust(cwd: Path, atcDir: Path): Unit =
    Config.projectRoot(cwd).foreach: root =>
      val store = read(atcDir).updated(key(root), of(root).fingerprint)
      val text = ujson.write(ujson.Obj.from(store.toList.sortBy(_._1).map((k, v) => k -> ujson.Str(v))), indent = 2)
      Config.replaceFile(atcDir.resolve(StoreFile).nn, text + "\n", keepPermissions = false)

  /** `layer` without the settings that need trust. */
  def withoutGrants(layer: ConfigLayer): ConfigLayer =
    val json = ujson.Obj.from(layer.json.value.filterNot((k, v) => Removed(k) && !v.isNull))
    layer.copy(json = json, config = ConfigLayer.settings(json, layer.path.fold("")(_.toString)))

  private val Removed = Set("commands", "hosts", "classifiedModel")

  private def of(root: Path): Grants =
    val config = Config.projectPath(root)
    val json =
      if Files.isRegularFile(config) then ObjectText.parse(Files.readString(config).nn, config.toString)
      else ujson.Obj()
    def strings(key: String) = json.value.get(key).flatMap(_.arrOpt).fold(Nil)(_.toList.flatMap(_.strOpt))
    val keyFile = Config.keysPath(root)
    val keys = (if Files.isRegularFile(keyFile) then KeyBindings.read(keyFile).toList else Nil).sortBy(_._1)
    val classified = json.value.get("classifiedModel").flatMap(_.strOpt)
    val granted = ujson.Obj(
      "commands" -> ujson.Arr.from(strings("commands")),
      "hosts" -> ujson.Arr.from(strings("hosts")),
      "classifiedModel" -> classified.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "keys" -> ujson.Obj.from(keys.map((name, value) => name -> ujson.Str(value))),
    )
    val digest = MessageDigest.getInstance("SHA-256").nn.digest(ujson.write(granted).getBytes(UTF_8))
    Grants(strings("commands"), strings("hosts"), classified, keys.map(_._1), HexFormat.of().nn.formatHex(digest))

  private def key(root: Path): String = PlatformPath.portable(PlatformPath.canonical(root))

  /** The recorded fingerprints; an unreadable store trusts nothing. */
  private def read(atcDir: Path): Map[String, String] =
    val file = atcDir.resolve(StoreFile).nn
    try
      if !Files.isRegularFile(file) then Map.empty
      else ujson.read(Files.readString(file).nn).obj.toMap.collect { case (k, ujson.Str(v)) => k -> v }
    catch
      case e: Exception =>
        Debug.log(s"ignoring $file: ${Debug.message(e)}")
        Map.empty
