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
  def parse(s: String): Access = s.trim.toLowerCase(java.util.Locale.ROOT) match
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
