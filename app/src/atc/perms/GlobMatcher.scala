package atc.perms

import scala.util.matching.Regex

/** Simple glob matching for command lines and host names: `*` matches any
  * sequence of characters, everything else is literal. Adapted from TACIT. */
object GlobMatcher:
  /** `*` becomes `.*`; every other segment is quoted, so it stays literal. */
  private def compile(pattern: String): Regex =
    Regex(pattern.split("\\*", -1).map(Regex.quote).mkString(".*"))

  def matches(value: String, pattern: String): Boolean =
    compile(pattern).matches(value)

  /** Command-line matching. A pattern matches the command line if it matches
    * as a glob, or — when it contains no `*` — if it equals the command line
    * or is a word-prefix of it: `"ls"` permits `ls -la`, `"git status"`
    * permits `git status --short`, `"git diff*"` also permits `git difftool`. */
  def matchesCommand(commandLine: String, pattern: String): Boolean =
    val p = pattern.trim
    if p.isEmpty then false
    else if p.contains('*') then matches(commandLine, p)
    else commandLine == p || commandLine.startsWith(p + " ")

  /** Host matching: plain glob on the host name (case-insensitive). */
  def matchesHost(host: String, pattern: String): Boolean =
    matches(host.toLowerCase, pattern.trim.toLowerCase)
