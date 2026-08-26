package atc.agent

import atc.perms.Decision
import atc.sandbox.ExecutionResult

/** Renders one sandbox evaluation as the text returned to the model. */
object ToolOutput:
  /** A hint appended to tool output for a common capture-checking / safe-mode stumble. */
  private case class Hint(applies: String => Boolean, text: String)
  private val hints = List(
    Hint(
      out => out.contains("Cannot refer to object StringBuilder") && out.contains("from safe code"),
      "safe mode rejects the `StringBuilder()` companion call, but construction works: use `new StringBuilder()`; a top-level binding needs `val b: StringBuilder = new StringBuilder()`."
    ),
    Hint(
      _.contains("needs an explicit type because the inferred type does not conform"),
      "top-level vals that hold capabilities (FileEntry, closures using println/fs) need an explicit type, e.g. `val e: FileEntry^{fs} = access(...)`, or use a `def` / inline expression."
    ),
    Hint(
      out => out.contains("Mutable variable") && out.contains("does not extend") && out.contains("Stateful"),
      "safe mode rejects top-level `var`s; put the `var` inside a `def`, block or lambda and return the immutable result."
    ),
    Hint(
      out => out.contains("Cannot refer to") && out.contains("from safe code"),
      "that API is not available in safe mode (only the sandbox API, immutable collections and plain JDK utilities are); e.g. `throw RuntimeException(...)` instead of sys.error, and a local `var` over an immutable `List`/`Vector`/`Map` instead of `ListBuffer`/`mutable.Map`."
    ),
    Hint(
      _.contains("Cannot run program"),
      "that program is not on the PATH (or is misspelt). Note that exec runs no shell: `exec(\"git status\")` is split into words for you, pipes and `<`/`>`/`>>`/`2>&1` work, but `&&`, `;`, globs and `$VAR` do not; run steps one by one and combine in Scala.",
    ),
    Hint(
      out => out.contains("Ambiguous given instances") && out.contains("FileSystem"),
      "do not define your own `given FileSystem`; use requestFiles(...) { ... } blocks."
    ),
    Hint(
      out => out.contains("cannot subsume a read-only capture set") || out.contains("Cannot call update method"),
      "you only have read-only access there: a bare `FileSystem`/`IOCap` type is the read-only view (write `FileSystem^` / `IOCap^` for the full one in your own signatures), and in read-only sandbox mode nothing can write, run commands or use the network. Say so and let the user switch modes (/mode) instead of working around it."
    ),
    Hint(
      out =>
        out.contains("No given instance of type atc.lib.Network") || out.contains(
          "No given instance of type atc.lib.Exec"
        ),
      "that capability does not exist in the current sandbox mode (local: no network; read-only: no commands, no network); tell the user which mode the task needs (/mode local, /mode full)."
    ),
  )

  /** Tool output as the model sees it: hint-annotated and bounded (cut in the
    * middle so both the first diagnostics and the tail survive). */
  def renderForModel(r: ExecutionResult, maxChars: Int): String = renderForModel(r, maxChars, Nil)

  /** The result as the model sees it: the rendered output with a hint for the
    * usual stumbles, cut in the middle beyond `maxChars`, then a note for every
    * decision the user made at a permission prompt during the run. The note
    * comes last and uncut: the model cannot see the pop-ups, so this is how it
    * learns whether a grant was for this call or for the session (and the
    * system prompt never changes with one). */
  def renderForModel(r: ExecutionResult, maxChars: Int, decisions: List[(Decision, String)]): String =
    val base = r.render
    val hinted = hints.find(_.applies(base)).fold(base)(h => s"$base\nHint: ${h.text}")
    val bounded =
      if hinted.length <= maxChars then hinted
      else
        val head = hinted.take(maxChars * 2 / 3)
        val tail = hinted.takeRight(maxChars / 3)
        s"$head\n... [${hinted.length - maxChars} characters omitted] ...\n$tail"
    if decisions.isEmpty then bounded else s"$bounded\n${decisionNote(decisions)}"

  /** What the user decided at the prompts of one call, for the model:
    * `[permissions: the user allowed commands npm * once (this call only; a
    * later call must ask again); the user allowed read on '/x' for the rest of
    * this session (no request needed from now on)]`. */
  def decisionNote(decisions: List[(Decision, String)]): String =
    val parts = decisions.map {
      case (Decision.AllowOnce, what) => s"the user allowed $what once (this call only; a later call must ask again)"
      case (Decision.AllowSession, what) =>
        s"the user allowed $what for the rest of this session (no request needed from now on)"
      case (Decision.Deny, what) => s"the user denied $what (do not ask again for the same thing)"
    }
    s"[permissions: ${parts.mkString("; ")}]"
