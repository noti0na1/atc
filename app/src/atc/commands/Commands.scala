package atc.commands

import atc.{App, Debug}
import atc.agent.Prompts
import atc.commands.SlashCommand as Cmd
import atc.perms.Mode
import atc.ui.Ansi

import scala.util.control.NonFatal

/** The slash commands of a session: Tab completion, and running a typed
  * command line. [[SlashCommand]] is the table of commands; the `*Commands`
  * classes and [[ProvidersMenu]] implement them. */
final class Commands(app: App):
  import app.{host, models, tui}

  private val modelCommands = ModelCommands(app)
  private val statusCommands = StatusCommands(app)
  private val providersMenu = ProvidersMenu(app, modelCommands)
  /** Also used at the start and end of an interactive run, to resume and save the session. */
  val sessionCommands: SessionCommands = SessionCommands(app)

  /** Completion candidates for the words typed so far: command names, then a command's choices. */
  def complete(words: List[String]): List[String] = words match
    case _ :: Nil => SlashCommand.names
    case "/model" :: _ :: Nil => models.catalog.labels
    case "/effort" :: _ :: Nil => modelCommands.effortChoices
    case "/classifiedmodel" :: _ :: Nil => "none" +: models.catalog.labels
    case "/mode" :: _ :: Nil => Mode.values.toList.map(_.label)
    case "/perms" :: _ :: Nil => List("revoke")
    case _ => Nil

  /** Handle a slash command line; returns false to quit. */
  def run(line: String): Boolean =
    SlashCommand.parse(line) match
      case Left(typed) =>
        tui.error(s"unknown command $typed (try /help)")
        true
      case Right((Cmd.Quit, _)) => false
      case Right((cmd, arg)) =>
        try dispatch(cmd, arg)
        catch
          case NonFatal(error) =>
            tui.error(Debug.message(error))
            Debug.trace(error)
        true

  private def dispatch(cmd: SlashCommand, arg: String): Unit = cmd match
    case Cmd.Help => tui.showHelp(SlashCommand.values.toList.map(command => command.usage -> command.help))
    case Cmd.Model => modelCommands.switchModel(arg)
    case Cmd.ClassifiedModel => modelCommands.switchClassified(arg)
    case Cmd.Models => modelCommands.show()
    case Cmd.Effort => modelCommands.switchEffort(arg)
    case Cmd.Providers => providersMenu.run()
    case Cmd.Mode => sessionCommands.switchMode(arg)
    case Cmd.Perms => statusCommands.permissions(arg)
    case Cmd.Config => statusCommands.showConfig()
    case Cmd.Interface => tui.println(Prompts.interfaceSource)
    case Cmd.Run => sessionCommands.run(arg)
    case Cmd.New => sessionCommands.newSession()
    case Cmd.Reset => sessionCommands.reset()
    case Cmd.Clear => sessionCommands.clear()
    case Cmd.Compact => sessionCommands.compact(arg)
    case Cmd.Todos => tui.showTodosNow(host.currentTodos)
    // Both commands display model-generated process names, so strip terminal controls.
    case Cmd.Ps => tui.println(Ansi.sanitize(host.processSummary))
    case Cmd.Kill => tui.println(Ansi.sanitize(host.killProcess(arg)))
    case Cmd.Cost => statusCommands.showCost()
    case Cmd.Output => tui.showOutput(arg)
    case Cmd.Task => statusCommands.showTask()
    case Cmd.Save => sessionCommands.save(arg)
    case Cmd.Resume => sessionCommands.resume(arg)
    case Cmd.Quit => () // `run` ends the loop instead
