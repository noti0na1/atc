package atc

import atc.ui.{Ansi, TextLayout}

class TextLayoutSuite extends munit.FunSuite:
  private def plain(text: String): String = Ansi.Sgr.replaceAllIn(text, "")

  test("wrapping preserves styled text, Unicode and word boundaries"):
    val input = Ansi.styled("Read the 中文 source and check the result", Ansi.Cyan)
    val lines = TextLayout.wrap(input, 16)
    assert(lines.forall(line => TextLayout.width(line) <= 16))
    assertEquals(lines.map(plain).mkString(" "), "Read the 中文 source and check the result")
    assert(lines.forall(_.contains("\u001b[36m")))

  test("tabs measure as tab stops, so a tabbed line is wrapped rather than let through"):
    assertEquals(TextLayout.width("a\tb"), 9)
    assertEquals(TextLayout.wrap("a\tb", 4).map(plain), List("a", "b"))

  test("long unbroken text wraps without dropping characters"):
    for text <- List("abcdefghijklmnopqrstuvwxyz", "中文漢字" * 8, "e\u0301" * 20) do
      val lines = TextLayout.wrap(text, 7)
      assert(lines.forall(line => TextLayout.width(line) <= 7))
      assertEquals(lines.map(plain).mkString, text)

  test("field layouts keep continuation lines aligned and adapt to narrow terminals"):
    val rows =
      List("/long-command [argument]" -> "Read and inspect the retained command output without running it again")
    for columns <- List(40, 80, 120) do
      val lines = TextLayout.fields(rows, columns)
      assert(lines.forall(line => TextLayout.width(line) < columns))
      assert(lines.forall(_.startsWith("  ")))
      assert(lines.mkString(" ").contains("/long-command [argument]"))
    assert(TextLayout.fields(rows, 40)(1).startsWith("    Read"))
