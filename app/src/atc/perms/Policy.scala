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
  *
  * `grantsWithin` marks a rule from a project config and names the folder its
  * `.atc` sits in: such a rule *grants* only inside that folder (the project
  * you opened) while its ceiling applies wherever it matches. A rule without
  * it comes from a granting layer and grants wherever it matches. */
case class FileRule(
  pattern: PathPattern,
  access: Option[Access],
  classified: Option[Boolean],
  locked: Boolean = false,
  grantsWithin: Option[Path] = None,
  /** Where the rule comes from, when that is not "a `files` entry"; shown by
    * `/perms` so a rule nobody wrote is not a mystery. */
  why: Option[String] = None
):
  /** Whether this rule may grant `p` access, or only take it away. */
  def grants(p: Path): Boolean = grantsWithin.forall(root => p == root || p.startsWith(root))

  /** A rule that only marks paths classified; the summary folds these. */
  def classifiedOnly: Boolean = classified.contains(true) && access.isEmpty && !locked
  def describe: String =
    val parts = List(
      access.map(a => s"access=${a.label}"),
      classified.filter(identity).map(_ => "classified"),
      Option.when(locked)("locked"),
    ).flatten
    val note = why.orElse(grantsWithin.map(root => s"from the project config, granting only inside $root"))
    s"$pattern: ${if parts.isEmpty then "(no constraint)" else parts.mkString(", ")}${note.fold("")(" — " + _)}"

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
  *  - configuration: a path's access is what some rule *grants* it, clamped by
  *    every rule that matches. A rule matching `p` or an ancestor of `p` grants
  *    `p` its `access` (except a project rule outside its own project, which
  *    only clamps) and the *minimum* over all matching rules is the ceiling
  *    (unmatched: no grant at all, and no ceiling). So a sub-folder inherits
  *    its parent's permission and can only be made stricter by a more specific
  *    rule; a project config can open its own tree but never reach outside it,
  *    and never past a limit a granting layer set. `classified` and `locked`
  *    hold if any matching rule says so, from any layer.
  *  - grants (from `request*`, once or for the session) can only *widen*
  *    access, never remove classification, and are ignored for locked paths.
  *
  * `denyCommands` / `denyHosts` are the other direction: patterns that are
  * refused outright. They are checked at the point of use, so no grant, open
  * scope or "allow for the session" can reach past them, and a `request*` that
  * would permit a denied command or host fails at once instead of prompting.
  */
final class Policy(
  val rules: List[FileRule],
  val baseCommands: List[String],
  val baseHosts: List[String],
  prompter: PermissionPrompter,
  val denyCommands: List[String] = Nil,
  val denyHosts: List[String] = Nil,
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

  /** Refuse an operation on a handle, such as a spawned `Process`, after its
    * scope closes. This is the same check applied to an escaped capability. */
  def requireScopeOpen(id: ScopeId): Unit = { scope(id); () }

  /** Whether a capability in `caller` may reach a handle created in `owned`.
    * Both scopes must remain open and belong to the same chain, meaning one is
    * the other or its ancestor. Closing a `request*` scope therefore makes its
    * handles unreachable from the base scope. */
  def scopeVisibleFrom(caller: ScopeId, owned: ScopeId): Boolean =
    (scopes.get(caller), scopes.get(owned)) match
      case (Some(c), Some(o)) =>
        val callerChain = c.chain.map(_.id).toSet
        callerChain.contains(owned) || o.chain.map(_.id).toSet.contains(caller)
      case _ => false

  // ── files ─────────────────────────────────────────────────────────

  /** Permission from the configuration only. `p` must be canonical. */
  def configPerm(p: Path): Perm =
    val matching = rules.filter(_.pattern.matches(p))
    // Nothing grants by default; every matching rule is a ceiling, so the most
    // restrictive one wins however many layers wrote it.
    val granted = matching.filter(_.grants(p)).flatMap(_.access).reduceOption(_.max(_)).getOrElse(Access.None)
    val ceiling = matching.flatMap(_.access).reduceOption(_.min(_)).getOrElse(Access.Write)
    Perm(
      granted.min(ceiling),
      classified = matching.exists(_.classified.contains(true)),
      locked = matching.exists(_.locked)
    )

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

  /** What may run without asking in `s`: the configured patterns plus what the
    * user granted in this scope or one enclosing it. */
  private def commandPatterns(s: Scope): List[String] = baseCommands ++ s.chain.flatMap(_.commands)

  /** The `denyCommands` pattern refusing `commandLine`, if any. */
  def commandDenied(commandLine: String): Option[String] =
    denyCommands.find(GlobMatcher.matchesCommand(commandLine, _))

  def commandAllowed(scopeId: ScopeId, commandLine: String): Boolean =
    mode.allowsExec && commandDenied(commandLine).isEmpty &&
      commandPatterns(scope(scopeId)).exists(GlobMatcher.matchesCommand(commandLine, _))

  def requestExec(parentId: ScopeId, commands: List[String], reason: String): ScopeId =
    val parent = scope(parentId)
    if !mode.allowsExec then
      throw SecurityException(s"Access denied: the sandbox is in ${mode.label} mode; commands cannot be run")
    refuseDenied("command", commands, denyCommands, GlobMatcher.matchesCommand)
    val missing = commands.filterNot(commandPatterns(parent).toSet)
    if missing.nonEmpty then
      decide(ExecRequest(missing, reason), s"commands ${missing.mkString(", ")}") { base.commands ++= missing }
    openScope(parent, commands = commands)

  // ── network ───────────────────────────────────────────────────────

  private def hostPatterns(s: Scope): List[String] = baseHosts ++ s.chain.flatMap(_.hosts)

  /** The `denyHosts` pattern refusing `host`, if any. */
  def hostDenied(host: String): Option[String] = denyHosts.find(GlobMatcher.matchesHost(host, _))

  def hostAllowed(scopeId: ScopeId, host: String): Boolean =
    mode.allowsNetwork && hostDenied(host).isEmpty &&
      hostPatterns(scope(scopeId)).exists(GlobMatcher.matchesHost(host, _))

  def requestNet(parentId: ScopeId, hosts: List[String], reason: String): ScopeId =
    val parent = scope(parentId)
    if !mode.allowsNetwork then
      throw SecurityException(s"Access denied: the sandbox is in ${mode.label} mode; the network is not reachable")
    refuseDenied("host", hosts, denyHosts, GlobMatcher.matchesHost)
    val missing = hosts.filterNot(hostPatterns(parent).toSet)
    if missing.nonEmpty then
      decide(NetRequest(missing, reason), s"hosts ${missing.mkString(", ")}") { base.hosts ++= missing }
    openScope(parent, hosts = hosts)

  /** Refuse a `request*` whose patterns collide with the deny list, before the
    * user is asked: a request is a widening, and the deny list is exactly what
    * may not be widened into. Two patterns collide when either one, read as a
    * concrete command line / host name, matches the other — so `denyCommands:
    * ["rm *"]` refuses both `requestExec(Set("rm -rf build"))` (the deny
    * pattern covers it) and `requestExec(Set("rm*"))` (granting it would
    * cover the deny pattern). */
  private def refuseDenied(
    what: String,
    requested: List[String],
    denied: List[String],
    matches: (String, String) => Boolean
  ): Unit =
    val hits = for r <- requested; d <- denied if matches(r, d) || matches(d, r) yield s"'$r' (deny pattern '$d')"
    if hits.nonEmpty then
      throw SecurityException(
        s"Access denied by the configuration: the $what ${if hits.size == 1 then "pattern" else "patterns"} " +
          s"${hits.distinct.mkString(", ")} may not be granted, so the user is not asked. " +
          "Do not retry: report this to the user instead."
      )

  // ── scopes ────────────────────────────────────────────────────────

  /** Put `request` to the user. Denial throws (`what` names what was refused);
    * "allow for the session" also runs `remember`, which records the grant on
    * the base scope. Returns normally when the caller may open its scope. */
  private def decide(request: PermissionRequest, what: String)(remember: => Unit): Unit =
    val decision = prompter.ask(request)
    decisionLog.synchronized(decisionLog += (decision -> what))
    decision match
      case Decision.Deny => throw SecurityException(s"Access denied by the user: $what")
      case Decision.AllowOnce => ()
      case Decision.AllowSession => remember

  /** Every decision the user made at a prompt, in order, with what it was
    * about as a phrase (`write on '/tmp/x'`, `commands npm *`). The agent
    * reads what was added during a tool call and tells the model in that
    * call's result, since the model cannot see the pop-ups: it would otherwise
    * take an "allow once" for a standing grant, or a later "no" for a
    * revocation. The prompt itself never changes with a decision, so every
    * request keeps its prefix. */
  private val decisionLog = scala.collection.mutable.ListBuffer[(Decision, String)]()
  def decisionCount: Int = decisionLog.synchronized(decisionLog.length)
  def decisionsSince(count: Int): List[(Decision, String)] = decisionLog.synchronized(decisionLog.drop(count).toList)

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

  /** Forget everything decided during the session: the "allow for the session"
    * grants and every scope still open (a `request*` block whose capability
    * outlived it). The configured rules, the deny lists and the mode stay. */
  def resetSession(): Unit =
    scopes.clear()
    scopes.put(ScopeId.Base, base)
    base.fileGrants = Nil
    base.commands = Nil
    base.hosts = Nil
    decisionLog.synchronized(decisionLog.clear())

  def openScopeCount: Int = scopes.size - 1

  /** Human-readable summary for the UI (`/perms`): the configuration, the mode
    * and what the user granted for the session. */
  def summary: String = render(withSession = true)

  /** The same without the session grants: what the system prompt states.
    * Grants reach the model through the tool results instead, so the prompt
    * stays the same for the whole session (see `decisionsSince`). */
  def configSummary: String = render(withSession = false)

  private def render(withSession: Boolean): String =
    // Rules that only mark paths classified are folded into one line; there are
    // a dozen of them in a normal config and each says the same thing.
    val (classifiedOnly, explicit) = rules.partition(r => r.grantsWithin.isEmpty && r.classifiedOnly)
    def listOf(patterns: List[String]) = if patterns.isEmpty then "(none)" else patterns.mkString(", ")
    def session(patterns: List[String]) =
      if !withSession || patterns.isEmpty then "" else s"  + session: ${patterns.mkString(", ")}"
    val lines = List.newBuilder[String]
    lines += s"Mode: ${mode.label} (${mode.description})"
    lines += "File rules (strictest matching rule wins; unmatched paths are inaccessible):"
    lines ++= explicit.map(r => s"  ${r.describe}")
    if classifiedOnly.nonEmpty then
      lines += s"  classified patterns: ${classifiedOnly.map(_.pattern).mkString(", ")}"
    if withSession && base.fileGrants.nonEmpty then
      lines += "Session file grants:"
      lines ++= base.fileGrants.reverse.map((p, a) => s"  $p: ${a.label}")
    lines += s"Commands: ${listOf(baseCommands)}${session(base.commands)}"
    if denyCommands.nonEmpty then lines += s"  always refused: ${denyCommands.mkString(", ")}"
    lines += s"Hosts:    ${listOf(baseHosts)}${session(base.hosts)}"
    if denyHosts.nonEmpty then lines += s"  always refused: ${denyHosts.mkString(", ")}"
    lines.result().mkString("\n")
