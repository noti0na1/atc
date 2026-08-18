package atc.lib

import language.experimental.captureChecking
import caps.*

/** The sandbox's injection point. It is NOT part of the agent API and is never
  * bundled into the system prompt. The host installs its [[Interface]] implementation
  * here before the REPL preamble runs; the preamble reads it back with `current`
  * and takes the root capability `rootIO`. Everything here is `@rejectSafe`, so
  * agent code (compiled under safe mode) cannot name it; the code validator
  * rejects the names too, and `install` is `private[atc]`. */
@rejectSafe("it is internal to the sandbox")
object Runtime:
  @volatile private var installed: Interface | Null = null

  private[atc] def install(impl: Interface): Unit = installed = impl

  def current: Interface =
    installed match
      case null => throw IllegalStateException("The sandbox has no host installed.")
      case a    => a

  /** The root-capability labels. The host never inspects them (every check is per
    * call against the policy), so one shared instance of each serves every
    * sandbox; their power is entirely in their types. `rootIO` derives the file
    * system / commands / network; `rootUser` talks to the user. */
  @caps.unsafe.untrackedCaptures
  val rootIO: IOCap^ = new IOCap
  @caps.unsafe.untrackedCaptures
  val rootUser: UserIO^ = new UserIO
