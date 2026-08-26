package atc.agent

import atc.config.Config
import atc.llm.*
import atc.perms.{Decision, Policy}
import atc.sandbox.{ExecutionResult, ReplSession}

/** The agent loop: user message → (model → tool calls)* → final answer.
  *
  * A *turn* handles one user message as a sequence of *rounds*. Each round
  * asks the model once and then acts on its answer:
  *
  *  - tool calls → run them (`run_scala`, one at a time), append the results
  *    and ask again;
  *  - a resumable completion (the provider paused after a server-side tool or
  *    hit an output limit) → ask again so the model resumes;
  *  - anything else is the final answer: the turn is over (the system prompt
  *    tells the model that ending without a tool call means "finished"; the loop
  *    does not second-guess its prose).
  *
  * Bounds keep a confused model from looping: `config.maxToolCalls` per turn (a
  * checkpoint: the UI may grant another budget, see [[AgentUI.confirmMoreToolCalls]]),
  * [[Agent.MaxResumes]] and [[Agent.MaxBudgetRejections]].
  * `cancelled` is polled while streaming and before every tool call; an
  * interrupted turn ends with an `[interrupted by user]` assistant message so
  * the history stays well-formed. */
final class Agent(
  config: Config,
  environment: AgentEnvironment,
  policy: Policy,
  ui: AgentUI,
  initialModel: ChatModel,
  /** The model that may see classified data; switchable with `/classifiedmodel`. */
  var classifiedModel: Option[ChatModel],
  extraInstructions: Option[String],
):
  private var currentModel = initialModel
  def model: ChatModel = currentModel
  /** Switching model changes both the tokenizer and often the wire payload.
    * Keep the conversation, but discard calibration learned from the previous
    * model so it cannot make the new model cut too much or overflow its window. */
  def model_=(next: ChatModel): Unit =
    currentModel = next
    context.modelChanged()

  private val conversation = Conversation()
  def history: List[Msg] = conversation.history
  /** Every model call since the last `clear()`, grouped by purpose
    * ([[Agent.Turns]], [[Agent.Chat]], ...) in order of first use. Access is
    * synchronized because next-input prediction records usage on another thread. */
  private val usageBy = scala.collection.mutable.LinkedHashMap[String, TokenUsage]()
  /** Tool calls actually run since the last `clear()`. */
  var toolCalls: Int = 0
  private val context = ContextManager()

  /** Everything spent on the model(s) since the last `clear()`. */
  def usage: TokenUsage = synchronized(usageBy.values.foldLeft(TokenUsage())(_ + _))
  /** The same, by purpose, in the order the purposes first occurred. */
  def usageByPurpose: List[(String, TokenUsage)] = synchronized(usageBy.toList)
  /** Add what one model call cost. */
  def recordUsage(purpose: String, u: TokenUsage): Unit =
    synchronized { usageBy.update(purpose, usageBy.getOrElse(purpose, TokenUsage()) + u) }

  /** The one and only native tool: everything else is a Scala function. */
  private var tools = ScalaToolRunner.tools
  private val sink: StreamSink = StreamSink(ui.assistantDelta, ui.assistantNote, ui.thinkingDelta)

  def systemPrompt: SystemPrompt =
    Prompts.system(
      environment,
      policy,
      classifiedModel.isDefined,
      config.safeMode,
      config.respectGitignore,
      extraInstructions,
    )

  /** Tokens of every request besides the history: the system prompt and the tool schema. */
  private def fixedTokens: Long =
    ContextManager.estimateTokens(systemPrompt.text) + tools.map(t =>
      ContextManager.estimateTokens(t.description + t.parametersJson)
    ).sum

  /** How full the model's context is: the estimated tokens of the next request
    * (system prompt, tool schema and history, corrected by the calibration
    * against the provider's count for the last one) and the model's window,
    * when the config states it. What the TUI shows after each turn. */
  def contextUsage: (tokens: Long, window: Option[Int]) =
    val usage = context.contextUsage(fixedTokens, history, model)
    (tokens = usage.tokens, window = usage.window)

  /** Tell the model that its REPL was replaced, so it does not conclude that
    * the documented "definitions persist between calls" guarantee is false when
    * its earlier `val`s and `def`s have vanished. */
  def noteSandboxRestarted(reason: String): Unit =
    conversation.queueNote(AgentMessages.sandboxRestarted(reason))

  /** Tell the model what the user ran in the shared REPL (`/run`) and what came
    * of it: the user's definitions are now part of the session the model
    * continues in, and the result may be what the next request is about. */
  def noteUserRan(code: String, result: ExecutionResult, decisions: List[(Decision, String)] = Nil): Unit =
    conversation.queueNote(AgentMessages.userRan(
      code,
      ToolOutput.renderForModel(result, config.maxToolOutputChars, decisions),
    ))

  def clear(): Unit =
    conversation.clear()
    synchronized { usageBy.clear() }
    toolCalls = 0
    context.reset()

  /** Run one user turn; returns when the model gives its final answer or the user interrupts. */
  def turn(session: ReplSession, input: String, cancelled: () => Boolean): Unit =
    runTurn(ScalaToolRunner(session, policy, ui, config.maxToolOutputChars), input, cancelled)

  /** Core entry point, with concrete tool execution supplied by the host adapter. */
  private[atc] def runTurn(runner: ToolRunner, input: String, cancelled: () => Boolean): Unit =
    tools = runner.tools
    conversation.beginTurn(input)
    context.beginTurn()
    Turn(runner, cancelled).run()

  private enum Outcome:
    /** Something was appended to the history; ask the model again. */
    case Continue
    case Done

  /** One turn: the round loop plus the counters the bounds are checked against. */
  private final class Turn(runner: ToolRunner, cancelled: () => Boolean):
    import Outcome.*
    private var used = 0 // tool calls run this turn
    private var budget = config.maxToolCalls // grows by `maxToolCalls` each time the user says "continue"
    private var resumes = 0
    private var budgetRejections = 0

    def run(): Unit =
      var outcome = Continue
      try while outcome == Continue do outcome = round()
      catch
        // Keep the transcript valid after a failed turn. Providers reject consecutive
        // user messages and tool requests without matching results:
        //  - If tool execution failed before its results were recorded, add an error
        //    result for every pending request.
        //  - If the model call failed immediately after the user message, add an
        //    assistant marker to preserve role alternation.
        //  - Otherwise, the history already ends with an assistant message or tool
        //    result and needs no repair; another assistant marker would be invalid.
        case e if scala.util.control.NonFatal(e) =>
          conversation.repairAfter(e)
          throw e

    /** Ask the model once, record its answer, then act on it. */
    private def round(): Outcome =
      if cancelled() then interrupted()
      else
        val prepared = context.prepare(fixedTokens, history, model)
        conversation.useHistory(prepared.history)
        prepared.warnings.foreach(ui.warn)
        ui.status(AgentMessages.thinkingStatus(model.alias))
        completeRound() match
          case None => interrupted()
          case Some(raw) =>
            recordUsage(Agent.Turns, raw.usage)
            context.calibrate(raw, prepared.estimatedInput)

            val accepted = CompletionPolicy(raw)
            conversation.append(accepted.message)
            accepted.warnings.foreach(ui.warn)
            accepted.next match
              case CompletionPolicy.Next.RunTools(calls) => runTools(calls)
              case CompletionPolicy.Next.Blocked => Done
              case CompletionPolicy.Next.Finish => if cancelled() then interrupted() else Done
              case CompletionPolicy.Next.Resume(needsContinuation) =>
                if cancelled() then interrupted()
                else if resumes < Agent.MaxResumes then
                  if needsContinuation then conversation.append(Msg.Continuation(AgentMessages.truncationContinuation))
                  resume()
                else
                  ui.warn(AgentMessages.resumeExhaustedWarning(model.alias, Agent.MaxResumes))
                  Done

    private def completeRound(): Option[Completion] =
      try Some(model.complete(systemPrompt, history, tools, sink, cancelled))
      catch
        case _: CancelledException => None
      finally ui.assistantEnd()

    /** Run the requested tools in order, honouring cancellation and the per-turn budget. */
    private def runTools(calls: List[ToolCall]): Outcome =
      var overBudget = false
      val results = calls.map { call =>
        if cancelled() then ToolResult(call.id, AgentMessages.cancelledBeforeExecution, isError = true)
        else if used >= budget && !extendBudget() then
          overBudget = true
          ToolResult(
            call.id,
            AgentMessages.toolBudgetExhausted(config.maxToolCalls),
            isError = true
          )
        else
          used += 1
          toolCalls += 1
          runner.run(call)
      }
      conversation.append(Msg.ToolResults(results))
      if cancelled() then interrupted()
      else if !overBudget then Continue
      else
        // Give the model a chance to react to the budget error, then stop insisting.
        budgetRejections += 1
        if budgetRejections < Agent.MaxBudgetRejections then Continue
        else
          ui.warn(AgentMessages.toolBudgetLoopWarning)
          Done

    /** Treat the budget as a checkpoint by asking the user for another
      * `maxToolCalls` allocation. A budget of zero disables tools and cannot be
      * extended. If the user declines, remember that decision for the rest of the
      * turn to avoid repeated prompts from the same batch or later rounds. */
    private var budgetDenied = false
    private def extendBudget(): Boolean =
      if budgetDenied || config.maxToolCalls <= 0 then false
      else if ui.confirmMoreToolCalls(used, config.maxToolCalls) then
        budget += config.maxToolCalls
        true
      else
        budgetDenied = true
        false

    /** The provider cut the response after a server-side tool call: re-send the history. */
    private def resume(): Outcome =
      resumes += 1
      ui.status(AgentMessages.resumingStatus)
      Continue

    private def interrupted(): Outcome =
      // A paused/resumed round already ends in an assistant message. Do
      // not append a second one: neutral provider replays require valid role
      // alternation. User/tool-result endings do need a closing assistant.
      conversation.interrupt()
      ui.warn(AgentMessages.interruptedWarning)
      Done

object Agent:
  /** Purposes a model call is recorded under (`/cost`). */
  val Turns = "agent turns"
  val Chat = "chat()"
  val ClassifiedChat = "classifiedChat()"
  val Prediction = "next-input prediction"

  /** Server-side tool pauses (web search) per turn; a research turn can take many. */
  val MaxResumes = 20
  /** Rounds in which the model may hit the exhausted tool budget before the turn is stopped. */
  val MaxBudgetRejections = 2
