package atc

import atc.config.{ModelConfig, ModelSpec}
import atc.llm.*

import java.io.{BufferedReader, IOException, InputStream, InputStreamReader, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{ConcurrentLinkedQueue, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.jdk.CollectionConverters.*

/** The Claude Code adapter against a scripted CLI that speaks the stream-json protocol over in-memory pipes. */
class ClaudeCodeSuite extends munit.FunSuite:
  /** One direction of a CLI's stdio. Unlike `PipedInputStream`, it does not fail when a writing thread ends. */
  private final class Pipe:
    private val chunks = LinkedBlockingQueue[Array[Byte]]()
    private var current = Array.emptyByteArray
    private var position = 0
    private var ended = false
    val out: OutputStream = new OutputStream:
      def write(b: Int): Unit = write(Array(b.toByte), 0, 1)
      override def write(b: Array[Byte], off: Int, len: Int): Unit = if len > 0 then chunks.put(b.slice(off, off + len))
      override def close(): Unit = chunks.put(Array.emptyByteArray)
    val in: InputStream = new InputStream:
      def read(): Int =
        val one = Array[Byte](0)
        if read(one, 0, 1) < 0 then -1 else one(0) & 0xff
      override def read(b: Array[Byte], off: Int, len: Int): Int =
        if position == current.length && !ended then
          current = chunks.take()
          position = 0
          ended = current.isEmpty
        if ended then -1
        else
          val n = math.min(len, current.length - position)
          System.arraycopy(current, position, b, off, n)
          position += n
          n

  /** The CLI side of one launch: what it was started with, and the protocol as a script sees it. */
  private final class FakeCli(val args: List[String]):
    private val toCli = Pipe()
    private val fromCli = Pipe()
    val stopped = AtomicBoolean(false)
    private val lines = BufferedReader(InputStreamReader(toCli.in, UTF_8))
    val process = ClaudeCli.Process(
      toCli.out,
      fromCli.in,
      () =>
        stopped.set(true)
        fromCli.out.close()
      ,
      () => "fake stderr line",
    )

    def send(message: ujson.Value): Unit = fromCli.out.write((ujson.write(message) + "\n").getBytes(UTF_8))

    /** Messages read while waiting for another kind, as the CLI reads stdin concurrently. */
    private val stash = scala.collection.mutable.Queue[ujson.Value]()

    /** The session's model, which `set_model` changes. */
    private var model = args.collectFirst { case a if a.startsWith("--model=") => a.stripPrefix("--model=") }

    /** The next message, after answering the model and context queries the CLI answers at once. */
    private def read(): ujson.Value =
      val line = lines.readLine()
      if line == null then throw IOException("the host closed the CLI's stdin")
      val message = ujson.read(line.nn)
      val query = if message("type").str == "control_request" then message("request")("subtype").str else ""
      query match
        case "set_model" =>
          model = Some(message("request")("model").str)
          control(message, ujson.Obj())
          read()
        case "get_context_usage" =>
          control(message, ujson.Obj("rawMaxTokens" -> Windows.getOrElse(model.getOrElse(""), 200000)))
          read()
        case _ => message

    private def control(request: ujson.Value, response: ujson.Value): Unit =
      send(ujson.Obj(
        "type" -> "control_response",
        "response" -> ujson.Obj("subtype" -> "success", "request_id" -> request("request_id"), "response" -> response),
      ))

    /** Read until the host closes stdin. */
    def drain(): Unit =
      while true do read()

    /** The next message that `wanted` accepts; others are kept for later. */
    def receive(wanted: ujson.Value => Boolean = _ => true): ujson.Value =
      stash.find(wanted) match
        case Some(found) =>
          stash.remove(stash.indexOf(found))
          found
        case None =>
          var message = read()
          while !wanted(message) do
            stash.enqueue(message)
            message = read()
          message

    /** Answer `initialize` and, when the CLI has ATC's MCP server, connect to it as the CLI does. */
    def handshake(): ujson.Value =
      val init = receive(m => m("type").str == "control_request")
      assertEquals(init("request")("subtype").str, "initialize")
      send(ujson.Obj(
        "type" -> "control_response",
        "response" -> ujson.Obj("subtype" -> "success", "request_id" -> init("request_id"), "response" -> Models),
      ))
      if args.contains("--mcp-config") then
        val initialized = mcp("initialize", ujson.Obj("protocolVersion" -> "2025-11-25"))
        assertEquals(initialized("serverInfo")("name").str, "atc")
        mcp("notifications/initialized", ujson.Obj(), notification = true)
        val listed = mcp("tools/list", ujson.Obj())
        assertEquals(listed("tools").arr.map(_("name").str).toList, List("run_scala"))
      init("request")

    private var nextId = 0
    /** Send an MCP request to the host's server and wait for its result. */
    def mcp(method: String, params: ujson.Obj, notification: Boolean = false): ujson.Value =
      nextId += 1
      val message = ujson.Obj("jsonrpc" -> "2.0", "method" -> method, "params" -> params)
      if !notification then message("id") = nextId
      send(ujson.Obj(
        "type" -> "control_request",
        "request_id" -> s"cli-$nextId",
        "request" -> ujson.Obj("subtype" -> "mcp_message", "server_name" -> "atc", "message" -> message),
      ))
      val id = s"cli-$nextId"
      val response = receive(m => m("type").str == "control_response" && m("response")("request_id").str == id)
      response("response")("response")("mcp_response")("result")

    /** The text of the next user message. */
    def user(): String =
      receive(_("type").str == "user")("message")("content").str

    private def event(e: ujson.Obj): Unit =
      send(ujson.Obj("type" -> "stream_event", "event" -> e, "parent_tool_use_id" -> ujson.Null))
    private def block(content: ujson.Obj): Unit =
      send(ujson.Obj(
        "type" -> "assistant",
        "message" -> ujson.Obj("role" -> "assistant", "content" -> ujson.Arr(content)),
        "parent_tool_use_id" -> ujson.Null,
      ))
    private def end(reason: String): Unit =
      event(ujson.Obj(
        "type" -> "message_delta",
        "delta" -> ujson.Obj("stop_reason" -> reason),
        "usage" -> ujson.Obj("input_tokens" -> 10, "cache_read_input_tokens" -> 5, "output_tokens" -> 3),
      ))
      event(ujson.Obj("type" -> "message_stop"))

    /** A response that ends the CLI's turn with `text`. */
    def answer(text: String, thinking: String = ""): Unit =
      event(ujson.Obj("type" -> "message_start"))
      if thinking.nonEmpty then
        event(ujson.Obj("type" -> "content_block_start", "content_block" -> ujson.Obj("type" -> "thinking")))
        event(ujson.Obj(
          "type" -> "content_block_delta",
          "delta" -> ujson.Obj("type" -> "thinking_delta", "thinking" -> thinking),
        ))
        block(ujson.Obj("type" -> "thinking", "thinking" -> thinking, "signature" -> "sig"))
      event(ujson.Obj("type" -> "content_block_start", "content_block" -> ujson.Obj("type" -> "text", "text" -> "")))
      event(ujson.Obj("type" -> "content_block_delta", "delta" -> ujson.Obj("type" -> "text_delta", "text" -> text)))
      block(ujson.Obj("type" -> "text", "text" -> text))
      end("end_turn")
      send(ujson.Obj("type" -> "result", "subtype" -> "success", "is_error" -> false, "stop_reason" -> "end_turn"))

    /** A response that calls `run_scala` once per code, then the MCP calls in order; returns their results. */
    def call(codes: (String, String)*): List[ujson.Value] =
      event(ujson.Obj("type" -> "message_start"))
      codes.foreach: (id, code) =>
        block(ujson.Obj(
          "type" -> "tool_use",
          "id" -> id,
          "name" -> "mcp__atc__run_scala",
          "input" -> ujson.Obj("code" -> code)
        ))
      end("tool_use")
      codes.toList.map: (id, code) =>
        mcp(
          "tools/call",
          ujson.Obj(
            "name" -> "run_scala",
            "arguments" -> ujson.Obj("code" -> code),
            "_meta" -> ujson.Obj("claudecode/toolUseId" -> id),
          ),
        )

  private val Windows = Map("opus" -> 1000000, "haiku" -> 200000)

  private val Models = ujson.Obj(
    "models" -> ujson.Arr(
      ujson.Obj("value" -> "default", "displayName" -> "Default (recommended)"),
      ujson.Obj(
        "value" -> "opus",
        "displayName" -> "Opus",
        "supportedEffortLevels" -> ujson.Arr("low", "high"),
        "supportsAdaptiveThinking" -> true,
      ),
      ujson.Obj("value" -> "haiku", "displayName" -> "Haiku"),
    )
  )

  /** A model whose launches run `scripts` in order, each on its own thread. */
  private final class Harness(scripts: (FakeCli => Unit)*):
    val launches = ConcurrentLinkedQueue[FakeCli]()
    val failure = AtomicReference[Throwable]()
    private val remaining = scripts.iterator
    val model = ClaudeCodeModel(spec, launch)
    def launch(args: List[String]): ClaudeCli.Process =
      val cli = FakeCli(args)
      launches.add(cli)
      val script = synchronized(if remaining.hasNext then remaining.next() else (_: FakeCli) => ())
      val thread = Thread { () =>
        try script(cli)
        catch
          case e: IOException if cli.stopped.get() => ()
          case e: Throwable =>
            failure.set(e)
            cli.process.stop()
      }
      thread.setDaemon(true)
      thread.start()
      cli.process
    def check(): Unit = Option(failure.get()).foreach(e => throw e)

  private val spec = ModelSpec("cc", "opus", "claude-code", "opus", None, None, ModelConfig())
  private val tools =
    List(ToolSpec("run_scala", "Run Scala.", """{"type":"object","properties":{"code":{"type":"string"}}}"""))
  private val quiet = StreamSink(_ => ())

  private def completion(h: Harness, history: List[Msg], sink: StreamSink = quiet): Completion =
    val c =
      try h.model.complete("system", history, tools, sink, () => false)
      finally h.check()
    h.check()
    c

  private def assistant(c: Completion): Msg.Assistant = Msg.Assistant(c.text, c.toolCalls, c.native)

  test("the CLI starts with no built-in tools, settings or commands, and only ATC's server allowed"):
    val h = Harness(cli => { cli.handshake(); cli.user(); cli.answer("hi") })
    completion(h, List(Msg.User("hello")))
    val args = h.launches.peek().nn.args
    List(
      "-p",
      "--tools=",
      "--setting-sources=",
      "--strict-mcp-config",
      "--disable-slash-commands",
      "--no-session-persistence",
      "--permission-mode=dontAsk",
      "--permission-prompts=none",
      "--allowedTools=mcp__atc",
      "--model=opus",
      "--thinking-display=summarized",
    ).foreach(flag => assert(args.contains(flag), s"$flag missing from ${args.mkString(" ")}"))
    assertEquals(ujson.read(args(args.indexOf("--mcp-config") + 1))("mcpServers")("atc")("type").str, "sdk")

  test("the system prompt goes in the initialize request"):
    val init = AtomicReference[ujson.Value]()
    val h = Harness(cli => { init.set(cli.handshake()); cli.user(); cli.answer("hi") })
    completion(h, List(Msg.User("hello")))
    assertEquals(init.get()("systemPrompt").str, "system")

  test("a tool call returns to the agent, and the next request answers it in the same session"):
    val results = AtomicReference[List[ujson.Value]]()
    val h = Harness: cli =>
      cli.handshake()
      assertEquals(cli.user(), "compute")
      results.set(cli.call("t1" -> "1 + 1", "t2" -> "2 + 2"))
      cli.answer("2 and 4")
      assertEquals(cli.user(), "thanks")
      cli.answer("welcome")
    val first = List(Msg.User("compute"))
    val calls = completion(h, first)
    assertEquals(calls.toolCalls.map(c => (c.id, c.name)), List("t1" -> "run_scala", "t2" -> "run_scala"))
    assertEquals(ujson.read(calls.toolCalls.head.arguments)("code").str, "1 + 1")
    assertEquals(calls.stop, CompletionStop.Complete)
    assertEquals(calls.usage, TokenUsage(15, 3, 5))
    val ran = Msg.ToolResults(List(ToolResult("t1", "2", isError = false), ToolResult("t2", "boom", isError = true)))
    val second = first :+ assistant(calls) :+ ran
    val streamed = StringBuilder()
    val answer = completion(h, second, StreamSink(t => streamed.append(t)))
    assertEquals(answer.text, "2 and 4")
    assertEquals(streamed.toString, "2 and 4")
    assertEquals(results.get().map(_("content")(0)("text").str), List("2", "boom"))
    assertEquals(results.get().map(_("isError").bool), List(false, true))
    assertEquals(completion(h, second :+ assistant(answer) :+ Msg.User("thanks")).text, "welcome")
    assertEquals(h.launches.size, 1)

  test("thinking streams to the sink and stays out of the answer"):
    val h = Harness(cli => { cli.handshake(); cli.user(); cli.answer("ten", thinking = "Count the primes.") })
    val thought = StringBuilder()
    val streamed = StringBuilder()
    val reply = completion(h, List(Msg.User("how many?")), StreamSink(streamed.append(_), _ => (), thought.append(_)))
    assertEquals(thought.toString, "Count the primes.")
    assertEquals(streamed.toString, "ten")
    assertEquals(reply.text, "ten")

  test("a history the session has not seen starts a new one, given the history as a transcript"):
    val transcript = AtomicReference[String]()
    val h = Harness(
      cli => { cli.handshake(); cli.user(); cli.answer("first") },
      cli => { cli.handshake(); transcript.set(cli.user()); cli.answer("second") },
    )
    val first = List(Msg.User("hello"))
    val reply = completion(h, first)
    // Context fitting or a compaction replaced what the session saw.
    val rewritten = List(Msg.User("summary of hello"), Msg.Assistant("first", Nil, None), Msg.User("next"))
    assertEquals(completion(h, rewritten).text, "second")
    assertEquals(h.launches.size, 2)
    assert(h.launches.peek().nn.stopped.get(), "the replaced session is stopped")
    assert(transcript.get().contains("[user]\nsummary of hello"), transcript.get())
    assert(transcript.get().endsWith("[user, the latest message]\nnext"), transcript.get())

  test("another system prompt or effort starts a new session"):
    val h = Harness(
      cli => { cli.handshake(); cli.user(); cli.answer("one") },
      cli => { cli.handshake(); cli.user(); cli.answer("two") },
    )
    val first = List(Msg.User("hello"))
    val reply = completion(h, first)
    h.model.effort = Some("high")
    completion(h, first :+ assistant(reply) :+ Msg.User("again"))
    assertEquals(h.launches.size, 2)
    assert(h.launches.asScala.last.args.contains("--effort=high"))

  test("a failed CLI turn is an error with the CLI's message, and ends the session"):
    val h = Harness: cli =>
      cli.handshake()
      cli.user()
      cli.send(ujson.Obj(
        "type" -> "result",
        "subtype" -> "success",
        "is_error" -> true,
        "result" -> "usage limit reached"
      ))
    val error = intercept[IOException](h.model.complete("system", List(Msg.User("hi")), tools, quiet, () => false))
    assertEquals(error.getMessage, "Claude Code: usage limit reached")
    assert(h.launches.peek().nn.stopped.get())

  test("a CLI that exits reports its last stderr line"):
    val h = Harness(cli => { cli.handshake(); cli.user(); cli.process.stop() })
    val error = intercept[IOException](h.model.complete("system", List(Msg.User("hi")), tools, quiet, () => false))
    assertEquals(error.getMessage, "Claude Code exited: fake stderr line")

  test("cancelling a request stops its CLI"):
    val waiting = java.util.concurrent.CountDownLatch(1)
    val h = Harness(cli => { cli.handshake(); cli.user(); waiting.countDown() })
    val cancelled = AtomicBoolean(false)
    val request = ModelRequest()
    val error = AtomicReference[Throwable]()
    val caller = Thread(() =>
      try
        request.run(() => cancelled.get())(h.model.complete("system", List(Msg.User("hi")), tools, quiet, () => false))
      catch case e => error.set(e)
      ()
    )
    caller.start()
    assert(waiting.await(5, TimeUnit.SECONDS))
    cancelled.set(true)
    request.recheck()
    caller.join(2000)
    assert(error.get().isInstanceOf[CancelledException], String.valueOf(error.get()))
    assert(h.launches.peek().nn.stopped.get())

  test("a one-shot call runs in its own CLI without tools"):
    val h = Harness(cli => { cli.handshake(); assertEquals(cli.user(), "name it"); cli.answer("Atlas") })
    assertEquals(h.model.simple(Some("be brief"), "name it", thinking = false).text, "Atlas")
    h.check()
    val args = h.launches.peek().nn.args
    assert(!args.contains("--mcp-config"))
    assert(args.contains("--thinking=disabled"))
    assert(!args.contains("--thinking-display=summarized"))
    assert(h.launches.peek().nn.stopped.get())

  test("listed models come from the initialize answer, with the windows the CLI reports"):
    val h = Harness(
      cli => {
        assertEquals(cli.args, List("auth", "status")); cli.send(ujson.Obj("loggedIn" -> true)); cli.process.stop()
      },
      cli => { cli.handshake(); cli.drain() },
    )
    val listed = ClaudeCodeModel(spec.copy(alias = "", modelId = ""), h.launch).listModels()
    h.check()
    assert(!h.launches.asScala.last.args.exists(_.startsWith("--model")))
    assertEquals(listed.map(_.modelId), List("opus", "haiku"))
    assertEquals(listed.map(_.settings.contextWindow.map(_.toInt)), List(Some(1000000), Some(200000)))
    assertEquals(listed.map(_.ref), List("cc/opus", "cc/haiku"))
    assertEquals(listed.head.settings.efforts, Some(List("low", "high")))
    assertEquals(listed(1).settings.efforts, Some(Nil))
    assertEquals(listed.head.displayName, Some("Opus"))

  test("listing tells the user to sign in to Claude Code when it is signed out"):
    val h = Harness(cli => { cli.send(ujson.Obj("loggedIn" -> false)); cli.process.stop() })
    val error = intercept[IOException](ClaudeCodeModel(spec, h.launch).listModels())
    assert(error.getMessage.contains("claude auth login"), error.getMessage)

  test("a configured model without a window learns it from the CLI; a configured window wins"):
    val h = Harness(cli => { cli.handshake(); cli.user(); cli.answer("hi") })
    assertEquals(h.model.contextWindow, None)
    completion(h, List(Msg.User("hello")))
    assertEquals(h.model.contextWindow, Some(1000000))
    val fixed =
      ClaudeCodeModel(spec.copy(settings = ModelConfig(contextWindow = atc.config.Tokens.from(300000L))), h.launch)
    assertEquals(fixed.contextWindow, Some(300000))

  test("a transcript shows tool calls and results and ends with the latest user message"):
    val text = ClaudeCodeModel.transcript(List(
      Msg.User("list files"),
      Msg.Assistant("Looking.", List(ToolCall("c1", "run_scala", """{"code":"ls(\".\")"}""")), None),
      Msg.ToolResults(List(ToolResult("c1", "a.txt", isError = false))),
      Msg.Assistant("One file.", Nil, None),
      Msg.User("read it"),
    ))
    List("[user]\nlist files", "[assistant]\nLooking.", "[tool call run_scala, id c1]", "[tool result, id c1]\na.txt")
      .foreach(part => assert(text.contains(part), text))
    assert(text.endsWith("[user, the latest message]\nread it"))
    assertEquals(ClaudeCodeModel.transcript(List(Msg.User("only"))), "only")
