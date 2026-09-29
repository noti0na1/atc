package atc.commands

import atc.App
import atc.lib.TaskNotes
import atc.llm.TokenUsage
import atc.perms.{PermissionRequest, SessionGrant}
import atc.ui.Format

/** `/perms`, `/cost` and `/task`: what the session has been granted, has
  * spent and is working on. */
final class StatusCommands(app: App):
  import app.{agent, policy, tui}

  /** `/perms`: the permissions, granting requests `auto` rejected, and revoking session
    * grants; the configured policy is unchanged. */
  def permissions(arg: String): Unit =
    val grants = policy.sessionGrants
    val rejected = policy.rejected
    def list(): Unit =
      if grants.isEmpty then tui.info("No session grants.")
      else grants.zipWithIndex.foreach((grant, index) => tui.println(s"  ${index + 1}. ${grant.describe}"))
    def listRejected(): Unit =
      if rejected.isEmpty then tui.info("No requests rejected by auto.")
      else rejected.zipWithIndex.foreach((entry, index) => tui.println(s"  ${index + 1}. ${entry._2}"))
    def revoke(grant: SessionGrant): Unit =
      app.predictor.invalidate()
      policy.revoke(grant)
      agent.notePermissionRevoked(grant.describe)
      tui.success(s"Revoked ${grant.describe} for future operations.")
    def grant(request: PermissionRequest, what: String): Unit =
      app.predictor.invalidate()
      policy.grant(request)
      agent.notePermissionGranted(what)
      tui.success(s"Allowed $what for the rest of the session.")
    arg.trim.split("\\s+", 2).toList match
      case "" :: Nil =>
        tui.println(policy.summary)
        list()
        if rejected.nonEmpty then
          tui.println("Rejected by auto:")
          listRejected()
        if grants.nonEmpty || rejected.nonEmpty then
          tui.info("Use /perms grant to allow a rejected request, /perms revoke to remove a session grant.")
      case "grant" :: "all" :: Nil =>
        if rejected.isEmpty then listRejected() else rejected.foreach((request, what) => grant(request, what))
      case "grant" :: number :: Nil =>
        number.toIntOption.flatMap(n => rejected.lift(n - 1)) match
          case Some((request, what)) => grant(request, what)
          case None => tui.error("Unknown request number. Run /perms to list the rejected requests.")
      case "grant" :: Nil =>
        if !tui.menusAvailable || rejected.isEmpty then listRejected()
        else
          val rows = rejected.zipWithIndex.map((entry, index) => s"${index + 1}. ${entry._2}")
          tui.choose("Allow a rejected request for the session", rows).flatMap(row => rejected.lift(rows.indexOf(row)))
            .foreach((request, what) => grant(request, what))
      case "revoke" :: "all" :: Nil => if grants.isEmpty then list() else grants.foreach(revoke)
      case "revoke" :: number :: Nil =>
        number.toIntOption.flatMap(n => grants.lift(n - 1)) match
          case Some(grant) => revoke(grant)
          case None => tui.error("Unknown grant number. Run /perms to list current grants.")
      case "revoke" :: Nil =>
        if !tui.menusAvailable || grants.isEmpty then list()
        else
          val rows = grants.zipWithIndex.map((grant, index) => s"${index + 1}. ${grant.describe}")
          tui.choose("Revoke a session grant", rows).flatMap(row => grants.lift(rows.indexOf(row))).foreach(revoke)
      case _ => tui.error("Usage: /perms [grant|revoke [number|all]]")

  /** `/cost`: token usage in total and, when there is more than one purpose, by purpose. */
  def showCost(): Unit =
    def show(u: TokenUsage) = s"input=${u.input} (cached ${u.cacheRead}) output=${u.output}"
    tui.println(s"tokens: ${show(agent.usage)}; tool calls: ${agent.toolCalls}")
    val by = agent.usageByPurpose
    if by.size > 1 then by.foreach((purpose, u) => tui.println(f"  $purpose%-22s ${show(u)}"))
    val context = agent.contextUsage
    val window = context.window.fold(" (no contextWindow configured for this model)")(_ => "")
    tui.println(s"${Format.contextUsage(context.tokens, context.window)} estimated for the next request$window")

  /** `/task`: the agent's task notes. */
  def showTask(): Unit =
    val notes = app.host.currentTaskNotes
    if notes == TaskNotes() then tui.info("No task notes yet.")
    else
      if notes.goal.nonEmpty then tui.println(s"Goal: ${notes.goal}")
      val lists =
        List("Constraints" -> notes.constraints, "Completed" -> notes.completed, "Remaining" -> notes.remaining)
      lists.filter(_._2.nonEmpty).foreach: (label, values) =>
        tui.println(s"$label:")
        values.foreach(value => tui.println(s"  - $value"))
