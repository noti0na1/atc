package atc.platform

import java.util.regex.Pattern
import scala.util.matching.Regex

/** Slash-based path globs shared by configuration rules and host searches. */
private[atc] object PathGlob:
  def pattern(glob: String): Pattern = Pattern.compile(source(glob), Platform.pathRegexFlags)

  def regex(glob: String): Regex =
    ((if Platform.isWindows then "(?i)" else "") + source(glob)).r

  private def source(glob: String): String =
    val result = StringBuilder("^")
    var index = 0
    var inClass = false
    var inBraces = false
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
            inBraces = true
            result.append("(?:")
          case '}' if inBraces =>
            inBraces = false
            result.append(')')
          case ',' if inBraces => result.append('|')
          case other => result.append(Pattern.quote(other.toString))
        index += 1
    result.append('$').toString
