package atc.lib

import language.experimental.captureChecking
import caps.*

/** How the sandbox derives the mode's capabilities from the root `io`: the REPL
  * preamble (compiled before the safe-mode import) calls these through [[Runtime]]
  * to define the givens `fs`/`ex`/`net`, and the host implements them. Not part of
  * the agent API: the agent gets the givens of its mode, never the derivations, so
  * nothing it can call takes a full `IOCap^`. */
@rejectSafe("it is internal to the sandbox")
trait Derivations:
  /** The configured file system, full (read + write); needs a full `io`. */
  def fileSystem(using io: IOCap^): FileSystem^{io}
  /** The configured command permissions; needs a full `io`. */
  def processes(using io: IOCap^): Exec^{io}
  /** The configured network permissions; needs a full `io`. */
  def network(using io: IOCap^): Network^{io}
  /** The configured file system as read-only, whatever `io` is (read-only mode's `fs`). */
  def readOnlyFileSystem(using io: IOCap): FileSystem^{io.rd}

/** The sandbox's injection point. It is NOT part of the agent API and is never
  * bundled into the system prompt. The host installs its [[Interface]] implementation
  * here before the REPL preamble runs; the preamble reads it back with `current`
  * and takes the root capability `rootIO`. Everything here is `@rejectSafe`, so
  * agent code (compiled under safe mode) cannot name it; the code validator
  * rejects the names too, and `install` is `private[atc]`. */
@rejectSafe("it is internal to the sandbox")
object Runtime:
  @volatile private var installed: (Interface & Derivations) | Null = null

  private[atc] def install(impl: Interface & Derivations): Unit = installed = impl

  private def host: Interface & Derivations =
    installed match
      case null => throw IllegalStateException("The sandbox has no host installed.")
      case a    => a

  def current: Interface = host

  /** The derivations, for the preamble (see [[Derivations]]). */
  def fileSystem(using io: IOCap^): FileSystem^{io} = host.fileSystem
  def processes(using io: IOCap^): Exec^{io} = host.processes
  def network(using io: IOCap^): Network^{io} = host.network
  def readOnlyFileSystem(using io: IOCap): FileSystem^{io.rd} = host.readOnlyFileSystem

  /** The root-capability labels. The host never inspects them (every check is per
    * call against the policy), so one shared instance of each serves every
    * sandbox; their power is entirely in their types. `rootIO` derives the file
    * system / commands / network; `rootUser` talks to the user. */
  @caps.unsafe.untrackedCaptures
  val rootIO: IOCap^ = new IOCap
  @caps.unsafe.untrackedCaptures
  val rootUser: UserIO^ = new UserIO
