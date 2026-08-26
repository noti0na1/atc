package atc.agent

import atc.llm.{Msg, ToolResult}

/** Owns transcript mutation and the role-alternation invariants expected by
  * model providers. The loop asks for semantic operations instead of editing
  * the message list or assembling protocol markers itself. */
private[agent] final class Conversation:
  private var messages: List[Msg] = Nil
  private var pendingNotes: List[String] = Nil

  def history: List[Msg] = messages

  def queueNote(note: String): Unit = pendingNotes :+= note

  def beginTurn(input: String): Unit =
    messages :+= Msg.User(AgentMessages.userMessage(pendingNotes, input))
    pendingNotes = Nil

  def append(message: Msg): Unit = messages :+= message

  /** Replace only what the model will see after context fitting. */
  def useHistory(history: List[Msg]): Unit = messages = history

  def clear(): Unit =
    messages = Nil
    pendingNotes = Nil

  /** Close whichever protocol edge a failed round left open. */
  def repairAfter(error: Throwable): Unit =
    val marker = AgentMessages.turnFailed(error)
    messages.lastOption match
      case Some(Msg.Assistant(_, calls, _)) if calls.nonEmpty =>
        append(Msg.ToolResults(calls.map(call => ToolResult(call.id, marker, isError = true))))
      case Some(Msg.User(_) | Msg.Continuation(_)) =>
        append(Msg.Assistant(marker, Nil, None))
      case _ => ()

  /** Close an interrupted user/tool-result edge without creating consecutive
    * assistant messages after a provider pause. */
  def interrupt(): Unit =
    if !messages.lastOption.exists(_.isInstanceOf[Msg.Assistant]) then
      append(Msg.Assistant(AgentMessages.interrupted, Nil, None))
