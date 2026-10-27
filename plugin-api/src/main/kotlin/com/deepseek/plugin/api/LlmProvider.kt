package com.deepseek.plugin.api

/**
 * LLM provider strategy interface.
 *
 * New providers only need to implement this interface and register with [LlmProviderRegistry].
 */
interface LlmProvider {
    /** Unique identifier, corresponding to the value of settings.provider */
    val id: String
    /** Display name in the settings UI */
    val displayName: String

    /** Provider API base URL */
    fun baseUrl(settings: SettingsSnapshot): String

    /** Provider API Key */
    fun apiKey(settings: SettingsSnapshot): String

    /** Name of the model currently in use */
    fun model(settings: SettingsSnapshot): String

    /** Whether FIM code completion is supported */
    val supportsFim: Boolean get() = false

    /** Capabilities used by Q&A routing instead of protocol-name checks. */
    val capabilities: LlmCapabilities get() = LlmCapabilities()

    /**
     * Chat request protocol:
     *  - "openai" (default): POST {baseUrl}/chat/completions, Authorization: Bearer, OpenAI-format SSE
     *  - "anthropic": POST {baseUrl}/v1/messages, x-api-key + anthropic-version, Anthropic native SSE
     *    (used to connect to Anthropic-compatible endpoints of third-party relays such as cc-switch, e.g. https://api.deepseek.com/anthropic)
     *  - "codex-responses": POST {baseUrl}/responses, Authorization: Bearer, OpenAI Responses API native SSE
     *    (used for models that only support the Responses API, such as Codex CLI gpt-5.x-codex, and for cc-switch direct-connect mode)
     */
    val protocol: String get() = "openai"

    /**
     * Fixed temperature value. When non-null, it overrides the user's configuration in settings.
     * Used for certain models that only accept a specific temperature (e.g. Kimi K3 only accepts 1.0).
     */
    val temperature: Double? get() = null
}

data class LlmCapabilities(
    val nativeImages: Boolean = false,
    val nativeReasoning: Boolean = false,
    val readOnlyTools: Boolean = false,
    val conversationContinuation: Boolean = false,
    val promptCaching: Boolean = false,
    /** Conservative input budget used until a model-specific catalog overrides it. */
    val maxContextTokens: Int = 32_000
)
