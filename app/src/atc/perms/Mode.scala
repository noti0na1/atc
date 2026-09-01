package atc.perms

/** The sandbox mode: which capabilities the REPL preamble hands to the agent
  * (type level, `ReplSession.preambleChunks`) and, as defence in depth, what the
  * policy lets through at run time. Ordered from least to most permissive. */
enum Mode(val label: String, val description: String):
  /** `io` and `fs` are read-only views: the agent can only read files. */
  case ReadOnly extends Mode("read-only", "files can only be read; no commands, no network")
  /** Full `io` grouping writable `fs` and `ex`; the mode provides no `net`. */
  case Local extends Mode("local", "files can be read and written, commands run; no network")
  /** Full `io` grouping `fs`, `ex`, and `net`. */
  case Full extends Mode("full", "files, commands and network")

  def allowsWrite: Boolean = this != ReadOnly
  def allowsExec: Boolean = this != ReadOnly
  def allowsNetwork: Boolean = this == Full
  /** One line naming the mode and what it allows, for the banner and `/mode`. */
  def describe: String = s"$label: $description"
  /** The next mode when cycling (Shift-Tab / `/mode` without an argument). */
  def next: Mode = Mode.fromOrdinal((ordinal + 1) % Mode.values.length)

object Mode:
  def parse(s: String): Mode = s.trim.toLowerCase(java.util.Locale.ROOT) match
    case "readonly" | "read-only" | "ro" | "read" => ReadOnly
    case "local" | "rw" => Local
    case "full" | "all" => Full
    case other => throw IllegalArgumentException(s"Unknown mode '$other' (expected readonly|local|full)")
