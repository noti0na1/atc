package atc.host

import atc.ProcessEnvironment
import atc.platform.Platform

import java.nio.file.{Files, Path, Paths}

/** Secure Windows executable lookup and launcher validation.
  *
  * Windows `CreateProcess` searches the current directory before `PATH`. That
  * would let a repository-local executable shadow a command the user permitted.
  * This resolver implements the safe lookup explicitly and honours `PATHEXT`.
  */
private[atc] object WindowsExecutable:
  private val DefaultExtensions = ".COM;.EXE;.BAT;.CMD"

  /** Disable OpenJDK's ambiguous legacy command/batch quoting on Windows. */
  def configureProcessRuntime(): Unit =
    if Platform.isWindows then System.setProperty("jdk.lang.Process.allowAmbiguousCommands", "false")

  /** Resolve `argv.head` safely on Windows; other platforms pass argv through. */
  def resolve(
    argv: List[String],
    workingDir: Path,
    environment: collection.Map[String, String] = ProcessEnvironment.entries,
  ): List[String] =
    if !Platform.isWindows || argv.isEmpty then argv
    else
      configureProcessRuntime()
      val command = argv.head
      val extensions = environmentValue(environment, "PATHEXT").getOrElse(DefaultExtensions)
        .split(";", -1).iterator.map(_.trim).filter(_.nonEmpty)
        .map(extension => if extension.startsWith(".") then extension else s".$extension").toList
      val raw = Paths.get(command).nn
      val explicit = raw.isAbsolute || command.exists(char => char == '/' || char == '\\')
      val bases =
        if explicit then List(if raw.isAbsolute then raw else workingDir.resolve(raw).nn)
        else pathBases(command, workingDir, environment)

      val resolved = bases.iterator.flatMap(candidates(_, extensions)).find(Files.isRegularFile(_))
      resolved match
        case None if explicit => argv // Let ProcessBuilder report the missing explicit path.
        case None =>
          throw java.io.IOException(
            s"Executable '$command' was not found on PATH; on Windows ATC does not search the working directory for bare commands (use .\\$command explicitly)"
          )
        case Some(path) => validate(path, argv, command)

  private def environmentValue(environment: collection.Map[String, String], name: String): Option[String] =
    environment.collectFirst { case (key, value) if key.equalsIgnoreCase(name) => value }

  private def pathBases(
    command: String,
    workingDir: Path,
    environment: collection.Map[String, String],
  ): List[Path] =
    environmentValue(environment, "PATH").toList.flatMap(_.split(Platform.pathListSeparator, -1))
      .map(_.trim).filter(_.nonEmpty)
      .flatMap { value =>
        // Empty and relative PATH entries mean "the current directory" on
        // Windows. Ignore them so they cannot restore cwd shadowing.
        scala.util.Try(
          workingDir.getFileSystem.getPath(value.stripPrefix("\"").stripSuffix("\"")).nn
        ).toOption.filter(_.isAbsolute).map(_.resolve(command).nn)
      }

  private def candidates(base: Path, extensions: List[String]): List[Path] =
    val name = Option(base.getFileName).fold("")(_.toString)
    val exact = List(base)
    if name.lastIndexOf('.') > 0 then exact
    else extensions.map(extension => base.resolveSibling(name + extension).nn) ++ exact

  private def validate(path: Path, argv: List[String], command: String): List[String] =
    val lower = path.getFileName.toString.toLowerCase(java.util.Locale.ROOT)
    if lower.endsWith(".cmd") || lower.endsWith(".bat") then
      val unsafe = path.toString :: argv.tail
      unsafe.find(_.exists(char => char == '%' || char == '!' || char == '\r' || char == '\n' || char == 0))
        .foreach { _ =>
          throw IllegalArgumentException(
            s"Unsafe path or argument for Windows batch command '$command': %, ! and line breaks can be expanded by cmd.exe; invoke an explicitly permitted cmd.exe command if shell syntax is intended"
          )
        }
    else if lower.contains('.') && !lower.endsWith(".exe") && !lower.endsWith(".com") then
      throw IllegalArgumentException(
        s"Windows cannot execute '$command' directly; invoke its interpreter explicitly (for example powershell.exe -File for .ps1)"
      )
    path.toString :: argv.tail
