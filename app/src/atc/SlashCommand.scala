package atc

/** Slash-command names, aliases and help text. Parsing and completion share
  * this table; [[App]] implements the command actions. */
enum SlashCommand(val usage: String, val help: String, val aliases: String*):
  case Help extends SlashCommand("/help", "show commands", "/h", "/?")
  case Model extends SlashCommand("/model [ref]", "choose or switch the agent model")
  case ClassifiedModel
      extends SlashCommand(
        "/classifiedmodel [ref]",
        "choose a classified model; off disables it",
        "/classified",
      )
  case Models extends SlashCommand("/models", "list configured models")
  case Mode
      extends SlashCommand(
        "/mode [name]",
        "change mode and restart the REPL",
      )
  case Perms
      extends SlashCommand("/perms [revoke [number|all]]", "show permissions or revoke session grants", "/permissions")
  case Config extends SlashCommand("/config", "show the active configuration")
  case Interface extends SlashCommand("/interface", "show the sandbox API reference", "/api")
  case Run
      extends SlashCommand(
        "/run [code]",
        "run Scala; omit code for multiline input",
        "/scala",
      )
  case New extends SlashCommand("/new", "clear conversation, task state, REPL and session grants")
  case Reset extends SlashCommand("/reset", "restart the REPL; keep the conversation")
  case Clear extends SlashCommand("/clear", "clear the conversation; keep the REPL")
  case Todos extends SlashCommand("/todos", "show tasks and progress", "/todo")
  case Ps extends SlashCommand("/ps", "list background processes", "/processes")
  case Kill extends SlashCommand("/kill [id|all]", "stop one or all background processes")
  case Cost extends SlashCommand("/cost", "show token usage and context", "/usage", "/context")
  case Output extends SlashCommand("/output [number|last] [line]", "inspect retained tool output and file changes")
  case Task extends SlashCommand("/task", "show the task goal, constraints and progress")
  case Save extends SlashCommand("/save [file]", "save conversation and task notes to a new file")
  case Resume
      extends SlashCommand("/resume [file]", "restore the last session or a saved file")
  case Quit extends SlashCommand("/quit", "save the session and exit", "/exit", "/q")

  /** The name as typed, e.g. `/help`. */
  def name: String = usage.takeWhile(_ != ' ')
  def answersTo(typed: String): Boolean = typed == name || aliases.contains(typed)

object SlashCommand:
  /** The names, in `/help` order, for Tab completion (aliases are accepted but not offered). */
  def names: List[String] = values.toList.map(_.name)

  private[atc] val helpWidth: Int = values.map(_.usage.length).max.max(22) + 2
  lazy val helpText: String =
    ("Commands:" :: values.toList.map(c => s"  ${c.usage.padTo(helpWidth, ' ')}${c.help}")).mkString("\n")

  /** The command a typed line names (case-insensitively), with its argument:
    * the rest of the line, trimmed. `Left(typed)` when nothing answers to it. */
  def parse(line: String): Either[String, (SlashCommand, String)] =
    val parts = line.trim.split("\\s+", 2)
    val typed = parts(0).toLowerCase(java.util.Locale.ROOT)
    val arg = if parts.length > 1 then parts(1).trim else ""
    values.find(_.answersTo(typed)).toRight(typed).map(_ -> arg)
