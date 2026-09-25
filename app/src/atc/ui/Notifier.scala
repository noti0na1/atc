package atc.ui

import atc.{Debug, ProcessEnvironment}
import atc.platform.Platform

import java.nio.charset.StandardCharsets
import java.util.{Base64, Locale}

/** Desktop notifications for moments when atc waits for the user. `terminal`
  * writes an escape sequence (or a bell) to the terminal; system notifications
  * run a platform command on a background thread and ring the bell if it cannot start. */
enum Notifier:
  case Off
  case Bell
  /** An OSC notification the terminal turns into a desktop notification (`Osc.format`). */
  case Terminal(osc: Notifier.Osc)
  /** `osascript` on macOS, a PowerShell toast on Windows, `notify-send` elsewhere. */
  case System

  /** Notify with `title` and `body`. `emit` writes raw control text to the terminal. */
  def send(title: String, body: String, emit: String => Unit): Unit =
    val t = Notifier.clean(title)
    val b = Notifier.clean(body)
    this match
      case Off => ()
      case Bell => emit(Notifier.BellChar)
      case Terminal(osc) => emit(Notifier.passThrough(osc.format(t, b)))
      case System =>
        val command = Notifier.systemCommand(t, b)
        val show: Runnable = () =>
          try
            ProcessBuilder(command*)
              .redirectInput(ProcessBuilder.Redirect.from(Platform.nullDevice))
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start()
            ()
          catch
            case e: Exception =>
              Debug.log(s"notification command ${command.head} failed: $e")
              emit(Notifier.BellChar)
        val thread = Thread(show, "atc-notify")
        thread.setDaemon(true)
        thread.start()

object Notifier:
  /** The notification escape sequences terminals understand. */
  enum Osc:
    /** OSC 9: iTerm2, WezTerm, Ghostty. It has no title field. */
    case Nine
    /** OSC 99: kitty. */
    case Kitty
    /** OSC 777: foot, rxvt-unicode, Ghostty, WezTerm. */
    case Notify

    def format(title: String, body: String): String = this match
      case Nine => s"\u001b]9;$title: $body\u0007"
      case Kitty => s"\u001b]99;i=atc:d=0;$title\u001b\\\u001b]99;i=atc:d=1:p=body;$body\u001b\\"
      // `;` separates the fields.
      case Notify => s"\u001b]777;notify;${title.replace(';', ',')};${body.replace(';', ',')}\u0007"

  private val BellChar = "\u0007"
  private val MaxChars = 200

  /** The notifier a `notifications` setting (validated by the config) selects. `auto` prefers the
    * terminal's own notifications, since they work over SSH and focus the
    * right tab when clicked; then a system notification on a local machine;
    * then the bell. */
  def fromSetting(setting: String, env: String => Option[String] = ProcessEnvironment.get): Notifier =
    setting.toLowerCase(Locale.ROOT) match
      case "off" => Off
      case "bell" => Bell
      case "terminal" => terminalOsc(env).fold(Bell)(Terminal(_))
      case "system" => System
      case _ =>
        terminalOsc(env).map(Terminal(_)).getOrElse:
          val remote = env("SSH_CONNECTION").orElse(env("SSH_TTY")).isDefined
          val desktop = Platform.isMac || Platform.isWindows || env("DISPLAY").orElse(env("WAYLAND_DISPLAY")).isDefined
          if !remote && desktop then System else Bell

  /** The OSC sequence the running terminal is known to display, from the
    * variables terminals set. Inside tmux these name the outer terminal only
    * when the session was started from it, which is the common case. */
  private[atc] def terminalOsc(env: String => Option[String]): Option[Osc] =
    val program = env("TERM_PROGRAM").getOrElse("").toLowerCase(Locale.ROOT)
    val term = env("TERM").getOrElse("")
    if env("KITTY_WINDOW_ID").isDefined || term == "xterm-kitty" then Some(Osc.Kitty)
    else if program == "iterm.app" || program == "wezterm" || program == "ghostty" then Some(Osc.Nine)
    else if term.startsWith("foot") || term.startsWith("rxvt") then Some(Osc.Notify)
    else None

  /** Markdown as plain prose for a notification: code blocks, heading and
    * list markers, emphasis and backticks removed, lines joined. */
  private[atc] def plainText(markdown: String): String =
    var inFence = false
    markdown.linesIterator
      .filter: line =>
        val fence = line.trim.startsWith("```") || line.trim.startsWith("~~~")
        if fence then inFence = !inFence
        !fence && !inFence
      .map(_.trim.replaceFirst("^(#{1,6}|>|[-*+]|\\d+[.)])\\s+", "").nn)
      .map(_.replace("**", "").replace("__", "").replace("`", ""))
      .filter(_.nonEmpty)
      .mkString(" ")

  /** The text as one line with single spaces, without control characters or anything past [[MaxChars]]. */
  private[atc] def clean(text: String): String =
    val line = Ansi.sanitize(text).replaceAll("\\s+", " ").nn.trim
    if line.length <= MaxChars then line else line.take(MaxChars - 1) + "…"

  /** tmux drops unknown OSC sequences unless they are wrapped for passthrough
    * (which also needs `set -g allow-passthrough on`). */
  private def passThrough(sequence: String): String =
    if ProcessEnvironment.get("TMUX").isEmpty then sequence
    else "\u001bPtmux;" + sequence.replace("\u001b", "\u001b\u001b") + "\u001b\\"

  /** The command that shows a system notification. The text travels as
    * arguments (macOS, Linux) or inside a Base64-encoded script (Windows), so
    * nothing is interpreted by a shell. */
  private[atc] def systemCommand(title: String, body: String): List[String] =
    if Platform.isMac then
      List(
        "osascript",
        "-e",
        "on run argv",
        "-e",
        "display notification (item 2 of argv) with title (item 1 of argv)",
        "-e",
        "end run",
        title,
        body
      )
    else if Platform.isWindows then
      List("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encodePowerShell(toast(title, body)))
    else List("notify-send", "--app-name=atc", title, body)

  /** A PowerShell script showing a toast through the WinRT API, under
    * PowerShell's own application id because atc has none registered. */
  private def toast(title: String, body: String): String =
    // PowerShell also closes single-quoted strings at typographic single quotes.
    def quoted(s: String) = "'" +
      s.flatMap(c => if "'\u2018\u2019\u201a\u201b".contains(c) then s"$c$c" else c.toString) + "'"
    s"""[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $$null
       |$$xml = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)
       |$$text = $$xml.GetElementsByTagName('text')
       |$$text.Item(0).AppendChild($$xml.CreateTextNode(${quoted(title)})) > $$null
       |$$text.Item(1).AppendChild($$xml.CreateTextNode(${quoted(body)})) > $$null
       |$$app = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe'
       |[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($$app).Show([Windows.UI.Notifications.ToastNotification]::new($$xml))
       |""".stripMargin

  private def encodePowerShell(script: String): String =
    Base64.getEncoder.nn.encodeToString(script.getBytes(StandardCharsets.UTF_16LE)).nn
