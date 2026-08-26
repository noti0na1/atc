package atc

/** Rendering used only when application data must appear in generated Scala
  * source (permission hints and diagnostics). This is intentionally distinct
  * from JSON, shell, regex, and terminal escaping. */
private[atc] object ScalaSource:
  def stringLiteral(value: String): String =
    val result = StringBuilder("\"")
    value.foreach:
      case '"' => result.append("\\\"")
      case '\\' => result.append("\\\\")
      case '\n' => result.append("\\n")
      case '\r' => result.append("\\r")
      case '\t' => result.append("\\t")
      case '\b' => result.append("\\b")
      case '\f' => result.append("\\f")
      case char if Character.isISOControl(char) => result.append(f"\\u${char.toInt}%04x")
      case char => result.append(char)
    result.append('"').toString
