package atc

import atc.sandbox.ExecutionResult

/** Assertions shared by the suites that drive a sandbox REPL.
  *
  * A snippet can be rejected in three ways: the regex validator, a compile
  * error (capture checking / safe mode), or a runtime exception. For most
  * security properties any rejection is equally good, so [[assertFails]] does
  * not distinguish them. Pass `pattern` when the *reason* is the point (e.g.
  * "read-only", or a validator rule id). */
trait ReplAssertions:
  self: munit.FunSuite =>

  /** The snippet compiled and ran. */
  def assertOk(r: ExecutionResult)(using munit.Location): ExecutionResult =
    assert(r.success, s"expected success, got:\n${r.output}\n${r.error.getOrElse("")}")
    r

  /** The snippet was rejected; `pattern` (case-insensitive) must appear in the diagnostics. */
  def assertFails(r: ExecutionResult, pattern: String = "")(using munit.Location): ExecutionResult =
    assert(!r.success, s"expected failure, got success with:\n${r.output}")
    if pattern.nonEmpty then
      val text = (r.output + "\n" + r.error.getOrElse("")).toLowerCase
      assert(text.contains(pattern.toLowerCase), s"expected '$pattern' in:\n${r.output}\n${r.error}")
    r
