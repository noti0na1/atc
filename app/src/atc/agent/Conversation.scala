package atc.agent

import atc.llm.{Msg, ToolResult}

/** Stores conversation history and pending notes, and repairs incomplete
  * message sequences after failed or interrupted rounds. */
private[agent] final class Conversation:
  import Conversation.*

  private var messages: List[Msg] = Nil
  private var pendingNotes: List[String] = Nil
  private var requests = Vector.empty[String]

  def history: List[Msg] = messages

  /** The first user request of the conversation and up to [[RecentRequests]] recent ones. */
  def userRequests: List[String] = requests.toList

  def notes: List[String] = pendingNotes

  private def remember(input: String): Unit =
    if input.nonEmpty then
      requests :+= AgentMessages.takeChars(input, MaxRequestChars)
      if requests.size > RecentRequests + 1 then requests = requests.take(1) ++ requests.takeRight(RecentRequests)

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
      case Some(Msg.User(text)) =>
        messages = messages.init :+ Msg.User(List(text, input).filter(_.nonEmpty).mkString("\n\n"))
      case Some(_: Msg.ToolResults | _: Msg.Continuation) =>
        append(Msg.Assistant(AgentMessages.pausedForUpdate, Nil, None))
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

  /** Close whichever protocol edge a failed round left open, since providers reject
    * consecutive user messages and tool requests without matching results. Pending
    * tool requests each get an error result; a trailing user message or continuation
    * gets an assistant marker. A history that already ends with an assistant message
    * or tool results needs no repair, and another assistant message would be invalid. */
  def repairAfter(error: Throwable): Unit =
    val marker = AgentMessages.turnFailed(error)
    messages.lastOption match
      case Some(Msg.Assistant(_, calls, _)) if calls.nonEmpty =>
        append(Msg.ToolResults(calls.map(call => ToolResult(call.id, marker, isError = true))))
      case Some(Msg.User(_) | Msg.Continuation(_)) =>
        append(Msg.Assistant(marker, Nil, None))
      case _ => ()

  /** Close an interrupted user/tool-result edge. A round paused by the provider
    * already ends with an assistant message, and a second one would break the role
    * alternation that neutral replays require. */
  def interrupt(): Unit =
    if !messages.lastOption.exists(_.isInstanceOf[Msg.Assistant]) then
      append(Msg.Assistant(AgentMessages.interrupted, Nil, None))

private[agent] object Conversation:
  /** Recent user requests kept besides the first one. */
  val RecentRequests = 8

  /** Characters kept of each remembered user request. */
  val MaxRequestChars = 8000
