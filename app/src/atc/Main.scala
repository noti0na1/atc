package atc

import atc.config.Config
import atc.ui.Ansi

import java.nio.file.Path

/** Command line entry point: parses the flags and starts [[App]]. */
object Main:
  /** Written by the build (`Versions.atc` in `build.mill`) into `atc/version.txt`. */
  lazy val Version: String =
    sys.props.get("atc.version").map(_.trim).filter(_.nonEmpty)
      .orElse(Resources.text("/atc/version.txt").map(_.trim).filter(_.nonEmpty))
      .getOrElse("dev")

  lazy val usage: String =
    s"""atc $Version — a minimal coding agent with tracked capabilities
       |
       |Usage: atc [options]
       |  -c, --config <file>   extra config file (merged over ~/.atc/config.json and the project's .atc/config.json)
       |  -C, --cwd <dir>       working directory (default: current)
       |  -m, --model <ref>     model to use: an alias from the config, or provider/alias
       |  -p, --prompt <text>   run one turn non-interactively and exit
       |      --mode <mode>     sandbox mode: readonly | local | full (default: the config's "mode", else full)
       |      --approve-all     auto-approve permission requests (use with -p in trusted setups only)
       |      --init            write a starter ./.atc/config.json (project layer) and exit
       |      --init-global     write a starter ~/.atc/config.json (global layer) and exit
       |  -h, --help            show this help
       |  -v, --version         show the version
       |""".stripMargin

  def main(argv: Array[String]): Unit =
    val args: Cli.Args =
      try
        val launched = LauncherEnvironment.arguments(argv.toList, ProcessEnvironment.get)
        Cli.validate(Cli.parse(launched))
      catch
        case e: IllegalArgumentException =>
          System.err.println(Ansi.sanitize(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))
          sys.exit(2)
    val exitCode =
      if args.help then
        println(usage)
        0
      else if args.version then
        println(s"atc $Version")
        0
      else if args.initGlobal then
        report(Config.globalPath, Config.ensureGlobal(), "fill in the API keys and edit the permissions")
      else if args.init then
        report(Config.projectPath(args.cwd), Config.initProject(args.cwd), "edit the project's permissions")
      else run(args)
    sys.exit(exitCode)

  private def run(args: Cli.Args): Int =
    try App(args).run()
    catch
      case App.Exit(code) => code
      case e: Throwable =>
        val message = Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
        System.err.println(Ansi.sanitize(s"atc: $message"))
        Debug.trace(e)
        1

  /** `--init` / `--init-global`: say what was written, or refuse to overwrite. */
  private def report(target: Path, created: List[Path], todo: String): Int =
    if created.isEmpty then
      System.err.println(Ansi.sanitize(s"$target already exists"))
      1
    else
      println(Ansi.sanitize(s"Wrote ${created.mkString(" and ")}; $todo, then run atc again."))
      0
