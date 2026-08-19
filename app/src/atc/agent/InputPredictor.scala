package atc.agent

import atc.Debug
import atc.llm.{ChatModel, Msg, TokenUsage}

import java.util.concurrent.atomic.AtomicLong

/** Guesses what the user will type next, from the conversation so far, so the
  * terminal can offer it as ghost text at the prompt (Tab / → accepts).
  *
  * The guess is made on a background thread by the *agent* model (never the
  * classified one: the history it sees is the same the agent model already
  * saw). A guess is shown only if nothing invalidated it while it was being
  * made: every [[start]] and [[invalidate]] bumps a generation, and a slow
  * answer for an older generation is dropped. Failures are silent: no guess
  * is just no ghost text. */
final class InputPredictor(
  model: () => ChatModel,
  history: () => List[Msg],
  show: Option[String] => Unit,
  /** Told what every guess cost, so `/cost` includes it. */
  spent: TokenUsage => Unit = _ => (),
):
  private val generation = AtomicLong(0)

  /** Forget the current guess (and any that is still being made). */
  def invalidate(): Unit =
    generation.incrementAndGet()
    show(None)

  /** Start guessing for the conversation as it stands now. */
  def start(): Unit =
    val gen = generation.incrementAndGet()
    show(None)
    val (m, h) = (model(), history())
    val worker = Thread(
      () =>
        val guess =
          try InputPredictor.predict(m, h, spent)
          catch
            case e: Exception =>
              Debug.log(s"input prediction failed: $e"); Debug.trace(e); None
        if generation.get == gen then show(guess)
      ,
      "atc-predict"
    )
    worker.setDaemon(true)
    worker.start()

object InputPredictor:
  /** Exchanges (user message + the assistant's final answer) sent to the model. */
  val Exchanges = 4
  /** Characters kept per message; long tool-heavy replies are cut in the middle. */
  val MessageChars = 600
  /** The longest guess offered. */
  val MaxChars = 200

  val System: String =
    "You predict what a developer will type next to a terminal coding agent, given the recent conversation. " +
      "Reply with the single most likely next message only: one line, imperative, concrete, at most 100 characters, " +
      "no quotes, no explanation. If no useful next message is likely (the work is done, or the user is being asked " +
      "a question you cannot answer for them), reply with an empty line."

  /** One synchronous guess. `None` when the model has nothing to offer. */
  def predict(model: ChatModel, history: List[Msg], spent: TokenUsage => Unit = _ => ()): Option[String] =
    val transcript = render(history)
    if transcript.isEmpty then None
    else
      val reply = model.simple(Some(System), transcript, thinking = false)
      spent(reply.usage)
      clean(reply.text)

  /** The last few user/assistant texts as `User:` / `Agent:` lines. */
  def render(history: List[Msg]): String =
    val texts = history.collect {
      case Msg.User(t) => "User: " + cut(t)
      case Msg.Assistant(t, _, _) if t.trim.nonEmpty => "Agent: " + cut(t)
    }
    // Roughly the last N exchanges: an exchange is a user line plus the answers to it.
    val keep = texts.reverse.take(Exchanges * 2).reverse
    if keep.isEmpty then "" else keep.mkString("\n\n") + "\n\nUser:"

  private def cut(text: String): String =
    val t = text.trim
    if t.length <= MessageChars then t
    else t.take(MessageChars / 2) + " […] " + t.takeRight(MessageChars / 2)

  /** The first non-empty line, unquoted and capped; `None` for an empty answer. */
  def clean(answer: String): Option[String] =
    answer.linesIterator.map(_.trim).find(_.nonEmpty).map { line =>
      val unprefixed = line.stripPrefix("User:").trim
      val unquoted =
        if unprefixed.length >= 2 && "\"'`".contains(unprefixed.head) && unprefixed.last == unprefixed.head
        then unprefixed.drop(1).dropRight(1).trim
        else unprefixed
      unquoted.take(MaxChars)
    }.filter(_.nonEmpty)
