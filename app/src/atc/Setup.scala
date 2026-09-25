package atc

import atc.config.{Config, Configuration, KeyBindings, ModelListStore, ProviderPreset}
import atc.llm.{ChatGPTAuth, ChatModel}
import atc.perms.{Decision, Policy, ScopeId}
import atc.platform.{Platform, PlatformPath}
import atc.ui.Tui

import java.nio.file.{Files, Path}

/** What happens before a session starts: the first-run setup and the offer of
  * a project config. */
object Setup:

  /** Load the configuration, offering to write what is missing first. No
    * configuration is written without asking, and nothing is asked in a
    * scripted (`-p`) run:
    *
    *  - no `~/.atc/config.json`: offer [[FirstRun]], which sets up one provider,
    *    or writes the starting config and key bindings for the user to edit.
    *    Declined (or `-p`), the bundled starting config stands in for this run.
    *  - no config grants the working directory and it has no `.atc/config.json`
    *    of its own: offer to write the starting project config there (as
    *    `--init` does), and use it at once.
    *
    * When the user chooses to edit the starting config, the program stops after
    * writing it (via [[App.Exit]]), so they can fill in the keys and start again. */
  def load(args: Cli.Args, tui: Tui): Configuration =
    val interactive = args.prompt.isEmpty
    val global = Config.globalPath
    val globalMissing = !Files.isRegularFile(global)
    // A `-c` file may define the providers itself, so only a plain start asks.
    val firstRun =
      if !globalMissing || !interactive || args.config.nonEmpty then FirstRun.Outcome.NotNow
      else
        tui.println(s"Welcome to atc. There is no configuration at ${PlatformPath.display(global)} yet.")
        if tui.menusAvailable then
          val keys = KeyBindings.load(Config.projectRoot(args.cwd).map(Config.keysPath).toList :+ Config.globalKeysPath)
          FirstRun.run(firstRunUi(tui), ProviderPreset.all, keys, ChatModel.listModels, signIn(again = false))
        else if tui.confirm("Write the starting config and key bindings there?") then
          FirstRun.Outcome.ConfigureYourself
        else FirstRun.Outcome.NotNow
    firstRun match
      case FirstRun.Outcome.ConfigureYourself =>
        val written = Config.ensureGlobal()
        if written.nonEmpty then tui.println(s"Wrote ${written.map(PlatformPath.display).mkString(" and ")}.")
        tui.println(
          s"Add your providers and models to ${PlatformPath.display(global)} and their API keys to ${PlatformPath.display(Config.globalKeysPath)} " +
            "(or export them in the environment), then start atc again."
        )
        throw App.Exit(0)
      case ready: FirstRun.Outcome.Ready =>
        saveKeyAndModels(ready)
        Config.writeGlobalConfig(global, List(ready.provider))
        Models.rememberLast(ready.model.ref)
        tui.success(
          s"Wrote ${PlatformPath.display(global)}${
              if ready.key.isDefined then s" and ${PlatformPath.display(Config.globalKeysPath)}" else ""
            }; " +
            s"starting with ${ready.model.ref}. /model switches models."
        )
      case FirstRun.Outcome.NotNow =>
        if globalMissing then
          tui.info("Using the built-in starting config for this run (`atc --init-global` writes it).")
    val bundledGlobal = globalMissing && firstRun == FirstRun.Outcome.NotNow

    def cwdReadable(c: Configuration): Boolean =
      Policy(c.fileRules(args.cwd), Nil, Nil, _ => Decision.Deny)
        .effective(ScopeId.Base, PlatformPath.canonical(args.cwd)).canRead

    def offerProjectConfig(current: Configuration): Configuration =
      val project = Config.projectPath(args.cwd)
      val shouldOffer = interactive && !cwdReadable(current) && !Files.exists(project)
      if !shouldOffer then current
      else
        tui.println(
          s"No configuration grants access to ${PlatformPath.display(args.cwd)}, so the agent would have to ask for every file."
        )
        val accepted =
          tui.confirm(
            s"Write a starting project config to ${PlatformPath.display(project)}? (It opens this directory to the agent)"
          )
        if !accepted then current
        else
          val created = Config.initProject(args.cwd).map(PlatformPath.display).mkString(" and ")
          tui.println(s"Wrote $created; edit it to change what the agent may touch here.")
          Config.load(args.cwd, args.config, bundledGlobal)

    // Offered whenever cwd has no `.atc/config.json` of its own and nothing
    // grants it, whatever an ancestor's project config (or the home `.atc`,
    // which the walk-up also finds) says: the new file becomes the nearest
    // project config and takes over from there.
    offerProjectConfig(Config.load(args.cwd, args.config, bundledGlobal))

  /** Save what setting up a provider produced besides its config entry: the key
    * the user typed, and the model list fetched with it. */
  def saveKeyAndModels(ready: FirstRun.Outcome.Ready): Unit =
    for name <- ready.provider.keyVariable; value <- ready.key do KeyBindings.bind(Config.globalKeysPath, name, value)
    ModelListStore.global.save(ready.endpoint, ready.models)

  /** Sign in with a ChatGPT plan, keeping a sign-in already saved unless `again`. */
  def signIn(again: Boolean)(ui: FirstRun.Ui): Boolean =
    FirstRun.signIn(ui, ChatGPTAuth.default, Platform.openBrowser, again)

  def firstRunUi(tui: Tui): FirstRun.Ui = new FirstRun.Ui:
    def choose(title: String, options: List[String]): Option[String] = tui.choose(title, options)
    def askSecret(question: String): Option[String] = tui.askSecret(question)
    def info(text: String): Unit = tui.info(text)
    def error(text: String): Unit = tui.error(text)
