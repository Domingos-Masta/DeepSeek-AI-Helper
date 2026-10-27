package com.deepseek.plugin.settings

import com.google.gson.JsonParser
import java.io.File

// ==================== Claude (Anthropic) ====================

private fun claudeDir(): File = File(System.getProperty("user.home"), ".claude")

/**
 * Reads Claude credentials from the env in ~/.claude/settings.json (invoked by the "Use local settings.json" button).
 * Prefers env.ANTHROPIC_API_KEY, then env.ANTHROPIC_AUTH_TOKEN.
 */
fun readClaudeSettingsEnvKey(): String? {
    return try {
        val f = File(claudeDir(), "settings.json")
        if (!f.isFile) return null
        val env = JsonParser.parseReader(f.reader()).asJsonObject.getAsJsonObject("env") ?: return null
        env.get("ANTHROPIC_API_KEY")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
            ?: env.get("ANTHROPIC_AUTH_TOKEN")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}

/** Reads env.ANTHROPIC_BASE_URL from ~/.claude/settings.json (invoked by the button; may be null) */
fun readClaudeSettingsBaseUrl(): String? {
    return try {
        val f = File(claudeDir(), "settings.json")
        if (!f.isFile) return null
        val json = JsonParser.parseReader(f.reader()).asJsonObject
        json.getAsJsonObject("env")?.get("ANTHROPIC_BASE_URL")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}

/**
 * Reads the Claude model mapping from env in ~/.claude/settings.json (cc-switch / Claude Code conventions):
 *  ANTHROPIC_DEFAULT_SONNET_MODEL / ANTHROPIC_DEFAULT_HAIKU_MODEL / ANTHROPIC_DEFAULT_OPUS_MODEL.
 * Returns { "sonnet" -> "...", "haiku" -> "...", "opus" -> "..." }; missing keys are omitted.
 */
fun readClaudeModelMapping(): Map<String, String> {
    return try {
        val f = File(claudeDir(), "settings.json")
        if (!f.isFile) return emptyMap()
        val env = JsonParser.parseReader(f.reader()).asJsonObject.getAsJsonObject("env") ?: return emptyMap()
        fun get(key: String): String? =
            env.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        buildMap {
            get("ANTHROPIC_DEFAULT_SONNET_MODEL")?.let { put("sonnet", it) }
            get("ANTHROPIC_DEFAULT_HAIKU_MODEL")?.let { put("haiku", it) }
            get("ANTHROPIC_DEFAULT_OPUS_MODEL")?.let { put("opus", it) }
        }
    } catch (e: Exception) {
        emptyMap()
    }
}

// ==================== Codex (OpenAI) ====================

private fun codexDir(): File =
    System.getenv("CODEX_HOME")?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
        ?: File(System.getProperty("user.home"), ".codex")

/**
 * Reads the API key from ~/.codex/auth.json (invoked by the "Use local config" button).
 * Prefers OPENAI_API_KEY (official codex login), then CODEX_API_KEY (cc-switch / custom model_provider conventions).
 */
fun readCodexAuthApiKey(): String? {
    return try {
        val f = File(codexDir(), "auth.json")
        if (!f.isFile) return null
        val json = JsonParser.parseReader(f.reader()).asJsonObject
        json.get("OPENAI_API_KEY")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
            ?: json.get("CODEX_API_KEY")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}

/** Detects whether ~/.codex/auth.json contains only an OAuth login state (no usable API key; used for button hints) */
fun readCodexAuthHasOAuthOnly(): Boolean {
    return try {
        val f = File(codexDir(), "auth.json")
        if (!f.isFile) return false
        val json = JsonParser.parseReader(f.reader()).asJsonObject
        val apiKey = json.get("OPENAI_API_KEY")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        val codexKey = json.get("CODEX_API_KEY")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        apiKey.isNullOrEmpty() && codexKey.isNullOrEmpty() && json.get("tokens")?.isJsonObject == true
    } catch (e: Exception) {
        false
    }
}

/** Reads model = "..." from ~/.codex/config.toml (the model currently used by Codex CLI, invoked by buttons/defaults) */
fun readCodexConfigModel(): String? {
    return try {
        val f = File(codexDir(), "config.toml")
        if (!f.isFile) return null
        Regex("""(?m)^\s*model\s*=\s*["']([^"']+)["']""").find(f.readText())
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}

/**
 * Reads the base_url of the currently selected model_provider section in ~/.codex/config.toml (cc-switch proxy scenarios).
 * For example, base_url = "https://..." inside [model_providers.<relay name>].
 */
fun readCodexConfigBaseUrl(): String? {
    return try {
        val f = File(codexDir(), "config.toml")
        if (!f.isFile) return null
        val text = f.readText()
        val providerName = Regex("""(?m)^\s*model_provider\s*=\s*["']([^"']+)["']""").find(text)
            ?.groupValues?.get(1) ?: return null
        val sectionHeader = Regex("""^\s*\[model_providers\.${Regex.escape(providerName)}\]\s*$""")
        val baseUrl = Regex("""^\s*base_url\s*=\s*["']([^"']+)["']\s*(?:#.*)?$""")
        var inProviderSection = false
        for (line in text.lineSequence()) {
            if (line.trimStart().startsWith("[") && !sectionHeader.matches(line.trim())) {
                if (inProviderSection) break
                continue
            }
            if (sectionHeader.matches(line.trim())) {
                inProviderSection = true
                continue
            }
            if (inProviderSection) {
                baseUrl.find(line.trim())?.groupValues?.get(1)?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        null
    } catch (e: Exception) {
        null
    }
}