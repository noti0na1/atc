package atc.ui

import org.jline.terminal.{Size, Sized, Terminal}
import org.jline.terminal.impl.DumbTerminal

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8

/** Menus: keys, the cursor and window, filtering, ticks, and what is drawn. */
class ListMenuSuite extends munit.FunSuite:
  import MenuKey.*
  import MenuState.Outcome.*

  private def keys(bytes: String): List[MenuKey] =
    val it = bytes.iterator
    def next(): Int = if it.hasNext then it.next().toInt else -2 // -2: nothing more within the timeout
    val decoded = List.newBuilder[MenuKey]
    while it.hasNext do decoded += MenuKey.decode(it.next().toInt, () => next())
    decoded.result()

  private val models =
    Vector("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-pro-preview-tts", "gemma-4", "gemini-3.8-flash") ++
      (1 to 20).map(i => s"other-$i")

  test("keys: arrows, pages and ends in their usual encodings; a lone Esc; text"):
    assertEquals(
      keys("\u001b[A\u001bOB\u001b[5~\u001b[6~\u001b[H\u001b[F"),
      List(Up, Down, PageUp, PageDown, Home, End)
    )
    assertEquals(keys("\u001b"), List(Escape))
    assertEquals(keys("\u001b[I\u001b[O"), List(FocusIn, FocusOut))
    assertEquals(keys("\u001b[200~\r\u001b[201~"), List(PasteStart, Enter, PasteEnd))
    assertEquals(keys("a \r\u007f\u0003"), List(Text("a"), Space, Enter, Backspace, Cancel))

  test("a tick belongs to its item, whatever the filter shows"):
    val menu = MenuState(models, multi = true, 0, Set(4))
    "pro".foreach(c => menu(Text(c.toString), 10))
    assertEquals(menu.matches.map(models), Vector("gemini-2.5-pro", "gemini-2.5-pro-preview-tts"))
    menu(Down, 10)
    menu(Space, 10)
    assertEquals(menu(Escape, 10), Continue, "Esc clears the filter first")
    assertEquals(menu.filter, "")
    assertEquals(menu.current, Some(2), "the cursor stays on its item")
    assertEquals(menu(Enter, 10), Chosen(List(2, 4)))

  test("a filter matches every word; Backspace widens it; Esc leaves once it is empty"):
    val menu = MenuState(models, multi = false, 0, Set.empty)
    "2.5 pro".foreach(c => menu(if c == ' ' then Space else Text(c.toString), 10))
    assertEquals(menu.matches.map(models), Vector("gemini-2.5-pro", "gemini-2.5-pro-preview-tts"))
    menu(Backspace, 10)
    assertEquals(menu.filter, "2.5 pr")
    menu(Escape, 10)
    assertEquals(menu(Escape, 10), Cancelled)

  test("a short list takes no filter; arrows wrap; pages and ends move within the list"):
    val short = MenuState(Vector("a", "b", "c"), multi = false, 0, Set.empty)
    short(Text("x"), 10)
    assertEquals(short.matches.size, 3)
    short(Up, 10)
    assertEquals(short.current, Some(2))
    val long = MenuState(models, multi = false, 0, Set.empty)
    long(PageDown, 10)
    assertEquals(long.current, Some(10))
    long(End, 10)
    assertEquals(long.current, Some(models.size - 1))
    assertEquals(long(Enter, 10), Chosen(List(models.size - 1)))

  test("the window opens around the initial row and follows the cursor"):
    val menu = MenuState(models, multi = false, 20, Set.empty)
    assertEquals(menu.window(10), (15, 25))
    menu(Home, 10)
    assertEquals(menu.window(10), (0, 10))

  test("ticked items are listed first, so a long list opens on what is chosen"):
    val menu = MenuState(models, multi = true, 0, Set(4, 1))
    assertEquals(menu.matches.take(3).map(models), Vector("gemini-2.5-pro", "gemini-3.8-flash", "gemini-2.5-flash"))

  test("the menu fits the screen: title and counts stay, rows scroll, long labels are cut"):
    val terminal =
      DumbTerminal("test", "xterm-256color", ByteArrayInputStream(Array.emptyByteArray), ByteArrayOutputStream(), UTF_8)
    (terminal: Terminal).setSize(Size(50, 16))
    val screen = Screen(terminal, plain = false, Glyphs.unicode)
    val menu = MenuState(models :+ ("x" * 80), multi = true, 0, Set(4))
    // As the menu's live region draws them: cut to one row.
    def plain(lines: List[String]) =
      lines.map(line => org.jline.utils.AttributedString.fromAnsi(screen.fit(line, 0)).nn.toString)
    val (lines, rows) = ListMenu.render(menu, "Models gemini offers", "keys", keysInMenu = false, 16, screen)
    val shown = plain(lines)
    assert(lines.size < 16 - 1, shown)
    assert(shown.forall(Screen.displayWidth(_) < 50), shown)
    assertEquals(shown.head, "? Models gemini offers  · 1 of 26 ticked")
    assertEquals(shown(1), "  Type to filter · 26 items")
    assertEquals(shown(3), "❯ ◉ gemini-3.8-flash")
    assertEquals(shown.last, s"  ▼ ${26 - rows} more")
    menu(End, rows)
    val end = plain(ListMenu.render(menu, "Models gemini offers", "keys", keysInMenu = true, 16, screen)._1)
    assert(end.exists(_.startsWith("❯ ◯ xxxx")) && end.exists(_.endsWith("…")), end)
    assertEquals(end.last, "  keys")
    // under a question: no title row, only the count, and every row indented
    val under = plain(ListMenu.render(menu, "", "keys", keysInMenu = false, 16, screen, indent = "    ")._1)
    assertEquals(under.head, "    · 1 of 26 ticked")
    assert(under.drop(1).filter(_.nonEmpty).forall(_.startsWith("    ")), under)

  /** Run a two-row menu on a terminal that sends `input` after `delayMillis`, then ends. */
  private def choose(input: String, delayMillis: Long = 0): Option[List[Int]] =
    val bytes = ByteArrayInputStream(input.getBytes(UTF_8))
    val source = new java.io.InputStream:
      private var waited = false
      override def read(): Int =
        if !waited then { Thread.sleep(delayMillis); waited = true }
        bytes.read()
    val terminal = DumbTerminal("test", "xterm-256color", source, ByteArrayOutputStream(), UTF_8)
    terminal.setSize(Size.of(80, 24): Sized)
    val screen = Screen(terminal, plain = false, Glyphs.unicode)
    val alerts = Alerts(terminal, plain = false, _ => ())
    val status = StatusLine(screen, () => true, () => true, () => 0)
    ListMenu(screen, status, alerts).run("Allow?", List("Allow once", "Deny"), multi = false, 0, Set.empty, "deny")

  test("a newline that arrives as the menu opens, or inside a paste, chooses nothing"):
    assertEquals(choose("\r"), None)
    assertEquals(choose("\n\r\n"), None)
    assertEquals(choose("\u001b[200~yes\r\u001b[201~", delayMillis = 500), None)
    assertEquals(
      choose("\u001b[200~x\u001b[201~\u001b[B\r", delayMillis = 500),
      Some(List(1)),
      "keys after a paste count"
    )
    assertEquals(choose("\r", delayMillis = 500), Some(List(0)))
