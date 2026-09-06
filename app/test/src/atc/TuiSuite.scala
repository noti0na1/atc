package atc

import atc.ui.{Ansi, Glyphs, Tui}
import atc.perms.Decision

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** The terminal front-end's pure helpers (the rest needs a real terminal). */
class TuiSuite extends munit.FunSuite:

  test("live row updates preserve the footer while growing, shrinking and clearing the preview"):
    val cases = List(
      List("header", "old") -> List("header", "new"),
      List("first") -> List("first", "second"),
      List("first", "second", "third") -> List("first"),
      List("first", "second") -> Nil,
      Nil -> List("first"),
      List("same") -> List("same"),
    )
    val control = "\u001b\\[([0-9]+)([ABK])".r
    for
      (before, after) <- cases
      force <- List(false, true)
    do
      val rows = Array.fill(12)("")
      before.zipWithIndex.foreach((line, index) => rows(index) = line)
      rows(11) = "footer must remain"
      var row = before.size
      var column = 0
      var remaining = Tui.replaceRows(before, after, force)
      while remaining.nonEmpty do
        control.findPrefixMatchOf(remaining) match
          case Some(sequence) =>
            val count = sequence.group(1).nn.toInt
            sequence.group(2) match
              case "A" => row -= count
              case "B" => row += count
              case "K" => assertEquals(count, 2); rows(row) = ""
              case _ => fail("Unexpected cursor operation")
            remaining = remaining.drop(sequence.end)
          case None =>
            remaining.head match
              case '\r' => column = 0
              case '\n' => row += 1; column = 0
              case '\u001b' => fail("A row update must not erase the screen")
              case char =>
                rows(row) = rows(row).take(column).padTo(column, ' ') + char + rows(row).drop(column + 1)
                column += 1
            remaining = remaining.tail
      assertEquals(rows.take(after.size).toList, after)
      assert(rows.slice(after.size, 11).forall(_.isEmpty))
      assertEquals(rows(11), "footer must remain")
      assertEquals(row, after.size)
    assert(!Tui.replaceRows(List("header", "old"), List("header", "new")).contains("header"))

  test("cancelling a text answer clears JLine's interrupt before returning to the menu"):
    try
      val answer = Tui.readAnswer {
        Thread.currentThread().interrupt()
        throw org.jline.reader.UserInterruptException("unfinished")
      }
      assertEquals(answer, None)
      assert(!Thread.currentThread().isInterrupted)
      assertEquals(Tui.readAnswer(" revised instructions "), Some("revised instructions"))
      assertEquals(Tui.readAnswer(throw org.jline.reader.EndOfFileException()), None)
    finally Thread.interrupted()

  test("plain permission prompts require an exact approval and preserve qualified answers as instructions"):
    for answer <- List("y", "YES", " yes ") do
      assertEquals(Tui.permissionReply(Some(answer)), Decision.AllowOnce)
    for answer <- List("s", "SESSION", " session ") do
      assertEquals(Tui.permissionReply(Some(answer)), Decision.AllowSession)
    for answer <- List(None, Some(""), Some("  "), Some("n"), Some("NO")) do
      assertEquals(Tui.permissionReply(answer), Decision.Deny)
    for answer <- List("yes, except the fifth command", "skip deployment", "session only for tests", "只运行测试") do
      assertEquals(Tui.permissionReply(Some(answer)), Decision.Revise(answer))

  test("escape sequences stop at their final byte, timeout or EOF"):
    val expired = org.jline.utils.NonBlockingReader.READ_EXPIRED
    for sequence <- List(
        List('['.toInt, '1'.toInt, ';'.toInt, '5'.toInt, 'A'.toInt),
        List('O'.toInt, 'B'.toInt),
        List('['.toInt, expired),
        List('['.toInt, -1),
        List(expired),
      )
    do
      val input = (sequence :+ 'x'.toInt).iterator
      Tui.readEscapeSequence(() => input.next())
      assertEquals(input.next(), 'x'.toInt)

  test("malformed escape sequences cannot retain the terminal reader indefinitely"):
    var reads = 0
    val sequence = Tui.readEscapeSequence(() =>
      reads += 1
      if reads == 1 then '['.toInt else ';'.toInt
    )
    assertEquals(reads, 64)
    assertEquals(sequence.length, 64)

  test("glyphs fall back to ASCII when a dumb terminal has no encoding"):
    assertEquals(Tui.glyphs(null, forceAscii = false), Glyphs.ascii)
    assertEquals(Tui.glyphs(StandardCharsets.UTF_8, forceAscii = false), Glyphs.unicode)
    assertEquals(Tui.glyphs(StandardCharsets.UTF_8, forceAscii = true), Glyphs.ascii)

  test("non-interactive terminals are explicitly dumb UTF-8 terminals"):
    val terminal = Tui.openTerminal(
      nonInteractive = true,
      input = ByteArrayInputStream(Array.emptyByteArray),
      output = ByteArrayOutputStream(),
    )
    try
      assertEquals(terminal.getType, org.jline.terminal.Terminal.TYPE_DUMB)
      assertEquals(terminal.encoding(), StandardCharsets.UTF_8)
    finally terminal.close()

  test("drawStatus leaves the terminal outside a synchronized update (JLine buffers the closer)"):
    val output = ByteArrayOutputStream()
    val terminal = org.jline.terminal.impl.ExternalTerminal(
      "test",
      "xterm-256color",
      ByteArrayInputStream(Array.emptyByteArray),
      output,
      StandardCharsets.UTF_8,
    )
    try
      terminal.setSize(org.jline.terminal.Size.of(80, 24): org.jline.terminal.Sized)
      val status = org.jline.utils.Status.getStatus(terminal)
      assert(status != null)
      Tui.drawStatus(terminal, status, "ready")
      Tui.drawStatus(terminal, status, "reasoning 1.0 s")
      val written = output.toString(StandardCharsets.UTF_8)
      def count(sub: String): Int = written.sliding(sub.length).count(_ == sub)
      val begins = count("\u001b[?2026h")
      val ends = count("\u001b[?2026l")
      assertEquals(begins, 2)
      assertEquals(ends, 2)
      assert(written.lastIndexOf("\u001b[?2026l") > written.lastIndexOf("\u001b[?2026h"))
      assert(written.contains("ready")) // the second draw only rewrites the changed suffix
    finally terminal.close()

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
    assertEquals(Tui.uniqueIds(List("a", "a (1)", "a")), List("a", "a (1)", "a (2)"))
    assertEquals(Tui.uniqueIds(Nil), Nil)

  test("history is an owner-only regular file where POSIX permissions exist"):
    val dir = Files.createTempDirectory("atc-history").nn
    val history = dir.resolve("nested/history").nn
    assertEquals(Tui.secureHistoryFile(history), history.toRealPath())
    assert(Files.isRegularFile(history))
    val view = Files.getFileAttributeView(history, classOf[java.nio.file.attribute.PosixFileAttributeView])
    if view != null then
      val permissive = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--")
      Files.setPosixFilePermissions(history, permissive)
      Tui.secureHistoryFile(history)
      val perms = Files.getPosixFilePermissions(history).nn
      assertEquals(perms, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))

  test("history refuses a final symbolic link"):
    val dir = Files.createTempDirectory("atc-history-link").nn
    val target = dir.resolve("target").nn
    Files.writeString(target, "do not append here")
    val link = dir.resolve("history-link").nn
    assume(TestEnv.trySymbolicLink(link, target), "symbolic links are unavailable for this account")
    val e = intercept[IllegalArgumentException](Tui.secureHistoryFile(link))
    assert(e.getMessage.nn.contains("symbolic link"), e.getMessage)
    assertEquals(Files.readString(target), "do not append here")

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
    assertEquals(Ansi.sanitize("safe\u202eevil\u202c.txt"), "safeevil.txt")
    assertEquals(Ansi.sanitize("a\u2066b\u2069c\u200fd"), "abcd")
    assertEquals(Ansi.sanitize(""), "")

  test("duration never prints 60 seconds"):
    assertEquals(Tui.duration(119.6), "2 min 0 s") // was "1 min 60 s"
    assertEquals(Tui.duration(65.4), "1 min 5 s")
    assertEquals(Tui.duration(60.0), "1 min 0 s")

  test("place counts wide (CJK) characters as two columns"):
    assertEquals(Tui.displayWidth("abc"), 3)
    assertEquals(Tui.displayWidth("中文"), 4)
    assertEquals(Tui.displayWidth("\u001b[36m$ git status\u001b[0m"), 12) // styles take no cells
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
