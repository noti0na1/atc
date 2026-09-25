package atc.ui

import atc.host.FileChange
import atc.sandbox.{ExecutionResult, ReplSession}

import scala.collection.mutable

import Ansi.{Bold, Cyan, Dim, Green, Magenta, Red, Yellow}

/** The block of one tool call: its code, the program output shown live ([[LiveOutput]]),
  * then what the REPL returned, the files it changed and the verdict. Results are kept
  * for `/output` in [[history]]. Called with the screen's lock held. */
private[ui] final class ToolBlock(screen: Screen, expanded: () => Boolean):
  import screen.{GutterWidth, Indent, ensureNewline, g, gutter, plain, styled, width, write}

  /** Recent results for `/output`. */
  val history: ToolHistory = ToolHistory()
  /** A tool block is open; `outputStarted` once the first program output line appeared. */
  private var open = false
  private var outputStarted = false
  /** Agent-visible text printed during the current tool call, as it appears in
    * the REPL's captured output; `end` subtracts it from the result panel. */
  private val printed = StringBuilder()
  private val liveCaptured = StringBuilder()
  private var liveTruncated = false
  private val fileChanges = mutable.ListBuffer[FileChange]()
  private var currentCode = ""
  private val live = LiveOutput(screen, () => plain || expanded() || !open)

  def isOpen: Boolean = open

  /** Open a code block titled `title`. */
  def start(code: String, title: String): Unit =
    write(styled(g.bullet, Magenta) + " " + styled(title, Magenta, Bold) + "\n")
    // The code is model-written: sanitize before highlighting/printing.
    val clean = Ansi.sanitize(code)
    val lines = if screen.colors > 0 then Highlight.scala(clean) else clean.linesIterator.toList
    lines.flatMap(line => TextLayout.wrap(line, width - GutterWidth - 1))
      .foreach(line => write(gutter(Magenta) + line + "\n"))
    open = true
    outputStarted = false
    printed.clear()
    liveCaptured.clear()
    liveTruncated = false
    fileChanges.clear()
    currentCode = code
    live.start()

  def fileChanged(change: FileChange): Unit =
    if open && fileChanges.size < 50 then fileChanges += change

  /** The agent's `println`. Classified content, where the two texts differ, is marked so
    * the user knows the model cannot see it. `printed` keeps the raw text, which `end`
    * matches verbatim against the REPL capture; only the display is sanitized. */
  def print(agentText: String, userText: String): Unit =
    // Text beyond the REPL capture limit cannot be subtracted from its result. The
    // limit is in bytes and this length in chars, so the budget is approximate.
    val room = ReplSession.MaxOutputBytes - printed.length
    if room > 0 then printed.append(agentText.take(room))
    if agentText == userText then emit(Ansi.sanitize(userText))
    else emit(styled("[classified] ", Yellow, Bold) + styled(Ansi.sanitize(userText), Yellow))

  /** What a command the agent runs writes, kept for `/output` but not part of the tool result. */
  def commandOutput(text: String): Unit =
    val room = TailBuffer.MaxChars - liveCaptured.length
    if room > 0 then liveCaptured.append(text.take(room))
    if text.length > room then liveTruncated = true
    emit(Ansi.sanitize(text))

  /** Display-ready text in the output section, which the first output opens. */
  def emit(text: String): Unit =
    screen.stopSpinner()
    if open && !outputStarted then
      ensureNewline()
      write(section("output", Dim))
      outputStarted = true
    live.emit(text)

  private def section(label: String, code: Int): String =
    Indent + styled(s"${g.tee} $label", code) + "\n"

  /** Close the block: what the REPL produced besides the agent's own prints (those were
    * shown live), meaning diagnostics, echoed values and exceptions, then the verdict. Long
    * bodies are cut in the middle (unless expanded) so both the first diagnostics and the
    * tail stay visible. */
  def end(r: ExecutionResult, millis: Long): Unit =
    val liveText = liveCaptured.toString + (if liveTruncated then "\n[retained live output limit reached]" else "")
    val record = history.add(currentCode, r, millis, liveText, fileChanges.toList)
    screen.stopSpinner()
    live.end()
    ensureNewline()
    val body =
      List(Option(ExecutionResult.trimStackFrames(r.output)).filter(_.nonEmpty), r.error).flatten.mkString("\n")
    val lines = ToolBlock.withoutPrinted(body, printed.toString).linesIterator.toList
    if lines.nonEmpty then
      // REPL output holds the agent's raw prints and compiler diagnostics: sanitize
      // before display. The subtraction above runs on the raw text, before this.
      val cleaned = lines.map(Ansi.sanitize).flatMap: line =>
        if plain then List(line) else TextLayout.wrap(line, width - GutterWidth - 1)
      val max = ToolBlock.MaxPanelLines
      val kept =
        if cleaned.length <= max || plain || expanded() then cleaned
        else
          cleaned.take(max * 2 / 3) ++
            List(s"${g.ellipsis} ${cleaned.length - max} lines omitted ${g.dot} /output ${record.id}") ++
            cleaned.takeRight(max / 3)
      if r.success then
        write(section("result", Dim))
        kept.foreach(l => write(gutter(Dim) + styled(l, Dim) + "\n"))
      else
        write(section("error", Red))
        kept.foreach(l => write(gutter(Red) + l + "\n"))
    fileChanges.foreach: change =>
      write(Indent + styled(Ansi.sanitize(s"${change.path}: ${change.summary}"), Cyan) + "\n")
    val verdict =
      if r.success then styled(s"${g.end} ok ${millis} ms", Green) else styled(s"${g.end} failed ${millis} ms", Red)
    write(Indent + verdict + styled(s" ${g.dot} /output ${record.id}", Dim) + "\n")
    open = false

  /** The output section is over (a pop-up or the end of the turn): what the fold window shows stays. */
  def endOutput(): Unit = live.end()

  /** The turn is over: what the fold window shows stays, and no block is open. */
  def endTurn(): Unit =
    live.end()
    open = false

  def resize(): Unit = live.resize()

  /** Ctrl-O, first half: take the fold window down and return the text it held back. */
  def detach(): String = live.detach()

  /** Ctrl-O, second half: the expanded view writes out what was held back and everything
    * after it; the compact view folds from here on when output has started. */
  def reattach(heldBack: String): Unit =
    if expanded() then
      if heldBack.nonEmpty then screen.writeGuttered(heldBack, gutter(Dim))
      live.showEverything()
    else if open && outputStarted then live.foldFromHere()

private[atc] object ToolBlock:
  /** Lines of a tool-result section before it is cut in the middle. */
  private val MaxPanelLines = 30

  /** The REPL output without the agent's own prints. The host wrote those to
    * the same stream, so they occur verbatim and contiguously: remove the
    * first occurrence of `printed`. If it cannot be found (truncated capture),
    * the body is shown as is. */
  def withoutPrinted(body: String, printed: String): String =
    val p = printed.stripTrailing()
    if p.isEmpty then body
    else
      val i = body.indexOf(p)
      if i < 0 then body
      else
        val before = body.take(i).stripTrailing()
        val after = body.drop(i + p.length).stripLeading()
        if before.isEmpty then after
        else if after.isEmpty then before
        else before + "\n" + after
