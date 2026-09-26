package atc.ui

import atc.agent.TurnOutcome

import org.jline.terminal.TerminalBuilder

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.concurrent.ConcurrentLinkedQueue

/** When an alert reaches the user: only after a wait in which they neither typed
  * nor had the terminal focused, longer for a finished turn than for a question. */
class AlertsSuite extends munit.FunSuite:
  private val QuestionDelay = 150L
  private val TurnDelay = 600L

  /** Alerts on a terminal of `termType` (`xterm*` claims focus reports), ringing the bell into `sent`. */
  private def alerts(termType: String = "xterm-256color"): (Alerts, ConcurrentLinkedQueue[String]) =
    val terminal = TerminalBuilder.builder().nn.system(false).nn.`type`(termType).nn
      .streams(ByteArrayInputStream(Array.emptyByteArray), ByteArrayOutputStream()).nn.build().nn
    val sent = ConcurrentLinkedQueue[String]()
    val a = Alerts(terminal, plain = false, text => sent.add(text), QuestionDelay, TurnDelay)
    a.notifier = Notifier.Bell
    (a, sent)

  /** Whether anything was sent `millis` from now. */
  private def sentWithin(sent: ConcurrentLinkedQueue[String], millis: Long): Boolean =
    Thread.sleep(millis)
    !sent.isEmpty

  private def afterQuestionWait(sent: ConcurrentLinkedQueue[String]) = sentWithin(sent, QuestionDelay * 4)

  test("nothing is sent while the terminal is known to be focused, even after it loses focus"):
    val (a, sent) = alerts()
    a.focusChanged(true)
    a.alert("question")
    a.focusChanged(false)
    assert(sent.isEmpty, "leaving sends nothing early")
    assert(!afterQuestionWait(sent))

  test("an alert raised while unfocused waits, then is sent"):
    val (a, sent) = alerts()
    a.focusChanged(false)
    a.alert("question")
    assert(sent.isEmpty, "not at once")
    assert(afterQuestionWait(sent))

  test("coming back to the terminal or typing during the wait drops the alert"):
    val (back, backSent) = alerts()
    back.focusChanged(false)
    back.alert("question")
    back.focusChanged(true)
    assert(!afterQuestionWait(backSent))
    val (typed, typedSent) = alerts()
    typed.focusChanged(false)
    typed.alert("question")
    typed.touch()
    assert(!afterQuestionWait(typedSent))

  test("until the terminal reports focus only typing counts, whatever its type claims"):
    for termType <- List("xterm-256color", "vt100") do
      val (a, sent) = alerts(termType)
      a.alert("question")
      assert(afterQuestionWait(sent), termType)
      sent.clear()
      a.alert("again")
      a.touch()
      assert(!afterQuestionWait(sent), termType)

  test("a finished turn waits longer than a question"):
    val (a, sent) = alerts()
    a.focusChanged(false)
    a.turnEnded(Tui.TurnStats(1.0, 0, 0, 0, None, TurnOutcome.Finished))
    assert(!sentWithin(sent, QuestionDelay * 2), "not after the question delay")
    assert(sentWithin(sent, TurnDelay * 2))
