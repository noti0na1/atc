package atc.host

import atc.lib.Classified

import scala.util.{Success, Try}

/** Stores classified values and non-fatal computation failures in `Try`.
  * Capture checking enforces callback purity in the agent-facing API.
  * Fatal errors and interruption propagate to abort evaluation; the validator
  * rejects catches that could expose those failures or suppress cancellation. */
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
