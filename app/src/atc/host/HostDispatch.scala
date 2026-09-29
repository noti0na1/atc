package atc.host

import atc.evaluator.{Channel, Decoder, Encoder}
import atc.lib.*
import atc.perms.ScopeId

import java.util.concurrent.ConcurrentHashMap
import scala.util.{Failure, Success, Try}

/** The host side of the evaluator process: decodes each call, rebuilds the capabilities
  * from their scope ids and runs [[Host]], which checks the call exactly as it checks an
  * in-process one. Everything here is untrusted input: a scope is honoured only while it
  * is open (every host operation looks it up), a process handle only from a scope that may
  * see it, and a callback is a nested call back into the evaluator on the same conversation. */
private[atc] final class HostDispatch(host: Host, channel: => Channel):
  private given UserIO = atc.lib.Runtime.rootUser
  private def fs(scope: Long): FileSystem = FileSystemImpl(ScopeId.fromLong(scope), host)
  private def ex(scope: Long, network: Option[Long]): Exec =
    ExecImpl(ScopeId.fromLong(scope), network.map(ScopeId.fromLong))
  private def net(scope: Long): Network = NetworkImpl(ScopeId.fromLong(scope))

  /** Handles of the processes the evaluator started or listed, by id. */
  private val processes = ConcurrentHashMap[Int, ProcessImpl]()

  private def scopeOf(capability: Scoped): Long = capability.scope.toLong

  private def callback(id: Long, capability: Scoped): Unit =
    channel.call("callback")(_.long(id).long(scopeOf(capability)))
    ()

  private def classifiedIn(d: Decoder): Classified[String] =
    if d.bool() then ClassifiedImpl.wrap(d.string())
    else ClassifiedImpl.fromTry(Failure(RuntimeException("the classified computation failed")))
  private def options(d: Decoder): ExecOptions = ExecOptions(d.string(), d.long(), d.string())
  private def matches(e: Encoder, found: List[GrepMatch]): Unit =
    e.int(found.size)
    found.foreach(m => e.string(m.file).int(m.lineNumber).string(m.line))
  private def result(e: Encoder, r: ProcessResult): Unit = e.int(r.exitCode).string(r.stdout).string(r.stderr)
  private def process(d: Decoder): ProcessImpl =
    val caller = ScopeId.fromLong(d.long())
    val visible = (p: ProcessImpl) => !host.policy.sealedScope(caller) && host.policy.scopeVisibleFrom(caller, p.scope)
    Option(processes.get(d.int())).filter(visible).getOrElse:
      throw IllegalStateException("no such process is visible here (see runningProcesses)")
  private def remember(p: Process): ProcessImpl = p match
    case impl: ProcessImpl =>
      processes.put(impl.id, impl)
      impl
    case other => throw IllegalStateException(s"unexpected process ${other.getClass.getName}")

  /** Run `op` with the command capability the evaluator named, through `withNetwork` when it
    * carries a network scope, so that the mode and the scope are checked as in-process. */
  private def withExec[T](d: Decoder)(op: Exec => T): T =
    val scope = d.long()
    d.optionalLong() match
      case None => op(ex(scope, None))
      case Some(network) => host.withNetwork((granted: Exec) ?=> op(granted))(using ex(scope, None), net(network))

  def handle(method: String, payload: Array[Byte]): Array[Byte] =
    val d = Decoder(payload)
    val r = Encoder()
    method match
      case "show" => host.output.show(d.string(), d.string())
      // ── files ──
      case "requestFiles" =>
        val (scope, path, access, reason, id) =
          (d.long(), d.string(), Access.fromOrdinal(d.int()), d.string(), d.long())
        host.requestFiles(path, access, reason)(using summon[UserIO], fs(scope))((f: FileSystem) ?=>
          callback(id, f.asInstanceOf[Scoped])
        )
      case "classified" =>
        val (fsScope, exScope, id) = (d.long(), d.long(), d.long())
        host.classified(using fs(fsScope), ex(exScope, None))((_: Sealed, f: FileSystem, _: Exec) ?=>
          callback(id, f.asInstanceOf[Scoped])
        )
      case "writeClassified" =>
        val (s, path) = (d.long(), d.string()); host.writeClassified(path, classifiedIn(d))(using fs(s))
      case "read" => val s = d.long(); r.string(host.read(d.string())(using fs(s)))
      case "readLines" => val s = d.long(); r.strings(host.readLines(d.string())(using fs(s)))
      case "readRange" => val s = d.long(); r.string(host.readRange(d.string(), d.int(), d.int())(using fs(s)))
      case "cat" =>
        val (s, path) = (d.long(), d.string())
        if d.bool() then host.cat(path, d.int(), d.int())(using fs(s)) else host.cat(path)(using fs(s))
      case "readBytes" => val s = d.long(); r.bytes(host.readBytes(d.string())(using fs(s)))
      case "write" => val s = d.long(); host.write(d.string(), d.string())(using fs(s))
      case "writeBytes" => val s = d.long(); host.writeBytes(d.string(), d.bytes())(using fs(s))
      case "move" => val s = d.long(); host.move(d.string(), d.string())(using fs(s))
      case "copy" => val s = d.long(); host.copy(d.string(), d.string())(using fs(s))
      case "sed" => val s = d.long(); r.int(host.sed(d.string(), d.string(), d.string())(using fs(s)))
      case "replaceExact" => val s = d.long(); host.replaceExact(d.string(), d.string(), d.string())(using fs(s))
      case "replaceLines" =>
        val s = d.long(); r.string(host.replaceLines(d.string(), d.int(), d.int(), d.string())(using fs(s)))
      case "insertLines" => val s = d.long(); host.insertLines(d.string(), d.int(), d.string())(using fs(s))
      case "append" => val s = d.long(); host.append(d.string(), d.string())(using fs(s))
      case "exists" => val s = d.long(); r.bool(host.exists(d.string())(using fs(s)))
      case "isDirectory" => val s = d.long(); r.bool(host.isDirectory(d.string())(using fs(s)))
      case "mkdir" => val s = d.long(); host.mkdir(d.string())(using fs(s))
      case "delete" => val s = d.long(); host.delete(d.string())(using fs(s))
      case "ls" => val s = d.long(); r.strings(host.ls(d.string())(using fs(s)))
      case "walk" => val s = d.long(); r.strings(host.walk(d.string())(using fs(s)))
      case "grep" => val s = d.long(); matches(r, host.grep(d.string(), d.string())(using fs(s)))
      case "grepRecursive" =>
        val (s, dir, pattern) = (d.long(), d.string(), d.string())
        given FileSystem = fs(s)
        matches(r, d.optionalString().fold(host.grepRecursive(dir, pattern))(host.grepRecursive(dir, pattern, _)))
      case "search" =>
        val (s, dir, pattern, glob) = (d.long(), d.string(), d.string(), d.string())
        val found =
          host.search(dir, pattern, glob, SearchOptions(d.int(), d.int(), d.int(), d.int(), d.int()))(using fs(s))
        matches(r, found.matches)
        r.int(found.filesScanned).bool(found.limited)
      case "find" => val s = d.long(); r.strings(host.find(d.string(), d.string())(using fs(s)))
      // ── file entries: re-derived from the scope and path on every call ──
      // ── commands ──
      case "requestExec" =>
        val (scope, network) = (d.long(), d.optionalLong())
        val (commands, reason, id) = (d.strings(), d.string(), d.long())
        host.requestExec(commands, reason)((e: Exec) ?=> callback(id, e.asInstanceOf[Scoped]))(using
          summon[UserIO],
          ex(scope, network)
        )
      case "exec" =>
        withExec(d): e =>
          val (s, command, args, opts) = (d.long(), d.string(), d.strings(), options(d))
          result(r, host.exec(command, args, opts)(using e, fs(s)))
      case "execReadOnly" =>
        withExec(d): e =>
          val (s, command, args, opts) = (d.long(), d.string(), d.strings(), options(d))
          result(r, host.execReadOnly(command, args, opts)(using e, fs(s)))
      case "execOutput" =>
        withExec(d): e =>
          val (s, command, args, opts) = (d.long(), d.string(), d.strings(), options(d))
          r.string(host.execOutput(command, args, opts)(using e, fs(s)))
      case "spawn" =>
        withExec(d): e =>
          val (s, command, opts) = (d.long(), d.string(), options(d))
          val started = remember(host.spawn(command, opts)(using e, fs(s)))
          r.int(started.id).string(started.commandLine)
      case "runningProcesses" =>
        val running = host.runningProcesses(using ex(d.long(), None)).map(remember)
        r.int(running.size)
        running.foreach(p => r.int(p.id).string(p.commandLine))
      case "process.isAlive" => r.bool(process(d).isAlive)
      case "process.exitCode" => r.optionalLong(process(d).exitCode.map(_.toLong))
      case "process.send" => val p = process(d); p.send(d.string())
      case "process.sendLine" => val p = process(d); p.sendLine(d.string())
      case "process.closeStdin" => process(d).closeStdin()
      case "process.read" => r.string(process(d).read())
      case "process.readErr" => r.string(process(d).readErr())
      case "process.readUntil" => val p = process(d); r.string(p.readUntil(d.string(), d.long()))
      case "process.waitFor" =>
        val p = process(d)
        p.waitFor(d.long()) match
          case Some(done) => result(r.bool(true), done)
          case None => r.bool(false)
      case "process.kill" => process(d).kill()
      // ── network ──
      case "requestNetwork" =>
        val (scope, hosts, reason, id) = (d.long(), d.strings(), d.string(), d.long())
        host.requestNetwork(hosts, reason)((n: Network) ?=> callback(id, n.asInstanceOf[Scoped]))(using
          summon[UserIO],
          net(scope)
        )
      case "http" =>
        val (scope, form, method, url) = (d.long(), d.string(), d.string(), d.string())
        val (body, contentType, headers) = (d.optionalString(), d.optionalString(), d.stringMap())
        given Network = net(scope)
        form match
          case "get" => r.string(host.httpGet(url, headers))
          case "post" =>
            val text = body.getOrElse("")
            r.string(contentType.fold(host.httpPost(url, text))(host.httpPost(url, text, _, headers)))
          case "request" =>
            val response = body.fold(host.httpRequest(method, url))(host.httpRequest(method, url, _, headers))
            r.int(response.status).string(response.body)
          case _ => throw IllegalArgumentException(s"unknown request form $form")
      // ── the user, notes and models ──
      case "ask" => r.optionalString(host.ask(d.string(), d.strings(), d.bool()))
      case "setTodos" => host.setTodos(List.fill(d.int())(Todo(d.string(), TodoStatus.fromOrdinal(d.int()))))
      case "todos" =>
        val items = host.todos
        r.int(items.size)
        items.foreach(t => r.string(t.text).int(t.status.ordinal))
      case "markTodo" => host.markTodo(d.string(), TodoStatus.fromOrdinal(d.int()))
      case "setTaskNotes" => host.setTaskNotes(TaskNotes(d.string(), d.strings(), d.strings(), d.strings()))
      case "taskNotes" =>
        val notes = host.taskNotes
        r.string(notes.goal).strings(notes.constraints).strings(notes.completed).strings(notes.remaining)
      case "chat" => r.string(host.chat(d.string()))
      case "classifiedChat" => r.string(host.classifiedChat(d.string()))
      case other => throw UnsupportedOperationException(s"unknown call $other")
    r.result
