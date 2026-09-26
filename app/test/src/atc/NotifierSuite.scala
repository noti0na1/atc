package atc

import atc.ui.Notifier
import atc.ui.Notifier.Osc

class NotifierSuite extends munit.FunSuite:
  private def env(vars: (String, String)*): String => Option[String] = vars.toMap.get

  test("terminals with their own notifications are recognised"):
    assertEquals(Notifier.terminalOsc(env("TERM" -> "xterm-kitty")), Some(Osc.Kitty))
    assertEquals(Notifier.terminalOsc(env("TERM_PROGRAM" -> "iTerm.app")), Some(Osc.Nine))
    assertEquals(Notifier.terminalOsc(env("TERM_PROGRAM" -> "WezTerm")), Some(Osc.Nine))
    assertEquals(Notifier.terminalOsc(env("TERM" -> "foot")), Some(Osc.Notify))
    assertEquals(Notifier.terminalOsc(env("TERM_PROGRAM" -> "vscode", "TERM" -> "xterm-256color")), None)

  test("auto prefers the terminal, then the desktop, then the bell"):
    assertEquals(Notifier.fromSetting("auto", env("TERM_PROGRAM" -> "ghostty")), Notifier.Terminal(Osc.Nine))
    assertEquals(
      Notifier.fromSetting("auto", env("SSH_CONNECTION" -> "1.2.3.4 22 5.6.7.8 22", "DISPLAY" -> ":0")),
      Notifier.Bell
    )
    assertEquals(Notifier.fromSetting("terminal", env("TERM_PROGRAM" -> "vscode")), Notifier.Bell)
    assertEquals(Notifier.fromSetting("system", env()), Notifier.System)
    assertEquals(Notifier.fromSetting("OFF", env()), Notifier.Off)

  test("terminal notifications cannot be broken out of by the text"):
    var emitted = List.empty[String]
    Notifier.Terminal(Osc.Notify).send("atc", "rm -rf x; \u0007\u001b]0;evil\u0007\nnext", s => emitted :+= s)
    assertEquals(emitted, List("\u001b]777;notify;atc;rm -rf x, ]0,evil next\u0007"))
    emitted = Nil
    Notifier.Bell.send("atc", "done", s => emitted :+= s)
    assertEquals(emitted, List("\u0007"))

  test("long text is cut to one short line"):
    val cleaned = Notifier.clean("a" * 500)
    assertEquals(cleaned.length, 200)
    assert(cleaned.endsWith("…"))
    assertEquals(Notifier.clean("command 1: ls\n  reason:    list files"), "command 1: ls reason: list files")

  test("system commands pass the text as data"):
    val command = Notifier.systemCommand("atc", "it's \"done\" $(x)")
    assert(command.nonEmpty)
    if !atc.platform.Platform.isWindows then assertEquals(command.takeRight(2), List("atc", "it's \"done\" $(x)"))
    // notify-send would read a body starting with `-` as an option.
    if !atc.platform.Platform.isWindows && !atc.platform.Platform.isMac then
      assertEquals(Notifier.systemCommand("atc", "--icon=/x").takeRight(3), List("--", "atc", "--icon=/x"))

  test("markdown replies become plain prose"):
    val reply =
      "## Done\n\nI updated **`Foo.scala`**:\n\n- added `bar`\n1. fixed tests\n\n```scala\nval x = 1\n```\n> all green"
    assertEquals(Notifier.plainText(reply), "Done I updated Foo.scala: added bar fixed tests all green")

  test("turn alerts lead with the reply, or say why the turn stopped"):
    import atc.agent.TurnOutcome
    import atc.ui.{Alerts, Tui}
    def stats(outcome: TurnOutcome) = Tui.TurnStats(42, 3, 100, 1000, None, outcome)
    assertEquals(Alerts.turnText(stats(TurnOutcome.Finished), "All **tests** pass.", ""), "All tests pass.")
    assertEquals(Alerts.turnText(stats(TurnOutcome.Finished), "", ""), "Finished in 42 s")
    assertEquals(Alerts.turnText(stats(TurnOutcome.Failed), "partial", "HTTP 429"), "Failed: HTTP 429")
    assertEquals(Alerts.turnText(stats(TurnOutcome.Interrupted), "partial", ""), "Interrupted after 42 s")
    assertEquals(Alerts.turnText(stats(TurnOutcome.LimitReached), "Halfway", ""), "Limit reached: Halfway")
