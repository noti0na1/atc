package atc

import atc.ui.Tui

/** The terminal front-end's pure helpers (the rest needs a real terminal). */
class TuiSuite extends munit.FunSuite:

  test("withoutPrinted removes the live-shown prints and keeps diagnostics and echoes"):
    val printed = "  leading spaces\nstaged:\nA  file\n"
    val body = "-- Warning: something\n  leading spaces\nstaged:\nA  file\nval r: Int = 1"
    assertEquals(Tui.withoutPrinted(body, printed), "-- Warning: something\nval r: Int = 1")

  test("withoutPrinted handles prints at the start, at the end, and nothing else"):
    assertEquals(Tui.withoutPrinted("hello\nval x: Int = 1", "hello\n"), "val x: Int = 1")
    assertEquals(Tui.withoutPrinted("1 warning found\nhello", "hello\n"), "1 warning found")
    assertEquals(Tui.withoutPrinted("hello", "hello\n"), "")
    assertEquals(Tui.withoutPrinted("only echo", ""), "only echo")

  test("withoutPrinted leaves the body alone when the prints are not found verbatim"):
    val body = "hel...[truncated]"
    assertEquals(Tui.withoutPrinted(body, "hello world\n"), body)

  test("uniqueIds keeps labels and disambiguates duplicates"):
    assertEquals(Tui.uniqueIds(List("a", "b", "a", "a")), List("a", "b", "a (1)", "a (2)"))
    assertEquals(Tui.uniqueIds(Nil), Nil)
