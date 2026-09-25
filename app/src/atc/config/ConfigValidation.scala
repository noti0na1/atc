package atc.config

import atc.perms.{Access, Mode, PathPattern}

import java.nio.file.Paths
import java.util.Locale

/** Checks that reject a configuration before anything runs, so a typo is reported
  * against the config rather than as a failure much later. */
object ConfigValidation:
  private val ReasoningSummaries = Set("auto", "concise", "detailed")
  private val NotificationChoices = Set("auto", "system", "terminal", "bell", "off")
  private val ProviderApis = Set(
    "anthropic",
    "claude",
    "openai-responses",
    "responses",
    "openai",
    "openai-chat",
    "chat",
    "chatgpt",
    "claude-code",
    "echo",
  )
  private val AnthropicWebSearchVersions = Set("20250305", "20260209")

  /** Reject settings that would otherwise fail much later, in output slicing,
    * timeout accounting or provider request construction. Returns `config`. */
  def validate(config: Config): Config =
    requirePositive("maxToolOutputChars", config.maxToolOutputChars)
    requireValid(config.maxToolCalls >= 0, s"maxToolCalls must be non-negative (was ${config.maxToolCalls})")
    requireValid(
      config.autoCompactThreshold.isFinite && config.autoCompactThreshold >= 0 && config.autoCompactThreshold <= 1,
      s"autoCompactThreshold must be between 0 and 1 (0 disables it; was ${config.autoCompactThreshold})"
    )
    requireValid(
      config.compactKeepRatio.isFinite && config.compactKeepRatio >= 0 && config.compactKeepRatio <= 1,
      s"compactKeepRatio must be between 0 and 1 (was ${config.compactKeepRatio})"
    )
    config.executionTimeoutMs.foreach(requirePositive("executionTimeoutMs", _))
    validateChoice("notifications", config.notifications, NotificationChoices)
    config.effort.foreach(validateChoice("effort", _, ModelConfig.ReasoningEfforts :+ ModelConfig.DefaultEffort))
    config.mode.foreach: m =>
      try Mode.parse(m)
      catch case e: IllegalArgumentException => invalid(e.getMessage.nn)
    config.providers.foreach(validateProvider)
    // `model` and `classifiedModel` are not checked here: a model a provider
    // lists may be named before the list is fetched, and the start warns
    // about a name that resolves to nothing instead of refusing to run.
    val duplicateRefs =
      ModelCatalog.from(config).configured.groupBy(_.ref.toLowerCase(Locale.ROOT)).values.filter(_.size > 1).toList
    requireValid(
      duplicateRefs.isEmpty,
      s"model references must be unique ignoring case: ${duplicateRefs.flatten.map(_.ref).sorted.mkString(", ")}"
    )
    config

  /** Reject an invalid `mode` and identify the layer that defines it.
    * [[Configuration.combine]] validates every layer first, so combining modes
    * can assume valid input. */
  private[config] def validateLayerMode(layer: ConfigLayer): Unit =
    layer.config.mode.foreach: m =>
      try Mode.parse(m)
      catch
        case e: IllegalArgumentException =>
          val where = layer.path.map(_.toString).getOrElse(s"(${layer.origin.label} layer)")
          throw IllegalArgumentException(s"Invalid config $where: ${e.getMessage}")

  /** A `files` entry of any layer (the project layer's included, which
    * `settings.files` leaves out): the path must be a usable pattern and the
    * access level one the policy knows, so a typo is reported as a config
    * error here rather than when the policy is built. */
  private[config] def validateRule(r: LayeredRule): Unit =
    val path = r.rule.path
    def invalid(what: String) = IllegalArgumentException(s"Invalid config: files entry '$path': $what")
    if path.trim.isEmpty then throw invalid("the path must not be blank")
    try PathPattern(path, r.base.getOrElse(Paths.get("").toAbsolutePath))
    catch case e: Exception => throw invalid(s"not a valid pattern (${e.getMessage})")
    r.rule.access.foreach: a =>
      try Access.parse(a)
      catch case e: IllegalArgumentException => throw invalid(e.getMessage.nn)

  private def validateProvider(name: String, provider: ProviderConfig): Unit =
    requireValid(name.trim.nonEmpty, "provider names must not be blank")
    requireValid(name == name.trim, s"provider name '$name' must not start or end with whitespace")
    // `api` may be absent from a layer that only extends an earlier provider, but
    // the fully merged provider must define it.
    requireValid(
      provider.api.exists(_.trim.nonEmpty),
      s"provider '$name' has no api (expected anthropic | openai | openai-responses | chatgpt | claude-code | echo)"
    )
    provider.api.foreach(api => validateChoice(s"providers.$name.api", api, ProviderApis))
    provider.models.foreach((alias, model) => validateModel(name, alias, model))

  private def validateModel(provider: String, alias: String, model: ModelConfig): Unit =
    val where = s"providers.$provider.models.$alias"
    requireValid(alias.trim.nonEmpty, s"model aliases of provider '$provider' must not be blank")
    requireValid(alias == alias.trim, s"model alias '$alias' must not start or end with whitespace")
    requireValid(!alias.contains('/'), s"model alias '$alias' must not contain '/'")
    requireValid(!model.name.exists(_.trim.isEmpty), s"$where.name must not be blank")
    requireValid(!model.displayName.exists(_.trim.isEmpty), s"$where.displayName must not be blank")
    requireValid(
      !model.displayName.exists(name => name != name.trim),
      s"$where.displayName must not start or end with whitespace"
    )
    requireValid(
      !model.displayName.exists(name => name.contains('\n') || name.contains('\r')),
      s"$where.displayName must be a single line"
    )
    model.maxTokens.foreach(requirePositive(s"$where.maxTokens", _))
    model.temperature.foreach(value => requireValid(value.isFinite, s"$where.temperature must be finite"))
    model.reasoning.foreach(validateChoice(s"$where.reasoning", _, ModelConfig.ReasoningEfforts))
    model.efforts.foreach: efforts =>
      efforts.foreach(validateChoice(s"$where.efforts", _, ModelConfig.ReasoningEfforts))
      model.reasoning.foreach: r =>
        requireValid(
          efforts.exists(_.equalsIgnoreCase(r)),
          s"$where.reasoning '$r' is not one of its efforts (${efforts.mkString("|")})"
        )
    model.reasoningSummary.foreach(validateChoice(s"$where.reasoningSummary", _, ReasoningSummaries))
    model.webSearchVersion.foreach(validateChoice(s"$where.webSearchVersion", _, AnthropicWebSearchVersions))

  private def validateChoice(where: String, value: String, allowed: Iterable[String]): Unit =
    requireValid(value == value.trim, s"$where must not start or end with whitespace (was '$value')")
    requireValid(
      allowed.exists(_ == value.trim.toLowerCase(Locale.ROOT)),
      s"$where must be one of ${allowed.toList.sorted.mkString("|")} (was '$value')"
    )

  private def requirePositive(name: String, value: Long): Unit =
    requireValid(value > 0, s"$name must be greater than zero (was $value)")

  private def requireValid(condition: Boolean, message: => String): Unit =
    if !condition then invalid(message)

  private def invalid(message: String): Nothing = throw IllegalArgumentException(s"Invalid config: $message")
