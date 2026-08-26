package atc

import atc.host.CommandLine
import atc.platform.Platform

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.Locale

/** A tiny child JVM used by process tests. Unlike `echo`, `cat`, `sort`,
  * `sleep`, and friends, it exists on every platform that can run the tests and
  * has a fixed UTF-8/LF contract. */
object TestProcess:
  private def write(text: String): Unit =
    System.out.write(text.getBytes(UTF_8))
    System.out.flush()

  private def writeErr(text: String): Unit =
    System.err.write(text.getBytes(UTF_8))
    System.err.flush()

  private def copy(in: java.io.InputStream): Unit =
    val buffer = new Array[Byte](8192)
    var count = in.read(buffer)
    while count >= 0 do
      if count > 0 then
        System.out.write(buffer, 0, count)
        System.out.flush()
      count = in.read(buffer)

  def main(args: Array[String]): Unit = args.toList match
    case "echo" :: rest => write(rest.mkString(" ") + "\n")
    case "unsorted" :: Nil => write("c\nb\na\n")
    case "pwd" :: Nil => write(Path.of("").toAbsolutePath.nn.normalize.nn.toString + "\n")
    case "cat" :: Nil => copy(System.in)
    case "cat" :: files => files.foreach(file => System.out.write(Files.readAllBytes(Path.of(file)).nn))
    case "upper" :: Nil =>
      val text = String(System.in.readAllBytes(), UTF_8)
      write(text.toUpperCase(Locale.ROOT))
    case "sort" :: Nil =>
      val text = String(System.in.readAllBytes(), UTF_8)
      text.linesIterator.toList.sorted.foreach(line => write(line + "\n"))
    case "fail" :: code :: rest =>
      if rest.nonEmpty then writeErr(rest.mkString(" ") + "\n")
      sys.exit(code.toInt)
    case "sleep" :: millis :: Nil => Thread.sleep(millis.toLong)
    case other =>
      writeErr(s"bad TestProcess arguments: ${other.mkString(" ")}\n")
      sys.exit(64)

/** Builds command lines for [[TestProcess]] using the same injective rendering
  * as the host's process grammar. `pattern(mode)` permits that fixture mode and
  * any following arguments, without permitting every invocation of Java. */
object ProcessFixture:
  private val javaExecutable: String =
    Path.of(sys.props("java.home"), "bin", if Platform.isWindows then "java.exe" else "java").nn.toString
  private val classpath: String = sys.props("java.class.path").nn
  private val base: List[String] =
    List(javaExecutable, "-Dfile.encoding=UTF-8", "-cp", classpath, "atc.TestProcess")

  def line(argv: String*): String = CommandLine.Stage(argv.toList).line

  def command(mode: String, args: String*): String = line((base ++ (mode +: args))*)

  def pattern(mode: String): String = CommandLine.parsePipeline(command(mode)).stages.head.line
