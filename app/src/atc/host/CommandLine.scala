package atc.host

import atc.platform.Platform

/** The deliberately small command language accepted by `exec`.
  *
  * It supports words with quoting, pipelines, file redirection, and `2>&1`.
  * It does not perform shell expansion or accept shell control operators. The
  * parsed result is argv data, so no general shell sees the original line.
  */
private[atc] object CommandLine:
  /** Bound the process/thread fan-out of one pipeline. */
  val MaxPipelineStages = 16

  /** One program of a pipeline and whether its stderr joins its stdout (`2>&1`). */
  final case class Stage(argv: List[String], mergeErr: Boolean = false):
    /** An injective, human-readable rendering used for permission matching.
      * Argument boundaries must not disappear here: otherwise a permitted
      * `./tool safe` could also authorize an executable literally named
      * `./tool safe`.
      */
    def line: String = argv match
      case Nil => ""
      case executable :: args =>
        val shown = if Platform.isWindows then executable.replace('\\', '/') else executable
        (renderArg(shown) :: args.map(renderArg)).mkString(" ")

  /** A parsed command line: stages joined by pipes, an optional input file for
    * the first stage, and output file (truncate or append) for the last.
    */
  final case class Pipeline(
    stages: List[Stage],
    stdinFile: Option[String] = None,
    stdoutFile: Option[String] = None,
    append: Boolean = false,
  ):
    /** One program, no redirection: what `exec(command, args)` accepts as `command`. */
    def isSimple: Boolean = stages.lengthIs == 1 && stdinFile.isEmpty && stdoutFile.isEmpty && !stages.head.mergeErr

    /** How the user sees it (re-joined; quoting is not reconstructed). */
    def line: String =
      stages.map(stage => stage.line + (if stage.mergeErr then " 2>&1" else "")).mkString(" | ") +
        stdinFile.fold("")(file => s" < $file") +
        stdoutFile.fold("")(file => (if append then " >> " else " > ") + file)

  private enum Token:
    case Word(text: String)
    case Pipe, In, Out, Append, MergeErr

  private def renderArg(arg: String): String =
    val plain = arg.nonEmpty && arg.forall { char =>
      (char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z') ||
      (char >= '0' && char <= '9') || "_@%+=:,./-".contains(char) ||
      (Platform.isWindows && char == '\\')
    }
    if plain then arg
    else
      val escaped = StringBuilder()
      arg.foreach:
        case '\\' => escaped.append("\\\\")
        case '"' => escaped.append("\\\"")
        case '\n' => escaped.append("\\n")
        case '\r' => escaped.append("\\r")
        case '\t' => escaped.append("\\t")
        case char if Character.isISOControl(char) => escaped.append(f"\\u${char.toInt}%04x")
        case char => escaped.append(char)
      s"\"$escaped\""

  private def noShell(line: String, what: String, instead: String): Nothing =
    throw IllegalArgumentException(
      s"exec runs no shell: '$what' in \"$line\" is not supported ($instead); quote it ('...') if it was meant literally"
    )

  /** Tokenize like a simple shell: whitespace separates words, `'...'` is
    * literal, `"..."` honours `\"` and `\\`, and a backslash escapes the next
    * character. Unquoted operators become tokens; unsupported shell syntax
    * produces an actionable error.
    */
  private def tokenize(line: String): List[Token] =
    val tokens = List.newBuilder[Token]
    val current = StringBuilder()
    var inWord = false
    var quoted = false
    var index = 0

    def flush(): Unit =
      if inWord then
        tokens += Token.Word(current.toString)
        current.clear()
        inWord = false
        quoted = false

    def operator(token: Token, width: Int): Unit =
      flush()
      tokens += token
      index += width

    while index < line.length do
      line.charAt(index) match
        case ' ' | '\t' | '\n' | '\r' =>
          flush()
          index += 1
        case '\'' =>
          val end = line.indexOf('\'', index + 1)
          if end < 0 then throw IllegalArgumentException(s"unterminated single quote in command line: $line")
          current.append(line.slice(index + 1, end))
          inWord = true
          quoted = true
          index = end + 1
        case '"' =>
          inWord = true
          quoted = true
          index += 1
          var closed = false
          while !closed do
            if index >= line.length then
              throw IllegalArgumentException(s"unterminated double quote in command line: $line")
            val char = line.charAt(index)
            if char == '"' then
              closed = true
              index += 1
            else if char == '\\' && index + 1 < line.length &&
              (line.charAt(index + 1) == '"' || line.charAt(index + 1) == '\\')
            then
              current.append(line.charAt(index + 1))
              index += 2
            else
              current.append(char)
              index += 1
        case '\\' if Platform.isWindows =>
          // A backslash is a path separator on Windows, not shell syntax.
          current.append('\\')
          inWord = true
          index += 1
        case '\\' if index + 1 < line.length =>
          current.append(line.charAt(index + 1))
          inWord = true
          quoted = true
          index += 2
        case '|' =>
          if line.startsWith("||", index) then
            noShell(line, "||", "run the commands separately and branch in Scala")
          operator(Token.Pipe, 1)
        case '<' =>
          if line.startsWith("<<", index) then
            noShell(line, "<<", "feed text with ExecOptions(stdin = ...)")
          operator(Token.In, 1)
        case '>' =>
          if inWord && !quoted && current.toString == "2" then
            if line.startsWith(">&1", index) then
              current.clear()
              inWord = false
              tokens += Token.MergeErr
              index += 3
            else noShell(line, "2>", "only 2>&1 is supported; stderr is captured in the result anyway")
          else if line.startsWith(">&", index) then noShell(line, ">&", "only 2>&1 is supported")
          else if line.startsWith(">>", index) then operator(Token.Append, 2)
          else operator(Token.Out, 1)
        case '&' =>
          if line.startsWith("&&", index) then
            noShell(line, "&&", "run the commands one after the other and check exitCode in Scala")
          else if line.startsWith("&>", index) then
            noShell(line, "&>", "only 2>&1 and > file are supported")
          else noShell(line, "&", "use spawn(...) for a background process")
        case ';' => noShell(line, ";", "run the commands one after the other")
        case '`' => noShell(line, "`", "run the inner command first and use its output in Scala")
        case '$' if line.startsWith("$(", index) =>
          noShell(line, "$(", "run the inner command first and use its output in Scala")
        case char =>
          current.append(char)
          inWord = true
          index += 1

    flush()
    tokens.result()

  /** Parse a command line into a [[Pipeline]]. */
  def parsePipeline(line: String): Pipeline =
    val tokens = tokenize(line)
    val stages = List.newBuilder[Stage]
    var arguments = List.newBuilder[String]
    var wordCount = 0
    var mergeErr = false
    var stdinFile: Option[String] = None
    var stdoutFile: Option[String] = None
    var append = false

    def endStage(why: String): Unit =
      if wordCount == 0 then throw IllegalArgumentException(s"exec: empty command $why in: $line")
      stages += Stage(arguments.result(), mergeErr)
      arguments = List.newBuilder[String]
      wordCount = 0
      mergeErr = false

    def fileAfter(operator: String, rest: List[Token]): (String, List[Token]) = rest match
      case Token.Word(file) :: more => (file, more)
      case _ => throw IllegalArgumentException(s"exec: '$operator' needs a file name in: $line")

    def parse(rest: List[Token]): Unit = rest match
      case Nil => ()
      case Token.Word(word) :: more =>
        arguments += word
        wordCount += 1
        parse(more)
      case Token.Pipe :: more =>
        endStage("before '|'")
        parse(more)
      case Token.MergeErr :: more =>
        mergeErr = true
        parse(more)
      case Token.In :: more =>
        val (file, remaining) = fileAfter("<", more)
        if stdinFile.isDefined then throw IllegalArgumentException(s"exec: more than one '<' in: $line")
        stdinFile = Some(file)
        parse(remaining)
      case (token @ (Token.Out | Token.Append)) :: more =>
        val (file, remaining) = fileAfter(if token == Token.Append then ">>" else ">", more)
        if stdoutFile.isDefined then
          throw IllegalArgumentException(s"exec: more than one '>' / '>>' in: $line")
        stdoutFile = Some(file)
        append = token == Token.Append
        parse(remaining)

    parse(tokens)
    if wordCount == 0 && stages.result().isEmpty then throw IllegalArgumentException("exec: empty command line")
    endStage("after '|'")
    val parsedStages = stages.result()
    if parsedStages.lengthIs > MaxPipelineStages then
      throw IllegalArgumentException(
        s"exec: a pipeline may have at most $MaxPipelineStages stages (got ${parsedStages.size})"
      )
    Pipeline(parsedStages, stdinFile, stdoutFile, append)

  /** Parse one program with no redirection (the form accepted with separate arguments). */
  def parseCommandLine(line: String): List[String] =
    val pipeline = parsePipeline(line)
    if !pipeline.isSimple then
      throw IllegalArgumentException(
        s"expected one program, but \"$line\" is a pipeline or has a redirection: put it in the one-line form, exec(\"...\"), without separate args"
      )
    pipeline.stages.head.argv
