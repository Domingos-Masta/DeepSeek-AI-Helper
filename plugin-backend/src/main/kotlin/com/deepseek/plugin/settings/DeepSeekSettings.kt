package com.deepseek.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(
    name = "DeepSeekSettings",
    storages = [Storage("deepseek-ai.xml")]
)
class DeepSeekSettings : PersistentStateComponent<DeepSeekSettings> {
    var apiKey: String = ""
    var model: String = "deepseek-v4-flash"
    var maxTokens: Int = 4096
    var temperature: Double = 0.7
    var completionEnabled: Boolean = true
    var completionModel: String = "deepseek-v4-flash"  // Empty = use the main model; can be specified separately
    var completionMaxTokens: Int = 256
    var completionDelayMs: Long = 500
    var completionMinPrefix: Int = 2           // Minimum input character count to trigger completion
    var maxContextLines: Int = 30              // Maximum context lines sent for completion

    // Annotation-aware completion
    var annotationAwareEnabled: Boolean = true // Whether annotation-analysis-driven completion/code mode is enabled

    // Comment-aware completion
    var commentAwareEnabled: Boolean = true    // Whether comment-analysis-driven code completion is enabled

    // Completion result cache
    var completionCacheEnabled: Boolean = true // Whether the LRU completion cache is enabled
    var completionCacheSize: Int = 20          // LRU cache capacity (entries)

    // Manual completion trigger (Alt+P) configuration
    var completionManualTemperature: Double = 0.2  // Generation temperature for manual trigger (0.0 for AUTO)
    var completionManualMaxTokens: Int = 512        // Max tokens for manual trigger (256 for AUTO)

    // Status bar indicator
    var completionStatusBarEnabled: Boolean = true // Whether to show completion status in the status bar

    // Ghost Text rendering mode (replaces the Lookup list)
    var completionGhostTextEnabled: Boolean = true // Whether Ghost Text rendering is enabled (on by default)

    // API Provider selection: "deepseek" | "agnes" | "nvidia"
    var provider: String = "deepseek"

    // Agnes 2.0 Flash configuration
    var agnesApiKey: String = ""
    var agnesModel: String = "agnes-2.5-flash"
    var agnesBaseUrl: String = "https://api.agnes-ai.cn/v1"

    // NVIDIA configuration
    var nvidiaApiKey: String = ""
    var nvidiaModel: String = "z-ai/glm-5.2"
    var nvidiaBaseUrl: String = "https://integrate.api.nvidia.com/v1"

    // OpenRouter configuration
    var openrouterApiKey: String = ""
    var openrouterModel: String = "inclusionai/ling-3.0-flash:free"
    var openrouterBaseUrl: String = "https://openrouter.ai/api/v1"

    // Zhipu AI GLM configuration
    var zhipuApiKey: String = ""
    var zhipuModel: String = "glm-4"
    var zhipuBaseUrl: String = "https://open.bigmodel.cn/api/paas/v4"

    // Claude (Anthropic) configuration — uses the Anthropic OpenAI-compatible endpoint
    var anthropicApiKey: String = ""
    var anthropicModel: String = "claude-sonnet-4-5"
    var anthropicBaseUrl: String = "https://api.anthropic.com/v1"

    // Codex (OpenAI) configuration — reuses the local ~/.codex login state
    var codexApiKey: String = ""
    var codexModel: String = ""
    var codexBaseUrl: String = "https://api.openai.com/v1"
    var codexReasoningEffort: String = "medium"

    // Claude/Codex CLI Agent permission mode: "acceptEdits" (default, file edits only) | "bypass" (skip all permissions) | "plan" (plan only)
    var cliAgentPermissionMode: String = "acceptEdits"

    // Image parsing configuration
    var imageParsingModel: String = "agnes"         // "agnes" | "stepfun" | "nvidia"
    var stepFunApiKey: String = ""                  // Only StepFun requires a separate key

    // Version tracking — the version the user last saw, used to show the changelog popup
    var lastSeenVersion: String = ""

    // Changelog language preference: "en"
    var changelogLanguage: String = "en"

    // Language setting (controls the UI + AI output): "en"
    var language: String = "en"

    // Content font size (chat body, Markdown, streaming output, etc.): 12–16
    var contentFontSize: Int = 13

    // Plugin standalone theme: follow=follow IDE / dark=fixed dark / light=fixed light (does not affect the IDE global theme)
    var pluginTheme: String = "follow"

    // Agentic Search configuration
    var agenticSearchEnabled: Boolean = true   // Whether Agentic Search (code search replacing RAG) is enabled
    var agenticSearchMaxRounds: Int = 3         // Max rounds for multi-round search (1 = single-round mode)

    // Per-phase configuration for the Agent Pipeline
    var agentPhase0Provider: String = "agnes"          // Intent confirmation provider
    var agentPhase0Model: String = "agnes-2.5-flash"  // Intent confirmation model

    // Phase 0 intent confirmation toggle — when off, skips intent confirmation and goes straight to planning
    var agentPhase0Enabled: Boolean = true

    // Phase 1 planning toggle — when off, skips planning and goes straight to coding
    var agentPhase1Enabled: Boolean = true

    // Phase 3 review toggle — when off, skips the review phase
    var agentPhase3Enabled: Boolean = true

    // Streaming output toggle — when off, uses a synchronous call and returns the complete result at once
    var streamingEnabled: Boolean = true

    // Reasoning display toggle — when off, the model's reasoning is hidden in Q&A / Q&A Full Scan modes
    var thinkingEnabled: Boolean = true

    // Output speed level: 0=fastest (unlimited) 1=fast 2=medium 3=slow
    var outputSpeedLevel: Int = 2

    var agentPhase1Provider: String = "deepseek"       // Planning provider
    var agentPhase1Model: String = "deepseek-v4-pro"   // Planning model
    var agentPhase2Provider: String = "deepseek"       // Coding provider
    var agentPhase2Model: String = "deepseek-v4-flash" // Coding model
    var agentPhase3Provider: String = "agnes"          // Review provider
    var agentPhase3Model: String = "agnes-2.5-flash"   // Review model

    // The Q&A classifier reuses the main API configuration's provider/model; no separate fields needed

    // ===== MCP Server configuration =====
    var mcpEnabled: Boolean = false      // Whether the MCP Server is enabled
    var mcpAutoStart: Boolean = false    // Auto-start when the IDE starts
    var mcpPort: Int = 8080              // Listening port

    override fun getState(): DeepSeekSettings = this
    override fun loadState(state: DeepSeekSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        val instance: DeepSeekSettings
            get() = ApplicationManager.getApplication().getService(DeepSeekSettings::class.java)
    }
}
