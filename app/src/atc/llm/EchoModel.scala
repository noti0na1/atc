package atc.llm

/** A local, key-less model for smoke tests and demos (`"api": "echo"`). It
  * replies with the user's text; a message of the form `run: <scala>` makes it
  * call `run_scala` with that code and then report the result. */
object EchoModel:
  def apply(alias: String, ref: String, contextWindow: Option[Int] = None): EchoModel =
    new EchoModel(alias, ref, contextWindow)
  /** An echo model that is its own reference (tests and smoke runs). */
  def apply(alias: String): EchoModel = new EchoModel(alias, alias, None)

final class EchoModel(val alias: String, override val ref: String, override val contextWindow: Option[Int])
    extends ChatModel:
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
      case Some(Msg.User(text)) =>
        runPayload(text) match
          case Some(code) =>
            counter += 1
            reply(
              "Running that in the sandbox.",
              List(ToolCall(s"echo-$counter", "run_scala", ujson.write(ujson.Obj("code" -> code))))
            )
          case None => reply(s"echo: $text")
      case Some(Msg.ToolResults(results)) => reply("Result:\n" + results.map(_.output).mkString("\n"))
      case _ => Completion("", Nil, None, TokenUsage(), "end_turn")

  /** The `run: <scala>` payload of a user message, if it has one. Agent notes
    * (`[sandbox notice] ...`) may be prepended to the text, so the marker is
    * looked for at the start of any line, not just the first. */
  private def runPayload(text: String): Option[String] =
    val lines = text.linesIterator.toList
    lines.indexWhere(_.trim.startsWith("run:")) match
      case -1 => None
      case i =>
        val code = (lines(i).trim.stripPrefix("run:") +: lines.drop(i + 1)).mkString("\n").trim
        Some(code).filter(_.nonEmpty)

  def simple(system: Option[String], prompt: String, thinking: Boolean): Reply =
    Reply(s"echo: $prompt", TokenUsage(1, 1))
