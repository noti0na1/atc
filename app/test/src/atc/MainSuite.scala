package atc

import atc.perms.{Decision, ExecRequest, Mode}

/** The CLI argument parser (Main.parseArgs). */
class MainSuite extends munit.FunSuite:
  private def parse(args: String*): atc.Main.Args = atc.Main.parseArgs(args.toList)

  test("defaults and the long/short flag forms"):
    val d = parse()
    assert(d.config.isEmpty && d.model.isEmpty && d.prompt.isEmpty && d.mode.isEmpty)
    assert(!d.approveAll && !d.init && !d.initGlobal && !d.help && !d.version)
    val a = parse("-C", "/work", "-m", "gpt", "-p", "hi", "--mode", "local", "--approve-all")
    assertEquals(a.cwd.toString, "/work")
    assertEquals(a.model, Some("gpt"))
    assertEquals(a.prompt, Some("hi"))
    assertEquals(a.mode, Some(Mode.Local))
    assert(a.approveAll)
    val b = parse("--cwd", "/w2", "--config", "/c.json", "--prompt", "x")
    assertEquals(b.cwd.toString, "/w2")
    assertEquals(b.config.map(_.toString), Some("/c.json"))
    assert(parse("--init").init)
    assert(parse("--init-global").initGlobal)
    assert(parse("--help").help && parse("-h").help)
    assert(parse("--version").version && parse("-v").version)

  test("unknown arguments and a bad mode are clear errors"):
    val e = intercept[IllegalArgumentException](parse("--bogus"))
    assert(e.getMessage.nn.contains("Unknown argument"), e.getMessage)
    intercept[IllegalArgumentException](parse("--mode", "bogus"))
    intercept[IllegalArgumentException](parse("-p")) // missing value

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
