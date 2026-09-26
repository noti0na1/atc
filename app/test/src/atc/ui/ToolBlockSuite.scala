package atc.ui

import atc.sandbox.ExecutionResult

import org.jline.terminal.{Size, Terminal}
import org.jline.terminal.impl.DumbTerminal

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable

/** A tool block as the terminal shows it: folded to a summary when it ends in the
  * compact view, written out in full in the expanded one. */
class ToolBlockSuite extends munit.FunSuite:

  /** A block on a 80x24 terminal whose output is kept in the returned stream. */
  private def block(expanded: Boolean): (Screen, ToolBlock, ByteArrayOutputStream) =
    val out = ByteArrayOutputStream()
    // A dumb terminal of an xterm type writes synchronously and sends no probes, whose
    // pending answers would delay the output of a terminal built from streams.
    val terminal = DumbTerminal("test", "xterm-256color", ByteArrayInputStream(Array.emptyByteArray), out, UTF_8)
    (terminal: Terminal).setSize(Size(80, 24))
    val screen = Screen(terminal, plain = false, Glyphs.unicode)
    (screen, ToolBlock(screen, () => expanded), out)

  /** The rows the output leaves on screen, replaying the cursor moves and line
    * clears a live region uses; styles are dropped. */
  private def rows(out: ByteArrayOutputStream): List[String] =
    val text = out.toString(StandardCharsets.UTF_8)
    val screen = mutable.ArrayBuffer("")
    var row = 0
    var i = 0
    // Parameters, intermediate bytes (`$` in JLine's `ESC[?2026$p` probe), final byte.
    val csi = "\u001b\\[([0-9;?]*)[ -/]*([@-~])".r
    while i < text.length do
      text.charAt(i) match
        case '\u001b' =>
          val m = csi.findPrefixMatchOf(
            text.substring(i)
          ).getOrElse(fail(s"unexpected escape: ${text.substring(i).take(8).drop(1)}"))
          val n = m.group(1).nn.toIntOption.getOrElse(1)
          m.group(2) match
            case "A" => row -= n
            case "B" => row += n
            case "K" => screen(row) = ""
            case _ => () // styles, modes
          i += m.end
        case '\r' => i += 1
        case '\n' =>
          row += 1
          while screen.size <= row do screen += ""
          i += 1
        case c =>
          screen(row) = screen(row) + c
          i += 1
    screen.toList.reverse.dropWhile(_.isEmpty).reverse

  test("a finished block in the compact view leaves only its summary"):
    val (screen, tool, out) = block(expanded = false)
    screen.frame(tool.start("val xs = 1 to 30\nxs.foreach(println)", "run_scala"))
    for i <- 1 to 30 do
      screen.frame(tool.emit(s"line $i\n"))
      assert(rows(out).size <= screen.height - 3, s"the running block must fit above the footer: ${rows(out)}")
    screen.frame(tool.end(ExecutionResult(success = true, output = ""), 12))
    assertEquals(
      rows(out),
      List("● run_scala  val xs = 1 to 30 …", "  └ ok 12 ms · 30 lines of output · /output 1"),
    )

  test("a summary longer than the width is cut to one row, styles and all"):
    val (screen, tool, out) = block(expanded = false)
    val code = "grep(\"app/src/atc/agent/ToolRunner.scala\", \"^(final class|object)\").foreach(m => println(m.line))"
    screen.frame(tool.start(code, "run_scala"))
    screen.frame(tool.end(ExecutionResult(success = true, output = ""), 7))
    val summary = rows(out)
    assertEquals(summary.head, ("● run_scala  " + code).take(78) + "…")
    assertEquals(summary.last, "  └ ok 7 ms · /output 1")

  test("a failed block's summary says why"):
    val (screen, tool, out) = block(expanded = false)
    screen.frame(tool.start("val x: Int = \"s\"", "run_scala"))
    val diagnostic = "-- [E007] Type Mismatch Error: ------\n1 |val x: Int = \"s\"\n  |             ^^^\n"
    screen.frame(tool.end(ExecutionResult(success = false, output = diagnostic), 5))
    assertEquals(
      rows(out),
      List("● run_scala  val x: Int = \"s\"", "  └ failed 5 ms · [E007] Type Mismatch Error · /output 1"),
    )

  test("the expanded view writes the block out in full"):
    val (screen, tool, out) = block(expanded = true)
    screen.frame(tool.start("println(1)", "run_scala"))
    screen.frame(tool.print("1\n", "1\n"))
    screen.frame(tool.end(ExecutionResult(success = true, output = "1\nval r: Int = 1"), 3))
    assertEquals(
      rows(out),
      List(
        "● run_scala",
        "  │ println(1)",
        "  ├ output",
        "  │ 1",
        "  ├ result",
        "  │ val r: Int = 1",
        "  └ ok 3 ms · /output 1",
      ),
    )

  test("the reason a call failed: its error, a coded compiler heading, or the message under a bare one"):
    def problem(output: String, error: Option[String] = None) =
      ToolBlock.firstProblem(ExecutionResult(success = false, output = output, error = error), "")
    assertEquals(
      problem("", Some("java.lang.RuntimeException: boom\n  at x")),
      Some("RuntimeException: boom") // java.lang is noise in a one-line summary
    )
    assertEquals(problem("", Some("java.io.IOException: gone")), Some("java.io.IOException: gone"))
    assertEquals(problem("-- [E007] Type Mismatch Error: ----\n1 |x\n  |^"), Some("[E007] Type Mismatch Error"))
    assertEquals(
      problem("-- Error: ----\n1 |Thread.sleep(1)\n  |^^^^^^^^^^^^\n  |Cannot refer to method sleep\n"),
      Some("Cannot refer to method sleep"),
    )
    assertEquals(problem(""), None)

  test("every line of a classified print is marked"):
    val (screen, tool, out) = block(expanded = true)
    screen.frame(tool.start("println(secret)", "run_scala"))
    screen.frame(tool.print("[classified]\n", "first secret\nsecond secret\n"))
    assertEquals(rows(out).takeRight(2), List("  │ [classified] first secret", "  │ [classified] second secret"))

  test("the expanded view keeps a long output line inside the gutter, across chunks"):
    val (screen, tool, out) = block(expanded = true)
    screen.frame(tool.start("println(long)", "run_scala"))
    screen.frame(tool.emit("x" * 60))
    screen.frame(tool.emit("y" * 60 + "\nshort\n"))
    val shown = rows(out).dropWhile(!_.contains("output")).drop(1)
    assert(shown.take(3).forall(_.startsWith("  │ ")), shown)
    assertEquals(shown.map(_.stripPrefix("  │ ")).take(3).mkString, "x" * 60 + "y" * 60 + "short")
    assert(shown.forall(Screen.displayWidth(_) < 80), shown)

  test("repeated identical file changes fold into one row with a count"):
    val (screen, tool, out) = block(expanded = false)
    screen.frame(tool.start("edits", "run_scala"))
    for _ <- 1 to 4 do tool.fileChanged(atc.host.FileChange("a.py", "updated (+1 -1)", ""))
    tool.fileChanged(atc.host.FileChange("b.py", "created (+3 -0)", ""))
    screen.frame(tool.end(ExecutionResult(success = true, output = ""), 5))
    assertEquals(rows(out).slice(1, 3), List("  a.py: updated (+1 -1), 4 times", "  b.py: created (+3 -0)"))

  test("output that arrives while a pop-up is drawn waits for it to close"):
    val (screen, tool, out) = block(expanded = false)
    screen.frame(tool.start("parallel(tasks)", "run_scala"))
    screen.frame(tool.emit("before\n"))
    screen.frame { tool.endOutput(); tool.hold() }
    val shown = out.size
    screen.frame(tool.emit("during\n"))
    assertEquals(out.size, shown, "nothing is written over the pop-up")
    screen.frame(tool.release())
    assert(rows(out).last.endsWith("during"), rows(out))
