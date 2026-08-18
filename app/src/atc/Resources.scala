package atc

/** Text files the build bundles into the jar (see `app.resources` in
  * `build.mill`): the version, the config template and the agent-facing API
  * source that goes into the system prompt. */
object Resources:
  /** The UTF-8 content of a bundled resource, or `None` when it is absent
    * (running from a classpath the build did not produce). */
  def text(path: String): Option[String] =
    Option(getClass.getResourceAsStream(path)).map { in =>
      try String(in.readAllBytes(), "UTF-8")
      finally in.close()
    }
