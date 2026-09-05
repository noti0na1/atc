package atc

import atc.ui.ToolHistory
import atc.sandbox.ExecutionResult
import atc.host.FileChange

class ToolHistorySuite extends munit.FunSuite:
  test("retained output and file previews remain available without rerunning tools"):
    val history = ToolHistory()
    val entry = history.add(
      "write(...)\n42",
      ExecutionResult(true, "42"),
      10,
      "command output",
      List(FileChange("file.txt", "updated", "- old\n+ new"))
    )
    val retained = history.get(entry.id).get.render
    assert(retained.contains("command output"))
    assert(retained.contains("- old\n+ new"))
    assertEquals(history.list.size, 1)

  test("old output is evicted within the configured retention budget"):
    val history = ToolHistory(maxChars = 500, maxEntries = 3)
    val first = history.add("first", ExecutionResult(true, "x" * 1000), 1, "", Nil)
    for i <- 1 to 20 do history.add(s"tool $i", ExecutionResult(true, "x" * 1000), 1, "", Nil)
    assertEquals(history.get(first.id), None)
    assert(history.list.size <= 3)
    assert(history.latest.get.render.contains("retained output limit reached"))
    history.clear()
    assertEquals(history.latest, None)
