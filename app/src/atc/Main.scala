package atc

import atc.config.Config
import atc.perms.{Mode, PathPattern}
import atc.ui.Ansi

import java.nio.file.{Files, Path, Paths}

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
    case ("-c" | "--config") :: p :: rest => parseArgs(rest, acc.copy(config = Some(path(p))))
    case ("-C" | "--cwd") :: p :: rest => parseArgs(rest, acc.copy(cwd = path(p).toAbsolutePath.normalize))
    case ("-m" | "--model") :: m :: rest => parseArgs(rest, acc.copy(model = Some(m)))
    case ("-p" | "--prompt") :: p :: rest => parseArgs(rest, acc.copy(prompt = Some(p)))
    case "--mode" :: m :: rest => parseArgs(rest, acc.copy(mode = Some(Mode.parse(m))))
    case "--approve-all" :: rest => parseArgs(rest, acc.copy(approveAll = true))
    case "--init" :: rest => parseArgs(rest, acc.copy(init = true))
    case "--init-global" :: rest => parseArgs(rest, acc.copy(initGlobal = true))
    case ("-h" | "--help") :: rest => parseArgs(rest, acc.copy(help = true))
    case ("-v" | "--version") :: rest => parseArgs(rest, acc.copy(version = true))
    case flag :: Nil if Set("-c", "--config", "-C", "--cwd", "-m", "--model", "-p", "--prompt", "--mode")(flag) =>
      throw IllegalArgumentException(s"$flag requires a value (try --help)")
    case other :: _ => throw IllegalArgumentException(s"Unknown argument: $other (try --help)")

  private def path(value: String): Path =
    val expanded = PathPattern.expandHome(value)
    if java.io.File.separatorChar == '\\' then
      PathPattern.invalidWindowsPath(expanded).foreach(reason =>
        throw IllegalArgumentException(s"Invalid Windows path ${atc.host.Host.scalaString(value)}: $reason")
      )
    Paths.get(expanded).nn

  /** Validate paths only for actions that use them, so `atc --help` and
    * `--version` remain available from a deleted working directory. */
  private[atc] def validateArgs(args: Args): Args =
    if !args.help && !args.version && !args.initGlobal then
      if !Files.exists(args.cwd) then
        throw IllegalArgumentException(s"Working directory does not exist: ${args.cwd}")
      if !Files.isDirectory(args.cwd) then
        throw IllegalArgumentException(s"Working directory is not a directory: ${args.cwd}")
    if !args.help && !args.version && !args.init && !args.initGlobal then
      args.config.foreach { config =>
        if !Files.exists(config) then throw IllegalArgumentException(s"Config file does not exist: $config")
        if !Files.isRegularFile(config) then
          throw IllegalArgumentException(s"Config path is not a regular file: $config")
      }
    args

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
    val args: Args =
      try validateArgs(parseArgs(argv.toList))
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

  private def run(args: Args): Int =
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
