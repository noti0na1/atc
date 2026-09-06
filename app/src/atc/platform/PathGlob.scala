package atc.platform

import java.util.regex.Pattern
import scala.util.matching.Regex

/** Slash-based path globs shared by configuration rules and host searches. */
private[atc] object PathGlob:
  def pattern(glob: String): Pattern = checked(glob)(Pattern.compile(source(glob), Platform.pathRegexFlags))

  def regex(glob: String): Regex = checked(glob)(((if Platform.isWindows then "(?i)" else "") + source(glob)).r)

  /** A malformed class or brace group surfaces as an error about the glob, not the regex. */
  private def checked[A](glob: String)(compile: => A): A =
    try compile
    catch
      case e: java.util.regex.PatternSyntaxException =>
        throw IllegalArgumentException(s"bad glob '$glob': ${e.getDescription}")

  private def source(glob: String): String =
    val result = StringBuilder("^")
    var index = 0
    var inClass = false
    var braces = 0
    while index < glob.length do
      val char = glob.charAt(index)
      if inClass then
        if char == ']' then inClass = false
        result.append(char)
        index += 1
      else if glob.startsWith("**/", index) then
        result.append("(?:.*/)?")
        index += 3
      else if glob.startsWith("**", index) then
        result.append(".*")
        index += 2
      else
        char match
          case '*' => result.append("[^/]*")
          case '?' => result.append("[^/]")
          case '[' =>
            inClass = true
            result.append('[')
            if glob.startsWith("[!", index) then
              result.append('^')
              index += 1
          case '{' =>
            braces += 1
            result.append("(?:")
          case '}' if braces > 0 =>
            braces -= 1
            result.append(')')
          case ',' if braces > 0 => result.append('|')
          case other => result.append(Pattern.quote(other.toString))
        index += 1
    if inClass || braces > 0 then
      throw IllegalArgumentException(s"bad glob '$glob': unclosed ${if inClass then "[" else "{"}")
    result.append('$').toString
