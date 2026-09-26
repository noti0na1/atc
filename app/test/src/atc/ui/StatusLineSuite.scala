package atc.ui

import org.jline.terminal.{Size, Sized}
import org.jline.terminal.impl.DumbTerminal

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8

/** The window title and the footer. */
class StatusLineSuite extends munit.FunSuite:
  /** A footer on a `width`-column terminal, busy when `busy` says so, and what it wrote. */
  private def footer(width: Int, busy: () => Boolean = () => false): (Screen, StatusLine, ByteArrayOutputStream) =
    val out = ByteArrayOutputStream()
    val terminal = DumbTerminal("test", "xterm-256color", ByteArrayInputStream(Array.emptyByteArray), out, UTF_8)
    terminal.setSize(Size.of(width, 24): Sized)
    val screen = Screen(terminal, plain = false, Glyphs.unicode)
    (screen, StatusLine(screen, busy, () => false, () => 0), out)

  private val details = "deepseek/deepseek-flash · ctx 1M · effort high · web search on"
  private val fields = s"full · $details"
  /** The footer as it reads, without its styles. */
  private def plain(line: String) = Ansi.Sgr.replaceAllIn(line, "")

  test("the window title is one line"):
    val (screen, status, out) = footer(80)
    screen.frame(status.setContext("full", "model", "a", "atc · a\nb\tc"))
    assert(out.toString(UTF_8).contains("\u001b]0;atc · a b c\u0007"), out.toString(UTF_8))

  test("the footer holds the mode and the model's settings on the left and the folder on the right"):
    val (screen, status, _) = footer(100)
    screen.frame(status.setContext("full", details, "textstats", "atc · textstats"))
    val line = plain(status.line)
    assert(line.startsWith(fields) && line.endsWith("  textstats"), line)
    assertEquals(Screen.displayWidth(line), 99)
    // the mode is set apart by its colour
    assert(status.line.startsWith(Ansi.styled("full", Ansi.Cyan, Ansi.Bold)), status.line)

  test("during a turn the activity sits beside the folder, and a narrow footer cuts the fields first"):
    val (screen, status, _) = footer(110, () => true)
    screen.frame(status.setContext("full", details, "textstats", "atc · textstats"))
    screen.frame(status.beginTurn())
    screen.frame(status.setOperation("responding"))
    val line = plain(status.line)
    assert(line.startsWith(fields), line)
    assert(line.matches(".*responding \\S+ s +textstats$"), line)
    val (narrowScreen, narrow, _) = footer(40)
    narrowScreen.frame(narrow.setContext("full", details, "textstats", "atc · textstats"))
    val cut = plain(narrow.line)
    assert(cut.startsWith("full · deepseek") && cut.contains("…") && cut.endsWith("textstats"), cut)
    assertEquals(Screen.displayWidth(cut), 39)
