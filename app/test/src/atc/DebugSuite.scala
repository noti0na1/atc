package atc

class DebugSuite extends munit.FunSuite:
  test("debug messages are evaluated once when enabled and never when disabled"):
    var evaluations = 0
    Debug.log {
      evaluations += 1
      "checking lazy debug output"
    }
    assertEquals(evaluations, if Debug.enabled then 1 else 0)

  test("error descriptions retain the cause hidden by a generic request failure"):
    val error = RuntimeException("Request failed", java.io.IOException("executor rejected"))
    assertEquals(Debug.describe(error), "RuntimeException: Request failed\nCaused by: IOException: executor rejected")

  test("error descriptions handle absent messages and cyclic cause chains"):
    val first = RuntimeException()
    val second = RuntimeException("nested", first)
    first.initCause(second)
    assertEquals(Debug.describe(first), "RuntimeException\nCaused by: RuntimeException: nested")
