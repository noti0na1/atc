package atc.sandbox

import atc.{LauncherEnvironment, ProcessEnvironment}
import atc.lib.{Interface, Runtime, Derivations}
import atc.platform.Platform

import java.nio.file.{Files, Path, Paths}

/** Isolates REPL classes from application internals. Agent code compiles against
  * `atc.lib` and the Scala standard library. The parent loader shares those
  * classes with the application and delegates other classes to the JDK platform
  * loader. Sharing the API classes allows direct calls to the host implementation. */
object Sandbox:

  val ClasspathProperty = "atc.lib.classpath"
  private val ClasspathEnvironment = LauncherEnvironment.LibraryClasspath

  /** The compile classpath for agent code. Development/Unix launchers use the
    * system property; the Windows batch launcher uses the environment because
    * java.exe cannot carry every Unicode path through its legacy argv encoding. */
  lazy val libraryClasspath: Seq[Path] =
    def setting(source: String, value: Option[String]) = value.map(_.trim).filter(_.nonEmpty).map(source -> _)
    val configured = setting(ClasspathEnvironment, ProcessEnvironment.get(ClasspathEnvironment))
      .orElse(setting(ClasspathProperty, sys.props.get(ClasspathProperty)))
    configured match
      case Some((source, cp)) =>
        val paths = cp.split(Platform.pathListSeparator).toSeq.filter(_.nonEmpty)
          .map(p => Paths.get(p).toAbsolutePath.nn.normalize.nn).filter(Files.exists(_))
        if paths.isEmpty then
          throw IllegalStateException(s"No existing entries in $source=$cp")
        paths
      case None =>
        throw IllegalStateException(
          s"Neither $ClasspathEnvironment nor $ClasspathProperty is set; one must list the capability library classpath."
        )

  /** `dotty.tools.repl.StopRepl` as the REPL asks for it, and the resource the build bundles it as. */
  private val StopReplClassFile = "dotty/tools/repl/StopRepl.class"
  private val StopReplBytes = "atc/StopRepl.class.bin"

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
    /** The one class file the REPL loader must read: it defines its own copy
      * of `StopRepl` (the stop flag checked by instrumented code) from the
      * parent's bytes. Served from the build's renamed copy, which also
      * exists in a native image where no `.class` resource does. */
    override def getResourceAsStream(name: String): java.io.InputStream | Null =
      if name == StopReplClassFile then app.getResourceAsStream(StopReplBytes)
      else super.getResourceAsStream(name)

  def newLoader(): ClassLoader = SandboxLoader(classOf[Interface].getClassLoader.nn)

  /** Make `host` the sandbox's API implementation. */
  def installHost(host: Interface & Derivations): Unit = Runtime.install(host)
