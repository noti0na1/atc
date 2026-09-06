package atc.agent

import atc.config.Config
import atc.llm.*
import atc.perms.{Decision, Policy}
import atc.sandbox.{ExecutionResult, ReplSession}

/** Runs each user turn as model completions followed by sequential tool calls.
  * Provider pauses and output limits may resume generation. Tool budgets,
  * resume limits and cancellation bound each turn. Conversation repairs keep
  * provider history valid after interruptions or failures. */
final class Agent(
  config: Config,
  environment: AgentEnvironment,
  policy: Policy,
  ui: AgentUI,
  initialModel: ChatModel,
  /** The model that may see classified data; switchable with `/classifiedmodel`. */
  var classifiedModel: Option[ChatModel],
  extraInstructions: Option[String],
  taskState: () => (atc.lib.TaskNotes, List[atc.lib.Todo]) = () => (atc.lib.TaskNotes(), Nil),
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
  def snapshot: SessionSnapshot =
    val (notes, todos) = taskState()
    SessionSnapshot(history, conversation.notes, conversation.userRequests, notes, todos, model.ref)

  def restore(saved: SessionSnapshot): Unit =
    clear()
    conversation.restore(saved.history, saved.pendingNotes, saved.userRequests)
    noteSandboxRestarted("a saved conversation was resumed; previous tool calls were not replayed")
    conversation.queueNote(
      "[permissions] Grants from the saved session are no longer active. Check the current policy and request any permissions still needed."
    )

  private def retainedContext: String =
    val (notes, todos) = taskState()
    ujson.write(ujson.Obj(
      "originalRequest" -> conversation.userRequests.headOption.getOrElse(""),
      "recentUserInstructions" -> ujson.Arr.from(conversation.userRequests.drop(1).takeRight(8).map(_.take(2000))),
      "goal" -> notes.goal,
      "constraints" -> ujson.Arr.from(notes.constraints),
      "completed" -> ujson.Arr.from(notes.completed),
      "remaining" -> ujson.Arr.from(notes.remaining),
      "todos" -> ujson.Arr.from(todos.take(50).map(todo => s"${todo.status}: ${todo.text.take(200)}")),
    ))
  /** Every model call since the last `clear()`, grouped by purpose
    * ([[Agent.Turns]], [[Agent.Chat]], ...) in order of first use. Access is
    * synchronized because next-input prediction records usage on another thread. */
  private val usageBy = scala.collection.mutable.LinkedHashMap[String, TokenUsage]()
  /** Tool calls actually run since the last `clear()`. */
  var toolCalls: Int = 0
  private val context = ContextManager()
  private val request = ModelRequest()
  private val queuedInput = java.util.concurrent.ConcurrentLinkedQueue[String]()

  def submit(input: String): Unit =
    if input.trim.nonEmpty then queuedInput.add(input.trim)

  def queuedInputCount: Int = queuedInput.size()

  private def acceptQueuedInput(): Unit =
    var input = queuedInput.poll()
    while input != null do
      conversation.steer(input)
      ui.inputAccepted(input)
      input = queuedInput.poll()

  /** Everything spent on the model(s) since the last `clear()`. */
  def usage: TokenUsage = synchronized(usageBy.values.foldLeft(TokenUsage())(_ + _))
  /** The same, by purpose, in the order the purposes first occurred. */
  def usageByPurpose: List[(String, TokenUsage)] = synchronized(usageBy.toList)
  /** Add what one model call cost. */
  def recordUsage(purpose: String, u: TokenUsage): Unit =
    synchronized { usageBy.update(purpose, usageBy.getOrElse(purpose, TokenUsage()) + u) }

  /** The native Scala tool; other operations are library calls. */
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

  /** Queue a notice that definitions from the previous REPL no longer exist. */
  def noteSandboxRestarted(reason: String): Unit =
    conversation.queueNote(AgentMessages.sandboxRestarted(reason))

  def notePermissionRevoked(grant: String): Unit =
    conversation.queueNote(
      s"[permissions] The user revoked the session grant for $grant. Do not assume it remains available."
    )

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
    queuedInput.clear()

  /** Run one user turn; returns when the model gives its final answer or the user interrupts. */
  def turn(session: => ReplSession, input: String, cancelled: () => Boolean): TurnOutcome =
    runTurn(ScalaToolRunner(session, policy, ui, config.maxToolOutputChars), input, cancelled)

  /** Core entry point, with concrete tool execution supplied by the host adapter. */
  private[atc] def runTurn(runner: ToolRunner, input: String, cancelled: () => Boolean): TurnOutcome =
    tools = runner.tools
    conversation.beginTurn(input)
    context.beginTurn()
    Turn(runner, cancelled).run()

  private enum Outcome:
    /** Something was appended to the history; ask the model again. */
    case Continue
    case Done(result: TurnOutcome)

  /** One turn: the round loop plus the counters the bounds are checked against. */
  private final class Turn(runner: ToolRunner, cancelled: () => Boolean):
    import Outcome.*
    private var used = 0 // tool calls run this turn
    private var budget = config.maxToolCalls // grows by `maxToolCalls` each time the user says "continue"
    private var resumes = 0
    private var budgetRejections = 0

    def run(): TurnOutcome =
      @scala.annotation.tailrec
      def loop(): TurnOutcome = round() match
        case Continue => loop()
        case Done(result) => result
      try loop()
      catch
        case error: CancelledException =>
          conversation.repairAfter(error)
          interrupted()
          TurnOutcome.Interrupted
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
        acceptQueuedInput()
        val prepared = context.prepare(fixedTokens, history, model, retainedContext)
        conversation.useHistory(prepared.history)
        prepared.warnings.foreach(ui.warn)
        ui.status(AgentMessages.thinkingStatus)
        completeRound() match
          case None if !cancelled() && !queuedInput.isEmpty =>
            conversation.interrupt()
            Continue
          case None => interrupted()
          case Some(raw) =>
            recordUsage(Agent.Turns, raw.usage)
            context.calibrate(raw, prepared.estimatedInput)

            val accepted = CompletionPolicy(raw)
            conversation.append(accepted.message)
            accepted.warnings.foreach(ui.warn)
            accepted.next match
              case CompletionPolicy.Next.RunTools(calls) => runTools(calls)
              case CompletionPolicy.Next.Blocked =>
                if !queuedInput.isEmpty then Continue else Done(TurnOutcome.Blocked)
              case CompletionPolicy.Next.Finish =>
                if cancelled() then interrupted()
                else if !queuedInput.isEmpty then Continue
                else if budgetRejections > 0 then Done(TurnOutcome.LimitReached)
                else if raw.text.trim.isEmpty then Done(TurnOutcome.Failed)
                else Done(TurnOutcome.Finished)
              case CompletionPolicy.Next.Resume(needsContinuation) =>
                if cancelled() then interrupted()
                else if resumes < Agent.MaxResumes then
                  if needsContinuation then conversation.append(Msg.Continuation(AgentMessages.truncationContinuation))
                  resume()
                else
                  ui.warn(AgentMessages.resumeExhaustedWarning(model.alias, Agent.MaxResumes))
                  Done(TurnOutcome.LimitReached)

    private def completeRound(): Option[Completion] =
      val active = java.util.concurrent.atomic.AtomicBoolean(true)
      val current = model
      val prompt = systemPrompt
      val messages = history
      val guarded = StreamSink(
        text => if active.get() then sink.text(text),
        text => if active.get() then sink.note(text),
        text => if active.get() then sink.thinking(text),
      )
      val stop = () => cancelled() || !queuedInput.isEmpty
      try Some(request.run(stop)(current.complete(prompt, messages, tools, guarded, stop)))
      catch
        case _: CancelledException => None
      finally
        active.set(false)
        ui.assistantEnd()

    /** Run the requested tools in order, honouring cancellation and the per-turn budget. */
    private def runTools(calls: List[ToolCall]): Outcome =
      var overBudget = false
      var needsReplan = false
      val results = calls.map { call =>
        if cancelled() then ToolResult(call.id, AgentMessages.cancelledBeforeExecution, isError = true)
        else if needsReplan || !queuedInput.isEmpty then
          ToolResult(call.id, AgentMessages.skippedAfterFeedback, isError = true)
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
          val result = runner.run(call)
          needsReplan = result.needsReplan
          result
      }
      conversation.append(Msg.ToolResults(results))
      if cancelled() then interrupted()
      else if !overBudget then Continue
      else
        // Allow a response to the budget error before enforcing the retry limit.
        budgetRejections += 1
        if budgetRejections < Agent.MaxBudgetRejections then Continue
        else
          ui.warn(AgentMessages.toolBudgetLoopWarning)
          Done(TurnOutcome.LimitReached)

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
      Done(TurnOutcome.Interrupted)

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
