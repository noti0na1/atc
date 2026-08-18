package atc.perms

/** Level of access to a path. Ordered: `None < Read < Write` (write includes read). */
enum Access(val level: Int):
  case None extends Access(0)
  case Read extends Access(1)
  case Write extends Access(2)

  def >=(that: Access): Boolean = level >= that.level
  def min(that: Access): Access = if level <= that.level then this else that
  def max(that: Access): Access = if level >= that.level then this else that
  def label: String = this match
    case None => "none"
    case Read => "read"
    case Write => "write"

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
    val flags = List(if classified then Some("classified") else None, if locked then Some("locked") else None).flatten
    if flags.isEmpty then access.label else s"${access.label} (${flags.mkString(", ")})"

object Perm:
  val none: Perm = Perm(Access.None, classified = false)
