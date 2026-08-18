package atc.sandbox

import scala.util.matching.Regex

/** A violation found by the code validator. */
case class Violation(ruleId: String, description: String, lineNumber: Int, snippet: String)

/** Static, regex-based validation of agent code before it reaches the REPL.
  *
  * This is defence in depth on top of Scala's safe mode and the class-loader
  * isolation: it rejects the obvious ways of reaching around the capability
  * API (java.io, reflection, class loaders, `unsafe*`, ...). Adapted from
  * TACIT (Apache-2.0).
  */
object CodeValidator:

  private case class Forbidden(id: String, regex: Regex, description: String)

  private val forbidden: List[Forbidden] = List(
    // File IO bypass
    Forbidden("file-io-java", raw"java\.io\b".r, "Direct java.io access is forbidden; use the file API"),
    Forbidden("file-io-nio", raw"java\.nio\b".r, "Direct java.nio access is forbidden; use the file API"),
    Forbidden("file-io-scala", raw"scala\.io\b".r, "Direct scala.io access is forbidden; use the file API"),
    // Process bypass
    Forbidden("proc-builder", raw"ProcessBuilder".r, "ProcessBuilder is forbidden; use exec"),
    Forbidden("proc-runtime", raw"Runtime\.getRuntime".r, "Runtime.getRuntime is forbidden; use exec"),
    Forbidden("proc-scala", raw"\bsys\.process\b".r, "scala.sys.process is forbidden; use exec"),
    // Network bypass
    Forbidden("net-java", raw"java\.net\b".r, "Direct java.net access is forbidden; use the http API"),
    Forbidden("net-javax", raw"javax\.net\b".r, "Direct javax.net access is forbidden; use the http API"),
    Forbidden("net-http-client", raw"HttpClient".r, "HttpClient is forbidden; use the http API"),
    Forbidden("net-http-conn", raw"HttpURLConnection".r, "HttpURLConnection is forbidden; use the http API"),
    // Cast escape
    Forbidden("cast-escape", raw"\.asInstanceOf\s*\[".r, ".asInstanceOf is forbidden"),
    // Capture-checking escape hatches
    Forbidden("cc-unsafe-caps", raw"caps\.unsafe".r, "caps.unsafe explicitly escapes capture checking"),
    Forbidden("cc-unsafe-pure", raw"unsafeAssumePure".r, "unsafeAssumePure explicitly escapes capture checking"),
    Forbidden("cc-unsafe-assume", raw"unsafeAssume\w*".r, "caps.unsafe.* explicitly escapes capture checking"),
    Forbidden("cc-assume-safe", raw"assumeSafe".r, "@assumeSafe may only be used by the library"),
    // Sandbox internals
    Forbidden(
      "atc-host",
      raw"atc\.(host|agent|sandbox|perms|config|llm|ui)\b".r,
      "application internals are not accessible"
    ),
    Forbidden(
      "atc-runtime",
      raw"\bInterface\.(current|takeRootIO|install)\b".r,
      "Interface.current/takeRootIO/install are internal to the sandbox"
    ),
    // Reflection
    Forbidden("reflect-method", raw"getDeclaredMethod".r, "Reflective access is forbidden"),
    Forbidden("reflect-field", raw"getDeclaredField".r, "Reflective access is forbidden"),
    Forbidden("reflect-ctor", raw"getDeclaredConstructor".r, "Reflective access is forbidden"),
    Forbidden("reflect-accessible", raw"setAccessible".r, "Reflective access is forbidden"),
    Forbidden("reflect-java", raw"java\.lang\.reflect\b".r, "java.lang.reflect is forbidden"),
    Forbidden("reflect-scala", raw"scala\.reflect\.runtime".r, "scala.reflect.runtime is forbidden"),
    Forbidden("reflect-forname", raw"Class\.forName".r, "Class.forName is forbidden"),
    Forbidden("reflect-getclass", raw"\.getClass\b".r, "getClass is forbidden"),
    // JVM internals
    Forbidden("jvm-jdk-internal", raw"jdk\.internal\b".r, "jdk.internal access is forbidden"),
    Forbidden("jvm-sun", raw"\bsun\.\w+".r, "sun.* access is forbidden"),
    Forbidden("jvm-com-sun", raw"com\.sun\.\w+".r, "com.sun.* access is forbidden"),
    Forbidden("jvm-invoke", raw"java\.lang\.invoke\b".r, "java.lang.invoke is forbidden"),
    // Output bypass
    Forbidden("io-system-out", raw"System\.out\b".r, "System.out is forbidden; use println"),
    Forbidden("io-system-err", raw"System\.err\b".r, "System.err is forbidden; use println"),
    Forbidden("io-system-in", raw"System\.in\b".r, "System.in is forbidden"),
    Forbidden("io-console", raw"\bConsole\b".r, "scala.Console is forbidden; use println"),
    Forbidden("io-predef-print", raw"Predef\.print".r, "Predef.println/print is forbidden; use println"),
    // System control
    Forbidden("sys-exit", raw"System\.exit".r, "System.exit is forbidden"),
    Forbidden("sys-setprop", raw"System\.setProperty".r, "System.setProperty is forbidden"),
    Forbidden("sys-getenv", raw"System\.getenv".r, "System.getenv is forbidden"),
    Forbidden("sys-getprop", raw"System\.getProperty".r, "System.getProperty is forbidden"),
    Forbidden("sys-load", raw"System\.load\w*".r, "System.load is forbidden"),
    Forbidden(
      "sys-scala",
      raw"\bsys\.(exit|env|props|runtime|allThreads|addShutdownHook)\b".r,
      "scala.sys.* (exit/env/props/runtime/...) is forbidden"
    ),
    Forbidden("sys-thread", raw"\bnew\s+Thread\b".r, "Creating threads is forbidden"),
    Forbidden("sys-thread2", raw"\bThread\s*\(".r, "Creating threads is forbidden"),
    // Catching fatal throwables. Fatal throwables (StackOverflowError, OutOfMemoryError,
    // the sandbox's ThreadDeath stop signal, ...) must propagate and abort the evaluation;
    // agent code must not catch them, or a callback that throws conditionally on a secret
    // becomes a per-bit oracle and a loop could swallow a timeout/interrupt. A typed catch
    // of Throwable/Error/a fatal type is rejected here; a bare catch-all is caught by the
    // cross-line `catch-all` rule below. A typed catch of a non-fatal type (`case _: Exception`,
    // a RuntimeException subtype, ...) stays allowed. (NonFatal(e) is not usable under safe mode.)
    Forbidden(
      "catch-fatal",
      raw"\bcase\s+(?!class\b|object\b)[^=]*:\s*(?:[\w.]+\.)?(?:Throwable|ControlThrowable|Error|VirtualMachineError|StackOverflowError|OutOfMemoryError|Any|AnyRef)\b".r,
      "Catching Throwable/Error/a fatal error is forbidden; catch a specific non-fatal type instead, e.g. case _: Exception (or a RuntimeException subtype)"
    ),
    Forbidden(
      "throwable-interrupted",
      raw"\bInterruptedException\b".r,
      "InterruptedException may not be used in agent code (throwing or catching it can defeat the interrupt/timeout)"
    ),
    Forbidden(
      "throwable-threaddeath",
      raw"\bThreadDeath\b".r,
      "ThreadDeath is the sandbox stop signal and may not be used in agent code"
    ),
    // Directives
    Forbidden("directive-using", raw"//>\s*using".r, "//> using directives are forbidden"),
    Forbidden("directive-import", """import\s+\$""".r, "import $ directives are forbidden"),
    // Class loading / compiler
    Forbidden("classloader", raw"ClassLoader".r, "ClassLoader access is forbidden"),
    Forbidden("dotty-tools", raw"dotty\.tools\b".r, "dotty.tools access is forbidden"),
    Forbidden("scala-tools", raw"scala\.tools\b".r, "scala.tools access is forbidden"),
    Forbidden("scala-quoted", raw"scala\.quoted\b".r, "scala.quoted access is forbidden"),
    Forbidden(
      "language-import",
      raw"import\s+(scala\.)?language\.experimental\b".r,
      "language imports are managed by the sandbox"
    ),
  )

  private inline def isIdentChar(c: Char): Boolean = Character.isLetterOrDigit(c) || c == '_'
  private inline def isIdentStart(c: Char): Boolean = Character.isLetter(c) || c == '_'

  private def charLiteralLength(code: String, i: Int, len: Int): Int =
    if code.charAt(i) != '\'' then 0
    else if i + 1 < len && code.charAt(i + 1) == '\\' then
      if i + 2 < len && code.charAt(i + 2) == 'u' then
        if i + 7 < len && code.charAt(i + 7) == '\'' then 8 else 0
      else if i + 3 < len && code.charAt(i + 3) == '\'' then 4
      else 0
    else if i + 2 < len && code.charAt(i + 2) == '\'' then 3
    else 0

  /** Blank string literals and comments (keeping `${...}` interpolations, which
    * are code), preserving newlines and character offsets. */
  def stripLiteralsAndComments(code: String): String = strip(code, blankComments = true)

  /** Blank string literals only; comments are kept (directives live there). */
  def stripStringLiteralsOnly(code: String): String = strip(code, blankComments = false)

  private def strip(code: String, blankComments: Boolean): String =
    val sb = StringBuilder(code.length)
    val len = code.length
    final class Frame(val isString: Boolean, val triple: Boolean, val interp: Boolean, val fromInterp: Boolean):
      var brace: Int = 0
    val stack = scala.collection.mutable.Stack[Frame](Frame(false, false, false, false))

    inline def emit(c: Char): Unit = sb.append(c)
    inline def blank(c: Char): Unit = sb.append(if c == '\n' then '\n' else ' ')

    var i = 0
    while i < len do
      val f = stack.top
      val c = code.charAt(i)
      if f.isString then
        val isClose =
          if f.triple then c == '"' && i + 2 < len && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"'
          else c == '"'
        if !f.triple && c == '\\' && i + 1 < len then
          blank(c); blank(code.charAt(i + 1)); i += 2
        else if f.interp && c == '$' && i + 1 < len && code.charAt(i + 1) == '{' then
          emit('$'); emit('{'); i += 2
          stack.push(Frame(false, false, false, true))
        else if f.interp && c == '$' && i + 1 < len && isIdentStart(code.charAt(i + 1)) then
          emit('$'); i += 1
          while i < len && isIdentChar(code.charAt(i)) do { emit(code.charAt(i)); i += 1 }
        else if isClose then
          if f.triple then { blank('"'); blank('"'); blank('"'); i += 3 }
          else { blank('"'); i += 1 }
          stack.pop()
        else
          blank(c); i += 1
      else
        val charLit = if c == '\'' then charLiteralLength(code, i, len) else 0
        if charLit > 0 then
          var k = 0
          while k < charLit do { blank(code.charAt(i + k)); k += 1 }
          i += charLit
        else if c == '"' then
          val triple = i + 2 < len && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"'
          val interp = i > 0 && isIdentChar(code.charAt(i - 1))
          if triple then { blank('"'); blank('"'); blank('"'); i += 3 }
          else { blank('"'); i += 1 }
          stack.push(Frame(true, triple, interp, false))
        else if c == '/' && i + 1 < len && code.charAt(i + 1) == '/' then
          while i < len && code.charAt(i) != '\n' do
            if blankComments then blank(code.charAt(i)) else emit(code.charAt(i))
            i += 1
        else if c == '/' && i + 1 < len && code.charAt(i + 1) == '*' then
          if blankComments then { blank('/'); blank('*') }
          else { emit('/'); emit('*') }
          i += 2
          var closed = false
          while i < len && !closed do
            if i + 1 < len && code.charAt(i) == '*' && code.charAt(i + 1) == '/' then
              if blankComments then { blank('*'); blank('/') }
              else { emit('*'); emit('/') }
              i += 2; closed = true
            else
              if blankComments then blank(code.charAt(i)) else emit(code.charAt(i))
              i += 1
        else if c == '{' then
          emit('{'); f.brace += 1; i += 1
        else if c == '}' then
          if f.fromInterp && f.brace == 0 then { emit('}'); i += 1; stack.pop() }
          else { emit('}'); if f.brace > 0 then f.brace -= 1; i += 1 }
        else
          emit(c); i += 1
    sb.toString

  /** Patterns matched against the whole stripped source (spanning line breaks),
    * for constructs the per-line scan cannot see — e.g. a `catch` and its `case`
    * on separate lines. */
  private val crossLineForbidden: List[Forbidden] = List(
    // A bare catch-all (`catch case _ =>`, `catch case e =>`, `catch { ... case _ => }`)
    // also catches fatal errors and the ThreadDeath stop signal. Both rules require the
    // `catch` keyword, so ordinary `match` arms and `.recover { case _ => }` are untouched;
    // a bare `_`/lower-case binder is what marks a catch-all — `catch case _: Exception` has a
    // type and an extractor like `catch case Foo(e)` starts upper-case, so neither is flagged.
    // Braceless form — the catch-all as the first (or only) arm:
    Forbidden(
      "catch-all",
      raw"\bcatch\b\s*case\s+(?:_|[a-z]\w*)\s*(?:if\b|=>)".r,
      "A bare catch-all also catches fatal errors and the sandbox stop signal; catch a specific non-fatal type instead, e.g. case _: Exception"
    ),
    // Braced form — a bare catch-all arm anywhere inside `catch { ... }` (e.g. a `case _ =>`
    // fallback after typed arms); scans the block allowing one level of nested braces.
    Forbidden(
      "catch-all",
      raw"\bcatch\b\s*\{(?:[^{}]|\{[^{}]*\})*?\bcase\s+(?:_|[a-z]\w*)\s*(?:if\b|=>)".r,
      "A bare catch-all also catches fatal errors and the sandbox stop signal; catch a specific non-fatal type instead, e.g. case _: Exception"
    ),
  )

  private val stringStrippedPatterns: Set[String] = Set("directive-using", "directive-import", "language-import")

  private val dotWhitespace = raw"\s*\.\s*".r
  private def squeezeDots(line: String): String = dotWhitespace.replaceAllIn(line, ".")

  /** Join physical lines connected by member-access dots so `java.\n io` is
    * seen as `java.io`. Returns `(line, startIndex)`. */
  private def logicalLines(strippedLines: Array[String]): List[(String, Int)] =
    val result = scala.collection.mutable.ListBuffer[(String, Int)]()
    var i = 0
    while i < strippedLines.length do
      val start = i
      val sb = StringBuilder(strippedLines(i))
      while i + 1 < strippedLines.length &&
        (sb.toString.trim.endsWith(".") || strippedLines(i + 1).trim.startsWith("."))
      do
        sb.append(' ').append(strippedLines(i + 1))
        i += 1
      result += ((squeezeDots(sb.toString), start))
      i += 1
    result.toList

  def validate(code: String): List[Violation] =
    val stripped = stripLiteralsAndComments(code)
    val originalLines = code.linesIterator.toArray
    val strippedLines = stripped.linesIterator.toArray
    val stringStrippedLines = stripStringLiteralsOnly(code).linesIterator.toArray
    val logical = logicalLines(strippedLines)
    val perLine =
      for
        pattern <- forbidden
        lines =
          if stringStrippedPatterns.contains(pattern.id)
          then stringStrippedLines.zipWithIndex.toList
          else logical
        (line, idx) <- lines
        if pattern.regex.findFirstIn(line).isDefined
      yield Violation(pattern.id, pattern.description, idx + 1, originalLines.lift(idx).getOrElse(line).trim)
    val crossLine =
      for
        pattern <- crossLineForbidden
        m <- pattern.regex.findAllMatchIn(stripped).toList
        idx = stripped.substring(0, m.start).count(_ == '\n')
      yield Violation(pattern.id, pattern.description, idx + 1, originalLines.lift(idx).getOrElse("").trim)
    perLine ++ crossLine

  def formatErrors(violations: List[Violation]): String =
    val header = s"Code validation failed (${violations.size} violation${if violations.size > 1 then "s" else ""}):"
    val details = violations.map(v => s"  [${v.ruleId}] Line ${v.lineNumber}: ${v.description}\n    > ${v.snippet}")
    (header :: details).mkString("\n")
