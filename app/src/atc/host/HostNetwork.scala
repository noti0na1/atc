package atc.host

import atc.lib.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse as JHttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import scala.util.{Success, Try, Using}

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
    val patterns = hosts.toList.map(_.trim).filter(_.nonEmpty)
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

  private final case class Prepared(uri: URI, method: String)

  /** Validate everything that is independent of classified header values before
    * inspecting any of them. Header names are plain strings even in
    * `secretHeaders`, so duplicate/restricted-name errors are safe to report. */
  private def prepare(
    net: Network,
    method: String,
    url: String,
    headers: Map[String, String],
    secretHeaderNames: Iterable[String],
  ): Prepared =
    val uri = URI(url)
    val scheme = Option(uri.getScheme).map(_.toLowerCase(Locale.ROOT)).getOrElse("")
    if scheme != "http" && scheme != "https" then
      throw SecurityException(s"Invalid URL (only http/https are supported): $url")
    requireAllowedHost(net, uri, url)

    val normalizedMethod = method.toUpperCase(Locale.ROOT)

    val names = headers.keysIterator.toList ++ secretHeaderNames.toList
    val duplicates =
      names.groupBy(_.toLowerCase(Locale.ROOT)).collect { case (_, variants) if variants.sizeIs > 1 => variants }.toList
    if duplicates.nonEmpty then
      throw IllegalArgumentException(
        s"HTTP header names are case-insensitive and may be supplied only once: ${duplicates.flatten.distinct.sorted.mkString(", ")}"
      )

    // Ask the JDK to validate the non-secret values now, outside the classified
    // failure boundary. Secret values are deliberately not touched here.
    val validator = HttpRequest.newBuilder(uri).nn
    headers.foreach((name, value) => validator.header(name, value))
    validator.method(normalizedMethod, HttpRequest.BodyPublishers.noBody())
    Prepared(uri, normalizedMethod)

  /** Sequence classified header computations without making their success or
    * failure observable. The resulting `Try` is consumed only inside another
    * classified value. */
  private def resolveSecretHeaders(headers: Map[String, Classified[String]]): Try[Map[String, String]] =
    headers.iterator.foldLeft(Try(Map.empty[String, String])) { case (result, (name, classified)) =>
      for
        resolved <- result
        value <- ClassifiedImpl.unwrap(classified)
      yield resolved.updated(name, value)
    }

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
  private def responseBody(response: JHttpResponse[java.io.InputStream], url: String): String =
    Using.resource(response.body.nn) { input =>
      val body = input.readNBytes(Host.HttpMaxResponseBytes + 1).nn
      if body.length > Host.HttpMaxResponseBytes then
        throw RuntimeException(
          s"HTTP response from $url exceeded the ${Host.HttpMaxResponseBytes}-byte limit"
        )
      String(body, StandardCharsets.UTF_8)
    }

  private def send(
    prepared: Prepared,
    url: String,
    body: Option[String],
    contentType: String,
    headers: Map[String, String],
    secretHeaders: Map[String, String],
  ): HttpResponse =
    val builder = HttpRequest.newBuilder(prepared.uri).nn.timeout(Duration.ofSeconds(60)).nn
    headers.foreach((name, value) => builder.header(name, value))
    secretHeaders.foreach((name, value) => builder.header(name, value))
    val publisher = requestBody(builder, body, contentType, headers.keys ++ secretHeaders.keys)
    builder.method(prepared.method, publisher)
    val response = http.send(builder.build(), JHttpResponse.BodyHandlers.ofInputStream()).nn
    HttpResponse(response.statusCode, responseBody(response, url))

  private def request(
    net: Network,
    method: String,
    url: String,
    body: Option[String],
    contentType: String,
    headers: Map[String, String],
  ): HttpResponse =
    val prepared = prepare(net, method, url, headers, Nil)
    send(prepared, url, body, contentType, headers, Map.empty)

  /** Any request containing classified input produces a classified response.
    * JDK validation, construction, transport, status and body failures after
    * unwrapping are retained inside it; none of their messages can reach the
    * agent as a plain exception. */
  private def requestClassified(
    net: Network,
    method: String,
    url: String,
    body: Try[Option[String]],
    contentType: String,
    headers: Map[String, String],
    secretHeaders: Map[String, Classified[String]],
  ): Classified[HttpResponse] =
    val prepared = prepare(net, method, url, headers, secretHeaders.keys)
    val result =
      for
        resolvedBody <- body
        resolvedHeaders <- resolveSecretHeaders(secretHeaders)
        response <- Try(send(prepared, url, resolvedBody, contentType, headers, resolvedHeaders))
      yield response
    ClassifiedImpl.fromTry(result)

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

  private def checkedClassified(method: String, url: String, response: Classified[HttpResponse]): Classified[String] =
    ClassifiedImpl.fromTry(ClassifiedImpl.unwrap(response).flatMap(value => Try(checked(method, url, value))))

  def httpGet(url: String)(using net: Network): String =
    checked("GET", url, request(net, "GET", url, None, JsonContentType, noHeaders))
  def httpGet(url: String, headers: Map[String, String])(using net: Network): String =
    checked("GET", url, request(net, "GET", url, None, JsonContentType, headers))
  def httpGet(url: String, headers: Map[String, String], secretHeaders: Map[String, Classified[String]])(using
    net: Network
  ): Classified[String] =
    checkedClassified(
      "GET",
      url,
      requestClassified(net, "GET", url, Success(None), JsonContentType, headers, secretHeaders)
    )

  def httpPost(url: String, body: String)(using Network): String =
    httpPost(url, body, JsonContentType, noHeaders)
  def httpPost(url: String, body: String, contentType: String)(using Network): String =
    httpPost(url, body, contentType, noHeaders)
  def httpPost(url: String, body: String, contentType: String, headers: Map[String, String])(using
    net: Network
  ): String =
    checked("POST", url, request(net, "POST", url, Some(body), contentType, headers))
  def httpPost(
    url: String,
    body: String,
    contentType: String,
    headers: Map[String, String],
    secretHeaders: Map[String, Classified[String]]
  )(using net: Network): Classified[String] =
    checkedClassified(
      "POST",
      url,
      requestClassified(net, "POST", url, Success(Some(body)), contentType, headers, secretHeaders)
    )

  def httpRequest(method: String, url: String)(using Network): HttpResponse =
    httpRequest(method, url, "", noHeaders)
  def httpRequest(method: String, url: String, body: String)(using Network): HttpResponse =
    httpRequest(method, url, body, noHeaders)
  def httpRequest(method: String, url: String, body: String, headers: Map[String, String])(using
    net: Network
  ): HttpResponse =
    request(net, method, url, Option(body).filter(_.nonEmpty), JsonContentType, headers)
  def httpRequest(
    method: String,
    url: String,
    body: String,
    headers: Map[String, String],
    secretHeaders: Map[String, Classified[String]]
  )(using net: Network): Classified[HttpResponse] =
    requestClassified(
      net,
      method,
      url,
      Success(Option(body).filter(_.nonEmpty)),
      JsonContentType,
      headers,
      secretHeaders
    )

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
    val classifiedResponse = requestClassified(
      net,
      "POST",
      url,
      ClassifiedImpl.unwrap(body).map(Some(_)),
      contentType,
      headers,
      secretHeaders,
    )
    ClassifiedImpl.fromTry(ClassifiedImpl.unwrap(classifiedResponse).map(_.body))
