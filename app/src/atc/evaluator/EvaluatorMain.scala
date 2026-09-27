package atc.evaluator

import atc.perms.Mode
import atc.sandbox.{ReplSession, SandboxConfig}

import java.io.{FileDescriptor, FileOutputStream}

/** The evaluator process: the compiler, the REPL and the agent's code, with the agent API
  * implemented by calls to the host over stdin and stdout. It holds no keys and, under the
  * OS sandbox the host starts it in, can read only the JDK and ATC's own classes. */
object EvaluatorMain:
  def main(args: Array[String]): Unit =
    val channelOut = FileOutputStream(FileDescriptor.out)
    // Nothing else may write to the channel: stray prints go to stderr.
    System.setOut(System.err)
    channelOut.write(Channel.Greeting)
    channelOut.flush()
    val channel = Channel("evaluator", System.in.nn, channelOut, conversationTag = 1)
    val host = RemoteHost(channel)
    @volatile var session: ReplSession | Null = null
    channel.onCancel = _ =>
      val current = session
      if current != null then current.interrupt()
    // The host is gone: nothing is left to serve.
    channel.onClose = () => Runtime.getRuntime.nn.halt(0)
    channel.handler = (method, payload) =>
      val d = Decoder(payload)
      method match
        case "init" =>
          val config = SandboxConfig(d.bool(), Mode.valueOf(d.string()), d.optionalLong(), d.int())
          val created = ReplSession(config, host)
          host.printStream = created.printStream
          host.clock = created.clock
          created.init()
          session = created
          Array.emptyByteArray
        case "eval" =>
          val result = Option(session).getOrElse(throw IllegalStateException("no session")).run(d.string())
          Encoder().bool(result.success).string(result.output).optionalString(result.error).result
        case "callback" =>
          host.runCallback(d.long(), d.long())
          Array.emptyByteArray
        case "print" =>
          Option(session).foreach(_.printStream.print(d.string()))
          Array.emptyByteArray
        case other => throw UnsupportedOperationException(s"unknown call $other")
    channel.start()
    Thread.currentThread().join()
