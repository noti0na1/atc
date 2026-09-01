package atc

import atc.perms.{Decision, ExecRequest, Mode}
import atc.platform.Platform
import java.nio.file.{Files, Paths}

/** CLI parsing, launcher transport, and argument validation. */
class MainSuite extends munit.FunSuite:
  private def parse(args: String*): Cli.Args = Cli.parse(args.toList)

  test("defaults and the long/short flag forms"):
    val d = parse()
    assert(d.config.isEmpty && d.model.isEmpty && d.prompt.isEmpty && d.mode.isEmpty)
    assert(!d.approveAll && !d.init && !d.initGlobal && !d.help && !d.version)
    val a = parse("-C", "/work", "-m", "gpt", "-p", "hi", "--mode", "local", "--approve-all")
    assertEquals(a.cwd, Paths.get("/work").toAbsolutePath.normalize)
    assertEquals(a.model, Some("gpt"))
    assertEquals(a.prompt, Some("hi"))
    assertEquals(a.mode, Some(Mode.Local))
    assert(a.approveAll)
    val absoluteCwd = Files.createTempDirectory("atc-main-absolute").nn.toAbsolutePath.nn.normalize.nn
    val absoluteConfig = absoluteCwd.resolve("c.json").nn
    val b = parse("--cwd", absoluteCwd.toString, "--config", absoluteConfig.toString, "--prompt", "x")
    assertEquals(b.cwd, absoluteCwd)
    assertEquals(b.config, Some(absoluteConfig))
    assert(parse("--init").init)
    assert(parse("--init-global").initGlobal)
    assert(parse("--help").help && parse("-h").help)
    assert(parse("--version").version && parse("-v").version)

  test("relative cwd and config resolve consistently regardless of flag order"):
    val base = Files.createTempDirectory("atc-main-base").nn.toAbsolutePath.nn.normalize.nn
    val expectedCwd = base.resolve("project").nn
    val expectedConfig = Some(base.resolve("project/extra.json").nn)
    val cwdFirst = Cli.parse(List("--cwd", "project", "--config", "extra.json"), Cli.Args(cwd = base))
    val configFirst = Cli.parse(List("--config", "extra.json", "--cwd", "project"), Cli.Args(cwd = base))
    assertEquals(cwdFirst.cwd, expectedCwd)
    assertEquals(cwdFirst.config, expectedConfig)
    assertEquals(configFirst.cwd, expectedCwd)
    assertEquals(configFirst.config, expectedConfig)

  test("unknown arguments and a bad mode are clear errors"):
    val e = intercept[IllegalArgumentException](parse("--bogus"))
    assert(e.getMessage.nn.contains("Unknown argument"), e.getMessage)
    intercept[IllegalArgumentException](parse("--mode", "bogus"))
    val missing = intercept[IllegalArgumentException](parse("-p"))
    assert(missing.getMessage.nn.contains("requires a value"), missing.getMessage)

  test("the internal Windows argument protocol restores Unicode, quotes, and empty values"):
    val values = List("-C", "C:\\仕事 dir", "-p", "run: println(\"Ω !\")", "")
    val environment =
      (Map("ATC_INTERNAL_ARG_COUNT" -> values.length.toString) ++
        values.zipWithIndex.map((value, index) => s"ATC_INTERNAL_ARG_$index" -> s"x$value")).get
    assertEquals(LauncherEnvironment.arguments(List("ignored"), environment), values)
    assertEquals(LauncherEnvironment.arguments(List("direct"), _ => None), List("direct"))
    intercept[IllegalArgumentException](
      LauncherEnvironment.arguments(Nil, Map("ATC_INTERNAL_ARG_COUNT" -> "1").get)
    )
    intercept[IllegalArgumentException](
      LauncherEnvironment.arguments(
        Nil,
        Map("ATC_INTERNAL_ARG_COUNT" -> "1", "ATC_INTERNAL_ARG_0" -> "missing-sentinel").get,
      )
    )
    assert(LauncherEnvironment.isInternal("atc_Internal_Arg_0"))

    val cwd = Files.createTempDirectory("atc-launch-cwd").nn.toAbsolutePath.nn.normalize.nn
    assertEquals(
      LauncherEnvironment.workingDirectory(Map("ATC_INTERNAL_LAUNCH_CWD" -> cwd.toString).get),
      cwd,
    )

  test("Windows-friendly path syntax expands home and validates paths before running"):
    assertEquals(parse("-C", "~").cwd, Paths.get(scala.util.Properties.userHome).toAbsolutePath.normalize)
    val missingDir = Files.createTempDirectory("atc-main-missing").nn.resolve("gone")
    val cwdError = intercept[IllegalArgumentException](Cli.validate(Cli.Args(cwd = missingDir)))
    assert(cwdError.getMessage.nn.contains("does not exist"), cwdError.getMessage)
    val file = Files.createTempFile("atc-main-file", ".txt").nn
    val fileError = intercept[IllegalArgumentException](Cli.validate(Cli.Args(cwd = file)))
    assert(fileError.getMessage.nn.contains("not a directory"), fileError.getMessage)
    val cwd = Files.createTempDirectory("atc-main-cwd").nn
    val configError = intercept[IllegalArgumentException](
      Cli.validate(Cli.Args(cwd = cwd, config = Some(cwd.resolve("missing.json"))))
    )
    assert(configError.getMessage.nn.contains("Config file does not exist"), configError.getMessage)
    // Informational/global-init actions do not depend on cwd or an explicit config.
    Cli.validate(Cli.Args(cwd = missingDir, help = true, config = Some(missingDir)))
    if Platform.isWindows then
      intercept[IllegalArgumentException](parse("-C", "C:work"))
      intercept[IllegalArgumentException](parse("-c", "NUL.json"))

  test("scripted runs deny permission requests without prompting unless approve-all is explicit"):
    val request = ExecRequest(List("git status"), "test")
    var asked = 0
    val interactive: atc.perms.PermissionRequest => Decision = _ => { asked += 1; Decision.AllowOnce }
    val scripted = App.permissionPrompter(Cli.Args(prompt = Some("work")), interactive)
    val denied = intercept[SecurityException](scripted.ask(request))
    assert(denied.getMessage.nn.contains("non-interactive run cannot ask"), denied.getMessage)
    assertEquals(asked, 0)
    val approved = App.permissionPrompter(Cli.Args(prompt = Some("work"), approveAll = true), interactive)
    assertEquals(approved.ask(request), Decision.AllowSession)
    assertEquals(asked, 0)
    val normal = App.permissionPrompter(Cli.Args(), interactive)
    assertEquals(normal.ask(request), Decision.AllowOnce)
    assertEquals(asked, 1)
