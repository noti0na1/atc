package atc.ui

import org.jline.terminal.{Size, Sized}
import org.jline.terminal.impl.DumbTerminal

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** The key reader of a turn: corrections typed or pasted while the agent works. */
class KeyReaderSuite extends munit.FunSuite:
  test("a paste reports its text once, when it ends"):
    val input = "\u001b[200~first line\rsecond\u001b[201~"
    val in = ByteArrayInputStream(input.getBytes(UTF_8))
    val terminal = DumbTerminal("test", "xterm-256color", in, ByteArrayOutputStream(), UTF_8)
    terminal.setSize(Size.of(80, 24): Sized)
    val drafts = ConcurrentLinkedQueue[String]()
    val submitted = ConcurrentLinkedQueue[String]()
    val alerts = Alerts(terminal, plain = false, _ => ())
    val keys =
      KeyReader(terminal, plain = false, alerts, () => (), s => { submitted.add(s); () }, s => { drafts.add(s); () })
    keys.start()
    val deadline = System.nanoTime() + 5_000_000_000L
    while !drafts.contains("first line\nsecond") && System.nanoTime() < deadline do Thread.sleep(10)
    keys.stop()
    assert(submitted.isEmpty, "a pasted newline submits nothing")
    assertEquals(drafts.asScala.toList.filter(_.nonEmpty).distinct, List("first line\nsecond"))
