package atc.host

import atc.lib.*
import atc.perms.GlobMatcher

import java.io.InputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse as JHttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import scala.util.{Success, Try, Using}

/** HTTP operations with host permissions and classified request/response handling. */
private[host] trait HostNetwork:
  self: Host =>

  def requestNetwork[T](hosts: Iterable[String])(op: Network ?=> T)(using UserIO, Network): T =
    requestNetwork(hosts, "")(op)

  def requestNetwork[T](hosts: Iterable[String], reason: String)(op: Network ?=> T)(using
    user: UserIO,
    parent: Network
  ): T =
    val patterns = hosts.toList.map(_.trim).filter(_.nonEmpty).distinct.sorted
    inScope(policy.requestNet(scopeOf(parent), patterns, reason))(id => op(using NetworkImpl(id)))

  private lazy val http = HttpClient.newBuilder().nn
    .followRedirects(HttpClient.Redirect.NEVER).nn
    .connectTimeout(Duration.ofSeconds(20)).nn
    .build().nn

  private def requireAllowedHost(net: Network, uri: URI, originalUrl: String): Unit =
    val host = Option(uri.getHost)
      .map(GlobMatcher.normalizeHost)
      .getOrElse(throw SecurityException(s"Invalid URL (no host): $originalUrl"))
    policy.hostDenied(host) match
      case Some(pattern) =>
        throw SecurityException(
          s"Access denied: host '$host' is refused by the configuration (denyHosts pattern '$pattern'). It cannot be granted; do not retry it or work around it, tell the user instead."
        )
      case None if !policy.hostAllowed(scopeOf(net), host) =>
        throw SecurityException(
          s"""Access denied: host '$host' matches no permitted pattern. Use requestNetwork(Set("$host"), reason) { ... } to ask the user."""
        )
      case None => ()

  private final case class Prepared(uri: URI, method: String)

  /** Validate the URL, the host, the method and the headers before anything is sent. */
  private def prepare(net: Network, method: String, url: String, headers: Map[String, String]): Prepared =
    val uri = URI(url)
    val scheme = Option(uri.getScheme).map(_.toLowerCase(Locale.ROOT)).getOrElse("")
    if scheme != "http" && scheme != "https" then
      throw SecurityException(s"Invalid URL (only http/https are supported): $url")
    requireAllowedHost(net, uri, url)

    val normalizedMethod = method.toUpperCase(Locale.ROOT)

    val duplicates =
      headers.keysIterator.toList.groupBy(_.toLowerCase(Locale.ROOT))
        .collect { case (_, variants) if variants.sizeIs > 1 => variants }.toList
    if duplicates.nonEmpty then
      throw IllegalArgumentException(
        s"HTTP header names are case-insensitive and may be supplied only once: ${duplicates.flatten.distinct.sorted.mkString(", ")}"
      )

    // Ask the JDK to validate the method and the headers now, before the request is built.
    val validator = HttpRequest.newBuilder(uri).nn
    headers.foreach((name, value) => validator.header(name, value))
    validator.method(normalizedMethod, HttpRequest.BodyPublishers.noBody())
    Prepared(uri, normalizedMethod)

  private def requestBody(
    builder: HttpRequest.Builder,
    body: Option[String],
    contentType: String,
    headerNames: Iterable[String]
  ): HttpRequest.BodyPublisher =
    body match
      case None => HttpRequest.BodyPublishers.noBody().nn
      case Some(text) =>
        if !headerNames.exists(_.equalsIgnoreCase("Content-Type")) then builder.header("Content-Type", contentType)
        HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8).nn

  /** Consume a response through a bounded stream. `ofString`/`ofByteArray`
    * buffer without a limit and let an allowed peer exhaust the process heap. */
  private def responseBody(response: JHttpResponse[InputStream], url: String): String =
    Using.resource(response.body.nn): input =>
      val body = input.readNBytes(Host.HttpMaxResponseBytes + 1).nn
      if body.length > Host.HttpMaxResponseBytes then
        throw RuntimeException(s"HTTP response from $url exceeded the ${Host.HttpMaxResponseBytes}-byte limit")
      String(body, StandardCharsets.UTF_8)

  private def request(
    net: Network,
    method: String,
    url: String,
    body: Option[String],
    contentType: String,
    headers: Map[String, String],
  ): HttpResponse =
    val prepared = prepare(net, method, url, headers)
    val builder = HttpRequest.newBuilder(prepared.uri).nn.timeout(Duration.ofSeconds(60)).nn
    headers.foreach((name, value) => builder.header(name, value))
    builder.method(prepared.method, requestBody(builder, body, contentType, headers.keys))
    val response = http.send(builder.build(), JHttpResponse.BodyHandlers.ofInputStream()).nn
    HttpResponse(response.statusCode, responseBody(response, url))

  private val noHeaders = Map.empty[String, String]
  private val JsonContentType = "application/json"

  /** `httpGet` and `httpPost` throw on HTTP errors so an error page cannot pass
    * for data. `httpRequest` returns the raw result. */
  private def checked(method: String, url: String, response: HttpResponse): String =
    if response.status >= 400 then
      throw RuntimeException(
        s"$method $url returned HTTP ${response.status}: ${response.body.take(Host.HttpErrorBodyChars)} (use httpRequest(...) to inspect a failure)"
      )
    response.body

  def httpGet(url: String)(using net: Network): String =
    checked("GET", url, request(net, "GET", url, None, JsonContentType, noHeaders))
  def httpGet(url: String, headers: Map[String, String])(using net: Network): String =
    checked("GET", url, request(net, "GET", url, None, JsonContentType, headers))

  def httpPost(url: String, body: String)(using Network): String =
    httpPost(url, body, JsonContentType, noHeaders)
  def httpPost(url: String, body: String, contentType: String)(using Network): String =
    httpPost(url, body, contentType, noHeaders)
  def httpPost(url: String, body: String, contentType: String, headers: Map[String, String])(using
    net: Network
  ): String =
    checked("POST", url, request(net, "POST", url, Some(body), contentType, headers))

  def httpRequest(method: String, url: String)(using Network): HttpResponse =
    httpRequest(method, url, "", noHeaders)
  def httpRequest(method: String, url: String, body: String)(using Network): HttpResponse =
    httpRequest(method, url, body, noHeaders)
  def httpRequest(method: String, url: String, body: String, headers: Map[String, String])(using
    net: Network
  ): HttpResponse =
    request(net, method, url, Option(body).filter(_.nonEmpty), JsonContentType, headers)
