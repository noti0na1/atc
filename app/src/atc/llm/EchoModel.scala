package atc.llm

/** A local, key-less model for smoke tests and demos (`"api": "echo"`). It
  * replies with the user's text; a message of the form `run: <scala>` makes it
  * call `run_scala` with that code and then report the result. */
object EchoModel:
  def apply(alias: String, ref: String): EchoModel = new EchoModel(alias, ref)
  /** An echo model that is its own reference (tests and smoke runs). */
  def apply(alias: String): EchoModel = new EchoModel(alias, alias)

final class EchoModel(val alias: String, override val ref: String) extends ChatModel:
  val modelId = "echo"
  val providerKey = "echo"
  val webSearch = false
  private var counter = 0

  def complete(
    system: SystemPrompt,
    history: List[Msg],
    tools: List[ToolSpec],
    sink: StreamSink,
    cancelled: () => Boolean
  ): Completion =
    def reply(text: String, calls: List[ToolCall] = Nil): Completion =
      sink.text(text)
      Completion(text, calls, None, TokenUsage(1, 1), if calls.isEmpty then "end_turn" else "tool_use")
    history.lastOption match
      case Some(Msg.User(text)) if text.trim.startsWith("run:") =>
        counter += 1
        val code = text.trim.stripPrefix("run:").trim
        reply(
          "Running that in the sandbox.",
          List(ToolCall(s"echo-$counter", "run_scala", ujson.write(ujson.Obj("code" -> code))))
        )
      case Some(Msg.ToolResults(results)) => reply("Result:\n" + results.map(_.output).mkString("\n"))
      case Some(Msg.User(text)) => reply(s"echo: $text")
      case _ => Completion("", Nil, None, TokenUsage(), "end_turn")

  def simple(system: Option[String], prompt: String, thinking: Boolean): Reply =
    Reply(s"echo: $prompt", TokenUsage(1, 1))
