package atc.llm

import java.util.concurrent.{CompletableFuture, ExecutionException}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.Using
import scala.util.control.NonFatal

/** One active model request per agent. The caller blocks until the worker finishes or
  * `recheck()` finds the request cancelled; cancellation wakes the caller, interrupts the
  * worker and closes its stream. */
private[atc] final class ModelRequest:
  /** A request whose worker was started; guarded by `this`. */
  private final class Pending(
    val cancelled: () => Boolean,
    val thread: Thread,
    val scope: ModelRequest.Scope,
    val done: CompletableFuture[Any],
  )
  private var pending: Pending | Null = null

  /** `cancelled` is evaluated before the worker starts, once it is registered (so a
    * `recheck()` that raced the start is not lost), on every `recheck()` and after the
    * worker returns; it is not polled while the worker runs. */
  def run[A](cancelled: () => Boolean)(operation: => A): A =
    if cancelled() then throw CancelledException()
    val scope = ModelRequest.Scope()
    val done = CompletableFuture[Any]()
    val work: Runnable = () =>
      ModelRequest.current.set(scope)
      try done.complete(operation)
      catch
        // The interrupt of a cancelled request is not `NonFatal`, but it ends the worker quietly.
        case e: InterruptedException => done.completeExceptionally(e)
        case NonFatal(e) => done.completeExceptionally(e)
        case e => // a fatal error still reaches the caller, then ends the worker as it would have
          done.completeExceptionally(e)
          throw e
      finally ModelRequest.current.remove()
    val thread = Thread(work, "atc-model-request")
    thread.setDaemon(true)
    val active = Pending(cancelled, thread, scope, done)
    synchronized {
      if pending != null && pending.nn.thread.isAlive then
        throw IllegalStateException("The previous model request is still stopping; try again shortly.")
      pending = active
      thread.start()
    }
    def stopped(e: CancelledException): Nothing =
      stop(active)
      thread.join(250)
      throw e
    try
      if cancelled() then throw CancelledException()
      val result = done.get()
      // The operation has finished, even if its worker has not exited yet.
      synchronized { if pending eq active then pending = null }
      if cancelled() then throw CancelledException()
      result.asInstanceOf[A]
    catch
      case e: ExecutionException =>
        e.getCause.nn match
          case c: CancelledException => stopped(c)
          case c =>
            synchronized { if pending eq active then pending = null }
            throw c
      case e: CancelledException => stopped(e)
      case _: InterruptedException =>
        stop(active)
        Thread.currentThread().interrupt()
        throw CancelledException()

  /** Something the pending request's `cancelled` predicate observes has changed: evaluate
    * it now, and if it holds wake the caller (which throws `CancelledException`) and stop
    * the worker. Called from the threads that record an interrupt or queue input. */
  def recheck(): Unit =
    val active = synchronized(pending)
    if active != null && active.cancelled() then stop(active)

  private def stop(active: Pending): Unit =
    active.done.completeExceptionally(CancelledException())
    active.thread.interrupt()
    active.scope.cancel()

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

  /** Bind cancellation to the Anthropic SDK's calls, including retries and calls still waiting for
    * response headers: cancelling a pending call's future makes the SDK cancel its OkHttp call, and
    * closing a response body mid-read does the same (SDK >= 2.64). The wrapper shares the model's
    * transport and never closes it; the model owns its lifetime (see `Providers.borrowed`). */
  def scopedTransport(base: com.anthropic.core.http.HttpClient): com.anthropic.core.http.HttpClient =
    val scope = Option(current.get())
    new com.anthropic.core.http.HttpClient:
      def execute(request: com.anthropic.core.http.HttpRequest, options: com.anthropic.core.RequestOptions) =
        scope.foreach(_.checkActive())
        base.execute(request, options)
      def executeAsync(request: com.anthropic.core.http.HttpRequest, options: com.anthropic.core.RequestOptions) =
        scope.foreach(_.checkActive())
        val call = base.executeAsync(request, options)
        scope.foreach(_.attachTransport(new AutoCloseable:
          def close(): Unit =
            call.cancel(true)
            ()))
        call
      def close(): Unit = ()

  /** Register a provider stream while it is open, including streams created after cancellation. */
  def withResource[R <: AutoCloseable, A](open: => R)(operation: R => A): A =
    Using.resource(open) { resource =>
      val scope = Option(current.get())
      try
        scope.foreach(_.attach(resource))
        operation(resource)
      finally scope.foreach(_.detach(resource))
    }
