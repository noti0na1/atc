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
//     `x.rd` names the read-only view of `x`:  `val ro: FileSystem^{fs.rd} = fs` is a
//     file system that provably cannot write (hand it to a helper, or read files
//     with it inside `Classified.map`, where a full `fs` may not be captured).
//
//   • `update def` marks a mutating operation (write, delete, …). It compiles
//     only through a *full* capability; on a read-only one you get
//     "Cannot call update method … its capture set is read-only".
//
// `Classified.map` accepts functions that capture only *read-only* capabilities,
// so ordinary world-changing operations (writing, commands, network, printing,
// asking) cannot happen on confidential data. The explicit exception is the
// trusted, assumed-pure `classifiedChat` primitive. See below.

// ─── Classified data ─────────────────────────────────────────────────────────

/** Confidential data: you can compute with it but never see it.
 *
 *  `map` and `flatMap` accept functions that may capture only **read-only**
 *  capabilities. Within them, you can compute freely, use local `var`s and
 *  arrays, and read files where your `fs` is itself read-only. You can never
 *  write, run a command, use the network, `println`, normal-model `chat` or `ask`, because
 *  each of those needs a full capability. Whatever you compute stays
 *  classified; `toString` shows `Classified(***)`.
 *
 *  This is termination-insensitive information flow: do not branch on a secret
 *  into nontermination, a timeout, or materially different resource use. The
 *  sandbox cannot make the running time or termination of arbitrary pure Scala
 *  code indistinguishable.
 *
 *  The only supported destinations are `println` (the user sees the value while
 *  you still see `Classified(***)`), `writeClassified` (to a classified file),
 *  `classifiedChat` with the classified model, and `httpPostClassified` or `secretHeaders`
 *  to an allowed host. Every response to a request carrying classified input is
 *  itself `Classified`, so a server cannot reflect a secret back into plain data.
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

/** The root capability for the file system, commands, and network. The view
 *  supplied by the sandbox mode determines which capabilities are available:
 *
 *  - **full** supplies `given io: IOCap^`, enabling files, commands, and network;
 *  - **local** supplies a read-only `given io: IOCap` plus full `fs` and `ex`;
 *  - **read-only** supplies a read-only `given io: IOCap` and read-only `fs`.
 *
 *  The sandbox derives `fs`/`ex`/`net` from `io` when it starts; you cannot derive
 *  anything yourself. In local and read-only modes, where `io` is read-only, the
 *  mode's givens are the only available capabilities (no `Network` in local mode and no writes
 *  in read-only mode). Talking to the user is a *separate* capability (`user`), so
 *  it works in every mode. */
@assumeSafe
class IOCap private[atc] () extends Cap

/** Capability for interacting with the user through `println`/`print`/`printf`,
 *  `ask`, the TODO list (`setTodos`/`markTodo`), and normal-model `chat`. It is
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
 *  only read. `requestFiles` hands a wider one to a block. */
@assumeSafe
abstract class FileSystem private[atc] () extends Cap:
  /** A handle for `path` (absolute, or relative to the working directory). The
   *  handle is read-only exactly when this file system is. */
  def access(path: String): FileEntry^{this}

/** A handle to a file or directory. Read operations work on any handle; the
 *  `update def`s (`write`, `append`, `delete`, `mkdir`, and `writeClassified`) require one from
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
   *  access are omitted, and a symlink is listed as its target. */
  def children: List[FileEntry^{this}]
  /** All descendants; classified sub-directories and symlinked directories are
   *  listed but not entered. */
  def walk(): List[FileEntry^{this}]
  /** Read any readable file as `Classified`. */
  def readClassified(): Classified[String]
  /** Write classified content; the target must be a classified path. */
  update def writeClassified(content: Classified[String]): Unit
  /** Absolute paths of the children of a classified directory. */
  def childrenClassified: Classified[List[String]]
  /** All descendant paths, including inside classified directories. */
  def walkClassified(): Classified[List[String]]

/** Permission to run commands (`given ex`). Derived by the sandbox only from a
 *  full `IOCap^`, so it does not exist in read-only mode. Unlike the file-system
 *  capabilities it has no read-only view: it is all or nothing (`Exec^`), which
 *  is what keeps commands out of `Classified.map`. */
@assumeSafe
abstract class Exec private[atc] () extends caps.ExclusiveCapability

/** Permission to reach network hosts (`given net`). Derived by the sandbox only
 *  from a full `IOCap^`, so it exists only in full mode. No read-only view either:
 *  `Network^` or nothing, so no request can be made from `Classified.map`. */
@assumeSafe
abstract class Network private[atc] () extends caps.ExclusiveCapability

/** A process started with `spawn` that you can interact with while it runs (a REPL, a
 *  dev server, a watcher). It holds the `Exec` it was started with, so it cannot
 *  be used where `Exec` cannot (inside `Classified.map`). It lives until it exits,
 *  you `kill()` it, or the session ends; `runningProcesses` finds the live ones
 *  again. One spawned inside a `requestExec` block is also killed when that block
 *  ends. Start long-lived processes from a standing grant rather than a one-time
 *  grant. Wait operations accept a timeout and throw `RuntimeException` when it
 *  expires. The exception includes any output received, which remains available
 *  for a later `read()`.
 *
 *  {{{
 *  val py: Process^{ex} = spawn("python3 -i")      // top-level val: explicit type, like FileEntry
 *  py.readUntil(">>> ", 5000)
 *  py.sendLine("print(6 * 7)")
 *  println(py.readUntil(">>> ", 5000))            // "42\n>>> "
 *  py.kill()
 *  }}}
 */
@assumeSafe
abstract class Process private[atc] () extends caps.ExclusiveCapability:
  /** Its number in this session (`p1`, `p2`, …), as the user sees it. */
  def id: Int
  def commandLine: String
  def isAlive: Boolean
  /** The exit code once it has exited (pipefail-style for a pipeline). */
  def exitCode: Option[Int]
  /** Write to its stdin, exactly as given (`sendLine` adds the newline). */
  def send(text: String): Unit
  def sendLine(line: String): Unit
  /** Close its stdin (EOF). */
  def closeStdin(): Unit
  /** Stdout produced since the last read, consumed; "" when there is none. Never blocks. */
  def read(): String
  /** Likewise for stderr. */
  def readErr(): String
  /** Wait (at most `timeoutMs`) until `regex` matches the unread stdout; returns
   *  everything up to and including the match. */
  def readUntil(regex: String, timeoutMs: Long): String
  /** Wait up to `timeoutMs` for the process to exit, returning its unread output
   *  and exit code if it does. */
  def waitFor(timeoutMs: Long): Option[ProcessResult]
  /** Terminate it (and every stage of a pipeline). */
  def kill(): Unit

// ─── Data types ──────────────────────────────────────────────────────────────

/** One grep hit: the file (relative to the working directory when inside it),
 *  the 1-based line number and the line. */
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

/** Options for `exec`: the directory to run in (default: the working directory),
 *  the wall-clock limit (default 10 minutes; raise it for longer builds), and text to
 *  feed to the command's standard input (stdin is closed either way, so a command
 *  that reads it gets EOF instead of hanging). */
@assumeSafe
case class ExecOptions(workingDir: String = ".", timeoutMs: Long = 600_000L, stdin: String = "")
@assumeSafe
object ExecOptions

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

/** Minimal JSON. `Json.parse(text)` reads it; navigate with `j("key")` and `j(i)`
 *  (a missing key or index gives `Json.Null`, so `j("a")(0)("b")` is safe to chain);
 *  read a leaf with `.str`/`.num`/`.int`/`.bool`/`.arr`/`.obj` (they throw with a clear
 *  message on the wrong kind; test with `.isNull`); build with `Json.obj("k" -> Json.Str("v"))`,
 *  `Json.arr(...)`, `Json.Num(1)`, `Json.Bool(true)`; change with `updated`/`removed`; and
 *  `render` (compact) or `pretty` gives the text back. Numbers are `Double`s (whole
 *  ones render without a decimal point); `toString` is `render`.
 *
 *  {{{
 *  val pkg = Json.parse(read("package.json"))
 *  println(pkg("scripts")("test").str)                                   // a leaf
 *  pkg("dependencies").obj.foreach((name, v) => println(s"$name ${v.str}"))
 *  write("package.json", pkg.updated("version", Json.Str("1.2.0")).pretty + "\n")
 *  }}}
 */
@assumeSafe
enum Json:
  case Null
  case Bool(value: Boolean)
  case Num(value: Double)
  case Str(value: String)
  case Arr(items: List[Json])
  case Obj(fields: List[(String, Json)])

  /** Field `key` of an object; `Null` when absent or not an object. */
  def apply(key: String): Json = this match
    case Obj(fields) =>
      fields.find(_._1 == key) match
        case Some((_, v)) => v
        case None         => Null
    case _ => Null
  /** Element `i` of an array; `Null` when out of range or not an array. */
  def apply(i: Int): Json = this match
    case Arr(items) if i >= 0 && i < items.length => items(i)
    case _                                        => Null
  def isNull: Boolean = this == Null
  def str: String = this match
    case Str(v) => v
    case _      => expected("a string")
  def num: Double = this match
    case Num(v) => v
    case _      => expected("a number")
  /** A whole number (`3.0` is fine, `3.5` throws). */
  def int: Int = this match
    case Num(v) if v.isWhole && v >= Int.MinValue && v <= Int.MaxValue => v.toInt
    case _                                                            => expected("a whole number")
  def bool: Boolean = this match
    case Bool(v) => v
    case _       => expected("a boolean")
  def arr: List[Json] = this match
    case Arr(items) => items
    case _          => expected("an array")
  def obj: List[(String, Json)] = this match
    case Obj(fields) => fields
    case _           => expected("an object")
  /** The field names of an object (empty otherwise). */
  def keys: List[String] = this match
    case Obj(fields) => fields.map(_._1)
    case _           => Nil
  /** This object with `key` set to `value` (in place if present, appended otherwise). */
  def updated(key: String, value: Json): Json = this match
    case Obj(fields) =>
      if fields.exists(_._1 == key) then Obj(fields.map(f => if f._1 == key then (key, value) else f))
      else Obj(fields :+ (key, value))
    case _ => expected("an object")
  /** This object without `key`. */
  def removed(key: String): Json = this match
    case Obj(fields) => Obj(fields.filterNot(_._1 == key))
    case _           => expected("an object")
  /** Compact JSON text. */
  def render: String = JsonCodec.render(this, pretty = false)
  /** Indented JSON text (2 spaces). */
  def pretty: String = JsonCodec.render(this, pretty = true)
  override def toString: String = render
  private def expected(what: String): Nothing =
    throw IllegalArgumentException(s"expected $what, got ${JsonCodec.kind(this)}: ${render.take(120)}")

@assumeSafe
object Json:
  /** Parse JSON text; throws `IllegalArgumentException` naming the offset on bad input
   *  (a trailing comma before `]`/`}` is tolerated). */
  def parse(text: String): Json = JsonCodec.parse(text)
  def obj(fields: (String, Json)*): Json = Obj(fields.toList)
  def arr(items: Json*): Json = Arr(items.toList)

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
 *  cat("src/Main.scala", 1, 40)   // look at lines 1-40 with line numbers
 *  val src = read("src/Main.scala")
 *  sed("src/Main.scala", """^import (\w+)\._$""", "import $1.*")   // in-place edit, fails loudly if nothing matches
 *  write("src/Main.scala", src + "\n// appended")   // or rewrite the whole file
 *  grepRecursive("src", "def main", "*.scala").foreach(m => println(s"${m.file}:${m.lineNumber}"))
 *  val r = exec("git status --short")   // split like a small shell grammar; `|`/redirection work, `&&` does not
 *  println(s"exit ${r.exitCode}\n${r.stdout}${r.stderr}")
 *  val pkg = Json.parse(read("package.json")); println(pkg("scripts")("test").str)
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

  /** A handle for `path` (relative paths resolve against the working directory);
   *  it is read-only exactly when `fs` is. */
  def access(path: String)(using fs: FileSystem): FileEntry^{fs}

  /** Read a whole file. */
  def read(path: String)(using FileSystem): String

  /** Read a file as lines. */
  def readLines(path: String)(using FileSystem): List[String]

  /** Print a file with 1-based line numbers, like `cat -n` (`     1\tline`); the
   *  first form stops after 400 lines with a note saying which `cat(path, from, to)`
   *  shows the rest, the second prints lines `from` to `to` inclusive, like
   *  `sed -n 'from,to'`. This is the way to look at a file (`read`/`readLines`
   *  give the raw text to code with). The numbers are not in the file: never copy
   *  them into a `sed` pattern. `to` may run past the end (`[end of file: N lines]`
   *  marks it), so a large `to` shows the tail. Lines longer than 2000 characters are
   *  cut with a `[+N chars]` marker. */
  def cat(path: String)(using FileSystem, UserIO^): Unit
  def cat(path: String, from: Int, to: Int)(using FileSystem, UserIO^): Unit

  /** Read a file as raw bytes (for binary files; `read` decodes as UTF-8). */
  def readBytes(path: String)(using FileSystem): Array[Byte]

  /** Write (create or overwrite) a file, creating parent directories. */
  def write(path: String, content: String)(using FileSystem^): Unit

  /** Write raw bytes, creating parent directories. Use it with `readBytes` to
   *  copy a binary file: `writeBytes(to, readBytes(from))`. */
  def writeBytes(path: String, content: Array[Byte])(using FileSystem^): Unit

  /** Move (rename) a file; `to` may be a new name or a path elsewhere (parent
   *  directories are created). It is read `from`, write `to`, delete `from` with the
   *  usual checks, so a classified file cannot be moved at all. Directories: move
   *  their files and `mkdir`/`delete` the directories. */
  def move(from: String, to: String)(using FileSystem^): Unit
  /** Copy a file, binary-safe (`writeBytes(to, readBytes(from))`). */
  def copy(from: String, to: String)(using FileSystem^): Unit

  /** Edit a file in place, like `sed -E -i 's/pattern/replacement/g'`: replace
   *  every match of `pattern` (a Java regex; `^`/`$` match at line boundaries and
   *  `.` does not cross a newline, but a `\n` in the pattern may span lines) and
   *  return how many matches were replaced. In `replacement`, `$1`/`${name}`/`\1`
   *  insert a group, `\n`/`\t` a newline/tab, `\\`/`\$` a literal backslash/dollar.
   *  For literal text (code with `(`, `.`, `[`, `$`, `*`, …) quote both sides:
   *  `sed(p, quote(old), quoteReplacement(new))`. It throws `IllegalArgumentException` when
   *  nothing matches and leaves the file alone, so an edit that silently changed
   *  nothing cannot pass unnoticed; check a pattern with `grep(path, pattern)`
   *  when in doubt, and compare the count it returns with what you expected.
   *
   *  {{{
   *  sed("src/Main.scala", """^(\s*)val timeout = (\d+)$""", "$1val timeout = $2 * 2")   // 1
   *  sed("Makefile", "^CC=.*$", "CC=clang")
   *  sed("src/Main.scala", quote("foo(a, b)"), quoteReplacement("foo(a, b, c)"))   // literal edit
   *  }}}
   */
  def sed(path: String, pattern: String, replacement: String)(using FileSystem^): Int

  // TODO(safe-mode): remove `quote`/`quoteReplacement` once safe mode admits
  // `scala.util.matching.Regex.quote`/`quoteReplacement` (stdlib not yet tagged).
  /** A regex that matches `text` literally (`\Q…\E`), for a `sed` pattern. */
  def quote(text: String): String
  /** `text` as a literal `sed` replacement: its `\` and `$` escaped. */
  def quoteReplacement(text: String): String

  /** Replace lines `from` to `to` (1-based, inclusive, as `cat` numbers them) with
   *  `text` (any number of lines; empty deletes them) and return the text they held,
   *  so you can check it was what you expected. Line numbers shift after an edit:
   *  `cat` again before the next one, or edit bottom-up. */
  def replaceLines(path: String, from: Int, to: Int, text: String)(using FileSystem^): String
  /** Insert `text` before line `before` (`lineCount + 1` appends). */
  def insertLines(path: String, before: Int, text: String)(using FileSystem^): Unit

  /** Append to a file (created if missing). */
  def append(path: String, content: String)(using FileSystem^): Unit

  def exists(path: String)(using FileSystem): Boolean
  def isDirectory(path: String)(using FileSystem): Boolean
  /** Create a directory (and parents). */
  def mkdir(path: String)(using FileSystem^): Unit
  /** Delete a file or an empty directory. There is no recursive delete: for a tree,
   *  `walk(dir).reverse.foreach(delete)` then `delete(dir)`. */
  def delete(path: String)(using FileSystem^): Unit

  /** The entries of a directory (non-recursive). Paths are relative to the working
   *  directory when inside it, absolute otherwise; every helper accepts either. */
  def ls(dir: String)(using FileSystem): List[String]

  /** All descendants of `dir`, parents before children (classified subtrees are
   *  skipped); paths as in `ls`. */
  def walk(dir: String)(using FileSystem): List[String]

  /** Search one file for a regex (`quote(text)` for literal text); the matching lines. */
  def grep(path: String, pattern: String)(using FileSystem): List[GrepMatch]

  /** Search the files under `dir` selected by `glob` (see `find`) for a regex
   *  (`quote(text)` for literal text); classified files are skipped. Without a
   *  `glob`, every file. */
  def grepRecursive(dir: String, pattern: String)(using FileSystem): List[GrepMatch]
  def grepRecursive(dir: String, pattern: String, glob: String)(using FileSystem): List[GrepMatch]

  /** The files under `dir` selected by `glob`: a plain glob (`"*.scala"`, `"Test?.txt"`)
   *  matches the file name; one containing `/` or `**` matches the path relative to
   *  `dir`, where `**` spans any number of directories, including none. Paths as in `ls`. */
  //   find("src", "**/*.scala")        every Scala file under src, at any depth
  //   find(".", "app/**/test/*.py")    test scripts anywhere under app
  def find(dir: String, glob: String)(using FileSystem): List[String]

  /** Read a file as `Classified` (required for classified paths). */
  def readClassified(path: String)(using FileSystem): Classified[String]

  /** Write classified content to a classified path. */
  def writeClassified(path: String, content: Classified[String])(using FileSystem^): Unit

  // ── Commands ────────────────────────────────────────────────────

  /** Ask the user for permission to run commands matching `commands` (patterns
   *  over the full command line, `*` a wildcard: `"git status"`, `"npm *"`). */
  def requestExec[T](commands: Iterable[String])(op: Exec^ ?=> T)(using UserIO^, Exec^): T
  def requestExec[T](commands: Iterable[String], reason: String)(op: Exec^ ?=> T)(using UserIO^, Exec^): T

  /** Run a command, without a shell. `command` is split like a simple shell line
   *  (quotes honoured), so `exec("git status --short")` equals
   *  `exec("git", List("status", "--short"))`; `args` are appended verbatim (then
   *  `command` must be one program). The line may be a small pipeline: `|` between
   *  commands, `< file` for the input of the first, `> file` / `>> file` for the
   *  output of the last, `2>&1` after a command to send its stderr down the pipe;
   *  every command in it is checked like its own `exec`, and the files like `read` /
   *  `write` (classified files are refused both ways). There is no shell beyond that:
   *  `&&`, `;`, `||`, `&`, `$(...)`, globs and `$VAR` are not interpreted (an unquoted
   *  one throws): run steps one by one and combine in Scala, feed text with
   *  `ExecOptions(stdin = ...)`. With several commands the exit code is the rightmost
   *  non-zero one (pipefail) and stderr is labelled per stage.
   *  A pipeline has at most 16 stages.
   *  Runs in the working directory unless `workingDir`/`options` say otherwise. The
   *  result carries the exit code and both streams (a non-zero exit does not throw;
   *  `execOutput` does). Throws `SecurityException` if the command line matches no
   *  permitted pattern, or if the directory is unreadable or classified (the check goes
   *  through `FileSystem`, so a `requestFiles` block covers it), and
   *  `RuntimeException` after `timeoutMs` (default 10 minutes) with the output so far;
   *  the time a command runs does not count against the snippet's own timeout. */
  def exec(command: String)(using Exec^, FileSystem): ProcessResult
  def exec(command: String, args: Seq[String])(using Exec^, FileSystem): ProcessResult
  def exec(command: String, args: Seq[String], workingDir: String)(using Exec^, FileSystem): ProcessResult
  def exec(command: String, args: Seq[String], options: ExecOptions)(using Exec^, FileSystem): ProcessResult

  /** The stdout of a command that succeeded; throws `RuntimeException` with the exit
   *  code and stderr when it did not (use `exec` to inspect a failure). */
  def execOutput(command: String)(using Exec^, FileSystem): String
  def execOutput(command: String, args: Seq[String])(using Exec^, FileSystem): String
  def execOutput(command: String, args: Seq[String], options: ExecOptions)(using Exec^, FileSystem): String

  /** Start a command using the same grammar and checks as `exec`, then return
   *  immediately with a `Process` for interacting with REPLs, servers, or watchers.
   *  Its stdin stays open for `send` (`ExecOptions(stdin = ...)` is sent first);
   *  `workingDir` applies, `timeoutMs` does not: it runs until it exits, you `kill()`
   *  it, or the session ends (and, if spawned inside a `requestExec` block, when that
   *  block ends). At most eight may be live at once; call `kill()` when finished.
   *  The user sees the process start, the input you send, and its exit. */
  def spawn(command: String)(using ex: Exec^, fs: FileSystem): Process^{ex}
  def spawn(command: String, options: ExecOptions)(using ex: Exec^, fs: FileSystem): Process^{ex}
  /** The processes you started that are still running (to find a handle again). */
  def runningProcesses(using ex: Exec^): List[Process^{ex}]

  // ── Network ─────────────────────────────────────────────────────

  /** Ask the user for permission to reach `hosts` (`*` a wildcard, e.g. `"*.github.com"`). */
  def requestNetwork[T](hosts: Iterable[String])(op: Network^ ?=> T)(using UserIO^, Network^): T
  def requestNetwork[T](hosts: Iterable[String], reason: String)(op: Network^ ?=> T)(using UserIO^, Network^): T

  /** HTTP GET: the body of a 2xx/3xx response (at most 8 MiB); a status of 400 or more throws
   *  `RuntimeException` with the status and the start of the body (use `httpRequest`
   *  to inspect a failure). Redirects are not followed (each URL is checked against
   *  the allowed hosts). A `secretHeaders` value (e.g. a token read with
   *  `readClassified`) is sent without being shown to you; because a peer could
   *  reflect it, that overload's response stays `Classified`. Only `http`/`https`
   *  URLs. */
  def httpGet(url: String)(using Network^): String
  def httpGet(url: String, headers: Map[String, String])(using Network^): String
  def httpGet(url: String, headers: Map[String, String],
              secretHeaders: Map[String, Classified[String]])(using Network^): Classified[String]

  /** HTTP POST, same contract as `httpGet`. `contentType` (default
   *  `application/json`) becomes the `Content-Type` header unless `headers` or
   *  `secretHeaders` already set it. */
  def httpPost(url: String, body: String)(using Network^): String
  def httpPost(url: String, body: String, contentType: String)(using Network^): String
  def httpPost(url: String, body: String, contentType: String, headers: Map[String, String])(using Network^): String
  def httpPost(url: String, body: String, contentType: String,
               headers: Map[String, String],
               secretHeaders: Map[String, Classified[String]])(using Network^): Classified[String]

  /** Any method; the raw status and body, never throws on an HTTP error. */
  def httpRequest(method: String, url: String)(using Network^): HttpResponse
  def httpRequest(method: String, url: String, body: String)(using Network^): HttpResponse
  def httpRequest(method: String, url: String, body: String, headers: Map[String, String])(using Network^): HttpResponse
  def httpRequest(method: String, url: String, body: String,
                  headers: Map[String, String],
                  secretHeaders: Map[String, Classified[String]])(using Network^): Classified[HttpResponse]

  /** POST a classified body; the response stays classified. */
  def httpPostClassified(url: String, body: Classified[String])(using Network^): Classified[String]
  def httpPostClassified(url: String, body: Classified[String], contentType: String)(using Network^): Classified[String]
  def httpPostClassified(url: String, body: Classified[String], contentType: String,
                         headers: Map[String, String],
                         secretHeaders: Map[String, Classified[String]])(using Network^): Classified[String]

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

  /** Ask the untrusted normal model a one-shot question. This outward effect
   *  requires the full user capability. */
  def chat(message: String)(using UserIO^): String

  /** Ask the trusted classified model. Its deployment is assumed to be an
   *  isolated, effect-free classified environment, so this operation is treated
   *  as pure and may be called inside `Classified.map`. */
  def classifiedChat(message: String): String

  /** Ask the trusted classified model about classified content; implemented as
   *  `message.map(classifiedChat)`, so the answer stays classified. */
  def classifiedChat(message: Classified[String]): Classified[String]
