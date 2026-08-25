package atc

import atc.config.Config
import atc.perms.{Mode, PathPattern}
import atc.ui.Ansi

import java.nio.file.{Files, Path, Paths}

/** Command line entry point: parses the flags and starts [[App]]. */
object Main:
  /** Launchers use this namespace for values that must cross Windows' legacy
    * command-line encoding boundary. Never inherit them into tool processes. */
  private[atc] val InternalEnvironmentPrefix = "ATC_INTERNAL_"
  private val ArgCountEnvironment = InternalEnvironmentPrefix + "ARG_COUNT"
  private val ArgEnvironmentPrefix = InternalEnvironmentPrefix + "ARG_"
  private val EncodedArgSentinel = "x"

  private[atc] def isInternalEnvironment(name: String): Boolean =
    name.regionMatches(true, 0, InternalEnvironmentPrefix, 0, InternalEnvironmentPrefix.length)

  /** Written by the build (`Versions.atc` in `build.mill`) into `atc/version.txt`. */
  lazy val Version: String =
    sys.props.get("atc.version").map(_.trim).filter(_.nonEmpty)
      .orElse(Resources.text("/atc/version.txt").map(_.trim).filter(_.nonEmpty))
      .getOrElse("dev")

  /** The Windows release launchers enter their installation directory so Java can
    * open jars there even when that path contains characters outside the
    * machine's legacy ANSI code page. It carries the user's real cwd through
    * the Unicode Windows environment instead of Java's command line. */
  private def launchCwd: Path =
    Option(System.getenv("ATC_INTERNAL_LAUNCH_CWD"))
      .map(value => Paths.get(value).nn.toAbsolutePath.nn.normalize.nn)
      .getOrElse(Paths.get("").nn.toAbsolutePath.nn.normalize)

  case class Args(
    config: Option[Path] = None,
    cwd: Path = launchCwd,
    model: Option[String] = None,
    mode: Option[Mode] = None,
    prompt: Option[String] = None,
    approveAll: Boolean = false,
    init: Boolean = false,
    initGlobal: Boolean = false,
    help: Boolean = false,
    version: Boolean = false,
  )

  /** Parse first, then resolve paths once. In particular, `-c extra.json -C
    * project` and `-C project -c extra.json` must mean the same thing. */
  def parseArgs(args: List[String], acc: Args = Args()): Args =
    val raw = parseRawArgs(args, acc)
    val base = acc.cwd.toAbsolutePath.nn.normalize.nn
    val cwd = resolve(base, raw.cwd)
    raw.copy(cwd = cwd, config = raw.config.map(resolve(cwd, _)))

  private def parseRawArgs(args: List[String], acc: Args): Args = args match
    case Nil => acc
    case ("-c" | "--config") :: p :: rest =>
      parseRawArgs(rest, acc.copy(config = Some(path(p))))
    case ("-C" | "--cwd") :: p :: rest =>
      parseRawArgs(rest, acc.copy(cwd = path(p)))
    case ("-m" | "--model") :: m :: rest => parseRawArgs(rest, acc.copy(model = Some(m)))
    case ("-p" | "--prompt") :: p :: rest => parseRawArgs(rest, acc.copy(prompt = Some(p)))
    case "--mode" :: m :: rest => parseRawArgs(rest, acc.copy(mode = Some(Mode.parse(m))))
    case "--approve-all" :: rest => parseRawArgs(rest, acc.copy(approveAll = true))
    case "--init" :: rest => parseRawArgs(rest, acc.copy(init = true))
    case "--init-global" :: rest => parseRawArgs(rest, acc.copy(initGlobal = true))
    case ("-h" | "--help") :: rest => parseRawArgs(rest, acc.copy(help = true))
    case ("-v" | "--version") :: rest => parseRawArgs(rest, acc.copy(version = true))
    case flag :: Nil if Set("-c", "--config", "-C", "--cwd", "-m", "--model", "-p", "--prompt", "--mode")(flag) =>
      throw IllegalArgumentException(s"$flag requires a value (try --help)")
    case other :: _ => throw IllegalArgumentException(s"Unknown argument: $other (try --help)")

  private def resolve(base: Path, value: Path): Path =
    (if value.isAbsolute then value else base.resolve(value).nn).normalize.nn

  private def path(value: String): Path =
    val expanded = PathPattern.expandHome(value)
    if java.io.File.separatorChar == '\\' then
      PathPattern.invalidWindowsPath(expanded).foreach(reason =>
        throw IllegalArgumentException(s"Invalid Windows path ${atc.host.Host.scalaString(value)}: $reason")
      )
    Paths.get(expanded).nn

  /** Windows' Java launcher first converts its UTF-16 command line through the
    * machine ANSI code page. The batch and PowerShell launchers therefore pass
    * application arguments through the Unicode child environment and give
    * java.exe only fixed ASCII arguments. */
  private[atc] def launchArgs(argv: List[String], environment: String => Option[String]): List[String] =
    environment(ArgCountEnvironment) match
      case None => argv
      case Some(rawCount) =>
        val count = rawCount.toIntOption.filter(n => n >= 0 && n <= 10_000).getOrElse(
          throw IllegalArgumentException(s"Invalid internal launcher argument count: $rawCount")
        )
        List.tabulate(count) { index =>
          val encoded = environment(ArgEnvironmentPrefix + index).getOrElse(
            throw IllegalArgumentException(s"Windows launcher did not provide argument $index of $count")
          )
          if !encoded.startsWith(EncodedArgSentinel) then
            throw IllegalArgumentException(s"Windows launcher provided an invalid argument $index of $count")
          encoded.drop(EncodedArgSentinel.length)
        }

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
      try
        val launched = launchArgs(argv.toList, name => Option(System.getenv(name)))
        validateArgs(parseArgs(launched))
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
