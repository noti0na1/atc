package atc.llm

import atc.config.{Config, ModelConfig}

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient

import java.time.Duration

/** Settings and client construction shared by the provider adapters. */
private[llm] object Providers:
  /** Generous on purpose: a reasoning model with tools can take many minutes. */
  val RequestTimeout: Duration = Duration.ofMinutes(15)

  /** A client for the two OpenAI-shaped providers: the configured key if there
    * is one, else the SDK's own environment resolution — except against a
    * custom `baseUrl` (Ollama, vLLM, LM Studio, ...), where a placeholder
    * stands in for the key such servers ignore. */
  def openAiClient(cfg: ModelConfig): OpenAIClient =
    val b = OpenAIOkHttpClient.builder().timeout(RequestTimeout)
    Config.resolveApiKey(cfg) match
      case Some(key) => b.apiKey(key)
      case None if cfg.baseUrl.isDefined => b.apiKey("none")
      case None => b.fromEnv()
    cfg.baseUrl.foreach(b.baseUrl)
    b.build()
