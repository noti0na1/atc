package atc.agent

import atc.llm.{Json, ToolCall, ToolResult, ToolSpec}
import atc.perms.{Decision, Policy}
import atc.sandbox.{ExecutionResult, ReplSession}

/** Executes the tools exposed to the model. The agent loop owns when a tool may
  * run; a runner owns the tool-specific decoding and effects. */
private[atc] trait ToolRunner:
  def tools: List[ToolSpec]
  def run(call: ToolCall): ToolResult

/** The model's Scala REPL tool, bound to the sandbox session for one turn. */
private[atc] final class ScalaToolRunner(
  session: => ReplSession,
  policy: Policy,
  ui: AgentUI,
  maxOutputChars: Int,
) extends ToolRunner:
  val tools: List[ToolSpec] = ScalaToolRunner.tools
  private lazy val repl = session

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
      val current = repl // may start the sandbox first: not part of the snippet's time
      val (result, decisions) = ScalaToolRunner.evaluate(current, policy, ui, code)
      val rendered = ToolOutput.renderForModel(result, maxOutputChars, decisions)
      val needsReplan = decisions.exists {
        case (Decision.Revise(_), _) => true
        case _ => false
      }
      ToolResult(call.id, rendered, isError = !result.success, needsReplan = needsReplan)

private[atc] object ScalaToolRunner:
  /** The native Scala tool; other operations are library calls. */
  val tools: List[ToolSpec] =
    List(ToolSpec(Prompts.ToolName, Prompts.toolDescription, Prompts.toolParameters))

  /** Run `code` inside an open tool block (`ui.toolStart` already called): report the result
    * with the evaluation time, minus the time spent waiting for the user at prompts, and
    * return it with what the user decided at those prompts. Shared with the user's `/run`. */
  def evaluate(
    session: ReplSession,
    policy: Policy,
    ui: AgentUI,
    code: String
  ): (ExecutionResult, List[(Decision, String)]) =
    val decisionsBefore = policy.decisionCount
    ui.status("running Scala")
    val start = System.nanoTime()
    val result = session.run(code)
    val millis = (System.nanoTime() - start - session.clock.paused) / 1_000_000L
    ui.toolEnd(result, millis)
    (result, policy.decisionsSince(decisionsBefore))
