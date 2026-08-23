package atc.host

import atc.lib.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse as JHttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.util.{Failure, Success, Try}

/** Network-facing operations supplied by [[Host]]. Keeping HTTP construction
  * here makes the permission boundary and classified-data handling reviewable
  * without mixing them with filesystem or process code. */
private[host] trait HostNetwork:
  self: Host =>

  def requestNetwork[T](hosts: Iterable[String])(op: Network ?=> T)(using UserIO, Network): T =
    requestNetwork(hosts, "")(op)

  def requestNetwork[T](hosts: Iterable[String], reason: String)(op: Network ?=> T)(using
    user: UserIO,
    parent: Network
  ): T =
    val patterns = hosts.toList.map(_.trim.toLowerCase).filter(_.nonEmpty)
    inScope(policy.requestNet(scopeOf(parent), patterns, reason))(id => op(using NetworkImpl(id)))

  private val http = HttpClient.newBuilder().nn
    .followRedirects(HttpClient.Redirect.NEVER).nn
    .connectTimeout(Duration.ofSeconds(20)).nn
    .build().nn

  private def requireAllowedHost(net: Network, uri: URI, originalUrl: String): Unit =
    val host = Option(uri.getHost).map(Host.normalizeHost(_)).getOrElse {
      throw SecurityException(s"Invalid URL (no host): $originalUrl")
    }
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

  private def resolveSecretHeaders(headers: Map[String, Classified[String]]): Map[String, String] =
    val attempted = headers.iterator.map((name, value) => name -> ClassifiedImpl.unwrap(value)).toList
    if attempted.exists(_._2.isFailure) then
      classifiedSinkFailed("a classified request header")
      throw SecurityException(
        "A classified header value could not be computed; the request was not sent. Its error is confidential."
      )
    attempted.collect { case (name, Success(value)) => name -> value }.toMap

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

  private def request(
    net: Network,
    method: String,
    url: String,
    body: Option[String],
    contentType: String,
    headers: Map[String, String],
    secretHeaders: Map[String, Classified[String]]
  ): HttpResponse =
    val uri = URI(url)
    val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("")
    if scheme != "http" && scheme != "https" then
      throw SecurityException(s"Invalid URL (only http/https are supported): $url")
    requireAllowedHost(net, uri, url)

    val builder = HttpRequest.newBuilder(uri).nn.timeout(Duration.ofSeconds(60)).nn
    headers.foreach((name, value) => builder.header(name, value))
    // Unwrap classified headers only for transmission; never expose them to the
    // agent. A failed header must abort the request: silently omitting it could
    // produce a distinguishable response and reveal the failure.
    val resolvedSecrets = resolveSecretHeaders(secretHeaders)
    resolvedSecrets.foreach((name, value) => builder.header(name, value))
    val publisher = requestBody(builder, body, contentType, headers.keys ++ resolvedSecrets.keys)
    builder.method(method.toUpperCase, publisher)
    val response = http.send(builder.build(), JHttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).nn
    HttpResponse(response.statusCode, response.body.nn)

  private val noHeaders = Map.empty[String, String]
  private val noSecrets = Map.empty[String, Classified[String]]
  private val JsonContentType = "application/json"

  /** `httpGet` and `httpPost` throw on HTTP errors so an error page cannot pass
    * for data. `httpRequest` and classified POSTs return the raw result. */
  private def checked(method: String, url: String, response: HttpResponse): String =
    if response.status >= 400 then
      throw RuntimeException(
        s"$method $url returned HTTP ${response.status}: ${response.body.take(Host.HttpErrorBodyChars)} (use httpRequest(...) to inspect a failure)"
      )
    response.body

  def httpGet(url: String)(using Network): String = httpGet(url, noHeaders, noSecrets)
  def httpGet(url: String, headers: Map[String, String])(using Network): String = httpGet(url, headers, noSecrets)
  def httpGet(url: String, headers: Map[String, String], secretHeaders: Map[String, Classified[String]])(using
    net: Network
  ): String =
    checked("GET", url, request(net, "GET", url, None, JsonContentType, headers, secretHeaders))

  def httpPost(url: String, body: String)(using Network): String =
    httpPost(url, body, JsonContentType, noHeaders, noSecrets)
  def httpPost(url: String, body: String, contentType: String)(using Network): String =
    httpPost(url, body, contentType, noHeaders, noSecrets)
  def httpPost(url: String, body: String, contentType: String, headers: Map[String, String])(using Network): String =
    httpPost(url, body, contentType, headers, noSecrets)
  def httpPost(
    url: String,
    body: String,
    contentType: String,
    headers: Map[String, String],
    secretHeaders: Map[String, Classified[String]]
  )(using net: Network): String =
    checked("POST", url, request(net, "POST", url, Some(body), contentType, headers, secretHeaders))

  def httpRequest(method: String, url: String)(using Network): HttpResponse =
    httpRequest(method, url, "", noHeaders, noSecrets)
  def httpRequest(method: String, url: String, body: String)(using Network): HttpResponse =
    httpRequest(method, url, body, noHeaders, noSecrets)
  def httpRequest(method: String, url: String, body: String, headers: Map[String, String])(using
    Network
  ): HttpResponse =
    httpRequest(method, url, body, headers, noSecrets)
  def httpRequest(
    method: String,
    url: String,
    body: String,
    headers: Map[String, String],
    secretHeaders: Map[String, Classified[String]]
  )(using net: Network): HttpResponse =
    request(net, method, url, Option(body).filter(_.nonEmpty), JsonContentType, headers, secretHeaders)

  def httpPostClassified(url: String, body: Classified[String])(using Network): Classified[String] =
    httpPostClassified(url, body, JsonContentType, noHeaders, noSecrets)
  def httpPostClassified(url: String, body: Classified[String], contentType: String)(using
    Network
  ): Classified[String] =
    httpPostClassified(url, body, contentType, noHeaders, noSecrets)
  def httpPostClassified(
    url: String,
    body: Classified[String],
    contentType: String,
    headers: Map[String, String],
    secretHeaders: Map[String, Classified[String]]
  )(using net: Network): Classified[String] =
    ClassifiedImpl.unwrap(body) match
      case Success(value) =>
        // Use the raw request: `httpPost` may expose an error response in its exception.
        ClassifiedImpl.fromTry(Try(request(net, "POST", url, Some(value), contentType, headers, secretHeaders).body))
      case Failure(_) => body
