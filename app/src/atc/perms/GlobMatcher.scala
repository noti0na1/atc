package atc.perms

import java.util.Locale
import scala.util.matching.Regex
import scala.util.Try

/** Simple glob matching for command lines and host names: `*` matches any
  * sequence of characters, everything else is literal. Adapted from TACIT. */
object GlobMatcher:
  private val Windows = java.io.File.separatorChar == '\\'

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
    val command = normalizeCommand(commandLine.trim)
    val p = normalizeCommand(pattern.trim)
    if p.isEmpty then false
    else if p.contains('*') then matches(command, p)
    else command == p || command.startsWith(p + " ")

  /** Win32 command names and extensions are case-insensitive. Treat the
    * standard executable/script suffixes as spelling details too, so a deny on
    * `git push*` cannot be bypassed as `GIT.EXE push`. Explicit paths stay
    * explicit and must still be granted as such. */
  private def normalizeCommand(value: String): String =
    if !Windows then value
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
      val normalized =
        if plainName then command.replaceFirst("(?i)\\.(?:exe|com|cmd|bat)$", "")
        else command
      normalized + suffix

  /** Normalize a concrete host or host pattern without resolving ordinary DNS
    * names. Exact numeric literals are canonicalized; globs retain their shape.
    * This is applied to both policy patterns and request hosts, so an exact
    * `::1` rule also covers `[0:0:0:0:0:0:0:1]`. */
  def normalizeHost(value: String): String =
    val normalized = value.trim.stripSuffix(".").toLowerCase(Locale.ROOT)
    if normalized.contains('*') then normalized
    else
      val (bare, bracketed) =
        if normalized.startsWith("[") && normalized.endsWith("]") then
          (normalized.substring(1, normalized.length - 1), true)
        else (normalized, false)
      literalIpAddress(bare)
        .orElse(if bracketed || looksLikeIpv6(bare) then ipv6Literal(bare) else None)
        .getOrElse(bare)

  private def looksLikeIpv6(value: String): Boolean =
    value.contains(':') && value.forall { char =>
      char == ':' || char == '.' || (char >= '0' && char <= '9') || (char >= 'a' && char <= 'f')
    }

  private def ipv6Literal(value: String): Option[String] =
    try
      java.net.InetAddress.getByName(value) match
        case address: java.net.Inet4Address => Some(address.getHostAddress.nn)
        case address: java.net.Inet6Address => Some(address.getHostAddress.nn)
        case _ => None
    catch case _: java.net.UnknownHostException => None

  /** Convert a numeric IPv4 literal with one to four decimal parts into
    * canonical dotted-quad form without a DNS lookup. */
  private[atc] def literalIpAddress(value: String): Option[String] =
    val parts = value.split("\\.", -1).toList

    def partValue(part: String): Option[Long] =
      if part.nonEmpty && part.forall(char => char >= '0' && char <= '9') then
        try Some(java.lang.Long.parseLong(part, 10))
        catch case _: NumberFormatException => None
      else None

    val parsed = parts.foldRight(Option(List.empty[Long])) { (part, result) =>
      for
        number <- partValue(part)
        tail <- result
      yield number :: tail
    }

    if parts.isEmpty || parts.lengthIs > 4 then None
    else
      for
        values <- parsed
        lastMax = 1L << (8 * (5 - values.length))
        if values.init.forall(_ <= 255) && values.last < lastMax
        address = values.init.zipWithIndex.foldLeft(values.last) { case (current, (part, index)) =>
          current | (part << (8 * (3 - index)))
        }
        bytes = Array.tabulate(4)(index => ((address >> (8 * (3 - index))) & 0xff).toByte)
        canonical <- Try(java.net.InetAddress.getByAddress(bytes).nn.getHostAddress.nn).toOption
      yield canonical

  /** Host matching: normalized plain glob on the host name (case-insensitive). */
  def matchesHost(host: String, pattern: String): Boolean =
    matches(normalizeHost(host), normalizeHost(pattern))
