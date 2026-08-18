package atc.lib

import language.experimental.captureChecking
import caps.*

// ─── Classified data ─────────────────────────────────────────────────────────

/** Confidential data. You can compute with it, but you cannot look at it.
 *
 *  - `toString` never reveals the value (prints `Classified(***)`).
 *  - `map`/`flatMap` only accept **pure** functions (`T -> U`): no capabilities
 *    (`IOCap`, `FileSystem`, `Exec`, `Network`) may be captured, so the content
 *    cannot be printed, written, sent, or otherwise leaked.
 *  - The only sinks are: `writeClassified` (into a classified file),
 *    `println` (the human user sees the content, you see `Classified(***)`),
 *    `chat(Classified[String])` (the safe model), and `httpPostClassified` /
 *    `secretHeaders` (an allow-listed host). */
@assumeSafe
abstract class Classified[+T] private[atc] ():
  def map[B](op: T ->{any.rd} B): Classified[B]
  def flatMap[B](op: T ->{any.rd} Classified[B]): Classified[B]
  /** Combine two classified values. */
  def zip[B](that: Classified[B]): Classified[(T, B)] = flatMap(a => that.map(b => (a, b)))

// ─── Capabilities ────────────────────────────────────────────────────────────

/** The root I/O capability. It is available at the top level as `given io: IOCap`
 *  and every default capability (`fs`, `ex`, `net`) captures it. Functions that
 *  do not capture `io` (or anything derived from it) are pure and may be used
 *  inside `Classified.map`. */
@assumeSafe
class IOCap private[atc] () extends caps.SharedCapability

/** Level of access to a file or directory. `Write` includes `Read`. */
@assumeSafe
enum Access:
  case Read, Write
@assumeSafe
object Access

/** Capability to use the file system, with a set of permitted paths.
 *
 *  The default `given fs: FileSystem^{io}` carries the permissions from the
 *  configuration (plus what the user granted for this session). Use
 *  `requestFiles(path, access)` to obtain a wider one for a block. */
@assumeSafe
abstract class FileSystem private[atc] () extends caps.SharedCapability:
  def access(path: String): FileEntry^{this}

/** Handle to a file or directory, obtained via `access(path)`. It captures the
 *  `FileSystem` it came from and can never outlive it. */
@assumeSafe
abstract class FileEntry private[atc] (tracked val origin: FileSystem):
  /** Absolute, normalized path. */
  def path: String
  def name: String
  def exists: Boolean
  def isDirectory: Boolean
  /** Whether the content of this file (or the structure of this directory) is
   *  classified. */
  def isClassified: Boolean
  def size: Long
  def read(): String
  def readBytes(): Array[Byte]
  def readLines(): List[String]
  /** Process each line without loading the file into memory; the callback
   *  receives the line and its 1-based number. */
  def forEachLine(op: (String, Int) => Unit): Unit
  def write(content: String): Unit
  def append(content: String): Unit
  def delete(): Unit
  /** Create this directory, including missing parents. */
  def mkdir(): Unit
  /** Immediate children of a (non-classified) directory. Entries you cannot
   *  access are omitted. */
  def children: List[FileEntry^{this}]
  /** All descendants. Classified sub-directories are listed but not entered. */
  def walk(): List[FileEntry^{this}]
  /** Read the content as `Classified`. Works on any readable file. */
  def readClassified(): Classified[String]
  /** Write classified content; the target must be a classified path with write access. */
  def writeClassified(content: Classified[String]): Unit
  /** Names (absolute paths) of the children of a classified directory. */
  def childrenClassified: Classified[List[String]]
  /** All descendant paths, including inside classified directories. */
  def walkClassified(): Classified[List[String]]

/** Capability to run processes matching a set of command patterns. */
@assumeSafe
abstract class Exec private[atc] () extends caps.SharedCapability

/** Capability to reach a set of network hosts. */
@assumeSafe
abstract class Network private[atc] () extends caps.SharedCapability

// ─── Data types ──────────────────────────────────────────────────────────────

@assumeSafe
case class GrepMatch(file: String, lineNumber: Int, line: String)
@assumeSafe
object GrepMatch

@assumeSafe
case class ProcessResult(exitCode: Int, stdout: String, stderr: String)
@assumeSafe
object ProcessResult

@assumeSafe
case class HttpResponse(status: Int, body: String)
@assumeSafe
object HttpResponse

@assumeSafe
enum TodoStatus:
  case Pending, InProgress, Done
@assumeSafe
object TodoStatus

/** One item of the plan shown to the user. */
@assumeSafe
case class Todo(text: String, status: TodoStatus = TodoStatus.Pending)
@assumeSafe
object Todo

// ─── The API ─────────────────────────────────────────────────────────────────

/** Everything the agent can do. All members are pre-imported at the REPL top
 *  level, together with the default capabilities:
 *
 *  ```
 *  given io:  IOCap              // root capability
 *  given fs:  FileSystem^{io}    // configured file permissions
 *  given ex:  Exec^{io}          // configured command permissions
 *  given net: Network^{io}       // configured network permissions
 *  ```
 *
 *  If an operation is denied (`SecurityException: ... not permitted`), wrap it
 *  in the matching `request*` block: the user is asked and, if they agree, the
 *  block runs with the extra permission. Permissions never escape the block.
 *
 *  ```
 *  // Read a file (path relative to the working directory or absolute)
 *  val src = read("src/Main.scala")
 *  // Edit it
 *  write("src/Main.scala", src.replace("TODO", "DONE"))
 *  // Search
 *  grepRecursive("src", "def main", "*.scala").foreach(m => println(s"${m.file}:${m.lineNumber}: ${m.line}"))
 *  // Need more? Ask.
 *  requestFiles("/tmp/data", Access.Write, reason = "cache build outputs") {
 *    write("/tmp/data/out.txt", "done")
 *  }
 *  requestExec(Set("mill *"), reason = "compile the project") {
 *    val r = exec("mill", List("compile"))
 *    println(r.stdout)
 *  }
 *  ```
 */
@assumeSafe
trait Interface:

  // ── Default capabilities (bound by the REPL preamble as givens) ──

  def defaultFiles(using io: IOCap): FileSystem^{io}
  def defaultExec(using io: IOCap): Exec^{io}
  def defaultNetwork(using io: IOCap): Network^{io}

  // ── File system ─────────────────────────────────────────────────

  /** Ask the user for `access` to `path` (a file or a directory subtree).
   *  Inside `op` a new `FileSystem` is available that has the current
   *  permissions plus the granted one. Throws `SecurityException` if denied. */
  def requestFiles[T](path: String, access: Access = Access.Read, reason: String = "")
                     (op: FileSystem^ ?=> T)(using IOCap, FileSystem): T

  /** Handle for `path`; relative paths are resolved against the working directory. */
  def access(path: String)(using fs: FileSystem): FileEntry^{fs}

  /** Read a whole file. */
  def read(path: String)(using FileSystem): String

  /** Read a file as lines. */
  def readLines(path: String)(using FileSystem): List[String]

  /** Write (create or overwrite) a file, creating parent directories. */
  def write(path: String, content: String)(using FileSystem): Unit

  /** Append to a file (created if missing). */
  def append(path: String, content: String)(using FileSystem): Unit

  def exists(path: String)(using FileSystem): Boolean
  def isDirectory(path: String)(using FileSystem): Boolean
  /** Create a directory (and parents). */
  def mkdir(path: String)(using FileSystem): Unit
  /** Delete a file or an empty directory. */
  def delete(path: String)(using FileSystem): Unit

  /** Absolute paths of the entries in a directory (non-recursive). */
  def ls(dir: String)(using FileSystem): List[String]

  /** Absolute paths of all descendants of `dir` (classified subtrees are not entered). */
  def walk(dir: String)(using FileSystem): List[String]

  /** Search one file for a regex; returns matching lines. */
  def grep(path: String, pattern: String)(using FileSystem): List[GrepMatch]

  /** Recursively search files under `dir` whose name matches `glob` for a regex.
   *  Classified files are skipped. */
  def grepRecursive(dir: String, pattern: String, glob: String = "*")(using FileSystem): List[GrepMatch]

  /** Absolute paths of all files under `dir` whose name matches `glob`. */
  def find(dir: String, glob: String)(using FileSystem): List[String]

  /** Read a file as `Classified` (required for classified paths). */
  def readClassified(path: String)(using FileSystem): Classified[String]

  /** Write classified content to a classified path. */
  def writeClassified(path: String, content: Classified[String])(using FileSystem): Unit

  // ── Processes ───────────────────────────────────────────────────

  /** Ask the user for permission to run commands matching `commands`. A
   *  pattern is matched against the full command line (`*` is a wildcard),
   *  e.g. `"git status"`, `"git diff*"`, `"npm *"`. */
  def requestExec[T](commands: Set[String], reason: String = "")
                    (op: Exec^ ?=> T)(using IOCap, Exec): T

  /** Run `command` with `args` (no shell). Throws `SecurityException` if the
   *  command line matches no permitted pattern; `RuntimeException` on timeout. */
  def exec(command: String, args: List[String] = Nil, workingDir: Option[String] = None,
           timeoutMs: Long = 120000)(using Exec): ProcessResult

  /** Run `command` and return its stdout. */
  def execOutput(command: String, args: List[String] = Nil)(using Exec): String

  // ── Network ─────────────────────────────────────────────────────

  /** Ask the user for permission to reach `hosts` (`*` is a wildcard,
   *  e.g. `"*.github.com"`). */
  def requestNetwork[T](hosts: Set[String], reason: String = "")
                       (op: Network^ ?=> T)(using IOCap, Network): T

  /** HTTP GET. `secretHeaders` values (e.g. an `Authorization` token read with
   *  `readClassified`) are sent to the host but never visible to you. */
  def httpGet(url: String, headers: Map[String, String] = Map.empty,
              secretHeaders: Map[String, Classified[String]] = Map.empty)(using Network): String

  def httpPost(url: String, body: String, contentType: String = "application/json",
               headers: Map[String, String] = Map.empty,
               secretHeaders: Map[String, Classified[String]] = Map.empty)(using Network): String

  def httpRequest(method: String, url: String, body: String = "",
                  headers: Map[String, String] = Map.empty,
                  secretHeaders: Map[String, Classified[String]] = Map.empty)(using Network): HttpResponse

  /** POST a classified body; the response stays classified. */
  def httpPostClassified(url: String, body: Classified[String], contentType: String = "application/json",
                         headers: Map[String, String] = Map.empty,
                         secretHeaders: Map[String, Classified[String]] = Map.empty)(using Network): Classified[String]

  // ── Output ──────────────────────────────────────────────────────

  /** Print to the conversation. This is how you report results. When `x` is
   *  a `Classified` value, the human user sees the real content in their
   *  terminal but you only see `Classified(***)`. */
  def println(x: Any)(using IOCap): Unit
  def println()(using IOCap): Unit
  def print(x: Any)(using IOCap): Unit
  def printf(fmt: String, args: Any*)(using IOCap): Unit

  // ── Talking to the user ─────────────────────────────────────────

  /** Ask the user a question and wait for the answer (`None` if they decline).
   *  Give `options` when a choice makes sense — the user can still type
   *  something else; `multiple` allows several choices (joined by `; `).
   *  Use this for decisions and information only the user has, not for
   *  permissions (the `request*` blocks do that).
   *
   *  ```
   *  ask("Which test framework?", List("munit", "scalatest")) match
   *    case Some("scalatest") => ...
   *    case _ => ...
   *  ``` */
  def ask(question: String, options: List[String] = Nil, multiple: Boolean = false)(using IOCap): Option[String]

  /** Replace the TODO list shown to the user. Keep a plan for multi-step
   *  tasks and update statuses as you go:
   *
   *  ```
   *  setTodos(List(Todo("read the build file"), Todo("add the test", TodoStatus.InProgress)))
   *  markTodo("read the build file", TodoStatus.Done)
   *  ``` */
  def setTodos(items: List[Todo])(using IOCap): Unit

  /** The current TODO list. */
  def todos(using IOCap): List[Todo]

  /** Change the status of the item whose text equals `text`. */
  def markTodo(text: String, status: TodoStatus)(using IOCap): Unit

  // ── Classified ──────────────────────────────────────────────────

  /** Wrap any value as `Classified`. */
  def classify[T](value: T): Classified[T]

  // ── LLM ─────────────────────────────────────────────────────────

  /** Ask the (normal) model a one-shot question, e.g. to summarize a long text. */
  def chat(message: String)(using IOCap): String

  /** Ask the *safe* model about classified content. The answer stays classified. */
  def chat(message: Classified[String]): Classified[String]

/** The interface's own injection point — the only thing in this module besides
  * the API and its types. The host installs its [[Interface]] implementation
  * before the REPL preamble runs; the preamble reads it back with `current`
  * and takes the root capability with `takeRootIO()`. Agent code cannot name
  * these members (the validator rejects them), and `install` is `private[atc]`. */
@assumeSafe
object Interface:
  @volatile private var installed: Interface | Null = null
  private val rootTaken = java.util.concurrent.atomic.AtomicBoolean(false)

  /** Install the implementation for a fresh sandbox (re-arms the root capability). */
  private[atc] def install(impl: Interface): Unit = synchronized:
    installed = impl
    rootTaken.set(false)

  /** The installed implementation. */
  def current: Interface =
    installed match
      case null => throw IllegalStateException("The sandbox has no host installed.")
      case a    => a

  /** Hand out the sandbox's single root capability, once. Every later call
    * throws, so a pure function can never obtain an `IOCap`. */
  def takeRootIO(): IOCap =
    if rootTaken.compareAndSet(false, true) then new IOCap
    else throw SecurityException("The root capability has already been taken; use the given `io`.")
