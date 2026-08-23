package atc.ui

/** The terminal escape sequences the UI writes, by hand: JLine's `toAnsi`
  * would rewrite box-drawing glyphs (into the DEC charset or plain ASCII).
  * The named codes are the SGR parameters the front-end uses, by role. */
object Ansi:
  val Esc = "\u001b"
  val Reset: String = Esc + "[0m"
  /** Back to the start of the current line and clear it. */
  val ClearLine: String = "\r" + Esc + "[2K"

  val Bold = 1
  val Dim = 2
  val Red = 31
  val Green = 32
  val Yellow = 33 // warnings, permissions, classified
  val Blue = 34 // TODO panel, fenced code in prose
  val Magenta = 35 // tool calls
  val Cyan = 36 // the user, questions, inline code in prose

  /** The SGR sequence selecting `codes`. */
  def sgr(codes: Int*): String = s"$Esc[${codes.mkString(";")}m"

  /** `s` wrapped in `codes` and a reset; the empty string stays empty. */
  def styled(s: String, codes: Int*): String = if s.isEmpty then s else sgr(codes*) + s + Reset

  /** Matches one SGR sequence. */
  val Sgr = """\u001b\[[0-9;]*m""".r

  /** Strip terminal control from untrusted text (model prose, program output,
    * file content, paths): C0 controls except `\n` and `\t`, DEL, and C1. ESC is
    * dropped outright, so even an escape sequence straddling two streamed chunks
    * dies with it. Applied where such text enters the TUI, never to the TUI's
    * own styled output. */
  def sanitize(s: String): String =
    if !s.exists(isControl) then s
    else s.filterNot(isControl)

  private def isControl(c: Char): Boolean =
    (c < ' ' && c != '\n' && c != '\t') || c == '\u007f' || (c >= '\u0080' && c <= '\u009f')
