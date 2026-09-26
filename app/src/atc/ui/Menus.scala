package atc.ui

import atc.perms.Decision

import java.util.Locale

/** The menus of pop-ups and slash commands, drawn by [[ListMenu]]. Each returns indices,
  * so duplicate labels stay apart, and `None` when left with Esc, Ctrl-C or Ctrl-D. The
  * caller pauses the turn's key reader and holds no screen lock while a menu reads. `escape`
  * names where Esc goes in the footer: `back` in a slash command's menus, `cancel` in the
  * agent's pop-ups. */
private[ui] final class Menus(screen: Screen, alerts: Alerts, status: StatusLine):
  private val menu = ListMenu(screen, status, alerts)

  /** A single-choice menu, its cursor on `initial` at first; see [[ListMenu.run]] for `indent`. */
  def list(message: String, labels: List[String], escape: String, initial: Int, indent: String = ""): Option[Int] =
    menu.run(message, labels, multi = false, initial, Set.empty, escape, indent).flatMap(_.headOption)

  /** A multi-choice menu with `checked` ticked at first; `Some(Nil)` if nothing is ticked. */
  def checkbox(
    message: String,
    labels: List[String],
    checked: Set[Int],
    escape: String,
    indent: String = "",
  ): Option[List[Int]] =
    menu.run(message, labels, multi = true, 0, checked, escape, indent)

private[atc] object Menus:
  val AllowOnce = "Allow once"
  val AllowSession = "Allow for this session"
  val DenyLabel = "Deny this request"
  val ReviseLabel = "Tell the agent what to change"
  val OtherLabel = "Write a different answer"
  val AddAnswerLabel = "Add an answer or instructions"
  val YesLabel = "Yes"
  val NoLabel = "No"
  /** The last row of a menu the user comes back to. */
  val DoneLabel = "Done"
  /** The last row of a sub-menu: back to the menu that opened it. */
  val BackLabel = "Back"

  /** Plain permission prompts accept exact approvals; every other answer is feedback. */
  private[atc] def permissionReply(answer: Option[String]): Decision =
    answer.map(_.trim).filter(_.nonEmpty) match
      case None => Decision.Deny
      case Some(text) => text.toLowerCase(Locale.ROOT) match
          case "y" | "yes" => Decision.AllowOnce
          case "s" | "session" => Decision.AllowSession
          case "n" | "no" => Decision.Deny
          case _ => Decision.Revise(text)
