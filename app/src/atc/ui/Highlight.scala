package atc.ui

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.printing.SyntaxHighlighting

import scala.util.control.NonFatal

/** Scala syntax colouring, done by the compiler's own scanner-based
  * highlighter (already on the classpath for the REPL). Output is split into
  * lines that each carry their own colour state, so a gutter can be put in
  * front of every line without breaking a multi-line string or comment. */
object Highlight:
  import Ansi.{Esc, Reset, Sgr}

  private lazy val ctx: Context =
    val base = new ContextBase
    base.initialCtx.fresh.setSetting(base.initialCtx.settings.color, "always")

  /** ANSI-coloured lines of `code`; the plain lines if highlighting fails. */
  def scala(code: String): List[String] =
    val ansi =
      try synchronized(SyntaxHighlighting.highlight(code)(using ctx))
      catch
        case NonFatal(_) => code
    // The compiler paints comments blue, which is unreadable on dark backgrounds: dim them instead.
    splitCarrying(ansi.replace(Esc + "[34m", Esc + "[2m"))

  /** Split ANSI text into lines, re-opening on each line the colour that was
    * active at the end of the previous one and closing it at the line end. */
  def splitCarrying(ansi: String): List[String] =
    var active = ""
    val lines = ansi.split("\n", -1).toList.map { line =>
      val prefix = active
      Sgr.findAllIn(line).foreach(seq => active = if seq == Reset then "" else seq)
      val suffix = if active.nonEmpty then Reset else ""
      prefix + line + suffix
    }
    if ansi.endsWith("\n") then lines.dropRight(1) else lines // no phantom line for the trailing newline
