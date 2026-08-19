package atc

/** The slash commands of the interactive loop: how each is typed (`usage`
  * starts with its name), the other spellings it answers to, and what `/help`
  * says about it. Pure on purpose, so the help text, Tab completion and the
  * parsing of a typed line are testable without a terminal; [[App]] runs them. */
enum SlashCommand(val usage: String, val help: String, val aliases: String*):
  case Help extends SlashCommand("/help", "this help", "/h", "/?")
  case Model extends SlashCommand("/model [ref]", "switch the agent model (no argument: pick from a list)")
  case ClassifiedModel
      extends SlashCommand(
        "/classifiedmodel [ref]",
        "switch the model that may see classified data (\"off\" to unset)",
        "/classified",
      )
  case Models extends SlashCommand("/models", "list configured models")
  case Mode
      extends SlashCommand(
        "/mode [name]",
        "cycle the sandbox mode (readonly → local → full), or set it; restarts the REPL",
      )
  case Perms extends SlashCommand("/perms", "show the effective permission policy", "/permissions")
  case Config extends SlashCommand("/config", "show config files and settings")
  case Interface extends SlashCommand("/interface", "show the sandbox API reference", "/api")
  case Run
      extends SlashCommand(
        "/run [code]",
        "run Scala in the sandbox yourself, as the agent would (no code: type lines, an empty one runs them)",
        "/scala",
      )
  case New extends SlashCommand("/new", "start over: fresh REPL, conversation, TODOs and session grants forgotten")
  case Reset extends SlashCommand("/reset", "restart the sandbox REPL (keeps the conversation)")
  case Clear extends SlashCommand("/clear", "forget the conversation (keeps the REPL)")
  case Todos extends SlashCommand("/todos", "show the agent's TODO list", "/todo")
  case Cost extends SlashCommand("/cost", "show token usage and how full the context is", "/usage", "/context")
  case Quit extends SlashCommand("/quit", "exit", "/exit", "/q")

  /** The name as typed, e.g. `/help`. */
  def name: String = usage.takeWhile(_ != ' ')
  def answersTo(typed: String): Boolean = typed == name || aliases.contains(typed)

object SlashCommand:
  /** The names, in `/help` order, for Tab completion (aliases are accepted but not offered). */
  def names: List[String] = values.toList.map(_.name)

  lazy val helpText: String =
    ("Commands:" :: values.toList.map(c => s"  ${c.usage.padTo(24, ' ')}${c.help}")).mkString("\n")

  /** The command a typed line names (case-insensitively), with its argument:
    * the rest of the line, trimmed. `Left(typed)` when nothing answers to it. */
  def parse(line: String): Either[String, (SlashCommand, String)] =
    val parts = line.trim.split("\\s+", 2)
    val typed = parts(0).toLowerCase
    val arg = if parts.length > 1 then parts(1).trim else ""
    values.find(_.answersTo(typed)).toRight(typed).map(_ -> arg)
