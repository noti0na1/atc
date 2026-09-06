package atc

import atc.perms.Decision
import atc.sandbox.ExecutionResult
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

/** Run separately as well as in the full suite: other compiler sessions can populate
  * Scala's shared wrapper-name table and hide a missing index after interruption. */
class ReplInterruptionSuite extends munit.FunSuite:
  test("interruption preserves earlier definitions and permits new bindings"):
    val env = TestEnv(prefix = "atc-interrupt-state")
    val session = env.newSession()
    val entered = CountDownLatch(1)
    env.decisions = List(Decision.AllowOnce)
    env.onRequest = _ => entered.countDown()
    val result = AtomicReference[ExecutionResult]()
    val caller = Thread(() =>
      result.set(session.run("requestExec(List(\"test-loop\"), \"interrupt test\") { while true do () }"))
    )
    caller.setDaemon(true)
    try
      assert(session.run("val before = 40").success)
      caller.start()
      assert(entered.await(10, TimeUnit.SECONDS), "evaluation did not reach the permission boundary")
      session.interrupt()
      caller.join(5000)
      assert(!caller.isAlive, "evaluation did not stop")
      assert(!result.get().nn.success)
      val next = session.run("val after = before + 2")
      assert(next.success, next.render)
      val value = session.run("after")
      assert(value.success && value.output.contains("42"), value.render)
    finally session.close()
