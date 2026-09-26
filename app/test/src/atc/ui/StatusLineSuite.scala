package atc.ui

import org.jline.terminal.{Size, Sized}
import org.jline.terminal.impl.DumbTerminal

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8

/** The window title and the footer. */
class StatusLineSuite extends munit.FunSuite:
  test("the window title is one line"):
    val out = ByteArrayOutputStream()
    val terminal = DumbTerminal("test", "xterm-256color", ByteArrayInputStream(Array.emptyByteArray), out, UTF_8)
    terminal.setSize(Size.of(80, 24): Sized)
    val screen = Screen(terminal, plain = false, Glyphs.unicode)
    val status = StatusLine(screen, () => false, () => false, () => 0)
    screen.frame(status.setContext("model", "atc · a\nb\tc"))
    assert(out.toString(UTF_8).contains("\u001b]0;atc · a b c\u0007"), out.toString(UTF_8))
