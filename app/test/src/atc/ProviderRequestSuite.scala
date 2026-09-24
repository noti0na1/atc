package atc

import atc.config.{ModelConfig, ModelSpec}
import atc.llm.*
import atc.agent.{Agent, AgentEnvironment, AgentMessages, ToolRunner, TurnOutcome}
import atc.config.Config
import atc.perms.{Decision, Policy}
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicReference

class ProviderRequestSuite extends munit.FunSuite:
  private val answer =
    """{"id":"one","object":"chat.completion","created":1,"model":"test","choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}],"usage":{"prompt_tokens":42,"completion_tokens":2,"total_tokens":44}}"""

  private def withModel(response: ujson.Value => (String, String))(test: (ChatModel, () => ujson.Value) => Unit): Unit =
    val received = AtomicReference[ujson.Value](ujson.Null)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).nn
    server.createContext(
      "/",
      exchange =>
        try
          val request = ujson.read(String(exchange.getRequestBody.nn.readAllBytes().nn, UTF_8))
          received.set(request)
          val (contentType, body) = response(request)
          val bytes = body.getBytes(UTF_8)
          exchange.getResponseHeaders.nn.add("Content-Type", contentType)
          exchange.sendResponseHeaders(200, bytes.length.toLong)
          exchange.getResponseBody.nn.write(bytes)
        finally exchange.close()
    )
    server.start()
    val model = ChatModel.create(ModelSpec(
      "test",
      "test",
      "openai",
      "test",
      Some(s"http://127.0.0.1:${server.getAddress.getPort}"),
      Some("test"),
      ModelConfig(maxTokens = Some(1234), temperature = Some(0.25)),
    ))
    try test(model, () => received.get().nn)
    finally
      model.close()
      server.stop(0)

  test("chat streams request usage and return it for cost and context accounting"):
    withModel { request =>
      val event =
        """{"id":"one","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}"""
      val usage =
        """{"id":"one","object":"chat.completion.chunk","created":1,"model":"test","choices":[],"usage":{"prompt_tokens":42,"completion_tokens":2,"total_tokens":44}}"""
      val includeUsage = request.obj.get("stream_options").exists(_.obj.get("include_usage").contains(ujson.Bool(true)))
      val frames = List(event) ++ Option.when(includeUsage)(usage) :+ "[DONE]"
      "text/event-stream" -> frames.map(frame => s"data: $frame\n\n").mkString
    } { (model, request) =>
      val completion =
        model.complete("test", List(Msg.User("hello")), Nil, StreamSink(_ => ()), () => false)
      assertEquals(completion.text, "done")
      assertEquals(completion.usage, TokenUsage(42, 2))
      assertEquals(request()("max_completion_tokens").num, 1234.0)
    }

  test("auxiliary chat calls honor configured output and sampling settings"):
    withModel(_ => "application/json" -> answer) { (model, request) =>
      for thinking <- List(false, true) do
        assertEquals(model.simple(None, "hello", thinking).text, "done")
        assertEquals(request()("max_completion_tokens").num, 1234.0)
        assertEquals(request()("temperature").num, 0.25)
        assert(!request().obj.contains("stream_options"))
    }

  private def chunk(delta: ujson.Obj, finish: ujson.Value = ujson.Null): String = ujson.write(ujson.Obj(
    "id" -> "one",
    "object" -> "chat.completion.chunk",
    "created" -> 1,
    "model" -> "test",
    "choices" -> ujson.Arr(ujson.Obj("index" -> 0, "delta" -> delta, "finish_reason" -> finish)),
  ))

  private def events(chunks: String*): (String, String) =
    "text/event-stream" -> chunks.map(value => s"data: $value\n\n").mkString

  test("reasoning in a mixed chunk precedes answer text and empty deltas stay invisible"):
    withModel(_ =>
      events(
        chunk(ujson.Obj("role" -> "assistant", "reasoning_content" -> "First thought", "content" -> "")),
        chunk(ujson.Obj("reasoning_content" -> "last thought", "content" -> "Yes,")),
        chunk(ujson.Obj("reasoning_content" -> "", "content" -> " it works.")),
        chunk(ujson.Obj(), ujson.Str("stop")),
        "[DONE]",
      )
    ) { (model, _) =>
      val shown = collection.mutable.ListBuffer[(String, String)]()
      val sink = StreamSink(text => shown += "answer" -> text, onThinking = text => shown += "thinking" -> text)
      val result = model.complete("test", List(Msg.User("hello")), Nil, sink, () => false)
      assertEquals(result.text, "Yes, it works.")
      assertEquals(
        shown.toList,
        List(
          "thinking" -> "First thought",
          "thinking" -> "last thought",
          "answer" -> "Yes,",
          "answer" -> " it works."
        )
      )
    }

  test("empty primary reasoning falls back to the alternate field without duplicating reasoning"):
    withModel(_ =>
      events(
        chunk(ujson.Obj("reasoning_content" -> "", "reasoning" -> "thought")),
        chunk(ujson.Obj("reasoning_content" -> "next", "reasoning" -> "next")),
        chunk(ujson.Obj("content" -> "done"), ujson.Str("stop")),
        "[DONE]",
      )
    ) { (model, _) =>
      val thoughts = StringBuilder()
      model.complete(
        "test",
        List(Msg.User("hello")),
        Nil,
        StreamSink(_ => (), onThinking = thoughts.append(_)),
        () => false
      )
      assertEquals(thoughts.toString, "thoughtnext")
    }

  for ending <- List("", "data: [DONE]\n\n") do
    test(s"missing finish marker preserves text and resumes safely (done=${ending.nonEmpty})"):
      var requests = 0
      withModel { _ =>
        requests += 1
        if requests == 1 then
          val first = events(chunk(ujson.Obj("role" -> "assistant", "content" -> "Partial answer")))
          first._1 -> (first._2 + ending)
        else events(chunk(ujson.Obj("content" -> "Finished answer"), ujson.Str("stop")), "[DONE]")
      } { (model, request) =>
        val ui = RecordingUI()
        val agent = Agent(
          Config(),
          AgentEnvironment("/test", "test"),
          Policy(Nil, Nil, Nil, _ => Decision.Deny),
          ui,
          model,
          None,
          None
        )
        val runner = new ToolRunner:
          val tools = Nil
          def run(call: ToolCall): ToolResult = fail("An incomplete stream must not execute tools")
        assertEquals(agent.runTurn(runner, "hello", () => false), TurnOutcome.Finished)
        assertEquals(requests, 2)
        assert(agent.history.contains(Msg.Assistant("Partial answer", Nil, None)))
        assert(agent.history.contains(Msg.Continuation(AgentMessages.truncationContinuation)))
        assertEquals(request()("messages").arr.last("content").str, AgentMessages.truncationContinuation)
        assert(ui.warnings.exists(_.contains("finish marker")))
      }

  test("a cut-off tool call is discarded even when its arguments are valid JSON"):
    val calls = ujson.Arr(ujson.Obj(
      "index" -> 0,
      "id" -> "call-one",
      "type" -> "function",
      "function" -> ujson.Obj("name" -> "run_scala", "arguments" -> "{\"code\":\"println(1)\"}")
    ))
    withModel(_ => events(chunk(ujson.Obj("role" -> "assistant", "tool_calls" -> calls)), "[DONE]")) { (model, _) =>
      val result = model.complete("test", List(Msg.User("hello")), Nil, StreamSink(_ => ()), () => false)
      assertEquals(result.stop, CompletionStop.Incomplete)
      assertEquals(result.toolCalls, Nil)
      assertEquals(result.native, None)
    }

  test("incomplete streams retain reported usage and do not turn reasoning into answer text"):
    val usage =
      """{"id":"one","object":"chat.completion.chunk","created":1,"model":"test","choices":[],"usage":{"prompt_tokens":42,"completion_tokens":2,"total_tokens":44}}"""
    withModel(_ => events(chunk(ujson.Obj("reasoning_content" -> "unfinished thought")), usage)) { (model, _) =>
      val result = model.complete("test", List(Msg.User("hello")), Nil, StreamSink(_ => ()), () => false)
      assertEquals(result.stop, CompletionStop.Incomplete)
      assertEquals(result.text, "")
      assertEquals(result.usage, TokenUsage(42, 2))
    }

  test("deltas after the finish chunk cannot add stray answer or thinking blocks"):
    withModel(_ =>
      events(
        chunk(ujson.Obj("content" -> "done"), ujson.Str("stop")),
        chunk(ujson.Obj("content" -> "stray answer", "reasoning_content" -> "stray thought")),
        "[DONE]",
      )
    ) { (model, _) =>
      val shown = collection.mutable.ListBuffer[String]()
      val result = model.complete(
        "test",
        List(Msg.User("hello")),
        Nil,
        StreamSink(shown += _, onThinking = shown += _),
        () => false
      )
      assertEquals(result.text, "done")
      assertEquals(shown.toList, List("done"))
    }

  /** A local server answering every request with `respond(method, path, body)` as a status,
    * a content type and a body; the requests are recorded. */
  private def withServer(respond: (String, String, String) => (Int, String, String))(
    test: (String, () => List[String]) => Unit
  ): Unit =
    val requests = java.util.concurrent.ConcurrentLinkedQueue[String]()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).nn
    server.createContext(
      "/",
      exchange =>
        try
          val method = exchange.getRequestMethod.nn
          val target = exchange.getRequestURI.nn.getPath.nn
          val body = String(exchange.getRequestBody.nn.readAllBytes().nn, UTF_8)
          requests.add(s"$method $target $body".trim)
          val (status, contentType, reply) = respond(method, target, body)
          val bytes = reply.getBytes(UTF_8)
          exchange.getResponseHeaders.nn.add("Content-Type", contentType)
          exchange.sendResponseHeaders(status, bytes.length.toLong)
          exchange.getResponseBody.nn.write(bytes)
        finally exchange.close()
    )
    server.start()
    import scala.jdk.CollectionConverters.*
    try test(s"http://127.0.0.1:${server.getAddress.getPort}", () => requests.asScala.toList)
    finally server.stop(0)

  private def endpoint(api: String, url: String) = ModelSpec("p", "", api, "", Some(url), Some("test"), ModelConfig())

  test("an OpenAI-compatible provider's models are listed with the context window they report"):
    val list =
      """{"object":"list","data":[
        {"id":"vendor/big","object":"model","created":1,"owned_by":"x","name":"Big","context_length":262144},
        {"id":"local","object":"model","created":1,"owned_by":"x","max_model_len":32768},
        {"id":"plain","object":"model","created":1,"owned_by":"x"}]}"""
    withServer((_, _, _) => (200, "application/json", list)) { (url, requests) =>
      val models = ChatModel.listModels(endpoint("openai", url))
      assertEquals(requests(), List("GET /models"))
      assertEquals(models.map(_.ref), List("p/vendor/big", "p/local", "p/plain"))
      assertEquals(models.map(_.settings.contextWindow.map(_.toInt)), List(Some(262144), Some(32768), None))
      assertEquals(models.map(_.displayName), List(Some("Big"), None, None))
      assertEquals(models.head.baseUrl, Some(url))
    }

  test("an Anthropic provider's models come with their input limit, effort levels and thinking support"):
    def support(on: Boolean) = s"""{"supported":$on}"""
    def capabilities(effort: String, adaptive: Boolean) =
      s"""{"batch":${support(true)},"citations":${support(true)},"code_execution":${support(true)},
         "context_management":{"supported":false,"clear_thinking_20251015":null,"clear_tool_uses_20250919":null,"compact_20260112":null},
         "effort":$effort,"image_input":${support(true)},"pdf_input":${support(true)},
         "structured_outputs":${support(true)},
         "thinking":{"supported":true,"types":{"adaptive":${support(adaptive)},"enabled":${support(true)}}}}"""
    val effort =
      s"""{"supported":true,"low":${support(true)},"medium":${support(true)},"high":${support(true)},"max":${support(
          false
        )}}"""
    val list =
      s"""{"data":[
        {"id":"claude-new","type":"model","display_name":"Claude New","created_at":"2026-01-01T00:00:00Z",
         "max_input_tokens":1000000,"max_tokens":128000,"capabilities":${capabilities(effort, adaptive = true)}},
        {"id":"claude-old","type":"model","display_name":"Claude Old","created_at":"2024-01-01T00:00:00Z",
         "capabilities":${capabilities(
          """{"supported":false,"low":{"supported":false},"medium":{"supported":false},"high":{"supported":false},"max":{"supported":false}}""",
          adaptive = false
        )}},
        {"id":"claude-bare","type":"model","display_name":"Bare","created_at":"2024-01-01T00:00:00Z"}],
        "has_more":false,"first_id":"claude-new","last_id":"claude-bare"}"""
    withServer((_, _, _) => (200, "application/json", list)) { (url, requests) =>
      val models = ChatModel.listModels(endpoint("anthropic", url))
      assertEquals(requests(), List("GET /v1/models"))
      assertEquals(models.map(_.modelId), List("claude-new", "claude-old", "claude-bare"))
      assertEquals(models.map(_.displayName), List(Some("Claude New"), Some("Claude Old"), Some("Bare")))
      assertEquals(models.map(_.settings.contextWindow.map(_.toInt)), List(Some(1000000), None, None))
      assertEquals(models.map(_.settings.efforts), List(Some(List("low", "medium", "high")), Some(Nil), None))
      assertEquals(models.map(_.settings.thinking), List(None, Some(false), None))
      assertEquals(ChatModel.create(models.head).efforts, List("low", "medium", "high"))
    }

  test("a switched effort applies to the next request"):
    withServer((_, _, _) => (200, "application/json", answer)) { (url, requests) =>
      val spec =
        endpoint("openai", url).copy(alias = "m", modelId = "m", settings = ModelConfig(reasoning = Some("low")))
      val model = ChatModel.create(spec)
      try
        assertEquals(model.efforts, atc.config.Config.ReasoningEfforts)
        assertEquals(model.effort, Some("low"))
        model.simple(None, "one")
        model.effort = Some("high")
        model.simple(None, "two")
        model.effort = None
        model.simple(None, "three")
        val efforts = requests().map(r => ujson.read(r.dropWhile(_ != '{')).obj.get("reasoning_effort").map(_.str))
        assertEquals(efforts, List(Some("low"), Some("high"), None))
      finally model.close()
    }

  private val streamedDone =
    List(
      """{"id":"one","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}""",
      "[DONE]",
    ).map(frame => s"data: $frame\n\n").mkString

  private def searching(url: String) =
    endpoint("openai", url).copy(alias = "m", modelId = "m", settings = ModelConfig(webSearch = Some(true)))

  test("a provider that rejects web search gets the request again without it, and never again with it"):
    val rejection =
      """{"error":{"message":"Unrecognized request argument supplied: web_search_options","type":"invalid_request_error"}}"""
    withServer { (_, _, body) =>
      if body.contains("web_search_options") then (400, "application/json", rejection)
      else (200, "text/event-stream", streamedDone)
    } { (url, requests) =>
      val model = ChatModel.create(searching(url))
      try
        assert(model.webSearch)
        def ask() = model.complete("s", List(Msg.User("hi")), Nil, StreamSink(_ => ()), () => false).text
        assertEquals(ask(), "done")
        assert(!model.webSearch, "web search is off for the rest of the session")
        assertEquals(ask(), "done")
        assertEquals(requests().map(_.contains("web_search_options")), List(true, false, false))
      finally model.close()
    }

  test("an unrelated bad request is not retried and keeps web search on"):
    val rejection = """{"error":{"message":"messages: bad role","type":"invalid_request_error"}}"""
    withServer((_, _, _) => (400, "application/json", rejection)) { (url, requests) =>
      val model = ChatModel.create(searching(url))
      try
        intercept[Exception](model.complete("s", List(Msg.User("hi")), Nil, StreamSink(_ => ()), () => false))
        assert(model.webSearch)
        assertEquals(requests().size, 1)
      finally model.close()
    }
