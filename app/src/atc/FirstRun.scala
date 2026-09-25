package atc

import atc.config.{Config, KeyBindings, ModelCatalog, ModelSpec, ProviderPreset}
import atc.llm.ChatGPTAuth

import java.nio.file.Path
import java.time.Duration
import scala.annotation.tailrec
import scala.util.Using
import scala.util.control.NonFatal

/** The first interactive start without a global config: choose one provider,
  * give its key (or sign in, for ChatGPT), and choose a model from the list
  * the provider returns. The key is checked by fetching that list. This object
  * only asks; `Setup.load` writes what the user chose. */
object FirstRun:
  /** What the first run needs from the terminal. */
  trait Ui:
    /** A menu; `None` when cancelled. */
    def choose(title: String, options: List[String]): Option[String]
    /** A secret typed without echo; `None` when empty or cancelled. */
    def askSecret(question: String): Option[String]
    def info(text: String): Unit
    def error(text: String): Unit

  enum Outcome:
    /** Set up `provider` with `model`, one of the `models` fetched from `endpoint`.
      * `key` is the key the user typed; `None` when one was already bound. */
    case Ready(
      provider: ProviderPreset,
      key: Option[String],
      endpoint: ModelSpec,
      models: List[ModelSpec],
      model: ModelSpec
    )
    /** Write the starting config with every provider and let the user edit it. */
    case ConfigureYourself
    /** Write nothing and run on the built-in defaults. */
    case NotNow

  val ConfigureYourselfLabel = "Configure providers myself (write a starting config and exit)"
  val NotNowLabel = "Not now (use the built-in defaults for this run)"

  /** Ask until the user sets up a provider or chooses one of the other two ways.
    * `keys` are the bindings already in force; `list` fetches a provider's models;
    * `signIn` signs in to a provider that takes no key (ChatGPT). */
  @tailrec def run(
    ui: Ui,
    presets: List[ProviderPreset],
    keys: KeyBindings,
    list: ModelSpec => List[ModelSpec],
    signIn: Ui => Boolean,
  ): Outcome =
    ui.choose("Choose a model provider to set up", presets.map(_.label) :+ ConfigureYourselfLabel :+ NotNowLabel) match
      case None | Some(NotNowLabel) => Outcome.NotNow
      case Some(ConfigureYourselfLabel) => Outcome.ConfigureYourself
      case Some(label) =>
        provider(ui, presets.find(_.label == label).get, keys, list, signIn) match
          case Some(ready) => ready
          case None => run(ui, presets, keys, list, signIn)

  /** One provider: a key or a sign-in, its model list, a model. `None` when the user gave up on it. */
  def provider(
    ui: Ui,
    preset: ProviderPreset,
    keys: KeyBindings,
    list: ModelSpec => List[ModelSpec],
    signIn: Ui => Boolean,
  ): Option[Outcome] =
    def ask(): Option[String] =
      preset.keyUrl.foreach(url => ui.info(s"Create a key at $url"))
      ui.askSecret(s"Paste your ${preset.label} API key (saved in ~/.atc/keys.properties, readable only by you)")

    @tailrec def attempt(typed: Option[String]): Option[Outcome] =
      val bindings = (preset.keyVariable, typed) match
        case (Some(name), Some(key)) => KeyBindings((Path.of("(typed)"), Map(name -> key)) :: keys.files)
        case _ => keys
      endpoint(preset, bindings) match
        case None =>
          ask() match
            case None => None
            case Some(key) => attempt(Some(key))
        case Some(spec) =>
          if typed.isEmpty then preset.keyVariable.foreach(name => ui.info(s"Using $name, which is already set."))
          ui.info(s"Fetching the models of ${preset.label}...")
          val fetched =
            try Right(list(spec))
            catch case NonFatal(e) => Left(reasons(e))
          fetched match
            case Right(Nil) =>
              ui.error(s"${preset.label} lists no models.")
              None
            case Right(models) => chooseModel(ui, models).map(Outcome.Ready(preset, typed, spec, models, _))
            case Left(why) =>
              ui.error(s"Could not list the models of ${preset.label}: $why")
              // Without a key there is nothing to correct (a local server that is not running).
              if preset.keyVariable.isEmpty then None
              else
                ask() match
                  case None => None
                  case Some(key) => attempt(Some(key))

    if preset.api == "chatgpt" && !signIn(ui) then None else attempt(None)

  /** Sign in with a ChatGPT plan in the browser, unless already signed in and not `again`. */
  def signIn(ui: Ui, auth: ChatGPTAuth, openBrowser: String => Boolean, again: Boolean): Boolean =
    auth.load().filter(_ => !again) match
      case Some(saved) =>
        ui.info(s"Using your ChatGPT sign-in${saved.email.fold("")(e => s" ($e)")}.")
        true
      case None =>
        try
          Using.resource(auth.begin()): login =>
            ui.info(
              if openBrowser(login.url) then "Sign in to ChatGPT in the browser window that opened, or open:"
              else "Open this address in a browser to sign in to ChatGPT:"
            )
            ui.info(login.url)
            awaitSignIn(ui, login)
        catch
          case NonFatal(e) =>
            ui.error(s"Could not sign in: ${reasons(e)}")
            false

  @tailrec private def awaitSignIn(ui: Ui, login: ChatGPTAuth#Login): Boolean =
    val pasted = ui.askSecret(
      "Press Enter once the browser says you are signed in (or paste the address it ended on if that page did not load)"
    )
    // The browser's callback may still be exchanging its code for tokens.
    val waited = if pasted.isEmpty && login.callbackReceived then Duration.ofSeconds(30) else Duration.ZERO
    pasted.map(login.paste).orElse(login.await(waited)) match
      case Some(Right(tokens)) =>
        ui.info(s"Signed in to ChatGPT${tokens.email.fold("")(e => s" as $e")}.")
        true
      case Some(Left(why)) =>
        ui.error(s"The sign-in failed: $why")
        false
      case None =>
        ui.choose("The browser has not finished signing in", List(KeepWaitingLabel, CancelLabel)) match
          case Some(KeepWaitingLabel) => awaitSignIn(ui, login)
          case _ => false

  private val KeepWaitingLabel = "Keep waiting"
  private val CancelLabel = "Cancel"

  private def chooseModel(ui: Ui, models: List[ModelSpec]): Option[ModelSpec] =
    val labels = models.map(m => m.displayName.fold(m.modelId)(name => s"$name  (${m.modelId})"))
    ui.choose("Choose a model (type to filter)", labels).flatMap: label =>
      models.zip(labels).collectFirst { case (m, l) if l == label => m }

  /** The provider as the catalog resolves it; `None` when its key is not bound. */
  private def endpoint(preset: ProviderPreset, keys: KeyBindings): Option[ModelSpec] =
    ModelCatalog.from(Config(providers = Map(preset.name -> preset.config)), keys).discoverable.headOption

  /** The messages of `e` and its causes: an SDK's "Request failed" alone does not say why. */
  private def reasons(e: Throwable): String =
    Iterator.iterate[Throwable | Null](e)(_.nn.getCause).takeWhile(_ != null).take(8)
      .flatMap(t => Option(t.nn.getMessage)).filter(_.nonEmpty).distinct.mkString(": ")
