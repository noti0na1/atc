package atc.perms

import java.nio.file.Path

/** What the user answers to a permission prompt. */
enum Decision:
  case AllowOnce, AllowSession, Deny
  /** Do not grant the request; return the user's instructions to the agent. */
  case Revise(instructions: String)

/** One pop-up put to the user. Subclasses supply the `label -> value` rows;
  * [[details]] aligns them (and appends the reason) for the UI. */
sealed trait PermissionRequest:
  def title: String
  def reason: String
  protected def fields: List[(String, String)]

  final def details: List[String] =
    val rows = fields ++ Option.when(reason.nonEmpty)("reason" -> reason)
    val width = rows.map(_._1.length).maxOption.getOrElse(0) + 1
    rows.map((label, value) => s"${(label + ":").padTo(width, ' ')} $value")

final case class FileRequest(path: Path, access: Access, current: Perm, reason: String) extends PermissionRequest:
  def title: String = s"File access: ${access.label}"
  protected def fields: List[(String, String)] = List("path" -> path.toString, "current" -> current.describe)

final case class ExecRequest(commands: List[String], reason: String) extends PermissionRequest:
  def title: String = "Run commands"
  protected def fields: List[(String, String)] =
    commands.zipWithIndex.map((command, index) => s"command ${index + 1}" -> command)

final case class NetRequest(hosts: List[String], reason: String) extends PermissionRequest:
  def title: String = "Network access"
  protected def fields: List[(String, String)] =
    hosts.zipWithIndex.map((host, index) => s"host ${index + 1}" -> host)

/** Shows the permission pop-up to the user. */
trait PermissionPrompter:
  def ask(request: PermissionRequest): Decision
