package atc

import atc.config.{ModelConfig, ModelSpec, Tokens}
import atc.llm.*
import atc.llm.ChatGPTAuth.Tokens as SignIn

import com.sun.net.httpserver.HttpServer

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** Signing in with a ChatGPT plan and calling its backend, against a local
  * server that plays both auth.openai.com and chatgpt.com. */
class ChatGPTSuite extends munit.FunSuite:
  private case class Request(method: String, path: String, query: String, headers: Map[String, String], body: String):
    def form: Map[String, String] = body.split('&').toList.filter(_.nonEmpty).map { pair =>
      val (k, v) = pair.span(_ != '=')
      java.net.URLDecoder.decode(k, UTF_8).nn -> java.net.URLDecoder.decode(v.drop(1), UTF_8).nn
    }.toMap
    def json: ujson.Value = ujson.read(body)

  private def withServer(respond: Request => (Int, String, String))(test: (String, () => List[Request]) => Unit)
    : Unit =
    val requests = ConcurrentLinkedQueue[Request]()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).nn
    server.createContext(
      "/",
      exchange =>
        try
          val uri = exchange.getRequestURI.nn
          val headers =
            exchange.getRequestHeaders.nn.asScala.map((k, v) => k.toLowerCase -> v.asScala.mkString(",")).toMap
          val request = Request(
            exchange.getRequestMethod.nn,
            uri.getPath.nn,
            Option(uri.getRawQuery).getOrElse(""),
            headers,
            String(exchange.getRequestBody.nn.readAllBytes().nn, UTF_8),
          )
          requests.add(request)
          val (status, contentType, reply) = respond(request)
          val bytes = reply.getBytes(UTF_8)
          exchange.getResponseHeaders.nn.add("Content-Type", contentType)
          exchange.sendResponseHeaders(status, bytes.length.toLong)
          exchange.getResponseBody.nn.write(bytes)
        finally exchange.close()
    )
    server.start()
    try test(s"http://127.0.0.1:${server.getAddress.getPort}", () => requests.asScala.toList)
    finally server.stop(0)

  private def jwt(claims: ujson.Obj): String =
    def part(s: String) = Base64.getUrlEncoder.nn.withoutPadding().nn.encodeToString(s.getBytes(UTF_8))
    s"${part("""{"alg":"none"}""")}.${part(ujson.write(claims))}.signature"

  private val inAnHour: Long = System.currentTimeMillis() / 1000 + 3600
  private val expiry = ujson.Num(inAnHour.toDouble)

  /** A stream as the ChatGPT backend sends it: the items arrive in `response.output_item.done`
    * events, and the closing `response.completed` carries no output. */
  private def backendStream(items: String*): String =
    val created =
      """{"type":"response.created","sequence_number":0,"response":{"id":"one","object":"response","created_at":1,"model":"test","status":"in_progress","output":[]}}"""
    val done = items.zipWithIndex.map((item, i) =>
      s"""{"type":"response.output_item.done","sequence_number":${i + 1},"output_index":$i,"item":$item}"""
    )
    val completed =
      s"""{"type":"response.completed","sequence_number":${items.size +
          1},"response":{"id":"one","object":"response","created_at":1,"model":"test","status":"completed","output":[]}}"""
    (created +: done :+ completed).map(e => s"event: ${ujson.read(e)("type").str}\ndata: $e\n\n").mkString

  private val streamed = backendStream(
    """{"id":"m","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"done","annotations":[]}]}"""
  )

  private def tokenAnswer(access: String, refresh: String): String = ujson.write(ujson.Obj(
    "access_token" -> access,
    "refresh_token" -> refresh,
    "id_token" -> jwt(ujson.Obj(
      "email" -> "me@example.com",
      "https://api.openai.com/auth" -> ujson.Obj("chatgpt_account_id" -> "account-1"),
    )),
    "expires_in" -> 3600,
    "token_type" -> "Bearer",
  ))

  private def tempFile(): Path = Files.createTempDirectory("atc-chatgpt").nn.resolve("chatgpt-auth.json").nn

  private def query(url: String): Map[String, String] =
    url.dropWhile(_ != '?').drop(1).split('&').toList.map { pair =>
      val (k, v) = pair.span(_ != '=')
      k -> java.net.URLDecoder.decode(v.drop(1), UTF_8).nn
    }.toMap

  private def visit(url: String): Int =
    val client = okhttp3.OkHttpClient()
    scala.util.Using.resource(client.newCall(okhttp3.Request.Builder().url(url).build()).execute())(_.code)

  // ── signing in ──────────────────────────────────────────────────

  test("the browser's callback exchanges the code with its PKCE verifier and saves the tokens for the owner only"):
    val access = jwt(ujson.Obj("exp" -> expiry))
    withServer(_ => (200, "application/json", tokenAnswer(access, "refresh-1"))): (url, requests) =>
      val file = tempFile()
      val auth = ChatGPTAuth(file, url, List(0))
      scala.util.Using.resource(auth.begin()): login =>
        assert(login.url.startsWith(s"$url/oauth/authorize?"), login.url)
        val asked = query(login.url)
        assertEquals(asked("client_id"), ChatGPTAuth.ClientId)
        assertEquals(asked("response_type"), "code")
        assertEquals(asked("code_challenge_method"), "S256")
        assert(asked("scope").split(' ').contains("offline_access"), asked("scope"))
        val redirect = asked("redirect_uri")
        assert(redirect.matches("http://localhost:\\d+/auth/callback"), redirect)
        val callback = redirect.replace("localhost", "127.0.0.1")
        assertEquals(visit(s"$callback?code=the-code&state=${asked("state")}"), 200)
        val tokens = login.await(java.time.Duration.ofSeconds(10))
        assertEquals(tokens.map(_.map(_.access)), Some(Right(access)))
        val exchange = requests().find(_.path == "/oauth/token").get.form
        assertEquals(exchange("grant_type"), "authorization_code")
        assertEquals(exchange("code"), "the-code")
        assertEquals(exchange("redirect_uri"), redirect)
        val digest =
          java.security.MessageDigest.getInstance("SHA-256").nn.digest(exchange("code_verifier").getBytes(UTF_8))
        assertEquals(Base64.getUrlEncoder.nn.withoutPadding().nn.encodeToString(digest), asked("code_challenge"))
      val saved = auth.load().get
      assertEquals(saved.refresh, "refresh-1")
      assertEquals(saved.accountId, Some("account-1"))
      assertEquals(saved.email, Some("me@example.com"))
      assertEquals(saved.expiresAt, inAnHour * 1000)
      if !atc.platform.Platform.isWindows then
        assertEquals(
          java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
          "rw-------"
        )

  test("a callback of another attempt is refused, and the address the browser ended on completes the sign-in"):
    withServer(_ => (200, "application/json", tokenAnswer("access", "refresh"))): (url, requests) =>
      val auth = ChatGPTAuth(tempFile(), url, List(0))
      scala.util.Using.resource(auth.begin()): login =>
        val asked = query(login.url)
        val callback = asked("redirect_uri").replace("localhost", "127.0.0.1")
        assertEquals(visit(s"$callback?code=forged&state=other"), 400)
        assert(!login.callbackReceived)
        assertEquals(login.await(java.time.Duration.ZERO), None)
        assert(login.paste(s"http://localhost:1455/auth/callback?code=forged&state=other").isLeft)
        val pasted = login.paste(s"  ${asked("redirect_uri")}?code=real&state=${asked("state")}  ")
        assertEquals(pasted.map(_.access), Right("access"))
        assertEquals(requests().filter(_.path == "/oauth/token").map(_.form("code")), List("real"))
      assert(auth.load().isDefined)

  test("a sign-in the user declines in the browser fails with the reason"):
    withServer(_ => (500, "text/plain", "unexpected")): (url, requests) =>
      val auth = ChatGPTAuth(tempFile(), url, List(0))
      scala.util.Using.resource(auth.begin()): login =>
        val asked = query(login.url)
        val callback = asked("redirect_uri").replace("localhost", "127.0.0.1")
        assertEquals(visit(s"$callback?error=access_denied&error_description=No&state=${asked("state")}"), 400)
        assertEquals(login.await(java.time.Duration.ofSeconds(5)), Some(Left("access_denied: No")))
      assert(requests().isEmpty, "no code, no exchange")
      assertEquals(auth.load(), None)

  // ── refreshing ──────────────────────────────────────────────────

  private def saved(file: Path, access: String, refresh: String, expiresIn: Long): Unit =
    Files.writeString(
      file,
      upickle.default.write(SignIn(access, refresh, Some("account-1"), System.currentTimeMillis() + expiresIn)),
    )

  test("tokens that expire soon are refreshed and the rotated refresh token is saved"):
    val fresh = jwt(ujson.Obj("exp" -> expiry))
    withServer(_ => (200, "application/json", tokenAnswer(fresh, "refresh-2"))): (url, requests) =>
      val file = tempFile()
      saved(file, "old", "refresh-1", expiresIn = 60_000)
      val auth = ChatGPTAuth(file, url, List(0))
      assertEquals(auth.current().access, fresh)
      val refresh = requests().single.json
      assertEquals(refresh("grant_type").str, "refresh_token")
      assertEquals(refresh("refresh_token").str, "refresh-1")
      assertEquals(refresh("client_id").str, ChatGPTAuth.ClientId)
      assertEquals(auth.load().map(_.refresh), Some("refresh-2"))
      assertEquals(auth.current().access, fresh, "fresh tokens are used as they are")
      assertEquals(requests().size, 1)
      if !atc.platform.Platform.isWindows then
        val lock = file.resolveSibling("chatgpt-auth.json.lock").nn
        assertEquals(
          java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(lock)),
          "rw-------"
        )

  test("a refresh waits for another process's refresh and uses the tokens it saved"):
    withServer(_ => (200, "application/json", tokenAnswer("mine", "refresh-3"))): (url, requests) =>
      val file = tempFile()
      saved(file, "old", "refresh-1", expiresIn = 0)
      val javaBin =
        Path.of(sys.props("java.home"), "bin", if atc.platform.Platform.isWindows then "java.exe" else "java")
      val holder = ProcessBuilder(
        javaBin.toString,
        "-cp",
        sys.props("java.class.path"),
        "atc.ChatGPTLockHolder",
        s"$file.lock",
      ).redirectError(ProcessBuilder.Redirect.INHERIT).nn.start().nn
      try
        val out = java.io.BufferedReader(java.io.InputStreamReader(holder.getInputStream.nn, UTF_8))
        assertEquals(out.readLine(), "locked")
        val access =
          java.util.concurrent.CompletableFuture.supplyAsync(() => ChatGPTAuth(file, url, List(0)).current().access)
        Thread.sleep(300) // it reads the expired tokens and waits for the lock
        saved(file, "theirs", "refresh-2", expiresIn = 3_600_000)
        holder.getOutputStream.nn.close()
        assertEquals(access.get(10, java.util.concurrent.TimeUnit.SECONDS), "theirs")
        assert(requests().isEmpty, "the refresh token the other process used is not used again")
      finally holder.destroy()

  test("a refresh token another process used gives way to the tokens it saved; otherwise sign in again"):
    val file = tempFile()
    val rejected = """{"error":"invalid_grant","error_description":"refresh_token_reused"}"""
    withServer { _ =>
      saved(file, "theirs", "refresh-2", expiresIn = 3_600_000)
      (400, "application/json", rejected)
    } { (url, _) =>
      saved(file, "old", "refresh-1", expiresIn = 0)
      assertEquals(ChatGPTAuth(file, url, List(0)).current().access, "theirs")
    }
    withServer(_ => (400, "application/json", rejected)): (url, _) =>
      saved(file, "old", "refresh-1", expiresIn = 0)
      val e = intercept[ChatGPTAuth.SignInNeeded](ChatGPTAuth(file, url, List(0)).current())
      assert(e.getMessage.contains("refresh_token_reused"), e.getMessage)
      assert(e.getMessage.contains("/providers"), e.getMessage)

  // ── the backend ─────────────────────────────────────────────────

  private def backend(url: String) = ModelSpec(
    "chatgpt",
    "gpt-x",
    "chatgpt",
    "gpt-x",
    Some(s"$url/backend-api/codex"),
    None,
    ModelConfig(maxTokens = Some(1000), temperature = Some(0.5)),
    Map("originator" -> "atc"),
  )

  test(
    "the backend gets the signed-in token and account, a prompt cache key and no output limit; a 401 renews the token"
  ):
    val renewed = jwt(ujson.Obj("exp" -> expiry))
    val seen = ConcurrentLinkedQueue[String]()
    withServer { r =>
      r.path match
        case "/oauth/token" => (200, "application/json", tokenAnswer(renewed, "refresh-2"))
        case _ =>
          seen.add(r.headers.getOrElse("authorization", ""))
          if r.headers.get("authorization").contains("Bearer stale") then (401, "application/json", "{}")
          else (200, "text/event-stream", streamed)
    } { (url, requests) =>
      val file = tempFile()
      saved(file, "stale", "refresh-1", expiresIn = 3_600_000)
      val model = ChatGPTModel(backend(url), ChatGPTAuth(file, url, List(0)))
      try
        val completion = model.complete("be brief", List(Msg.User("hi")), Nil, StreamSink(_ => ()), () => false)
        assertEquals(completion.text, "done")
        assertEquals(seen.asScala.toList, List("Bearer stale", s"Bearer $renewed"))
        val sent = requests().filter(_.path == "/backend-api/codex/responses").last
        assertEquals(sent.headers.get("chatgpt-account-id"), Some("account-1"))
        assertEquals(sent.headers.get("originator"), Some("atc"))
        val body = sent.json.obj
        assertEquals(body("stream"), ujson.Bool(true))
        assertEquals(body("store"), ujson.Bool(false))
        assertEquals(body("instructions").str, "be brief")
        assertEquals(body("prompt_cache_key").str, Providers.conversation)
        assert(!body.contains("max_output_tokens") && !body.contains("temperature"), body.keys.toString)
        assertEquals(body("reasoning")("summary").str, "auto", "a summary is asked for unless configured")
        assertEquals(model.maxOutputTokens, Some(1000), "the configured limit still reserves room")
        assertEquals(auth(file).refresh, "refresh-2")
      finally model.close()
    }

  private def auth(file: Path): SignIn = upickle.default.read[SignIn](Files.readString(file).nn)

  test("tool calls come from the streamed items, since the closing event carries none"):
    val call =
      """{"id":"fc","type":"function_call","status":"completed","call_id":"call-1","name":"run_scala","arguments":"{\"code\":\"1 + 1\"}"}"""
    withServer(_ => (200, "text/event-stream", backendStream(call))): (url, _) =>
      val file = tempFile()
      saved(file, "token", "refresh", expiresIn = 3_600_000)
      val model = ChatGPTModel(backend(url), ChatGPTAuth(file, url, List(0)))
      try
        val completion = model.complete("s", List(Msg.User("add")), Nil, StreamSink(_ => ()), () => false)
        assertEquals(completion.toolCalls, List(ToolCall("call-1", "run_scala", """{"code":"1 + 1"}""")))
        assertEquals(completion.stop, CompletionStop.Complete)
      finally model.close()

  test("a one-shot call streams and carries instructions"):
    withServer(_ => (200, "text/event-stream", streamed)): (url, requests) =>
      val file = tempFile()
      saved(file, "token", "refresh", expiresIn = 3_600_000)
      val model = ChatGPTModel(backend(url), ChatGPTAuth(file, url, List(0)))
      try
        assertEquals(model.simple(None, "hello", thinking = false).text, "done")
        val body = requests().single.json.obj
        assertEquals(body("stream"), ujson.Bool(true))
        assert(body("instructions").str.nonEmpty)
        assert(!body.get("reasoning").exists(_.obj.contains("summary")), "a call that does not think asks for none")
      finally model.close()

  test("without a sign-in a request fails before reaching the backend and says how to sign in"):
    withServer(_ => (200, "text/event-stream", streamed)): (url, requests) =>
      val model = ChatGPTModel(backend(url), ChatGPTAuth(tempFile(), url, List(0)))
      try
        val e = intercept[Exception](model.simple(Some("s"), "hello", thinking = true))
        val messages = Iterator.iterate[Throwable | Null](e)(_.nn.getCause).takeWhile(_ != null).map(_.nn.getMessage)
        assert(messages.exists(m => m != null && m.contains("/providers")), e.toString)
        assert(requests().isEmpty)
      finally model.close()

  test("a malformed token answer fails the request with an IOException that quotes no token"):
    withServer { r =>
      if r.path == "/oauth/token" then (200, "application/json", """["secret-token"]""")
      else (200, "text/event-stream", streamed)
    } { (url, requests) =>
      val file = tempFile()
      saved(file, "old", "refresh-1", expiresIn = 0)
      val model = ChatGPTModel(backend(url), ChatGPTAuth(file, url, List(0)))
      try
        val e = intercept[Exception](model.complete("s", List(Msg.User("hi")), Nil, StreamSink(_ => ()), () => false))
        val messages = Iterator.iterate[Throwable | Null](e)(_.nn.getCause).takeWhile(_ != null)
          .map(t => String.valueOf(t.nn.getMessage)).toList
        assert(messages.exists(_.contains("ChatGPT sign-in")), messages.toString)
        assert(!messages.exists(_.contains("secret-token")), messages.toString)
        assert(requests().forall(_.path == "/oauth/token"), "the backend is not reached")
      finally model.close()
    }

  test("a provider without a url reaches the ChatGPT backend"):
    val auth = ChatGPTAuth(tempFile(), "http://127.0.0.1:1", List(0))
    val model = ChatGPTModel(backend("http://x").copy(baseUrl = None), auth)
    try assertEquals(model.spec.baseUrl, Some(ChatGPTModel.BackendUrl))
    finally model.close()

  test("the backend's model list gives the listed models in order with names, context windows and efforts"):
    val listed = ujson.read("""{"models":[
      {"slug":"gpt-b","display_name":"GPT B","visibility":"list","priority":2,"context_window":272000,
       "supported_reasoning_levels":[{"effort":"low","description":"."},{"effort":"high","description":"."},
                                     {"effort":"ultra","description":"."}],
       "default_reasoning_level":"high"},
      {"slug":"gpt-hidden","display_name":"Hidden","visibility":"hide","priority":0},
      {"slug":"gpt-a","display_name":"GPT A","visibility":"list","priority":1,"supported_reasoning_levels":[]}]}""")
    val endpoint = backend("http://x").copy(alias = "", modelId = "", settings = ModelConfig())
    val models = ChatGPTModel.models(endpoint, listed)
    assertEquals(models.map(_.modelId), List("gpt-a", "gpt-b"))
    val b = models(1).settings
    assertEquals(b.displayName, Some("GPT B"))
    assertEquals(b.contextWindow, Tokens.from(272000L))
    assertEquals(b.efforts, Some(List("low", "high")))
    assertEquals(b.reasoning, Some("high"))
    assertEquals(models.head.settings.efforts, None)

  extension [A](xs: List[A])
    private def single: A =
      assertEquals(xs.size, 1, xs.toString)
      xs.head

/** Another process holding the lock `ChatGPTAuth` refreshes under, until its input ends. */
object ChatGPTLockHolder:
  def main(args: Array[String]): Unit =
    val options = java.util.Set.of(java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)
    val channel = java.nio.channels.FileChannel.open(Path.of(args(0)), options).nn
    channel.lock()
    System.out.println("locked")
    System.out.flush()
    System.in.read()
