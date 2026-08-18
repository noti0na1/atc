package atc.lib

import language.experimental.captureChecking
import caps.*

// ═══ Reading this file ════════════════════════════════════════════════════════
//
// This is the whole API you can call. It is capture-checked Scala 3; two type
// notations carry the security rules, so read them like permission bits:
//
//   • `^` marks a capability you hold.  `IOCap^` is the full root capability;
//     the bare type `IOCap` (no `^`) is its *read-only* view. Same for
//     `FileSystem^` (can write) vs `FileSystem` (read only). A full capability
//     can be passed where a read-only one is wanted, never the other way round.
//     `x.rd` names the read-only view of `x`:  `val ro: IOCap^{io.rd} = io`.
//
//   • `update def` marks a mutating operation (write, delete, …). It compiles
//     only through a *full* capability; on a read-only one you get
//     "Cannot call update method … its capture set is read-only".
//
// `Classified.map` accepts functions that capture only *read-only* capabilities,
// so nothing that changes the world (writing, running a command, the network,
// printing, asking) can happen on confidential data. See below.

// ─── Classified data ─────────────────────────────────────────────────────────

/** Confidential data: you can compute with it but never see it.
 *
 *  `map`/`flatMap` take a function that may capture only **read-only**
 *  capabilities. So inside one you can compute freely, use local `var`s and
 *  arrays, and read files where your `fs` is itself read-only. You can never
 *  write, run a command, use the network, `println`, `chat` or `ask`, because
 *  each of those needs a full capability. Whatever you compute stays
 *  classified; `toString` shows `Classified(***)`.
 *
 *  The only ways out are: `println` (the user sees the value, you still see
 *  `Classified(***)`), `writeClassified` (into a classified file), `chat` with
 *  the safe model, and `httpPostClassified` / `secretHeaders` to an allowed host.
 *
 *  {{{
 *  val secret = readClassified(".env")          // Classified[String]
 *  val upper  = secret.map(_.toUpperCase)       // ok, stays classified
 *  val n      = secret.map(s => s.length * 2)   // ok
 *  println(secret)                              // the user sees it; you don't
 *  }}}
 */
@assumeSafe
abstract class Classified[+T] private[atc] ():
  def map[B](op: T ->{any.rd} B): Classified[B]
  def flatMap[B](op: T ->{any.rd} Classified[B]): Classified[B]
  /** Combine two classified values. */
  def zip[B](that: Classified[B]): Classified[(T, B)] = flatMap(a => that.map(b => (a, b)))

// ─── Capabilities ────────────────────────────────────────────────────────────

@assumeSafe
trait Cap extends caps.Stateful, caps.ExclusiveCapability

/** The root capability for the file system, commands and network. Which view of
 *  it your sandbox mode gives you decides what you can derive:
 *
 *  - **full**: `given io: IOCap^`, so files, commands and network;
 *  - **local**: `given io: IOCap`, a read-only view, but with a full `fs`/`ex`;
 *  - **read-only**: `given io: IOCap`, a read-only view with a read-only `fs`.
 *
 *  In local/read-only mode `io` is read-only, so `write`/`exec`/`network` cannot
 *  be derived from it. Talking to the user is a *separate* capability (`user`),
 *  so it works in every mode. */
@assumeSafe
class IOCap private[atc] () extends Cap

/** Capability to talk to the user: `println`/`print`/`printf`, `ask`, the TODO
 *  list (`setTodos`/`markTodo`), and the normal-model `chat`. It is always
 *  available and mutable (`given user: UserIO^`) in every mode, because these
 *  are effects on the conversation rather than on the file system. You can
 *  always report results, even in read-only mode. Reading the TODO list (`todos`) needs
 *  only the read-only view. */
@assumeSafe
class UserIO private[atc] () extends Cap

/** Level of access to a path. `Write` includes `Read`. */
@assumeSafe
enum Access:
  case Read, Write
@assumeSafe
object Access

/** The file system, restricted to the configured (and session-granted) paths.
 *  A full `FileSystem^` can read and write; a bare read-only `FileSystem` can
 *  only read. `request*Files` hands a wider one to a block. */
@assumeSafe
abstract class FileSystem private[atc] () extends Cap:
  /** A handle for `path` (absolute, or relative to the working directory). The
   *  handle is read-only exactly when this file system is. */
  def access(path: String): FileEntry^{this}

/** A handle to a file or directory. The reading members work on any handle; the
 *  `update def`s (write/append/delete/mkdir/writeClassified) need a handle from
 *  a full `FileSystem^`. */
@assumeSafe
abstract class FileEntry private[atc] () extends Cap:
  /** Absolute, normalized path. */
  def path: String
  def name: String
  def exists: Boolean
  def isDirectory: Boolean
  /** Whether this file's content (or this directory's structure) is classified. */
  def isClassified: Boolean
  def size: Long
  def read(): String
  def readBytes(): Array[Byte]
  def readLines(): List[String]
  /** Visit each line without loading the file into memory; `op` gets the line
   *  and its 1-based number. */
  def forEachLine(op: (String, Int) => Unit): Unit
  update def write(content: String): Unit
  update def writeBytes(content: Array[Byte]): Unit
  update def append(content: String): Unit
  update def delete(): Unit
  /** Create this directory, including missing parents. */
  update def mkdir(): Unit
  /** Immediate children of a (non-classified) directory; entries you cannot
   *  access are omitted. */
  def children: List[FileEntry^{this}]
  /** All descendants; classified sub-directories are listed but not entered. */
  def walk(): List[FileEntry^{this}]
  /** Read any readable file as `Classified`. */
  def readClassified(): Classified[String]
  /** Write classified content; the target must be a classified path. */
  update def writeClassified(content: Classified[String]): Unit
  /** Absolute paths of the children of a classified directory. */
  def childrenClassified: Classified[List[String]]
  /** All descendant paths, including inside classified directories. */
  def walkClassified(): Classified[List[String]]

/** Permission to run commands. Derived only from a full `IOCap^`, so it does not
 *  exist in read-only mode. */
@assumeSafe
abstract class Exec private[atc] () extends caps.ExclusiveCapability

/** Permission to reach network hosts. Derived only from a full `IOCap^`, so it
 *  exists only in full mode. */
@assumeSafe
abstract class Network private[atc] () extends caps.ExclusiveCapability

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

/** Everything you can do. All members are already in scope, along with the
 *  capabilities of the current sandbox mode (full mode shown):
 *
 *  {{{
 *  given io:   IOCap^            // the root, for files/commands/network
 *  given user: UserIO^           // talking to the user (all modes)
 *  given fs:   FileSystem^{io}   // configured paths (read + write)
 *  given ex:   Exec^{io}         // configured commands
 *  given net:  Network^{io}      // configured hosts
 *  }}}
 *
 *  `user` is always there, so `println`/`ask`/`setTodos`/`chat` work in every
 *  mode. In **local** mode `io` is read-only and there is no `net`; in
 *  **read-only** mode `io` is read-only and `fs` is read-only. So `read` works
 *  everywhere, but `write`/`exec`/`httpGet` compile only in the modes that
 *  provide them. If one does not compile, ask the user to switch modes rather
 *  than working around it.
 *
 *  A method that writes needs a full file system: declare your own helpers as
 *  `(using fs: FileSystem^)`, since a bare `FileSystem` is read-only.
 *
 *  If an effect is denied at run time (`SecurityException: … not permitted`),
 *  wrap it in the matching `request*` block; the user is asked, and if they
 *  agree the block runs with the extra permission (which never escapes it).
 *
 *  {{{
 *  val src = read("src/Main.scala")
 *  replace("src/Main.scala", "TODO", "DONE")   // targeted edit, fails loudly if absent
 *  write("src/Main.scala", src + "\n// appended")   // or rewrite the whole file
 *  grepRecursive("src", "def main", "*.scala").foreach(m => println(s"${m.file}:${m.lineNumber}"))
 *
 *  requestFiles("/tmp/data", Access.Write, reason = "cache build outputs") {
 *    write("/tmp/data/out.txt", "done")
 *  }
 *  requestExec(Set("mill *"), reason = "compile the project") {
 *    println(exec("mill", List("compile")).stdout)
 *  }
 *  }}}
 */
@assumeSafe
trait Interface:

  // ── Deriving capabilities from `io` ─────────────────────────────

  /** The configured file system, full (read + write); needs a full `io`. */
  def fileSystem(using io: IOCap^): FileSystem^{io}

  /** The configured file system as read-only, whatever `io` is, e.g. to pass a
   *  helper an `fs` that provably cannot write. */
  def readOnlyFileSystem(using io: IOCap): FileSystem^{io.rd}

  /** The configured command permissions; needs a full `io`. */
  def processes(using io: IOCap^): Exec^{io}

  /** The configured network permissions; needs a full `io`. */
  def network(using io: IOCap^): Network^{io}

  // ── File system ─────────────────────────────────────────────────

  /** Ask the user for `access` to `path` (a file or a whole subtree); inside `op`
   *  a wider `FileSystem` is in scope. The block's file system is as capable as
   *  the one you already have: with a full `fs` you get a full one (so you can
   *  write), with a read-only `fs` (read-only mode) you get a read-only one, so
   *  this works in every mode. It cannot escape the block. Throws
   *  `SecurityException` if the user denies it. */
  def requestFiles[T, C^](path: String)(using UserIO^, FileSystem^{C})
                         (op: (FileSystem^{any.rd, C}) ?=> T): T
  def requestFiles[T, C^](path: String, access: Access)(using UserIO^, FileSystem^{C})
                         (op: (FileSystem^{any.rd, C}) ?=> T): T
  def requestFiles[T, C^](path: String, access: Access, reason: String)(using UserIO^, FileSystem^{C})
                         (op: (FileSystem^{any.rd, C}) ?=> T): T

  /** A handle for `path` (relative paths resolve against the working directory).
   *  Needs a full `fs`; with a read-only file system call `fs.access(path)`. */
  def access(path: String)(using fs: FileSystem^): FileEntry^{fs}

  /** Read a whole file. */
  def read(path: String)(using FileSystem): String

  /** Read a file as lines. */
  def readLines(path: String)(using FileSystem): List[String]

  /** Read a file as raw bytes (for binary files; `read` decodes as UTF-8). */
  def readBytes(path: String)(using FileSystem): Array[Byte]

  /** Write (create or overwrite) a file, creating parent directories. */
  def write(path: String, content: String)(using FileSystem^): Unit

  /** Write raw bytes, creating parent directories. Use it with `readBytes` to
   *  copy a binary file: `writeBytes(to, readBytes(from))`. */
  def writeBytes(path: String, content: Array[Byte])(using FileSystem^): Unit

  /** Replace every occurrence of `target` in a file and return how many were
   *  replaced. This is the safe way to make a small edit to a large file: it
   *  throws `IllegalArgumentException` when `target` does not occur, so an edit
   *  that silently changed nothing cannot pass unnoticed (unlike
   *  `write(p, read(p).replace(...))`, which rewrites the file unchanged).
   *
   *  {{{
   *  replace("src/Main.scala", "val timeout = 30", "val timeout = 60")   // 1
   *  }}}
   */
  def replace(path: String, target: String, replacement: String)(using FileSystem^): Int

  /** Append to a file (created if missing). */
  def append(path: String, content: String)(using FileSystem^): Unit

  def exists(path: String)(using FileSystem): Boolean
  def isDirectory(path: String)(using FileSystem): Boolean
  /** Create a directory (and parents). */
  def mkdir(path: String)(using FileSystem^): Unit
  /** Delete a file or an empty directory. */
  def delete(path: String)(using FileSystem^): Unit

  /** Absolute paths of the entries in a directory (non-recursive). */
  def ls(dir: String)(using FileSystem): List[String]

  /** Absolute paths of all descendants of `dir` (classified subtrees are skipped). */
  def walk(dir: String)(using FileSystem): List[String]

  /** Search one file for a regex; returns the matching lines. */
  def grep(path: String, pattern: String)(using FileSystem): List[GrepMatch]

  /** Search files under `dir` whose name matches `glob` for a regex (classified
   *  files are skipped); `glob` defaults to every file. */
  def grepRecursive(dir: String, pattern: String)(using FileSystem): List[GrepMatch]
  def grepRecursive(dir: String, pattern: String, glob: String)(using FileSystem): List[GrepMatch]

  /** Absolute paths of all files under `dir` whose name matches `glob`. */
  def find(dir: String, glob: String)(using FileSystem): List[String]

  /** Read a file as `Classified` (required for classified paths). */
  def readClassified(path: String)(using FileSystem): Classified[String]

  /** Write classified content to a classified path. */
  def writeClassified(path: String, content: Classified[String])(using FileSystem^): Unit

  // ── Commands ────────────────────────────────────────────────────

  /** Ask the user for permission to run commands matching `commands` (patterns
   *  over the full command line, `*` a wildcard: `"git status"`, `"npm *"`). */
  def requestExec[T](commands: Set[String])(op: Exec^ ?=> T)(using UserIO^, Exec): T
  def requestExec[T](commands: Set[String], reason: String)(op: Exec^ ?=> T)(using UserIO^, Exec): T

  /** Run `command` with `args` (no shell) in `workingDir` (default: the working
   *  directory). Throws `SecurityException` if the command line matches no
   *  permitted pattern, or if the working directory is unreadable or classified
   *  (the check goes through `FileSystem`, so a `request*Files` block covers it). */
  def exec(command: String)(using Exec, FileSystem): ProcessResult
  def exec(command: String, args: List[String])(using Exec, FileSystem): ProcessResult
  def exec(command: String, args: List[String], workingDir: Option[String])(using Exec, FileSystem): ProcessResult
  def exec(command: String, args: List[String], workingDir: Option[String], timeoutMs: Long)
          (using Exec, FileSystem): ProcessResult

  /** Run `command` in the working directory and return its stdout. */
  def execOutput(command: String)(using Exec, FileSystem): String
  def execOutput(command: String, args: List[String])(using Exec, FileSystem): String

  // ── Network ─────────────────────────────────────────────────────

  /** Ask the user for permission to reach `hosts` (`*` a wildcard, e.g. `"*.github.com"`). */
  def requestNetwork[T](hosts: Set[String])(op: Network^ ?=> T)(using UserIO^, Network): T
  def requestNetwork[T](hosts: Set[String], reason: String)(op: Network^ ?=> T)(using UserIO^, Network): T

  /** HTTP GET. A `secretHeaders` value (e.g. a token read with `readClassified`)
   *  is sent but never shown to you. Only `http`/`https` URLs are accepted. */
  def httpGet(url: String)(using Network): String
  def httpGet(url: String, headers: Map[String, String])(using Network): String
  def httpGet(url: String, headers: Map[String, String],
              secretHeaders: Map[String, Classified[String]])(using Network): String

  /** HTTP POST. `contentType` becomes the `Content-Type` header unless `headers`
   *  or `secretHeaders` already set it. */
  def httpPost(url: String, body: String)(using Network): String
  def httpPost(url: String, body: String, contentType: String)(using Network): String
  def httpPost(url: String, body: String, contentType: String,
               headers: Map[String, String],
               secretHeaders: Map[String, Classified[String]])(using Network): String

  def httpRequest(method: String, url: String)(using Network): HttpResponse
  def httpRequest(method: String, url: String, body: String)(using Network): HttpResponse
  def httpRequest(method: String, url: String, body: String,
                  headers: Map[String, String],
                  secretHeaders: Map[String, Classified[String]])(using Network): HttpResponse

  /** POST a classified body; the response stays classified. */
  def httpPostClassified(url: String, body: Classified[String])(using Network): Classified[String]
  def httpPostClassified(url: String, body: Classified[String], contentType: String)(using Network): Classified[String]
  def httpPostClassified(url: String, body: Classified[String], contentType: String,
                         headers: Map[String, String],
                         secretHeaders: Map[String, Classified[String]])(using Network): Classified[String]

  // ── Output ──────────────────────────────────────────────────────

  /** Print to the conversation. This is how you report results. A `Classified`
   *  value prints in full for the user but as `Classified(***)` for you. */
  def println(x: Any)(using UserIO^): Unit
  def println()(using UserIO^): Unit
  def print(x: Any)(using UserIO^): Unit
  def printf(fmt: String, args: Any*)(using UserIO^): Unit

  // ── Talking to the user ─────────────────────────────────────────

  /** Ask the user a question and wait (`None` if they decline). Offer `options`
   *  when a choice fits; `multiple` allows several (joined by `; `). For
   *  decisions and facts only the user has, not for permissions (`request*`).
   *
   *  {{{
   *  ask("Which test framework?", List("munit", "scalatest")) match
   *    case Some("scalatest") => ...
   *    case _                 => ...
   *  }}}
   */
  def ask(question: String)(using UserIO^): Option[String]
  def ask(question: String, options: List[String])(using UserIO^): Option[String]
  def ask(question: String, options: List[String], multiple: Boolean)(using UserIO^): Option[String]

  /** Replace the TODO list shown to the user; keep it updated on multi-step tasks.
   *
   *  {{{
   *  setTodos(List(Todo("read the build file"), Todo("add the test", TodoStatus.InProgress)))
   *  markTodo("read the build file", TodoStatus.Done)
   *  }}}
   */
  def setTodos(items: List[Todo])(using UserIO^): Unit

  /** The current TODO list. */
  def todos(using UserIO): List[Todo]

  /** Set the status of the item whose text equals `text`. */
  def markTodo(text: String, status: TodoStatus)(using UserIO^): Unit

  // ── Classified ──────────────────────────────────────────────────

  /** Wrap a value as `Classified`. */
  def classify[T](value: T): Classified[T]

  // ── LLM ─────────────────────────────────────────────────────────

  /** Ask the normal model a one-shot question (e.g. to summarize a long text). */
  def chat(message: String)(using UserIO^): String

  /** Ask the *safe* model about classified content; the answer stays classified. */
  def chat(message: Classified[String]): Classified[String]
