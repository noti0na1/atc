// The REPL's `Rendering` is `private[repl]`, so a subclass has to live in its
// package. This is the only file outside `atc.*`; keep it minimal.
package dotty.tools.repl

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Denotations.Denotation
import dotty.tools.dotc.reporting.Diagnostic
import dotty.vendored.fansi

/** Renders REPL values (`val x: T = ...` echoes) like the stock REPL but cuts
  * renderings longer than `maxChars`. Agent code tends to bind whole files and
  * process outputs to top-level `val`s; echoing them in full duplicates what
  * was already printed and floods the model's context.
  *
  * It also records whether an evaluation threw: the REPL reports an uncaught
  * exception by rendering its trace as ordinary output, through
  * [[renderError]], so this is the one place that sees the fact. */
final class CappedRendering(parent: Option[ClassLoader], maxChars: Int) extends Rendering(parent):
  /** Set when an evaluation threw since the last [[resetThrew]]. */
  @volatile var threw: Boolean = false
  def resetThrew(): Unit = threw = false

  override def renderError(thr: Throwable, d: Denotation)(using Context): Diagnostic =
    threw = true
    super.renderError(thr, d)

  override def replStringOf(value: Object, prefixLength: Int)(using Context): fansi.Str =
    val full = super.replStringOf(value, prefixLength)
    if full.length <= maxChars then full
    else
      val text = full.plainText // same length as `full`
      // Do not cut in the middle of a surrogate pair (an emoji, for instance).
      val cut = if maxChars > 0 && Character.isHighSurrogate(text.charAt(maxChars - 1)) then maxChars - 1 else maxChars
      fansi.Str(text.take(cut) + s"… [${text.length - cut} more characters not shown; println the value to see all]")
