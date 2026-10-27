package com.deepseek.plugin.api

/**
 * Provider settings snapshot — a subset of the Provider-related fields in [com.deepseek.plugin.settings.DeepSeekSettings].
 * Pure data object with no IntelliJ SDK dependency, safely transferable between api/backend/frontend.
 */
data class SettingsSnapshot(
    val provider: String,
    val apiKey: String,
    val model: String,
    val agnesApiKey: String,
    val agnesModel: String,
    val agnesBaseUrl: String,
    val nvidiaApiKey: String,
    val nvidiaModel: String,
    val nvidiaBaseUrl: String,
    val openrouterApiKey: String,
    val openrouterModel: String,
    val openrouterBaseUrl: String,
    val zhipuApiKey: String,
    val zhipuModel: String,
    val zhipuBaseUrl: String,
    val anthropicApiKey: String,
    val anthropicModel: String,
    val anthropicBaseUrl: String,
    val codexApiKey: String,
    val codexModel: String,
    val codexBaseUrl: String,
)

/** Extracts the API Key from the snapshot based on the Provider type */
fun SettingsSnapshot.resolveApiKey(): String = when (provider) {
    "deepseek" -> apiKey
    "agnes" -> agnesApiKey
    "nvidia" -> nvidiaApiKey
    "openrouter" -> openrouterApiKey
    "zhipu" -> zhipuApiKey
    "anthropic" -> resolveAnthropicApiKey()
    "codex" -> resolveCodexApiKey()
    else -> apiKey
}

/** Extracts the baseUrl from the snapshot based on the Provider type */
fun SettingsSnapshot.resolveBaseUrl(): String = when (provider) {
    "deepseek" -> "https://api.deepseek.com/v1"
    "agnes" -> agnesBaseUrl.trimEnd('/')
    "nvidia" -> nvidiaBaseUrl.trimEnd('/')
    "openrouter" -> openrouterBaseUrl.trimEnd('/')
    "zhipu" -> zhipuBaseUrl.trimEnd('/')
    "anthropic" -> {
        val envBase = System.getenv("ANTHROPIC_BASE_URL")?.trim()?.trimEnd('/')
        if (!envBase.isNullOrEmpty()) envBase
        else anthropicBaseUrl.trimEnd('/').ifBlank { "https://api.anthropic.com/v1" }
    }
    "codex" -> {
        val envBase = System.getenv("OPENAI_BASE_URL")?.trim()?.trimEnd('/')
        if (!envBase.isNullOrEmpty()) envBase
        else codexBaseUrl.trimEnd('/').ifBlank { "https://api.openai.com/v1" }
    }
    else -> "https://api.deepseek.com/v1"
}

/** Extracts the model from the snapshot based on the Provider type */
fun SettingsSnapshot.resolveModel(): String = when (provider) {
    "deepseek" -> model
    "agnes" -> agnesModel.ifBlank { "agnes-2.5-flash" }
    "nvidia" -> nvidiaModel.ifBlank { "z-ai/glm-5.2" }
    "openrouter" -> openrouterModel.ifBlank { "inclusionai/ling-3.0-flash:free" }
    "zhipu" -> zhipuModel.ifBlank { "glm-4" }
    "anthropic" -> anthropicModel.ifBlank { "claude-sonnet-4-5" }
    "codex" -> codexModel.ifBlank { "gpt-5.2-codex" }
    else -> model
}

// ==================== Claude (Anthropic) ====================

/**
 * Claude API key runtime resolution chain (consistent with the Anthropic implementation in [LlmProvider]):
 *  1. anthropicApiKey explicitly entered in the settings panel
 *  2. Environment variable ANTHROPIC_API_KEY (API key billing)
 *  3. Environment variable ANTHROPIC_AUTH_TOKEN (Claude subscription OAuth login state, Bearer form)
 */
fun SettingsSnapshot.resolveAnthropicApiKey(): String {
    if (anthropicApiKey.isNotBlank()) return anthropicApiKey
    System.getenv("ANTHROPIC_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    System.getenv("ANTHROPIC_AUTH_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return ""
}

/** Currently active source of the Claude API key: settings / env / none */
fun SettingsSnapshot.anthropicKeySource(): String = when {
    anthropicApiKey.isNotBlank() -> "settings"
    !System.getenv("ANTHROPIC_API_KEY").isNullOrBlank() -> "env"
    !System.getenv("ANTHROPIC_AUTH_TOKEN").isNullOrBlank() -> "env"
    else -> "none"
}

// ==================== Codex (OpenAI) ====================

/**
 * Codex API key runtime resolution chain (consistent with the Codex implementation in [LlmProvider]):
 *  1. codexApiKey explicitly entered in the settings panel
 *  2. Environment variable OPENAI_API_KEY (official API key)
 *  3. Environment variable CODEX_API_KEY (the env_key convention for cc-switch / custom model_provider)
 */
fun SettingsSnapshot.resolveCodexApiKey(): String {
    if (codexApiKey.isNotBlank()) return codexApiKey
    System.getenv("OPENAI_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    System.getenv("CODEX_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return ""
}

/** Currently active source of the Codex API key: settings / env / none */
fun SettingsSnapshot.codexKeySource(): String = when {
    codexApiKey.isNotBlank() -> "settings"
    !System.getenv("OPENAI_API_KEY").isNullOrBlank() -> "env"
    !System.getenv("CODEX_API_KEY").isNullOrBlank() -> "env"
    else -> "none"
}