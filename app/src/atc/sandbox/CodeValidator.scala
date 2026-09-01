package atc.sandbox

import scala.util.matching.Regex

/** A violation found by the code validator. */
case class Violation(ruleId: String, description: String, lineNumber: Int, snippet: String)

/** Fast, regex-based preflight for agent code before it reaches the compiler.
  *
  * Its purpose is quick, actionable feedback for common invalid forms
  * (`java.io`, reflection, class loaders, `unsafe*`, evaluator-hostile catches,
  * ...). It is intentionally lexical: it does not parse or type-check Scala and
  * is neither complete nor a safety boundary. Scala compiler safe mode is the
  * authoritative safety check.
  *
  * Keep this implementation simple and fast. Prefer cheap linear scans and
  * high-value diagnostics; do not grow a second Scala parser or try to prove
  * that accepted code is safe here. Adapted from TACIT (Apache-2.0).
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
      raw"\b(?:atc\.lib\.)?Runtime\b".r,
      "Runtime.current/rootIO/rootUser/install/fileSystem/readOnlyFileSystem/processes/network are internal to the sandbox"
    ),
    // Reflection
    Forbidden("reflect-method", raw"getDeclaredMethod".r, "Reflective access is forbidden"),
    Forbidden("reflect-field", raw"getDeclaredField".r, "Reflective access is forbidden"),
    Forbidden("reflect-ctor", raw"getDeclaredConstructor".r, "Reflective access is forbidden"),
    Forbidden("reflect-accessible", raw"setAccessible".r, "Reflective access is forbidden"),
    Forbidden("reflect-java", raw"java\.lang\.reflect\b".r, "java.lang.reflect is forbidden"),
    Forbidden("reflect-scala", raw"scala\.reflect\.runtime".r, "scala.reflect.runtime is forbidden"),
    Forbidden("reflect-forname", raw"Class\.forName".r, "Class.forName is forbidden"),
    // No leading dot: backquoted `` x.`getClass` `` must be caught too (safe mode
    // allows getClass, which leaks host implementation class names). Strings and
    // comments are stripped before matching, so text false positives are impossible.
    Forbidden("reflect-getclass", raw"\bgetClass\b".r, "getClass is forbidden"),
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
      "sys-system-import",
      raw"\bimport\s+(?:java\.lang\.)?System\b".r,
      "Importing System or its members is forbidden; call only the permitted time/line-separator methods directly"
    ),
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
    // The fatal type may appear anywhere in the ascription, so a parenthesised
    // (`case _: (Throwable)`) or union (`case _: (RuntimeException | Throwable)`)
    // type does not slip past. The scan of the ascription stops before the arm
    // arrow `=>` and before an `if` guard, so a guard that merely mentions a
    // fatal type (`case _: Foo if Throwable.check() =>`) is not a false positive.
    Forbidden(
      "catch-fatal",
      raw"\bcase\s+(?!class\b|object\b)[^=]*:(?:(?!=>|\bif\b)[^=])*\b(?:[\w.]+\.)?(?:Throwable|ControlThrowable|Error|VirtualMachineError|StackOverflowError|OutOfMemoryError|Any|AnyRef)\b".r,
      "Catching Throwable/Error/a fatal error is forbidden; catch a specific non-fatal type instead, e.g. case _: Exception (or a RuntimeException subtype)"
    ),
    // An erased type parameter bounded by a fatal type would defeat `catch-fatal`:
    // `def g[T <: Throwable] = try ... catch case _: T` erases to a catch of the
    // bound. `Any`/`AnyRef` are left out: `[T <: AnyRef]` is a common, legitimate
    // bound, and this rule targets an explicit fatal upper bound.
    Forbidden(
      "catch-fatal-bound",
      raw"<:\s*(?:[\w.]+\.)?(?:Throwable|ControlThrowable|Error|VirtualMachineError|StackOverflowError|OutOfMemoryError|InterruptedException|ThreadDeath)\b".r,
      "A type parameter bounded by Throwable/Error/a fatal type is forbidden; `case _: T` would then catch fatal throwables"
    ),
    Forbidden(
      "throwable-interrupted",
      raw"\bInterruptedException\b".r,
      "InterruptedException may not be used in agent code (throwing or catching it can defeat the interrupt/timeout)"
    ),
    // A type alias for a fatal type would defeat `catch-fatal`: `type T = Throwable`
    // followed by `catch case _: T =>` names no forbidden type textually. The fatal
    // type must be a top-level constituent of the right-hand side (the alias target
    // itself or a `|`/`&` member); a fatal type nested inside type arguments is fine
    // (`type M = Map[String, AnyRef]` aliases Map, not AnyRef), so the RHS scan does
    // not cross a `[`.
    Forbidden(
      "catch-fatal-alias",
      raw"\btype\s+\w+(?:\[[^\]\n]*\])?\s*=\s*(?:[^\[=\n|&]*[|&]\s*)*(?:[\w.]+\.)?(?:Throwable|ControlThrowable|Error|VirtualMachineError|StackOverflowError|OutOfMemoryError|Any|AnyRef|InterruptedException|ThreadDeath)\b".r,
      "Aliasing Throwable/Error/a fatal error type is forbidden; it would defeat the ban on catching fatal throwables"
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

  private val CatchAllDescription: String =
    "A bare catch-all also catches fatal errors and the sandbox stop signal; catch a specific non-fatal type instead, e.g. case _: Exception"

  private val ImportAliasDescription: String =
    "Import aliases are forbidden when safe mode is off because they can hide restricted packages, classes, and methods from validation"

  /** Offsets of `as` / `=>` aliases inside import statements. Imports may have
    * selectors over several lines, so a per-line regex is not sufficient. */
  private def importAliasOffsets(code: String): List[Int] =
    val hits = scala.collection.mutable.ListBuffer[Int]()
    val len = code.length
    def keywordAt(i: Int, keyword: String): Boolean =
      code.regionMatches(i, keyword, 0, keyword.length) &&
        (i == 0 || !isIdentChar(code.charAt(i - 1))) &&
        (i + keyword.length >= len || !isIdentChar(code.charAt(i + keyword.length)))
    var i = 0
    while i < len do
      if keywordAt(i, "import") then
        var k = i + "import".length
        var braces = 0
        var brackets = 0
        var parens = 0
        var stop = false
        while k < len && !stop do
          code.charAt(k) match
            case '{' => braces += 1; k += 1
            case '}' => braces = math.max(0, braces - 1); k += 1
            case '[' => brackets += 1; k += 1
            case ']' => brackets = math.max(0, brackets - 1); k += 1
            case '(' => parens += 1; k += 1
            case ')' => parens = math.max(0, parens - 1); k += 1
            case '=' if k + 1 < len && code.charAt(k + 1) == '>' => hits += k; k += 2
            case c if isIdentStart(c) =>
              val start = k
              while k < len && isIdentChar(code.charAt(k)) do k += 1
              if keywordAt(start, "as") then hits += start
            case ';' if braces == 0 && brackets == 0 && parens == 0 => stop = true; k += 1
            case '\n' if braces == 0 && brackets == 0 && parens == 0 =>
              var before = k - 1
              while before >= 0 && (code.charAt(before) == ' ' || code.charAt(before) == '\t') do before -= 1
              var after = k + 1
              while after < len && (code.charAt(after) == ' ' || code.charAt(after) == '\t') do after += 1
              val continues =
                (before >= 0 && (code.charAt(before) == '.' || code.charAt(before) == ',')) ||
                  (after < len && code.charAt(after) == '.')
              if continues then k += 1 else { stop = true; k += 1 }
            case _ => k += 1
        i = k
      else i += 1
    hits.toList

  /** Quick diagnostic for a bare catch-all arm (`case _ =>`, `case e =>`,
    * `case e if ...`), which also catches fatal errors and the ThreadDeath stop
    * signal. This remains a lexical heuristic, not an exhaustive exception-flow
    * check or a substitute for compiler safety.
    *
    * One linear pass over the stripped source (strings/comments already blanked).
    * Only `case`s belonging to a `catch` are considered: `match` arms and
    * `.recover { case _ => }` are untouched. An arm is a bare catch-all when its
    * head is `_` or a lower-case binder followed by `=>` or `if` — `case _: T`
    * is typed (left to the `catch-fatal` rule) and extractors start upper-case.
    * Braceless regions end at `finally`, a depth-0 `}`, or a line indented no
    * deeper than the `catch` that does not start with `case` (a braceless nested
    * `match` with a `case _` inside such an arm is an accepted false positive;
    * braced nested matches are never flagged).
    *
    * Returns the character offsets of the offending `case` keywords. */
  private def catchAllOffsets(code: String): List[Int] =
    if !code.contains("catch") then return Nil // the common case: no catch, no scan
    val len = code.length
    val hits = scala.collection.mutable.ListBuffer[Int]()
    def skipWs(from: Int): Int =
      var k = from
      while k < len && { val c = code.charAt(k); c == ' ' || c == '\t' || c == '\n' || c == '\r' } do k += 1
      k
    def identEnd(from: Int): Int =
      var k = from
      while k < len && isIdentChar(code.charAt(k)) do k += 1
      k
    def keywordAt(i: Int, kw: String): Boolean =
      code.regionMatches(i, kw, 0, kw.length) &&
        (i == 0 || !isIdentChar(code.charAt(i - 1))) &&
        (i + kw.length >= len || !isIdentChar(code.charAt(i + kw.length)))
    /** Does the arm whose pattern starts at `i0` begin with a bare catch-all —
      * `_`, a lower-case binder, an `@`-binder over one, or any of those in
      * parentheses (`case (e) =>`, `case e @ _ =>`) — with no type ascription
      * (a `:` is left to `catch-fatal`), extractor (upper-case name) or literal? */
    def isBareCatchAll(i0: Int): Boolean =
      var k = skipWs(i0)
      var sawBinder = false
      var result = false
      var done = false
      while !done do
        if k >= len then done = true
        else
          val c = code.charAt(k)
          if c == '=' && k + 1 < len && code.charAt(k + 1) == '>' then { result = sawBinder; done = true }
          else if c == ':' then done = true // a type ascription: left to `catch-fatal`
          else if keywordAt(k, "if") then { result = sawBinder; done = true }
          else if c == '(' || c == ')' || c == '@' || c == '|' || c == ' ' || c == '\t' || c == '\n' || c == '\r'
          then k += 1
          else if c == '_' then { sawBinder = true; k += 1 }
          else if isIdentStart(c) && c.isLower then { sawBinder = true; k = identEnd(k) }
          else done = true // upper-case extractor, a `.`-path, or a literal: not a bare catch-all
      result
    /** Braced form: arms are the depth-1 `case`s inside `catch { ... }`. */
    def scanBraced(open: Int): Unit =
      var k = open + 1
      var depth = 1
      while k < len && depth > 0 do
        val c = code.charAt(k)
        if c == '{' then { depth += 1; k += 1 }
        else if c == '}' then { depth -= 1; k += 1 }
        else if depth == 1 && isIdentStart(c) then
          val end = identEnd(k)
          if keywordAt(k, "case") && isBareCatchAll(end) then hits += k
          k = end
        else k += 1
    /** Braceless form: arms at depth 0 until the region ends (see above). */
    def scanBraceless(from: Int, catchIndent: Int): Unit =
      var k = from
      var depth = 0
      var stop = false
      while k < len && !stop do
        val c = code.charAt(k)
        if c == '{' then { depth += 1; k += 1 }
        else if c == '}' then { if depth == 0 then stop = true else depth -= 1; k += 1 }
        else if c == '\n' then
          // The next line: a `case` is a further arm; a blank line is skipped.
          var tok = k + 1
          var ind = 0
          var scanning = true
          while tok < len && scanning do
            code.charAt(tok) match
              case ' ' => ind += 1; tok += 1
              case '\t' => ind += 8; tok += 1
              case '\n' => ind = 0; tok += 1
              case _ => scanning = false
          if tok >= len then stop = true
          else if keywordAt(tok, "case") then () // a further arm; keep scanning
          else if keywordAt(tok, "finally") then stop = true
          else if ind <= catchIndent then stop = true
          k += 1
        else if depth == 0 && isIdentStart(c) then
          val end = identEnd(k)
          if keywordAt(k, "case") && isBareCatchAll(end) then hits += k
          k = end
        else k += 1
    var i = 0
    while i < len do
      if isIdentStart(code.charAt(i)) then
        val end = identEnd(i)
        if keywordAt(i, "catch") then
          val j = skipWs(end)
          if j < len && code.charAt(j) == '{' then scanBraced(j)
          else
            // the indent of the catch keyword's own line
            var s = i
            while s > 0 && code.charAt(s - 1) != '\n' do s -= 1
            var ind = 0
            while s < len && (code.charAt(s) == ' ' || code.charAt(s) == '\t') do
              ind += (if code.charAt(s) == '\t' then 8 else 1); s += 1
            scanBraceless(j, ind)
        i = end
      else i += 1
    hits.toList

  private val stringStrippedPatterns: Set[String] = Set("directive-using", "directive-import", "language-import")

  // Catching a type parameter erases to a catch of its bound — Object for the
  // default/`Any`/`AnyRef` bound — so `case _: T` inside `def f[T]` swallows fatal
  // throwables exactly like `case _: Throwable` (a runnable demonstration: an
  // uncaught StackOverflowError becomes "caught"). A regex cannot tell an abstract
  // type parameter from a concrete class by name, so correlate: collect the names
  // declared as type parameters, then reject a `case` ascribed to one of them.
  private val typeParamDecl = raw"\b(?:def|class|trait|enum|given|extension|type)\b[^\[\n=]*?\[([^\]\n]*)\]".r
  private val caseAscription = raw"\bcase\b[^=:\n]*:\s*([A-Za-z_]\w*)".r
  private val TypeParamCatchDescription: String =
    "Catching a type parameter (`case _: T`) is forbidden: it erases to a catch of its bound and so catches fatal errors; catch a specific non-fatal type instead"

  /** Split a type-parameter list on its top-level commas (a comma inside a nested
    * `[...]`/`(...)`, as in `[T <: Map[K, V]]`, does not separate parameters). */
  private def splitTopLevel(inner: String): List[String] =
    val segs = scala.collection.mutable.ListBuffer[String]()
    var depth = 0
    var start = 0
    var i = 0
    while i < inner.length do
      inner.charAt(i) match
        case '[' | '(' => depth += 1
        case ']' | ')' => depth -= 1
        case ',' if depth == 0 => segs += inner.substring(start, i); start = i + 1
        case _ => ()
      i += 1
    segs += inner.substring(start)
    segs.toList

  /** The names declared as type parameters anywhere in `code` (variance and bounds
    * stripped: `+A`, `T <: X` and `F[_]` all yield their leading identifier). */
  private def typeParamNames(code: String): Set[String] =
    typeParamDecl
      .findAllMatchIn(code)
      .flatMap(m => splitTopLevel(m.group(1).nn))
      .map(seg => seg.trim.stripPrefix("+").stripPrefix("-").trim.takeWhile(isIdentChar))
      .filter(_.nonEmpty)
      .toSet

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

  def validate(code: String, strictImportAliases: Boolean = true): List[Violation] =
    // Backticks may quote ordinary identifiers (`Runtime.`rootIO``,
    // `java.`io``) without changing what they resolve to. Blank them so the
    // dotted-token normalization and forbidden patterns see the real name while
    // character offsets/newlines remain stable for diagnostics.
    val stripped = stripLiteralsAndComments(code).replace('`', ' ')
    val originalLines = code.linesIterator.toArray
    val stringStripped = stripStringLiteralsOnly(code).replace('`', ' ').linesIterator.zipWithIndex.toList
    val logical = logicalLines(stripped.linesIterator.toArray)
    /** The violation of `pattern` on line `idx`, quoting the original source. */
    def violation(pattern: Forbidden, idx: Int, fallback: String): Violation =
      Violation(pattern.id, pattern.description, idx + 1, originalLines.lift(idx).getOrElse(fallback).trim)
    val perLine =
      for
        pattern <- forbidden
        lines = if stringStrippedPatterns.contains(pattern.id) then stringStripped else logical
        (line, idx) <- lines
        if pattern.regex.findFirstIn(line).isDefined
      yield violation(pattern, idx, line)
    val catchAlls =
      for pos <- catchAllOffsets(stripped)
      yield
        val idx = stripped.substring(0, pos).count(_ == '\n')
        Violation("catch-all", CatchAllDescription, idx + 1, originalLines.lift(idx).getOrElse("").trim)
    val importAliases =
      if !strictImportAliases then Nil
      else
        for pos <- importAliasOffsets(stripped)
        yield
          val idx = stripped.substring(0, pos).count(_ == '\n')
          Violation("import-alias", ImportAliasDescription, idx + 1, originalLines.lift(idx).getOrElse("").trim)
    val typeParams = typeParamNames(stripped)
    val typeParamCatches =
      if typeParams.isEmpty then Nil
      else
        for
          m <- caseAscription.findAllMatchIn(stripped).toList
          if typeParams.contains(m.group(1).nn)
        yield
          val idx = stripped.substring(0, m.start).count(_ == '\n')
          Violation("catch-type-param", TypeParamCatchDescription, idx + 1, originalLines.lift(idx).getOrElse("").trim)
    perLine ++ catchAlls ++ importAliases ++ typeParamCatches

  def formatErrors(violations: List[Violation]): String =
    val header = s"Code validation failed (${violations.size} violation${if violations.size > 1 then "s" else ""}):"
    val details = violations.map(v => s"  [${v.ruleId}] Line ${v.lineNumber}: ${v.description}\n    > ${v.snippet}")
    (header :: details).mkString("\n")
