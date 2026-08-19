package atc.ui

/** The characters that draw the layout; ASCII when the terminal is not UTF-8
  * (or `ATC_ASCII` is set). Always constructed with named arguments — a bare
  * list of eighteen one-character strings says nothing. */
final case class Glyphs(
  /** Opens a block: prose, tool call, thinking. */
  bullet: String,
  /** The gutter of a block's body. */
  bar: String,
  /** Opens a section inside a block ("output", "result"). */
  tee: String,
  /** Closes a tool block (the verdict line). */
  end: String,
  arrow: String,
  warn: String,
  cross: String,
  todo: String,
  done: String,
  inProgress: String,
  pending: String,
  /** Markdown list bullet (prose, not blocks). */
  bullet2: String,
  /** Markdown block quote bar. */
  quote: String,
  /** Horizontal rule, also table rules. */
  rule: String,
  ellipsis: String,
  /** Separator in summary lines ("worked for 3 s · 2 tool calls"). */
  dot: String,
  /** Table header/rule crossing. */
  junction: String,
  spinner: IndexedSeq[String]
)
object Glyphs:
  val unicode: Glyphs = Glyphs(
    bullet = "●",
    bar = "│",
    tee = "├",
    end = "└",
    arrow = "→",
    warn = "⚠",
    cross = "✗",
    todo = "▸",
    done = "✓",
    inProgress = "▶",
    pending = "○",
    bullet2 = "•",
    quote = "▎",
    rule = "─",
    ellipsis = "…",
    dot = "·",
    junction = "┼",
    spinner = IndexedSeq("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"),
  )
  val ascii: Glyphs = Glyphs(
    bullet = "*",
    bar = "|",
    tee = "+",
    end = "`",
    arrow = "->",
    warn = "!",
    cross = "x",
    todo = ">",
    done = "[x]",
    inProgress = "[>]",
    pending = "[ ]",
    bullet2 = "-",
    quote = ">",
    rule = "-",
    ellipsis = "...",
    dot = "-",
    junction = "+",
    spinner = IndexedSeq("|", "/", "-", "\\"),
  )
