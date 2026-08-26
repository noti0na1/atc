package atc.config

import atc.{ProcessEnvironment, TextFiles}

import java.io.StringReader
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
      // The live process-environment lookup follows the platform's name rules;
      // notably, Windows names are case-insensitive while a copied Scala Map is not.
      .orElse(ProcessEnvironment.get(name).filter(_.nonEmpty))

  /** The names these files bind, for `/config`. Never the values. */
  def names: List[String] = files.flatMap(_._2.keys).distinct.sorted

  def sources: List[Path] = files.map(_._1)

object KeyBindings:
  val empty: KeyBindings = KeyBindings(Nil)

  /** Read existing `keys.properties` files from most to least specific. Warn if
    * a file containing API keys is readable by other users. */
  def load(paths: List[Path]): KeyBindings =
    val present = paths.filter(Files.isRegularFile(_)).distinctBy(_.toAbsolutePath.normalize)
    present.foreach { p =>
      if sharedReadable(p) then
        System.err.println(s"atc: warning: $p can be read by other users and holds API keys; chmod 600 it")
    }
    KeyBindings(present.map(p => p -> read(p)))

  /** Whether `p` is readable by its group or by other users. Returns `false`
    * when POSIX permissions are unavailable or cannot be read. */
  private def sharedReadable(p: Path): Boolean =
    try
      import java.nio.file.attribute.PosixFilePermission as P
      val perms = Files.getPosixFilePermissions(p).nn
      perms.contains(P.GROUP_READ) || perms.contains(P.OTHERS_READ)
    catch case _: Exception => false

  /** The bindings of one file, in the standard `.properties` format (`#` and
    * `!` comments, `=` or `:` after the name, `\` escapes and continuations;
    * `java.util.Properties` does the reading). An empty value means "not
    * bound", so the next source still applies. */
  private def read(path: Path): Map[String, String] =
    val props = Properties()
    try
      val text = TextFiles.stripBom(Files.readString(path).nn)
      props.load(StringReader(text))
    catch case e: Exception => throw IllegalArgumentException(s"Cannot read keys $path: ${e.getMessage}")
    props.stringPropertyNames().nn.asScala.iterator
      .map(name => name -> props.getProperty(name).nn.trim)
      .filter((_, value) => value.nonEmpty)
      .toMap
