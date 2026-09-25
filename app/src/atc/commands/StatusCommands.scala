package atc.commands

import atc.App
import atc.lib.TaskNotes
import atc.llm.TokenUsage
import atc.perms.SessionGrant
import atc.ui.Format

/** `/perms`, `/config`, `/cost` and `/task`: what the session has been
  * granted, is configured with, has spent and is working on. */
final class StatusCommands(app: App):
  import app.{agent, config, policy, tui}

  /** `/perms`: session grant selection and revocation; configured policy is unchanged. */
  def permissions(arg: String): Unit =
    val grants = policy.sessionGrants
    def list(): Unit =
      if grants.isEmpty then tui.info("No session grants.")
      else grants.zipWithIndex.foreach((grant, index) => tui.println(s"  ${index + 1}. ${grant.describe}"))
    def revoke(grant: SessionGrant): Unit =
      app.predictor.invalidate()
      policy.revoke(grant)
      agent.notePermissionRevoked(grant.describe)
      tui.success(s"Revoked ${grant.describe} for future operations.")
    arg.trim.split("\\s+", 2).toList match
      case "" :: Nil =>
        tui.println(policy.summary)
        list()
        if grants.nonEmpty then tui.info("Use /perms revoke to remove a session grant; /kill stops existing processes.")
      case "revoke" :: "all" :: Nil => grants.foreach(revoke)
      case "revoke" :: number :: Nil =>
        number.toIntOption.flatMap(n => grants.lift(n - 1)) match
          case Some(grant) => revoke(grant)
          case None => tui.error("Unknown grant number. Run /perms to list current grants.")
      case "revoke" :: Nil =>
        if !tui.menusAvailable || grants.isEmpty then list()
        else
          val rows = grants.zipWithIndex.map((grant, index) => s"${index + 1}. ${grant.describe}")
          tui.choose("Revoke a session grant", rows).flatMap(row => grants.lift(rows.indexOf(row))).foreach(revoke)
      case _ => tui.error("Usage: /perms [revoke [number|all]]")

  /** `/config`: the layers, key names and scalar settings. */
  def showConfig(): Unit =
    tui.println("config layers, in order:")
    app.configuration.layers.foreach(l => tui.println(l.describe))
    val keys = app.configuration.keys
    if keys.sources.nonEmpty then
      tui.println(s"key bindings: ${keys.names.mkString(", ")} (from ${keys.sources.mkString(", ")})")
    val settings = List(
      "safeMode" -> config.safeMode,
      "executionTimeoutMs" -> config.executionTimeoutMs.getOrElse("none"),
      "maxToolCalls" -> config.maxToolCalls,
      "respectGitignore" -> config.respectGitignore,
      "predictInput" -> config.predictInput,
      "autoCompactThreshold" -> config.autoCompactThreshold,
      "compactKeepRatio" -> config.compactKeepRatio,
      "notifications" -> config.notifications,
    )
    tui.println(settings.map((key, value) => s"$key=$value").mkString(" "))
    tui.println(s"open permission scopes: ${policy.openScopeCount}")

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
