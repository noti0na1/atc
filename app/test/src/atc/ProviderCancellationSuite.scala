package atc

import atc.config.{ModelConfig, ModelSpec}
import atc.llm.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

class ProviderCancellationSuite extends munit.FunSuite:
  test("closing borrowed SDK transports leaves the shared executor and connection pool open"):
    val http = okhttp3.OkHttpClient()
    val backend = com.anthropic.backends.AnthropicBackend.builder().apiKey("test").build()
    val openai = com.openai.client.okhttp.OkHttpClient(http)
    val anthropic = com.anthropic.client.okhttp.OkHttpClient(http, backend)
    try
      Providers.borrowed(openai).close()
      Providers.borrowed(anthropic).close()
      assert(!http.dispatcher.executorService.isShutdown)
      openai.close()
      assert(http.dispatcher.executorService.isShutdown, "the owning model must still release resources")
    finally
      openai.close()
      anthropic.close()

  for api <- List("openai", "openai-responses", "anthropic") do
    test(s"$api: garbage collection between streamed requests does not close the model's executor"):
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).nn
      server.createContext(
        "/",
        exchange =>
          try
            exchange.getRequestBody.nn.readAllBytes()
            val body = events(api).getBytes(UTF_8)
            exchange.getResponseHeaders.nn.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.length.toLong)
            exchange.getResponseBody.nn.write(body)
          finally exchange.close()
      )
      server.start()
      val model = ChatModel.create(ModelSpec(
        "test",
        "test",
        api,
        "test",
        Some(s"http://127.0.0.1:${server.getAddress.getPort}"),
        Some("test"),
        ModelConfig()
      ))
      val request = ModelRequest()
      try
        for _ <- 1 to 4 do
          val result = request.run(() => false) {
            model.complete(SystemPrompt("test"), List(Msg.User("hello")), Nil, StreamSink(_ => ()), () => false)
          }
          assertEquals(result.text, "done")
          System.gc()
          Thread.sleep(100) // allow the SDK's phantom-reference cleanup thread to run
      finally
        model.close()
        server.stop(0)

  private def events(api: String): String = api match
    case "anthropic" =>
      val messages = List(
        """{"type":"message_start","message":{"id":"one","type":"message","role":"assistant","content":[],"model":"test","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":0}}}""",
        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"done"}}""",
        """{"type":"content_block_stop","index":0}""",
        """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":1}}""",
        """{"type":"message_stop"}""",
      )
      messages.map(message => s"event: ${ujson.read(message)("type").str}\ndata: $message\n\n").mkString
    case "openai-responses" =>
      val created =
        """{"type":"response.created","sequence_number":0,"response":{"id":"one","object":"response","created_at":1,"model":"test","status":"in_progress","output":[]}}"""
      val item =
        """{"type":"response.output_item.added","sequence_number":1,"output_index":0,"item":{"id":"m","type":"message","role":"assistant","status":"in_progress","content":[{"type":"output_text","text":"done","annotations":[]}]}}"""
      val event =
        """{"type":"response.completed","sequence_number":2,"response":{"id":"one","object":"response","created_at":1,"model":"test","status":"completed","output":[{"id":"m","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"done","annotations":[]}]}]}}"""
      s"event: response.created\ndata: $created\n\nevent: response.output_item.added\ndata: $item\n\nevent: response.completed\ndata: $event\n\n"
    case _ =>
      val event =
        """{"id":"one","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}"""
      s"data: $event\n\ndata: [DONE]\n\n"

  for
    api <- List("openai", "openai-responses", "anthropic")
    duringStream <- List(false, true)
  do
    test(
      s"$api: cancel ${if duringStream then "between streamed events" else "before headers"} and complete a new request"
    ):
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).nn
      val executor = Executors.newCachedThreadPool()
      server.setExecutor(executor)
      val entered = CountDownLatch(1)
      val streamed = CountDownLatch(1)
      val release = CountDownLatch(1)
      val auth = AtomicReference[String]()
      val count = AtomicInteger()
      server.createContext(
        "/",
        exchange =>
          exchange.getRequestBody.nn.readAllBytes()
          try
            val first = count.incrementAndGet() == 1
            auth.set(exchange.getRequestHeaders.nn.getFirst(if api == "anthropic" then "x-api-key"
            else "Authorization"))
            if first && !duringStream then
              entered.countDown()
              release.await(5, TimeUnit.SECONDS)
            exchange.getResponseHeaders.nn.add("Content-Type", "text/event-stream")
            if first && duringStream then
              exchange.sendResponseHeaders(200, 0)
              val frames = events(api).split("\n\n").toList
              val prefix = if api == "anthropic" then 3 else if api == "openai-responses" then 2 else 1
              exchange.getResponseBody.nn.write((frames.take(prefix).mkString("\n\n") + "\n\n").getBytes(UTF_8))
              exchange.getResponseBody.nn.flush()
              entered.countDown()
              release.await(5, TimeUnit.SECONDS)
            else
              val body = events(api).getBytes(UTF_8)
              exchange.sendResponseHeaders(200, body.length.toLong)
              exchange.getResponseBody.nn.write(body)
          catch case _: java.io.IOException | _: InterruptedException => ()
          finally exchange.close()
      )
      server.start()
      val spec = ModelSpec(
        "test",
        "test",
        api,
        "test",
        Some(s"http://127.0.0.1:${server.getAddress.getPort}"),
        Some("test"),
        ModelConfig()
      )
      val model = ChatModel.create(spec)
      val request = ModelRequest()
      val cancelled = AtomicBoolean(false)
      val failure = AtomicReference[Throwable]()
      def run(): Completion = model.complete(
        SystemPrompt("test"),
        List(Msg.User("hello")),
        Nil,
        StreamSink(_ => streamed.countDown(), _ => streamed.countDown()),
        () => cancelled.get()
      )
      val caller = Thread(() =>
        try request.run(() => cancelled.get())(run())
        catch case error: Throwable => failure.set(error)
        ()
      )
      caller.setDaemon(true)
      caller.start()
      try
        assert(entered.await(5, TimeUnit.SECONDS))
        assertEquals(auth.get(), if api == "anthropic" then "test" else "Bearer test")
        if duringStream then
          assert(streamed.await(5, TimeUnit.SECONDS))
          Thread.sleep(50)
        cancelled.set(true)
        caller.join(1500)
        assert(!caller.isAlive, "cancellation waited for the server")
        assert(failure.get().isInstanceOf[CancelledException])
        cancelled.set(false)
        assertEquals(request.run(() => false)(run()).text, "done")
      finally
        release.countDown()
        model.close()
        server.stop(0)
        executor.shutdownNow()
