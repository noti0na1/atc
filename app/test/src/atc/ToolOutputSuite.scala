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
    val listed =
      ToolOutput.renderForModel(ExecutionResult(false, "java.nio.file.NotDirectoryException: /x/a.txt"), 10000)
    assert(listed.contains("Hint:") && listed.contains("not a directory"), listed)

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

  test("permission feedback reaches the model in full even when execution output is truncated"):
    val instructions = "Skip the fifth command.\nRequest only the other four, with a \"test\" scope."
    val rendered = ToolOutput.renderForModel(
      ExecutionResult(false, "x" * 1000),
      20,
      List(Decision.Revise(instructions) -> "commands one, two, three, four, five"),
    )
    assert(rendered.contains("characters omitted"), rendered)
    assert(rendered.contains("no permission granted"), rendered)
    assert(rendered.contains(ujson.write(instructions)), rendered)
    assert(rendered.contains("request only the permissions still needed"), rendered)
    assert(!rendered.contains("the user denied"), rendered)

  test("a denial tells the model what it may and may not conclude"):
    val out = ToolOutput.renderForModel(ExecutionResult(true, "ok"), 10000, List(Decision.Deny -> "write on '/x'"))
    assert(out.contains("the user denied write on '/x'"), out)
    assert(out.contains("do not repeat it unchanged"), out)
    assert(out.contains("or infer a permanent ban on every item"), out)

  test("each common capture-checking or safe-mode error gets its own hint"):
    def hintFor(output: String): String = ToolOutput.renderForModel(ExecutionResult(false, output), 10000)

    val explicitType = hintFor("value e needs an explicit type because the inferred type does not conform to ...")
    assert(explicitType.contains("explicit type"), explicitType)
    assert(explicitType.contains("FileEntry^{fs}"), explicitType)

    val safeMode = hintFor("Cannot refer to object ArrayBuffer ... from safe code since it is neither ...")
    assert(safeMode.contains("not available in safe mode"), safeMode)

    val builder = hintFor("Cannot refer to object StringBuilder ... from safe code since it is neither ...")
    assert(builder.contains("new StringBuilder()"), builder)
    assert(builder.contains("val b: StringBuilder"), builder)

    val variable = hintFor("Mutable variable counter is defined in a class that does not extend Stateful")
    assert(variable.contains("top-level `var`"), variable)
    assert(variable.contains("inside a `def`"), variable)

    val ambiguous = hintFor("Ambiguous given instances: both fs and fs2 match type FileSystem ...")
    assert(ambiguous.contains("requestFiles"), ambiguous)

    List("... cannot subsume a read-only capture set ...", "... Cannot call update method ...").foreach(output =>
      assert(hintFor(output).contains("/mode"), output)
    )
    List("No given instance of type atc.lib.Network ...", "No given instance of type atc.lib.Exec ...").foreach(
      output => assert(hintFor(output).contains("/mode"), output)
    )
