package atc.ui

import org.jline.utils.NonBlockingReader

import java.io.IOException
import java.util.Locale

import Ansi.{Bold, Cyan, Dim}

/** A key a menu acts on, decoded from the terminal's bytes. */
private[ui] enum MenuKey:
  case Up, Down, PageUp, PageDown, Home, End, Enter, Space, Backspace, Escape, Cancel, FocusIn, FocusOut, Ignore
  /** The start and end of a bracketed paste. */
  case PasteStart, PasteEnd
  case Text(text: String)

private[ui] object MenuKey:
  /** The key that starts with `first`; `next` reads a following byte with a short timeout,
    * which tells an escape sequence from a lone Esc. */
  def decode(first: Int, next: () => Int): MenuKey = first match
    case '\r' | '\n' => Enter
    case ' ' => Space
    case 127 | 8 => Backspace
    case 3 | 4 => Cancel // Ctrl-C, Ctrl-D
    case 16 => Up // Ctrl-P
    case 14 => Down // Ctrl-N
    case 27 =>
      KeyReader.readEscapeSequence(next) match
        case "" => Escape
        case "[A" | "OA" => Up
        case "[B" | "OB" => Down
        case "[5~" => PageUp
        case "[6~" => PageDown
        case "[H" | "OH" | "[1~" | "[7~" => Home
        case "[F" | "OF" | "[4~" | "[8~" => End
        case "[I" => FocusIn
        case "[O" => FocusOut
        case "[200~" => PasteStart
        case "[201~" => PasteEnd
        case _ => Ignore
    case c if c >= 32 => Text(String(Character.toChars(c)))
    case _ => Ignore

/** What a menu shows and where its cursor is, apart from the terminal: a single choice or
  * ticks, a filter typed over a long list, and the window of rows in view. Ticks belong to
  * items, so they survive a change of filter, and the items ticked at first are listed
  * first, so a long list opens on what is chosen. */
private[ui] final class MenuState(
  val labels: IndexedSeq[String],
  val multi: Boolean,
  initial: Int,
  ticked: Set[Int],
):
  /** A list longer than a window is filtered by typing. */
  val filterable: Boolean = labels.size > MenuState.MaxRows
  private val order: IndexedSeq[Int] =
    if multi then labels.indices.filter(ticked) ++ labels.indices.filterNot(ticked) else labels.indices
  private var query = ""
  private var shown: IndexedSeq[Int] = order
  /** The cursor, as a position in `shown`, and the first position in view. */
  private var at = initial.max(0).min((labels.size - 1).max(0))
  /** The first position in view; `-1` until the first window, which centres the cursor. */
  private var first = -1
  var checked: Set[Int] = ticked

  def filter: String = query
  def matches: IndexedSeq[Int] = shown
  /** The item under the cursor, if any matches. */
  def current: Option[Int] = shown.lift(at)

  /** Act on `key`, with `page` rows in view. */
  def apply(key: MenuKey, page: Int): MenuState.Outcome =
    import MenuKey.*
    import MenuState.Outcome.*
    def move(to: Int): MenuState.Outcome =
      if shown.nonEmpty then at = to.max(0).min(shown.size - 1)
      Continue
    def filterBy(text: String): MenuState.Outcome =
      if filterable then refilter(text)
      Continue
    key match
      case Up => move(if at > 0 then at - 1 else shown.size - 1)
      case Down => move(if at < shown.size - 1 then at + 1 else 0)
      case PageUp => move(at - page.max(1))
      case PageDown => move(at + page.max(1))
      case Home => move(0)
      case End => move(shown.size - 1)
      case Enter => if multi then Chosen(checked.toList.sorted) else current.fold(Continue)(i => Chosen(List(i)))
      case Space if multi =>
        current.foreach(i => checked = if checked(i) then checked - i else checked + i)
        Continue
      case Space => filterBy(query + " ")
      case Text(text) => filterBy(query + text)
      case Backspace =>
        val drop = if query.length >= 2 && query.last.isLowSurrogate then 2 else 1
        if query.nonEmpty then refilter(query.dropRight(drop))
        Continue
      case Escape =>
        if query.isEmpty then Cancelled
        else
          refilter("")
          Continue
      case Cancel => Cancelled
      case FocusIn | FocusOut | Ignore | PasteStart | PasteEnd => Continue

  /** Show the items whose label holds every word of `text`, keeping the cursor on its item. */
  private def refilter(text: String): Unit =
    val item = current
    query = text
    val words = text.toLowerCase(Locale.ROOT).split("\\s+").toList.filter(_.nonEmpty)
    shown = order.filter(i => words.forall(labels(i).toLowerCase(Locale.ROOT).contains))
    at = item.map(shown.indexOf).filter(_ >= 0).getOrElse(0)
    first = 0

  /** The positions in view with `rows` rows: `[from, until)` of `matches`, around the cursor. */
  def window(rows: Int): (Int, Int) =
    val size = rows.max(1)
    if first < 0 then first = at - size / 2
    if at < first then first = at
    if at >= first + size then first = at - size + 1
    first = first.min((shown.size - size).max(0)).max(0)
    (first, (first + size).min(shown.size))

private[ui] object MenuState:
  /** Rows a list shows at most; a longer one scrolls and is filtered by typing. */
  val MaxRows = 12

  enum Outcome:
    case Continue, Cancelled
    case Chosen(items: List[Int])

/** A menu in the terminal: a title, then a window of rows that scrolls with the cursor,
  * a filter line for a long list, and the keys in the footer (or under the rows when there
  * is no footer). A single choice ends on Enter; ticks are made with Space and kept with
  * Enter. Esc clears the filter, then leaves. The menu is drawn in a live region, kept
  * shorter than the screen, and replaced by one line saying what was chosen. */
private[ui] final class ListMenu(screen: Screen, status: StatusLine, alerts: Alerts):
  import screen.styled

  /** The chosen items, `None` when the menu was left without a choice. Every row starts
    * with `indent`; an empty `title` draws no title row, for a menu under its question. */
  def run(
    title: String,
    labels: List[String],
    multi: Boolean,
    initial: Int,
    checked: Set[Int],
    escape: String,
    indent: String = "",
  ): Option[List[Int]] =
    if labels.isEmpty then None
    else
      val state = MenuState(labels.toIndexedSeq, multi, initial, checked)
      val keys = ListMenu.hint(state, escape)
      val region = screen.synchronized:
        screen.stopSpinner()
        screen.ensureNewline()
        screen.LiveRegion()
      var page = MenuState.MaxRows
      def draw(force: Boolean = false): Unit = screen.frame:
        val (lines, rows) = ListMenu.render(state, title, keys, !status.shown, screen.height, screen, indent)
        page = rows
        region.redraw(lines, force)
      val outcome = status.withHint(keys):
        alerts.withFocusReports:
          val saved = screen.terminal.enterRawMode()
          screen.frame(screen.writeStyle(Ansi.Esc + "[?25l"))
          try
            draw()
            read(state, () => page, draw)
          finally
            screen.terminal.setAttributes(saved)
            screen.frame(screen.writeStyle(Ansi.Esc + "[?25h"))
      screen.frame:
        outcome match
          case MenuState.Outcome.Chosen(items) =>
            val answer =
              if multi then Format.plural(items.size, "item") + " ticked" else items.headOption.fold("")(labels(_))
            val line =
              if title.isEmpty then indent + styled(s"› ${Ansi.sanitize(answer)}", Cyan)
              else
                indent + styled("? ", Cyan, Bold) + Ansi.sanitize(title) + styled(s" › ${Ansi.sanitize(answer)}", Cyan)
            region.redraw(List(line), force = true)
            region.freeze()
          case _ => region.clear()
      outcome match
        case MenuState.Outcome.Chosen(items) => Some(items)
        case _ => None

  /** Read keys until the menu ends. A resize while it waits is redrawn at the new size.
    * Nothing pasted is a key, and Enter counts only [[ListMenu.EnterDelayNanos]] after
    * the menu opened: a newline typed or pasted just before must not choose the first
    * row, which in a permission request allows it. */
  private def read(state: MenuState, page: () => Int, draw: Boolean => Unit): MenuState.Outcome =
    val in = screen.terminal.reader()
    def next(timeout: Long) =
      try in.read(timeout)
      catch case _: IOException => -1
    val opened = System.nanoTime()
    var pasting = false
    var size = (screen.width, screen.height)
    var outcome: MenuState.Outcome = MenuState.Outcome.Continue
    while outcome == MenuState.Outcome.Continue do
      next(100L) match
        case NonBlockingReader.READ_EXPIRED =>
          if (screen.width, screen.height) != size then
            size = (screen.width, screen.height)
            draw(true)
        case c if c < 0 => outcome = MenuState.Outcome.Cancelled
        case c =>
          MenuKey.decode(c, () => next(30L)) match
            case MenuKey.PasteStart => pasting = true
            case MenuKey.PasteEnd => pasting = false
            case _ if pasting => ()
            case MenuKey.Enter if System.nanoTime() - opened < ListMenu.EnterDelayNanos => ()
            case MenuKey.FocusIn => alerts.focusChanged(true)
            case MenuKey.FocusOut => alerts.focusChanged(false)
            case key =>
              outcome = state(key, page())
              if outcome == MenuState.Outcome.Continue then draw(false)
    outcome

private[ui] object ListMenu:
  /** How long after a menu opens Enter is ignored. */
  val EnterDelayNanos: Long = 300_000_000L

  /** The keys a menu takes, for the footer. */
  def hint(state: MenuState, escape: String): String =
    val choose = if state.multi then "Space tick · Enter save" else "Enter choose"
    val filter = if state.filterable then " · type to filter" else ""
    s"Arrows move · $choose$filter · Esc $escape"

  /** The menu's lines for a terminal of `height` rows, and how many item rows are in view.
    * The region cuts each line to one row. The window leaves room for the title, the filter
    * line, the rows out of view and the footer, so the whole menu stays shorter than the screen. */
  def render(
    state: MenuState,
    title: String,
    keys: String,
    keysInMenu: Boolean,
    height: Int,
    screen: Screen,
    indent: String = "",
  ): (List[String], Int) =
    import screen.{g, styled}
    val total = state.labels.size
    val shown = state.matches
    val ticked = Option.when(state.multi)(s"${g.dot} ${state.checked.size} of $total ticked")
    val head =
      if title.isEmpty then ticked.map(styled(_, Dim))
      else
        Some(styled("? ", Cyan, Bold) + styled(Ansi.sanitize(title), Bold) + ticked.fold("")(t => styled(s"  $t", Dim)))
    val filterLine = Option.when(state.filterable):
      if state.filter.isEmpty then styled(s"  Type to filter ${g.dot} $total items", Dim)
      else "  Filter: " + styled(Ansi.sanitize(state.filter), Cyan) + styled(s"  ${g.dot} ${shown.size} of $total", Dim)
    val fixed = head.size + filterLine.size + (if keysInMenu then 1 else 0) +
      2 // title, filter, keys, the rows out of view
    val rows = (height - fixed - 3).min(MenuState.MaxRows).max(1) // and the footer, the line under, a margin
    val (from, until) = state.window(rows)
    val scrolls = shown.size > rows
    val above = Option.when(scrolls)(
      if from > 0 then styled(s"  ${g.above} ${from} more", Dim) else ""
    )
    val below = Option.when(scrolls)(
      if until < shown.size then styled(s"  ${g.below} ${shown.size - until} more", Dim) else ""
    )
    val items =
      if shown.isEmpty then List(styled("  No matches", Dim))
      else
        (from until until).toList.map: position =>
          val item = shown(position)
          val here = state.current.contains(item)
          val pointer = if here then styled(g.pointer, Cyan, Bold) else " " * Screen.displayWidth(g.pointer)
          val box =
            if !state.multi then ""
            else (if state.checked(item) then styled(g.ticked, Cyan) else g.unticked) + " "
          val label = Ansi.sanitize(state.labels(item))
          s"$pointer $box" + (if here then styled(label, Cyan, Bold) else label)
    val lines =
      head.toList ++ filterLine.toList ++ above.toList ++ items ++ below.toList ++
        Option.when(keysInMenu)(styled(s"  $keys", Dim)).toList
    (lines.map(line => if line.isEmpty then line else indent + line), rows)
