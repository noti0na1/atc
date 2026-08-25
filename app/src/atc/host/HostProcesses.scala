package atc.host

import atc.Main
import atc.lib.*
import atc.perms.ScopeId

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** Command execution and spawned-process lifecycle supplied by [[Host]]. */
private[host] trait HostProcesses:
  self: Host =>

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

  /** A command line ready to start: parsed, authorized, and represented as one
    * `ProcessBuilder` per pipeline stage. Shared by `exec` and `spawn`. */
  private final case class Prepared(pbs: List[ProcessBuilder], stageLines: List[String], line: String)

  private def withArgs(command: String, args: Seq[String]): Processes.Pipeline =
    val pipeline = Processes.parsePipeline(command)
    if args.isEmpty then pipeline
    else if pipeline.isSimple then
      val stage = pipeline.stages.head
      pipeline.copy(stages = List(stage.copy(argv = stage.argv ++ args)))
    else
      throw IllegalArgumentException(
        "exec(command, args, ...): `command` must be one program when args are given; write a pipeline or redirection in the one-line form exec(\"...\")"
      )

  private def authorizeCommands(pipeline: Processes.Pipeline, scope: ScopeId): Unit =
    pipeline.stages.foreach { stage =>
      policy.commandDenied(stage.line).foreach { pattern =>
        throw SecurityException(
          s"Access denied: command '${stage.line}' is refused by the configuration (denyCommands pattern '$pattern'). It cannot be granted; do not retry it or work around it, tell the user instead."
        )
      }
    }

    val missing = pipeline.stages.filterNot(stage => policy.commandAllowed(scope, stage.line))
    if missing.nonEmpty then
      val target =
        if pipeline.stages.lengthIs == 1 then s"command '${missing.head.line}'"
        else
          val stages = missing.map(stage => s"'${stage.line}'").mkString(", ")
          s"stage${if missing.lengthIs > 1 then "s" else ""} $stages of the pipeline"
      val patterns = missing.map(stage => Host.scalaString(stage.line)).mkString(", ")
      throw SecurityException(
        s"Access denied: $target matches no permitted pattern. Use requestExec(Set($patterns), reason) { ... } to ask the user."
      )

  private def commandDirectory(path: String, fs: FileSystem): Path =
    val dir = canonical(path)
    val permission = requireRead(scopeOf(fs), dir, "running a command in")
    requireNotClassified(permission, dir, "running a command there", "a working directory outside it")
    dir

  private def inputRedirect(path: String, fs: FileSystem): Path =
    val input = canonical(path)
    val permission = requireRead(scopeOf(fs), input, "feeding a command from")
    requireNotClassified(permission, input, "feeding a command from", "an unclassified file")
    input

  private def outputRedirect(path: String, fs: FileSystem): Path =
    val target = canonical(path)
    val permission = requireWrite(scopeOf(fs), target, "redirecting a command's output to")
    requireNotClassified(permission, target, "redirecting a command's output to", "an unclassified file")
    ensureParent(target)
    target

  private def processBuilders(pipeline: Processes.Pipeline, dir: Path): List[ProcessBuilder] =
    pipeline.stages.map { stage =>
      val argv = Processes.executableArgv(stage.argv, dir)
      val builder = ProcessBuilder(argv.asJava).directory(dir.toFile).nn
      // Windows launchers may carry the original CLI (including a prompt) in
      // these variables. It belongs to ATC, not commands the agent starts.
      builder.environment().nn.keySet().nn.removeIf(Main.isInternalEnvironment)
      if stage.mergeErr then builder.redirectErrorStream(true)
      builder
    }

  private def prepare(command: String, args: Seq[String], options: ExecOptions)(using
    ex: Exec,
    fs: FileSystem
  ): Prepared =
    val pipeline = withArgs(command, args)
    if options.stdin.nonEmpty && pipeline.stdinFile.isDefined then
      throw IllegalArgumentException(
        "exec: both ExecOptions(stdin = ...) and '< file' would feed the command; use one of them"
      )
    authorizeCommands(pipeline, scopeOf(ex))

    val dir = commandDirectory(options.workingDir, fs)
    val stdinFile = pipeline.stdinFile.map(inputRedirect(_, fs))
    val stdoutFile = pipeline.stdoutFile.map(outputRedirect(_, fs))
    val pbs = processBuilders(pipeline, dir)
    stdinFile.foreach(path => pbs.head.redirectInput(path.toFile))
    stdoutFile.foreach { path =>
      val file = path.toFile
      pbs.last.redirectOutput(if pipeline.append then ProcessBuilder.Redirect.appendTo(file)
      else ProcessBuilder.Redirect.to(file))
    }
    Prepared(pbs, pipeline.stages.map(_.line), pipeline.line)

  def exec(command: String, args: Seq[String], options: ExecOptions)(using ex: Exec, fs: FileSystem): ProcessResult =
    if options.timeoutMs <= 0 then
      throw IllegalArgumentException(s"exec: timeoutMs must be positive (got ${options.timeoutMs})")
    val prepared = prepare(command, args, options)
    val port = output
    val live = new Processes.LiveOutput:
      def begin(): Unit = port.commandRunning(prepared.line)
      def output(text: String): Unit = port.commandOutput(text)
    output.whileCommandRuns(
      Processes.run(
        prepared.pbs,
        prepared.stageLines,
        prepared.line,
        options.timeoutMs,
        Some(live),
        options.stdin
      )
    )

  // The registry is per host; ids are never reused within a session.
  private val spawned = scala.collection.mutable.LinkedHashMap[Int, ProcessImpl]()
  private var nextProcessId = 0

  def spawn(command: String)(using Exec, FileSystem): Process = spawn(command, ExecOptions())

  def spawn(command: String, options: ExecOptions)(using ex: Exec, fs: FileSystem): Process =
    val prepared = prepare(command, Nil, options)
    spawned.synchronized:
      reapProcesses()
      if spawned.size >= Host.MaxProcesses then
        throw IllegalStateException(
          s"spawn: ${Host.MaxProcesses} processes are already running (${spawned.keys.map(id => s"p$id").mkString(", ")}); kill() one first"
        )
      nextProcessId += 1
      val id = nextProcessId
      val port = output
      val managed = Processes.ManagedProcess.start(
        prepared.pbs,
        prepared.stageLines,
        prepared.line,
        options.stdin,
        closeStdinAfter = false,
        live = None,
        keepHead = false,
        onExit = code => port.processExited(id, code),
      )
      val handle = ProcessImpl(id, managed, output, scopeOf(ex), policy)
      spawned(id) = handle
      output.processStarted(id, prepared.line)
      handle

  /** Return the live processes visible from the caller's scope. */
  def runningProcesses(using ex: Exec): List[Process] = spawned.synchronized:
    reapProcesses()
    val caller = scopeOf(ex)
    spawned.values.toList.filter(process => policy.scopeVisibleFrom(caller, process.scope))

  private def reapProcesses(): Unit = spawned.filterInPlace((_, process) => process.managed.isAlive)

  /** Kill processes owned by a closing one-time permission scope. */
  private[host] def killProcessesInScope(id: ScopeId): Unit = spawned.synchronized:
    spawned.values.toList.filter(_.scope == id).foreach { process =>
      try process.managed.kill()
      catch case _: Exception => ()
    }
    reapProcesses()

  /** Kill every spawned process at session end or for `/kill all`. */
  private[atc] def killProcesses(): Unit = spawned.synchronized:
    spawned.values.foreach(_.managed.kill())
    spawned.clear()

  /** `/kill`: `p3`, `3`, or `all`; returns a user-facing result. */
  private[atc] def killProcess(ref: String): String = spawned.synchronized:
    reapProcesses()
    ref.trim.toLowerCase match
      case "" | "all" =>
        val count = spawned.size
        killProcesses()
        if count == 0 then "no process is running" else s"killed $count process${if count == 1 then "" else "es"}"
      case value =>
        value.stripPrefix("p").toIntOption.flatMap(spawned.get) match
          case Some(process) =>
            process.managed.kill()
            spawned.remove(process.id)
            s"killed p${process.id} (${process.commandLine})"
          case None => s"no running process '$ref' (see /ps)"

  /** `/ps`: one line per live process. */
  private[atc] def processSummary: String = spawned.synchronized:
    reapProcesses()
    if spawned.isEmpty then "no process is running"
    else spawned.values.map(process => s"p${process.id}  ${process.commandLine}").mkString("\n")

  def execOutput(command: String)(using Exec, FileSystem): String = execOutput(command, Nil, ExecOptions())

  def execOutput(command: String, args: Seq[String])(using Exec, FileSystem): String =
    execOutput(command, args, ExecOptions())

  def execOutput(command: String, args: Seq[String], options: ExecOptions)(using Exec, FileSystem): String =
    val result = exec(command, args, options)
    if result.exitCode != 0 then
      val error = result.stderr.trim
      val tail = if error.isEmpty then "" else s"; stderr: ${error.takeRight(Host.ExecErrorTailChars)}"
      throw RuntimeException(
        s"'${withArgs(command, args).line}' exited with ${result.exitCode}$tail (use exec(...) to inspect a failure)"
      )
    result.stdout
