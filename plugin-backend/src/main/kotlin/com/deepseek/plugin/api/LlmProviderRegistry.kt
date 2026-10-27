package com.deepseek.plugin.api

import com.deepseek.plugin.settings.readCodexConfigBaseUrl
import java.util.ServiceLoader
import com.deepseek.plugin.settings.readCodexConfigModel

/**
 * Provider registry — manages all LLM provider instances.
 *
 * To add a new provider: write `class XxxProvider : LlmProvider`, then one line:
 *   `LlmProviderRegistry.register(XxxProvider())`
 */
object LlmProviderRegistry {
    private val lock = Any()
    private val providers = mutableMapOf<String, LlmProvider>()
    private var discovered = false

    private fun discover() {
        if (discovered) return
        synchronized(lock) {
            if (discovered) return
            runCatching {
                ServiceLoader.load(LlmProvider::class.java, LlmProvider::class.java.classLoader)
                    .forEach { register(it) }
            }
            // Safety net for environments where ServiceLoader cannot see the plugin classloader.
            if (providers.isEmpty()) {
                registerBuiltins()
            }
            discovered = true
        }
    }

    private fun registerBuiltins() {
        register(DeepSeekProvider())
        register(AgnesProvider())
        register(NvidiaProvider())
        register(OpenRouterProvider())
        register(ZhipuProvider())
        register(AnthropicProvider())
        register(CodexProvider())
    }

    fun register(provider: LlmProvider) {
        synchronized(lock) {
            providers[provider.id] = provider
        }
    }

    fun get(id: String): LlmProvider {
        discover()
        return synchronized(lock) {
            providers[id] ?: providers["deepseek"]
                ?: throw IllegalStateException("No default provider registered")
        }
    }

    fun allProviders(): Collection<LlmProvider> {
        discover()
        return synchronized(lock) { providers.values.toList() }
    }

    fun hasProvider(id: String): Boolean {
        discover()
        return synchronized(lock) { id in providers }
    }
}

// ============== Implementation ==============

/** DeepSeek official API */
class DeepSeekProvider : LlmProvider {
    override val id = "deepseek"
    override val displayName = "DeepSeek"
    override val supportsFim = true

    override fun baseUrl(settings: SettingsSnapshot): String = "https://api.deepseek.com/v1"
    override fun apiKey(settings: SettingsSnapshot): String = settings.apiKey
    override fun model(settings: SettingsSnapshot): String = settings.model
}

/** Agnes 2.0 Flash */
class AgnesProvider : LlmProvider {
    override val id = "agnes"
    override val displayName = "Agnes 2.0 Flash"

    override fun baseUrl(settings: SettingsSnapshot): String = settings.agnesBaseUrl.trimEnd('/')
    override fun apiKey(settings: SettingsSnapshot): String = settings.agnesApiKey
    override fun model(settings: SettingsSnapshot): String = settings.agnesModel.ifBlank { "agnes-2.5-flash" }
}

/** NVIDIA NIM API */
class NvidiaProvider : LlmProvider {
    override val id = "nvidia"
    override val displayName = "NVIDIA"

    override fun baseUrl(settings: SettingsSnapshot): String = settings.nvidiaBaseUrl.trimEnd('/')
    override fun apiKey(settings: SettingsSnapshot): String = settings.nvidiaApiKey
    override fun model(settings: SettingsSnapshot): String = settings.nvidiaModel.ifBlank { "z-ai/glm-5.2" }
}

/** OpenRouter API — aggregates multiple models */
class OpenRouterProvider : LlmProvider {
    override val id = "openrouter"
    override val displayName = "OpenRouter"

    override fun baseUrl(settings: SettingsSnapshot): String = settings.openrouterBaseUrl.trimEnd('/')
    override fun apiKey(settings: SettingsSnapshot): String = settings.openrouterApiKey
    override fun model(settings: SettingsSnapshot): String = settings.openrouterModel.ifBlank { "inclusionai/ling-3.0-flash:free" }
}

/** Zhipu AI GLM */
class ZhipuProvider : LlmProvider {
    override val id = "zhipu"
    override val displayName = "BigModel"

    override fun baseUrl(settings: SettingsSnapshot): String = settings.zhipuBaseUrl.trimEnd('/')
    override fun apiKey(settings: SettingsSnapshot): String = settings.zhipuApiKey
    override fun model(settings: SettingsSnapshot): String = settings.zhipuModel.ifBlank { "glm-4" }
}

/**
 * Claude (Anthropic) — uses the Anthropic official OpenAI-compatible endpoint (/v1/chat/completions, Bearer authentication).
 *
 * For the API key resolution chain see [SettingsSnapshot.resolveAnthropicApiKey] (settings panel → ANTHROPIC_API_KEY environment variable
 * → ~/.claude/settings.json → ~/.claude/.credentials.json OAuth token; the latter is for the official endpoint only).
 * baseUrl prefers the ANTHROPIC_BASE_URL environment variable (enterprise proxy/relay scenarios).
 */
class AnthropicProvider : LlmProvider {
    override val id = "anthropic"
    override val displayName = "Claude (Anthropic)"
    override val supportsFim = false
    override val protocol = "anthropic"
    override val capabilities = LlmCapabilities(
        nativeImages = true,
        nativeReasoning = true,
        readOnlyTools = true,
        promptCaching = true,
        maxContextTokens = 64_000
    )

    override fun baseUrl(settings: SettingsSnapshot): String {
        val envBase = System.getenv("ANTHROPIC_BASE_URL")?.trim()?.trimEnd('/')
        if (!envBase.isNullOrEmpty()) return envBase
        return settings.anthropicBaseUrl.trimEnd('/').ifBlank { "https://api.anthropic.com/v1" }
    }

    override fun apiKey(settings: SettingsSnapshot): String = settings.resolveAnthropicApiKey()

    override fun model(settings: SettingsSnapshot): String =
        settings.anthropicModel.ifBlank { "claude-sonnet-4-5" }
}

/** Currently effective source of the Claude API key (delegates to [SettingsSnapshot.anthropicKeySource]) */
fun detectAnthropicKeySource(settings: SettingsSnapshot): String = settings.anthropicKeySource()

/**
 * Codex (OpenAI) — reuses the local Codex CLI login state, using the OpenAI-compatible chat/completions endpoint.
 *
 * For the API key resolution chain see [SettingsSnapshot.resolveCodexApiKey] (settings panel → OPENAI_API_KEY environment variable
 * → OPENAI_API_KEY in ~/.codex/auth.json). baseUrl prefers the OPENAI_BASE_URL environment variable;
 * when model is not set, the model config is read automatically from ~/.codex/config.toml.
 */
class CodexProvider : LlmProvider {
    override val id = "codex"
    override val displayName = "Codex (OpenAI)"
    override val supportsFim = false
    override val protocol = "codex-responses"
    override val capabilities = LlmCapabilities(
        nativeImages = true,
        nativeReasoning = true,
        readOnlyTools = true,
        conversationContinuation = true,
        promptCaching = true,
        maxContextTokens = 64_000
    )

    override fun baseUrl(settings: SettingsSnapshot): String {
        val envBase = System.getenv("OPENAI_BASE_URL")?.trim()?.trimEnd('/')
        if (!envBase.isNullOrEmpty()) return envBase
        val configured = settings.codexBaseUrl.trimEnd('/')
        // Keep Q&A on the same cc-switch/local proxy selected by Codex CLI when
        // the plugin setting is still at its default value.
        if (configured.isBlank() || configured == "https://api.openai.com/v1") {
            readCodexConfigBaseUrl()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return configured.ifBlank { "https://api.openai.com/v1" }
    }

    override fun apiKey(settings: SettingsSnapshot): String = settings.resolveCodexApiKey()

    override fun model(settings: SettingsSnapshot): String =
        settings.codexModel.ifBlank { readCodexConfigModel() ?: "gpt-5.2-codex" }
}

/** Currently effective source of the Codex API key (delegates to [SettingsSnapshot.codexKeySource]) */
fun detectCodexKeySource(settings: SettingsSnapshot): String = settings.codexKeySource()

