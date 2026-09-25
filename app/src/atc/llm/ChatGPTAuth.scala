package atc.llm

import upickle.default.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}
import java.security.{MessageDigest, SecureRandom}
import java.time.Duration
import java.util.Base64
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.util.control.NonFatal

/** Signing in with a ChatGPT plan, as the Codex CLI does: an OAuth 2.0
  * authorization-code grant with PKCE at `issuer`, answered on a callback
  * server on localhost. The tokens are kept in `file`, readable only by its
  * owner; the default policy denies the agent access to `.atc`.
  *
  * The file is read again for every request, so every atc process uses the
  * newest tokens. A refresh token can be used once: each refresh saves the
  * one it returns. */
final class ChatGPTAuth(file: Path, issuer: String = ChatGPTAuth.Issuer, ports: List[Int] = ChatGPTAuth.Ports):
  import ChatGPTAuth.*

  private val http = Providers.httpClient(Timeout, Timeout, Timeout, Timeout)

  /** The saved tokens; `None` when not signed in or the file cannot be read. */
  def load(): Option[Tokens] =
    try Option.when(Files.isRegularFile(file))(read[Tokens](Files.readString(file).nn))
    catch case NonFatal(_) => None

  /** Tokens for a request: the saved ones, refreshed first when they expire soon. */
  def current(): Tokens = synchronized {
    val saved = load().getOrElse(throw SignInNeeded("Not signed in to ChatGPT"))
    if saved.expiresAt - System.currentTimeMillis() > RefreshMargin.toMillis then saved else refresh(saved)
  }

  /** New tokens after the backend rejected `stale`. Another process may have refreshed already. */
  def renewed(stale: Tokens): Tokens = synchronized {
    load() match
      case Some(saved) if saved.access != stale.access => saved
      case Some(saved) => refresh(saved)
      case None => throw SignInNeeded("Not signed in to ChatGPT")
  }

  private def refresh(saved: Tokens): Tokens =
    val body = ujson.Obj("grant_type" -> "refresh_token", "client_id" -> ClientId, "refresh_token" -> saved.refresh)
    try save(tokens(post(okhttp3.RequestBody.create(ujson.write(body), JsonType)), Some(saved)))
    catch
      case e: TokenRejected =>
        // A refresh token is single-use: another process may have used it and saved the next one.
        load().filter(
          _.refresh != saved.refresh
        ).getOrElse(throw SignInNeeded(s"The ChatGPT sign-in expired (${e.getMessage})"))

  /** Start signing in: the callback server listens until the login is closed. */
  def begin(): Login =
    val verifier = randomToken(64)
    val state = randomToken(32)
    val (server, port) = bind(ports)
    val redirect = s"http://localhost:$port/auth/callback"
    val query = List(
      "response_type" -> "code",
      "client_id" -> ClientId,
      "redirect_uri" -> redirect,
      "scope" -> Scope,
      "code_challenge" -> challenge(verifier),
      "code_challenge_method" -> "S256",
      "id_token_add_organizations" -> "true",
      "codex_cli_simplified_flow" -> "true",
      "state" -> state,
      "originator" -> Originator,
    )
    Login(s"${issuer.stripSuffix("/")}/oauth/authorize?${form(query)}", server, redirect, verifier, state)

  /** One sign-in attempt. It completes on the browser's callback, or on the
    * address the browser ended on when that could not reach this machine. */
  final class Login private[ChatGPTAuth] (
    val url: String,
    server: com.sun.net.httpserver.HttpServer,
    redirect: String,
    verifier: String,
    state: String,
  ) extends AutoCloseable:
    private val done = CompletableFuture[Tokens]()
    @volatile private var received = false

    server.createContext(
      "/auth/callback",
      exchange =>
        try
          val outcome = accept(parameters(Option(exchange.getRequestURI.nn.getRawQuery).getOrElse("")))
          val (status, page) = outcome match
            case Right(_) => 200 -> htmlPage("Signed in to ChatGPT", "You can close this tab and return to atc.")
            case Left(why) => 400 -> htmlPage("Sign-in failed", why)
          val bytes = page.getBytes(UTF_8)
          exchange.getResponseHeaders.nn.add("Content-Type", "text/html; charset=utf-8")
          exchange.sendResponseHeaders(status, bytes.length.toLong)
          exchange.getResponseBody.nn.write(bytes)
        finally exchange.close()
    )
    server.start()

    /** Whether the browser reached the callback (the code exchange may still run). */
    def callbackReceived: Boolean = received

    /** The tokens, once the sign-in is complete; waits up to `timeout`. */
    def await(timeout: Duration): Option[Either[String, Tokens]] =
      try Some(Right(done.get(timeout.toMillis, TimeUnit.MILLISECONDS)))
      catch
        case _: java.util.concurrent.TimeoutException => None
        case e: java.util.concurrent.ExecutionException => Some(Left(e.getCause.nn.getMessage.nn))

    /** Complete with the address the browser ended on (or just its query). */
    def paste(address: String): Either[String, Tokens] =
      accept(parameters(address.trim.dropWhile(_ != '?').stripPrefix("?").takeWhile(_ != '#')))

    private def accept(params: Map[String, String]): Either[String, Tokens] = synchronized {
      if done.isDone then await(Duration.ZERO).get
      else if !params.get("state").contains(state) then
        Left("The sign-in does not belong to this attempt (state mismatch)")
      else
        received = true
        val outcome = params.get("error") match
          case Some(error) => Left(params.get("error_description").fold(error)(d => s"$error: $d"))
          case None =>
            params.get("code").filter(_.nonEmpty) match
              case None => Left("The callback carried no authorization code")
              case Some(code) =>
                try Right(save(tokens(exchange(code), None)))
                catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.toString))
        outcome.fold(why => done.completeExceptionally(RuntimeException(why)), done.complete)
        outcome
    }

    private def exchange(code: String): ujson.Value =
      val body = List(
        "grant_type" -> "authorization_code",
        "client_id" -> ClientId,
        "code" -> code,
        "redirect_uri" -> redirect,
        "code_verifier" -> verifier,
      )
      post(okhttp3.RequestBody.create(form(body), FormType))

    def close(): Unit = server.stop(0)

  /** Forget the tokens. */
  def signOut(): Unit = Files.deleteIfExists(file)

  private def post(body: okhttp3.RequestBody): ujson.Value =
    val request = okhttp3.Request.Builder().url(s"${issuer.stripSuffix("/")}/oauth/token")
      .header("User-Agent", Providers.UserAgent).post(body).build()
    scala.util.Using.resource(http.newCall(request).execute()) { response =>
      val text = Option(response.body).fold("")(_.string())
      if !response.isSuccessful then
        throw TokenRejected(s"HTTP ${response.code}${errorOf(text).fold("")(e => s": $e")}")
      ujson.read(text)
    }

  /** The tokens of a token-endpoint answer; a refresh may omit what did not change. */
  private def tokens(json: ujson.Value, previous: Option[Tokens]): Tokens =
    def field(name: String) = json.obj.get(name).flatMap(_.strOpt).filter(_.nonEmpty)
    val access = field("access_token").getOrElse(throw TokenRejected("the answer has no access token"))
    val idClaims = field("id_token").flatMap(claims)
    val accessClaims = claims(access)
    def auth(c: ujson.Value) = c.obj.get("https://api.openai.com/auth").flatMap(_.objOpt)
    val account = (idClaims.toList ++ accessClaims)
      .flatMap(c => auth(c).flatMap(_.get("chatgpt_account_id")).flatMap(_.strOpt))
      .headOption.orElse(previous.flatMap(_.accountId))
    val now = System.currentTimeMillis()
    val expiresAt = accessClaims.flatMap(_.obj.get("exp")).flatMap(_.numOpt).map(s => (s * 1000).toLong)
      .orElse(json.obj.get("expires_in").flatMap(_.numOpt).map(s => now + (s * 1000).toLong))
      .getOrElse(now + Duration.ofHours(1).toMillis)
    Tokens(
      access,
      field(
        "refresh_token"
      ).orElse(previous.map(_.refresh)).getOrElse(throw TokenRejected("the answer has no refresh token")),
      account,
      expiresAt,
      idClaims.flatMap(_.obj.get("email")).flatMap(_.strOpt).orElse(previous.flatMap(_.email)),
    )

  /** Replace the file atomically, readable by its owner only. */
  private def save(t: Tokens): Tokens =
    import java.nio.file.attribute.PosixFilePermissions
    val dir = file.toAbsolutePath.nn.getParent.nn
    Files.createDirectories(dir)
    val temp =
      try
        Files.createTempFile(
          dir,
          s".${file.getFileName}.",
          ".tmp",
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        )
      catch case _: UnsupportedOperationException => Files.createTempFile(dir, s".${file.getFileName}.", ".tmp")
    try
      Files.writeString(temp, write(t))
      try Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      catch case _: AtomicMoveNotSupportedException => Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(temp)
    t

object ChatGPTAuth:
  val Issuer = "https://auth.openai.com"
  /** The Codex CLI's client, whose registered callbacks are these two ports on localhost. */
  val ClientId = "app_EMoamEEZ73f0CkXaXp7hrann"
  val Ports: List[Int] = List(1455, 1457)
  private val Scope = "openid profile email offline_access"
  /** How ChatGPT's sign-in and backend are told which client is calling. */
  val Originator = "atc"
  private val Timeout = Duration.ofSeconds(30)
  private val RefreshMargin = Duration.ofMinutes(5)
  private val JsonType = okhttp3.MediaType.get("application/json")
  private val FormType = okhttp3.MediaType.get("application/x-www-form-urlencoded")

  /** `~/.atc/chatgpt-auth.json`. */
  lazy val default: ChatGPTAuth =
    ChatGPTAuth(atc.platform.PlatformPath.userHome.resolve(".atc").nn.resolve("chatgpt-auth.json").nn)

  /** A sign-in: the tokens, the ChatGPT account (workspace) they act for, and
    * when the access token expires, in epoch milliseconds. */
  case class Tokens(
    access: String,
    refresh: String,
    accountId: Option[String],
    expiresAt: Long,
    email: Option[String] = None,
  ) derives ReadWriter:
    override def toString: String = s"Tokens(account=${accountId.getOrElse("-")}, expiresAt=$expiresAt)"

  /** The request cannot be authorized until the user signs in (again). An
    * `IOException`, so OkHttp reports it as a failed call. */
  final class SignInNeeded(message: String)
      extends java.io.IOException(s"$message; choose chatgpt in /providers to sign in")

  private final class TokenRejected(message: String) extends java.io.IOException(message)

  private val random = SecureRandom()
  private def randomToken(bytes: Int): String =
    val b = new Array[Byte](bytes)
    random.nextBytes(b)
    Base64.getUrlEncoder.nn.withoutPadding().nn.encodeToString(b).nn

  private def challenge(verifier: String): String =
    val digest = MessageDigest.getInstance("SHA-256").nn.digest(verifier.getBytes(UTF_8))
    Base64.getUrlEncoder.nn.withoutPadding().nn.encodeToString(digest).nn

  private def form(pairs: List[(String, String)]): String =
    pairs.map((k, v) => s"${encode(k)}=${encode(v)}").mkString("&")
  private def encode(s: String) = java.net.URLEncoder.encode(s, UTF_8).nn

  private def parameters(query: String): Map[String, String] =
    query.split('&').toList.filter(_.nonEmpty).map { pair =>
      val (k, v) = pair.span(_ != '=')
      java.net.URLDecoder.decode(k, UTF_8).nn -> java.net.URLDecoder.decode(v.drop(1), UTF_8).nn
    }.toMap

  /** The payload of a JWT; the signature is not checked, the token endpoint was trusted over TLS. */
  private def claims(jwt: String): Option[ujson.Value] =
    jwt.split('.') match
      case Array(_, payload, _) =>
        try Some(ujson.read(String(Base64.getUrlDecoder.nn.decode(payload), UTF_8))).filter(_.objOpt.isDefined)
        catch case NonFatal(_) => None
      case _ => None

  private def errorOf(text: String): Option[String] =
    try
      val json = ujson.read(text)
      json.obj.get("error_description").orElse(json.obj.get("error")).flatMap {
        case ujson.Str(s) => Some(s)
        case o: ujson.Obj => o.value.get("message").flatMap(_.strOpt).orElse(o.value.get("code").flatMap(_.strOpt))
        case _ => None
      }
    catch case NonFatal(_) => None

  /** Listen on the first free port of `ports`, on the loopback interface only. */
  private def bind(ports: List[Int]): (com.sun.net.httpserver.HttpServer, Int) =
    ports.iterator.flatMap { port =>
      try
        val server = com.sun.net.httpserver.HttpServer.create(
          java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress, port),
          0
        ).nn
        Some(server -> server.getAddress.nn.getPort)
      catch case _: java.net.BindException => None
    }.nextOption().getOrElse(
      throw java.io.IOException(
        s"Ports ${ports.mkString(" and ")} are in use; another sign-in (atc or codex login) may be waiting"
      )
    )

  private def htmlPage(title: String, text: String): String =
    def escape(s: String) = s.flatMap {
      case '<' => "&lt;"
      case '>' => "&gt;"
      case '&' => "&amp;"
      case '"' => "&quot;"
      case c => c.toString
    }
    s"""<!doctype html><html><head><meta charset="utf-8"><title>atc: ${escape(title)}</title></head>
       |<body style="font-family: system-ui, sans-serif; margin: 4em auto; max-width: 36em">
       |<h1>${escape(title)}</h1><p>${escape(text)}</p></body></html>""".stripMargin
