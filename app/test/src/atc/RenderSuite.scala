package atc

import atc.ui.{Highlight, MarkdownStream}

/** The streaming Markdown renderer and the Scala highlighter (pure). */
class RenderSuite extends munit.FunSuite:
  val E = "\u001b"
  val R = s"$E[0m"
  val B = s"$E[1m"
  val D = s"$E[2m"
  val C = s"$E[36m"
  private val glyphs = MarkdownStream.Glyphs("•", "▎", "─", "G ", "│", "┼")
  private def md() = MarkdownStream(glyphs, code => code.linesIterator.toList.map("H:" + _))
  private def render(chunks: String*): String =
    val m = md()
    chunks.map(m.push).mkString + m.finish()
  private def plain(s: String): String = s.replaceAll("\\[[0-9;]*m", "")

  test("plain text streams through immediately, chunk by chunk"):
    val m = md()
    assertEquals(m.push("Hello world, "), "Hello world, ")
    assertEquals(m.push("more"), "more")
    assertEquals(m.finish(), "\n")

  test("bold and code spans, also when the markers are split across chunks"):
    assertEquals(render("Hello **wor", "ld** and `x`\n"), s"Hello ${R}${B}world${R} and ${R}${C}x${R}\n")
    val m = md()
    assertEquals(m.push("a *"), "a ") // lone trailing `*` is held back
    assertEquals(m.push("* b **\n"), s"${R}${B} b ${R}\n")

  test("unclosed styles are reset at the end of the line"):
    assertEquals(render("**oops\nnext\n"), s"${R}${B}oops${R}\nnext\n")

  test("headings, bullets, ordered lists, quotes, rules"):
    assertEquals(render("## Title\n"), s"${B}Title${R}\n")
    assertEquals(render("- item\n  * nested\n1. one\n"), "• item\n  • nested\n1. one\n")
    assertEquals(render("> quoted\n"), s"${D}▎ quoted${R}\n")
    assertEquals(render("---\n"), s"${D}${"─" * 40}${R}\n")
    assertEquals(plain(render("- **bold** item\n")), "• bold item\n")

  test("the start of a line waits until it cannot be a marker any more"):
    val m = md()
    assertEquals(m.push("#"), "")
    assertEquals(m.push("# H\n"), s"${B}H${R}\n")
    assertEquals(m.push("- "), "")
    assertEquals(m.push("x\n"), "• x\n")
    assertEquals(m.push("1"), "")
    assertEquals(m.push("0 apples\n"), "10 apples\n")
    assertEquals(m.push("Hi"), "Hi") // cannot be a marker: rendered at once

  test("fenced code: coloured only when tagged scala; other or untagged blocks are verbatim"):
    assertEquals(render("```scala\nval x = 1\nval y = 2\n```\nafter\n"), "G H:val x = 1\nG H:val y = 2\nafter\n")
    assertEquals(render("```sh\nls -la\n```\n"), "G ls -la\n")
    assertEquals(
      render("```scala\na\n\nb\n```\n"),
      "G H:a\nG H:\nG H:b\n"
    ) // blank lines inside stay blank (not repeats)
    assertEquals(render("```json\n{\"a\": 1}\n```\n"), "G {\"a\": 1}\n")
    assertEquals(render("```\nplain\n"), "G plain\n") // untagged: verbatim; unterminated fence flushed by finish
    // a fence line arriving in pieces
    val m = md()
    assertEquals(m.push("``"), "")
    assertEquals(m.push("`\nval a = 1\n"), "G val a = 1\n")
    assertEquals(m.push("```\ndone\n"), "done\n")

  test("a ```markdown wrapper is transparent and a fence line is waited for in full"):
    assertEquals(render("```markdown\n# T\n- a\n```\n"), s"${B}T${R}\n• a\n")
    val m = md()
    assertEquals(m.push("```mark"), "")
    assertEquals(m.push("down\nx\n"), "x\n")
    assertEquals(m.push("```sca"), "")
    assertEquals(m.push("la\nval a = 1\n```\n"), "G H:val a = 1\n")

  test("pipe tables are laid out in columns once complete, with the delimiter's alignment"):
    val src = "| Name | N | Where |\n|:--|--:|:-:|\n| Alice | 30 | Paris |\n| **Bob** | 7 | `Rome` |\n"
    val out = render(src)
    assertEquals(
      plain(out),
      "Name  │  N │ Where\n" +
        "──────┼────┼──────\n" +
        "Alice │ 30 │ Paris\n" +
        "Bob   │  7 │ Rome\n"
    )
    assert(out.startsWith(s"$R${B}Name"), out) // bold header cells
    assert(out.contains(s"${R}${B}Bob"), out) // inline bold inside a cell
    assert(out.contains(s"$C"), out) // inline code inside a cell
    // arriving in arbitrary chunks gives the same result, and nothing shows before the table is complete
    val m = md()
    val chunks = src.grouped(7).toList
    val early = chunks.init.map(m.push).mkString
    assertEquals(early, "")
    assertEquals(m.push(chunks.last) + m.finish(), out)

  test("a table ends at the first non-row line, an unterminated one at finish, and a lone | line is text"):
    assertEquals(plain(render("| a | b |\n|---|---|\n| 1 | 2 |\nafter\n")), "a │ b\n──┼──\n1 │ 2\nafter\n")
    assertEquals(plain(render("| a | b |\n|---|---|\n| 1 | 2 |")), "a │ b\n──┼──\n1 │ 2\n")
    assertEquals(render("| not a table\nnext\n"), "| not a table\nnext\n")
    assertEquals(render("| held\n"), "| held\n")
    // text streaming after a table is not emitted before the table itself
    val m = md()
    assertEquals(plain(m.push("| a |\n|---|\n| 1 |\nSome longer text")), "a\n─\n1\nSome longer text")
    assertEquals(m.finish(), "\n")

  test("blank lines and empty pushes are harmless"):
    assertEquals(render("a\n\nb\n"), "a\n\nb\n")
    assertEquals(render("", "x", ""), "x\n")
    assertEquals(md().finish(), "")

  test("a rule line under a |-line is a horizontal rule, not a one-column table"):
    // `---` has no pipe: it must not be taken as the delimiter row of a table.
    assertEquals(plain(render("| a | b |\n---\n")), "| a | b |\n" + "─" * 40 + "\n")
    // a one-column table (pipes around the delimiter cell) still renders as a table
    assertEquals(plain(render("| a |\n|---|\n| 1 |\n")), "a\n─\n1\n")

  test("splitCarrying reopens the active colour on every line and closes it at the end"):
    assertEquals(Highlight.splitCarrying(s"$E[32ma\nb$R c\n"), List(s"$E[32ma$R", s"$E[32mb$R c"))
    assertEquals(Highlight.splitCarrying("plain"), List("plain"))
    assertEquals(Highlight.splitCarrying(""), List(""))

  test("Highlight.scala colours keywords and carries a multi-line string; broken code survives"):
    val lines = Highlight.scala("val s = \"\"\"a\nb\"\"\"\nval y = 1")
    assertEquals(lines.length, 3)
    assert(lines.head.contains(s"$E[33mval"), lines.head)
    assert(lines(1).startsWith(s"$E[32m"), lines(1)) // string colour reopened
    assert(!lines(1).contains(s"$E[33m"))
    assertEquals(plain(lines.mkString("\n")), "val s = \"\"\"a\nb\"\"\"\nval y = 1")
    assertEquals(plain(Highlight.scala("val = (((\"x").mkString), "val = (((\"x")
    assert(!Highlight.scala("// c").head.contains(s"$E[34m")) // comments not blue
