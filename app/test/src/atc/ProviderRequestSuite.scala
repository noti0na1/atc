package atc

import atc.config.{ModelConfig, ModelSpec}
import atc.llm.*
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
        model.complete(SystemPrompt("test"), List(Msg.User("hello")), Nil, StreamSink(_ => ()), () => false)
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
