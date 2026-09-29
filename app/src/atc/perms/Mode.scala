package atc.perms

import java.util.Locale

/** The sandbox mode: which capabilities the REPL preamble hands to the agent
  * (type level, `ReplSession.preambleChunks`) and, as defence in depth, what the
  * policy lets through at run time. Ordered from least to most permissive. */
enum Mode(val label: String, val description: String):
  /** Local mode's capabilities on a copy of the project (`Isolation`); its changes reach the
    * project only when the user applies them. Listed first: the strictest for narrowing. */
  case Isolate
      extends Mode("isolate", "files and commands work on a copy of the project, applied with /apply; no network")
  /** `io` and `fs` are read-only views: the agent reads files and runs commands read-only. */
  case ReadOnly extends Mode("read-only", "files can only be read; commands run read-only; no network")
  /** Full `io` grouping writable `fs` and `ex`; the mode provides no `net`. */
  case Local extends Mode("local", "files can be read and written, commands run; no network")
  /** Full `io` grouping `fs`, `ex`, and `net`. */
  case Full extends Mode("full", "files, commands and network")

  def allowsWrite: Boolean = this != ReadOnly
  def allowsNetwork: Boolean = this == Full
  /** One line naming the mode and what it allows, for the banner and `/mode`. */
  def describe: String = s"$label: $description"
  /** The next mode when cycling (Shift-Tab / `/mode` without an argument). Isolate mode, which
    * moves the session to a copy of the project, is left out: `/mode isolate` enters it. */
  def next: Mode = this match
    case Full | Isolate => ReadOnly
    case other => Mode.fromOrdinal(other.ordinal + 1)

object Mode:
  def parse(s: String): Mode = s.trim.toLowerCase(Locale.ROOT) match
    case "readonly" | "read-only" | "ro" | "read" => ReadOnly
    case "local" | "rw" => Local
    case "full" | "all" => Full
    case "isolate" | "isolated" => Isolate
    case other => throw IllegalArgumentException(s"Unknown mode '$other' (expected isolate|readonly|local|full)")
