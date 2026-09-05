package atc.agent

import atc.lib.{TaskNotes, Todo, TodoStatus}
import atc.llm.{Msg, ToolCall, ToolResult}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Portable conversation state. Runtime capabilities, permissions and native provider payloads are excluded. */
private[atc] final case class SessionSnapshot(
  history: List[Msg],
  pendingNotes: List[String],
  userRequests: List[String],
  task: TaskNotes,
  todos: List[Todo],
  model: String,
)

private[atc] object SessionStore:
  private val MaxBytes = 8 * 1024 * 1024

  def write(path: Path, session: SessionSnapshot): Unit =
    val bytes = encode(session).getBytes(UTF_8)
    if bytes.length > MaxBytes then throw IllegalArgumentException("Saved session exceeds the 8 MiB limit")
    val target = path.toAbsolutePath.nn.normalize.nn
    Files.createDirectories(target.getParent)
    val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    val channel =
      try FileChannel.open(target, Set(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).asJava, permissions).nn
      catch
        case _: UnsupportedOperationException =>
          FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).nn
    Using.resource(channel) { out =>
      val buffer = ByteBuffer.wrap(bytes)
      while buffer.hasRemaining do out.write(buffer)
    }

  def read(path: Path): SessionSnapshot =
    val bytes = Using.resource(Files.newInputStream(path).nn)(_.readNBytes(MaxBytes + 1).nn)
    if bytes.length > MaxBytes then throw IllegalArgumentException("Saved session exceeds the 8 MiB limit")
    decode(String(bytes, UTF_8))

  private[atc] def encode(session: SessionSnapshot): String =
    def strings(values: List[String]): ujson.Arr = ujson.Arr.from(values)
    val messages = session.history.map {
      case Msg.User(text) => ujson.Obj("role" -> "user", "text" -> text)
      case Msg.Continuation(text) => ujson.Obj("role" -> "continuation", "text" -> text)
      case Msg.Assistant(text, calls, _) => ujson.Obj(
          "role" -> "assistant",
          "text" -> text,
          "calls" -> ujson.Arr.from(calls.map(call =>
            ujson.Obj("id" -> call.id, "name" -> call.name, "arguments" -> call.arguments)
          ))
        )
      case Msg.ToolResults(results) => ujson.Obj(
          "role" -> "tools",
          "results" -> ujson.Arr.from(results.map(result =>
            ujson.Obj("id" -> result.callId, "output" -> result.output, "error" -> result.isError)
          ))
        )
    }
    ujson.write(
      ujson.Obj(
        "version" -> 1,
        "model" -> session.model,
        "history" -> ujson.Arr.from(messages),
        "pendingNotes" -> strings(session.pendingNotes),
        "userRequests" -> strings(session.userRequests),
        "task" -> ujson.Obj(
          "goal" -> session.task.goal,
          "constraints" -> strings(session.task.constraints),
          "completed" -> strings(session.task.completed),
          "remaining" -> strings(session.task.remaining)
        ),
        "todos" ->
          ujson.Arr.from(session.todos.map(todo => ujson.Obj("text" -> todo.text, "status" -> todo.status.toString))),
      ),
      indent = 2
    )

  private[atc] def decode(text: String): SessionSnapshot =
    val data = ujson.read(text)
    if data("version").num != 1 then throw IllegalArgumentException("Unsupported saved-session version")
    def strings(value: ujson.Value): List[String] = value.arr.toList.map(_.str)
    val history = data("history").arr.toList.map { value =>
      value("role").str match
        case "user" => Msg.User(value("text").str)
        case "continuation" => Msg.Continuation(value("text").str)
        case "assistant" => Msg.Assistant(
            value("text").str,
            value("calls").arr.toList.map(c => ToolCall(c("id").str, c("name").str, c("arguments").str)),
            None
          )
        case "tools" => Msg.ToolResults(value("results").arr.toList.map(r =>
            ToolResult(r("id").str, r("output").str, r("error").bool)
          ))
        case other => throw IllegalArgumentException(s"Unknown saved message role: $other")
    }
    var pending = Set.empty[String]
    history.foreach {
      case Msg.ToolResults(results) =>
        if results.map(_.callId).toSet != pending || results.size != pending.size || pending.isEmpty then
          throw IllegalArgumentException("Saved session has mismatched tool results")
        pending = Set.empty
      case message =>
        if pending.nonEmpty then throw IllegalArgumentException("Saved session has unanswered tool calls")
        message match
          case Msg.Assistant(_, calls, _) =>
            pending = calls.map(_.id).toSet
            if pending.size != calls.size then
              throw IllegalArgumentException("Saved session has duplicate tool call IDs")
          case _ => ()
    }
    if pending.nonEmpty then throw IllegalArgumentException("Saved session has unanswered tool calls")
    val task = data("task")
    val notes =
      TaskNotes(task("goal").str, strings(task("constraints")), strings(task("completed")), strings(task("remaining")))
    if (notes.goal :: (notes.constraints ++ notes.completed ++ notes.remaining)).mkString("\n").length > 16000 then
      throw IllegalArgumentException("Saved task notes exceed 16000 characters")
    val requests = strings(data("userRequests"))
    if requests.size > 9 || requests.exists(_.length > 8000) then
      throw IllegalArgumentException("Saved user request history exceeds its limit")
    SessionSnapshot(
      history,
      strings(data("pendingNotes")),
      requests,
      notes,
      data("todos").arr.toList.map(t => Todo(t("text").str, TodoStatus.valueOf(t("status").str))),
      data("model").str
    )
