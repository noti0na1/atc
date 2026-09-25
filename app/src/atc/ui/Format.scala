package atc.ui

/** Short human-readable numbers, durations and plurals for status and summary lines. */
object Format:
  /** `context 45.2k/200k (23%)`, or `context ~45.2k` when the model's window is unknown. */
  def contextUsage(tokens: Long, window: Option[Int]): String = window match
    case Some(w) if w > 0 => s"context ${count(tokens)}/${count(w)} (${(tokens * 100 + w / 2) / w}%)"
    case _ => s"context ~${count(tokens)}"

  /** `1 line`, `2 lines`. */
  def plural(n: Long, noun: String): String = s"$n $noun${if n == 1 then "" else "s"}"

  /** `4.2 s` under ten seconds, `42 s` under a minute, then `2 min 5 s`. */
  def duration(secs: Double): String =
    if secs < 10 then f"$secs%.1f s"
    else if secs < 60 then s"${secs.round} s"
    else
      val total = secs.round // round first: 119.6 s is "2 min 0 s", not "1 min 60 s"
      s"${total / 60} min ${total % 60} s"

  /** `1234` → `1.2k`, `200000` → `200k`, `1234567` → `1.2M`. */
  def count(n: Long): String =
    def short(x: Double, unit: String) = (if x == x.floor then f"$x%.0f" else f"$x%.1f") + unit
    if n < 1000 then n.toString
    else if n < 1_000_000 then short(n / 1e3, "k")
    else short(n / 1e6, "M")
