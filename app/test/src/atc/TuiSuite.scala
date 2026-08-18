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

  // ── row arithmetic behind the output fold ───────────────────────

  test("place: a short line is one row, whatever the gutter"):
    assertEquals(Tui.place(0, "hello\n", 80, 4), (rows = 1, column = 0))
    assertEquals(Tui.place(0, "\n", 80, 4), (rows = 1, column = 0))

  test("place: a long line costs the rows it wraps over"):
    // 4 columns of gutter + 4000 characters = 4004 / 80, rounded up
    assertEquals(Tui.place(0, "x" * 4000 + "\n", 80, 4).rows, 51)
    assertEquals(Tui.place(0, "y" * 300 + "\n", 80, 4).rows, 4)

  test("place: text without a newline keeps the column, so later chunks add rows"):
    val first = Tui.place(0, "chunk ", 80, 4)
    assertEquals(first, (rows = 1, column = 10))
    // continuing the same row adds nothing until it wraps
    assertEquals(Tui.place(first.column, "more ", 80, 4), (rows = 0, column = 15))
    assertEquals(Tui.place(70, "z" * 90, 80, 4), (rows = 1, column = 80))
    assertEquals(Tui.place(76, "abcd", 80, 4), (rows = 0, column = 80)) // exactly fills the row

  test("uniqueIds keeps labels and disambiguates duplicates"):
    assertEquals(Tui.uniqueIds(List("a", "b", "a", "a")), List("a", "b", "a (1)", "a (2)"))
    assertEquals(Tui.uniqueIds(Nil), Nil)
