package atc.agent

import atc.llm.{Msg, ToolResult}

/** Stores conversation history and pending notes, and repairs incomplete
  * message sequences after failed or interrupted rounds. */
private[agent] final class Conversation:
  private var messages: List[Msg] = Nil
  private var pendingNotes: List[String] = Nil
  private var requests = Vector.empty[String]

  def history: List[Msg] = messages
  def userRequests: List[String] = requests.toList
  def notes: List[String] = pendingNotes

  private def remember(input: String): Unit =
    if input.nonEmpty then
      requests :+= input.take(8000)
      if requests.size > 9 then requests = requests.take(1) ++ requests.takeRight(8)

  def queueNote(note: String): Unit = pendingNotes :+= note

  def beginTurn(input: String): Unit =
    remember(input)
    messages :+= Msg.User(AgentMessages.userMessage(pendingNotes, input))
    pendingNotes = Nil

  def append(message: Msg): Unit = messages :+= message

  /** Add a user correction only after pending tool calls have matching results. */
  def steer(input: String): Unit =
    remember(input)
    messages.lastOption match
      case Some(Msg.User(text)) => messages = messages.init :+ Msg.User(s"$text\n\n$input")
      case Some(_: Msg.ToolResults | _: Msg.Continuation) =>
        append(Msg.Assistant("[paused to apply the user's update]", Nil, None))
        append(Msg.User(input))
      case _ => append(Msg.User(input))

  /** Replace only what the model will see after context fitting. */
  def useHistory(history: List[Msg]): Unit = messages = history

  def clear(): Unit =
    messages = Nil
    pendingNotes = Nil
    requests = Vector.empty

  def restore(history: List[Msg], queued: List[String], inputs: List[String]): Unit =
    messages = history
    pendingNotes = queued
    requests = inputs.toVector

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
