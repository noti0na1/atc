package atc

import atc.config.{Config, Configuration, KeyBindings, ModelListStore, ProviderPreset}
import atc.llm.{ChatGPTAuth, ChatModel}
import atc.perms.{Decision, Policy, ScopeId}
import atc.platform.{Platform, PlatformPath}
import atc.ui.Tui

import java.nio.file.Files

/** What happens before a session starts: the first-run setup and the offer of
  * a project config. */
object Setup:
  import App.pretty

  /** Load the configuration, offering to write what is missing first. No
    * configuration is written without asking, and nothing is asked in a
    * scripted (`-p`) run:
    *
    *  - no `~/.atc/config.json`: offer to write the starting config and the
    *    key bindings beside it. Declined (or `-p`), the bundled starting config
    *    stands in for this run.
    *  - no config grants the working directory and it has no `.atc/config.json`
    *    of its own: offer to write the starting project config there (as
    *    `--init` does), and use it at once.
    *
    * When the global config was written the program then stops (via
    * [[App.Exit]]), so the user can fill in the keys or export them and start
    * again. */
  def load(args: Cli.Args, tui: Tui): Configuration =
    val interactive = args.prompt.isEmpty
    val global = Config.globalPath
    val globalKeys = global.getParent.nn.resolve(Config.KeysFile).nn
    val globalMissing = !Files.isRegularFile(global)
    // A `-c` file may define the providers itself, so only a plain start asks.
    val firstRun =
      if !globalMissing || !interactive || args.config.nonEmpty then FirstRun.Outcome.NotNow
      else
        tui.println(s"Welcome to atc. There is no configuration at ${pretty(global)} yet.")
        if tui.menusAvailable then
          val keys = KeyBindings.load(Config.projectRoot(args.cwd).map(Config.keysPath).toList :+ globalKeys)
          FirstRun.run(firstRunUi(tui), ProviderPreset.all, keys, ChatModel.listModels, signIn(again = false))
        else if tui.confirm("Write the starting config and key bindings there?") then
          FirstRun.Outcome.ConfigureYourself
        else FirstRun.Outcome.NotNow
    firstRun match
      case FirstRun.Outcome.ConfigureYourself =>
        val written = Config.ensureGlobal()
        if written.nonEmpty then tui.println(s"Wrote ${written.map(pretty).mkString(" and ")}.")
        tui.println(
          s"Add your providers and models to ${pretty(global)} and their API keys to ${pretty(globalKeys)} " +
            "(or export them in the environment), then start atc again."
        )
        throw App.Exit(0)
      case FirstRun.Outcome.Ready(provider, key, endpoint, models, model) =>
        for name <- provider.keyVariable; value <- key do KeyBindings.bind(globalKeys, name, value)
        Config.writeGlobalConfig(global, List(provider))
        ModelListStore.global.save(endpoint, models)
        Models.rememberLast(model.ref)
        tui.success(
          s"Wrote ${pretty(global)}${if key.isDefined then s" and ${pretty(globalKeys)}" else ""}; " +
            s"starting with ${model.ref}. /model switches models."
        )
      case FirstRun.Outcome.NotNow =>
        if globalMissing then
          tui.info(s"Using the built-in starting config for this run (`atc --init-global` writes it).")
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
          s"No configuration grants access to ${pretty(args.cwd)}, so the agent would have to ask for every file."
        )
        val accepted =
          tui.confirm(s"Write a starting project config to ${pretty(project)}? (It opens this directory to the agent)")
        if !accepted then current
        else
          val created = Config.initProject(args.cwd).map(pretty).mkString(" and ")
          tui.println(s"Wrote $created; edit it to change what the agent may touch here.")
          Config.load(args.cwd, args.config, bundledGlobal)

    // Offered whenever cwd has no `.atc/config.json` of its own and nothing
    // grants it, whatever an ancestor's project config (or the home `.atc`,
    // which the walk-up also finds) says: the new file becomes the nearest
    // project config and takes over from there.
    offerProjectConfig(Config.load(args.cwd, args.config, bundledGlobal))

  /** Sign in with a ChatGPT plan, keeping a sign-in already saved unless `again`. */
  def signIn(again: Boolean)(ui: FirstRun.Ui): Boolean =
    FirstRun.signIn(ui, ChatGPTAuth.default, Platform.openBrowser, again)

  def firstRunUi(tui: Tui): FirstRun.Ui = new FirstRun.Ui:
    def choose(title: String, options: List[String]): Option[String] = tui.choose(title, options)
    def askSecret(question: String): Option[String] = tui.askSecret(question)
    def info(text: String): Unit = tui.info(text)
    def error(text: String): Unit = tui.error(text)
