package atc.agent

import atc.config.Config
import atc.llm.*
import atc.perms.Policy
import atc.sandbox.{ExecutionResult, ReplSession}

import java.nio.file.Path

/** What the agent reports to the terminal. */
trait AgentUI:
  def assistantDelta(text: String): Unit
  /** Out-of-band note during streaming ("web search"); empty = paragraph break. */
  def assistantNote(text: String): Unit
  def assistantEnd(): Unit
  /** A piece of the model's reasoning, when the provider streams it. */
  def thinkingDelta(text: String): Unit
  def toolStart(code: String): Unit
  /** `millis` is execution time excluding the time spent waiting for the user. */
  def toolEnd(result: ExecutionResult, millis: Long): Unit
  def status(text: String): Unit
  def warn(text: String): Unit

/** The agent loop: user message → (model → tool calls)* → final answer.
  *
  * A *turn* handles one user message as a sequence of *rounds*. Each round
  * asks the model once and then acts on its answer:
  *
  *  - tool calls → run them (`run_scala`, one at a time), append the results
  *    and ask again;
  *  - `unfinished` (the provider paused after a server-side tool such as web
  *    search) → ask again so the model resumes;
  *  - a reply that merely announces a next step ("Let me check…") → nudge the
  *    model to act;
  *  - anything else is the final answer: the turn is over.
  *
  * Bounds keep a confused model from looping: `config.maxToolCalls` per turn,
  * [[Agent.MaxResumes]], [[Agent.MaxNudges]] and [[Agent.MaxBudgetRejections]].
  * `cancelled` is polled while streaming and before every tool call; an
  * interrupted turn ends with an `[interrupted by user]` assistant message so
  * the history stays well-formed. */
final class Agent(
  config: Config,
  cwd: Path,
  policy: Policy,
  ui: AgentUI,
  var model: ChatModel,
  val safeModel: Option[ChatModel],
  extraInstructions: Option[String],
):
  var history: List[Msg] = Nil
  var usage: TokenUsage = TokenUsage()
  /** Tool calls actually run since the last `clear()`. */
  var toolCalls: Int = 0

  /** The one and only native tool: everything else is a Scala function. */
  private val tools = List(ToolSpec(Prompts.ToolName, Prompts.toolDescription, Prompts.toolParameters))
  private val sink: StreamSink = StreamSink(ui.assistantDelta, ui.assistantNote, ui.thinkingDelta)

  def systemPrompt: String = Prompts.system(cwd, policy, safeModel.map(_.alias), extraInstructions)

  def clear(): Unit =
    history = Nil
    usage = TokenUsage()
    toolCalls = 0

  /** Run one user turn; returns when the model gives its final answer or the user interrupts. */
  def turn(session: ReplSession, input: String, cancelled: () => Boolean): Unit =
    history :+= Msg.User(input)
    Turn(session, cancelled).run()

  private enum Outcome:
    /** Something was appended to the history; ask the model again. */
    case Continue
    case Done

  /** One turn: the round loop plus the counters the bounds are checked against. */
  private final class Turn(session: ReplSession, cancelled: () => Boolean):
    import Outcome.*
    private var used = 0 // tool calls run this turn
    private var resumes = 0
    private var nudges = 0
    private var budgetRejections = 0

    def run(): Unit =
      var outcome = Continue
      while outcome == Continue do outcome = round()

    /** Ask the model once, record its answer, then act on it. */
    private def round(): Outcome =
      ui.status(s"${model.alias} is thinking")
      val completion =
        try model.complete(systemPrompt, history, tools, sink, cancelled)
        catch
          case _: CancelledException =>
            ui.assistantEnd()
            return interrupted()
      ui.assistantEnd()
      usage += completion.usage
      history :+= Msg.Assistant(completion.text, completion.toolCalls, completion.native)
      if completion.stopReason == "refusal" then
        ui.warn("The model refused this request (stop_reason=refusal).")

      if completion.toolCalls.nonEmpty then runTools(completion.toolCalls)
      else if cancelled() then Done
      else if completion.unfinished && resumes < Agent.MaxResumes then resume()
      else if Agent.looksUnfinished(completion.text) && nudges < Agent.MaxNudges then nudge()
      else Done

    /** Run the requested tools in order, honouring cancellation and the per-turn budget. */
    private def runTools(calls: List[ToolCall]): Outcome =
      var overBudget = false
      val results = calls.map { call =>
        if cancelled() then ToolResult(call.id, "Cancelled by the user before execution.", isError = true)
        else if used >= config.maxToolCalls then
          overBudget = true
          ToolResult(
            call.id,
            s"Tool budget of ${config.maxToolCalls} calls per turn exhausted; answer the user now.",
            isError = true
          )
        else
          used += 1
          toolCalls += 1
          runTool(call)
      }
      history :+= Msg.ToolResults(results)
      if cancelled() then interrupted()
      else if !overBudget then Continue
      else
        // Give the model a chance to react to the budget error, then stop insisting.
        budgetRejections += 1
        if budgetRejections < Agent.MaxBudgetRejections then Continue
        else
          ui.warn("model kept requesting tools after exhausting the tool budget; stopping this turn")
          Done

    /** The provider cut the response after a server-side tool call: re-send the history. */
    private def resume(): Outcome =
      resumes += 1
      ui.status("resuming")
      Continue

    /** The model narrated its next step and stopped without acting. */
    private def nudge(): Outcome =
      nudges += 1
      ui.warn("model ended its turn on a plan; asking it to continue")
      history :+= Msg.User(Agent.ContinueNudge)
      Continue

    private def interrupted(): Outcome =
      history :+= Msg.Assistant("[interrupted by user]", Nil, None)
      ui.warn("interrupted")
      Done

    private def runTool(call: ToolCall): ToolResult = call.name match
      case Prompts.ToolName => runScala(call)
      case other =>
        ToolResult(
          call.id,
          s"Unknown tool '$other'. Only ${Prompts.ToolName} is available; everything else is a Scala function.",
          isError = true
        )

    private def runScala(call: ToolCall): ToolResult =
      val code = Json.parseObject(call.arguments).value.get("code").flatMap(_.strOpt).getOrElse("")
      if code.trim.isEmpty then return ToolResult(call.id, "Missing 'code' argument.", isError = true)
      ui.toolStart(code)
      val start = System.nanoTime()
      val result = session.run(code)
      // Time the snippet spent waiting for the user (prompts, questions) is not execution time.
      val millis = (System.nanoTime() - start - session.clock.paused) / 1_000_000L
      ui.toolEnd(result, millis)
      ToolResult(call.id, Agent.renderForModel(result, config.maxToolOutputChars), isError = !result.success)

object Agent:
  val MaxNudges = 2
  val MaxResumes = 6
  /** Rounds in which the model may hit the exhausted tool budget before the turn is stopped. */
  val MaxBudgetRejections = 2
  val ContinueNudge =
    "You ended your turn with a plan but without acting. If work remains, do it now by calling run_scala; " +
      "if you are actually finished, reply with your final summary."

  private val intentTail =
    """(?is).*\b(let me(?! know)|let's|i'll|i will|i am going to|i'm going to|now i|next,? i)\b[^!?]{0,200}$""".r
  /** Heuristic: the last sentence announces further work. */
  def looksUnfinished(text: String): Boolean =
    val t = text.trim
    t.nonEmpty && intentTail.matches(t.takeRight(240))

  /** A hint appended to tool output for a common capture-checking / safe-mode stumble. */
  private case class Hint(applies: String => Boolean, text: String)
  private val hints = List(
    Hint(
      _.contains("needs an explicit type because the inferred type does not conform"),
      "top-level vals that hold capabilities (FileEntry, closures using println/fs) need an explicit type, e.g. `val e: FileEntry^{fs} = access(...)`, or use a `def` / inline expression."
    ),
    Hint(
      out => out.contains("Cannot refer to") && out.contains("from safe code"),
      "that API is not available in safe mode (only the sandbox API, immutable collections and plain JDK utilities are); e.g. `throw RuntimeException(...)` instead of sys.error, and an immutable `List`/`Vector` with `mkString` instead of `ListBuffer`/`StringBuilder`."
    ),
    Hint(
      out => out.contains("Ambiguous given instances") && out.contains("FileSystem"),
      "do not define your own `given FileSystem`; use requestFiles(...) { ... } blocks."
    ),
  )

  /** Tool output as the model sees it: hint-annotated and bounded (cut in the
    * middle so both the first diagnostics and the tail survive). */
  def renderForModel(r: ExecutionResult, maxChars: Int): String =
    val base = r.render
    val hinted = hints.find(_.applies(base)).fold(base)(h => s"$base\nHint: ${h.text}")
    if hinted.length <= maxChars then hinted
    else
      val head = hinted.take(maxChars * 2 / 3)
      val tail = hinted.takeRight(maxChars / 3)
      s"$head\n... [${hinted.length - maxChars} characters omitted] ...\n$tail"
