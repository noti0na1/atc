package atc.host

import atc.lib.*
import atc.perms.{GitIgnore, PathPattern, Perm, Policy, ScopeId}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse as JHttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{FileSystems, Files, Path, Paths, StandardOpenOption}
import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try, Using}

/** The application's implementation of the agent-facing API. Every method
  * enforces [[Policy]] and performs the real effect. */
final class Host(
  val policy: Policy,
  val cwd: Path,
  output: HostOutput,
  llm: HostLlm,
  ui: HostUi,
  /** Paths git ignores are left out of listings (config `respectGitignore`). */
  gitIgnore: GitIgnore = GitIgnore.Disabled,
) extends Interface, Derivations:

  @volatile private var todoList: List[Todo] = Nil

  // ── paths & permission checks ─────────────────────────────────────

  /** Convert a path to canonical absolute form: resolve it against `cwd`, expand
    * `~`, normalize it, and resolve symlinks as far as possible. The policy
    * evaluates a link by its target, including a dangling link whose target would
    * be created by a write. */
  private[atc] def canonical(p: String): Path =
    val raw = Paths.get(PathPattern.expandHome(p)).nn
    PathPattern.canonical(if raw.isAbsolute then raw else cwd.resolve(raw).nn)

  private def denied(p: Path, what: String, pm: Perm, hint: String): SecurityException =
    SecurityException(s"Access denied: $what on '$p' is not permitted (current permission: ${pm.describe}). $hint")

  private[atc] def requireRead(scope: ScopeId, p: Path, what: String): Perm =
    val pm = policy.effective(scope, p)
    if !pm.canRead then
      throw denied(p, what, pm, s"""Use requestFiles("$p", Access.Read, reason) { ... } to ask the user.""")
    pm

  private[atc] def requireWrite(scope: ScopeId, p: Path, what: String): Perm =
    val pm = policy.effective(scope, p)
    if !pm.canWrite then
      throw denied(p, what, pm, s"""Use requestFiles("$p", Access.Write, reason) { ... } to ask the user.""")
    pm

  private[atc] def requireNotClassified(pm: Perm, p: Path, what: String, alt: String): Unit =
    if pm.classified then
      throw SecurityException(s"Access denied: '$p' is classified; '$what' would reveal its content. Use $alt instead.")

  /** The permission scope a capability (`FileSystem`, `Exec`, `Network`) was issued for. */
  private def scopeOf(capability: AnyRef): ScopeId = capability match
    case s: Scoped => s.scope
    case other => throw SecurityException(s"Unknown capability implementation: ${other.getClass.getName}")

  /** Run a `request*` block with a capability for the newly opened scope `id`.
    * Always close the scope and kill its processes when the block ends. This
    * prevents a one-time grant from leaving behind a live process that is no
    * longer visible or controllable. Long-lived processes must use a standing
    * grant in the base scope. */
  private def inScope[T](id: ScopeId)(op: ScopeId => T): T =
    try op(id)
    finally
      killProcessesInScope(id)
      policy.closeScope(id)

  /** Kill and drop every spawned process whose owning scope is exactly `id`. */
  private def killProcessesInScope(id: ScopeId): Unit = spawned.synchronized:
    spawned.values.toList.filter(_.scope == id).foreach { p =>
      try p.managed.kill()
      catch case _: Exception => ()
    }
    reapProcesses()

  // ── file effects shared by FileEntryImpl and the path helpers ─────

  /** Writes create the missing parent directories of their target. */
  private def ensureParent(p: Path): Unit = Option(p.getParent).foreach(Files.createDirectories(_))

  private[atc] def writeFile(scope: ScopeId, p: Path, content: String, append: Boolean): Unit =
    val pm = requireWrite(scope, p, if append then "append" else "write")
    requireNotClassified(pm, p, "write", "writeClassified(path, classify(content))")
    ensureParent(p)
    if append then
      Files.writeString(p, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    else Files.writeString(p, content, StandardCharsets.UTF_8)

  private[atc] def writeFileBytes(scope: ScopeId, p: Path, content: Array[Byte]): Unit =
    val pm = requireWrite(scope, p, "writeBytes")
    requireNotClassified(pm, p, "writeBytes", "writeClassified(path, classify(content))")
    ensureParent(p)
    Files.write(p, content)
    ()

  private[atc] def writeClassifiedFile(scope: ScopeId, p: Path, content: Try[String]): Unit =
    // Check permissions and target classification before inspecting the result.
    // A denied or non-classified target must fail identically whether the
    // classified computation succeeded or failed, preventing a failure oracle.
    val pm = requireWrite(scope, p, "writeClassified")
    if !pm.classified then
      throw SecurityException(
        s"Access denied: '$p' is not a classified path; writing classified content there would declassify it."
      )
    ensureParent(p)
    content match
      case Success(v) => Files.writeString(p, v, StandardCharsets.UTF_8)
      // Keep the agent unaware of the failure: create the target so its existence
      // reveals nothing, leave its classified content unreadable, and report the
      // failure only through the user channel.
      case Failure(_) =>
        if !Files.exists(p) then { Files.createFile(p); () }
        classifiedSinkFailed(s"writing '$p'")

  /** Report a failed classified computation only through the user channel. The
    * agent must not observe the failure bit because a pure `map` can fail
    * conditionally on the secret and turn that bit into an oracle. */
  private[atc] def classifiedSinkFailed(what: String): Unit =
    output.print(
      "",
      s"<$what failed: the classified value is the result of a failed computation; its error is confidential>\n"
    )

  /** The entries of `dir` (itself canonical) the scope may see, each as the
    * canonical path the policy judges it by, flagged when the entry is a
    * symlink. Every path the host hands out is canonical, so an entry from a
    * listing is checked exactly like the same path given by name: a link is
    * listed as, and judged by, its target (so one to a classified or
    * unreadable file cannot be read through its link). Only links pay for a
    * `toRealPath`; a plain child of a canonical directory is canonical already.
    * Omitted: entries the scope may not read and, with `respectGitignore`, the
    * ones git ignores (judged by the link's own location, as git does). */
  private def visibleEntries(scope: ScopeId, dir: Path): List[(Path, Boolean)] =
    Using.resource(Files.list(dir).nn) { s =>
      s.iterator.nn.asScala.toList.sortBy(_.getFileName.toString).flatMap { raw =>
        try
          val isLink = Files.isSymbolicLink(raw)
          val path = if isLink then PathPattern.canonical(raw) else raw
          Option.when(!gitIgnore.ignores(raw) && policy.effective(scope, path).canRead)((path, isLink))
        catch case _: Exception => None
      }
    }

  /** Children the scope may see, canonical (see [[visibleEntries]]). */
  private[atc] def visibleChildren(scope: ScopeId, dir: Path): List[Path] = visibleEntries(scope, dir).map(_._1)

  /** Every visible descendant of `dir`, in pre-order. Classified sub-trees are
    * listed but not entered unless `intoClassified`; symlinked directories are
    * listed (as their target) but never followed. */
  private[atc] def walkPaths(scope: ScopeId, dir: Path, intoClassified: Boolean): List[Path] =
    def descendInto(c: Path, isLink: Boolean): Boolean =
      !isLink && Files.isDirectory(c) && (intoClassified || !policy.effective(scope, c).classified)
    def go(d: Path): List[Path] =
      visibleEntries(scope, d).flatMap((c, isLink) => c :: (if descendInto(c, isLink) then go(c) else Nil))
    go(dir)

  // ── Interface: deriving capabilities ──────────────────────────────
  // (Read-only vs full is a matter of types on the agent side; the host
  // hands out the same base-scope objects.)

  def fileSystem(using io: IOCap): FileSystem = FileSystemImpl(ScopeId.Base, this)
  def readOnlyFileSystem(using io: IOCap): FileSystem = FileSystemImpl(ScopeId.Base, this)
  def processes(using io: IOCap): Exec = ExecImpl(ScopeId.Base)
  def network(using io: IOCap): Network = NetworkImpl(ScopeId.Base)

  // ── Interface: files ──────────────────────────────────────────────

  def requestFiles[T, C <: caps.CapSet](path: String)(using UserIO, FileSystem)(op: FileSystem ?=> T): T =
    requestFiles(path, Access.Read, "")(op)
  def requestFiles[T, C <: caps.CapSet](path: String, access: Access)(using
    UserIO,
    FileSystem
  )(
    op: FileSystem ?=> T
  ): T =
    requestFiles(path, access, "")(op)
  def requestFiles[T, C <: caps.CapSet](path: String, access: Access, reason: String)(using
    user: UserIO,
    parent: FileSystem
  )(op: FileSystem ?=> T): T =
    val level = access match
      case Access.Read => atc.perms.Access.Read
      case Access.Write => atc.perms.Access.Write
    inScope(policy.requestFile(scopeOf(parent), canonical(path), level, reason)) { id =>
      op(using FileSystemImpl(id, this))
    }

  def access(path: String)(using fs: FileSystem): FileEntry = fs.access(path)
  def read(path: String)(using fs: FileSystem): String = fs.access(path).read()
  def readLines(path: String)(using fs: FileSystem): List[String] = fs.access(path).readLines()

  /** `cat -n` view of a file, capped at [[Host.CatMaxLines]] with a note naming
    * the `cat(path, from, to)` call that shows the next window. */
  def cat(path: String)(using fs: FileSystem, user: UserIO): Unit =
    val lines = fs.access(path).readLines()
    val n = lines.length
    val text =
      if n == 0 then "[empty file]\n"
      else
        val body = numbered(lines.take(Host.CatMaxLines), 1)
        if n <= Host.CatMaxLines then body
        else
          val next = math.min(n, 2 * Host.CatMaxLines)
          body + s"... [${n - Host.CatMaxLines} more lines ($n in all): cat(\"$path\", ${Host.CatMaxLines + 1}, $next) shows the next]\n"
    output.print(text, text)

  /** `sed -n 'from,top'` view: lines `from` to `to` (1-based, inclusive) with numbers. */
  def cat(path: String, from: Int, to: Int)(using fs: FileSystem, user: UserIO): Unit =
    if from < 1 || to < from then
      throw IllegalArgumentException(s"cat: the range must satisfy 1 <= from <= to (got $from, $to)")
    val lines = fs.access(path).readLines()
    val n = lines.length
    val text =
      if from > n then s"[nothing to show: $path has $n lines]\n"
      else
        val body = numbered(lines.slice(from - 1, math.min(to, n)), from)
        if to > n then body + s"[end of file: $n lines]\n" else body
    output.print(text, text)

  /** `cat -n` formatting: a 6-wide right-aligned number, a tab, the line (cut at
    * [[Host.CatMaxLineChars]] with a marker), a newline; numbering starts at `first`. */
  private def numbered(lines: List[String], first: Int): String =
    val sb = StringBuilder()
    var i = first
    for line <- lines do
      val shown =
        if line.length <= Host.CatMaxLineChars then line
        else line.take(Host.CatMaxLineChars) + s" ... [+${line.length - Host.CatMaxLineChars} chars]"
      sb.append(f"$i%6d\t").append(shown).append('\n')
      i += 1
    sb.toString
  def readBytes(path: String)(using fs: FileSystem): Array[Byte] = fs.access(path).readBytes()
  def write(path: String, content: String)(using fs: FileSystem): Unit = fs.access(path).write(content)
  def writeBytes(path: String, content: Array[Byte])(using fs: FileSystem): Unit =
    fs.access(path).writeBytes(content)

  /** Composed of the checked primitives (read `from`, write `to`, delete `from`),
    * so it grants nothing they would not: a classified source is refused by the
    * read, a classified target by the write. Moving a file onto itself is a no-op
    * (read → write → delete would otherwise destroy it). */
  def move(from: String, to: String)(using fs: FileSystem): Unit =
    val src = fs.access(from)
    if src.isDirectory then
      throw IllegalArgumentException(s"move: '$from' is a directory; move its files and mkdir/delete the directories")
    val dst = fs.access(to)
    if src.path != dst.path then
      val bytes = src.readBytes()
      dst.writeBytes(bytes)
      src.delete()

  def copy(from: String, to: String)(using fs: FileSystem): Unit =
    val src = fs.access(from)
    if src.isDirectory then throw IllegalArgumentException(s"copy: '$from' is a directory; copy its files one by one")
    fs.access(to).writeBytes(src.readBytes())

  /** In-place edit: rewrite the file with every match of `pattern` replaced (`(?m)`, so
    * `^`/`$` see line boundaries as in sed). Refuses a no-op so a mistaken pattern
    * cannot look like a successful edit. */
  def sed(path: String, pattern: String, replacement: String)(using fs: FileSystem): Int =
    if pattern.isEmpty then throw IllegalArgumentException("sed: the pattern must not be empty")
    val regex = ("(?m)" + pattern).r // a malformed regex throws PatternSyntaxException (an IllegalArgumentException)
    val entry = fs.access(path)
    val before = entry.read()
    val n = regex.findAllMatchIn(before).length
    if n == 0 then
      throw IllegalArgumentException(
        s"sed: the regex '$pattern' matches nothing in '${entry.path}', so nothing was changed; check it with grep(path, pattern), and quote literal text with quote(text) (the pattern) and quoteReplacement(text) (the replacement)."
      )
    entry.write(regex.replaceAllIn(before, sedReplacement(replacement)))
    n

  /** `sed`'s replacement syntax: Java's (`$1`, `${name}`, `\` escapes the next
    * character) plus sed's `\1` for a group and `\n`/`\t` for a newline/tab; a
    * trailing lone backslash is literal instead of an error. */
  private def sedReplacement(r: String): String =
    val sb = StringBuilder()
    var i = 0
    while i < r.length do
      val c = r.charAt(i)
      if c == '\\' && i + 1 < r.length then
        r.charAt(i + 1) match
          case d if d.isDigit => sb.append('$').append(d)
          case 'n' => sb.append('\n')
          case 't' => sb.append('\t')
          case other => sb.append('\\').append(other)
        i += 2
      else
        if c == '\\' then sb.append("\\\\") else sb.append(c)
        i += 1
    sb.toString

  // TODO(safe-mode): drop with the Interface declarations once safe mode admits Regex.quote/quoteReplacement.
  def quote(text: String): String = java.util.regex.Pattern.quote(text).nn
  def quoteReplacement(text: String): String = java.util.regex.Matcher.quoteReplacement(text).nn

  def replaceLines(path: String, from: Int, to: Int, text: String)(using fs: FileSystem): String =
    val entry = fs.access(path)
    val (lines, sep, trailing) = Host.splitLines(entry.read())
    val n = lines.length
    if from < 1 || to < from || to > n then
      throw IllegalArgumentException(
        s"replaceLines: the range must satisfy 1 <= from <= to <= $n (the file has $n lines), got $from..$to; cat the file again, line numbers shift after an edit"
      )
    val old = lines.slice(from - 1, to)
    val updated = lines.take(from - 1) ++ Host.textLines(text) ++ lines.drop(to)
    entry.write(Host.joinLines(updated, sep, trailing))
    old.mkString(sep)

  def insertLines(path: String, before: Int, text: String)(using fs: FileSystem): Unit =
    val entry = fs.access(path)
    val (lines, sep, trailing) = Host.splitLines(entry.read())
    val n = lines.length
    if before < 1 || before > n + 1 then
      throw IllegalArgumentException(
        s"insertLines: `before` must be between 1 and ${n + 1} (the file has $n lines), got $before"
      )
    val updated = lines.take(before - 1) ++ Host.textLines(text) ++ lines.drop(before - 1)
    entry.write(Host.joinLines(updated, sep, trailing))

  def append(path: String, content: String)(using fs: FileSystem): Unit = fs.access(path).append(content)
  def exists(path: String)(using fs: FileSystem): Boolean = fs.access(path).exists
  def isDirectory(path: String)(using fs: FileSystem): Boolean = fs.access(path).isDirectory
  def mkdir(path: String)(using fs: FileSystem): Unit = fs.access(path).mkdir()
  def delete(path: String)(using fs: FileSystem): Unit = fs.access(path).delete()
  def ls(dir: String)(using fs: FileSystem): List[String] = fs.access(dir).children.map(e => display(e.path))
  def walk(dir: String)(using fs: FileSystem): List[String] = fs.access(dir).walk().map(e => display(e.path))

  /** How the listing helpers and `GrepMatch` show a (canonical, absolute) path:
    * relative to the working directory when inside it, so listings cost the model
    * a few tokens per entry instead of a long common prefix; absolute otherwise.
    * Every helper resolves relative paths against the working directory, so the
    * shown form can be passed straight back. */
  private lazy val cwdCanonical: Path = canonical(".")
  private def display(absolute: String): String =
    val p = Paths.get(absolute).nn
    if p == cwdCanonical then "."
    else if p.startsWith(cwdCanonical) then cwdCanonical.relativize(p).nn.toString
    else absolute

  private def grepEntry(entry: FileEntry, regex: scala.util.matching.Regex): List[GrepMatch] =
    val buf = collection.mutable.ListBuffer[GrepMatch]()
    val shown = display(entry.path)
    entry.forEachLine((line, n) => if regex.findFirstIn(line).isDefined then buf += GrepMatch(shown, n, line))
    buf.toList

  def grep(path: String, pattern: String)(using fs: FileSystem): List[GrepMatch] =
    grepEntry(fs.access(path), pattern.r)

  def grepRecursive(dir: String, pattern: String)(using fs: FileSystem): List[GrepMatch] =
    grepRecursive(dir, pattern, "*")
  def grepRecursive(dir: String, pattern: String, glob: String)(using fs: FileSystem): List[GrepMatch] =
    val regex = pattern.r
    filesNamed(dir, glob).filterNot(_.isClassified).flatMap(grepEntry(_, regex))

  def find(dir: String, glob: String)(using fs: FileSystem): List[String] =
    filesNamed(dir, glob).map(e => display(e.path))

  /** The files (not directories) under `dir` selected by `glob`: a plain glob is
    * matched against the file *name*; one containing `/` or `**` against the path
    * relative to `dir` ([[Host.globRegex]]), so `src`, `**` and `*.scala` joined by
    * slashes finds Scala files at any depth under `src`, including directly in it. */
  private def filesNamed(dir: String, glob: String)(using fs: FileSystem): List[FileEntry] =
    val files = fs.access(dir).walk().filter(!_.isDirectory)
    if glob.contains('/') || glob.contains("**") then
      val base = canonical(dir)
      val regex = Host.globRegex(glob)
      files.filter(e => regex.matches(base.relativize(Paths.get(e.path)).nn.toString))
    else
      val matcher = FileSystems.getDefault.nn.getPathMatcher(s"glob:$glob").nn
      files.filter(e => matcher.matches(Paths.get(e.path).nn.getFileName))

  def readClassified(path: String)(using fs: FileSystem): Classified[String] = fs.access(path).readClassified()
  def writeClassified(path: String, content: Classified[String])(using fs: FileSystem): Unit =
    fs.access(path).writeClassified(content)

  // ── Interface: processes ──────────────────────────────────────────

  def requestExec[T](commands: Iterable[String])(op: Exec ?=> T)(using UserIO, Exec): T =
    requestExec(commands, "")(op)
  def requestExec[T](commands: Iterable[String], reason: String)(op: Exec ?=> T)(using user: UserIO, parent: Exec): T =
    val patterns = commands.toList.map(_.trim).filter(_.nonEmpty)
    inScope(policy.requestExec(scopeOf(parent), patterns, reason))(id => op(using ExecImpl(id)))

  def exec(command: String)(using Exec, FileSystem): ProcessResult = exec(command, Nil, ExecOptions())
  def exec(command: String, args: Seq[String])(using Exec, FileSystem): ProcessResult =
    exec(command, args, ExecOptions())
  def exec(command: String, args: Seq[String], workingDir: String)(using Exec, FileSystem): ProcessResult =
    exec(command, args, ExecOptions(workingDir = workingDir))
  /** A command line ready to start: parsed, every stage and redirection checked,
    * the `ProcessBuilder`s built. Shared by `exec` and `spawn`. */
  private final case class Prepared(pbs: List[ProcessBuilder], stageLines: List[String], line: String)

  private def prepare(command: String, args: Seq[String], options: ExecOptions)(using
    ex: Exec,
    fs: FileSystem
  ): Prepared =
    // `command` is parsed with the small grammar (`|`, `<`, `>`, `>>`, `2>&1`; quotes,
    // no shell); with `args` it must be one program, and the args are appended verbatim.
    val parsed = Processes.parsePipeline(command)
    val pipeline =
      if args.isEmpty then parsed
      else if parsed.isSimple then
        parsed.copy(stages = List(parsed.stages.head.copy(argv = parsed.stages.head.argv ++ args)))
      else
        throw IllegalArgumentException(
          "exec(command, args, ...): `command` must be one program when args are given; write a pipeline or redirection in the one-line form exec(\"...\")"
        )
    if options.stdin.nonEmpty && pipeline.stdinFile.isDefined then
      throw IllegalArgumentException(
        "exec: both ExecOptions(stdin = ...) and '< file' would feed the command; use one of them"
      )
    // Every stage is a command of its own for the policy: the deny list first
    // (final, so the message must not point at `requestExec`), then the patterns.
    for st <- pipeline.stages do
      policy.commandDenied(st.line).foreach { pattern =>
        throw SecurityException(
          s"Access denied: command '${st.line}' is refused by the configuration (denyCommands pattern '$pattern'). It cannot be granted; do not retry it or work around it, tell the user instead."
        )
      }
    val missing = pipeline.stages.filterNot(st => policy.commandAllowed(scopeOf(ex), st.line))
    if missing.nonEmpty then
      val what =
        if pipeline.stages.lengthIs == 1 then s"command '${missing.head.line}'"
        else
          s"stage${if missing.lengthIs > 1 then "s" else ""} ${missing.map(st => s"'${st.line}'").mkString(", ")} of the pipeline"
      val patterns = missing.map(st => s"\"${st.argv.head} *\"").mkString(", ")
      throw SecurityException(
        s"Access denied: $what matches no permitted pattern. Use requestExec(Set($patterns), reason) { ... } to ask the user."
      )
    // A command observes the directory it runs in (`git status` lists file names),
    // so the working directory must be readable and unclassified for the FileSystem
    // capability in scope. (What the command itself reads is up to the OS: the
    // command pattern is the user's decision.) `cwd` is canonicalized like any
    // other path so a symlinked project directory matches the rules.
    val dir = canonical(options.workingDir)
    val pm = requireRead(scopeOf(fs), dir, "running a command in")
    requireNotClassified(pm, dir, "running a command there", "a working directory outside it")
    // Redirections are file operations, checked like `read` and `write`: a classified
    // file may neither feed a command nor receive its output.
    val stdinFile = pipeline.stdinFile.map { f =>
      val p = canonical(f)
      val pm = requireRead(scopeOf(fs), p, "feeding a command from")
      requireNotClassified(pm, p, "feeding a command from", "an unclassified file")
      p
    }
    val stdoutFile = pipeline.stdoutFile.map { f =>
      val p = canonical(f)
      val pm = requireWrite(scopeOf(fs), p, "redirecting a command's output to")
      requireNotClassified(pm, p, "redirecting a command's output to", "an unclassified file")
      ensureParent(p)
      p
    }
    val pbs = pipeline.stages.map { st =>
      val pb = ProcessBuilder(st.argv.asJava).directory(dir.toFile).nn
      if st.mergeErr then pb.redirectErrorStream(true)
      pb
    }
    stdinFile.foreach(p => pbs.head.redirectInput(p.toFile))
    stdoutFile.foreach { p =>
      val file = p.toFile
      pbs.last.redirectOutput(if pipeline.append then ProcessBuilder.Redirect.appendTo(file)
      else ProcessBuilder.Redirect.to(file))
    }
    Prepared(pbs, pipeline.stages.map(_.line), pipeline.line)

  def exec(command: String, args: Seq[String], options: ExecOptions)(using ex: Exec, fs: FileSystem): ProcessResult =
    val p = prepare(command, args, options)
    // A command that takes a while shows its output to the user as it runs.
    val port = output
    val live = new Processes.LiveOutput:
      def begin(): Unit = port.commandRunning(p.line)
      def output(text: String): Unit = port.commandOutput(text)
    output.whileCommandRuns(Processes.run(p.pbs, p.stageLines, p.line, options.timeoutMs, Some(live), options.stdin))

  // ── processes started with `spawn` ───────────────────────────────
  // The registry is per host (one per atc process); ids are never reused in a
  // session. `killProcesses` is what the app calls when the REPL session ends.

  private val spawned = scala.collection.mutable.LinkedHashMap[Int, ProcessImpl]()
  private var nextProcessId = 0

  def spawn(command: String)(using Exec, FileSystem): Process = spawn(command, ExecOptions())
  def spawn(command: String, options: ExecOptions)(using ex: Exec, fs: FileSystem): Process =
    val p = prepare(command, Nil, options)
    spawned.synchronized:
      reapProcesses()
      if spawned.size >= Host.MaxProcesses then
        throw IllegalStateException(
          s"spawn: ${Host.MaxProcesses} processes are already running (${spawned.keys.map(i => s"p$i").mkString(", ")}); kill() one first"
        )
      nextProcessId += 1
      val id = nextProcessId
      val port = output
      val managed = Processes.ManagedProcess.start(
        p.pbs,
        p.stageLines,
        p.line,
        options.stdin,
        closeStdinAfter = false,
        live = None,
        keepHead = false,
        onExit = code => port.processExited(id, code),
      )
      val handle = ProcessImpl(id, managed, output, scopeOf(ex), policy)
      spawned(id) = handle
      output.processStarted(id, p.line)
      handle

  /** Return the live processes visible from the caller's scope. A process created
    * inside `requestExec` becomes unreachable when the block closes, preventing a
    * one-time grant from leaving a usable handle (`Policy.scopeVisibleFrom`). */
  def runningProcesses(using ex: Exec): List[Process] = spawned.synchronized:
    reapProcesses()
    val caller = scopeOf(ex)
    spawned.values.toList.filter(p => policy.scopeVisibleFrom(caller, p.scope))

  private def reapProcesses(): Unit = spawned.filterInPlace((_, p) => p.managed.isAlive)

  /** Kill every spawned process at session end or for `/kill all`. Use `managed`
    * directly so host-internal cleanup still works after a handle's scope closes. */
  private[atc] def killProcesses(): Unit = spawned.synchronized:
    spawned.values.foreach(_.managed.kill())
    spawned.clear()

  /** `/kill`: `p3`, `3` or `all`; a message for the user either way. */
  private[atc] def killProcess(ref: String): String = spawned.synchronized:
    reapProcesses()
    ref.trim.toLowerCase match
      case "" | "all" =>
        val n = spawned.size
        killProcesses()
        if n == 0 then "no process is running" else s"killed $n process${if n == 1 then "" else "es"}"
      case r =>
        r.stripPrefix("p").toIntOption.flatMap(spawned.get) match
          case Some(p) =>
            p.managed.kill()
            spawned.remove(p.id)
            s"killed p${p.id} (${p.commandLine})"
          case None => s"no running process '$ref' (see /ps)"

  /** `/ps`: one line per live process. */
  private[atc] def processSummary: String = spawned.synchronized:
    reapProcesses()
    if spawned.isEmpty then "no process is running"
    else spawned.values.map(p => s"p${p.id}  ${p.commandLine}").mkString("\n")

  def execOutput(command: String)(using Exec, FileSystem): String = execOutput(command, Nil, ExecOptions())
  def execOutput(command: String, args: Seq[String])(using Exec, FileSystem): String =
    execOutput(command, args, ExecOptions())
  def execOutput(command: String, args: Seq[String], options: ExecOptions)(using Exec, FileSystem): String =
    val r = exec(command, args, options)
    if r.exitCode != 0 then
      val err = r.stderr.trim
      val tail = if err.isEmpty then "" else s"; stderr: ${err.takeRight(Host.ExecErrorTailChars)}"
      throw RuntimeException(
        s"'${(Processes.parseCommandLine(command) ++ args).mkString(" ")}' exited with ${r.exitCode}$tail (use exec(...) to inspect a failure)"
      )
    r.stdout

  // ── Interface: network ────────────────────────────────────────────

  def requestNetwork[T](hosts: Iterable[String])(op: Network ?=> T)(using UserIO, Network): T =
    requestNetwork(hosts, "")(op)
  def requestNetwork[T](hosts: Iterable[String], reason: String)(op: Network ?=> T)(using
    user: UserIO,
    parent: Network
  ): T =
    val patterns = hosts.toList.map(_.trim.toLowerCase).filter(_.nonEmpty)
    inScope(policy.requestNet(scopeOf(parent), patterns, reason))(id => op(using NetworkImpl(id)))

  private val http = HttpClient.newBuilder().nn
    .followRedirects(HttpClient.Redirect.NEVER).nn
    .connectTimeout(Duration.ofSeconds(20)).nn
    .build().nn

  private def request(
    net: Network,
    method: String,
    url: String,
    body: Option[String],
    contentType: String,
    headers: Map[String, String],
    secretHeaders: Map[String, Classified[String]]
  ): HttpResponse =
    val uri = URI(url)
    val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("")
    if scheme != "http" && scheme != "https" then
      throw SecurityException(s"Invalid URL (only http/https are supported): $url")
    // Match a normalized host: lowercase, without a trailing dot, and with numeric
    // IP literals in canonical form. Thus `evil.com.` cannot bypass a deny rule for
    // `evil.com`, and `2852039166` is evaluated as `169.254.169.254`.
    val host =
      Option(uri.getHost).map(Host.normalizeHost(_)).getOrElse(throw SecurityException(s"Invalid URL (no host): $url"))
    policy.hostDenied(host) match
      case Some(pattern) =>
        throw SecurityException(
          s"Access denied: host '$host' is refused by the configuration (denyHosts pattern '$pattern'). It cannot be granted; do not retry it or work around it, tell the user instead."
        )
      case None =>
        if !policy.hostAllowed(scopeOf(net), host) then
          throw SecurityException(
            s"""Access denied: host '$host' matches no permitted pattern. Use requestNetwork(Set("$host"), reason) { ... } to ask the user."""
          )
    val b = HttpRequest.newBuilder(uri).nn.timeout(Duration.ofSeconds(60)).nn
    headers.foreach((k, v) => b.header(k, v))
    // Unwrap classified headers only for transmission; never expose them to the
    // agent. If a header computation failed, abort the request instead of omitting
    // the header. A missing authentication header could produce a distinguishable
    // response (for example, 401 instead of 200) or trigger different side effects,
    // revealing the failure. Notify the user; `httpPostClassified` captures the
    // error in a failed `Classified`, keeping the response confidential. For a
    // plaintext-response request, the allowed host is already an intended channel
    // for the secret, so aborting does not expose any additional information.
    val resolvedSecrets = secretHeaders.view.mapValues(ClassifiedImpl.unwrap).toMap
    if resolvedSecrets.valuesIterator.exists(_.isFailure) then
      classifiedSinkFailed("a classified request header")
      throw SecurityException(
        "A classified header value could not be computed; the request was not sent. Its error is confidential."
      )
    resolvedSecrets.foreach((k, v) => b.header(k, v.get))
    val publisher = body match
      case None => HttpRequest.BodyPublishers.noBody().nn
      case Some(text) =>
        // Header names are case-insensitive: do not add a duplicate Content-Type.
        val hasContentType = (headers.keys ++ secretHeaders.keys).exists(_.equalsIgnoreCase("Content-Type"))
        if !hasContentType then b.header("Content-Type", contentType)
        HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8).nn
    b.method(method.toUpperCase, publisher)
    val resp = http.send(b.build(), JHttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).nn
    HttpResponse(resp.statusCode, resp.body.nn)

  private val noHeaders = Map.empty[String, String]
  private val noSecrets = Map.empty[String, Classified[String]]
  /** Default `Content-Type` when the caller does not set one. */
  private val JsonContentType = "application/json"

  /** `httpGet`/`httpPost` throw on an HTTP error so a 404 page cannot pass for data;
    * `httpRequest` and the classified POST never do (the latter must stay
    * status-blind: its body is classified, so nothing about the response may
    * reach the agent except through the classified value). */
  private def checked(method: String, url: String, r: HttpResponse): String =
    if r.status >= 400 then
      throw RuntimeException(
        s"$method $url returned HTTP ${r.status}: ${r.body.take(Host.HttpErrorBodyChars)} (use httpRequest(...) to inspect a failure)"
      )
    r.body

  def httpGet(url: String)(using Network): String = httpGet(url, noHeaders, noSecrets)
  def httpGet(url: String, headers: Map[String, String])(using Network): String = httpGet(url, headers, noSecrets)
  def httpGet(url: String, headers: Map[String, String], secretHeaders: Map[String, Classified[String]])(using
    net: Network
  ): String =
    checked("GET", url, request(net, "GET", url, None, JsonContentType, headers, secretHeaders))

  def httpPost(url: String, body: String)(using Network): String =
    httpPost(url, body, JsonContentType, noHeaders, noSecrets)
  def httpPost(url: String, body: String, contentType: String)(using Network): String =
    httpPost(url, body, contentType, noHeaders, noSecrets)
  def httpPost(url: String, body: String, contentType: String, headers: Map[String, String])(using Network): String =
    httpPost(url, body, contentType, headers, noSecrets)
  def httpPost(
    url: String,
    body: String,
    contentType: String,
    headers: Map[String, String],
    secretHeaders: Map[String, Classified[String]]
  )(using net: Network): String =
    checked("POST", url, request(net, "POST", url, Some(body), contentType, headers, secretHeaders))

  def httpRequest(method: String, url: String)(using Network): HttpResponse =
    httpRequest(method, url, "", noHeaders, noSecrets)
  def httpRequest(method: String, url: String, body: String)(using Network): HttpResponse =
    httpRequest(method, url, body, noHeaders, noSecrets)
  def httpRequest(method: String, url: String, body: String, headers: Map[String, String])(using
    Network
  ): HttpResponse =
    httpRequest(method, url, body, headers, noSecrets)
  def httpRequest(
    method: String,
    url: String,
    body: String,
    headers: Map[String, String],
    secretHeaders: Map[String, Classified[String]]
  )(using net: Network): HttpResponse =
    request(net, method, url, Option(body).filter(_.nonEmpty), JsonContentType, headers, secretHeaders)

  def httpPostClassified(url: String, body: Classified[String])(using Network): Classified[String] =
    httpPostClassified(url, body, JsonContentType, noHeaders, noSecrets)
  def httpPostClassified(url: String, body: Classified[String], contentType: String)(using
    Network
  ): Classified[String] =
    httpPostClassified(url, body, contentType, noHeaders, noSecrets)
  def httpPostClassified(
    url: String,
    body: Classified[String],
    contentType: String,
    headers: Map[String, String],
    secretHeaders: Map[String, Classified[String]]
  )(using net: Network): Classified[String] =
    ClassifiedImpl.unwrap(body) match
      case Success(b) =>
        // The raw request, not `httpPost`: its error would carry the response body.
        ClassifiedImpl.fromTry(Try(request(net, "POST", url, Some(b), contentType, headers, secretHeaders).body))
      case Failure(_) => body

  // ── Interface: output ─────────────────────────────────────────────

  /** A printed value as the agent sees it: classified values are masked. */
  private def agentView(x: Any): Any = x match
    case _: Classified[?] => "Classified(***)"
    case other => other

  /** A printed value as the user sees it: classified values are unwrapped. */
  private def userView(x: Any): Any = x match
    case c: Classified[?] => ClassifiedImpl.unwrap(c).fold(e => s"<classified error: ${e.getMessage}>", v => v)
    case other => other

  private def emit(x: Any, suffix: String = ""): Unit =
    output.print(String.valueOf(agentView(x)) + suffix, String.valueOf(userView(x)) + suffix)

  def println(x: Any)(using UserIO): Unit = emit(x, "\n")
  def println()(using UserIO): Unit = output.print("\n", "\n")
  def print(x: Any)(using UserIO): Unit = emit(x)
  def printf(fmt: String, args: Any*)(using UserIO): Unit =
    // Non-classified args keep their real (typed) value so numeric/date conversions (`%d`, `%f`, ...) still work.
    output.print(fmt.format(args.map(agentView)*), fmt.format(args.map(userView)*))

  // ── Interface: talking to the user ────────────────────────────────

  def ask(question: String)(using UserIO): Option[String] = ui.askUser(question, Nil, false)
  def ask(question: String, options: List[String])(using UserIO): Option[String] =
    ui.askUser(question, options, false)
  def ask(question: String, options: List[String], multiple: Boolean)(using UserIO): Option[String] =
    ui.askUser(question, options, multiple)

  def setTodos(items: List[Todo])(using UserIO): Unit =
    todoList = items
    ui.showTodos(items)

  def todos(using UserIO): List[Todo] = todoList

  def markTodo(text: String, status: TodoStatus)(using UserIO): Unit =
    if !todoList.exists(_.text == text) then
      throw IllegalArgumentException(s"No TODO item with text '$text'. Current: ${todoList.map(_.text).mkString(", ")}")
    setTodos(todoList.map(t => if t.text == text then t.copy(status = status) else t))

  /** For the `/todos` command. */
  private[atc] def currentTodos: List[Todo] = todoList

  /** For `/new`: drop the list without announcing it. */
  private[atc] def clearTodos(): Unit = todoList = Nil

  // ── Interface: classified & LLM ───────────────────────────────────

  def classify[T](value: T): Classified[T] = ClassifiedImpl.wrap(value)

  def chat(message: String)(using UserIO): String = llm.chat(message)
  def chat(message: Classified[String]): Classified[String] =
    ClassifiedImpl.unwrap(message) match
      case Success(m) => ClassifiedImpl.fromTry(Try(llm.chatClassified(m)))
      case Failure(_) => message

object Host:
  /** `cat(path)` shows at most this many lines, then says how to see the rest. */
  val CatMaxLines: Int = 400
  /** `cat` cuts a line beyond this many characters (minified files) with a marker. */
  val CatMaxLineChars: Int = 2000
  /** How much stderr `execOutput` quotes when a command fails. */
  val ExecErrorTailChars: Int = 2000
  /** Live `spawn`ed processes per session; beyond it `spawn` asks to `kill()` one. */
  val MaxProcesses: Int = 8
  /** How much of an error body `httpGet`/`httpPost` quote. */
  val HttpErrorBodyChars: Int = 500

  /** Normalize a host for policy matching: lowercase, remove a trailing dot, and
    * convert numeric IP literals to canonical form. IPv4 and IPv4-mapped IPv6
    * addresses use dotted-quad notation. This ensures that alternate forms such
    * as `evil.com.`, `2852039166`, and `[::ffff:169.254.169.254]` cannot bypass an
    * equivalent rule. Literal parsing does not use DNS; ordinary hostnames are
    * returned unchanged after case and trailing-dot normalization. */
  def normalizeHost(host: String): String =
    val h = host.stripSuffix(".").toLowerCase
    // `URI.getHost` returns bracketed IPv6 literals, such as `[::1]`. Canonicalize
    // them so an IPv4-mapped spelling cannot bypass a rule for the IPv4 address.
    val (bare, bracketed) =
      if h.startsWith("[") && h.endsWith("]") then (h.substring(1, h.length - 1), true) else (h, false)
    literalIpAddress(bare)
      .orElse(if bracketed || looksLikeIpv6(bare) then ipv6Literal(bare) else None)
      .getOrElse(bare)

  /** Whether a string contains only IPv6-literal characters: hexadecimal digits,
    * `:`, and `.` for an IPv4-mapped suffix. This guard keeps ordinary hostnames
    * out of the no-DNS literal parser. */
  private def looksLikeIpv6(h: String): Boolean =
    h.contains(':') && h.forall(c => c == ':' || c == '.' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))

  /** Return the canonical form of an IPv6 literal, using dotted-quad IPv4 form
    * for an IPv4-mapped address. For example, `[::ffff:169.254.169.254]` is
    * evaluated as `169.254.169.254`. Strings containing `:` are parsed strictly
    * as literals and never trigger a DNS lookup. */
  private def ipv6Literal(h: String): Option[String] =
    try
      java.net.InetAddress.getByName(h) match
        case v4: java.net.Inet4Address => Some(v4.getHostAddress.nn)
        case v6: java.net.Inet6Address => Some(v6.getHostAddress.nn)
        case _ => None
    catch case _: java.net.UnknownHostException => None

  /** Convert a numeric IPv4 literal with one to four decimal parts into canonical
    * dotted-quad form. These are the no-DNS forms accepted by `InetAddress`:
    * `127.1` means `127.0.0.1`, and `2852039166` means `169.254.169.254`; the final
    * part supplies the remaining bytes. Return `None` for other input. Building
    * the address from bytes avoids a DNS lookup. */
  private[host] def literalIpAddress(h: String): Option[String] =
    val parts = h.split("\\.", -1).toList
    def partValue(p: String): Option[Long] =
      if p.nonEmpty && p.forall(_.isDigit) then
        try Some(java.lang.Long.parseLong(p, 10))
        catch case _: NumberFormatException => None
      else None
    if parts.lengthIs < 1 || parts.lengthIs > 4 then None
    else
      val values = parts.map(partValue)
      if values.exists(_.isEmpty) then None
      else
        val vs = values.flatten
        val lastMax = 1L << (8 * (5 - parts.length)) // the last part covers 5-n bytes
        if vs.init.exists(_ > 255) || vs.last >= lastMax then None
        else
          var addr = vs.last
          for i <- 0 until vs.length - 1 do addr = addr | (vs(i) << (8 * (3 - i)))
          val bytes = Array.tabulate(4)(i => ((addr >> (8 * (3 - i))) & 0xff).toByte)
          try Some(java.net.InetAddress.getByAddress(bytes).nn.getHostAddress.nn)
          catch case _: java.net.UnknownHostException => None

  /** gitignore-flavoured glob over a `/`-separated relative path: `**` spans
    * directories (a leading `**` + `/` also matches none), `*` and `?` stay within
    * a segment, `[...]` is a class (`[!...]` negated), `{a,b}` alternatives. */
  def globRegex(glob: String): scala.util.matching.Regex =
    val sb = StringBuilder("^")
    var i = 0
    var inClass = false
    var inBraces = false
    while i < glob.length do
      val c = glob.charAt(i)
      if inClass then
        if c == ']' then inClass = false
        sb.append(c)
        i += 1
      else if glob.startsWith("**/", i) then
        sb.append("(?:.*/)?")
        i += 3
      else if glob.startsWith("**", i) then
        sb.append(".*")
        i += 2
      else
        c match
          case '*' => sb.append("[^/]*")
          case '?' => sb.append("[^/]")
          case '[' =>
            inClass = true
            sb.append('[')
            if glob.startsWith("[!", i) then
              sb.append('^')
              i += 1
          case '{' => inBraces = true; sb.append("(?:")
          case '}' if inBraces => inBraces = false; sb.append(')')
          case ',' if inBraces => sb.append('|')
          case other => sb.append(java.util.regex.Pattern.quote(other.toString))
        i += 1
    sb.append('$')
    sb.toString.r

  /** Lines of a file plus what is needed to write it back unchanged: its
    * separator (`\r\n` if it uses that, else `\n`) and whether it ended with one
    * (an empty file counts as ending with one, so an insertion gets a newline). */
  def splitLines(content: String): (List[String], String, Boolean) =
    val sep = if content.contains("\r\n") then "\r\n" else "\n"
    if content.isEmpty then (Nil, sep, true)
    else
      val trailing = content.endsWith(sep) || content.endsWith("\n")
      val body = if content.endsWith(sep) then content.dropRight(sep.length) else content.stripSuffix("\n")
      (body.split(java.util.regex.Pattern.quote(sep), -1).toList, sep, trailing)

  /** The lines of an edit's `text`: empty text is no line, one trailing newline is not an extra empty line. */
  def textLines(text: String): List[String] =
    if text.isEmpty then Nil else text.stripSuffix("\n").split("\n", -1).toList

  def joinLines(lines: List[String], sep: String, trailing: Boolean): String =
    if lines.isEmpty then "" else lines.mkString(sep) + (if trailing then sep else "")
