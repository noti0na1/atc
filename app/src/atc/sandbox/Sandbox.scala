package atc.sandbox

import atc.lib.{Interface, Runtime}

import java.io.File
import java.nio.file.{Files, Path, Paths}

/** Class-loader isolation for agent code.
  *
  * The REPL compiles agent code against the *library classpath* (`atc.lib` +
  * the Scala standard library, as jars/dirs) and loads the compiled classes
  * into a loader whose parent is [[SandboxLoader]]:
  *
  * {{{
  *   REPL loader → SandboxLoader → { app loader for scala.* and atc.lib.*,
  *                                    platform loader for the JDK }
  * }}}
  *
  * so agent code sees the JDK, the Scala standard library and the capability
  * API — the very same classes the application uses, which is what lets the
  * application implement `atc.lib.Interface` directly and the sandbox call it
  * without any marshalling — but nothing else of the application (LLM
  * clients, config, policy, UI, compiler, their dependencies).
  */
object Sandbox:

  val ClasspathProperty = "atc.lib.classpath"

  /** The compile classpath for agent code, from `-Datc.lib.classpath=<path list>`. */
  lazy val libraryClasspath: Seq[Path] =
    Option(System.getProperty(ClasspathProperty)).map(_.trim).filter(_.nonEmpty) match
      case Some(cp) =>
        val paths = cp.split(File.pathSeparator).toSeq.filter(_.nonEmpty)
          .map(p => Paths.get(p).toAbsolutePath.nn.normalize.nn).filter(Files.exists(_))
        if paths.isEmpty then
          throw IllegalStateException(s"No existing entries in $ClasspathProperty=$cp")
        paths
      case None =>
        throw IllegalStateException(
          s"System property $ClasspathProperty is not set; it must list the capability library classpath."
        )

  private val sharedPrefixes = List("scala.", "atc.lib.")
  /** Compiler-internal packages that live under `scala.` in the app loader. */
  private val hiddenPrefixes = List("scala.quoted.runtime.impl.", "scala.tools.")

  def isShared(name: String): Boolean =
    sharedPrefixes.exists(name.startsWith) && !hiddenPrefixes.exists(name.startsWith)

  /** Delegates the shared packages to the application loader and everything
    * else to the platform loader (i.e. the JDK only).
    *
    * Class *resources* (`*.class`) are hidden: with interrupt instrumentation
    * enabled, the REPL loader would otherwise read the bytecode of every
    * non-JDK class through its parent and re-define an instrumented copy of
    * it — including `atc.lib.Interface`, whose static state holds the
    * installed host. Without the resource the REPL falls back to normal
    * delegation, so shared classes stay shared and only REPL-defined classes
    * are instrumented. */
  final class SandboxLoader(app: ClassLoader) extends ClassLoader("atc-sandbox", ClassLoader.getPlatformClassLoader):
    override protected def loadClass(name: String, resolve: Boolean): Class[?] =
      if isShared(name) then
        val c = app.loadClass(name).nn
        if resolve then resolveClass(c)
        c
      else super.loadClass(name, resolve).nn
    override def getResource(name: String): java.net.URL | Null =
      if name.endsWith(".class") then null else super.getResource(name)

  def newLoader(): ClassLoader = SandboxLoader(classOf[Interface].getClassLoader.nn)

  /** Make `host` the sandbox's API implementation. */
  def installHost(host: Interface): Unit = Runtime.install(host)
