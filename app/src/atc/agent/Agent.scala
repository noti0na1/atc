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
  /** The model that may see classified data; switchable with `/classifiedmodel`. */
  var classifiedModel: Option[ChatModel],
  extraInstructions: Option[String],
):
  var history: List[Msg] = Nil
  /** Every model call since the last `clear()`, by what it was for
    * ([[Agent.Turns]], [[Agent.Chat]], ...). Guarded: the next-input
    * prediction records from its own thread. */
  private var usageBy: Map[String, TokenUsage] = Map.empty
  /** Tool calls actually run since the last `clear()`. */
  var toolCalls: Int = 0
  /** Observed prompt tokens per estimated one, from the last completion: the
    * chars-per-token guess is off for code and for CJK text, and the provider's
    * own count for the request we just sent puts it right. */
  private var tokenCalibration: Double = 1.0
  /** Messages cut from the front of the history so far (the notice tells the model the total). */
  private var contextDropped: Int = 0

  /** Everything spent on the model(s) since the last `clear()`. */
  def usage: TokenUsage = synchronized(usageBy.values.foldLeft(TokenUsage())(_ + _))
  /** The same, by purpose, in the order the purposes first occurred. */
  def usageByPurpose: List[(String, TokenUsage)] = synchronized(usageBy.toList)
  /** Add what one model call cost. */
  def recordUsage(purpose: String, u: TokenUsage): Unit =
    synchronized { usageBy = usageBy.updated(purpose, usageBy.getOrElse(purpose, TokenUsage()) + u) }

  /** The one and only native tool: everything else is a Scala function. */
  private val tools = List(ToolSpec(Prompts.ToolName, Prompts.toolDescription, Prompts.toolParameters))
  private val sink: StreamSink = StreamSink(ui.assistantDelta, ui.assistantNote, ui.thinkingDelta)

  def systemPrompt: SystemPrompt =
    Prompts.system(cwd, policy, classifiedModel.map(_.ref), config.respectGitignore, extraInstructions)

  /** Notes prepended to the next user message, in order: that the sandbox was
    * restarted, code the user ran themselves. They are carried this way rather
    * than appended to the history on their own so the transcript never holds
    * two user messages in a row. */
  private var pendingNotes: List[String] = Nil

  /** Tell the model that its REPL was replaced, so it does not conclude that
    * the documented "definitions persist between calls" guarantee is false when
    * its earlier `val`s and `def`s have vanished. */
  def noteSandboxRestarted(reason: String): Unit =
    pendingNotes :+=
      s"[sandbox notice] The Scala REPL was restarted ($reason). Every `val`, `def` and `import` " +
        "you defined earlier is gone, so re-create anything you still need. The conversation itself is unchanged."

  /** Tell the model what the user ran in the shared REPL (`/run`) and what came
    * of it: the user's definitions are now part of the session the model
    * continues in, and the result may be what the next request is about. */
  def noteUserRan(code: String, result: ExecutionResult): Unit =
    pendingNotes :+=
      s"[user ran code] The user ran this in the sandbox REPL themselves (its definitions persist for you too):\n" +
        s"```scala\n$code\n```\nResult:\n${Agent.renderForModel(result, config.maxToolOutputChars)}"

  def clear(): Unit =
    history = Nil
    synchronized { usageBy = Map.empty }
    toolCalls = 0
    tokenCalibration = 1.0
    contextDropped = 0
    pendingNotes = Nil

  /** Run one user turn; returns when the model gives its final answer or the user interrupts. */
  def turn(session: ReplSession, input: String, cancelled: () => Boolean): Unit =
    history :+= Msg.User((pendingNotes :+ input).mkString("\n\n"))
    pendingNotes = Nil
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
      fitHistoryToContext()
      val estimated = fixedTokens + history.map(Agent.estimateTokens).sum
      ui.status(s"${model.alias} is thinking")
      val completion =
        try model.complete(systemPrompt, history, tools, sink, cancelled)
        catch
          case _: CancelledException =>
            ui.assistantEnd()
            return interrupted()
      ui.assistantEnd()
      recordUsage(Agent.Turns, completion.usage)
      // The provider counted the request we just sent: correct the estimator with it.
      if completion.usage.input >= Agent.CalibrationMinTokens && estimated > 0 then
        tokenCalibration = (completion.usage.input.toDouble / estimated).max(0.25).min(8.0)
      history :+= Msg.Assistant(completion.text, completion.toolCalls, completion.native)
      if completion.stopReason == "refusal" then
        ui.warn("The model refused this request (stop_reason=refusal).")

      if completion.toolCalls.nonEmpty then runTools(completion.toolCalls)
      else if cancelled() then Done
      else if completion.unfinished && resumes < Agent.MaxResumes then resume()
      else if Agent.looksUnfinished(completion.text) && nudges < Agent.MaxNudges then nudge()
      else Done

    /** Tokens of every request besides the history: the system prompt and the tool schema. */
    private def fixedTokens: Long =
      Agent.estimateTokens(systemPrompt.text) + tools.map(t =>
        Agent.estimateTokens(t.description + t.parametersJson)
      ).sum

    /** When the model has a `contextWindow`, drop the oldest exchanges from the
      * history until the next request should fit it, leaving room for the
      * answer. What was dropped stays in the terminal's scrollback, and the
      * model is told what happened. TODO: compact instead of cut (summarise the
      * dropped exchanges into one message) once the estimator has been proven
      * in use. */
    private def fitHistoryToContext(): Unit =
      model.contextWindow.foreach { window =>
        val reserve = window / 8 // for the model's own answer and estimation slack
        val room = ((window - reserve) / tokenCalibration).toLong - fixedTokens
        val (kept, dropped) = Agent.fitToContext(history, room)
        if dropped > 0 then
          contextDropped += dropped
          history = kept match
            case Msg.User(t) :: rest => Msg.User(s"${Agent.contextCutNotice(contextDropped)}\n\n$t") :: rest
            case other => other
          ui.warn(
            s"context window of ${model.alias} (${window} tokens): the oldest $dropped messages were dropped from what the model sees"
          )
      }

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
  /** Purposes a model call is recorded under (`/cost`). */
  val Turns = "agent turns"
  val Chat = "chat()"
  val ClassifiedChat = "chat(Classified)"
  val Prediction = "next-input prediction"

  /** Below this many prompt tokens a completion is not used to calibrate the estimator. */
  val CalibrationMinTokens = 200L

  /** A rough token count: about four characters per token, corrected at run
    * time by [[Agent]]'s calibration against the provider's own numbers. */
  def estimateTokens(text: String): Long = (text.length + 3) / 4

  /** A message's share of a request, with a little per-message framing. */
  def estimateTokens(msg: Msg): Long = msg match
    case Msg.User(t) => estimateTokens(t) + 4
    case Msg.Assistant(t, calls, _) => estimateTokens(t) + calls.map(c => estimateTokens(c.arguments) + 12).sum + 4
    case Msg.ToolResults(rs) => rs.map(r => estimateTokens(r.output) + 12).sum + 4

  /** `history` cut to an estimated `budget` tokens by dropping whole exchanges
    * from the front: a cut always starts at a user message, so no tool result
    * is left without the call it answers, and the last user message and what
    * follows it are always kept (even when they alone exceed the budget: there
    * is nothing better to send). Returns the kept history and how many
    * messages were dropped. */
  def fitToContext(history: List[Msg], budget: Long): (List[Msg], Int) =
    val lastUser = history.lastIndexWhere(_.isInstanceOf[Msg.User])
    var start = 0
    var total = history.map(estimateTokens).sum
    while total > budget && start < lastUser do
      // drop up to (excluding) the next user message
      val next = history.indexWhere(_.isInstanceOf[Msg.User], start + 1)
      val cut = if next < 0 || next > lastUser then lastUser else next
      total -= history.slice(start, cut).map(estimateTokens).sum
      start = cut
    (history.drop(start), start)

  def contextCutNotice(dropped: Int): String =
    s"[context notice] The $dropped oldest messages of this conversation were dropped to fit your context window; " +
      "if you need something from them, ask the user or read it again."

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
      "that API is not available in safe mode (only the sandbox API, immutable collections and plain JDK utilities are); e.g. `throw RuntimeException(...)` instead of sys.error, and a local `var` over an immutable `List`/`Vector`/`Map` instead of `ListBuffer`/`mutable.Map`."
    ),
    Hint(
      out => out.contains("Ambiguous given instances") && out.contains("FileSystem"),
      "do not define your own `given FileSystem`; use requestFiles(...) { ... } blocks."
    ),
    Hint(
      out => out.contains("cannot subsume a read-only capture set") || out.contains("Cannot call update method"),
      "you only have read-only access there: a bare `FileSystem`/`IOCap` type is the read-only view (write `FileSystem^` / `IOCap^` for the full one in your own signatures), and in read-only sandbox mode nothing can write, run commands or use the network. Say so and let the user switch modes (/mode) instead of working around it."
    ),
    Hint(
      out =>
        out.contains("No given instance of type atc.lib.Network") || out.contains(
          "No given instance of type atc.lib.Exec"
        ),
      "that capability does not exist in the current sandbox mode (local: no network; read-only: no commands, no network); tell the user which mode the task needs (/mode local, /mode full)."
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
