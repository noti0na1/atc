package atc

import atc.host.{CommandLine, Processes, WindowsExecutable}
import atc.lib.{Exec, FileSystem}
import atc.platform.Platform

import java.nio.file.{Files, Path}

/** Small native-tool checks kept apart from the portable process contract in
  * [[PermissionSuite]]. */
class UnixProcessIntegrationSuite extends munit.FunSuite:
  test("a real Unix pipeline is connected and reports pipefail"):
    assume(!Platform.isWindows)
    val env = TestEnv(commands = List("printf", "tr", "cat", "false"))
    import env.given
    given Exec = env.host.processes
    given FileSystem = env.host.fileSystem
    assertEquals(env.host.exec("printf hello | tr a-z A-Z | cat").stdout, "HELLO")
    assertEquals(env.host.exec("false | cat").exitCode, 1)

class WindowsProcessIntegrationSuite extends munit.FunSuite:
  test("the low-level process boundary always enables strict Windows quoting"):
    assume(Platform.isWindows)
    System.setProperty("jdk.lang.Process.allowAmbiguousCommands", "true")
    val argv = CommandLine.parseCommandLine(ProcessFixture.command("echo", "strict"))
    val result = Processes.run(ProcessBuilder(argv*), "test process", 10_000L)
    assertEquals(result.stdout.trim, "strict")
    assertEquals(System.getProperty("jdk.lang.Process.allowAmbiguousCommands"), "false")

  test("PATH/PATHEXT resolution excludes cwd and validates script entry points"):
    assume(Platform.isWindows)
    val cwd = Files.createTempDirectory("atc-win-cwd").nn.toRealPath().nn
    val bin = Files.createTempDirectory("atc-win-path").nn.toRealPath().nn
    val shadow = Files.writeString(cwd.resolve("tool.exe"), "not an executable").nn
    val exe = Files.writeString(bin.resolve("tool.exe"), "not an executable").nn
    val cmd = Files.writeString(bin.resolve("tool.cmd"), "@exit /b 0\r\n").nn
    val environment = Map("PATH" -> bin.toString, "PATHEXT" -> ".CMD;.EXE")
    val resolved = WindowsExecutable.resolve(List("tool"), cwd, environment)
    assertEquals(Path.of(resolved.head).toRealPath(), cmd.toRealPath())
    assertNotEquals(Path.of(resolved.head).toRealPath(), shadow.toRealPath())
    val unsafe = intercept[IllegalArgumentException](
      WindowsExecutable.resolve(List("tool", "%PATH%"), cwd, environment)
    )
    assert(unsafe.getMessage.nn.contains("expanded by cmd.exe"), unsafe.getMessage)
    val ps1 = Files.writeString(bin.resolve("script.ps1"), "exit 0\r\n").nn
    val unsupported = intercept[IllegalArgumentException](
      WindowsExecutable.resolve(List(ps1.toString), cwd, environment)
    )
    assert(unsupported.getMessage.nn.contains("interpreter explicitly"), unsupported.getMessage)
    assertEquals(System.getProperty("jdk.lang.Process.allowAmbiguousCommands"), "false")

  test("an explicit cmd wrapper receives one argument containing spaces"):
    assume(Platform.isWindows)
    val env = TestEnv(commands = Nil)
    val script = env.root.resolve("test process.cmd").nn
    Files.writeString(
      script,
      "@echo off\r\nif \"%~1\"==\"two words\" (echo cmd-ok) else (echo bad-arg 1>&2 & exit /b 9)\r\n"
    )
    val command = ProcessFixture.line(script.toString)
    val allowed = TestEnv(commands = List(CommandLine.parsePipeline(command).stages.head.line))
    import allowed.given
    given Exec = allowed.host.processes
    given FileSystem = allowed.host.fileSystem
    val result = allowed.host.exec(command, List("two words"))
    assertEquals(result.exitCode, 0, result.stderr)
    assertEquals(result.stdout.trim, "cmd-ok")

  test("BOM-marked UTF-16 from a native Windows process is decoded"):
    assume(Platform.isWindows)
    val powershell = Path.of(
      sys.env("SystemRoot"),
      "System32",
      "WindowsPowerShell",
      "v1.0",
      "powershell.exe",
    ).nn.toString
    val command = ProcessFixture.line(powershell)
    val env = TestEnv(commands = List(CommandLine.parsePipeline(command).stages.head.line))
    import env.given
    given Exec = env.host.processes
    given FileSystem = env.host.fileSystem
    val script =
      "$b=[byte[]](255,254,104,0,233,0,108,0,108,0,111,0);[Console]::OpenStandardOutput().Write($b,0,$b.Length)"
    val result = env.host.exec(command, List("-NoProfile", "-Command", script))
    assertEquals(result.exitCode, 0, result.stderr)
    assertEquals(result.stdout, "héllo")
