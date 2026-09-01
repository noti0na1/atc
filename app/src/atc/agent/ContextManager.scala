package atc.agent

import atc.llm.{ChatModel, Completion, Msg, NativeTurn, ToolCall}

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
    // Leave both estimation slack and, when configured, the full output
    // allowance. A maxTokens larger than the window intentionally leaves no
    // room and triggers the actionable warning below.
    val allowance = model.contextWindow.map { window =>
      val reserve = (window.toLong / 8).max(model.maxOutputTokens.map(_.toLong).getOrElse(0L))
      Allowance(window, reserve, window.toLong - reserve)
    }
    val (fitted, dropped) = allowance match
      case Some(a) => fitToContext(history, (a.input / tokenCalibration).toLong - fixedTokens, estimateFor(_, model))
      case None => (history, 0)
    contextDropped += dropped
    val preparedHistory = fitted match
      case Msg.User(text) :: rest if dropped > 0 =>
        Msg.User(s"${AgentMessages.contextCutNotice(contextDropped)}\n\n$text") :: rest
      case other => other

    // Estimate the final history once; the overflow check below reuses the
    // same figure instead of scanning the messages a second time.
    val estimatedInput = fixedTokens + preparedHistory.map(estimateFor(_, model)).sum
    val calibratedInput = (estimatedInput * tokenCalibration).round

    val droppedWarning =
      allowance.filter(_ => dropped > 0).map(a => AgentMessages.contextDroppedWarning(model.alias, a.window, dropped))
    val overflowWarning =
      allowance.filter(a => calibratedInput > a.input && !contextOverflowWarned).map { a =>
        contextOverflowWarned = true
        val cause =
          if (fixedTokens * tokenCalibration).round > a.input then AgentMessages.ContextOverflowCause.FixedPrompt
          else AgentMessages.ContextOverflowCause.RetainedExchange
        AgentMessages.contextOverflowWarning(
          model.alias,
          a.window,
          cause,
          calibratedInput,
          a.input,
          a.reserve,
          model.maxOutputTokens,
        )
      }

    Preparation(
      history = preparedHistory,
      estimatedInput = estimatedInput,
      calibratedInput = calibratedInput,
      dropped = dropped,
      totalDropped = contextDropped,
      warnings = droppedWarning.toList ++ overflowWarning,
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

  /** A model's window, the part reserved for the answer, and what is left for the request. */
  private final case class Allowance(window: Int, reserve: Long, input: Long)

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
  def estimateTokens(msg: Msg): Long = estimateTokens(msg, replayed = _ => true)

  /** Model-aware estimate: native replay data from another model is not sent. */
  def estimateTokens(msg: Msg, providerKey: String, modelRef: String): Long =
    estimateTokens(msg, replayed = _.isFor(providerKey, modelRef))

  private def estimateFor(msg: Msg, model: ModelContext): Long =
    estimateTokens(msg, model.providerKey, model.ref)

  private def estimateTokens(msg: Msg, replayed: NativeTurn => Boolean): Long = msg match
    case Msg.User(text) => estimateTokens(text) + 4
    case Msg.Continuation(text) => estimateTokens(text) + 4
    case Msg.Assistant(text, calls, native) => estimateAssistantTokens(text, calls, native.filter(replayed))
    case Msg.ToolResults(results) => results.map(result => estimateTokens(result.output) + 12).sum + 4

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
    * user message plus everything following it is always retained. Linear in
    * the history length: per-message estimates and their prefix sums are
    * computed once, then the earliest viable user boundary is chosen. */
  def fitToContext(
    history: List[Msg],
    budget: Long,
    estimate: Msg => Long = ContextManager.estimateTokens,
  ): (List[Msg], Int) =
    val sizes = history.map(estimate)
    val total = sizes.sum
    val users = history.zipWithIndex.collect { case (Msg.User(_), i) => i }
    if total <= budget || users.lastOption.forall(_ == 0) then (history, 0)
    else
      // The earliest user boundary that sheds enough (`before(i)` is the size of
      // everything in front of message `i`); when none does, the last one.
      val before = sizes.scanLeft(0L)(_ + _).toVector
      val start = users.find(before(_) >= total - budget).getOrElse(users.last)
      (history.drop(start), start)
