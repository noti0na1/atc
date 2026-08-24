package atc.host

import atc.lib.ProcessResult

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** Running external processes with bounded, deadlock-free output capture */
object Processes:
  private val Windows = File.separatorChar == '\\'
  // OpenJDK's legacy Windows process mode permits ambiguous command/batch
  // quoting. The strict mode quotes cmd/bat metacharacters and rejects embedded
  // quotes rather than letting an argument become a second shell command.
  if Windows then System.setProperty("jdk.lang.Process.allowAmbiguousCommands", "false")
  private val MaxStreamChars = 8 * 1024 * 1024
  private val TruncationMarker = "\n...[truncated: output exceeded 8 MiB cap]..."
  /** A command still running after this long has its output shown live from then on. */
  val LiveAfterMs = 1000L
  /** How much of a command's early output is kept to show when it goes live (the tail). */
  private val LiveBacklogChars = 64 * 1024
  /** How much of each stream a timeout error quotes. */
  private val TimeoutTailChars = 2000
  /** Bound the process/thread fan-out of one pipeline. */
  val MaxPipelineStages = 16

  /** Resolve a Windows command without CreateProcess's current-directory-first
    * search. A repository-local `git.exe` must not shadow the Git on PATH just
    * because policy allowed `git status`. PATHEXT makes normal entry points such
    * as `npm`/`gradlew` find their `.cmd`/`.bat` launchers. */
  private[atc] def executableArgv(
    argv: List[String],
    workingDir: Path,
    environment: collection.Map[String, String] = System.getenv().nn.asScala,
  ): List[String] =
    if !Windows || argv.isEmpty then argv
    else
      val command = argv.head
      def envValue(name: String): Option[String] =
        environment.collectFirst { case (key, value) if key.equalsIgnoreCase(name) => value }
      val extensions = envValue("PATHEXT").getOrElse(".COM;.EXE;.BAT;.CMD")
        .split(";", -1).iterator.map(_.trim).filter(_.nonEmpty)
        .map(ext => if ext.startsWith(".") then ext else s".$ext").toList
      val raw = Paths.get(command).nn
      val explicit = raw.isAbsolute || command.exists(c => c == '/' || c == '\\')
      val bases =
        if explicit then List(if raw.isAbsolute then raw else workingDir.resolve(raw).nn)
        else
          envValue("PATH").toList.flatMap(_.split(File.pathSeparator, -1))
            .map(_.trim).filter(_.nonEmpty)
            .flatMap { value =>
              // Empty and relative PATH entries mean "the current directory"
              // to Windows; accepting them would restore the shadowing issue
              // this resolver exists to prevent.
              scala.util.Try(
                workingDir.getFileSystem.getPath(value.stripPrefix("\"").stripSuffix("\"")).nn
              ).toOption.filter(_.isAbsolute).map(_.resolve(command).nn)
            }
      def candidates(base: Path): List[Path] =
        val name = Option(base.getFileName).fold("")(_.toString)
        val exact = List(base)
        if name.lastIndexOf('.') > 0 then exact
        else extensions.map(ext => base.resolveSibling(name + ext).nn) ++ exact
      val resolved = bases.iterator.flatMap(candidates).find(Files.isRegularFile(_))
      resolved match
        case None if explicit => argv // let ProcessBuilder report the missing explicit path
        case None =>
          throw java.io.IOException(
            s"Executable '$command' was not found on PATH; on Windows ATC does not search the working directory for bare commands (use .\\$command explicitly)"
          )
        case Some(path) =>
          val lower = path.getFileName.toString.toLowerCase(java.util.Locale.ROOT)
          if lower.endsWith(".cmd") || lower.endsWith(".bat") then
            val unsafe = path.toString :: argv.tail
            unsafe.find(_.exists(c => c == '%' || c == '!' || c == '\r' || c == '\n' || c == 0)).foreach { _ =>
              throw IllegalArgumentException(
                s"Unsafe path or argument for Windows batch command '$command': %, ! and line breaks can be expanded by cmd.exe; invoke an explicitly permitted cmd.exe command if shell syntax is intended"
              )
            }
          else if lower.contains('.') && !lower.endsWith(".exe") && !lower.endsWith(".com") then
            throw IllegalArgumentException(
              s"Windows cannot execute '$command' directly; invoke its interpreter explicitly (for example powershell.exe -File for .ps1)"
            )
          path.toString :: argv.tail

  // ── The command-line grammar ──────────────────────────────────────
  //
  // A deliberately tiny subset of a shell: words with quoting, `|` between
  // stages, `< file`, `> file`, `>> file`, and `2>&1` on a stage. No expansion
  // (globs, `$VAR`, `~`), no control operators (`&&`, `;`, `||`, `&`), no
  // command substitution. The parser produces argv data; no general shell sees
  // the original line (an explicitly selected Windows .cmd/.bat necessarily
  // runs through the OS command processor after its stage is authorized).

  /** One program of a pipeline and whether its stderr joins its stdout (`2>&1`). */
  final case class Stage(argv: List[String], mergeErr: Boolean = false):
    /** An injective, human-readable rendering used for permission matching.
      * Argument boundaries must not disappear here: otherwise a permitted
      * `./tool safe` could also authorize an executable literally named
      * `./tool safe`. */
    def line: String = argv match
      case Nil => ""
      case executable :: args =>
        val shown = if Windows then executable.replace('\\', '/') else executable
        (renderArg(shown) :: args.map(renderArg)).mkString(" ")

  /** A parsed command line: stages joined by pipes, an optional input file for the
    * first stage and output file (truncate or append) for the last. */
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
      stages.map(st => st.line + (if st.mergeErr then " 2>&1" else "")).mkString(" | ") +
        stdinFile.fold("")(f => s" < $f") +
        stdoutFile.fold("")(f => (if append then " >> " else " > ") + f)

  private enum Tok:
    case Word(text: String)
    case Pipe, In, Out, Append, MergeErr

  private def renderArg(arg: String): String =
    val plain = arg.nonEmpty && arg.forall { char =>
      (char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z') ||
      (char >= '0' && char <= '9') || "_@%+=:,./-".contains(char)
      || (Windows && char == '\\')
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

  /** Tokenize like a simple shell: whitespace separates words, `'...'` is literal,
    * `"..."` honours `\"` and `\\`, a backslash escapes the next character; the
    * unquoted operators `|`, `<`, `>`, `>>`, `2>&1` become tokens, and what a shell
    * would also accept but this grammar does not (`&&`, `;`, `||`, `&`, `2>`,
    * backticks, `$(`) throws `IllegalArgumentException` saying what to do instead. */
  private def tokenize(line: String): List[Tok] =
    val toks = List.newBuilder[Tok]
    val cur = StringBuilder()
    var inWord = false
    var quoted = false // some part of the current word came from quotes or an escape
    var i = 0
    def flush(): Unit =
      if inWord then
        toks += Tok.Word(cur.toString)
        cur.clear()
        inWord = false
        quoted = false
    def operator(t: Tok, width: Int): Unit =
      flush()
      toks += t
      i += width
    while i < line.length do
      val c = line.charAt(i)
      c match
        case ' ' | '\t' | '\n' | '\r' =>
          flush()
          i += 1
        case '\'' =>
          val end = line.indexOf('\'', i + 1)
          if end < 0 then throw IllegalArgumentException(s"unterminated single quote in command line: $line")
          cur.append(line.slice(i + 1, end))
          inWord = true
          quoted = true
          i = end + 1
        case '"' =>
          inWord = true
          quoted = true
          i += 1
          var closed = false
          while !closed do
            if i >= line.length then throw IllegalArgumentException(s"unterminated double quote in command line: $line")
            val d = line.charAt(i)
            if d == '"' then
              closed = true
              i += 1
            else if d == '\\' && i + 1 < line.length && (line.charAt(i + 1) == '"' || line.charAt(i + 1) == '\\') then
              cur.append(line.charAt(i + 1))
              i += 2
            else
              cur.append(d)
              i += 1
        case '\\' if Windows =>
          // A backslash is a path separator on Windows, not shell syntax.
          // Quoting remains available for executable paths and arguments with spaces.
          cur.append('\\')
          inWord = true
          i += 1
        case '\\' if i + 1 < line.length =>
          cur.append(line.charAt(i + 1))
          inWord = true
          quoted = true
          i += 2
        case '|' =>
          if line.startsWith("||", i) then noShell(line, "||", "run the commands separately and branch in Scala")
          operator(Tok.Pipe, 1)
        case '<' =>
          if line.startsWith("<<", i) then noShell(line, "<<", "feed text with ExecOptions(stdin = ...)")
          operator(Tok.In, 1)
        case '>' =>
          if inWord && !quoted && cur.toString == "2" then
            // `2>&1` merges stderr into the pipe; any other stderr redirection is out of the grammar.
            if line.startsWith(">&1", i) then
              cur.clear(); inWord = false
              toks += Tok.MergeErr
              i += 3
            else noShell(line, "2>", "only 2>&1 is supported; stderr is captured in the result anyway")
          else if line.startsWith(">&", i) then noShell(line, ">&", "only 2>&1 is supported")
          else if line.startsWith(">>", i) then operator(Tok.Append, 2)
          else operator(Tok.Out, 1)
        case '&' =>
          if line.startsWith("&&", i) then
            noShell(line, "&&", "run the commands one after the other and check exitCode in Scala")
          else if line.startsWith("&>", i) then noShell(line, "&>", "only 2>&1 and > file are supported")
          else noShell(line, "&", "use spawn(...) for a background process")
        case ';' => noShell(line, ";", "run the commands one after the other")
        case '`' => noShell(line, "`", "run the inner command first and use its output in Scala")
        case '$' if line.startsWith("$(", i) =>
          noShell(line, "$(", "run the inner command first and use its output in Scala")
        case other =>
          cur.append(other)
          inWord = true
          i += 1
    flush()
    toks.result()

  /** Parse a command line into a [[Pipeline]]; `<` names the input of the first
    * stage and `>`/`>>` the output of the last wherever they appear (at most one of
    * each). Throws `IllegalArgumentException` for an empty line or stage, a
    * redirection without a file name, or anything outside the grammar. */
  def parsePipeline(line: String): Pipeline =
    val toks = tokenize(line)
    val stages = List.newBuilder[Stage]
    var argv = List.newBuilder[String]
    var words = 0
    var mergeErr = false
    var stdinFile: Option[String] = None
    var stdoutFile: Option[String] = None
    var append = false
    def endStage(why: String): Unit =
      if words == 0 then throw IllegalArgumentException(s"exec: empty command $why in: $line")
      stages += Stage(argv.result(), mergeErr)
      argv = List.newBuilder[String]
      words = 0
      mergeErr = false
    def fileAfter(op: String, rest: List[Tok]): (String, List[Tok]) = rest match
      case Tok.Word(f) :: more => (f, more)
      case _ => throw IllegalArgumentException(s"exec: '$op' needs a file name in: $line")
    def go(ts: List[Tok]): Unit = ts match
      case Nil => ()
      case Tok.Word(w) :: rest =>
        argv += w
        words += 1
        go(rest)
      case Tok.Pipe :: rest =>
        endStage("before '|'")
        go(rest)
      case Tok.MergeErr :: rest =>
        mergeErr = true
        go(rest)
      case Tok.In :: rest =>
        val (f, more) = fileAfter("<", rest)
        if stdinFile.isDefined then throw IllegalArgumentException(s"exec: more than one '<' in: $line")
        stdinFile = Some(f)
        go(more)
      case (t @ (Tok.Out | Tok.Append)) :: rest =>
        val (f, more) = fileAfter(if t == Tok.Append then ">>" else ">", rest)
        if stdoutFile.isDefined then throw IllegalArgumentException(s"exec: more than one '>' / '>>' in: $line")
        stdoutFile = Some(f)
        append = t == Tok.Append
        go(more)
    go(toks)
    if words == 0 && stages.result().isEmpty then throw IllegalArgumentException("exec: empty command line")
    endStage("after '|'")
    val parsedStages = stages.result()
    if parsedStages.lengthIs > MaxPipelineStages then
      throw IllegalArgumentException(
        s"exec: a pipeline may have at most $MaxPipelineStages stages (got ${parsedStages.size})"
      )
    Pipeline(parsedStages, stdinFile, stdoutFile, append)

  /** A single program's words: the line must hold one stage and no redirection
    * (what `exec(command, args, ...)` accepts as `command`). */
  def parseCommandLine(line: String): List[String] =
    val p = parsePipeline(line)
    if !p.isSimple then
      throw IllegalArgumentException(
        s"expected one program, but \"$line\" is a pipeline or has a redirection: put it in the one-line form, exec(\"...\"), without separate args"
      )
    p.stages.head.argv

  /** Where a long-running command's output is shown as it comes: `begin` once,
    * [[LiveAfterMs]] after the start when the command is still running, then
    * `output` for what it produced so far and for every chunk after that, from
    * the draining threads. A quick command is never shown this way. */
  trait LiveOutput:
    def begin(): Unit
    def output(text: String): Unit

  /** A bounded text buffer fed by a drain thread and read by the agent. With
    * `keepHead` the first `cap` characters are kept and the rest dropped (a
    * foreground command: its first megabytes carry the diagnostics); otherwise
    * the oldest text is dropped (a long-running process: the recent output
    * matters). Reads may consume, so an interactive session sees each chunk once. */
  private final class OutputBuffer(cap: Int, keepHead: Boolean):
    private val sb = StringBuilder()
    private var truncated = false
    def append(text: String): Unit = synchronized:
      if keepHead then
        val room = cap - sb.length
        if room > 0 then sb.append(text.take(room))
        if text.length > room then truncated = true
      else
        sb.append(text)
        if sb.length > cap then
          sb.delete(0, sb.length - cap)
          truncated = true
      notifyAll()
    /** The unread text, left in place. */
    def peek: String = synchronized(sb.toString)
    /** The unread text, consumed. */
    def take(): String = synchronized:
      val s = sb.toString
      sb.clear()
      s
    /** The first `n` unread characters, consumed. */
    def consume(n: Int): String = synchronized:
      val s = sb.substring(0, n)
      sb.delete(0, n)
      s
    /** Whether the cap ever dropped text (reported once, in `marker`). */
    def marker: String = synchronized:
      if !truncated then ""
      else if keepHead then TruncationMarker
      else "\n...[older output dropped: exceeded 8 MiB cap]...\n"
    /** Wait (at most `ms`) for more text to arrive. */
    def awaitChange(ms: Long): Unit = synchronized(wait(math.max(1L, ms)))

  /** The gate between the draining threads and the live view: text is held
    * back until `goLive()` (keeping at most the last [[LiveBacklogChars]]),
    * then passed straight on. */
  private final class LiveGate(view: LiveOutput):
    private var live = false
    private val backlog = StringBuilder()
    def feed(text: String): Unit = synchronized:
      if live then view.output(text)
      else
        backlog.append(text)
        if backlog.length > LiveBacklogChars then backlog.delete(0, backlog.length - LiveBacklogChars)
    def goLive(): Unit = synchronized:
      if !live then
        live = true
        view.begin()
        if backlog.nonEmpty then view.output(backlog.toString)
        backlog.clear()

  /** A started pipeline: its stages, the stdout of the last and the stderr of
    * every stage draining into bounded buffers, optionally a live view, and a
    * watcher that reports the exit. Both a foreground `exec` (start, wait,
    * result) and a background `spawn` (talk to it, read as it goes) are built on it. */
  final class ManagedProcess private (
    procs: List[java.lang.Process],
    val stageLines: List[String],
    val line: String,
    private val stdoutBuf: OutputBuffer,
    private val stderrBufs: List[OutputBuffer],
    gate: Option[LiveGate],
  ):
    private val stdin = procs.head.getOutputStream.nn
    private var drains: List[Thread] = Nil
    @volatile private var stdinClosed = false

    def isAlive: Boolean = procs.exists(_.isAlive)
    /** Pipefail-style: the rightmost non-zero code, else 0; `None` while running. */
    def exitCode: Option[Int] =
      if isAlive then None else Some(procs.map(_.exitValue()).reverse.find(_ != 0).getOrElse(0))

    def send(text: String): Unit = synchronized:
      if stdinClosed then throw RuntimeException(s"the stdin of '$line' is closed")
      try
        stdin.write(text.getBytes("UTF-8"))
        stdin.flush()
      catch
        case e: java.io.IOException =>
          throw RuntimeException(
            s"could not write to '$line' (exited? ${exitCode.fold("no")(c => s"yes, code $c")}): ${e.getMessage}"
          )
    def closeStdin(): Unit = synchronized:
      if !stdinClosed then
        stdinClosed = true
        try stdin.close()
        catch case _: java.io.IOException => ()

    /** Unread stdout, consumed; "" when there is none. */
    def read(): String = stdoutBuf.take()
    /** Unread stderr of every stage, consumed (labelled per stage when there are several). */
    def readErr(): String = labelled(stderrBufs.map(_.take()))
    private def labelled(texts: List[String]): String =
      if texts.lengthIs == 1 then texts.head
      else
        stageLines.zip(texts).zipWithIndex.collect {
          case ((l, t), i) if t.nonEmpty => s"[stage ${i + 1}: $l]\n" + (if t.endsWith("\n") then t else t + "\n")
        }.mkString

    /** Wait until `regex` matches the unread stdout (returns the text up to and
      * including the match, consumed), the process exits without it matching, or
      * `timeoutMs` passes; the latter two throw `RuntimeException` carrying what
      * did arrive (left unread, so `read()` can still fetch it). */
    def readUntil(regex: String, timeoutMs: Long): String =
      val pattern = java.util.regex.Pattern.compile(regex)
      val started = System.nanoTime()
      def tryMatch(): Option[String] =
        val text = stdoutBuf.peek
        val m = pattern.matcher(text)
        if m.find() then Some(stdoutBuf.consume(m.end())) else None
      var found = tryMatch()
      while found.isEmpty do
        if !isAlive then
          drains.foreach(_.join(500)) // let the last chunks land
          found = tryMatch()
          if found.isEmpty then
            throw RuntimeException(
              s"'$line' exited with code ${exitCode.getOrElse(-1)} before '$regex' matched; unread output:\n${stdoutBuf.peek.takeRight(TimeoutTailChars)}"
            )
        else
          val remaining = timeoutMs - (System.nanoTime() - started) / 1_000_000L
          if remaining <= 0 then
            throw RuntimeException(
              s"timed out after ${timeoutMs}ms waiting for '$regex' from '$line'; output so far (still unread):\n${stdoutBuf.peek.takeRight(TimeoutTailChars)}"
            )
          stdoutBuf.awaitChange(math.min(remaining, 200L)) // InterruptedException propagates (Ctrl-C)
          found = tryMatch()
      found.get

    /** Wait (at most `timeoutMs`) for every stage to exit; whether they did. */
    def awaitExit(timeoutMs: Long): Boolean =
      val started = System.nanoTime()
      procs.foreach { p =>
        val remaining = math.max(0L, timeoutMs - (System.nanoTime() - started) / 1_000_000L)
        if p.isAlive then p.waitFor(remaining, TimeUnit.MILLISECONDS)
      }
      !isAlive

    /** Let the drains deliver the last chunks; then everything unread, consumed. */
    def result(): ProcessResult =
      drains.foreach(_.join(5000))
      ProcessResult(exitCode.getOrElse(-1), stdoutBuf.take() + stdoutBuf.marker, labelled(stderrBufs.map(_.take())))

    /** The last part of each stream, unread text included, for an error message. */
    def tails: (String, String) =
      (stdoutBuf.peek.takeRight(TimeoutTailChars), stderrBufs.map(_.peek).mkString.takeRight(TimeoutTailChars))

    def goLive(): Unit = gate.foreach(_.goLive())

    private def descendants(): List[java.lang.ProcessHandle] =
      procs.flatMap { process =>
        val stream = process.descendants().nn
        try stream.iterator().nn.asScala.toList
        finally stream.close()
      }.distinct

    /** Terminate every descendant and pipeline stage, give them a moment, then
      * force what is left. Wrapper scripts on Windows commonly start the real
      * server as a child; killing only cmd/npm/gradlew would leak that server. */
    def kill(): Unit =
      val children = descendants()
      children.reverse.foreach(_.destroy())
      procs.foreach(_.destroy())
      val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000)
      var interrupted = false
      while !interrupted && (procs.exists(_.isAlive) || children.exists(_.isAlive)) && System.nanoTime() < deadline do
        try Thread.sleep(20)
        catch case _: InterruptedException => interrupted = true
      children.reverse.filter(_.isAlive).foreach(_.destroyForcibly())
      procs.filter(_.isAlive).foreach(_.destroyForcibly())
      if interrupted then Thread.currentThread().interrupt()
      ManagedProcess.live.remove(this)
      ()

  object ManagedProcess:
    /** Every started, not yet exited process tree of this JVM: killed at
      * shutdown so an agent's dev server cannot outlive atc. */
    private val live = java.util.concurrent.ConcurrentHashMap.newKeySet[ManagedProcess]().nn
    java.lang.Runtime.getRuntime.nn.addShutdownHook(Thread(() => live.forEach(_.kill())))

    /** Start the stages (one `ProcessBuilder` each; the caller may already have
      * redirected the first's input / the last's output to files), feed `stdin`
      * to the first (closing it either way when it is empty... no: closing it
      * only when `closeStdinAfter`: a foreground command gets EOF at once, a
      * spawned one keeps its stdin open for `send`), drain the streams into
      * buffers, and watch for the exit. */
    def start(
      pbs: List[ProcessBuilder],
      stageLines: List[String],
      line: String,
      stdin: String,
      closeStdinAfter: Boolean,
      live: Option[LiveOutput],
      keepHead: Boolean,
      onExit: Int => Unit,
    ): ManagedProcess =
      val procs: List[java.lang.Process] =
        if pbs.lengthIs == 1 then List(pbs.head.start().nn)
        else ProcessBuilder.startPipeline(pbs.asJava).nn.asScala.toList
      val gate = live.map(LiveGate(_))
      val m = ManagedProcess(
        procs,
        stageLines,
        line,
        OutputBuffer(MaxStreamChars, keepHead),
        procs.map(_ => OutputBuffer(MaxStreamChars, keepHead)),
        gate
      )
      ManagedProcess.live.add(m)
      // stdin: written on its own thread (a large input would deadlock against the drains).
      if stdin.isEmpty then
        if closeStdinAfter then m.closeStdin()
      else
        val feeder = Thread(() =>
          try m.send(stdin)
          catch case _: RuntimeException => ()
          finally if closeStdinAfter then m.closeStdin()
        )
        feeder.setDaemon(true)
        feeder.start()
      def drainer(stream: java.io.InputStream, into: OutputBuffer): Thread =
        Thread(() =>
          val text = TextSink(into.append)
          val shown = gate.map(g => TextSink(g.feed))
          val buf = new Array[Byte](8192)
          try
            var n = stream.read(buf)
            while n >= 0 do
              text.write(buf, 0, n)
              shown.foreach(_.write(buf, 0, n))
              n = stream.read(buf)
          catch case _: java.io.IOException => ()
          finally
            text.finish()
            shown.foreach(_.finish())
            try stream.close()
            catch case _: java.io.IOException => ()
        )
      m.drains = drainer(procs.last.getInputStream.nn, m.stdoutBuf) ::
        procs.zip(m.stderrBufs).map((p, b) => drainer(p.getErrorStream.nn, b))
      m.drains.foreach { t =>
        t.setDaemon(true); t.start()
      }
      val watcher = Thread(() =>
        procs.foreach(p =>
          try p.waitFor()
          catch case _: InterruptedException => ()
        )
        m.drains.foreach(_.join(5000))
        ManagedProcess.live.remove(m)
        onExit(m.exitCode.getOrElse(-1))
      )
      watcher.setDaemon(true)
      watcher.start()
      m

  /** Start `pb`, capture both streams (capped), enforce the timeout; with `live`,
    * show the output as it comes once the command has run for [[LiveAfterMs]].
    * Single-stage convenience over the pipeline form. */
  def run(pb: ProcessBuilder, name: String, timeoutMs: Long, live: Option[LiveOutput] = None): ProcessResult =
    run(List(pb), List(name), name, timeoutMs, live, "")

  /** Run a pipeline to completion: start the stages (see [[ManagedProcess.start]]),
    * feed `stdin` to the first and close it, wait at most `timeoutMs` for every
    * stage (switching the live view on after [[LiveAfterMs]]), kill them all on a
    * timeout (the error quotes the output so far), and return the last stage's
    * stdout, every stage's stderr (labelled when there are several) and the
    * pipefail-style exit code. */
  def run(
    pbs: List[ProcessBuilder],
    stageLines: List[String],
    name: String,
    timeoutMs: Long,
    live: Option[LiveOutput],
    stdin: String,
  ): ProcessResult =
    val m = ManagedProcess.start(pbs, stageLines, name, stdin, closeStdinAfter = true, live, keepHead = true, _ => ())
    try
      val firstWait = math.min(LiveAfterMs, timeoutMs)
      if !m.awaitExit(firstWait) then
        m.goLive()
        if !m.awaitExit(timeoutMs - firstWait) then
          m.kill()
          val (out, err) = m.tails
          throw RuntimeException(
            s"Process '$name' timed out after ${timeoutMs}ms (raise it with ExecOptions(timeoutMs = ...)); output so far:\n$out${
                if err.isEmpty then "" else s"\n[stderr]\n$err"
              }"
          )
      m.result()
    catch
      case e: Exception =>
        m.kill()
        throw e
