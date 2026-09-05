package atc

/** `ATC_DEBUG=1`: stack traces and terminal/stream diagnostics on stderr. */
object Debug:
  /** Include nested transport failures without requiring a full stack trace. */
  def describe(error: Throwable): String =
    val seen = java.util.IdentityHashMap[Throwable, java.lang.Boolean]()
    val parts = List.newBuilder[String]
    var current: Throwable | Null = error
    while current != null && seen.size() < 8 && !seen.containsKey(current) do
      val cause = current
      seen.put(cause, true)
      parts += cause.getClass.getSimpleName + Option(cause.getMessage).filter(_.nonEmpty).fold("")(": " + _)
      current = cause.getCause
    parts.result().mkString("\nCaused by: ")

  val enabled: Boolean = ProcessEnvironment.contains("ATC_DEBUG")
  def log(message: => String): Unit = if enabled then System.err.println(s"[atc] $message")
  def trace(e: Throwable): Unit = if enabled then e.printStackTrace()
