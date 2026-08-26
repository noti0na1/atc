package atc

/** `ATC_DEBUG=1`: stack traces and terminal/stream diagnostics on stderr. */
object Debug:
  val enabled: Boolean = ProcessEnvironment.contains("ATC_DEBUG")
  def log(message: => String): Unit = if enabled then System.err.println(s"[atc] $message")
  def trace(e: Throwable): Unit = if enabled then e.printStackTrace()
