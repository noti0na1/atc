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
      caller.join(1500)
      assert(!caller.isAlive)
      assert(error.get().isInstanceOf[CancelledException])
      assertEquals(released.getCount, 0L)
    finally released.countDown()

  test("already cancelled requests have no effects and completed requests can be reused"):
    val request = ModelRequest()
    var ran = false
    intercept[CancelledException](request.run(() => true) { ran = true })
    assert(!ran)
    for i <- 1 to 20 do assertEquals(request.run(() => false)(i), i)
    val failure = intercept[IllegalArgumentException](request.run(() => false)(throw IllegalArgumentException("bad")))
    assertEquals(failure.getMessage, "bad")
