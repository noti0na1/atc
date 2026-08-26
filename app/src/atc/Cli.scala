package atc

import atc.perms.Mode
import atc.platform.PlatformPath

import java.nio.file.{Files, Path, Paths}

/** Command-line data, parsing, path resolution, and validation. */
private[atc] object Cli:
  final case class Args(
    config: Option[Path] = None,
    cwd: Path = LauncherEnvironment.workingDirectory(),
    model: Option[String] = None,
    mode: Option[Mode] = None,
    prompt: Option[String] = None,
    approveAll: Boolean = false,
    init: Boolean = false,
    initGlobal: Boolean = false,
    help: Boolean = false,
    version: Boolean = false,
  )

  private val FlagsWithValues = Set(
    "-c",
    "--config",
    "-C",
    "--cwd",
    "-m",
    "--model",
    "-p",
    "--prompt",
    "--mode",
  )

  /** Parse first, then resolve paths once. In particular, `-c extra.json -C
    * project` and `-C project -c extra.json` have identical meaning. */
  def parse(arguments: List[String], initial: Args = Args()): Args =
    val raw = parseRaw(arguments, initial)
    val base = initial.cwd.toAbsolutePath.nn.normalize.nn
    val cwd = resolve(base, raw.cwd)
    raw.copy(cwd = cwd, config = raw.config.map(resolve(cwd, _)))

  private def parseRaw(arguments: List[String], current: Args): Args = arguments match
    case Nil => current
    case ("-c" | "--config") :: value :: rest =>
      parseRaw(rest, current.copy(config = Some(path(value))))
    case ("-C" | "--cwd") :: value :: rest =>
      parseRaw(rest, current.copy(cwd = path(value)))
    case ("-m" | "--model") :: value :: rest => parseRaw(rest, current.copy(model = Some(value)))
    case ("-p" | "--prompt") :: value :: rest => parseRaw(rest, current.copy(prompt = Some(value)))
    case "--mode" :: value :: rest => parseRaw(rest, current.copy(mode = Some(Mode.parse(value))))
    case "--approve-all" :: rest => parseRaw(rest, current.copy(approveAll = true))
    case "--init" :: rest => parseRaw(rest, current.copy(init = true))
    case "--init-global" :: rest => parseRaw(rest, current.copy(initGlobal = true))
    case ("-h" | "--help") :: rest => parseRaw(rest, current.copy(help = true))
    case ("-v" | "--version") :: rest => parseRaw(rest, current.copy(version = true))
    case flag :: Nil if FlagsWithValues(flag) =>
      throw IllegalArgumentException(s"$flag requires a value (try --help)")
    case other :: _ => throw IllegalArgumentException(s"Unknown argument: $other (try --help)")

  private def resolve(base: Path, value: Path): Path =
    (if value.isAbsolute then value else base.resolve(value).nn).normalize.nn

  private def path(value: String): Path =
    val expanded = PlatformPath.expandHome(value)
    PlatformPath.validationError(expanded).foreach(reason =>
      throw IllegalArgumentException(s"Invalid Windows path ${ScalaSource.stringLiteral(value)}: $reason")
    )
    Paths.get(PlatformPath.native(expanded)).nn

  /** Validate paths only for actions that use them, so `--help` and
    * `--version` remain available from a deleted working directory. */
  def validate(args: Args): Args =
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
