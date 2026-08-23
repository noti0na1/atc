package atc

import atc.ui.{Ansi, Tui}

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

  test("count: short forms, without a pointless .0"):
    assertEquals(Tui.count(999), "999")
    assertEquals(Tui.count(1234), "1.2k")
    assertEquals(Tui.count(200_000), "200k")
    assertEquals(Tui.count(1_000_000), "1M")
    assertEquals(Tui.count(1_234_567), "1.2M")

  test("contextUsage: against the window when known, an estimate otherwise"):
    assertEquals(Tui.contextUsage(45_200, Some(200_000)), "context 45.2k/200k (23%)")
    assertEquals(Tui.contextUsage(199_000, Some(200_000)), "context 199k/200k (100%)")
    assertEquals(Tui.contextUsage(45_200, None), "context ~45.2k")

  test("uniqueIds keeps labels and disambiguates duplicates"):
    assertEquals(Tui.uniqueIds(List("a", "b", "a", "a")), List("a", "b", "a (1)", "a (2)"))
    assertEquals(Tui.uniqueIds(Nil), Nil)

  // ── sanitization, widths, durations, the tail buffer ─────────────

  test("sanitize strips terminal control, keeps text, newlines and tabs"):
    val esc = 27.toChar.toString
    assertEquals(Ansi.sanitize("plain text\nmore"), "plain text\nmore")
    assertEquals(Ansi.sanitize("a" + 13.toChar + "b"), "ab") // a bare CR cannot reset the column
    assertEquals(Ansi.sanitize("a\tb\nc"), "a\tb\nc")
    assertEquals(Ansi.sanitize("unicode: héllo 中文"), "unicode: héllo 中文")
    // an injected clear-screen / OSC-52 clipboard write loses its ESC byte and goes inert
    assertEquals(Ansi.sanitize("before" + esc + "[2Jafter"), "before[2Jafter")
    assertEquals(Ansi.sanitize("x" + esc + "]52;c;eGk=" + 7.toChar + "y"), "x]52;c;eGk=y")
    assertEquals(Ansi.sanitize("c1: " + 0x85.toChar), "c1: ")
    assertEquals(Ansi.sanitize("del: " + 0x7f.toChar), "del: ")
    assertEquals(Ansi.sanitize(""), "")

  test("duration never prints 60 seconds"):
    assertEquals(Tui.duration(119.6), "2 min 0 s") // was "1 min 60 s"
    assertEquals(Tui.duration(65.4), "1 min 5 s")
    assertEquals(Tui.duration(60.0), "1 min 0 s")

  test("place counts wide (CJK) characters as two columns"):
    assertEquals(Tui.displayWidth("abc"), 3)
    assertEquals(Tui.displayWidth("中文"), 4)
    assertEquals(Tui.place(0, "中" * 40 + "\n", 80, 4).rows, 2) // 4 + 80 columns: wraps

  test("TailBuffer: the tail is the last n lines; a trailing newline is not a line"):
    val b = Tui.TailBuffer(1000)
    b.append("a\nb\nc")
    assertEquals(b.tail(2), List("b", "c")) // the unfinished last line counts
    assertEquals(b.lineCount, 3L)
    b.append("d\ne\n")
    assertEquals(b.tail(2), List("cd", "e"))
    assertEquals(b.lineCount, 4L)
    b.append("f\n")
    assertEquals(b.tail(10), List("a", "b", "cd", "e", "f"))

  test("TailBuffer: past the cap the front goes, the counts stay exact"):
    val b = Tui.TailBuffer(10)
    b.append("01234\n67890\n")
    assertEquals(b.text, "67890\n")
    assertEquals(b.lineCount, 2L) // the dropped line still counts
    assertEquals(b.tail(5), List("67890"))

  // ── multi-line input (Continuation) ───────────────────────────────

  import atc.ui.Continuation.{pending, unclosed}

  test("unclosed: brackets nest, innermost first; balanced code leaves nothing open"):
    assertEquals(unclosed("foo(a, List(1"), List(")", ")"))
    assertEquals(unclosed("def f = { x.map { y => (y, "), List(")", "}", "}"))
    assertEquals(unclosed("foo(List(1, 2)).map(_ + 1)"), Nil)
    assertEquals(unclosed("1 + 1"), Nil)
    assertEquals(unclosed("Map(1 -> 2)))"), Nil) // a stray closer is not waited on

  test("unclosed: strings, char literals and comments hide brackets"):
    assertEquals(unclosed("println(\"a (b\")"), Nil)
    assertEquals(unclosed("\"(\\\"\" + x"), Nil) // an escaped quote does not close the string
    assertEquals(unclosed("println(\"abc"), List("\"", ")"))
    assertEquals(unclosed("println(\"abc\nfoo)"), Nil) // an unterminated string ends at its line
    assertEquals(unclosed("\"\"\"a ( \" b"), List("\"\"\""))
    assertEquals(unclosed("\"\"\"a\"\"\"\" + (1"), List(")")) // `""""` closes it
    assertEquals(unclosed("List('(', '\\'', 'x')"), Nil)
    assertEquals(unclosed("x // a comment with (\nfoo"), Nil)
    assertEquals(unclosed("/* a ( comment"), List("*/"))
    assertEquals(unclosed("/* nested /* ( */ still open"), List("*/"))
    assertEquals(unclosed("/* ( */ ok(1)"), Nil)

  test("pending: a /run continues while brackets are open, indented by their depth; other input does not"):
    assertEquals(pending("/run foo(List(1,", block = false), Some(2))
    assertEquals(pending("/RUN x.map { y =>", block = false), Some(1))
    assertEquals(pending("/scala \"\"\"multi", block = false), Some(0)) // a string: continue, no indent
    assertEquals(pending("/run 1 + 1", block = false), None)
    assertEquals(pending("please fix foo(", block = false), None) // a request, not code
    assertEquals(pending("/mode local", block = false), None)

  test("pending: an empty last line always submits"):
    assertEquals(pending("/run foo(\n  ", block = false), None) // Enter on the (indented) empty line: submit anyway
    assertEquals(pending("/run foo(\nbar", block = false), Some(1))
    assertEquals(pending("first line \\", block = false), None) // a backslash is a key sequence, not a parse rule

  test("pending: block mode continues until an empty line"):
    assertEquals(pending("val x = 1", block = true), Some(0))
    assertEquals(pending("val x = 1\nx + 1", block = true), Some(0))
    assertEquals(pending("val x = 1\n", block = true), None)
    assertEquals(pending("", block = true), None)
