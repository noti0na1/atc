package atc.ui

import org.jline.terminal.{Size, Sized}
import org.jline.terminal.impl.ExternalTerminal

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** The main prompt as the terminal shows it. */
class PromptReaderSuite extends munit.FunSuite:
  /** A prompt on an 80x24 xterm that reads `input`, and what it writes. */
  private def promptOn(input: String): (PromptReader, ExternalTerminal, ByteArrayOutputStream) =
    val out = ByteArrayOutputStream()
    val terminal =
      ExternalTerminal("test", "xterm-256color", ByteArrayInputStream(input.getBytes(UTF_8)), out, UTF_8)
    terminal.setSize(Size.of(80, 24): Sized)
    val screen = Screen(terminal, plain = false, Glyphs.unicode)
    val alerts = Alerts(terminal, plain = false, _ => ())
    (PromptReader(screen, Files.createTempDirectory("atc-prompt").nn.resolve("history").nn, alerts), terminal, out)

  test("Ctrl-L lifts the footer's scroll region and erases its row before clearing"):
    val (prompt, terminal, out) = promptOn("\f\u0004")
    try
      intercept[org.jline.reader.EndOfFileException](prompt.read("> ", ""))
      val text = out.toString(UTF_8)
      val lifted = text.indexOf("\u001b[r\u001b[24;1H\u001b[2K")
      assert(lifted >= 0 && lifted < text.indexOf("\u001b[2J"), text.replace("\u001b", "ESC"))
    finally terminal.close()

  test("the ghost suggestion is not left on the prompt line when the read ends"):
    val (prompt, terminal, out) = promptOn("\u0004")
    prompt.suggestion = Some("rename word_count")
    try
      intercept[org.jline.reader.EndOfFileException](prompt.read("> ", ""))
      // Drawn while the prompt waits, then erased by the redraw that stays on screen, so the
      // transcript does not show a request the user never sent.
      val text = out.toString(UTF_8)
      val ghost = text.lastIndexOf("rename word_count")
      assert(ghost >= 0, text)
      assert(text.indexOf("\u001b[K", ghost) > ghost, text.replace("\u001b", "ESC"))
    finally terminal.close()
