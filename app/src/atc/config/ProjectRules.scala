package atc.config

import atc.perms.{ExecRequest, FileRequest, NetRequest, PermissionRequest}
import atc.platform.PlatformPath

import java.nio.file.{Files, Path}

/** "Always allow in this project": the grant of an approved request, saved to the project
  * config so that later sessions have it. Commands and hosts join the config's lists. A
  * file grant becomes a rule, which a project config may only make for its own folder, so
  * one outside it cannot be saved; nor can one that a configured rule caps lower, since the
  * saved rule would grant nothing, or a path that would read as a glob. */
object ProjectRules:
  /** Where saving `request` would write and what it would add: a top-level list and its new entries. */
  final case class Plan(config: Path, key: String, entries: List[ujson.Value])

  /** The plan for saving `request` in the project `cwd` belongs to (the config is created
    * in `cwd` when there is none), or `None` when it cannot be saved there or would add
    * nothing. `globalDir` is `~/.atc`. */
  def plan(cwd: Path, request: PermissionRequest, globalDir: Path = Config.globalDir): Option[Plan] =
    val root = PlatformPath.canonical(Config.projectRoot(cwd).getOrElse(cwd))
    val config = Config.projectPath(root)
    // In the home directory the "project" config is the global one, which grants everywhere.
    if root.resolve(".atc").nn == PlatformPath.canonical(globalDir) then None
    else
      val existing = present(config)
      def strings(key: String, values: List[String]): Option[Plan] =
        val listed = existing.get(key).flatMap(_.arrOpt).fold(Nil)(_.toList.flatMap(_.strOpt))
        val added = values.distinct.filterNot(listed.contains)
        Option.when(added.nonEmpty)(Plan(config, key, added.map(ujson.Str(_))))
      request match
        case ExecRequest(commands, _) => strings("commands", commands)
        case NetRequest(hosts, _) => strings("hosts", hosts)
        case FileRequest(path, access, _, _, ceiling) =>
          val relative = PlatformPath.portable(root.relativize(path).nn)
          Option.when(path.startsWith(root) && !relative.exists("*?[]{}\\".contains(_)) && ceiling >= access):
            val pattern = if relative.isEmpty then "." else s"./$relative"
            Plan(config, "files", List(ujson.Obj("path" -> pattern, "access" -> access.label)))

  /** Save `request`'s grant to the project config, creating the config if needed, and return
    * a note for the user. A project whose commands and hosts were trusted stays trusted, since
    * the user just approved the addition; one that was not trusted is not trusted by this. */
  def save(cwd: Path, request: PermissionRequest, globalDir: Path = Config.globalDir): String =
    val p =
      plan(
        cwd,
        request,
        globalDir
      ).getOrElse(throw IllegalStateException("this grant cannot be saved to the project config"))
    val trusted = ProjectTrust.pending(cwd, globalDir).isEmpty
    if !Files.exists(p.config) then
      Files.createDirectories(p.config.getParent)
      Files.writeString(p.config, "{\n}\n")
    Config.editFile(p.config)(ObjectText.withAppended(
      _,
      p.key,
      p.entries,
      after = List("files", "commands"),
      p.config.toString
    ))
    if trusted then ProjectTrust.trust(cwd, globalDir)
    val shown = PlatformPath.display(p.config)
    if trusted || p.key == "files" then s"saved to $shown"
    else s"saved to $shown; its commands and hosts apply in later sessions once you trust the project"

  private def present(config: Path): Map[String, ujson.Value] =
    if Files.isRegularFile(config) then ObjectText.parse(Files.readString(config).nn, config.toString).value.toMap
    else Map.empty
