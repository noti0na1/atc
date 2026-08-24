package atc

import atc.perms.{Decision, ExecRequest, Mode}
import java.nio.file.{Files, Paths}

/** The CLI argument parser (Main.parseArgs). */
class MainSuite extends munit.FunSuite:
  private def parse(args: String*): atc.Main.Args = atc.Main.parseArgs(args.toList)

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
    val b = parse("--cwd", "/w2", "--config", "/c.json", "--prompt", "x")
    assertEquals(b.cwd, Paths.get("/w2").toAbsolutePath.normalize)
    assertEquals(b.config, Some(Paths.get("/c.json")))
    assert(parse("--init").init)
    assert(parse("--init-global").initGlobal)
    assert(parse("--help").help && parse("-h").help)
    assert(parse("--version").version && parse("-v").version)

  test("unknown arguments and a bad mode are clear errors"):
    val e = intercept[IllegalArgumentException](parse("--bogus"))
    assert(e.getMessage.nn.contains("Unknown argument"), e.getMessage)
    intercept[IllegalArgumentException](parse("--mode", "bogus"))
    val missing = intercept[IllegalArgumentException](parse("-p"))
    assert(missing.getMessage.nn.contains("requires a value"), missing.getMessage)

  test("Windows-friendly path syntax expands home and validates paths before running"):
    assertEquals(parse("-C", "~").cwd, Paths.get(scala.util.Properties.userHome).toAbsolutePath.normalize)
    val missingDir = Files.createTempDirectory("atc-main-missing").nn.resolve("gone")
    val cwdError = intercept[IllegalArgumentException](Main.validateArgs(Main.Args(cwd = missingDir)))
    assert(cwdError.getMessage.nn.contains("does not exist"), cwdError.getMessage)
    val file = Files.createTempFile("atc-main-file", ".txt").nn
    val fileError = intercept[IllegalArgumentException](Main.validateArgs(Main.Args(cwd = file)))
    assert(fileError.getMessage.nn.contains("not a directory"), fileError.getMessage)
    val cwd = Files.createTempDirectory("atc-main-cwd").nn
    val configError = intercept[IllegalArgumentException](
      Main.validateArgs(Main.Args(cwd = cwd, config = Some(cwd.resolve("missing.json"))))
    )
    assert(configError.getMessage.nn.contains("Config file does not exist"), configError.getMessage)
    // Informational/global-init actions do not depend on cwd or an explicit config.
    Main.validateArgs(Main.Args(cwd = missingDir, help = true, config = Some(missingDir)))
    if java.io.File.separatorChar == '\\' then
      intercept[IllegalArgumentException](parse("-C", "C:work"))
      intercept[IllegalArgumentException](parse("-c", "NUL.json"))

  test("scripted runs deny permission requests without prompting unless approve-all is explicit"):
    val request = ExecRequest(List("git status"), "test")
    var asked = 0
    def interactive(request: atc.perms.PermissionRequest): Decision = { asked += 1; Decision.AllowOnce }
    val scripted = App.permissionPrompter(Main.Args(prompt = Some("work")), interactive)
    val denied = intercept[SecurityException](scripted.ask(request))
    assert(denied.getMessage.nn.contains("non-interactive run cannot ask"), denied.getMessage)
    assertEquals(asked, 0)
    val approved = App.permissionPrompter(Main.Args(prompt = Some("work"), approveAll = true), interactive)
    assertEquals(approved.ask(request), Decision.AllowSession)
    assertEquals(asked, 0)
    val normal = App.permissionPrompter(Main.Args(), interactive)
    assertEquals(normal.ask(request), Decision.AllowOnce)
    assertEquals(asked, 1)
