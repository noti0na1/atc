package atc

/** The slash-command table: what `/help` prints, what Tab offers, and how a
  * typed line resolves (names, aliases, arguments). */
class SlashCommandSuite extends munit.FunSuite:
  import SlashCommand.*

  test("every name and alias is unique, and names are offered for completion in help order"):
    val spellings = values.toList.flatMap(c => c.name :: c.aliases.toList)
    assertEquals(spellings.distinct, spellings)
    assertEquals(names.head, "/help")
    assertEquals(names.last, "/quit")
    assert(names.forall(_.startsWith("/")))

  test("the help text has one aligned line per command"):
    val lines = helpText.linesIterator.toList
    assertEquals(lines.head, "Commands:")
    assertEquals(lines.size, values.length + 1)
    // Usage in a 24-column field after two spaces; the description follows.
    lines.tail.zip(values).foreach { (line, c) =>
      assert(line.startsWith(s"  ${c.usage}"), line)
      assertEquals(line.drop(26), c.help, line)
    }

  test("parse resolves names and aliases case-insensitively and splits off the argument"):
    assertEquals(parse("/help"), Right((Help, "")))
    assertEquals(parse("/?"), Right((Help, "")))
    assertEquals(parse("/Q"), Right((Quit, "")))
    assertEquals(parse("  /model   gpt-5  "), Right((Model, "gpt-5")))
    assertEquals(parse("/classified off"), Right((ClassifiedModel, "off")))
    assertEquals(parse("/mode read only"), Right((Mode, "read only")))

  test("parse reports an unknown command by what was typed"):
    assertEquals(parse("/bogus 1 2"), Left("/bogus"))
    assertEquals(parse("/helpme"), Left("/helpme"))
