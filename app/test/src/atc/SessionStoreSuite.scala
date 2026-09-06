package atc

import atc.agent.{SessionSnapshot, SessionStore}
import atc.lib.{TaskNotes, Todo, TodoStatus}
import atc.llm.*
import atc.platform.Platform
import java.nio.file.{Files, FileAlreadyExistsException}
import java.nio.file.attribute.PosixFilePermissions

class SessionStoreSuite extends munit.FunSuite:
  private val history = List(
    Msg.User("Implement the feature; preserve compatibility."),
    Msg.Assistant(
      "checking",
      List(ToolCall("one", "run_scala", "println(42)")),
      Some(NativeTurn("p", "m", "native-payload"))
    ),
    Msg.ToolResults(List(ToolResult("one", "42", false))),
    Msg.Assistant("done", Nil, None),
  )
  private val snapshot = SessionSnapshot(
    history,
    List("pending notice"),
    List("Implement the feature"),
    TaskNotes("implement", List("preserve compatibility"), List("read the source"), List("run tests")),
    List(Todo("test", TodoStatus.Pending)),
    "p/m"
  )

  test("portable sessions retain task state and messages but omit native provider payloads"):
    val text = SessionStore.encode(snapshot)
    assert(!text.contains("native-payload"))
    val loaded = SessionStore.decode(text)
    assertEquals(loaded.task, snapshot.task)
    assertEquals(loaded.todos, snapshot.todos)
    assertEquals(loaded.pendingNotes, snapshot.pendingNotes)
    assertEquals(loaded.history(1), Msg.Assistant("checking", List(ToolCall("one", "run_scala", "println(42)")), None))

  test("save creates an owner-only file and never overwrites an existing file"):
    val root = Files.createTempDirectory("atc-session-store").nn
    val path = root.resolve("session.json").nn
    SessionStore.write(path, snapshot)
    assertEquals(SessionStore.read(path).task, snapshot.task)
    if !Platform.isWindows then
      assertEquals(Files.getPosixFilePermissions(path), PosixFilePermissions.fromString("rw-------"))
    val before = Files.readString(path)
    intercept[FileAlreadyExistsException](SessionStore.write(path, snapshot.copy(model = "other")))
    assertEquals(Files.readString(path), before)

  test("malformed tool histories are rejected before a session can be resumed"):
    val broken = snapshot.copy(history = history.take(2))
    intercept[IllegalArgumentException](SessionStore.decode(SessionStore.encode(broken)))
    val mismatched =
      snapshot.copy(history = history.updated(2, Msg.ToolResults(List(ToolResult("other", "42", false)))))
    intercept[IllegalArgumentException](SessionStore.decode(SessionStore.encode(mismatched)))

  test("automatic saves use a stable path for each canonical working directory"):
    val home = Files.createTempDirectory("atc-session-home").nn
    val first = Files.createDirectory(home.resolve("first")).nn
    val second = Files.createDirectory(home.resolve("second")).nn
    val path = SessionStore.autoSavePath(home, first)
    assertEquals(path, SessionStore.autoSavePath(home, first.resolve(".").nn))
    assertNotEquals(path, SessionStore.autoSavePath(home, second))
    assert(path.startsWith(home.resolve(".atc/sessions")))

  test("checkpoints replace the last save and preserve it when a new save fails"):
    val root = Files.createTempDirectory("atc-session-checkpoint").nn
    val path = root.resolve("last.json").nn
    SessionStore.checkpoint(path, snapshot)
    SessionStore.checkpoint(path, snapshot.copy(model = "updated"))
    assertEquals(SessionStore.read(path).model, "updated")
    if !Platform.isWindows then
      assertEquals(Files.getPosixFilePermissions(path), PosixFilePermissions.fromString("rw-------"))
    val before = Files.readString(path)
    val oversized = snapshot.copy(history = List(Msg.User("x" * (8 * 1024 * 1024))))
    intercept[IllegalArgumentException](SessionStore.checkpoint(path, oversized))
    assertEquals(Files.readString(path), before)
    val files = Files.list(root).nn
    try assertEquals(files.count(), 1L)
    finally files.close()

  test("empty sessions do not replace previous work, while user-run code and task notes are retained"):
    val empty = SessionSnapshot(Nil, Nil, Nil, TaskNotes(), Nil, "p/m")
    assert(!empty.nonEmpty)
    assert(empty.copy(pendingNotes = List("User ran: println(42)")).nonEmpty)
    assert(empty.copy(task = TaskNotes(goal = "finish the tests")).nonEmpty)
    assert(empty.copy(history = List(Msg.User("hello"))).nonEmpty)
