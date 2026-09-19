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
