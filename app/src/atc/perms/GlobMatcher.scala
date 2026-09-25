package atc.perms

import atc.platform.Platform

import java.net.{InetAddress, UnknownHostException}
import java.util.Locale
import scala.util.Try

/** Glob matching for command lines and host names: `*` matches any sequence of characters,
  * everything else is literal. Adapted from TACIT. */
object GlobMatcher:
  private val WindowsExecutableSuffixes = List(".exe", ".com", ".cmd", ".bat")

  /** Full-string glob matching without regex compilation. Like Java regex `.`, a `*` does not
    * match a line terminator; policy inputs do not normally contain one. */
  def matches(value: String, pattern: String): Boolean =
    var valueIndex = 0
    var patternIndex = 0
    var starIndex = -1
    var retryValueIndex = -1

    while valueIndex < value.length do
      if patternIndex < pattern.length && pattern.charAt(patternIndex) == '*' then
        starIndex = patternIndex
        patternIndex += 1
        retryValueIndex = valueIndex
      else if patternIndex < pattern.length && pattern.charAt(patternIndex) == value.charAt(valueIndex) then
        patternIndex += 1
        valueIndex += 1
      else if starIndex >= 0 && !regexLineTerminator(value.charAt(retryValueIndex)) then
        retryValueIndex += 1
        valueIndex = retryValueIndex
        patternIndex = starIndex + 1
      else return false

    while patternIndex < pattern.length && pattern.charAt(patternIndex) == '*' do patternIndex += 1
    patternIndex == pattern.length

  private inline def regexLineTerminator(char: Char): Boolean =
    char == '\n' || char == '\r' || char == '\u0085' || char == '\u2028' || char == '\u2029'

  /** Command-line matching. A pattern containing `*` matches as a glob. One without `*` matches
    * the command line it equals and any command line it is a word prefix of: `"ls"` permits
    * `ls -la` and `"git status"` permits `git status --short`, while `"git diff*"` also permits
    * `git difftool`. */
  def matchesCommand(commandLine: String, pattern: String): Boolean =
    val command = normalizeCommand(commandLine.trim)
    val p = normalizeCommand(pattern.trim)
    if p.isEmpty then false
    else if p.contains('*') then matches(command, p)
    else command == p || command.startsWith(p + " ")

  /** Win32 command names and extensions are case-insensitive. The standard executable and script
    * suffixes are spelling details too, so a deny on `git push*` cannot be bypassed as
    * `GIT.EXE push`. Explicit paths stay explicit and must still be granted as such. */
  private def normalizeCommand(value: String): String =
    if !Platform.isWindows then value
    else
      val boundary =
        if value.startsWith("\"") then
          value.indexOf('"', 1) match
            case -1 => value.length
            case n => n + 1
        else
          value.indexOf(' ') match
            case -1 => value.length
            case n => n
      val command = value.substring(0, boundary).toLowerCase(Locale.ROOT).replace('\\', '/')
      val suffix = value.substring(boundary)
      val plainName = !command.exists(c => c == '/' || c == '\\' || c == '*' || c == '?')
      val executableSuffix = if plainName then WindowsExecutableSuffixes.find(command.endsWith) else None
      executableSuffix.fold(command)(extension => command.dropRight(extension.length)) + suffix

  /** Normalize a concrete host or host pattern without resolving DNS names. Exact numeric literals
    * are canonicalized; globs keep their shape. Both policy patterns and request hosts pass
    * through this, so an exact `::1` rule also covers `[0:0:0:0:0:0:0:1]`. */
  def normalizeHost(value: String): String =
    val normalized = value.trim.stripSuffix(".").toLowerCase(Locale.ROOT)
    if normalized.contains('*') then normalized
    else
      val (bare, bracketed) =
        if normalized.startsWith("[") && normalized.endsWith("]") then
          (normalized.substring(1, normalized.length - 1), true)
        else (normalized, false)
      literalIpAddress(bare)
        .orElse(if bare.contains(':') && (bracketed || looksLikeIpv6(bare)) then ipv6Literal(bare) else None)
        .getOrElse(bare)

  private def looksLikeIpv6(value: String): Boolean =
    value.contains(':') && value.forall: char =>
      char == ':' || char == '.' ||
        (char >= '0' && char <= '9') ||
        (char >= 'a' && char <= 'f')

  /** The canonical form of an IPv6 literal. In brackets and with a `:`, `getByName` parses the
    * value as a literal or rejects it; it never falls back to a DNS lookup. */
  private def ipv6Literal(value: String): Option[String] =
    try Some(InetAddress.getByName(s"[$value]").nn.getHostAddress.nn)
    catch case _: UnknownHostException => None

  /** Convert a numeric IPv4 literal with one to four decimal parts into canonical dotted-quad form
    * without a DNS lookup. */
  private def literalIpAddress(value: String): Option[String] =
    val parts = value.split("\\.", -1).toList
    // Decimal digits only (`toLongOption` would also take a sign), and no overflow.
    val numbers = parts.filter(part => part.nonEmpty && part.forall(char => char >= '0' && char <= '9'))
      .flatMap(_.toLongOption)

    if parts.isEmpty || parts.lengthIs > 4 then None
    else
      for
        values <- Option.when(numbers.lengthIs == parts.length)(numbers)
        lastMax = 1L << (8 * (5 - values.length))
        if values.init.forall(_ <= 255) && values.last < lastMax
        address = values.init.zipWithIndex.foldLeft(values.last) { case (current, (part, index)) =>
          current | (part << (8 * (3 - index)))
        }
        bytes = Array.tabulate(4)(index => ((address >> (8 * (3 - index))) & 0xff).toByte)
        canonical <- Try(InetAddress.getByAddress(bytes).nn.getHostAddress.nn).toOption
      yield canonical

  /** Host matching: normalized plain glob on the host name (case-insensitive). */
  def matchesHost(host: String, pattern: String): Boolean =
    matches(normalizeHost(host), normalizeHost(pattern))
