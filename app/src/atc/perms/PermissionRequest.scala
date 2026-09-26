package atc.perms

import java.nio.file.Path

/** What the user answers to a permission prompt. */
enum Decision:
  case AllowOnce, AllowSession, Deny
  /** Do not grant the request; return the user's instructions to the agent. */
  case Revise(instructions: String)

/** One pop-up put to the user. Subclasses supply the `label -> value` rows;
  * [[details]] aligns them, after the reason, for the UI. */
sealed trait PermissionRequest:
  def title: String
  def reason: String
  protected def fields: List[(String, String)]

  /** The rows the user decides on. The model writes every value, so none may draw a row of
    * its own or hide part of itself: line breaks, tabs and invisible characters are shown
    * as escapes. The reason is one line, capped, and comes first, so that what is granted
    * stays next to the question. */
  final def details: List[String] =
    val why = PermissionRequest.visible(reason.replaceAll("\\s+", " ").trim)
    val shortWhy =
      if why.length <= PermissionRequest.MaxReason then why
      else why.take(PermissionRequest.MaxReason).stripSuffix("\\") + "…"
    val rows = Option.when(shortWhy.nonEmpty)("reason" -> shortWhy).toList ++
      fields.map((label, value) => label -> PermissionRequest.visible(value))
    val width = rows.map(_._1.length).maxOption.getOrElse(0) + 1
    rows.map((label, value) => s"${(label + ":").padTo(width, ' ')} $value")

object PermissionRequest:
  private val MaxReason = 300

  /** `value` with controls, line separators and format characters (zero-width and bidi
    * controls among them) written as `\n`, `\t` or `\uXXXX`. */
  def visible(value: String): String =
    val out = StringBuilder()
    value.codePoints.forEach: cp =>
      val kind = Character.getType(cp)
      if cp == '\n' then out.append("\\n")
      else if cp == '\r' then out.append("\\r")
      else if cp == '\t' then out.append("\\t")
      else if Character.isISOControl(cp) || kind == Character.FORMAT || kind == Character.LINE_SEPARATOR ||
        kind == Character.PARAGRAPH_SEPARATOR
      then out.append(f"\\u$cp%04x")
      else out.appendAll(Character.toChars(cp))
    out.toString

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
