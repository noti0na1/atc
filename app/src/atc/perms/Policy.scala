package atc.perms

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap

/** The identity of a permission scope. [[ScopeId.Base]] is the session-wide
  * base scope; every `request*` block opens a child of the scope its capability
  * came from. Capabilities carry their scope id, so a leaked capability is
  * refused once its block has closed. */
opaque type ScopeId = Long
object ScopeId:
  /** The base scope: the configured rules plus the grants the user made "for the session". */
  val Base: ScopeId = 0L
  private[perms] def apply(id: Long): ScopeId = id

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
      Option.when(locked)("locked"),
    ).flatten
    s"$pattern: ${if parts.isEmpty then "(no constraint)" else parts.mkString(", ")}"

/** What the user answers to a permission prompt. */
enum Decision:
  case AllowOnce, AllowSession, Deny

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

case class FileRequest(path: Path, access: Access, current: Perm, reason: String) extends PermissionRequest:
  def title = s"File access: ${access.label}"
  protected def fields = List("path" -> path.toString, "current" -> current.describe)

case class ExecRequest(commands: List[String], reason: String) extends PermissionRequest:
  def title = "Run commands"
  protected def fields = List("patterns" -> commands.mkString(", "))

case class NetRequest(hosts: List[String], reason: String) extends PermissionRequest:
  def title = "Network access"
  protected def fields = List("hosts" -> hosts.mkString(", "))

/** Shows the permission pop-up to the user. */
trait PermissionPrompter:
  def ask(request: PermissionRequest): Decision

/** A permission scope opened by a `request*` call. Its grants add to those
  * of its ancestors; the base scope holds the session grants. */
final class Scope(val id: ScopeId, val parent: Option[Scope]):
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
  val base: Scope = Scope(ScopeId.Base, None)
  private val scopes = TrieMap[ScopeId, Scope](ScopeId.Base -> base)
  private val nextId = AtomicLong(1L)
  /** The sandbox mode. The type system already denies what the mode forbids
    * (the preamble hands out only the mode's capabilities); the checks below
    * make the host refuse it too, so nothing depends on the REPL alone. */
  @volatile var mode: Mode = Mode.Full

  private def scope(id: ScopeId): Scope =
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
  def effective(scopeId: ScopeId, p: Path): Perm =
    val cfg = configPerm(p)
    val perm = if cfg.locked then cfg else cfg.copy(access = cfg.access.max(grantedAccess(scope(scopeId), p)))
    if mode.allowsWrite then perm else perm.copy(access = perm.access.min(Access.Read))

  def requestFile(parentId: ScopeId, p: Path, access: Access, reason: String): ScopeId =
    val parent = scope(parentId)
    if access == Access.Write && !mode.allowsWrite then
      throw SecurityException(s"Access denied: the sandbox is in ${mode.label} mode; writing '$p' cannot be granted")
    val current = effective(parentId, p)
    if !(current.access >= access) then
      if current.locked then
        throw SecurityException(s"Access denied: '$p' is locked to ${current.access.label} by the configuration")
      decide(FileRequest(p, access, current, reason), s"${access.label} on '$p'") { base.fileGrants ::= (p -> access) }
    openScope(parent, fileGrants = List(p -> access))

  // ── commands ──────────────────────────────────────────────────────

  private def commandPatterns(s: Scope): List[String] = baseCommands ++ s.chain.flatMap(_.commands)

  def commandAllowed(scopeId: ScopeId, commandLine: String): Boolean =
    mode.allowsExec && commandPatterns(scope(scopeId)).exists(GlobMatcher.matchesCommand(commandLine, _))

  def requestExec(parentId: ScopeId, commands: List[String], reason: String): ScopeId =
    val parent = scope(parentId)
    if !mode.allowsExec then
      throw SecurityException(s"Access denied: the sandbox is in ${mode.label} mode; commands cannot be run")
    val missing = commands.filterNot(commandPatterns(parent).toSet)
    if missing.nonEmpty then
      decide(ExecRequest(missing, reason), s"commands ${missing.mkString(", ")}") { base.commands ++= missing }
    openScope(parent, commands = commands)

  // ── network ───────────────────────────────────────────────────────

  private def hostPatterns(s: Scope): List[String] = baseHosts ++ s.chain.flatMap(_.hosts)

  def hostAllowed(scopeId: ScopeId, host: String): Boolean =
    mode.allowsNetwork && hostPatterns(scope(scopeId)).exists(GlobMatcher.matchesHost(host, _))

  def requestNet(parentId: ScopeId, hosts: List[String], reason: String): ScopeId =
    val parent = scope(parentId)
    if !mode.allowsNetwork then
      throw SecurityException(s"Access denied: the sandbox is in ${mode.label} mode; the network is not reachable")
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
  ): ScopeId =
    val s = Scope(ScopeId(nextId.getAndIncrement()), Some(parent))
    s.fileGrants = fileGrants
    s.commands = commands
    s.hosts = hosts
    scopes.put(s.id, s)
    s.id

  def closeScope(id: ScopeId): Unit = if id != ScopeId.Base then scopes.remove(id)

  def openScopeCount: Int = scopes.size - 1

  /** Human-readable summary for the UI and the system prompt. */
  def summary: String =
    val (builtin, explicit) = rules.partition(_.builtin)
    def listOf(patterns: List[String]) = if patterns.isEmpty then "(none)" else patterns.mkString(", ")
    def session(patterns: List[String]) = if patterns.isEmpty then "" else s"  + session: ${patterns.mkString(", ")}"
    val lines = List.newBuilder[String]
    lines += s"Mode: ${mode.label} (${mode.description})"
    lines += "File rules (strictest matching rule wins; unmatched paths are inaccessible):"
    lines ++= explicit.map(r => s"  ${r.describe}")
    if builtin.nonEmpty then lines += s"  built-in classified patterns: ${builtin.map(_.pattern).mkString(", ")}"
    if base.fileGrants.nonEmpty then
      lines += "Session file grants:"
      lines ++= base.fileGrants.reverse.map((p, a) => s"  $p: ${a.label}")
    lines += s"Commands: ${listOf(baseCommands)}${session(base.commands)}"
    lines += s"Hosts:    ${listOf(baseHosts)}${session(base.hosts)}"
    lines.result().mkString("\n")
