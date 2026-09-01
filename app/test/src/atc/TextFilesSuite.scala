package atc

class TextFilesSuite extends munit.FunSuite:
  test("a leading BOM is stripped without touching embedded BOMs"):
    assertEquals(TextFiles.bomLength("plain"), 0)
    assertEquals(TextFiles.bomLength("\uFEFFplain"), 1)
    assertEquals(TextFiles.stripBom("\uFEFFplain\uFEFFtext"), "plain\uFEFFtext")
    assertEquals(TextFiles.stripBom("plain\uFEFFtext"), "plain\uFEFFtext")

  test("LF, CRLF, and bare CR split and join losslessly"):
    for ending <- List("\n", "\r\n", "\r") do
      val text = s"first${ending}second$ending"
      val split = TextFiles.splitLines(text)
      assertEquals(split.lines, List("first", "second"), ending)
      assertEquals(split.lineEnding, ending)
      assert(split.trailingLineEnding, ending)
      assertEquals(split.join, text)

  test("splitting records missing final endings and normalizes mixed files to the first ending"):
    val split = TextFiles.splitLines("first\r\nsecond\nthird\rfourth")
    assertEquals(split.lines, List("first", "second", "third", "fourth"))
    assertEquals(split.lineEnding, "\r\n")
    assert(!split.trailingLineEnding)
    assertEquals(split.join, "first\r\nsecond\r\nthird\r\nfourth")
    assertEquals(TextFiles.splitLines("").join, "")

  test("line-ending detection treats CRLF as one ending"):
    val text = "first\r\nsecond\rthird\n"
    assertEquals(TextFiles.firstLineEnding(text), Some(TextFiles.LineEnding(5, "\r\n")))
    assertEquals(TextFiles.lastLineEnding(text), Some(TextFiles.LineEnding(19, "\n")))
    assertEquals(TextFiles.lastLineEnding("a\n\n"), Some(TextFiles.LineEnding(2, "\n")))
    assertEquals(TextFiles.lastLineEnding("a\r\r"), Some(TextFiles.LineEnding(2, "\r")))
    assertEquals(TextFiles.lastLineEnding("a\r\n"), Some(TextFiles.LineEnding(1, "\r\n")))
    assertEquals(TextFiles.lastLineEnding("\n"), Some(TextFiles.LineEnding(0, "\n")))
    assertEquals(TextFiles.lastLineEnding("abc"), None)
    assertEquals(TextFiles.firstLineEnding(""), None)

  test("appending follows the existing line-ending convention and preserves a BOM"):
    for ending <- List("\n", "\r\n", "\r") do
      assertEquals(TextFiles.appendLine(s"\uFEFFfirst$ending", "second"), s"\uFEFFfirst${ending}second$ending")
      assertEquals(TextFiles.appendLine("first", "second"), "first\nsecond\n")
    assertEquals(TextFiles.appendLine("first\r\n\r\n", "second"), "first\r\n\r\nsecond\r\n")
