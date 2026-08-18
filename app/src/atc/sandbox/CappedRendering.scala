// The REPL's `Rendering` is `private[repl]`, so a subclass has to live in its
// package. This is the only file outside `atc.*`; keep it minimal.
package dotty.tools.repl

import dotty.tools.dotc.core.Contexts.Context
import dotty.vendored.fansi

/** Renders REPL values (`val x: T = ...` echoes) like the stock REPL but cuts
  * renderings longer than `maxChars`. Agent code tends to bind whole files and
  * process outputs to top-level `val`s; echoing them in full duplicates what
  * was already printed and floods the model's context. */
final class CappedRendering(parent: Option[ClassLoader], maxChars: Int) extends Rendering(parent):
  override def replStringOf(value: Object, prefixLength: Int)(using Context): fansi.Str =
    val full = super.replStringOf(value, prefixLength)
    if full.length <= maxChars then full
    else
      val note = s"… [${full.length - maxChars} more characters not shown; println the value to see all]"
      fansi.Str(full.plainText.take(maxChars) + note)
