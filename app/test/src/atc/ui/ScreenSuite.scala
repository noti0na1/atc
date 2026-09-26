package atc.ui

import org.jline.terminal.{Size, Sized, Terminal}
import org.jline.terminal.impl.DumbTerminal

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8

/** The screen's measured size: kept between resizes, and caught up with when a
  * resize went to a reader that had the terminal's signal. */
class ScreenSuite extends munit.FunSuite:
  test("measuring again reports whether the terminal's size changed"):
    val terminal =
      DumbTerminal("test", "xterm-256color", ByteArrayInputStream(Array.emptyByteArray), ByteArrayOutputStream(), UTF_8)
    (terminal: Terminal).setSize(Size(100, 30))
    val screen = Screen(terminal, plain = false, Glyphs.unicode)
    assertEquals((screen.width, screen.height), (100, 30))
    assert(!screen.resized(), "nothing changed")
    (terminal: Terminal).setSize(Size(60, 30))
    assertEquals(screen.width, 100, "measured only when asked")
    assert(screen.resized())
    assertEquals((screen.width, screen.height), (60, 30))

  private def screenOf(width: Int, out: ByteArrayOutputStream = ByteArrayOutputStream()): Screen =
    val terminal = DumbTerminal("test", "xterm-256color", ByteArrayInputStream(Array.emptyByteArray), out, UTF_8)
    terminal.setSize(Size.of(width, 24): Sized)
    Screen(terminal, plain = false, Glyphs.unicode)

  test("fit counts tab stops from the column the text starts at, and a newline as a space"):
    val screen = screenOf(80)
    // After a four-column gutter the tab reaches column 16, so the line would end at column 83.
    val tabbed = "xxxxx\t" + "y" * 67
    val cut = screen.fit(tabbed, 4)
    assert(cut.endsWith("…"), cut)
    assert(Screen.displayWidth("    " + cut) < 80, cut)
    assertEquals(screen.fit(tabbed, 0), tabbed, "from column 0 it fits")
    assertEquals(screen.fit("a\nb", 0), "a b")
    assertEquals(screen.fit("\u001b[2m" + "z" * 200 + "\u001b[0m", 0), "\u001b[2m" + "z" * 78 + "\u001b[0m…")

  test("a live region cuts every row to the width from column 0, gutter and all"):
    val out = ByteArrayOutputStream()
    val screen = screenOf(24, out)
    val region = screen.LiveRegion()
    val rows =
      List(screen.gutter(Ansi.Dim) + "xxxxx\t" + "y" * 10, "• thinking… (Ctrl-O to expand)", "a label\nwith a newline")
    screen.frame(region.redraw(rows))
    val drawn = out.toString(UTF_8).split("\r\u001b\\[2K").toList.drop(1).map(_.stripSuffix("\n"))
    assertEquals(drawn.size, 3)
    assert(drawn.forall(row => !row.contains('\n') && Screen.displayWidth(row) < 24), drawn)
    assertEquals(drawn(2), "a label with a newline")
