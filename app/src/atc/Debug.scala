package atc

import java.util.IdentityHashMap

/** `ATC_DEBUG=1`: stack traces and terminal/stream diagnostics on stderr. */
object Debug:
  /** Include nested transport failures without requiring a full stack trace. */
  def describe(error: Throwable): String =
    val seen = IdentityHashMap[Throwable, java.lang.Boolean]()
    val parts = List.newBuilder[String]
    var current: Throwable | Null = error
    while current != null && seen.size() < 8 && !seen.containsKey(current) do
      val cause = current
      seen.put(cause, true)
      parts += cause.getClass.getSimpleName + Option(cause.getMessage).filter(_.nonEmpty).fold("")(": " + _)
      current = cause.getCause
    parts.result().mkString("\nCaused by: ")

  /** The message of `error`, or its class name when it has none: for one-line reports. */
  def message(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

  val enabled: Boolean = ProcessEnvironment.contains("ATC_DEBUG")
  /** The process's own stderr: the REPL redirects `System.err` while it evaluates, and
    * a line logged then from another thread would land in its output, where a set-up
    * round reads "error" in it as a compile failure. */
  private val stderr = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true)
  inline def log(inline message: String): Unit = if enabled then stderr.println(s"[atc] $message")
  def trace(e: Throwable): Unit = if enabled then e.printStackTrace(stderr)
