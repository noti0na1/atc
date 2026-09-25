package atc.llm

import atc.{Debug, Main}

import java.io.{BufferedReader, IOException, InputStream, InputStreamReader, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.concurrent.{CompletableFuture, ExecutionException, LinkedBlockingQueue, TimeUnit, TimeoutException}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** One running `claude -p` in stream-json mode, the protocol of the Claude Agent SDK. A reader thread answers
  * the CLI's MCP requests for ATC's tools and queues every other message for [[next]]. The CLI calls a tool
  * through MCP after it has streamed the `tool_use` block; the call is answered once [[answer]] supplies its
  * result, whichever of the two comes first. */
private[atc] final class ClaudeCli private (process: ClaudeCli.Process, tools: List[ToolSpec]):
  private val events = LinkedBlockingQueue[ujson.Value]()
  /** Tool calls the CLI made that have no result yet: `tool_use` id to control request id and JSON-RPC id. */
  private val calls = mutable.Map[String, (String, ujson.Value)]()
  /** Results that arrived before the CLI made their call. */
  private val results = mutable.Map[String, ToolResult]()
  /** Control requests sent to the CLI that await their response, by request id. */
  private val replies = mutable.Map[String, CompletableFuture[ujson.Value]]()
  @volatile private var ended = false

  private def begin(): Unit =
    val reader = Thread(() => readAll(), "atc-claude-code")
    reader.setDaemon(true)
    reader.start()

  /** Send the `initialize` request with the system prompt; returns the CLI's answer (its models and account). */
  def initialize(system: String): ujson.Value =
    request(ujson.Obj("subtype" -> "initialize", "systemPrompt" -> system))

  /** The current model's context window as the CLI knows it (`get_context_usage`). */
  def contextWindow(): Option[Long] =
    request(ujson.Obj("subtype" -> "get_context_usage")).objOpt.flatMap(_.get("rawMaxTokens")).flatMap(_.numOpt)
      .map(_.toLong)

  /** Switch the session to another model (`set_model`). */
  def setModel(model: String): Unit =
    request(ujson.Obj("subtype" -> "set_model", "model" -> model))
    ()

  /** Start a model turn with `text` as the user's message. */
  def prompt(text: String): Unit =
    send(ujson.Obj(
      "type" -> "user",
      "session_id" -> "",
      "parent_tool_use_id" -> ujson.Null,
      "message" -> ujson.Obj("role" -> "user", "content" -> text),
    ))

  /** The result of a tool call the CLI made or will make. */
  def answer(result: ToolResult): Unit = synchronized:
    calls.remove(result.callId) match
      case Some((request, id)) => reply(request, id, ClaudeCli.toolResult(result))
      case None => results(result.callId) = result

  /** The next message from the CLI, waiting for it. Throws `IOException` once the CLI has exited. */
  def next(): ujson.Value =
    val message = events.take()
    if message == ujson.Null then
      events.put(ujson.Null)
      throw IOException(
        s"Claude Code exited${process.diagnostics().trim.linesIterator.toList.lastOption.fold("")(": " + _)}"
      )
    message

  def close(): Unit =
    ended = true
    process.stop()

  private def request(body: ujson.Obj): ujson.Value =
    val id = UUID.randomUUID().toString
    val done = CompletableFuture[ujson.Value]()
    synchronized(replies(id) = done)
    send(ujson.Obj("type" -> "control_request", "request_id" -> id, "request" -> body))
    try done.get(ClaudeCli.RequestTimeoutSeconds, TimeUnit.SECONDS)
    catch
      case e: ExecutionException => throw e.getCause.nn
      case _: TimeoutException => throw IOException(s"Claude Code did not answer its ${body("subtype").str} request")
    finally synchronized(replies.remove(id))

  private def send(message: ujson.Value): Unit = process.input.synchronized:
    process.input.write((ujson.write(message) + "\n").getBytes(UTF_8))
    process.input.flush()

  private def readAll(): Unit =
    try
      val in = BufferedReader(InputStreamReader(process.output, UTF_8))
      var line = in.readLine()
      while line != null do
        val text = line.nn
        if text.trim.nonEmpty then
          try receive(ujson.read(text))
          catch case NonFatal(e) => Debug.log(s"claude-code: unreadable message ${text.take(200)}: ${Debug.message(e)}")
        line = in.readLine()
    catch case NonFatal(e) => if !ended then Debug.log(s"claude-code: reading failed: ${Debug.message(e)}")
    finally
      events.put(ujson.Null)
      val waiting = synchronized(replies.values.toList)
      waiting.foreach(_.completeExceptionally(IOException("Claude Code exited")))

  private def receive(message: ujson.Value): Unit =
    ClaudeCli.string(message, "type") match
      case Some("control_request") =>
        val id = message("request_id").str
        val request = message("request")
        ClaudeCli.string(request, "subtype") match
          case Some("mcp_message") => mcp(id, request("message"))
          case other => send(ujson.Obj(
              "type" -> "control_response",
              "response" -> ujson.Obj(
                "subtype" -> "error",
                "request_id" -> id,
                "error" -> s"ATC does not handle ${other.getOrElse("this request")}",
              ),
            ))
      case Some("control_response") =>
        val response = message("response")
        val waiting = synchronized(replies.get(response("request_id").str))
        waiting.foreach: done =>
          if ClaudeCli.string(response, "subtype").contains("success") then
            done.complete(response.obj.getOrElse("response", ujson.Obj()))
          else
            done.completeExceptionally(
              IOException(s"Claude Code: ${ClaudeCli.string(response, "error").getOrElse("request failed")}")
            )
      case Some("control_cancel_request") => ()
      case _ => events.put(message)

  /** One JSON-RPC message for ATC's MCP server. Every message, notifications included, gets a response. */
  private def mcp(request: String, message: ujson.Value): Unit =
    val id = message.obj.getOrElse("id", ujson.Num(0))
    ClaudeCli.string(message, "method") match
      case Some("initialize") =>
        val version = message.obj.get("params").flatMap(ClaudeCli.string(_, "protocolVersion"))
        reply(
          request,
          id,
          ujson.Obj(
            "protocolVersion" -> version.getOrElse(ClaudeCli.McpVersion),
            "capabilities" -> ujson.Obj("tools" -> ujson.Obj()),
            "serverInfo" -> ujson.Obj("name" -> ClaudeCli.Server, "version" -> Main.Version),
          ),
        )
      case Some("tools/list") =>
        val listed = tools.map: t =>
          ujson.Obj("name" -> t.name, "description" -> t.description, "inputSchema" -> ujson.read(t.parametersJson))
        reply(request, id, ujson.Obj("tools" -> ujson.Arr.from(listed)))
      case Some("tools/call") =>
        val toolUse =
          for
            params <- message.obj.get("params")
            meta <- params.obj.get("_meta")
            value <- ClaudeCli.string(meta, "claudecode/toolUseId")
          yield value
        toolUse match
          case None => reply(request, id, ClaudeCli.toolError("ATC runs its tools only within a model turn."))
          case Some(use) =>
            synchronized:
              results.remove(use) match
                case Some(result) => reply(request, id, ClaudeCli.toolResult(result))
                case None => calls(use) = (request, id)
      case _ => reply(request, id, ujson.Obj())

  private def reply(request: String, id: ujson.Value, result: ujson.Value): Unit =
    send(ujson.Obj(
      "type" -> "control_response",
      "response" -> ujson.Obj(
        "subtype" -> "success",
        "request_id" -> request,
        "response" -> ujson.Obj("mcp_response" -> ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "result" -> result)),
      ),
    ))

private[atc] object ClaudeCli:
  /** The MCP server name; the CLI names its tools `mcp__atc__<tool>`. */
  val Server = "atc"
  val ToolPrefix = s"mcp__${Server}__"
  private val McpVersion = "2025-06-18"
  private val RequestTimeoutSeconds = 60L

  /** A started CLI: its stdin and stdout, how to stop it, and what it wrote to stderr. */
  final class Process(
    val input: OutputStream,
    val output: InputStream,
    val stop: () => Unit,
    val diagnostics: () => String,
  )

  def start(process: Process, tools: List[ToolSpec]): ClaudeCli =
    val cli = new ClaudeCli(process, tools)
    cli.begin()
    cli

  /** Variables that would change what the CLI loads or who pays: an API key would replace the
    * subscription, an effort level would override `--effort`, and the others belong to a CLI
    * that runs ATC itself. */
  private val Removed = List(
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_AUTH_TOKEN",
    "CLAUDE_CODE_EFFORT_LEVEL",
    "CLAUDE_CODE_ENTRYPOINT",
    "CLAUDECODE",
    "NODE_OPTIONS",
  )

  /** CLAUDE.md files, auto memory, claude.ai connectors, the CLI's own compaction (ATC compacts),
    * updates and telemetry stay off. A tool call may wait for a permission prompt, so the MCP
    * timeout is a day. */
  private val Settings = Map(
    "CLAUDE_CODE_DISABLE_CLAUDE_MDS" -> "1",
    "CLAUDE_CODE_DISABLE_AUTO_MEMORY" -> "1",
    "ENABLE_CLAUDEAI_MCP_SERVERS" -> "false",
    "DISABLE_AUTO_COMPACT" -> "1",
    "DISABLE_AUTOUPDATER" -> "1",
    "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" -> "1",
    "MCP_TOOL_TIMEOUT" -> "86400000",
  )

  /** Start `claude` with `args` in an empty temporary directory, so no project file of the
    * working directory can configure it. */
  def launch(args: List[String]): Process =
    val dir = Files.createTempDirectory("atc-claude-code").nn
    val builder = ProcessBuilder(("claude" :: args).asJava).directory(dir.toFile)
    val env = builder.environment().nn
    Removed.foreach(env.remove)
    Settings.foreach((k, v) => env.put(k, v))
    val process =
      try builder.start().nn
      catch
        case e: IOException =>
          deleteQuietly(dir)
          throw IOException(
            "Claude Code (`claude`) is not on the PATH; install it from https://claude.com/claude-code and sign in",
            e,
          )
    process.onExit().nn.thenRun(() => deleteQuietly(dir))
    val errors = StringBuilder()
    val drain = Thread(
      () =>
        try
          val in = BufferedReader(InputStreamReader(process.getErrorStream.nn, UTF_8))
          var line = in.readLine()
          while line != null do
            errors.synchronized:
              errors.append(line.nn).append('\n')
              if errors.length > 8000 then errors.delete(0, errors.length - 8000)
            line = in.readLine()
        catch case NonFatal(_) => (),
      "atc-claude-code-stderr",
    )
    drain.setDaemon(true)
    drain.start()
    Process(
      process.getOutputStream.nn,
      process.getInputStream.nn,
      () => { process.destroy(); () },
      () => errors.synchronized(errors.toString),
    )

  private def deleteQuietly(dir: Path): Unit =
    try Files.deleteIfExists(dir)
    catch case NonFatal(_) => ()

  def string(value: ujson.Value, key: String): Option[String] =
    value.objOpt.flatMap(_.get(key)).flatMap(_.strOpt)

  private def toolResult(result: ToolResult): ujson.Value =
    ujson.Obj(
      "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> result.output)),
      "isError" -> result.isError,
    )

  private def toolError(text: String): ujson.Value = toolResult(ToolResult("", text, isError = true))
