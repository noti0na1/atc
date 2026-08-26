package atc

import atc.agent.ToolOutput
import atc.perms.Decision
import atc.sandbox.ExecutionResult

class ToolOutputSuite extends munit.FunSuite:
  test("renders short and bounded sandbox output"):
    assertEquals(ToolOutput.renderForModel(ExecutionResult(true, "hello"), 1000), "hello")
    assertEquals(ToolOutput.renderForModel(ExecutionResult(true, ""), 1000), "(no output)")

    val big = ("H" * 400) + ("T" * 400)
    val bounded = ToolOutput.renderForModel(ExecutionResult(true, big), 120)
    assert(bounded.startsWith("H"), bounded)
    assert(bounded.contains("characters omitted"), bounded)
    assert(bounded.endsWith("T"), bounded)

  test("adds diagnostic guidance and leaves permission decisions uncut"):
    val result = ExecutionResult(false, "Cannot run program \"gti\": No such file or directory")
    val guided = ToolOutput.renderForModel(result, 10000)
    assert(guided.contains("Hint:"), guided)
    assert(guided.contains("PATH"), guided)

    val rendered = ToolOutput.renderForModel(
      result,
      20,
      List(
        Decision.AllowOnce -> "commands gti status",
        Decision.AllowSession -> "read on '/tmp/project'",
        Decision.Deny -> "write on '/tmp/project'",
      ),
    )
    assert(rendered.contains("[permissions:"), rendered)
    assert(rendered.contains("the user allowed commands gti status once (this call only"), rendered)
    assert(rendered.contains("the user allowed read on '/tmp/project' for the rest of this session"), rendered)
    assert(rendered.contains("the user denied write on '/tmp/project'"), rendered)
    assert(rendered.endsWith(")]"), rendered)
