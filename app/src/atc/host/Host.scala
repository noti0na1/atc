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
) extends Interface:

  @volatile private var todoList: List[Todo] = Nil

  // ── paths & permission checks ─────────────────────────────────────

  /** Canonical absolute path: relative to `cwd`, `~` expanded, normalized,
    * symlinks resolved as far as the path exists (a link inside an allowed
    * directory pointing elsewhere is judged by its target). */
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

  // ── file effects shared by FileEntryImpl and the path helpers ─────

  private[atc] def writeFile(scope: ScopeId, p: Path, content: String, append: Boolean): Unit =
    val pm = requireWrite(scope, p, if append then "append" else "write")
    requireNotClassified(pm, p, "write", "writeClassified(path, classify(content))")
    Option(p.getParent).foreach(Files.createDirectories(_))
    if append then
      Files.writeString(p, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    else Files.writeString(p, content, StandardCharsets.UTF_8)

  private[atc] def writeFileBytes(scope: ScopeId, p: Path, content: Array[Byte]): Unit =
    val pm = requireWrite(scope, p, "writeBytes")
    requireNotClassified(pm, p, "writeBytes", "writeClassified(path, classify(content))")
    Option(p.getParent).foreach(Files.createDirectories(_))
    Files.write(p, content)
    ()

  private[atc] def writeClassifiedFile(scope: ScopeId, p: Path, content: String): Unit =
    val pm = requireWrite(scope, p, "writeClassified")
    if !pm.classified then
      throw SecurityException(
        s"Access denied: '$p' is not a classified path; writing classified content there would declassify it."
      )
    Option(p.getParent).foreach(Files.createDirectories(_))
    Files.writeString(p, content, StandardCharsets.UTF_8)

  /** Children the scope may see (entries with no read access, and — when
    * `respectGitignore` is on — the ones git ignores, are omitted). */
  private[atc] def visibleChildren(scope: ScopeId, dir: Path): List[Path] =
    Using.resource(Files.list(dir).nn) { s =>
      s.iterator.nn.asScala.toList.sortBy(_.getFileName.toString).filter { c =>
        try !gitIgnore.ignores(c) && policy.effective(scope, PathPattern.canonical(c)).canRead
        catch case _: Exception => false
      }
    }

  /** Every visible descendant of `dir`, in pre-order. Classified sub-trees are
    * listed but not entered unless `intoClassified`; symlinked directories are
    * never followed. */
  private[atc] def walkPaths(scope: ScopeId, dir: Path, intoClassified: Boolean): List[Path] =
    def descendInto(c: Path): Boolean =
      Files.isDirectory(c) && !Files.isSymbolicLink(c) &&
        (intoClassified || !policy.effective(scope, PathPattern.canonical(c)).classified)
    def go(d: Path): List[Path] =
      visibleChildren(scope, d).flatMap(c => c :: (if descendInto(c) then go(c) else Nil))
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
    val id = policy.requestFile(scopeOf(parent), canonical(path), level, reason)
    try op(using FileSystemImpl(id, this))
    finally policy.closeScope(id)

  def access(path: String)(using fs: FileSystem): FileEntry = fs.access(path)
  def read(path: String)(using fs: FileSystem): String = fs.access(path).read()
  def readLines(path: String)(using fs: FileSystem): List[String] = fs.access(path).readLines()
  def readBytes(path: String)(using fs: FileSystem): Array[Byte] = fs.access(path).readBytes()
  def write(path: String, content: String)(using fs: FileSystem): Unit = fs.access(path).write(content)
  def writeBytes(path: String, content: Array[Byte])(using fs: FileSystem): Unit =
    fs.access(path).writeBytes(content)

  /** Targeted edit: rewrite the file with every occurrence of `target` replaced.
    * Refuses a no-op so a mistaken pattern cannot look like a successful edit. */
  def replace(path: String, target: String, replacement: String)(using fs: FileSystem): Int =
    if target.isEmpty then throw IllegalArgumentException("replace: the target string must not be empty")
    val entry = fs.access(path)
    val before = entry.read()
    val n = countOccurrences(before, target)
    if n == 0 then
      throw IllegalArgumentException(
        s"replace: '$target' does not occur in '${entry.path}', so nothing was changed; read the file and check the exact text (whitespace and indentation included)."
      )
    entry.write(before.replace(target, replacement))
    n

  private def countOccurrences(haystack: String, needle: String): Int =
    var i = haystack.indexOf(needle)
    var n = 0
    while i >= 0 do
      n += 1
      i = haystack.indexOf(needle, i + needle.length)
    n

  def append(path: String, content: String)(using fs: FileSystem): Unit = fs.access(path).append(content)
  def exists(path: String)(using fs: FileSystem): Boolean = fs.access(path).exists
  def isDirectory(path: String)(using fs: FileSystem): Boolean = fs.access(path).isDirectory
  def mkdir(path: String)(using fs: FileSystem): Unit = fs.access(path).mkdir()
  def delete(path: String)(using fs: FileSystem): Unit = fs.access(path).delete()
  def ls(dir: String)(using fs: FileSystem): List[String] = fs.access(dir).children.map(_.path)
  def walk(dir: String)(using fs: FileSystem): List[String] = fs.access(dir).walk().map(_.path)

  private def grepEntry(entry: FileEntry, regex: scala.util.matching.Regex): List[GrepMatch] =
    val buf = collection.mutable.ListBuffer[GrepMatch]()
    entry.forEachLine((line, n) => if regex.findFirstIn(line).isDefined then buf += GrepMatch(entry.path, n, line))
    buf.toList

  def grep(path: String, pattern: String)(using fs: FileSystem): List[GrepMatch] =
    grepEntry(fs.access(path), pattern.r)

  def grepRecursive(dir: String, pattern: String)(using fs: FileSystem): List[GrepMatch] =
    grepRecursive(dir, pattern, "*")
  def grepRecursive(dir: String, pattern: String, glob: String)(using fs: FileSystem): List[GrepMatch] =
    val matcher = FileSystems.getDefault.nn.getPathMatcher(s"glob:$glob").nn
    val regex = pattern.r
    fs.access(dir).walk().flatMap { entry =>
      if entry.isDirectory || entry.isClassified then Nil
      else if matcher.matches(Paths.get(entry.path).nn.getFileName) then grepEntry(entry, regex)
      else Nil
    }

  def find(dir: String, glob: String)(using fs: FileSystem): List[String] =
    val matcher = FileSystems.getDefault.nn.getPathMatcher(s"glob:$glob").nn
    fs.access(dir).walk().flatMap { entry =>
      if entry.isDirectory then Nil
      else if matcher.matches(Paths.get(entry.path).nn.getFileName) then List(entry.path)
      else Nil
    }

  def readClassified(path: String)(using fs: FileSystem): Classified[String] = fs.access(path).readClassified()
  def writeClassified(path: String, content: Classified[String])(using fs: FileSystem): Unit =
    fs.access(path).writeClassified(content)

  // ── Interface: processes ──────────────────────────────────────────

  def requestExec[T](commands: Set[String])(op: Exec ?=> T)(using UserIO, Exec): T =
    requestExec(commands, "")(op)
  def requestExec[T](commands: Set[String], reason: String)(op: Exec ?=> T)(using user: UserIO, parent: Exec): T =
    val patterns = commands.toList.map(_.trim).filter(_.nonEmpty)
    val id = policy.requestExec(scopeOf(parent), patterns, reason)
    try op(using ExecImpl(id))
    finally policy.closeScope(id)

  def exec(command: String)(using Exec, FileSystem): ProcessResult = exec(command, Nil, None, Host.ExecTimeoutMs)
  def exec(command: String, args: List[String])(using Exec, FileSystem): ProcessResult =
    exec(command, args, None, Host.ExecTimeoutMs)
  def exec(command: String, args: List[String], workingDir: Option[String])(using Exec, FileSystem): ProcessResult =
    exec(command, args, workingDir, Host.ExecTimeoutMs)
  def exec(command: String, args: List[String], workingDir: Option[String], timeoutMs: Long)(using
    ex: Exec,
    fs: FileSystem
  ): ProcessResult =
    val argv = command :: args
    val line = argv.mkString(" ")
    policy.commandDenied(line) match
      case Some(pattern) =>
        // Refused by the configuration: asking the user is not an option, so the
        // message must not point at `requestExec` (which would fail too).
        throw SecurityException(
          s"Access denied: command '$line' is refused by the configuration (denyCommands pattern '$pattern'). It cannot be granted; do not retry it or work around it, tell the user instead."
        )
      case None =>
        if !policy.commandAllowed(scopeOf(ex), line) then
          throw SecurityException(
            s"""Access denied: command '$line' matches no permitted pattern. Use requestExec(Set("$command *"), reason) { ... } to ask the user."""
          )
    // A command observes the directory it runs in (`git status` lists file names),
    // so the working directory must be readable and unclassified for the FileSystem
    // capability in scope. (What the command itself reads is up to the OS: the
    // command pattern is the user's decision.) `cwd` is canonicalized like any
    // other path so a symlinked project directory matches the rules.
    val dir = workingDir.fold(canonical("."))(canonical)
    val pm = requireRead(scopeOf(fs), dir, "running a command in")
    requireNotClassified(pm, dir, "running a command there", "a working directory outside it")
    val pb = ProcessBuilder(argv.asJava).directory(dir.toFile).nn
    Processes.run(pb, command, timeoutMs)

  def execOutput(command: String)(using Exec, FileSystem): String = exec(command).stdout
  def execOutput(command: String, args: List[String])(using Exec, FileSystem): String = exec(command, args).stdout

  // ── Interface: network ────────────────────────────────────────────

  def requestNetwork[T](hosts: Set[String])(op: Network ?=> T)(using UserIO, Network): T =
    requestNetwork(hosts, "")(op)
  def requestNetwork[T](hosts: Set[String], reason: String)(op: Network ?=> T)(using user: UserIO, parent: Network): T =
    val patterns = hosts.toList.map(_.trim.toLowerCase).filter(_.nonEmpty)
    val id = policy.requestNet(scopeOf(parent), patterns, reason)
    try op(using NetworkImpl(id))
    finally policy.closeScope(id)

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
    val host = Option(uri.getHost).getOrElse(throw SecurityException(s"Invalid URL (no host): $url"))
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
    // Classified header values are unwrapped here and sent, never shown to the agent.
    secretHeaders.foreach((k, c) => b.header(k, ClassifiedImpl.get(c)))
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

  def httpGet(url: String)(using Network): String = httpGet(url, noHeaders, noSecrets)
  def httpGet(url: String, headers: Map[String, String])(using Network): String = httpGet(url, headers, noSecrets)
  def httpGet(url: String, headers: Map[String, String], secretHeaders: Map[String, Classified[String]])(using
    net: Network
  ): String =
    request(net, "GET", url, None, JsonContentType, headers, secretHeaders).body

  def httpPost(url: String, body: String)(using Network): String =
    httpPost(url, body, JsonContentType, noHeaders, noSecrets)
  def httpPost(url: String, body: String, contentType: String)(using Network): String =
    httpPost(url, body, contentType, noHeaders, noSecrets)
  def httpPost(
    url: String,
    body: String,
    contentType: String,
    headers: Map[String, String],
    secretHeaders: Map[String, Classified[String]]
  )(using net: Network): String =
    request(net, "POST", url, Some(body), contentType, headers, secretHeaders).body

  def httpRequest(method: String, url: String)(using Network): HttpResponse =
    httpRequest(method, url, "", noHeaders, noSecrets)
  def httpRequest(method: String, url: String, body: String)(using Network): HttpResponse =
    httpRequest(method, url, body, noHeaders, noSecrets)
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
      case Success(b) => ClassifiedImpl.fromTry(Try(httpPost(url, b, contentType, headers, secretHeaders)))
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
  /** Default wall-clock limit for one `exec`; overridable per call. */
  val ExecTimeoutMs: Long = 120_000L
