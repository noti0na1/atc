package atc.platform

import java.io.File
import java.util.regex.Pattern

/** Process-wide operating-system traits. Platform checks belong here so the
  * rest of the application can depend on the behavior it needs instead of
  * inspecting JVM path separators itself. */
private[atc] object Platform:
  val isWindows: Boolean = File.separatorChar == '\\'
  val fileSeparator: Char = File.separatorChar
  val pathListSeparator: String = File.pathSeparator
  val pathRegexFlags: Int = if isWindows then Pattern.CASE_INSENSITIVE else 0

  /** Text shown to the model for the operating system it is running on. */
  def description: String = s"${System.getProperty("os.name")} ${System.getProperty("os.arch")}"

  /** Compare filesystem names using the host filesystem's case semantics. */
  def samePathName(left: String, right: String): Boolean =
    if isWindows then left.equalsIgnoreCase(right) else left == right
