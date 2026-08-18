package atc.host

import atc.lib.Classified

import scala.util.{Success, Try}

/** The host's `Classified` implementation. It wraps a `Try` so a failing pure
  * computation inside `map` stays confidential too: the failure is only
  * observable at a sink. Purity of `map`/`flatMap` arguments is enforced by
  * capture checking on the agent side (the declared signature in `atc.lib`);
  * this class just carries the value.
  *
  * `Try` traps only `NonFatal`, so a *fatal* throwable raised by a callback
  * (`StackOverflowError`, `OutOfMemoryError`, an `InterruptedException`, or the
  * sandbox's `ThreadDeath` stop signal) propagates out of `map`/`flatMap` and
  * aborts the evaluation rather than becoming a masked failure. That is safe
  * because agent code cannot *catch* a fatal throwable — `CodeValidator` rejects
  * such catches — so a fatal throw cannot be turned into a per-bit oracle over
  * the classified value (nor be used to swallow a timeout/interrupt). */
final class ClassifiedImpl[+T](val value: Try[T]) extends Classified[T]:
  def map[B](op: T => B): Classified[B] = ClassifiedImpl(value.map(op))
  def flatMap[B](op: T => Classified[B]): Classified[B] =
    ClassifiedImpl(value.flatMap(v => ClassifiedImpl.unwrap(op(v))))
  override def toString: String = "Classified(***)"

object ClassifiedImpl:
  def wrap[T](value: T): Classified[T] = ClassifiedImpl(Success(value))
  /** Classify the outcome of an already-run effect (value or failure). */
  def fromTry[T](value: Try[T]): Classified[T] = ClassifiedImpl(value)
  def unwrap[T](c: Classified[T]): Try[T] = c match
    case impl: ClassifiedImpl[T] @unchecked => impl.value
    case other => throw SecurityException(s"Unknown Classified implementation: ${other.getClass.getName}")
  /** Unwrap for a sink. A failed computation is reported with a *sanitized*
    * error: the original exception may carry the confidential value in its
    * message (a pure `map` lambda can throw), so it must never reach the agent. */
  def get[T](c: Classified[T]): T = unwrap(c).getOrElse(throw failed())

  /** The error a sink raises for a failed classified computation. */
  def failed(): IllegalStateException =
    IllegalStateException(
      "The classified value is the result of a failed computation; its error is confidential (println it to let the user see it)."
    )
