package atc.config

import java.nio.file.{Files, Path}
import java.util.Properties
import scala.jdk.CollectionConverters.*

/** The values behind the `${VAR}` references a configuration uses for its API
  * keys, read from `.atc/keys.properties`, a Java properties file with one
  * binding per line:
  *
  * {{{
  * DEEPSEEK_API_KEY=sk-...
  * OPENROUTER_API_KEY=
  * }}}
  *
  * A configuration is meant to be shared (a project's travels with the
  * repository), so it names variables rather than holding keys, and only this
  * file has to stay out of version control. `.atc` is `none` and `locked` in
  * the starting policy, so the agent can read neither.
  *
  * A name is looked up in the project's file, then the global one, then the
  * process environment. **An empty value is not a binding**: the lookup passes
  * over it and carries on, so blanking a line falls back instead of breaking.
  */
final case class KeyBindings(files: List[(Path, Map[String, String])]):
  /** The value bound to `name`, from the files in order and then the
    * environment; `None` when nothing binds it to a non-empty value. */
  def get(name: String): Option[String] =
    files.iterator.flatMap((_, bindings) => bindings.get(name)).nextOption()
      .orElse(Option(System.getenv(name)).filter(_.nonEmpty))

  /** The names these files bind, for `/config`. Never the values. */
  def names: List[String] = files.flatMap(_._2.keys).distinct.sorted

  def sources: List[Path] = files.map(_._1)

object KeyBindings:
  val empty: KeyBindings = KeyBindings(Nil)

  /** Read the `keys.properties` files that exist, most specific first. */
  def load(paths: List[Path]): KeyBindings =
    KeyBindings(paths.filter(Files.isRegularFile(_)).distinctBy(_.toAbsolutePath.normalize).map(p => p -> read(p)))

  /** The bindings of one file, in the standard `.properties` format (`#` and
    * `!` comments, `=` or `:` after the name, `\` escapes and continuations;
    * `java.util.Properties` does the reading). An empty value means "not
    * bound", so the next source still applies. */
  private def read(path: Path): Map[String, String] =
    val props = Properties()
    try
      val in = Files.newBufferedReader(path).nn
      try props.load(in)
      finally in.close()
    catch case e: Exception => throw IllegalArgumentException(s"Cannot read keys $path: ${e.getMessage}")
    props.stringPropertyNames().nn.asScala.iterator
      .map(name => name -> props.getProperty(name).nn.trim)
      .filter((_, value) => value.nonEmpty)
      .toMap
