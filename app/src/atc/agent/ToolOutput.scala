package atc.agent

import atc.perms.Decision
import atc.sandbox.ExecutionResult

/** Renders one sandbox evaluation as the text returned to the model. */
object ToolOutput:
  /** A hint appended to tool output for a common capture-checking or safe-mode error. */
  private final case class Hint(applies: String => Boolean, text: String)
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
      "The program could not be started. Check PATH, the executable and interpreter, permissions and working directory. exec runs no shell: `exec(\"git status\")` is split into words for you, pipes and `<`/`>`/`>>`/`2>&1` work, but `&&`, `;`, globs and `$VAR` do not; run steps one by one and combine in Scala.",
    ),
    Hint(
      _.contains("NotDirectoryException"),
      "that path is a file, not a directory: use `cat`/`read` on it, or `ls`/`walk`/`find` on its parent."
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
      out => List("Network", "Exec").exists(name => out.contains(s"No given instance of type atc.lib.$name")),
      "that capability does not exist in the current sandbox mode (local: no network; read-only: no commands, no network); tell the user which mode the task needs (/mode local, /mode full)."
    ),
  )

  /** The result as the model sees it: the rendered output with a hint for common
    * errors, cut in the middle beyond `maxChars` so both the first diagnostics and
    * the tail survive. After the cut come a hint keyed on the snippet `code` itself
    * ([[codeHint]]), since it concerns what the snippet wrote rather than what it
    * printed, and a note for every decision the user made at a permission prompt
    * during the run. The model cannot see the prompts and the system prompt never
    * changes with a grant, so this note is how it learns whether a grant was for
    * this call or for the session. */
  def renderForModel(
    result: ExecutionResult,
    maxChars: Int,
    decisions: List[(Decision, String)] = Nil,
    code: String = "",
  ): String =
    val base = result.render
    // Hints key on diagnostics, which only a failed run carries. A successful run's
    // output is data the model asked for, and a file that quotes a compiler message
    // (this project's own docs do) must not earn the hint for that message.
    val hinted =
      if result.success then base else hints.find(_.applies(base)).fold(base)(h => s"$base\nHint: ${h.text}")
    val bounded =
      if hinted.length <= maxChars then hinted
      else
        val head = AgentMessages.takeChars(hinted, maxChars * 2 / 3)
        val tail = AgentMessages.takeRightChars(hinted, maxChars / 3)
        s"$head\n... [${hinted.length - head.length - tail.length} characters omitted] ...\n$tail"
    val withCodeHint = codeHint(code).fold(bounded)(h => s"$bounded\nHint: $h")
    if decisions.isEmpty then withCodeHint else s"$withCodeHint\n${decisionNote(decisions)}"

  /** Warn when an escaped quote would be written literally from a plain triple-quoted string. */
  private def codeHint(code: String): Option[String] =
    Option.when(hasEscapedQuoteInRawLiteral(code))(
      "inside a plain triple-quoted literal `\\\"` is two characters, a backslash and a quote, so the text " +
        "you wrote contains backslashes; to put `\"\"\"` in text, prefix the literal with `s` (`s\"\"\"...\"\"\"` " +
        "processes escapes; write `$` as `$$` in it) or use an ordinary `\"...\"` string."
    )

  /** Whether `code` has a triple-quoted literal without an interpolator prefix whose body
    * contains `\"`. A prefix (`s`, `f`, `raw`) is an identifier character right before the
    * opening quotes; `raw` keeps the backslash too, but is a deliberate choice. */
  private[atc] def hasEscapedQuoteInRawLiteral(code: String): Boolean =
    val quotes = "\"\"\""
    var i = 0
    var found = false
    while !found && i < code.length do
      if code.startsWith(quotes, i) then
        val prefixed = i > 0 && (Character.isLetterOrDigit(code.charAt(i - 1)) || code.charAt(i - 1) == '_')
        val end = code.indexOf(quotes, i + 3)
        val stop = if end < 0 then code.length else end
        if !prefixed && code.substring(i + 3, stop).contains("\\\"") then found = true
        i = if end < 0 then code.length else end + 3
      else i += 1
    found

  /** What the user decided at the prompts of one call, for the model:
    * `[permissions: the user allowed commands npm * once (this call only; a
    * later call must ask again); the user allowed read on '/x' for the rest of
    * this session (no request needed from now on)]`. */
  private def decisionNote(decisions: List[(Decision, String)]): String =
    val parts = decisions.map:
      case (Decision.AllowOnce, what) => s"the user allowed $what once (this call only; a later call must ask again)"
      case (Decision.AllowSession, what) =>
        s"the user allowed $what for the rest of this session (no request needed from now on)"
      case (Decision.Deny, what) =>
        s"the user denied $what (this request was not approved; do not repeat it unchanged or infer a permanent ban on every item)"
      case (Decision.Revise(instructions), what) =>
        s"the user requested changes to $what (no permission granted). User instructions: ${ujson.write(instructions)}. " +
          "Revise the plan to follow these instructions and request only the permissions still needed; this is not a blanket denial"
    s"[permissions: ${parts.mkString("; ")}]"
