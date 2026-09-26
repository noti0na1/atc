package atc.ui

import atc.perms.{Decision, ExecRequest}

import org.jline.terminal.{Size, Sized}
import org.jline.terminal.impl.DumbTerminal

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** Pop-ups as the user meets them: what they show and what a key does. */
class DialogsSuite extends munit.FunSuite:

  /** Dialogs on a `width`-column terminal that sends `input` and then ends; what it wrote. */
  private def dialogs(input: String, width: Int = 80): (Dialogs, ByteArrayOutputStream) =
    val out = ByteArrayOutputStream()
    val terminal = DumbTerminal("test", "xterm-256color", ByteArrayInputStream(input.getBytes(UTF_8)), out, UTF_8)
    terminal.setSize(Size.of(width, 24): Sized)
    val screen = Screen(terminal, plain = false, Glyphs.unicode)
    val alerts = Alerts(terminal, plain = false, _ => ())
    val keys = KeyReader(terminal, plain = false, alerts, () => (), _ => (), _ => ())
    val status = StatusLine(screen, () => true, () => true, () => 0)
    val prompt = PromptReader(screen, Files.createTempDirectory("atc-dialogs").nn.resolve("history").nn, alerts)
    (Dialogs(screen, alerts, keys, status, prompt), out)

  private def written(out: ByteArrayOutputStream): String = Ansi.Sgr.replaceAllIn(out.toString(UTF_8), "")

  test("a stray Enter does not approve a permission request, and Esc is shown to deny"):
    val (d, out) = dialogs("\r")
    assertEquals(d.permission(ExecRequest(List("rm -rf build"), "clean the build")), Decision.Deny)
    assert(written(out).contains("Esc deny"), written(out))

  test("a long question wraps with its lines under the first one's text"):
    val question = "Which of the three build directories should be removed before the next release is cut?"
    val (d, out) = dialogs("", width = 40)
    assertEquals(d.answer(question, List("all", "none"), multiple = false), None)
    val lines = TextLayout.wrap(question, 35)
    assert(lines.size > 1)
    val expected = (("  ? " + lines.head) :: lines.tail.map("    " + _)).mkString("\n")
    assert(written(out).contains(expected), written(out))
