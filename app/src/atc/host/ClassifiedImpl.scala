package atc.host

import atc.lib.Classified

import scala.util.control.{ControlThrowable, NonFatal}
import scala.util.{Failure, Success, Try}

/** Stores classified values and non-fatal computation failures in `Try`.
  * Capture checking enforces callback purity in the agent-facing API.
  * Fatal errors and interruption propagate to abort evaluation; the validator
  * rejects catches that could expose those failures or suppress cancellation. */
final class ClassifiedImpl[+T](val value: Try[T]) extends Classified[T]:
  def map[B](op: T => B): Classified[B] = ClassifiedImpl(value.flatMap(v => ClassifiedImpl.attempt(op(v))))
  override def toString: String = "Classified(***)"

object ClassifiedImpl:
  def wrap[T](value: T): Classified[T] = ClassifiedImpl(Success(value))

  /** Run a classified computation, keeping a non-fatal failure as its result. A control
    * throwable (a non-local `return`, `Breaks.break`) is kept too: escaping, it would carry
    * control flow that depends on the value out of the computation. */
  def attempt[T](op: => T): Try[T] =
    try Success(op)
    catch
      case e: ControlThrowable => Failure(e)
      case NonFatal(e) => Failure(e)

  /** Classify the outcome of an already-run effect (value or failure). */
  def fromTry[T](value: Try[T]): Classified[T] = ClassifiedImpl(value)

  def unwrap[T](c: Classified[T]): Try[T] = c match
    case impl: ClassifiedImpl[T] @unchecked => impl.value
    case other => throw SecurityException(s"Unknown Classified implementation: ${other.getClass.getName}")

  /** Read the value out, for host code and tests that are allowed to see it.
    * A failed computation raises a sanitized error instead of the original
    * exception, which may quote the confidential value (a pure `map` lambda can
    * throw), so it must never reach the agent. */
  def get[T](c: Classified[T]): T =
    unwrap(c).getOrElse:
      throw IllegalStateException(
        "The classified value is the result of a failed computation; its error is confidential (println it to let the user see it)."
      )
