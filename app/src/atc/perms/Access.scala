package atc.perms

/** Level of access to a path. Ordered: `None < Read < Write` (write includes read). */
enum Access(val level: Int, val label: String):
  case None extends Access(0, "none")
  case Read extends Access(1, "read")
  case Write extends Access(2, "write")

  def >=(that: Access): Boolean = level >= that.level
  def min(that: Access): Access = if level <= that.level then this else that
  def max(that: Access): Access = if level >= that.level then this else that

object Access:
  def parse(s: String): Access = s.trim.toLowerCase match
    case "none" | "deny" | "no" => None
    case "read" | "r" | "ro" => Read
    case "write" | "w" | "rw" | "readwrite" | "read-write" => Write
    case other => throw IllegalArgumentException(s"Unknown access level '$other' (expected none|read|write)")

/** The effective permission of a path. `classified` means the content (and,
  * for directories, the structure) may only be observed through
  * `Classified` values; `locked` means the configuration forbids widening
  * the access through a user prompt. */
case class Perm(access: Access, classified: Boolean, locked: Boolean = false):
  def canRead: Boolean = access >= Access.Read
  def canWrite: Boolean = access >= Access.Write
  def describe: String =
    val flags = List(Option.when(classified)("classified"), Option.when(locked)("locked")).flatten
    if flags.isEmpty then access.label else s"${access.label} (${flags.mkString(", ")})"

/** The sandbox mode: which capabilities the REPL preamble hands to the agent
  * (type level, `ReplSession.preamble`) and, as defence in depth, what the
  * policy lets through at run time. Ordered from least to most permissive. */
enum Mode(val label: String, val description: String):
  /** `io` and `fs` are read-only views: the agent can only read files. */
  case ReadOnly extends Mode("read-only", "files can only be read; no commands, no network")
  /** Read and write files, run commands; no network and no full `io`. */
  case Local extends Mode("local", "files can be read and written, commands run; no network")
  /** The full root capability: files, commands, network. */
  case Full extends Mode("full", "files, commands and network")

  def allowsWrite: Boolean = this != ReadOnly
  def allowsExec: Boolean = this != ReadOnly
  def allowsNetwork: Boolean = this == Full
  /** One line naming the mode and what it allows, for the banner and `/mode`. */
  def describe: String = s"$label: $description"
  /** The next mode when cycling (Shift-Tab / `/mode` without an argument). */
  def next: Mode = Mode.fromOrdinal((ordinal + 1) % Mode.values.length)

object Mode:
  def parse(s: String): Mode = s.trim.toLowerCase match
    case "readonly" | "read-only" | "ro" | "read" => ReadOnly
    case "local" | "rw" => Local
    case "full" | "all" => Full
    case other => throw IllegalArgumentException(s"Unknown mode '$other' (expected readonly|local|full)")
