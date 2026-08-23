package atc.host

import atc.lib.*

import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/** User-visible output, questions, TODO state, and model interaction supplied
  * by [[Host]]. */
private[host] trait HostInteraction:
  self: Host =>

  @volatile private var todoList: List[Todo] = Nil

  /** Report a classified computation failure only through the user channel;
    * exposing the failure bit to the agent could reveal classified data. */
  private[atc] def classifiedSinkFailed(operation: String): Unit =
    output.print(
      "",
      s"<$operation failed: the classified value is the result of a failed computation; its error is confidential>\n"
    )

  private def agentView(value: Any): Any = value match
    case _: Classified[?] => "Classified(***)"
    case other => other

  /** Render something that came out of a classified computation for the user.
    *
    * The rendering itself is a classified sink: an agent-defined `toString` (or
    * even a custom `Throwable.getMessage`) can throw an exception containing the
    * secret. Letting that exception escape would put its message in the REPL
    * result, which is visible to the agent. Non-fatal rendering failures are
    * therefore turned into user-only text and the call returns normally. */
  private def classifiedUserText(value: Try[Any]): String =
    def errorText(error: Throwable): String =
      try Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.toString)
      catch case NonFatal(_) => "(error details could not be rendered)"

    value match
      case Success(plain) =>
        try Option(String.valueOf(plain)).getOrElse("null")
        catch case NonFatal(error) => s"<classified rendering failed: ${errorText(error)}>"
      case Failure(error) => s"<classified error: ${errorText(error)}>"

  private def userView(value: Any): Any = value match
    case classified: Classified[?] =>
      // Always return an already-rendered String. In particular, do not hand an
      // agent-defined object to `String.valueOf`/`Formatter` outside this guard.
      classifiedUserText(ClassifiedImpl.unwrap(classified))
    case other => other

  private def emit(value: Any, suffix: String = ""): Unit =
    output.print(String.valueOf(agentView(value)) + suffix, String.valueOf(userView(value)) + suffix)

  def println(value: Any)(using UserIO): Unit = emit(value, "\n")
  def println()(using UserIO): Unit = output.print("\n", "\n")
  def print(value: Any)(using UserIO): Unit = emit(value)
  def printf(format: String, args: Any*)(using UserIO): Unit =
    // Preserve the types of plain arguments for numeric and date conversions.
    output.print(format.format(args.map(agentView)*), format.format(args.map(userView)*))

  def ask(question: String)(using UserIO): Option[String] = ui.askUser(question, Nil, false)
  def ask(question: String, options: List[String])(using UserIO): Option[String] =
    ui.askUser(question, options, false)
  def ask(question: String, options: List[String], multiple: Boolean)(using UserIO): Option[String] =
    ui.askUser(question, options, multiple)

  def setTodos(items: List[Todo])(using UserIO): Unit =
    todoList = items
    ui.showTodos(items)

  def todos(using UserIO): List[Todo] = todoList

  def markTodo(text: String, status: TodoStatus)(using UserIO): Unit =
    if !todoList.exists(_.text == text) then
      throw IllegalArgumentException(s"No TODO item with text '$text'. Current: ${todoList.map(_.text).mkString(", ")}")
    setTodos(todoList.map(todo => if todo.text == text then todo.copy(status = status) else todo))

  private[atc] def currentTodos: List[Todo] = todoList

  private[atc] def clearTodos(): Unit = todoList = Nil

  def classify[T](value: T): Classified[T] = ClassifiedImpl.wrap(value)

  def chat(message: String)(using UserIO): String = llm.chat(message)
  def classifiedChat(message: String): String = llm.classifiedChat(message)
  def classifiedChat(message: Classified[String]): Classified[String] = message.map(classifiedChat)
