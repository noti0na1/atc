package atc

import java.nio.file.{Path, Paths}

/** Private environment protocol shared by the native launchers and the JVM.
  * Keeping it here prevents Windows transport details from leaking into CLI,
  * sandbox, and child-process logic. */
private[atc] object LauncherEnvironment:
  private val Prefix = "ATC_INTERNAL_"
  val LibraryClasspath = Prefix + "LIB_CLASSPATH"

  private val WorkingDirectory = Prefix + "LAUNCH_CWD"
  private val ArgCount = Prefix + "ARG_COUNT"
  private val ArgPrefix = Prefix + "ARG_"
  private val EncodedArgSentinel = "x"
  private val MaxArguments = 10_000

  def isInternal(name: String): Boolean =
    name.regionMatches(true, 0, Prefix, 0, Prefix.length)

  /** Restore arguments carried through the Unicode Windows environment. */
  def arguments(argv: List[String], environment: String => Option[String]): List[String] =
    environment(ArgCount) match
      case None => argv
      case Some(rawCount) =>
        val count = rawCount.toIntOption.filter(n => n >= 0 && n <= MaxArguments).getOrElse(
          throw IllegalArgumentException(s"Invalid internal launcher argument count: $rawCount")
        )
        List.tabulate(count) { index =>
          val encoded = environment(ArgPrefix + index).getOrElse(
            throw IllegalArgumentException(s"Windows launcher did not provide argument $index of $count")
          )
          if !encoded.startsWith(EncodedArgSentinel) then
            throw IllegalArgumentException(s"Windows launcher provided an invalid argument $index of $count")
          encoded.drop(EncodedArgSentinel.length)
        }

  /** The user's launch directory. Windows launchers may temporarily enter the
    * installation directory so Java can open jars through a legacy argv path. */
  def workingDirectory(environment: String => Option[String] = ProcessEnvironment.get): Path =
    environment(WorkingDirectory)
      .map(value => Paths.get(value).nn.toAbsolutePath.nn.normalize.nn)
      .getOrElse(Paths.get("").nn.toAbsolutePath.nn.normalize)
