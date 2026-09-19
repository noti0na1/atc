package atc.agent

import atc.platform.{Platform, PlatformPath}

import java.nio.file.Path

/** Platform data exposed to the model. Keeping it as plain data means prompt
  * construction has no dependency on the host implementation or ambient
  * system properties. */
final case class AgentEnvironment(
  workingDirectory: String,
  operatingSystem: String,
  /** Whether someone is at the terminal to answer `ask` and permission prompts (false for a `-p` run). */
  userPresent: Boolean = true,
)

object AgentEnvironment:
  /** Capture the process environment once when an agent is constructed. */
  def current(cwd: Path, userPresent: Boolean = true): AgentEnvironment =
    AgentEnvironment(
      workingDirectory = PlatformPath.portable(cwd),
      operatingSystem = Platform.description,
      userPresent = userPresent,
    )
