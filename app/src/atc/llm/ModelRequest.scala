package atc.llm

import java.util.concurrent.{ExecutionException, FutureTask, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.Using
import scala.util.control.NonFatal

/** One active model request per agent. Cancellation interrupts the worker and closes its stream. */
private[atc] final class ModelRequest:
  private var pending: FutureTask[?] | Null = null

  def run[A](cancelled: () => Boolean)(operation: => A): A =
    if cancelled() then throw CancelledException()
    val scope = ModelRequest.Scope()
    val task = FutureTask[A](() =>
      ModelRequest.current.set(scope)
      try operation
      finally ModelRequest.current.remove()
    )
    val thread = synchronized {
      if pending != null && !pending.nn.isDone then
        throw IllegalStateException("The previous model request is still stopping; try again shortly.")
      val next = Thread(task, "atc-model-request")
      next.setDaemon(true)
      pending = task
      next.start()
      next
    }
    try
      while true do
        if cancelled() then throw CancelledException()
        try
          val result = task.get(50, TimeUnit.MILLISECONDS)
          if cancelled() then throw CancelledException()
          return result
        catch case _: TimeoutException => ()
      throw IllegalStateException("Model request ended without a result")
    catch
      case e: ExecutionException => throw e.getCause.nn
      case e: CancelledException =>
        thread.interrupt()
        scope.cancel()
        thread.join(250)
        throw e
      case e: InterruptedException =>
        thread.interrupt()
        scope.cancel()
        Thread.currentThread().interrupt()
        throw CancelledException()

private[atc] object ModelRequest:
  private val current = ThreadLocal[Scope]()

  /** SDK async streams can be cancelled even before response headers arrive. */
  def awaitStream(closeStream: () => Unit)(completion: => java.util.concurrent.CompletableFuture[?]): Unit =
    withResource(new AutoCloseable:
      def close(): Unit = closeStream()) { _ =>
      try
        completion.get()
        ()
      catch case error: ExecutionException => throw error.getCause.nn
    }

  private final class Scope:
    private val stopped = AtomicBoolean(false)
    private val resource = AtomicReference[AutoCloseable]()
    private val transport = AtomicReference[AutoCloseable]()

    def checkActive(): Unit = if stopped.get() then throw CancelledException()

    def attachTransport(value: AutoCloseable): Unit =
      transport.set(value)
      if stopped.get() then cancel()

    def attach(value: AutoCloseable): Unit =
      resource.set(value)
      if stopped.get() then
        cancel()
        throw CancelledException()

    def detach(value: AutoCloseable): Unit =
      resource.compareAndSet(value, null)
      ()

    def cancel(): Unit =
      stopped.set(true)
      // Cancel the HTTP call before closing the SDK reader, whose lock may be held by a blocked read.
      List(transport.getAndSet(null), resource.getAndSet(null)).foreach { value =>
        if value != null then
          try value.close()
          catch case NonFatal(_) => ()
      }

  /** Bind cancellation to OkHttp calls, including retries and reads already in progress. */
  def scopedHttpClient(base: okhttp3.OkHttpClient): okhttp3.OkHttpClient =
    val scope = Option(current.get())
    base.newBuilder().eventListenerFactory { _ =>
      scope.foreach(_.checkActive())
      new okhttp3.EventListener:
        override def callStart(call: okhttp3.Call): Unit =
          scope.foreach(_.attachTransport(new AutoCloseable:
            def close(): Unit = call.cancel()))
    }.build()

  /** Register a provider stream while it is open, including streams created after cancellation. */
  def withResource[R <: AutoCloseable, A](open: => R)(operation: R => A): A =
    Using.resource(open) { resource =>
      val scope = Option(current.get())
      try
        scope.foreach(_.attach(resource))
        operation(resource)
      finally scope.foreach(_.detach(resource))
    }
