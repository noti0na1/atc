package atc

import atc.host.*
import atc.lib.{IOCap, Todo, UserIO}
import atc.perms.*
import atc.sandbox.{ReplSession, SandboxConfig}

import java.nio.file.{Files, Path}
import scala.collection.mutable.ListBuffer

/** A recording test environment: a fresh temp root, a policy with a scripted
  * permission prompter, and a [[Host]] whose output, LLM calls, questions and
  * TODO updates are captured. Optionally opens sandbox REPL sessions on it.
  *
  * The `IOCap` for direct host calls is constructed here (its constructor is
  * `private[atc]`); it is only a label, so any instance will do.
  */
final class TestEnv(
  mkRules: Path => List[FileRule] = TestEnv.defaultRules,
  commands: List[String] = List("echo"),
  hosts: List[String] = Nil,
  prefix: String = "atc-test",
  denyCommands: List[String] = Nil,
  denyHosts: List[String] = Nil,
):
  val root: Path = Files.createTempDirectory(prefix).nn.toRealPath().nn

  // ── permission prompter (scripted; unanswered requests are denied) ──
  var decisions: List[Decision] = Nil
  val requests: ListBuffer[PermissionRequest] = ListBuffer()
  /** Called on every prompt before the scripted decision (e.g. to simulate a slow user). */
  var onRequest: PermissionRequest => Unit = _ => ()
  val prompter: PermissionPrompter = r =>
    requests += r
    onRequest(r)
    decisions match
      case d :: rest => decisions = rest; d
      case Nil => Decision.Deny

  val policy: Policy = Policy(mkRules(root), commands, hosts, prompter, denyCommands, denyHosts)

  // ── recorded host interactions ──
  val agentOut: StringBuilder = StringBuilder()
  /** User-visible output; classified segments are wrapped as `<...>`. */
  val userOut: StringBuilder = StringBuilder()
  val chats: ListBuffer[String] = ListBuffer()
  val classifiedChats: ListBuffer[String] = ListBuffer()
  val questions: ListBuffer[(String, List[String], Boolean)] = ListBuffer()
  var answers: List[Option[String]] = Nil
  var shownTodos: List[Todo] = Nil

  @volatile var session: Option[ReplSession] = None

  /** Commands that ran long enough to be shown live, and what they wrote while live. */
  val liveCommands: ListBuffer[String] = ListBuffer()
  val liveCommandOut: StringBuilder = StringBuilder()
  /** How many commands ran inside `whileCommandRuns` (the clock-pausing hook). */
  var commandsWrapped: Int = 0
  /** What the user was shown about spawned processes (started / input / exited). */
  val processEvents: ListBuffer[String] = ListBuffer()

  val output: HostOutput = new HostOutput:
    def print(agentText: String, userText: String): Unit =
      agentOut.append(agentText)
      session.foreach(_.printStream.print(agentText))
      userOut.append(if agentText == userText then userText else s"<$userText>")
    override def commandRunning(commandLine: String): Unit = liveCommands += commandLine
    override def whileCommandRuns[T](body: => T): T =
      commandsWrapped += 1
      // Mirror `App`: the time a command runs does not count against the
      // evaluation timeout of the snippet that launched it.
      session match
        case Some(s) =>
          s.clock.pause()
          try body
          finally s.clock.resume()
        case None => body
    override def processStarted(id: Int, commandLine: String): Unit =
      processEvents.synchronized(processEvents += s"p$id started: $commandLine")
    override def processInput(id: Int, text: String): Unit =
      processEvents.synchronized(processEvents += s"p$id < $text")
    override def processExited(id: Int, exitCode: Int): Unit =
      processEvents.synchronized(processEvents += s"p$id exited $exitCode")
    override def commandOutput(text: String): Unit = liveCommandOut.synchronized(liveCommandOut.append(text))

  val llm: HostLlm = new HostLlm:
    def chat(m: String): String = { chats += m; s"normal:$m" }
    def classifiedChat(m: String): String = { classifiedChats += m; s"safe:$m" }

  val ui: HostUi = new HostUi:
    def askUser(question: String, options: List[String], multiple: Boolean): Option[String] =
      questions += ((question, options, multiple))
      answers match
        case a :: rest => answers = rest; a
        case Nil => None
    def showTodos(items: List[Todo]): Unit = shownTodos = items

  val host: Host = Host(policy, root, output, llm, ui)

  /** Root capabilities for calling the host directly from tests. */
  given io: IOCap = new IOCap()
  given user: UserIO = new UserIO()

  // ── helpers ──
  def file(rel: String, content: String): Path =
    val p = root.resolve(rel).nn
    Option(p.getParent).foreach(Files.createDirectories(_))
    Files.writeString(p, content)
    p
  def dir(rel: String): Path = Files.createDirectories(root.resolve(rel)).nn
  def contents(rel: String): String = Files.readString(root.resolve(rel)).nn
  def existsOnDisk(rel: String): Boolean = Files.exists(root.resolve(rel))
  /** Relative to the root; listings already come relative when inside the root. */
  def rel(p: String): String =
    val path = Path.of(p)
    if path.isAbsolute then root.relativize(path).toString else p

  def clearOutput(): Unit =
    agentOut.clear(); userOut.clear()

  /** Re-install this env's host as the sandbox implementation. The installed
    * host is process-global (one sandbox per JVM), so a suite that keeps more
    * than one session alive must activate the right env before evaluating.
    * Otherwise a session picks up another env's host when its `api` object is
    * first forced, and its effects land in the wrong temp root. */
  def activate(): Unit = atc.sandbox.Sandbox.installHost(host)

  /** Open a sandbox session on this host (installs it as the sandbox API). */
  def newSession(
    safeMode: Boolean = true,
    timeoutMs: Option[Long] = Some(60000L),
    preambleOverride: Option[String] = None,
    mode: Mode = Mode.Full
  ): ReplSession =
    policy.mode = mode
    val s = ReplSession(SandboxConfig(safeMode, mode, timeoutMs), host, preambleOverride).init()
    session = Some(s)
    s

object TestEnv:
  /** The working directory is writable; nothing else is accessible. */
  val defaultRules: Path => List[FileRule] = root => List(FileRule(PathPattern(".", root), Some(Access.Write), None))

  /** Writable cwd with a classified `secrets/` directory and the `.env` component pattern. */
  val withSecrets: Path => List[FileRule] = root =>
    List(
      FileRule(PathPattern(".", root), Some(Access.Write), None),
      FileRule(PathPattern("secrets", root), None, Some(true)),
      FileRule(PathPattern(".env", root), None, Some(true)),
    )

  /** A second temp directory outside any root, with one readable file. */
  def outsideDir(content: String = "outside"): Path =
    val d = Files.createTempDirectory("atc-outside").nn.toRealPath().nn
    Files.writeString(d.resolve("o.txt"), content)
    d
