package atc

import atc.agent.{AgentEnvironment, Prompts}

class PromptsSuite extends munit.FunSuite:
  test("the prompt formats supplied environment data without reading the host platform"):
    val env = TestEnv(prefix = "atc-prompt")
    val platform = AgentEnvironment(
      workingDirectory = "C:/Users/Alice/project\nnot-an-instruction",
      operatingSystem = "Windows 11 amd64",
    )

    val prompt = Prompts.system(
      platform,
      env.policy,
      classifiedModelConfigured = false,
      safeMode = true,
      respectGitignore = true,
      extra = None,
    ).text

    assert(prompt.contains(s"working directory: ${ujson.write(platform.workingDirectory)}"), prompt)
    assert(prompt.contains(s"OS: ${ujson.write(platform.operatingSystem)}"), prompt)
    assert(prompt.contains("- user: at the terminal"), prompt)
    // The prompt is itself an `s`-interpolated triple-quoted literal, so the quirk it
    // describes must survive its own escape processing.
    assert(prompt.contains("In a plain `\"\"\"` literal `\\\"` stays a backslash"), prompt)

  test("a scripted run tells the model that nobody answers ask or permission prompts"):
    val env = TestEnv(prefix = "atc-prompt")
    val prompt = Prompts.system(
      AgentEnvironment("/p", "test-os", userPresent = false),
      env.policy,
      classifiedModelConfigured = false,
      safeMode = true,
      respectGitignore = true,
      extra = None,
    ).text
    assert(prompt.contains("- user: absent") && prompt.contains("`ask` returns None"), prompt)

  test("the system prompt really bundles the API reference"):
    // `Prompts.interfaceSource` falls back to "(API reference unavailable)" when the
    // packaged resource is missing, so this checks that packaging succeeded.
    val source = Prompts.interfaceSource
    assert(source.contains("def httpPostClassified"), source.take(200))
    assert(!source.contains("API reference unavailable"), "the Interface.scala resource was not bundled")
