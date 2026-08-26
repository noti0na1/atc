package atc.platform

import java.nio.file.{Files, Path, Paths}
import java.util.Locale

/** Host-path parsing, display and validation. Agent-facing paths always use
  * forward slashes; input is converted to native separators only where the
  * current filesystem requires it. */
private[atc] object PlatformPath:
  val userHome: Path = Paths.get(scala.util.Properties.userHome).nn

  /** Stable path text exposed to agent code and shown in prompts. */
  def portable(path: Path): String =
    val native = path.toString
    if Platform.isWindows then native.replace('\\', '/') else native

  /** Convert slash-based path text to the current filesystem's spelling. */
  def native(value: String): String =
    if Platform.isWindows then value.replace('/', Platform.fileSeparator) else value

  /** Normalize path text for slash-based matching without changing literal
    * backslashes on filesystems where they are ordinary filename characters. */
  def slashSeparated(value: String): String =
    if Platform.isWindows then value.replace('\\', '/') else value

  def hasSeparator(value: String): Boolean = value.exists(isSeparator)

  def isRoot(value: String): Boolean =
    value == "/" ||
      (Platform.isWindows && (value == "\\" || value.matches("(?i)^[a-z]:[\\\\/]$")))

  /** Remove path separators from the end without turning a root into an empty
    * path. Call [[isRoot]] first when roots have special meaning. */
  def stripTrailingSeparators(value: String): String =
    value.reverse.dropWhile(isSeparator).reverse

  private def isSeparator(char: Char): Boolean =
    char == '/' || (Platform.isWindows && char == '\\')

  /** Resolve `~` and `~/...` using the process home directory. Both slash
    * spellings are accepted after `~`, on every platform. */
  def expandHome(value: String): String =
    if value == "~" then userHome.toString
    else if value.startsWith("~/") || value.startsWith("~\\") then
      val relative = value.drop(2).map(c => if c == '/' || c == '\\' then Platform.fileSeparator else c)
      userHome.resolve(relative).toString
    else value

  /** Why a path is unsafe on the current platform, if it is unsafe. */
  def validationError(value: String): Option[String] =
    if Platform.isWindows then windowsValidationError(value) else None

  /** Drive-relative paths must be rejected even when their suffix contains a
    * glob and therefore cannot undergo full literal-path validation. */
  def driveRelativeValidationError(value: String): Option[String] =
    Option.when(Platform.isWindows && isDriveRelative(value))(DriveRelativeError)

  /** Why a path is unsafe under Win32 name resolution. Kept string-based so
    * callers can reject device namespaces before java.nio touches them. */
  def windowsValidationError(value: String): Option[String] =
    val normalized = value.replace('/', '\\')
    val lower = normalized.toLowerCase(Locale.ROOT)
    if lower.startsWith("\\\\.\\") || lower.startsWith("\\\\?\\") || lower.startsWith("\\??\\") then
      Some("Win32 device namespaces are not allowed")
    else if isDriveRelative(normalized) then Some(DriveRelativeError)
    else
      normalized.split("\\\\+", -1).iterator
        .filterNot(component =>
          component.isEmpty || component == "." || component == ".." || component.matches("(?i)[a-z]:")
        )
        .flatMap { component =>
          if component.contains(':') then Some("alternate data streams are not allowed")
          else if component.endsWith(".") || component.endsWith(" ") then
            Some("path components ending in a dot or space are not allowed")
          else
            val stem = component.takeWhile(_ != '.').reverse.dropWhile(c => c == '.' || c == ' ').reverse
              .toUpperCase(Locale.ROOT)
            val reserved =
              Set("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$", "CLOCK$").contains(stem) ||
                stem.matches("COM[1-9¹²³]") || stem.matches("LPT[1-9¹²³]")
            Option.when(reserved)(s"'$component' is a reserved Windows device name")
        }
        .nextOption()

  private val DriveRelativeError = "drive-relative paths such as 'C:work' are ambiguous; use 'C:/work'"

  private def isDriveRelative(value: String): Boolean =
    value.replace('/', '\\').matches("(?i)^[a-z]:(?:$|[^\\\\].*)")

  /** Convert a path to absolute normalized form and resolve symlinks as far as
    * possible. Dangling links are resolved too because writing through one
    * creates its target, which is the path the policy must evaluate. */
  def canonical(path: Path): Path =
    realPathOfNearestAncestor(path.toAbsolutePath.normalize, MaxLinkDepth)

  /** Maximum symlink-chain depth, analogous to the kernel's ELOOP threshold. */
  private val MaxLinkDepth = 40

  private def realPathOfNearestAncestor(path: Path, depth: Int): Path =
    if depth <= 0 then path // a symlink loop: judge the path literally
    else if Files.isSymbolicLink(path) then // NOFOLLOW: true for dangling links too
      try
        val target = Files.readSymbolicLink(path).nn
        val resolved = if target.isAbsolute then target else path.getParent.resolve(target).nn
        realPathOfNearestAncestor(resolved.toAbsolutePath.normalize, depth - 1)
      catch case _: java.io.IOException => path
    else if Files.exists(path) then
      try path.toRealPath()
      catch case _: java.io.IOException => path
    else
      val parent = path.getParent
      val name = path.getFileName
      if parent != null && name != null then realPathOfNearestAncestor(parent, depth).resolve(name) else path
