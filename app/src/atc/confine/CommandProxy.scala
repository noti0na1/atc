package atc.confine

import atc.perms.{Policy, ScopeId}

import java.io.{InputStream, OutputStream}
import java.net.{InetAddress, InetSocketAddress, Socket, StandardProtocolFamily, URI, UnixDomainSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.{ServerSocketChannel, SocketChannel}
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.util.Using
import scala.util.control.NonFatal

/** The only way out for a confined command that may use the network: an HTTP proxy,
  * one per command, that opens `CONNECT` tunnels and forwards absolute-form HTTP
  * requests to the hosts the policy allows in the command's scope. A host not
  * allowed yet goes through `Policy.requestNet`, which asks the user in an
  * interactive session; a "once" approval lasts until the command ends.
  *
  * Names are resolved here, not in the sandbox, and a name that resolves to a
  * loopback, link-local, wildcard or multicast address is refused unless that
  * address itself is an allowed host, so an allowed name cannot be pointed at a
  * service on this machine or at a cloud metadata endpoint. The proxy listens on
  * a loopback port (macOS) or a Unix socket that is mounted into the sandbox
  * (Linux). It reads no traffic beyond the request head. */
final class CommandProxy private (server: ServerSocketChannel, policy: Policy, scope: ScopeId, command: String)
    extends AutoCloseable:
  private val decisions = ConcurrentHashMap[String, Boolean]()
  private val grantedScopes = mutable.ListBuffer[ScopeId]()
  private val connections = ConcurrentHashMap.newKeySet[AutoCloseable]().nn
  @volatile private var closed = false

  /** The loopback port, for a TCP listener. */
  val port: Int = server.getLocalAddress match
    case address: InetSocketAddress => address.getPort
    case _ => -1

  private val socketFile: Option[Path] = server.getLocalAddress match
    case unix: UnixDomainSocketAddress => Some(unix.getPath.nn)
    case _ => None

  private def start(): CommandProxy =
    CommandProxy.daemon("atc-command-proxy"): () =>
      while !closed do
        try
          val client = server.accept().nn
          connections.add(client)
          CommandProxy.daemon("atc-command-proxy-connection")(() => serve(client))
        catch case NonFatal(_) => ()
    this

  def close(): Unit =
    closed = true
    try server.close()
    catch case NonFatal(_) => ()
    connections.forEach(c =>
      try c.close()
      catch case NonFatal(_) => ()
    )
    grantedScopes.synchronized(grantedScopes.foreach(policy.closeScope))
    socketFile.foreach(Files.deleteIfExists)

  private def serve(client: SocketChannel): Unit =
    try
      val in = CommandProxy.input(client)
      val out = CommandProxy.output(client)
      CommandProxy.readHead(in) match
        case None => ()
        case Some((head, rest)) =>
          val lines = head.split("\r\n").toList
          lines.headOption.map(_.split(' ')) match
            case Some(Array("CONNECT", target, _)) =>
              val (host, port) = CommandProxy.hostPort(target, 443)
              open(host, port, out).foreach: upstream =>
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(ISO_8859_1))
                out.flush()
                upstream.getOutputStream.nn.write(rest)
                tunnel(in, out, upstream)
            case Some(Array(method, target, version)) if target.startsWith("http://") =>
              val uri = URI(target)
              val host = Option(uri.getHost).getOrElse("")
              open(host, if uri.getPort < 0 then 80 else uri.getPort, out).foreach: upstream =>
                val path = Option(uri.getRawPath).filter(_.nonEmpty).getOrElse("/") +
                  Option(uri.getRawQuery).fold("")("?" + _)
                val headers = lines.drop(1).filterNot(_.toLowerCase.startsWith("proxy-"))
                val request = (s"$method $path $version" :: headers).mkString("", "\r\n", "\r\n\r\n")
                upstream.getOutputStream.nn.write(request.getBytes(ISO_8859_1) ++ rest)
                tunnel(in, out, upstream)
            case _ => refuse(out, 400, "only CONNECT and absolute-form http:// requests are proxied")
    catch case NonFatal(_) => ()
    finally
      connections.remove(client)
      try client.close()
      catch case NonFatal(_) => ()

  /** Connect to `host:port` if the policy allows it; otherwise answer the client and return nothing. */
  private def open(host: String, port: Int, out: OutputStream): Option[Socket] =
    val name = host.stripPrefix("[").stripSuffix("]")
    if name.isEmpty then
      refuse(out, 400, "the request names no host")
      None
    else if !permitted(name) then
      refuse(out, 403, s"atc: $name is not an allowed host for commands (add it to \"hosts\" or approve it when asked)")
      None
    else
      CommandProxy.resolve(name, literal => policy.hostAllowed(scope, literal) || literalGranted(literal)) match
        case Left(reason) =>
          refuse(out, 403, s"atc: $name $reason")
          None
        case Right(address) =>
          val socket = Socket()
          try
            socket.connect(InetSocketAddress(address, port), CommandProxy.ConnectTimeoutMs)
            connections.add(socket)
            Some(socket)
          catch
            case NonFatal(e) =>
              socket.close()
              refuse(out, 502, s"atc: cannot connect to $name:$port (${e.getMessage})")
              None

  /** Whether the policy allows `host`, asking the user once per host and command when it does not yet. */
  private def permitted(host: String): Boolean =
    if policy.hostAllowed(scope, host) then true
    else
      decisions.synchronized:
        Option(decisions.get(host)) match
          case Some(known) => known
          case None =>
            val granted =
              try
                val child = policy.requestNet(scope, List(host), s"a command (${command.take(200)}) connects to $host")
                grantedScopes.synchronized(grantedScopes += child)
                true
              catch case NonFatal(_) => false
            decisions.put(host, granted)
            granted

  private def literalGranted(literal: String): Boolean =
    grantedScopes.synchronized(grantedScopes.exists(s => policy.hostAllowed(s, literal)))

  private def refuse(out: OutputStream, status: Int, reason: String): Unit =
    val body = reason.getBytes(ISO_8859_1)
    val text = status match
      case 403 => "Forbidden"
      case 502 => "Bad Gateway"
      case _ => "Bad Request"
    out.write(s"HTTP/1.1 $status $text\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n"
      .getBytes(ISO_8859_1) ++ body)
    out.flush()

  /** Copy both directions until either side closes. */
  private def tunnel(clientIn: InputStream, clientOut: OutputStream, upstream: Socket): Unit =
    val back = CommandProxy.daemon("atc-command-proxy-pump"): () =>
      CommandProxy.pump(upstream.getInputStream.nn, clientOut)
      try clientOut.close()
      catch case NonFatal(_) => ()
    CommandProxy.pump(clientIn, upstream.getOutputStream.nn)
    try upstream.shutdownOutput()
    catch case NonFatal(_) => ()
    back.join()
    connections.remove(upstream)
    upstream.close()

object CommandProxy:
  private val ConnectTimeoutMs = 10_000
  private val MaxHeadBytes = 64 * 1024

  /** A proxy listening on a loopback TCP port. */
  def tcp(policy: Policy, scope: ScopeId, command: String): CommandProxy =
    val server = ServerSocketChannel.open().nn
    server.bind(InetSocketAddress(InetAddress.getLoopbackAddress, 0))
    CommandProxy(server, policy, scope, command).start()

  /** A proxy listening on the Unix socket `socket`. */
  def unix(socket: Path, policy: Policy, scope: ScopeId, command: String): CommandProxy =
    val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).nn
    server.bind(UnixDomainSocketAddress.of(socket))
    CommandProxy(server, policy, scope, command).start()

  /** The request head (without the blank line) and the bytes read after it. */
  private def readHead(in: InputStream): Option[(String, Array[Byte])] =
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = new Array[Byte](4096)
    var result: Option[(String, Array[Byte])] = None
    var done = false
    while !done do
      val count = in.read(chunk)
      if count < 0 then done = true
      else
        buffer.write(chunk, 0, count)
        val bytes = buffer.toByteArray.nn
        val end = String(bytes, ISO_8859_1).indexOf("\r\n\r\n")
        if end >= 0 then
          result = Some((String(bytes, 0, end, ISO_8859_1), bytes.drop(end + 4)))
          done = true
        else if bytes.length > MaxHeadBytes then done = true
    result

  /** `host:port`, with a bracketed IPv6 host, and a default port. */
  private def hostPort(target: String, defaultPort: Int): (String, Int) =
    val colon = target.lastIndexOf(':')
    if colon > 0 && !target.substring(colon).contains(']') then
      (target.substring(0, colon), target.substring(colon + 1).toIntOption.getOrElse(defaultPort))
    else (target, defaultPort)

  /** The first address of `host` that is not local, unless `allowedLiteral` allows that address itself. */
  private def resolve(host: String, allowedLiteral: String => Boolean): Either[String, InetAddress] =
    try
      val addresses = InetAddress.getAllByName(host).nn.toList.map(_.nn)
      addresses.find(a => !local(a) || allowedLiteral(a.getHostAddress.nn))
        .toRight("resolves only to local or link-local addresses, which commands may not reach through a name")
    catch case NonFatal(e) => Left(s"cannot be resolved (${e.getMessage})")

  /** Loopback, link-local (cloud metadata included), wildcard and multicast addresses. */
  private def local(address: InetAddress): Boolean =
    address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress || address.isMulticastAddress

  /** Streams over a blocking socket channel that call its `read` and `write` directly. On
    * JDK 17 the streams of `Channels` hold the channel's blocking lock while a read waits,
    * so a tunnel could not write to the client while it waits for the client's next bytes. */
  private def input(channel: SocketChannel): InputStream = new InputStream:
    def read(): Int =
      val one = new Array[Byte](1)
      if read(one, 0, 1) < 0 then -1 else one(0) & 0xff
    override def read(bytes: Array[Byte], offset: Int, length: Int): Int =
      if length == 0 then 0 else channel.read(ByteBuffer.wrap(bytes, offset, length))
    override def close(): Unit = channel.close()

  private def output(channel: SocketChannel): OutputStream = new OutputStream:
    def write(byte: Int): Unit = write(Array(byte.toByte), 0, 1)
    override def write(bytes: Array[Byte], offset: Int, length: Int): Unit =
      val buffer = ByteBuffer.wrap(bytes, offset, length)
      while buffer.hasRemaining do channel.write(buffer)
    override def close(): Unit = channel.close()

  private def pump(in: InputStream, out: OutputStream): Unit =
    val buffer = new Array[Byte](16384)
    try
      var count = in.read(buffer)
      while count >= 0 do
        out.write(buffer, 0, count)
        out.flush()
        count = in.read(buffer)
    catch case NonFatal(_) => ()

  private def daemon(name: String)(body: Runnable): Thread =
    val thread = Thread(body, name)
    thread.setDaemon(true)
    thread.start()
    thread
