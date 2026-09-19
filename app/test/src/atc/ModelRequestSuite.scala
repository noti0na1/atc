package atc

import atc.llm.{CancelledException, ModelRequest}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

class ModelRequestSuite extends munit.FunSuite:
  test("a stalled stream is closed on cancellation and the caller returns promptly"):
    val entered = CountDownLatch(1)
    val released = CountDownLatch(1)
    val cancelled = AtomicBoolean(false)
    val error = AtomicReference[Throwable]()
    val request = ModelRequest()
    val caller = Thread(() =>
      try
        request.run(() => cancelled.get()) {
          ModelRequest.withResource(new AutoCloseable:
            def close(): Unit = released.countDown()) { _ =>
            entered.countDown()
            var waiting = true
            while waiting do
              try waiting = !released.await(5, TimeUnit.SECONDS)
              catch case _: InterruptedException => () // closing the resource must unblock it
            "late response"
          }
        }
      catch case e: Throwable => error.set(e)
      ()
    )
    caller.setDaemon(true)
    caller.start()
    try
      assert(entered.await(5, TimeUnit.SECONDS))
      cancelled.set(true)
      request.recheck()
      caller.join(1500)
      assert(!caller.isAlive)
      assert(error.get().isInstanceOf[CancelledException])
      assertEquals(released.getCount, 0L)
    finally released.countDown()

  test("recheck only ends a request whose predicate holds, and a stale one never affects the next"):
    val request = ModelRequest()
    val cancelled = AtomicBoolean(false)
    val release = CountDownLatch(1)
    val result = AtomicReference[Any]()
    val caller = Thread(() =>
      result.set(
        try request.run(() => cancelled.get()) { release.await(5, TimeUnit.SECONDS); "answer" }
        catch case e: Throwable => e
      )
    )
    caller.setDaemon(true)
    caller.start()
    try
      request.recheck() // the predicate is false: nothing happens
      Thread.sleep(50)
      assert(caller.isAlive)
      release.countDown()
      caller.join(1500)
      assertEquals(result.get(), "answer")
      request.recheck() // nothing pending: nothing happens
      cancelled.set(true)
      request.recheck() // nothing pending either, and the next request is not touched
      cancelled.set(false)
      assertEquals(request.run(() => cancelled.get())("next"), "next")
    finally release.countDown()

  test("already cancelled requests have no effects and completed requests can be reused"):
    val request = ModelRequest()
    var ran = false
    intercept[CancelledException](request.run(() => true) { ran = true })
    assert(!ran)
    for i <- 1 to 20 do assertEquals(request.run(() => false)(i), i)
    val failure = intercept[IllegalArgumentException](request.run(() => false)(throw IllegalArgumentException("bad")))
    assertEquals(failure.getMessage, "bad")
    assertEquals(request.run(() => false)("recovered"), "recovered")

  test("a failed operation releases its slot before the worker thread finishes exiting"):
    val request = ModelRequest()
    val exiting = CountDownLatch(1)
    val release = CountDownLatch(1)
    val worker = AtomicReference[Thread]()
    val failure = LinkageError("provider failure")
    try
      val observed =
        try
          request.run(() => false) {
            val thread = Thread.currentThread()
            worker.set(thread)
            // Keep the failed worker alive after it publishes the failure to its caller.
            thread.setUncaughtExceptionHandler((_, _) =>
              exiting.countDown()
              release.await(5, TimeUnit.SECONDS)
              ()
            )
            throw failure
          }
          fail("Expected the provider failure")
        catch case error: LinkageError => error
      assert(observed eq failure)
      assert(exiting.await(5, TimeUnit.SECONDS))
      assertEquals(request.run(() => false)("recovered"), "recovered")
    finally
      release.countDown()
      Option(worker.get()).foreach(_.join(1000))

  test("cancellation keeps the slot occupied until an uncooperative operation ends"):
    val request = ModelRequest()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val cancelled = AtomicBoolean(false)
    val error = AtomicReference[Throwable]()
    val worker = AtomicReference[Thread]()
    val caller = Thread(() =>
      try
        request.run(() => cancelled.get()) {
          worker.set(Thread.currentThread())
          entered.countDown()
          while release.getCount > 0 do
            try release.await()
            catch case _: InterruptedException => ()
          "late response"
        }
      catch case failure: Throwable => error.set(failure)
      ()
    )
    caller.setDaemon(true)
    caller.start()
    try
      assert(entered.await(5, TimeUnit.SECONDS))
      cancelled.set(true)
      request.recheck()
      caller.join(1500)
      assert(!caller.isAlive)
      assert(error.get().isInstanceOf[CancelledException])
      val blocked = intercept[IllegalStateException](request.run(() => false)("too soon"))
      assert(blocked.getMessage.nn.contains("still stopping"))
      release.countDown()
      worker.get().nn.join(1500)
      assertEquals(request.run(() => false)("recovered"), "recovered")
    finally
      release.countDown()
      caller.join(1000)
      Option(worker.get()).foreach(_.join(1000))
