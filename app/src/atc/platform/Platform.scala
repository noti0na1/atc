package atc.platform

import java.io.{File, IOException}
import java.util.Locale
import java.util.regex.Pattern

/** Process-wide operating-system traits. Platform checks belong here so the rest of the
  * application depends on the behavior it needs instead of inspecting JVM path separators. */
private[atc] object Platform:
  val isWindows: Boolean = File.separatorChar == '\\'
  val isMac: Boolean = System.getProperty("os.name", "").nn.toLowerCase(Locale.ROOT).startsWith("mac")
  val fileSeparator: Char = File.separatorChar
  val pathListSeparator: String = File.pathSeparator
  /** Whether the usual file systems ignore case in names: NTFS, and APFS as macOS formats it. A rule
    * on `.atc` must also cover a new `.ATC`, which is the same directory there. */
  val caseInsensitivePaths: Boolean = isWindows || isMac
  val pathRegexFlags: Int = if caseInsensitivePaths then Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE else 0
  /** The file that reads as empty, for a child process's input. */
  val nullDevice: File = File(if isWindows then "NUL" else "/dev/null")

  /** Text shown to the model for the operating system it is running on. */
  def description: String = s"${System.getProperty("os.name")} ${System.getProperty("os.arch")}"

  /** Open `url` in the user's browser; `false` when no opener could be started. */
  def openBrowser(url: String): Boolean =
    val command =
      if isMac then List("open", url)
      else if isWindows then List("rundll32", "url.dll,FileProtocolHandler", url)
      else List("xdg-open", url)
    try
      ProcessBuilder(command*).redirectInput(ProcessBuilder.Redirect.from(nullDevice))
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
      true
    catch case _: IOException => false

  /** Compare filesystem names using the host filesystem's case semantics. */
  def samePathName(left: String, right: String): Boolean =
    if caseInsensitivePaths then left.equalsIgnoreCase(right) else left == right
