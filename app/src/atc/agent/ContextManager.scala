package atc.agent

import atc.llm.{ChatModel, Completion, Msg, NativeTurn, ToolCall}

import scala.collection.mutable.ListBuffer

/** Context-window accounting for an agent conversation.
  *
  * The manager owns the state that spans model rounds: calibration against the
  * provider's token count and the cumulative number of messages dropped. Call
  * [[beginTurn]] once for each user turn so an unavoidable overflow is warned
  * at most once during that turn.
  */
final class ContextManager:
  import ContextManager.*

  private var tokenCalibration = 1.0
  private var contextDropped = 0
  private var contextOverflowWarned = false

  /** Start a user turn. Context overflow is advisory and may be reported once
    * again on a later turn if it remains unresolved. */
  def beginTurn(): Unit = contextOverflowWarned = false

  /** Clear conversation-derived accounting. */
  def reset(): Unit =
    tokenCalibration = 1.0
    contextDropped = 0
    contextOverflowWarned = false

  /** A new model may use a different tokenizer. Conversation/drop accounting
    * remains valid, but calibration learned from the old model does not. */
  def modelChanged(): Unit = tokenCalibration = 1.0

  /** Current provider-token / estimated-token ratio. Exposed for diagnostics
    * and focused unit tests; callers normally use [[contextUsage]]. */
  def calibration: Double = tokenCalibration

  /** Messages dropped over the lifetime of the current conversation. */
  def droppedMessages: Int = contextDropped

  /** Correct the character-based estimate with the prompt count reported by a
    * completed provider request. Small counts are too noisy to be useful. */
  def calibrate(completion: Completion, estimatedInput: Long): Unit =
    calibrate(completion.usage.input, estimatedInput)

  def calibrate(inputTokens: Long, estimatedInput: Long): Unit =
    if inputTokens >= CalibrationMinTokens && estimatedInput > 0 then
      tokenCalibration = (inputTokens.toDouble / estimatedInput).max(0.25).min(8.0)

  /** Estimate what the next request would use without changing history. */
  def contextUsage(fixedTokens: Long, history: List[Msg], model: ChatModel): ContextUsage =
    contextUsage(fixedTokens, history, ModelContext.from(model))

  def contextUsage(fixedTokens: Long, history: List[Msg], model: ModelContext): ContextUsage =
    val raw = fixedTokens + history.map(estimateFor(_, model)).sum
    ContextUsage((raw * tokenCalibration).round, model.contextWindow)

  /** Fit history to the model's input allowance and return all effects as
    * values for the loop to apply.
    *
    * `estimatedInput` is the uncalibrated estimate of the prepared request; it
    * is the denominator to pass to [[calibrate]] after completion.
    * `calibratedInput` is the estimate shown to users and used for overflow
    * checks. `dropped` counts this preparation, while `totalDropped` is the
    * cumulative count written into the context notice.
    */
  def prepare(fixedTokens: Long, history: List[Msg], model: ChatModel): Preparation =
    prepare(fixedTokens, history, ModelContext.from(model))

  def prepare(fixedTokens: Long, history: List[Msg], model: ModelContext): Preparation =
    var preparedHistory = history
    var dropped = 0
    val warnings = ListBuffer[String]()

    model.contextWindow.foreach { window =>
      // Leave both estimation slack and, when configured, the full output
      // allowance. A maxTokens larger than the window intentionally leaves no
      // room and triggers the actionable warning below.
      val reserve = (window.toLong / 8).max(model.maxOutputTokens.map(_.toLong).getOrElse(0L))
      val availableInput = window.toLong - reserve
      val room = (availableInput / tokenCalibration).toLong - fixedTokens
      val fitted = ContextManager.fitToContext(preparedHistory, room, estimateFor(_, model))
      preparedHistory = fitted._1
      dropped = fitted._2

      if dropped > 0 then
        contextDropped += dropped
        preparedHistory = preparedHistory match
          case Msg.User(text) :: rest =>
            Msg.User(s"${AgentMessages.contextCutNotice(contextDropped)}\n\n$text") :: rest
          case other => other
        warnings += AgentMessages.contextDroppedWarning(model.alias, window, dropped)

      val estimated = fixedTokens + preparedHistory.map(estimateFor(_, model)).sum
      val calibrated = (estimated * tokenCalibration).round
      if calibrated > availableInput && !contextOverflowWarned then
        contextOverflowWarned = true
        val fixedInput = (fixedTokens * tokenCalibration).round
        val cause =
          if fixedInput > availableInput then AgentMessages.ContextOverflowCause.FixedPrompt
          else AgentMessages.ContextOverflowCause.RetainedExchange
        warnings += AgentMessages.contextOverflowWarning(
          model.alias,
          window,
          cause,
          calibrated,
          availableInput,
          reserve,
          model.maxOutputTokens,
        )
    }

    val estimatedInput = fixedTokens + preparedHistory.map(estimateFor(_, model)).sum
    Preparation(
      history = preparedHistory,
      estimatedInput = estimatedInput,
      calibratedInput = (estimatedInput * tokenCalibration).round,
      dropped = dropped,
      totalDropped = contextDropped,
      warnings = warnings.toList,
    )

object ContextManager:
  /** Below this many prompt tokens a completion is not used to calibrate the estimator. */
  val CalibrationMinTokens = 200L

  final case class ModelContext(
    alias: String,
    providerKey: String,
    ref: String,
    contextWindow: Option[Int] = None,
    maxOutputTokens: Option[Int] = None,
  )

  object ModelContext:
    def from(model: ChatModel): ModelContext =
      ModelContext(model.alias, model.providerKey, model.ref, model.contextWindow, model.maxOutputTokens)

  final case class ContextUsage(tokens: Long, window: Option[Int])

  final case class Preparation(
    history: List[Msg],
    estimatedInput: Long,
    calibratedInput: Long,
    dropped: Int,
    totalDropped: Int,
    warnings: List[String],
  )

  /** A rough token count: about four characters per token, corrected at run
    * time by [[ContextManager]]'s calibration against provider counts. */
  def estimateTokens(text: String): Long = (text.length + 3) / 4

  /** Estimate a neutral message, including per-message framing. */
  def estimateTokens(msg: Msg): Long = msg match
    case Msg.User(text) => estimateTokens(text) + 4
    case Msg.Continuation(text) => estimateTokens(text) + 4
    case Msg.Assistant(text, calls, native) => estimateAssistantTokens(text, calls, native)
    case Msg.ToolResults(results) => results.map(result => estimateTokens(result.output) + 12).sum + 4

  /** Model-aware estimate: native replay data from another model is not sent. */
  def estimateTokens(msg: Msg, providerKey: String, modelRef: String): Long = msg match
    case Msg.User(text) => estimateTokens(text) + 4
    case Msg.Continuation(text) => estimateTokens(text) + 4
    case Msg.Assistant(text, calls, native) =>
      estimateAssistantTokens(text, calls, native.filter(_.isFor(providerKey, modelRef)))
    case Msg.ToolResults(results) => results.map(result => estimateTokens(result.output) + 12).sum + 4

  private def estimateFor(msg: Msg, model: ModelContext): Long =
    estimateTokens(msg, model.providerKey, model.ref)

  private def estimateAssistantTokens(
    text: String,
    calls: List[ToolCall],
    native: Option[NativeTurn],
  ): Long =
    val neutral = estimateTokens(text) + calls.map(call => estimateTokens(call.arguments) + 12).sum
    // A request contains either the exact native payload or the neutral
    // text/calls, never both. The larger estimate is conservative without
    // double-counting every assistant turn.
    neutral.max(native.map(turn => (turn.payloadChars + 3L) / 4).getOrElse(0L)) + 4

  /** Cut history to an estimated token budget by dropping whole exchanges
    * from the front. A cut starts at a real user message, and the last real
    * user message plus everything following it is always retained. */
  def fitToContext(
    history: List[Msg],
    budget: Long,
    estimate: Msg => Long = ContextManager.estimateTokens,
  ): (List[Msg], Int) =
    val lastUser = history.lastIndexWhere(_.isInstanceOf[Msg.User])
    var start = 0
    var total = history.map(estimate).sum
    while total > budget && start < lastUser do
      val next = history.indexWhere(_.isInstanceOf[Msg.User], start + 1)
      val cut = if next < 0 || next > lastUser then lastUser else next
      total -= history.slice(start, cut).map(estimate).sum
      start = cut
    (history.drop(start), start)
