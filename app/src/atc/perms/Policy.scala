package atc.perms

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap

/** One configured file rule. Missing fields mean "no constraint from this rule".
  * `builtin` marks the default classified patterns (`.ssh`, `.env`, ...) so
  * summaries can fold them into one line. */
case class FileRule(
  pattern: PathPattern,
  access: Option[Access],
  classified: Option[Boolean],
  locked: Boolean = false,
  builtin: Boolean = false
):
  def describe: String =
    val parts = List(
      access.map(a => s"access=${a.label}"),
      classified.filter(identity).map(_ => "classified"),
      if locked then Some("locked") else None,
    ).flatten
    s"$pattern: ${if parts.isEmpty then "(no constraint)" else parts.mkString(", ")}"

/** What the user answers to a permission prompt. */
enum Decision:
  case AllowOnce, AllowSession, Deny

sealed trait PermissionRequest:
  def reason: String
  def title: String
  def details: List[String]

object PermissionRequest:
  private[perms] def reasonLine(label: String, reason: String): List[String] =
    if reason.nonEmpty then List(label + reason) else Nil

case class FileRequest(path: Path, access: Access, current: Perm, reason: String) extends PermissionRequest:
  def title = s"File access: ${access.label}"
  def details =
    List(s"path:    $path", s"current: ${current.describe}") ++ PermissionRequest.reasonLine("reason:  ", reason)

case class ExecRequest(commands: List[String], reason: String) extends PermissionRequest:
  def title = "Run commands"
  def details = List(s"patterns: ${commands.mkString(", ")}") ++ PermissionRequest.reasonLine("reason:   ", reason)

case class NetRequest(hosts: List[String], reason: String) extends PermissionRequest:
  def title = "Network access"
  def details = List(s"hosts:  ${hosts.mkString(", ")}") ++ PermissionRequest.reasonLine("reason: ", reason)

/** Shows the permission pop-up to the user. */
trait PermissionPrompter:
  def ask(request: PermissionRequest): Decision

/** A permission scope opened by a `request*` call. Its grants add to those
  * of its ancestors; scope 0 (the base) holds the session grants. */
final class Scope(val id: Long, val parent: Option[Scope]):
  @volatile var fileGrants: List[(Path, Access)] = Nil
  @volatile var commands: List[String] = Nil
  @volatile var hosts: List[String] = Nil
  def chain: List[Scope] = this :: parent.toList.flatMap(_.chain)

/** The permission policy: configured rules + session grants + open scopes.
  *
  * Effective file permission of a path `p` in scope `s`:
  *
  *  - configuration: `access` is the *minimum* over all rules matching `p` or
  *    an ancestor of `p` (a path matched by no rule with an access level has
  *    `none`); `classified` if any matching rule says so; `locked` likewise.
  *    So a sub-folder inherits its parent's permission and can only be made
  *    stricter by a more specific rule.
  *  - grants (from `request*`, once or for the session) can only *widen*
  *    access, never remove classification, and are ignored for locked paths.
  */
final class Policy(
  val rules: List[FileRule],
  val baseCommands: List[String],
  val baseHosts: List[String],
  prompter: PermissionPrompter,
):
  val base: Scope = Scope(0L, None)
  private val scopes = TrieMap[Long, Scope](0L -> base)
  private val nextId = AtomicLong(1L)

  private def scope(id: Long): Scope =
    scopes.getOrElse(
      id,
      throw SecurityException(s"Permission scope $id is not open (the capability escaped its block?)")
    )

  // ── files ─────────────────────────────────────────────────────────

  /** Permission from the configuration only. `p` must be canonical. */
  def configPerm(p: Path): Perm =
    val matching = rules.filter(_.pattern.matches(p))
    val access = matching.flatMap(_.access).reduceOption(_.min(_)).getOrElse(Access.None)
    Perm(access, classified = matching.exists(_.classified.contains(true)), locked = matching.exists(_.locked))

  private def grantedAccess(s: Scope, p: Path): Access =
    s.chain.flatMap(_.fileGrants).collect { case (g, a) if p == g || p.startsWith(g) => a }
      .reduceOption(_.max(_)).getOrElse(Access.None)

  /** Effective permission in `scopeId`. `p` must be canonical. */
  def effective(scopeId: Long, p: Path): Perm =
    val cfg = configPerm(p)
    if cfg.locked then cfg
    else cfg.copy(access = cfg.access.max(grantedAccess(scope(scopeId), p)))

  def requestFile(parentId: Long, p: Path, access: Access, reason: String): Long =
    val parent = scope(parentId)
    val current = effective(parentId, p)
    if !(current.access >= access) then
      if current.locked then
        throw SecurityException(s"Access denied: '$p' is locked to ${current.access.label} by the configuration")
      decide(FileRequest(p, access, current, reason), s"${access.label} on '$p'") { base.fileGrants ::= (p -> access) }
    openScope(parent, fileGrants = List(p -> access))

  // ── commands ──────────────────────────────────────────────────────

  private def commandPatterns(s: Scope): List[String] = baseCommands ++ s.chain.flatMap(_.commands)

  def commandAllowed(scopeId: Long, commandLine: String): Boolean =
    commandPatterns(scope(scopeId)).exists(GlobMatcher.matchesCommand(commandLine, _))

  def requestExec(parentId: Long, commands: List[String], reason: String): Long =
    val parent = scope(parentId)
    val missing = commands.filterNot(commandPatterns(parent).toSet)
    if missing.nonEmpty then
      decide(ExecRequest(missing, reason), s"commands ${missing.mkString(", ")}") { base.commands ++= missing }
    openScope(parent, commands = commands)

  // ── network ───────────────────────────────────────────────────────

  private def hostPatterns(s: Scope): List[String] = baseHosts ++ s.chain.flatMap(_.hosts)

  def hostAllowed(scopeId: Long, host: String): Boolean =
    hostPatterns(scope(scopeId)).exists(GlobMatcher.matchesHost(host, _))

  def requestNet(parentId: Long, hosts: List[String], reason: String): Long =
    val parent = scope(parentId)
    val missing = hosts.filterNot(hostPatterns(parent).toSet)
    if missing.nonEmpty then
      decide(NetRequest(missing, reason), s"hosts ${missing.mkString(", ")}") { base.hosts ++= missing }
    openScope(parent, hosts = hosts)

  // ── scopes ────────────────────────────────────────────────────────

  /** Put `request` to the user. Denial throws (`what` names what was refused);
    * "allow for the session" also runs `remember`, which records the grant on
    * the base scope. Returns normally when the caller may open its scope. */
  private def decide(request: PermissionRequest, what: String)(remember: => Unit): Unit =
    prompter.ask(request) match
      case Decision.Deny => throw SecurityException(s"Access denied by the user: $what")
      case Decision.AllowOnce => ()
      case Decision.AllowSession => remember

  private def openScope(
    parent: Scope,
    fileGrants: List[(Path, Access)] = Nil,
    commands: List[String] = Nil,
    hosts: List[String] = Nil
  ): Long =
    val s = Scope(nextId.getAndIncrement(), Some(parent))
    s.fileGrants = fileGrants
    s.commands = commands
    s.hosts = hosts
    scopes.put(s.id, s)
    s.id

  def closeScope(id: Long): Unit = if id != 0L then scopes.remove(id)

  def openScopeCount: Int = scopes.size - 1

  /** Human-readable summary for the UI. */
  def summary: String =
    val sb = StringBuilder()
    sb.append("File rules (strictest matching rule wins; unmatched paths are inaccessible):\n")
    val (builtin, explicit) = rules.partition(_.builtin)
    explicit.foreach(r => sb.append(s"  ${r.describe}\n"))
    if builtin.nonEmpty then
      sb.append(s"  built-in classified patterns: ${builtin.map(_.pattern.toString).mkString(", ")}\n")
    if base.fileGrants.nonEmpty then
      sb.append("Session file grants:\n")
      base.fileGrants.reverse.foreach((p, a) => sb.append(s"  $p: ${a.label}\n"))
    sb.append("Commands: ").append(if baseCommands.isEmpty then "(none)" else baseCommands.mkString(", "))
    if base.commands.nonEmpty then sb.append(s"  + session: ${base.commands.mkString(", ")}")
    sb.append("\nHosts:    ").append(if baseHosts.isEmpty then "(none)" else baseHosts.mkString(", "))
    if base.hosts.nonEmpty then sb.append(s"  + session: ${base.hosts.mkString(", ")}")
    sb.toString
