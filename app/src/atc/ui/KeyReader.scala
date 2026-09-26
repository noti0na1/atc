package atc.ui

import org.jline.terminal.{Attributes, Terminal}
import org.jline.utils.NonBlockingReader

import java.io.IOException

/** During turns, read corrections and Ctrl-O in raw mode on a thread of its own.
  * Enter submits a correction (`onSubmit`); unsent text is reported as a draft
  * (`onDraft`) and handed to the next prompt. Pop-ups take exclusive control of
  * input with `withPaused`. */
private[ui] final class KeyReader(
  terminal: Terminal,
  plain: Boolean,
  alerts: Alerts,
  onToggle: () => Unit,
  onSubmit: String => Unit,
  onDraft: String => Unit,
):
  private val out = terminal.writer()
  private var thread: Option[Thread] = None
  private var saved: Option[Attributes] = None
  @volatile private var running = false
  /** The pop-up handshake: both guarded by pauseLock. A pop-up may not read
    * while the key thread is inside `read`, and the key thread may not start
    * a read once a pop-up asked for the pause. Every change of `reading`,
    * `pauseDepth` and `running` notifies, so the waits below have no timeout. */
  private val pauseLock = Object()
  private var pauseDepth = 0
  private var reading = false
  private val typeAhead = StringBuilder()

  def start(): Unit = if !plain && thread.isEmpty then
    saved = Some(terminal.enterRawMode())
    alerts.reportFocus(true)
    out.print(Ansi.Esc + "[?2004h")
    out.flush()
    running = true
    val t = Thread(() => loop(), "atc-keys")
    t.setDaemon(true)
    thread = Some(t)
    t.start()

  /** Stop the thread and restore the terminal. Call without the screen's lock:
    * the key thread may be waiting for it. */
  def stop(): Unit =
    running = false
    pauseLock.synchronized(pauseLock.notifyAll())
    thread.foreach(t => t.join(2500))
    thread = None
    // Still in raw mode: a report arriving now waits unechoed for the next reader.
    alerts.reportFocus(false)
    saved.foreach(terminal.setAttributes)
    saved = None
    if !plain then
      out.print(Ansi.Esc + "[?2004l")
      out.flush()

  /** Run `body` with the key thread idle (a pop-up is about to read the terminal). */
  def withPaused[T](body: => T): T =
    pauseLock.synchronized { pauseDepth += 1 }
    try
      pauseLock.synchronized:
        while reading do pauseLock.wait()
      body
    finally
      pauseLock.synchronized:
        pauseDepth -= 1
        if pauseDepth == 0 && running then
          alerts.reportFocus(true) // an answer read by the line reader turned it off
          out.print(Ansi.Esc + "[?2004h")
          out.flush()
        pauseLock.notifyAll()

  /** Hand the type-ahead to the next prompt. */
  def takeTypeAhead(): String =
    val text = typeAhead.toString
    typeAhead.clear()
    text

  private def loop(): Unit =
    val in: NonBlockingReader = terminal.reader()
    var skipLf = false // a CR already added the newline of a CRLF
    var pasting = false
    def submit(): Unit =
      val text = typeAhead.toString.trim
      if text.nonEmpty then
        onSubmit(text)
        typeAhead.clear()
    while running do
      val mayRead = pauseLock.synchronized:
        while pauseDepth > 0 && running do pauseLock.wait()
        reading = running
        reading
      if mayRead then
        try
          val c =
            try in.read(100L)
            catch case _: Exception => -1
          if c >= 0 && c != 27 then alerts.touch()
          c match
            case NonBlockingReader.READ_EXPIRED => () // no key read: leave skipLf pending
            case -1 => running = false
            case '\r' =>
              if pasting then typeAhead.append('\n') else submit()
              skipLf = true
            // Collapse only a CRLF pair: an LF right after a CR. Any other real key
            // clears the latch, so a later lone LF is not wrongly swallowed as the
            // tail of an old CR (`skipLf` is reset in every branch below but '\r').
            case '\n' =>
              if !skipLf then
                if pasting then typeAhead.append('\n') else submit()
              skipLf = false
            case other =>
              skipLf = false
              other match
                case 15 => onToggle() // Ctrl-O
                // Swallow the rest of an escape sequence (arrow keys, function keys): its
                // bytes are all ≥ 32 and would otherwise land in the type-ahead as stray
                // `[A` text.
                case 27 => KeyReader.readEscapeSequence(() => in.read(30L)) match
                    case "[I" => alerts.focusChanged(true)
                    case "[O" => alerts.focusChanged(false)
                    case sequence =>
                      alerts.touch()
                      if sequence == "[200~" then pasting = true
                      else if sequence == "[201~" then pasting = false
                case 127 | 8 =>
                  if typeAhead.nonEmpty then
                    val end = typeAhead.length
                    val removed =
                      if end >= 2 && Character.isSurrogatePair(typeAhead.charAt(end - 2), typeAhead.charAt(end - 1))
                      then 2
                      else 1
                    typeAhead.setLength(end - removed)
                // ch.toChar alone would truncate a non-BMP code point; UTF-16 units
                // (a reader that delivers surrogates) pass through reassembled.
                case ch if ch > 0xffff => typeAhead.append(String(Character.toChars(ch)))
                case ch if ch >= 32 => typeAhead.append(ch.toChar)
                case _ => ()
          // A paste reports its text once, at its end, not with every byte; a pause
          // in the input still reports it, which also keeps the footer's clock going.
          if !pasting || c == NonBlockingReader.READ_EXPIRED then onDraft(typeAhead.toString)
        finally
          pauseLock.synchronized:
            reading = false
            pauseLock.notifyAll()

private[atc] object KeyReader:
  /** Consume CSI/SS3 bytes after ESC, stopping on a final byte, EOF or timeout. */
  private[atc] def readEscapeSequence(read: () => Int): String =
    val result = StringBuilder()
    def next(): Int =
      try
        val char = read()
        if char >= 0 then result.append(char.toChar)
        char
      catch case _: IOException => -1
    next() match
      case '[' =>
        var char = next()
        while result.length < 64 && char >= 0 && !(char >= 0x40 && char <= 0x7e) do char = next()
      case 'O' => next(); ()
      case _ => ()
    result.toString
