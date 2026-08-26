package atc.agent

import atc.llm.{Json, ToolCall, ToolResult, ToolSpec}
import atc.perms.Policy
import atc.sandbox.ReplSession

/** Executes the tools exposed to the model. The agent loop owns when a tool may
  * run; a runner owns the tool-specific decoding and effects. */
private[atc] trait ToolRunner:
  def tools: List[ToolSpec]
  def run(call: ToolCall): ToolResult

/** The model's Scala REPL tool, bound to the sandbox session for one turn. */
private[atc] final class ScalaToolRunner(
  session: ReplSession,
  policy: Policy,
  ui: AgentUI,
  maxOutputChars: Int,
) extends ToolRunner:
  val tools: List[ToolSpec] = ScalaToolRunner.tools

  def run(call: ToolCall): ToolResult = call.name match
    case Prompts.ToolName => runScala(call)
    case other =>
      ToolResult(
        call.id,
        AgentMessages.unknownTool(other, Prompts.ToolName),
        isError = true
      )

  private def runScala(call: ToolCall): ToolResult =
    val code = Json.parseObject(call.arguments).value.get("code").flatMap(_.strOpt).getOrElse("")
    if code.trim.isEmpty then ToolResult(call.id, AgentMessages.missingCodeArgument, isError = true)
    else
      ui.toolStart(code)
      val start = System.nanoTime()
      val decisionsBefore = policy.decisionCount
      val result = session.run(code)
      // Time the snippet spent waiting for the user (prompts, questions) is not execution time.
      val millis = (System.nanoTime() - start - session.clock.paused) / 1_000_000L
      ui.toolEnd(result, millis)
      val rendered = ToolOutput.renderForModel(result, maxOutputChars, policy.decisionsSince(decisionsBefore))
      ToolResult(call.id, rendered, isError = !result.success)

private[atc] object ScalaToolRunner:
  /** The one and only native tool: everything else is a Scala function. */
  val tools: List[ToolSpec] =
    List(ToolSpec(Prompts.ToolName, Prompts.toolDescription, Prompts.toolParameters))
