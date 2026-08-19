package atc

import atc.config.Config
import atc.perms.Mode

import java.nio.file.{Path, Paths}

/** Command line entry point: parses the flags and starts [[App]]. */
object Main:
  /** Written by the build (`Versions.atc` in `build.mill`) into `atc/version.txt`. */
  lazy val Version: String = Resources.text("/atc/version.txt").map(_.trim).getOrElse("dev")

  case class Args(
    config: Option[Path] = None,
    cwd: Path = Paths.get("").toAbsolutePath,
    model: Option[String] = None,
    mode: Option[Mode] = None,
    prompt: Option[String] = None,
    approveAll: Boolean = false,
    init: Boolean = false,
    initGlobal: Boolean = false,
    help: Boolean = false,
    version: Boolean = false,
  )

  def parseArgs(args: List[String], acc: Args = Args()): Args = args match
    case Nil => acc
    case ("-c" | "--config") :: p :: rest => parseArgs(rest, acc.copy(config = Some(Paths.get(p))))
    case ("-C" | "--cwd") :: p :: rest => parseArgs(rest, acc.copy(cwd = Paths.get(p).toAbsolutePath.normalize))
    case ("-m" | "--model") :: m :: rest => parseArgs(rest, acc.copy(model = Some(m)))
    case ("-p" | "--prompt") :: p :: rest => parseArgs(rest, acc.copy(prompt = Some(p)))
    case "--mode" :: m :: rest => parseArgs(rest, acc.copy(mode = Some(Mode.parse(m))))
    case "--approve-all" :: rest => parseArgs(rest, acc.copy(approveAll = true))
    case "--init" :: rest => parseArgs(rest, acc.copy(init = true))
    case "--init-global" :: rest => parseArgs(rest, acc.copy(initGlobal = true))
    case ("-h" | "--help") :: rest => parseArgs(rest, acc.copy(help = true))
    case ("-v" | "--version") :: rest => parseArgs(rest, acc.copy(version = true))
    case other :: _ => throw IllegalArgumentException(s"Unknown argument: $other (try --help)")

  lazy val usage: String =
    s"""atc $Version — a minimal coding agent with tracked capabilities
       |
       |Usage: atc [options]
       |  -c, --config <file>   extra config file (merged over ~/.config/atc/config.json and ./.atc/config.json)
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
    val args: Args =
      try parseArgs(argv.toList)
      catch
        case e: IllegalArgumentException =>
          System.err.println(e.getMessage)
          sys.exit(2)
    if args.help then { println(usage); return }
    if args.version then { println(s"atc $Version"); return }
    if args.initGlobal then
      sys.exit(report(Config.globalPath, Config.ensureGlobal(), "fill in the API keys and edit the permissions"))
    if args.init then
      sys.exit(report(Config.projectPath(args.cwd), Config.initProject(args.cwd), "edit the project's permissions"))
    val exit: Int =
      try App(args).run()
      catch
        case App.Exit(code) => code
        case e: Throwable =>
          System.err.println(s"atc: ${e.getMessage}")
          Debug.trace(e)
          1
    sys.exit(exit)

  /** `--init` / `--init-global`: say what was written, or refuse to overwrite. */
  private def report(target: Path, created: List[Path], todo: String): Int =
    if created.isEmpty then
      System.err.println(s"$target already exists")
      1
    else
      println(s"Wrote ${created.mkString(" and ")}; $todo, then run atc again.")
      0
