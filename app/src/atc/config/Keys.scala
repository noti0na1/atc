package atc.config

import atc.{Debug, ProcessEnvironment, TextFiles}

import java.io.StringReader
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*

/** API-key bindings from project and global `keys.properties` files.
  * Configuration files reference keys with `${VAR}` instead of storing them.
  * Lookup uses project bindings, global bindings, then the process environment;
  * empty values are skipped. The default policy denies access to `.atc`. */
final case class KeyBindings(files: List[(Path, Map[String, String])]):
  /** The value bound to `name`, from the files in order and then the
    * environment; `None` when nothing binds it to a non-empty value. */
  def get(name: String): Option[String] =
    files.iterator.flatMap((_, bindings) => bindings.get(name)).nextOption()
      // The live process-environment lookup follows the platform's name rules;
      // Windows names are case-insensitive, while a copied Scala Map is not.
      .orElse(ProcessEnvironment.get(name).filter(_.nonEmpty))

  /** The names these files bind, for `/config`. Never the values. */
  def names: List[String] = files.flatMap(_._2.keys).distinct.sorted

  def sources: List[Path] = files.map(_._1)

  /** A `${VAR}` reference resolved through these bindings; anything else is the
    * literal value. `None` when nothing binds the variable or the literal is
    * empty, the same as an empty value in `keys.properties`. */
  def resolve(value: String): Option[String] = KeyBindings.envRefName(value) match
    case Some(name) => get(name)
    case None => Option.when(value.nonEmpty)(value)

  /** The provider's key: `key` (a literal or `${VAR}`), else the variable
    * `keyEnv` names, else none, which leaves the SDK to resolve its own default
    * variable. */
  def apiKey(p: ProviderConfig): Option[String] = p.key.flatMap(resolve).orElse(p.keyEnv.flatMap(get))

  /** The provider's extra headers with `${VAR}` values resolved; a header whose
    * variable is unset is dropped, and [[ProviderConfig.SessionRef]] is kept for
    * each request to fill in. */
  def headers(p: ProviderConfig): Map[String, String] =
    p.headers.flatMap: (name, value) =>
      (if value == ProviderConfig.SessionRef then Some(value) else resolve(value)).map(name -> _)

object KeyBindings:
  val empty: KeyBindings = KeyBindings(Nil)

  private val EnvRef = """\$\{([A-Za-z_][A-Za-z0-9_]*)\}""".r

  /** The variable a `${VAR}` value names. */
  def envRefName(value: String): Option[String] = value match
    case EnvRef(name) => Some(name.nn)
    case _ => None

  /** Bind `name` to `value` in the key file at `path`: an existing binding of
    * `name` is replaced and every other line is kept. A new file is readable
    * by its owner only. */
  def bind(path: Path, name: String, value: String): Unit =
    require(!value.exists(c => c == '\n' || c == '\r'), "a key must be a single line")
    val line = s"$name=${value.replace("\\", "\\\\")}"
    if !Files.exists(path) then
      Option(path.getParent).foreach(Files.createDirectories(_))
      Config.writeOwnerOnly(path, s"# API keys for the providers in config.json, one NAME=value per line.\n$line\n")
    else
      val lines = TextFiles.splitLines(TextFiles.stripBom(Files.readString(path).nn)).lines
      val binds = s"^\\s*${Pattern.quote(name)}\\s*[=:\\s]".r
      val kept = lines.filterNot(l => binds.findFirstIn(l + " ").isDefined)
      Files.writeString(path, (kept :+ line).mkString("", "\n", "\n"))

  /** Read existing `keys.properties` files from most to least specific. Warn if
    * a file containing API keys is readable by other users. */
  def load(paths: List[Path]): KeyBindings =
    val present = paths.filter(Files.isRegularFile(_)).distinctBy(_.toAbsolutePath.normalize)
    present.foreach: p =>
      if sharedReadable(p) then
        System.err.println(s"atc: warning: $p can be read by other users and holds API keys; chmod 600 it")
    KeyBindings(present.map(p => p -> read(p)))

  /** Whether `p` is readable by its group or by other users. Returns `false`
    * when POSIX permissions are unavailable or cannot be read. */
  private def sharedReadable(p: Path): Boolean =
    try
      val permissions = Files.getPosixFilePermissions(p).nn
      permissions.contains(PosixFilePermission.GROUP_READ) || permissions.contains(PosixFilePermission.OTHERS_READ)
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
    catch case e: Exception => throw IllegalArgumentException(s"Cannot read keys $path: ${Debug.message(e)}")
    props.stringPropertyNames().nn.asScala.iterator
      .map(name => name -> props.getProperty(name).nn.trim)
      .filter((_, value) => value.nonEmpty)
      .toMap
