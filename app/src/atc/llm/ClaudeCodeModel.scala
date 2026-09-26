package atc.llm

import atc.Debug
import atc.config.{ModelSpec, Tokens}

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal

/** Claude models through the user's own Claude Code CLI (`"api": "claude-code"`), signed in with a
  * Claude plan. The CLI runs in the stream-json mode of the Claude Agent SDK with its built-in tools,
  * settings files, CLAUDE.md, MCP servers and commands turned off, and ATC's tools offered as an
  * in-process MCP server. ATC never reads the CLI's credentials.
  *
  * The CLI runs its own agent loop; ATC's stays in charge. A response that calls tools is returned
  * as a completion while the CLI waits on its MCP calls, and the next [[complete]] answers them with
  * the results the agent produced. One CLI process serves a conversation while each request extends
  * the history the process has seen. Any other history (compaction, a context cut, an interrupt, a
  * model switch, a restored session), or another system prompt, tool list or effort, starts a new
  * process that is given the history as a transcript. */
final class ClaudeCodeModel(spec: ModelSpec, launch: List[String] => ClaudeCli.Process) extends SpecModel(spec):
  def this(spec: ModelSpec) = this(spec, ClaudeCli.launch)

  import ClaudeCodeModel.*

  val providerKey: String = "claude-code"
  protected def knownEfforts: List[String] = List("low", "medium", "high", "xhigh", "max")

  /** What a CLI process was started with. */
  private final case class Setup(system: String, tools: List[ToolSpec], effort: Option[String])

  /** A CLI process and the conversation it holds. */
  private final class Session(val cli: ClaudeCli, val setup: Setup):
    /** The history of the last request, which the CLI has seen. */
    var seen: List[Msg] = Nil
    /** Marks the assistant turn that answered it. */
    var reply: Option[Turn] = None
    /** Its tool calls, which the CLI is waiting on. */
    var pending: List[String] = Nil

  /** What a request sends to its session. */
  private enum Input:
    case Prompt(text: String)
    case Results(results: List[ToolResult])

  private var session: Option[Session] = None

  /** The window the CLI reported. A new session asks for it until the CLI has answered once. */
  @volatile private var reported: Option[Int] = None

  /** The configured window, else the one the CLI reports for this model (known once a
    * session has started; a listed model has it from the listing). */
  override def contextWindow: Option[Int] = settings.contextWindow.map(_.toInt).orElse(reported)

  override def close(): Unit = synchronized(end(session))

  private def end(ending: Option[Session]): Unit = synchronized:
    ending.foreach(_.cli.close())
    if session == ending then session = None

  def complete(
    system: SystemPrompt,
    history: List[Msg],
    tools: List[ToolSpec],
    sink: StreamSink,
    cancelled: () => Boolean
  ): Completion =
    val setup = Setup(system, tools, effort)
    val continued = synchronized(session.filter(_.setup == setup).flatMap(s => continuation(s, history).map(s -> _)))
    val (active, input) = continued.getOrElse:
      Debug.log(s"$ref: new Claude Code session for ${history.size} messages")
      synchronized(end(session))
      val started = Session(start(system, tools, thinking = true), setup)
      // Published first, so that `close` or the next session ends it when the question below is interrupted.
      synchronized { session = Some(started) }
      if settings.contextWindow.isEmpty && reported.isEmpty then
        reported = windowOf(_ => started.cli.contextWindow(), modelId).map(_.toInt)
      started -> Input.Prompt(transcript(history))
    val guard = Guard(() => end(Some(active)))
    try
      ModelRequest.withResource(guard): _ =>
        input match
          case Input.Prompt(text) => active.cli.prompt(text)
          case Input.Results(results) => results.foreach(active.cli.answer)
        val turn = Turn(Turns.incrementAndGet())
        val completion = read(active.cli, sink, cancelled, Some(NativeTurn(providerKey, ref, turn)))
        active.seen = history
        active.reply = Some(turn)
        active.pending = completion.toolCalls.map(_.id)
        guard.finished = true
        completion
    catch
      case e =>
        end(Some(active))
        throw e

  /** What `history` adds to what `s` has seen, when that is something the CLI can continue with:
    * the results of the calls it waits on, or the next user message after a finished answer. */
  private def continuation(s: Session, history: List[Msg]): Option[Input] =
    val seen = s.seen.length
    def same(a: Msg, b: Msg) = (a eq b) || a == b
    if seen == 0 || history.length <= seen + 1 || !history.take(seen).corresponds(s.seen)(same) then None
    else
      history(seen) match
        case Msg.Assistant(_, _, native) if s.reply.exists(r => native.exists(_.payload == r)) =>
          history.drop(seen + 1) match
            case List(Msg.ToolResults(results))
                if s.pending.nonEmpty && results.map(_.callId).toSet == s.pending.toSet =>
              Some(Input.Results(results))
            case added if s.pending.isEmpty && added.forall(userText(_).isDefined) =>
              Some(Input.Prompt(added.flatMap(userText).mkString("\n\n")))
            case _ => None
        case _ => None

  /** Read one response: until it calls tools, or until the CLI's turn ends. */
  private def read(cli: ClaudeCli, sink: StreamSink, cancelled: () => Boolean, native: Option[NativeTurn])
    : Completion =
    val text = StringBuilder()
    var calls = List.empty[ToolCall]
    var usage = TokenUsage()
    var stop = "end_turn"
    var thought = 0
    var result: Option[Completion] = None
    def done(calls: List[ToolCall], reason: String) =
      Some(Completion(text.toString, calls, native, usage, reason, CompletionStop.fromReason(reason)))
    while result.isEmpty do
      if cancelled() then throw CancelledException()
      val message = cli.next()
      // Subagent messages carry the id of the tool call that started them; there are none, but never mix them in.
      val main = message.obj.get("parent_tool_use_id").forall(_.isNull)
      ClaudeCli.string(message, "type") match
        case Some("stream_event") if main =>
          val event = message("event")
          ClaudeCli.string(event, "type") match
            case Some("message_start") => calls = Nil
            case Some("content_block_start") =>
              val block = event("content_block")
              ClaudeCli.string(block, "type") match
                case Some("text") => sink.note("") // a new text block
                case Some("server_tool_use") => sink.note("web search")
                case Some("tool_use") if !ClaudeCli.string(block, "name").exists(_.startsWith(ClaudeCli.ToolPrefix)) =>
                  sink.note("web search") // the CLI's own WebSearch, the only built-in tool it may have
                case _ => ()
            case Some("content_block_delta") =>
              val delta = event("delta")
              ClaudeCli.string(delta, "type") match
                case Some("text_delta") => ClaudeCli.string(delta, "text").foreach(sink.text)
                case Some("thinking_delta") =>
                  ClaudeCli.string(delta, "thinking").foreach: t =>
                    thought += t.length
                    sink.thinking(t)
                case _ => ()
            case Some("message_delta") =>
              event.obj.get("delta").flatMap(ClaudeCli.string(_, "stop_reason")).foreach(stop = _)
              event.obj.get("usage").foreach(u => usage = usage + usageOf(u))
            case Some("message_stop") if calls.nonEmpty => result = done(calls, stop)
            case _ => ()
        case Some("assistant") if main =>
          for
            content <- message("message").obj.get("content").flatMap(_.arrOpt)
            block <- content
          do
            ClaudeCli.string(block, "type") match
              case Some("text") => ClaudeCli.string(block, "text").foreach(t => text.append(t))
              case Some("tool_use") =>
                ClaudeCli.string(block, "name").filter(_.startsWith(ClaudeCli.ToolPrefix)).foreach: name =>
                  val input = block.obj.getOrElse("input", ujson.Obj())
                  calls :+= ToolCall(block("id").str, name.stripPrefix(ClaudeCli.ToolPrefix), ujson.write(input))
              case _ => ()
        case Some("result") =>
          val failed = message.obj.get("is_error").flatMap(_.boolOpt).getOrElse(false) ||
            !ClaudeCli.string(message, "subtype").contains("success")
          if failed then
            val why = ClaudeCli.string(message, "result").filter(_.trim.nonEmpty)
              .orElse(message.obj.get("errors").flatMap(_.arrOpt).map(_.flatMap(_.strOpt).mkString("; ")))
              .orElse(ClaudeCli.string(message, "subtype")).getOrElse("the request failed")
            throw IOException(s"Claude Code: $why")
          ClaudeCli.string(message, "stop_reason").foreach(stop = _)
          result = done(Nil, stop)
        case _ => ()
    Debug.log(s"$ref: stop=$stop calls=${result.get.toolCalls.size} thinking chars=$thought")
    result.get

  def simple(system: Option[String], prompt: String, thinking: Boolean): Reply =
    val cli = start(system.getOrElse(DefaultInstructions), Nil, thinking)
    try
      cli.prompt(prompt)
      val completion = read(cli, StreamSink(_ => ()), () => false, None)
      Reply(completion.text, completion.usage)
    finally cli.close()

  /** The models Claude Code offers the signed-in account, from its `initialize` answer,
    * each with the context window the CLI gives it. Neither makes a model request. */
  private[atc] def listModels(): List[ModelSpec] =
    signedIn()
    val cli = ClaudeCli.start(launch(arguments(Nil, thinking = true)), Nil)
    try
      val answer = cli.initialize(DefaultInstructions)
      models(spec, answer, id => { cli.setModel(id); cli.contextWindow() })
    finally cli.close()

  /** Fail with what to do when Claude Code is signed in to nothing. */
  private def signedIn(): Unit =
    val process = launch(List("auth", "status"))
    val status =
      try String(process.output.readAllBytes().nn, UTF_8)
      finally process.stop()
    val loggedIn =
      try ujson.read(status).obj.get("loggedIn").flatMap(_.boolOpt)
      catch case NonFatal(_) => None
    if loggedIn.contains(false) then
      throw IOException("Claude Code is not signed in; run `claude auth login` in a terminal, then try again")

  private def start(system: String, tools: List[ToolSpec], thinking: Boolean): ClaudeCli =
    val cli = ClaudeCli.start(launch(arguments(tools, thinking)), tools)
    try
      cli.initialize(system)
      cli
    catch
      case e =>
        cli.close()
        throw e

  /** The CLI's command line. Only ATC's tools are allowed, without a prompt; anything else
    * that would ask is denied. The CLI asks the API to omit thinking text unless told
    * otherwise; `summarized` (a hidden flag) returns the most of it: a summary, or the full
    * text from models that give it, such as Haiku 4.5. */
  private def arguments(tools: List[ToolSpec], thinking: Boolean): List[String] =
    val builtIn = if webSearch && tools.nonEmpty then List("WebSearch") else Nil
    val allowed = builtIn ++ Option.when(tools.nonEmpty)(s"mcp__${ClaudeCli.Server}")
    val mcp = ujson.Obj("mcpServers" -> ujson.Obj(ClaudeCli.Server -> ujson.Obj(
      "type" -> "sdk",
      "name" -> ClaudeCli.Server,
    )))
    List(
      "-p",
      "--input-format=stream-json",
      "--output-format=stream-json",
      "--verbose",
      "--include-partial-messages",
      s"--tools=${builtIn.mkString(",")}",
      "--setting-sources=",
      "--strict-mcp-config",
      "--disable-slash-commands",
      "--no-session-persistence",
      "--no-chrome",
      "--permission-mode=dontAsk",
      "--permission-prompts=none",
    ) ++
      Option.when(tools.nonEmpty)(List("--mcp-config", ujson.write(mcp))).toList.flatten ++
      Option.when(allowed.nonEmpty)(s"--allowedTools=${allowed.mkString(",")}") ++
      Option.when(modelId.nonEmpty)(s"--model=$modelId") ++
      effort.map(e => s"--effort=$e") ++
      (if !thinking || settings.thinking.contains(false) then List("--thinking=disabled")
       else List("--thinking-display=summarized"))

private[atc] object ClaudeCodeModel:
  /** Marks an assistant turn a CLI session produced; the payload of its [[NativeTurn]]. */
  final case class Turn(id: Long)
  private val Turns = AtomicLong()

  /** The window the CLI reports for `id`; `None` when it does not answer. */
  private def windowOf(window: String => Option[Long], id: String): Option[Tokens] =
    try window(id).flatMap(Tokens.from)
    catch
      case NonFatal(e) =>
        Debug.log(s"claude-code: no context window for $id: ${Debug.message(e)}")
        None

  /** The system prompt of a one-shot call that has none. */
  private val DefaultInstructions = "Answer the request."

  /** Ends a CLI session when a cancelled request closes it before it finished. */
  private final class Guard(onCancel: () => Unit) extends AutoCloseable:
    @volatile var finished = false
    def close(): Unit = if !finished then onCancel()

  private def userText(m: Msg): Option[String] = m match
    case Msg.User(text) => Some(text)
    case Msg.Continuation(text) => Some(text)
    case _ => None

  /** `input` is the whole prompt, as for the Anthropic adapter. */
  private def usageOf(u: ujson.Value): TokenUsage =
    def count(key: String) = u.obj.get(key).flatMap(_.numOpt).fold(0L)(_.toLong)
    val cacheRead = count("cache_read_input_tokens")
    TokenUsage(
      count("input_tokens") + cacheRead + count("cache_creation_input_tokens"),
      count("output_tokens"),
      cacheRead
    )

  /** The first message of a new session: the request itself, or the history as a transcript
    * followed by the latest user message. */
  private[atc] def transcript(history: List[Msg]): String = history match
    case List(message) if userText(message).isDefined => userText(message).get
    case _ =>
      val latest = history.lastOption.flatMap(userText)
      val earlier = if latest.isDefined then history.init else history
      val entries = earlier.flatMap:
        case Msg.User(text) => List(s"[user]\n$text")
        case Msg.Continuation(text) => List(s"[user]\n$text")
        case Msg.Assistant(text, calls, _) =>
          Option.when(text.nonEmpty)(s"[assistant]\n$text").toList ++
            calls.map(c => s"[tool call ${c.name}, id ${c.id}]\n${c.arguments}")
        case Msg.ToolResults(results) =>
          results.map(r => s"[tool result, id ${r.callId}${if r.isError then ", error" else ""}]\n${r.output}")
      val ending = latest.fold("Continue from where the transcript ends.")(text => s"[user, the latest message]\n$text")
      (TranscriptIntro :: (entries :+ ending)).mkString("\n\n")

  private val TranscriptIntro =
    "This conversation continues from an earlier session, whose transcript follows. The tool calls in it " +
      "have run and their results are shown; do not run them again."

  /** The models of an `initialize` answer, as listed models of `spec`'s provider, with the
    * context window `window` reports for a model id. `default` stands for one of the others
    * and is left out. */
  private[atc] def models(spec: ModelSpec, answer: ujson.Value, window: String => Option[Long])
    : List[ModelSpec] =
    answer.obj.get("models").flatMap(_.arrOpt).toList.flatten.flatMap: m =>
      ClaudeCli.string(m, "value").filter(v => v.nonEmpty && v != "default").map: id =>
        val efforts = m.obj.get("supportedEffortLevels").flatMap(_.arrOpt).map(_.flatMap(_.strOpt).toList)
        val adaptive = m.obj.get("supportsAdaptiveThinking").flatMap(_.boolOpt)
        spec.listed(
          id,
          spec.settings.copy(
            displayName = ClaudeCli.string(m, "displayName").map(_.trim).filter(_.nonEmpty),
            efforts = Some(efforts.getOrElse(Nil)),
            thinking = adaptive.filterNot(identity),
            contextWindow = spec.settings.contextWindow.orElse(windowOf(window, id)),
          ),
        )
