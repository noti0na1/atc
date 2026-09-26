package atc.ui

import org.jline.terminal.{Size, Terminal}
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
