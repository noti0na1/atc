package atc.evaluator

import atc.host.ClassifiedImpl
import atc.lib.*

import java.io.PrintStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ConcurrentHashMap, ExecutionException, Executors}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

// The agent API as the evaluator process implements it. Every effect is a call to the host,
// which checks it as it checks in-process calls; capabilities travel as the host's scope ids.
// Classified values, callback results and parallel tasks stay here.

private[evaluator] final class RemoteFileSystem(val scope: Long) extends FileSystem

/** `network` is the scope of the `Network` a `withNetwork` block derived it from. */
private[evaluator] final class RemoteExec(val scope: Long, val network: Option[Long]) extends Exec
private[evaluator] final class RemoteNetwork(val scope: Long) extends Network

private[evaluator] final class RemoteClassified[+T](val value: Try[T]) extends Classified[T]:
  def map[B](op: T => B): Classified[B] = RemoteClassified(value.flatMap(v => ClassifiedImpl.attempt(op(v))))
  override def toString: String = "Classified(***)"

private[evaluator] object RemoteClassified:
  def unwrap[T](c: Classified[T]): Try[T] = c match
    case remote: RemoteClassified[T] @unchecked => remote.value
    case other => throw SecurityException(s"Unknown Classified implementation: ${other.getClass.getName}")

/** Raised in the host when a callback failed here; the original throwable stays here. */
private[evaluator] final class CallbackFailed extends RuntimeException("the callback failed in the evaluator")

private[evaluator] final class RemoteProcess(val id: Int, val commandLine: String, scope: Long, host: RemoteHost)
    extends Process:
  private def call(op: String)(args: Encoder => Unit): Decoder =
    host.channel.call("process." + op) { e => e.long(scope).int(id); args(e) }
  private def call(op: String): Decoder = call(op)(_ => ())
  def isAlive: Boolean = call("isAlive").bool()
  def exitCode: Option[Int] = call("exitCode").optionalLong().map(_.toInt)
  def send(text: String): Unit = call("send")(_.string(text))
  def sendLine(line: String): Unit = call("sendLine")(_.string(line))
  def closeStdin(): Unit = call("closeStdin")
  def read(): String = call("read").string()
  def readErr(): String = call("readErr").string()
  def readUntil(regex: String, timeoutMs: Long): String =
    call("readUntil")(_.string(regex).long(timeoutMs)).string()
  def waitFor(timeoutMs: Long): Option[ProcessResult] =
    val d = call("waitFor")(_.long(timeoutMs))
    Option.when(d.bool())(ProcessResult(d.int(), d.string(), d.string()))
  def kill(): Unit = call("kill")
  override def toString: String = s"Process(p$id, \"$commandLine\")"

private[evaluator] final class RemoteHost(val channel: Channel) extends Interface, Derivations:
  /** The session's capture stream (what the model sees); set once the session exists. */
  @volatile var printStream: PrintStream = System.err

  private val callbacks = ConcurrentHashMap[java.lang.Long, Long => Unit]()
  private val callbackIds = AtomicLong()

  private def call(method: String)(args: Encoder => Unit): Decoder = channel.call(method)(args)

  private def scopeOf(capability: AnyRef): Long = capability match
    case fs: RemoteFileSystem => fs.scope
    case ex: RemoteExec => ex.scope
    case net: RemoteNetwork => net.scope
    case other => throw SecurityException(s"Unknown capability implementation: ${other.getClass.getName}")

  private def execOf(capability: Exec): RemoteExec = capability match
    case ex: RemoteExec => ex
    case other => throw SecurityException(s"Unknown capability implementation: ${other.getClass.getName}")

  /** A failure goes without its message: computing it would run the agent's code outside the
    * computation, where its failing or hanging would be seen. */
  private def encodeClassified(e: Encoder, content: Classified[String]): Unit = RemoteClassified.unwrap(content) match
    case Success(value) => e.bool(true).string(value)
    case Failure(_) => e.bool(false)
  private def encodeOptions(e: Encoder, o: ExecOptions): Unit = e.string(o.workingDir).long(o.timeoutMs).string(o.stdin)
  private def decodeResult(d: Decoder): ProcessResult = ProcessResult(d.int(), d.string(), d.string())
  private def decodeMatches(d: Decoder): List[GrepMatch] =
    List.fill(d.int())(GrepMatch(d.string(), d.int(), d.string()))
  private def encodeExec(e: Encoder, ex: Exec): Encoder =
    val remote = execOf(ex)
    e.long(remote.scope).optionalLong(remote.network)

  // ── callbacks: the host runs a block inside a scope it opened, on this same thread ──

  /** The host invokes callback `id` with the scope it opened for it. */
  def runCallback(id: Long, scope: Long): Unit =
    Option(callbacks.get(id)).getOrElse(throw IllegalStateException(s"no callback $id"))(scope)

  private def withCallback[T](method: String)(args: Encoder => Unit)(op: Long => T): T =
    var result: Option[T] = None
    var failure: Throwable | Null = null
    val id = callbackIds.incrementAndGet()
    callbacks.put(
      id,
      scope =>
        try result = Some(op(scope))
        catch
          case t: Throwable =>
            failure = t // the original, fatal ones included, stays here
            throw CallbackFailed()
    )
    try call(method) { e => args(e); e.long(id) }
    catch case NonFatal(_) if failure != null => throw failure.nn
    finally callbacks.remove(id)
    if failure != null then throw failure.nn
    result.get

  // ── derivations ──
  def fileSystem(using IOCap): FileSystem = RemoteFileSystem(0)
  def readOnlyFileSystem(using IOCap): FileSystem = RemoteFileSystem(0)
  def processes(using IOCap): Exec = RemoteExec(0, None)
  def readOnlyProcesses(using IOCap): Exec = RemoteExec(0, None)
  def network(using IOCap): Network = RemoteNetwork(0)

  // ── files ──
  def requestFiles[T, C <: caps.CapSet](path: String)(using UserIO, FileSystem)(op: FileSystem ?=> T): T =
    requestFiles(path, Access.Read, "")(op)
  def requestFiles[T, C <: caps.CapSet](path: String, access: Access)(using
    UserIO,
    FileSystem
  )(op: FileSystem ?=> T): T =
    requestFiles(path, access, "")(op)
  def requestFiles[T, C <: caps.CapSet](path: String, access: Access, reason: String)(using
    user: UserIO,
    parent: FileSystem
  )(op: FileSystem ?=> T): T =
    withCallback("requestFiles")(_.long(scopeOf(parent)).string(path).int(access.ordinal).string(reason)): scope =>
      op(using RemoteFileSystem(scope))

  def writeClassified(path: String, content: Classified[String])(using fs: FileSystem): Unit =
    call("writeClassified") { e => e.long(scopeOf(fs)).string(path); encodeClassified(e, content) }
  def read(path: String)(using fs: FileSystem): String = call("read")(_.long(scopeOf(fs)).string(path)).string()
  def readLines(path: String)(using fs: FileSystem): List[String] =
    call("readLines")(_.long(scopeOf(fs)).string(path)).strings()
  def readRange(path: String, from: Int, to: Int)(using fs: FileSystem): String =
    call("readRange")(_.long(scopeOf(fs)).string(path).int(from).int(to)).string()
  def cat(path: String)(using fs: FileSystem, user: UserIO): Unit =
    call("cat")(_.long(scopeOf(fs)).string(path).bool(false))
  def cat(path: String, from: Int, to: Int)(using fs: FileSystem, user: UserIO): Unit =
    call("cat")(_.long(scopeOf(fs)).string(path).bool(true).int(from).int(to))
  def readBytes(path: String)(using fs: FileSystem): Array[Byte] =
    call("readBytes")(_.long(scopeOf(fs)).string(path)).bytes()
  def write(path: String, content: String)(using fs: FileSystem): Unit =
    call("write")(_.long(scopeOf(fs)).string(path).string(content))
  def writeBytes(path: String, content: Array[Byte])(using fs: FileSystem): Unit =
    call("writeBytes")(_.long(scopeOf(fs)).string(path).bytes(content))
  def move(from: String, to: String)(using fs: FileSystem): Unit =
    call("move")(_.long(scopeOf(fs)).string(from).string(to))
  def copy(from: String, to: String)(using fs: FileSystem): Unit =
    call("copy")(_.long(scopeOf(fs)).string(from).string(to))
  def sed(path: String, pattern: String, replacement: String)(using fs: FileSystem): Int =
    call("sed")(_.long(scopeOf(fs)).string(path).string(pattern).string(replacement)).int()
  def replaceExact(path: String, expected: String, replacement: String)(using fs: FileSystem): Unit =
    call("replaceExact")(_.long(scopeOf(fs)).string(path).string(expected).string(replacement))
  def quote(text: String): String = java.util.regex.Pattern.quote(text).nn
  def quoteReplacement(text: String): String = java.util.regex.Matcher.quoteReplacement(text).nn
  def replaceLines(path: String, from: Int, to: Int, text: String)(using fs: FileSystem): String =
    call("replaceLines")(_.long(scopeOf(fs)).string(path).int(from).int(to).string(text)).string()
  def insertLines(path: String, before: Int, text: String)(using fs: FileSystem): Unit =
    call("insertLines")(_.long(scopeOf(fs)).string(path).int(before).string(text))
  def append(path: String, content: String)(using fs: FileSystem): Unit =
    call("append")(_.long(scopeOf(fs)).string(path).string(content))
  def exists(path: String)(using fs: FileSystem): Boolean = call("exists")(_.long(scopeOf(fs)).string(path)).bool()
  def isDirectory(path: String)(using fs: FileSystem): Boolean =
    call("isDirectory")(_.long(scopeOf(fs)).string(path)).bool()
  def mkdir(path: String)(using fs: FileSystem): Unit = call("mkdir")(_.long(scopeOf(fs)).string(path))
  def delete(path: String)(using fs: FileSystem): Unit = call("delete")(_.long(scopeOf(fs)).string(path))
  def ls(dir: String)(using fs: FileSystem): List[String] = call("ls")(_.long(scopeOf(fs)).string(dir)).strings()
  def walk(dir: String)(using fs: FileSystem): List[String] = call("walk")(_.long(scopeOf(fs)).string(dir)).strings()
  def grep(path: String, pattern: String)(using fs: FileSystem): List[GrepMatch] =
    decodeMatches(call("grep")(_.long(scopeOf(fs)).string(path).string(pattern)))
  def grepRecursive(dir: String, pattern: String)(using fs: FileSystem): List[GrepMatch] =
    decodeMatches(call("grepRecursive")(_.long(scopeOf(fs)).string(dir).string(pattern).optionalString(None)))
  def grepRecursive(dir: String, pattern: String, glob: String)(using fs: FileSystem): List[GrepMatch] =
    decodeMatches(call("grepRecursive")(_.long(scopeOf(fs)).string(dir).string(pattern).optionalString(Some(glob))))
  def search(dir: String, pattern: String, glob: String, o: SearchOptions)(using fs: FileSystem): SearchResult =
    val d = call("search"): e =>
      e.long(scopeOf(fs)).string(dir).string(pattern).string(glob)
      e.int(o.maxMatches).int(o.maxFiles).int(o.maxLinesPerFile).int(o.maxLineChars).int(o.maxCharsPerFile)
    SearchResult(decodeMatches(d), d.int(), d.bool())
  def find(dir: String, glob: String)(using fs: FileSystem): List[String] =
    call("find")(_.long(scopeOf(fs)).string(dir).string(glob)).strings()

  // ── commands ──
  def requestExec[T](commands: Iterable[String])(op: Exec ?=> T)(using UserIO, Exec): T = requestExec(commands, "")(op)
  def requestExec[T](commands: Iterable[String], reason: String)(op: Exec ?=> T)(using user: UserIO, parent: Exec): T =
    val network = execOf(parent).network
    withCallback("requestExec")(e => encodeExec(e, parent).strings(commands).string(reason)): scope =>
      op(using RemoteExec(scope, network))
  def exec(command: String)(using Exec, FileSystem): ProcessResult = exec(command, Nil, ExecOptions())
  def exec(command: String, args: Seq[String])(using Exec, FileSystem): ProcessResult =
    exec(command, args, ExecOptions())
  def exec(command: String, args: Seq[String], options: ExecOptions)(using ex: Exec, fs: FileSystem): ProcessResult =
    runCommand("exec", command, args, options, ex, fs)
  def execOutput(command: String)(using Exec, FileSystem): String = execOutput(command, Nil, ExecOptions())
  def execOutput(command: String, args: Seq[String])(using Exec, FileSystem): String =
    execOutput(command, args, ExecOptions())
  def execOutput(command: String, args: Seq[String], options: ExecOptions)(using ex: Exec, fs: FileSystem): String =
    call("execOutput") { e =>
      encodeExec(e, ex).long(scopeOf(fs)).string(command).strings(args); encodeOptions(e, options)
    }
      .string()
  def execReadOnly(command: String)(using Exec, FileSystem): ProcessResult = execReadOnly(command, Nil, ExecOptions())
  def execReadOnly(command: String, args: Seq[String])(using Exec, FileSystem): ProcessResult =
    execReadOnly(command, args, ExecOptions())
  def execReadOnly(command: String, args: Seq[String], options: ExecOptions)(using
    ex: Exec,
    fs: FileSystem
  ): ProcessResult =
    runCommand("execReadOnly", command, args, options, ex, fs)
  private def runCommand(
    method: String,
    line: String,
    args: Seq[String],
    options: ExecOptions,
    ex: Exec,
    fs: FileSystem
  ) =
    decodeResult(call(method) { e =>
      encodeExec(e, ex).long(scopeOf(fs)).string(line).strings(args)
      encodeOptions(e, options)
    })
  def spawn(command: String)(using ex: Exec, fs: FileSystem): Process = spawn(command, ExecOptions())
  def spawn(command: String, options: ExecOptions)(using ex: Exec, fs: FileSystem): Process =
    val d = call("spawn") { e => encodeExec(e, ex).long(scopeOf(fs)).string(command); encodeOptions(e, options) }
    RemoteProcess(d.int(), d.string(), scopeOf(ex), this)
  def runningProcesses(using ex: Exec): List[Process] =
    val d = call("runningProcesses")(_.long(scopeOf(ex)))
    List.fill(d.int())(RemoteProcess(d.int(), d.string(), scopeOf(ex), this))
  def withNetwork[T](op: Exec ?=> T)(using ex: Exec, net: Network): T =
    // The host checks the mode and the network scope when a command uses it.
    op(using RemoteExec(scopeOf(ex), Some(scopeOf(net))))

  // ── network ──
  def requestNetwork[T](hosts: Iterable[String])(op: Network ?=> T)(using UserIO, Network): T =
    requestNetwork(hosts, "")(op)
  def requestNetwork[T](hosts: Iterable[String], reason: String)(op: Network ?=> T)(using
    user: UserIO,
    parent: Network
  ): T =
    withCallback("requestNetwork")(_.long(scopeOf(parent)).strings(hosts).string(reason))(scope =>
      op(using RemoteNetwork(scope))
    )

  private def http(
    form: String,
    method: String,
    url: String,
    body: Option[String],
    contentType: Option[String],
    headers: Map[String, String],
    net: Network,
  ): Decoder =
    call("http") { e =>
      e.long(scopeOf(net)).string(form).string(method).string(url).optionalString(body).optionalString(contentType)
      e.stringMap(headers)
    }
  def httpGet(url: String)(using net: Network): String = http("get", "GET", url, None, None, Map(), net).string()
  def httpGet(url: String, headers: Map[String, String])(using net: Network): String =
    http("get", "GET", url, None, None, headers, net).string()
  def httpPost(url: String, body: String)(using net: Network): String =
    http("post", "POST", url, Some(body), None, Map(), net).string()
  def httpPost(url: String, body: String, contentType: String)(using net: Network): String =
    http("post", "POST", url, Some(body), Some(contentType), Map(), net).string()
  def httpPost(url: String, body: String, contentType: String, headers: Map[String, String])(using
    net: Network
  ): String =
    http("post", "POST", url, Some(body), Some(contentType), headers, net).string()
  def httpRequest(method: String, url: String)(using net: Network): HttpResponse =
    response(http("request", method, url, None, None, Map(), net))
  def httpRequest(method: String, url: String, body: String)(using net: Network): HttpResponse =
    response(http("request", method, url, Some(body), None, Map(), net))
  def httpRequest(method: String, url: String, body: String, headers: Map[String, String])(using
    net: Network
  ): HttpResponse =
    response(http("request", method, url, Some(body), None, headers, net))
  private def response(d: Decoder): HttpResponse = HttpResponse(d.int(), d.string())

  // ── concurrency: tasks are closures here; each task thread is its own conversation ──
  def parallel[A, C <: caps.CapSet](tasks: Seq[() => A]): List[A] =
    if tasks.isEmpty then Nil
    else
      val pool = Executors.newFixedThreadPool(math.min(tasks.size, RemoteHost.MaxParallel)).nn
      try
        val futures = tasks.toList.map(task => pool.submit[A](() => task()).nn)
        val outcomes = futures.map(future => Try(future.get()))
        val failures = outcomes.collect:
          case Failure(wrapped: ExecutionException) => Option(wrapped.getCause).getOrElse(wrapped)
          case Failure(other) => other
        failures.find(!NonFatal(_)).orElse(failures.headOption).foreach(throw _)
        outcomes.collect { case Success(value) => value }
      finally pool.shutdownNow()

  // ── output: rendered here, the model's view into the capture and the user's to the host ──
  private def agentView(value: Any): Any = value match
    case _: Classified[?] => "Classified(***)"
    case other => other

  /** A classified value rendered for the user. Rendering runs agent code (`toString`), so a
    * failure becomes user-only text instead of an exception the model would see. */
  private def userView(value: Any): Any = value match
    case classified: Classified[?] =>
      def errorText(error: Throwable): String =
        try Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.toString)
        catch case NonFatal(_) => "(error details could not be rendered)"
      RemoteClassified.unwrap(classified) match
        case Success(plain) =>
          try Option(String.valueOf(plain)).getOrElse("null")
          catch case NonFatal(error) => s"<classified rendering failed: ${errorText(error)}>"
        case Failure(error) => s"<classified error: ${errorText(error)}>"
    case other => other

  private def emit(agentText: String, userText: String): Unit =
    printStream.print(agentText)
    call("show")(_.string(agentText).string(userText))
    ()
  def println(value: Any)(using UserIO): Unit =
    emit(String.valueOf(agentView(value)) + "\n", String.valueOf(userView(value)) + "\n")
  def println()(using UserIO): Unit = emit("\n", "\n")
  def print(value: Any)(using UserIO): Unit = emit(String.valueOf(agentView(value)), String.valueOf(userView(value)))
  def printf(format: String, args: Any*)(using UserIO): Unit =
    emit(format.format(args.map(agentView)*), format.format(args.map(userView)*))

  // ── the user ──
  def ask(question: String)(using UserIO): Option[String] = ask(question, Nil, false)
  def ask(question: String, options: List[String])(using UserIO): Option[String] = ask(question, options, false)
  def ask(question: String, options: List[String], multiple: Boolean)(using UserIO): Option[String] =
    call("ask")(_.string(question).strings(options).bool(multiple)).optionalString()
  def setTodos(items: List[Todo])(using UserIO): Unit =
    call("setTodos") { e => e.int(items.size); items.foreach(t => e.string(t.text).int(t.status.ordinal)) }
  def todos(using UserIO): List[Todo] =
    val d = call("todos")(_ => ())
    List.fill(d.int())(Todo(d.string(), TodoStatus.fromOrdinal(d.int())))
  def markTodo(text: String, status: TodoStatus)(using UserIO): Unit =
    call("markTodo")(_.string(text).int(status.ordinal))
  def setTaskNotes(notes: TaskNotes)(using UserIO): Unit =
    call("setTaskNotes")(
      _.string(notes.goal).strings(notes.constraints).strings(notes.completed).strings(notes.remaining)
    )
  def taskNotes(using UserIO): TaskNotes =
    val d = call("taskNotes")(_ => ())
    TaskNotes(d.string(), d.strings(), d.strings(), d.strings())

  def classify[T](value: T): Classified[T] = RemoteClassified(Success(value))

  // The block runs here; the host opens and closes its sealed scope, and the result stays here.
  def classified[T](using
    fs: FileSystem,
    ex: Exec
  )(op: (Sealed, FileSystem, Exec) ?=> T)
    : Classified[T] =
    RemoteClassified(ClassifiedImpl.attempt(withCallback("classified")(_.long(scopeOf(fs))): scope =>
      op(using new Sealed {}, RemoteFileSystem(scope), RemoteExec(scope, None))))

  extension [T](c: Classified[T]) def reveal(using Sealed): T = RemoteClassified.unwrap(c).get

  // ── models: the clients and their keys stay in the host ──
  def chat(message: String)(using UserIO): String = call("chat")(_.string(message)).string()
  def classifiedChat(message: String): String = call("classifiedChat")(_.string(message)).string()

private[evaluator] object RemoteHost:
  val MaxParallel: Int = 8
