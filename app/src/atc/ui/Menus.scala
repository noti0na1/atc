package atc.ui

import atc.perms.Decision

import org.jline.prompt.{CheckboxResult, ListResult, PromptBuilder, PromptResult, PrompterConfig, PrompterFactory}
import org.jline.reader.{EndOfFileException, UserInterruptException}
import org.jline.utils.AttributedString

import java.util.Locale
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** jline-prompt menus. Each returns indices so duplicate display labels do not
  * collapse into the first option, and `None` on Ctrl-C/Ctrl-D. The caller
  * pauses the turn's key reader and holds no screen lock while a menu reads. */
private[ui] final class Menus(screen: Screen, alerts: Alerts):
  import screen.g

  val ListHint = s"Arrows move ${g.dot} Enter confirm ${g.dot} Ctrl-C cancel"
  val CheckboxHint = s"Space toggle ${g.dot} Enter confirm ${g.dot} Ctrl-C cancel"

  /** A single-choice menu. */
  def list(message: String, labels: List[String]): Option[Int] =
    val byId = Menus.uniqueIds(labels).zip(labels)
    run { b =>
      val lp = b.createListPrompt().name("a").message(message)
      // A long list (a provider's models) is paged, and typing filters it.
      if labels.size > Menus.FilterFrom then lp.filterable(true)
      byId.foreach((id, l) => lp.add(id, l))
      lp.addPrompt()
    } {
      case r: ListResult => byId.map(_._1).zipWithIndex.toMap.get(r.getSelectedId)
      case _ => None
    }

  /** A multi-choice menu; `Some(Nil)` if nothing was ticked. */
  def checkbox(message: String, labels: List[String], checked: Set[Int] = Set.empty): Option[List[Int]] =
    val byId = Menus.uniqueIds(labels).zip(labels)
    run { b =>
      val cb = b.createCheckboxPrompt().name("a").message(message)
      if labels.size > Menus.FilterFrom then cb.filterable(true)
      byId.zipWithIndex.foreach { case ((id, l), i) => cb.add(id, l, checked.contains(i)) }
      cb.addPrompt()
    } {
      case r: CheckboxResult =>
        val indices = byId.map(_._1).zipWithIndex.toMap
        Some(r.getSelectedIds.asScala.toList.flatMap(indices.get))
      case _ => None
    }

  /** Run one pop-up named "a" and read its result. jline-prompt echoes the
    * chosen *id* after the message once the user confirms, so menu ids are the
    * visible labels (made unique). */
  private def run[R](define: PromptBuilder => Unit)(read: PromptResult[?] => Option[R]): Option[R] =
    screen.synchronized(screen.flush())
    val config = if g == Glyphs.ascii then PrompterConfig.windows() else PrompterConfig.defaults()
    val prompter = PrompterFactory.create(screen.terminal, config.withCancellableFirstPrompt(true))
    val builder = prompter.newBuilder()
    define(builder)
    alerts.withoutFocusReports:
      try Option(prompter.prompt(List.empty[AttributedString].asJava, builder.build()).get("a")).flatMap(read)
      catch case _: UserInterruptException | _: EndOfFileException => None
        // The prompter redraws the answer, clears the menu below it and prints one
        // more newline (`DefaultPrompter.close`), so the cursor is already past a
        // blank line: tell `blankLine` so the block does not get a second one.
      finally screen.tail = "\n\n"

private[atc] object Menus:
  /** Lists longer than this can be filtered by typing. */
  val FilterFrom = 12

  val AllowOnce = "Allow once"
  val AllowSession = "Allow for this session"
  val DenyLabel = "Deny this request"
  val ReviseLabel = "Tell the agent what to change"
  val OtherLabel = "Write a different answer"
  val AddAnswerLabel = "Add an answer or instructions"
  val YesLabel = "Yes"
  val NoLabel = "No"

  /** Menu ids are the labels where possible; collisions get numeric suffixes
    * that are themselves checked (so `a`, `a (1)`, `a` still stays unique). */
  def uniqueIds(labels: List[String]): List[String] =
    val used = mutable.Set[String]()
    labels.map: l =>
      var n = 0
      var candidate = l
      while used.contains(candidate) do
        n += 1
        candidate = s"$l ($n)"
      used += candidate
      candidate

  /** Plain permission prompts accept exact approvals; every other answer is feedback. */
  private[atc] def permissionReply(answer: Option[String]): Decision =
    answer.map(_.trim).filter(_.nonEmpty) match
      case None => Decision.Deny
      case Some(text) => text.toLowerCase(Locale.ROOT) match
          case "y" | "yes" => Decision.AllowOnce
          case "s" | "session" => Decision.AllowSession
          case "n" | "no" => Decision.Deny
          case _ => Decision.Revise(text)
