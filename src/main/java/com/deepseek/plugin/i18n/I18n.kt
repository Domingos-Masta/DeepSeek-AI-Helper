package com.deepseek.plugin.i18n

/**
 * Internationalization helper — centralizes all user-visible strings.
 *
 * Usage: call `I18n.tr("key")` in UI code to get the current-language string.
 */
object I18n {

    /** clientProperty key: marks the i18n key associated with a component, used to refresh text/tooltip on language change */
    const val KEY_I18N = "deepseek.i18nKey"

    /**
     * Sets the component tooltip and records its i18n key so it can be refreshed after a language change.
     */
    fun tooltip(component: javax.swing.JComponent, key: String) {
        component.toolTipText = tr(key)
        component.putClientProperty(KEY_I18N, key)
    }

    /**
     * Walks the component tree and refreshes all tooltips marked via [tooltip].
     * Called by each panel when the language changes.
     */
    fun refreshTooltips(container: java.awt.Container) {
        fun walk(c: java.awt.Component) {
            if (c is javax.swing.JComponent) {
                val key = c.getClientProperty(KEY_I18N) as? String
                if (key != null) c.toolTipText = tr(key)
            }
            if (c is java.awt.Container) {
                for (i in 0 until c.componentCount) walk(c.getComponent(i))
            }
        }
        walk(container)
    }

    /**
     * Current language ("en"). When changed, publishes [I18nTopics.LANGUAGE_CHANGED]
     * via the MessageBus so UI panels can refresh immediately without reopening settings.
     */
    var currentLang: String = "en"
        set(value) {
            val changed = field != value
            field = value
            if (changed) {
                try {
                    com.intellij.openapi.application.ApplicationManager.getApplication()
                        .messageBus
                        .syncPublisher(I18nTopics.LANGUAGE_CHANGED)
                        .languageChanged()
                } catch (_: Exception) {
                    // Ignore outside the IDE (e.g., unit tests / utility calls)
                }
            }
        }

    /** Returns the translation for [key] according to [currentLang]. Falls back to the key itself when missing. */
    fun tr(key: String, vararg args: Any?): String {
        val map = en
        var result = map[key] ?: key
        if (args.isNotEmpty()) {
            result = result.format(*args)
        }
        return result
    }

    // ════════════════════════════════════════════════════════════
    //  English string map
    // ════════════════════════════════════════════════════════════
    private val en = mapOf(
        "chat.send" to "Send",
        "chat.stop" to "Stop",
        "chat.send.enter" to "Send",
        "chat.stop.enter" to "Stop",
        "chat.load.more" to "\u25B2 Load earlier messages",
        "chat.analysis.complete" to "\u2705 Context analysis complete",
        "chat.analysis.failed" to "Context analysis failed",
        "chat.analysis.scanning" to "\uD83D\uDD0D Scanning project context",
        "chat.yes.this.is.what.i.mean" to "\u2705 Yes, that's what I mean",
        "chat.no.i.need.to.supplement" to "\u274C No, I need to add more",
        "chat.tooltip.send" to "Send message",
        "chat.tooltip.stop" to "Stop",
        "chat.tooltip.mode.select" to "Mode selection",
        "chat.mode.qa" to "Q&A",
        "chat.mode.qa.scan" to "Q&A Full Scan",
        "chat.mode.agent" to "Agent",
        "chat.streaming.toggle" to "Streaming Output",
        "chat.thinking.toggle" to "Show Reasoning",
        "chat.thinking" to "Thinking",
        "chat.step.analyzing" to "Analyzing your request...",
        "chat.step.searching" to "Searching the codebase...",
        "chat.step.reasoning" to "Reasoning through the solution...",
        "chat.step.generating" to "Generating code...",
        "chat.step.refining" to "Refining the response...",
        "chat.output.speed" to "Output Speed",
        "chat.output.speed.fastest" to "Fastest",
        "chat.output.speed.fast" to "Fast",
        "chat.output.speed.medium" to "Medium",
        "chat.output.speed.slow" to "Slow",
        "chat.settings" to "Settings",
        "chat.agent.pipeline.settings" to "Agent Pipeline Phase Settings...",
        "chat.agent.phase0.toggle" to "Phase 0 Intent Confirmation",
        "chat.agent.phase1.toggle" to "Phase 1 Planning",
        "chat.agent.phase3.toggle" to "Phase 3 Review",
        "chat.confirm.agent.action" to "Confirm Agent Action",
        "chat.confirm.execute" to "Confirm & Execute",
        "chat.cancel" to "Cancel",
        "chat.skip.prefix" to "⏭ Skipped",
        "chat.clear.all.sessions" to "Clear All Sessions",
        "chat.placeholder" to "Type a message...  ·  Enter to send  ·  Shift+Enter for newline",
        "chat.planning" to "\uD83E\uDD16 Planner Agent analyzing requirements...",
        "chat.parsing" to "Parsing...",
        "chat.understanding" to "\uD83E\uDD14 Understanding your request...",
        "chat.searching" to "\uD83D\uDD0D Agent searching codebase...",
        "chat.supplement" to "\uD83D\uDCDD Please supplement your request",
        "chat.info.supplement" to "\uD83D\uDCDD Please supplement or re-describe your requirements",
        "chat.info.skip.review" to "\u23ED Skipping review phase",
        "chat.api.error" to "\u26A0\uFE0F API Error",
        "chat.api.key.required" to "[ERROR] Please configure your API Key in Settings...",
        "chat.api.key.required.anthropic" to "Claude API Q&A requires an Anthropic API key. Use Claude Code mode for your local Claude login.",
        "chat.api.key.required.codex" to "Codex API Q&A requires OPENAI_API_KEY. Use local Codex mode for a ChatGPT OAuth login.",
        "chat.context.truncated" to "This turn's context was trimmed to fit the model token budget.",
        "chat.api.key.missing.prefix" to "Please configure ",
        "chat.api.key.missing.suffix" to " API Key in Settings",
        "chat.warning.prefix" to "⚠️ ",
        "chat.agent.complete" to "\u2705 Agent execution completed!",
        "chat.change.recorded" to "\uD83D\uDCCB Recorded in Change Management panel",
        "chat.session.default" to "Session",
        "chat.session" to "Session",
        "chat.session.default.name" to "Session",
        "chat.file.context" to "Below is the content of file...",
        "chat.image.context" to "Below is the parsed result of image...",
        "chat.agent.error" to "\u274C Agent execution failed",
        "chat.agent.progress" to "DeepSeek-AI-Helper",
        "chat.agent.interprets" to "🤖 Analysis result:",
        "chat.agent.single.file" to "📝 Simple task, skipping planning & review, entering coding",
        "chat.agent.understanding" to "🤔 Understanding your request...",
        "chat.agent.cancelled" to "✋ Cancelled",
        "chat.agent.planning.prefix" to "📋 Planning with ",
        "chat.agent.planning.suffix" to "...",
        "chat.agent.coding.prefix" to "🛠 Implementing with ",
        "chat.agent.coding.suffix" to "...",
        "chat.agent.reviewing.prefix" to "🔎 Reviewing with ",
        "chat.agent.reviewing.suffix" to "...",
        "chat.upload" to "Upload File",
        "attachment.filter" to "Supported formats (code, text, PDF, Word, Excel, PowerPoint, images)",
        "attachment.not.file" to "Attachment does not exist or is not a file: %s",
        "attachment.too.large" to "Attachment exceeds the 25 MB limit: %s",
        "attachment.total.too.large" to "Total attachment size cannot exceed 50 MB",
        "attachment.too.many" to "You can attach at most 10 files at a time",
        "attachment.legacy.office" to "Convert this file to docx/xlsx/pptx first: %s",
        "attachment.unsupported" to "Unsupported attachment format: %s",
        "attachment.label" to "Attachments: %s",
        "attachment.prepare.failed" to "Failed to prepare attachments",
        "attachment.status.pending" to "Pending",
        "attachment.status.processing" to "Parsing",
        "attachment.status.ready" to "Ready",
        "attachment.status.partial" to "Partial",
        "attachment.status.failed" to "Failed",
        "attachment.pdf.scanned" to "No text was found in the PDF; rendered the first %s pages as images.",
        "chat.translate" to "Translate",
        "chat.rollback.success" to "✅ Successfully rolled back",
        "chat.rollback.partial" to "\u26A0\uFE0F Partial rollback completed",
        "chat.rollback.failed" to "\u274C Rollback failed",
        "chat.diff.error" to "Diff Error",
        "chat.error.network" to "Network error",
        "chat.error.rate.limit" to "Rate limit exceeded. Please try again later.",
        "chat.error.save.failed" to "Failed to save chat history. Please check disk space.",
        "chat.error.operation.failed" to "Agent file operation failed.",
        "chat.error.operation.title" to "Operation Failed",

        // ── ChatPanel ──
        "chat.no.messages" to "No messages",

        "toolbar.history" to "History",
        "toolbar.change.management" to "Change Management",
        "toolbar.settings" to "Settings",

        "session.new" to "New Session",
        "session.clear.current" to "Clear Current Session",

        "input.drag.resize" to "Drag to resize input area, double-click to reset",

        "welcome.title" to "DeepSeek-AI-Helper",
        "welcome.subtitle" to "AI-powered coding assistant for JETBRAINS",
        "welcome.quickstart" to "Quick Start",
        "welcome.tip.send" to "\uD83D\uDCAC Enter to send message",
        "welcome.tip.newline" to "\u21E7+Enter for newline",
        "welcome.tip.context" to "\uD83D\uDCC4 Selected code auto-fills as context",
        "welcome.tip.upload" to "\uD83D\uDCC1 Upload files as attachments",
        "welcome.tip.image" to "\uD83D\uDDBC Ctrl+V to paste images",
        "welcome.tip.at" to "@ to reference project files",
        "welcome.version" to "v",

        "history.title" to "Session History",
        "history.open" to "Open Session",
        "history.clear" to "clear",
        "history.message.count" to "messages",
        "history.messages" to "messages",
        "history.tokens" to "tokens",

        "bubble.me" to "You",
        "bubble.dp.helper" to "DeepSeek",
        "bubble.thinking" to "Thinking...",
        "bubble.reasoning" to "Reasoning",
        "bubble.reasoning.word.count" to "{0} chars",
        "bubble.reasoning.collapse" to "Collapse reasoning",
        "bubble.reasoning.expand" to "Expand reasoning",
        "bubble.delete" to "Delete this message",

        "settings.theme" to "Theme",
        "settings.skills" to "Skills",
        "settings.api.config" to "API Config",
        "settings.claude.codex" to "Claude / Codex Config",
        "settings.agent.pipeline" to "Agent Pipeline",
        "settings.code.completion" to "Code Completion",
        "settings.code.search" to "Agentic Search",
        "settings.image.parsing" to "Image Parsing",
        "settings.close" to "Close",
        "settings.theme.title" to "Theme Settings",
        "settings.theme.group" to "Theme",
        "settings.theme.follow" to "Follow IDE",
        "settings.theme.dark" to "Dark",
        "settings.theme.light" to "White",
        "settings.font.size" to "Font Size",
        "settings.font.group" to "Content Font",
        "settings.font.comment" to "Chat bubbles refresh immediately on change",
        "settings.language.group" to "Interface Language",
        "settings.language.comment" to "Takes effect immediately, no need to reopen settings",

        "api.group.title" to "API Configuration",
        "api.provider" to "API Provider:",
        "api.provider.comment" to "deepseek = DeepSeek API, agnes = Agnes 2.5 Flash, nvidia = NVIDIA NIM, openrouter = OpenRouter, zhipu = BigModel, anthropic = Claude (Anthropic), codex = Codex (OpenAI)",
        "api.deepseek.group" to "DeepSeek",
        "api.deepseek.key" to "API Key:",
        "api.deepseek.model" to "Model:",
        "api.deepseek.model.comment" to "deepseek-v4-flash or deepseek-v4-pro",
        "api.deepseek.max.tokens" to "Max Tokens:",
        "api.deepseek.temperature" to "Temperature:",
        "api.deepseek.temperature.comment" to "0.0 - 2.0",
        "api.agnes.group" to "Agnes 2.5 Flash",
        "api.agnes.key" to "API Key:",
        "api.agnes.model" to "Model:",
        "api.agnes.model.comment" to "agnes-2.5-flash",
        "api.agnes.base.url" to "Base URL:",
        "api.nvidia.group" to "NVIDIA Model",
        "api.nvidia.key" to "API Key:",
        "api.nvidia.model" to "Model:",
        "api.nvidia.base.url" to "Base URL:",
        "api.openrouter.group" to "OpenRouter",
        "api.openrouter.key" to "API Key:",
        "api.openrouter.model" to "Model:",
        "api.openrouter.model.comment" to "<a href='https://openrouter.ai/models'>openrouter.ai/models</a> — e.g. inclusionai/ling-3.0-flash:free",
        "api.openrouter.base.url" to "Base URL:",
        "api.zhipu.group" to "BigModel",
        "api.zhipu.key" to "API Key:",
        "api.zhipu.model" to "Model:",
        "api.zhipu.model.comment" to "glm-4 / glm-4-flash / glm-4v",
        "api.zhipu.base.url" to "Base URL:",

        // ── Anthropic / Claude ──
        "api.anthropic.group" to "Claude (Anthropic)",
        "api.anthropic.key" to "API Key:",
        "api.anthropic.key.comment" to "Leave empty to auto-read ANTHROPIC_API_KEY env var or ~/.claude config",
        "api.anthropic.key.source" to "Current key source:",
        "api.anthropic.key.source.settings" to "Settings panel",
        "api.anthropic.key.source.env" to "Env var ANTHROPIC_API_KEY",
        "api.anthropic.key.source.claude-settings" to "~/.claude/settings.json (env.ANTHROPIC_API_KEY)",
        "api.anthropic.key.source.claude-credentials" to "~/.claude/.credentials.json (OAuth)",
        "api.anthropic.key.source.none" to "Not detected — fill in below",
        "api.anthropic.model" to "Model:",
        "api.anthropic.model.comment" to "<a href='https://docs.anthropic.com/en/docs/about-claude/models'>docs.anthropic.com/models</a>",
        "api.anthropic.base.url" to "Base URL:",

        // FIM completion capability notice
        "completion.fim.unsupported.title" to "Code completion unavailable",
        "completion.fim.unsupported.message" to "The current provider does not support FIM code completion (e.g. Anthropic). Switch back to DeepSeek or another FIM-capable provider in Settings.",

        // ── Codex (OpenAI) ──
        "api.codex.group" to "Codex (OpenAI)",
        "api.codex.key" to "API Key:",
        "api.codex.key.comment" to "Leave empty to auto-read OPENAI_API_KEY env var or ~/.codex/auth.json",
        "api.codex.key.source" to "Current key source:",
        "api.codex.key.source.settings" to "Settings panel",
        "api.codex.key.source.env" to "Env var OPENAI_API_KEY",
        "api.codex.key.source.codex-auth" to "~/.codex/auth.json (OPENAI_API_KEY)",
        "api.codex.key.source.codex-oauth-only" to "Codex OAuth login (ChatGPT account) detected, but OAuth tokens cannot be used with the OpenAI API — log in with an API key or set OPENAI_API_KEY",
        "api.codex.key.source.none" to "Not detected — fill in below",
        "api.codex.model" to "Model:",
        "api.codex.model.comment" to "Leave empty to auto-read the model from ~/.codex/config.toml",
        "api.codex.base.url" to "Base URL:",

        // ── Claude / Codex dedicated config page ──
        "cc.claude.group" to "Claude (Anthropic)",
        "cc.claude.use.local" to "Local config:",
        "cc.claude.use.settings.json" to "Use local settings.json",
        "cc.claude.use.local.comment" to "Explicitly authorize reading env.ANTHROPIC_API_KEY / env.ANTHROPIC_AUTH_TOKEN (and ANTHROPIC_BASE_URL) from ~/.claude/settings.json",
        "cc.claude.key" to "API Key:",
        "cc.claude.key.comment" to "Leave empty to use the ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN env var",
        "cc.claude.model" to "Model:",
        "cc.claude.base.url" to "Base URL:",
        "cc.claude.use.title" to "Claude Local Config",
        "cc.claude.use.success" to "Config loaded from ~/.claude/settings.json and applied",
        "cc.claude.use.not.found" to "No env.ANTHROPIC_API_KEY or env.ANTHROPIC_AUTH_TOKEN found in ~/.claude/settings.json. Configure Claude Code first, or fill in manually",

        // ── Codex dedicated config page ──
        "cc.codex.group" to "Codex (OpenAI)",
        "cc.codex.use.local" to "Local config:",
        "cc.codex.use.local.config" to "Use local config",
        "cc.codex.use.local.comment" to "Explicitly authorize reading ~/.codex/config.toml (model & proxy Base URL) and ~/.codex/auth.json (OPENAI_API_KEY / CODEX_API_KEY)",
        "cc.codex.key" to "API Key:",
        "cc.codex.key.comment" to "Leave empty to use the OPENAI_API_KEY / CODEX_API_KEY env var",
        "cc.codex.model" to "Model:",
        "cc.codex.base.url" to "Base URL:",
        "cc.codex.use.title" to "Codex Local Config",
        "cc.codex.use.success" to "Config loaded from ~/.codex and applied",
        "cc.codex.use.not.found" to "No OPENAI_API_KEY or CODEX_API_KEY found in ~/.codex/auth.json. Log in to the codex CLI with an API key first, or fill in manually",
        "cc.codex.use.oauth.only" to "Codex is logged in via OAuth (ChatGPT account), but OAuth tokens cannot be used with the OpenAI API — log in with an API key or set OPENAI_API_KEY",
        "cc.codex.reasoning.effort" to "Q&A reasoning effort:",
        "cc.codex.reasoning.effort.comment" to "Codex Responses API only; choose high for complex questions",

        // ── CLI Agent (mode 4) ──
        "cli.mode.group" to "CLI Agent (Claude/Codex)",
        "cli.mode.permission.label" to "CLI Agent permission mode:",
        "cli.mode.permission.acceptEdits" to "Accept file edits (recommended)",
        "cli.mode.permission.bypass" to "Skip all permissions (dangerous)",
        "cli.mode.permission.plan" to "Plan only (no file changes)",
        "cli.mode.permission.comment" to "Permission policy for Claude/Codex CLI when modifying code; \"Skip all permissions\" lets the CLI run arbitrary commands",
        "cli.mode.requires.provider" to "Switch the API Provider to Claude (Anthropic) or Codex in Settings to use this mode",
        "cli.mode.not.found" to "%s CLI not found. Install it first: npm install -g @anthropic-ai/claude-code (Claude) or npm install -g @openai/codex (Codex)",
        "cli.mode.change.title" to "%s CLI changes",
        "cli.mode.change.recorded" to "Recorded %s file change(s) — view/roll back in the Change Management panel",

        "pipeline.group.title" to "Agent Pipeline Configuration",
        "pipeline.phase0.provider" to "Phase 0 Intent Confirmation Provider:",
        "pipeline.phase0.model" to "Phase 0 Intent Confirmation Model:",
        "pipeline.phase0.model.comment" to "Default: agnes-2.5-flash",
        "pipeline.phase0.enabled" to "Enable Phase 0 Intent Confirmation",
        "pipeline.phase1.provider" to "Phase 1 Planning Provider:",
        "pipeline.phase1.model" to "Phase 1 Planning Model:",
        "pipeline.phase1.model.comment" to "Default: deepseek-v4-pro",
        "pipeline.phase1.enabled" to "Enable Phase 1 Planning",
        "pipeline.phase2.provider" to "Phase 2 Coding Provider:",
        "pipeline.phase2.model" to "Phase 2 Coding Model:",
        "pipeline.phase2.model.comment" to "Default: deepseek-v4-flash",
        "pipeline.phase3.provider" to "Phase 3 Review Provider:",
        "pipeline.phase3.model" to "Phase 3 Review Model:",
        "pipeline.phase3.model.comment" to "Default: agnes-2.5-flash",
        "pipeline.phase3.enabled" to "Enable Phase 3 Review",
        "pipeline.comment" to "Each phase can use a different Provider+Model. The API Key comes from the selected Provider's configuration above.",
        "pipeline.view.models" to "View Models",

        "completion.group.title" to "Code Completion",
        "completion.enable" to "Enable AI code completion",
        "completion.model" to "Completion Model:",
        "completion.model.comment" to "Leave empty to use main model above. Try: deepseek-v4-flash",
        "completion.max.tokens" to "Max Completion Tokens:",
        "completion.max.tokens.comment" to "Max tokens per completion suggestion",
        "completion.min.prefix" to "Min Prefix Chars:",
        "completion.min.prefix.comment" to "Minimum characters typed before triggering completion (default: 2)",
        "completion.max.context" to "Max Context Lines:",
        "completion.max.context.comment" to "Lines of surrounding code sent to the model for context",
        "completion.debounce" to "Debounce Delay (ms):",
        "completion.annotation" to "Annotation-aware completion (analyze @Data, @Service, @Entity, etc.)",
        "completion.annotation.comment" to "Automatically suggests synthetic methods (Lombok), code patterns (Spring/JPA), and annotation names",
        "completion.comment" to "Comment-aware completion (analyze // getter, // singleton, // TODO, etc.)",
        "completion.comment.comment" to "Generates code based on natural language comments: // getter for name, // save, // singleton pattern, etc.",
        "completion.cache" to "Enable result cache (LRU)",
        "completion.cache.comment" to "Cache AI completion results to avoid redundant API calls (default: enabled, 20 entries)",
        "completion.statusbar" to "Show status bar indicator",
        "completion.statusbar.comment" to "Display completion status (IDLE/GENERATING/ERROR) in the status bar",
        "completion.ghost" to "Ghost Text rendering mode (experimental)",
        "completion.ghost.comment" to "Render completions as ghost text inline instead of Lookup list. Requires Tab handler to accept.",

        "search.group.title" to "Agentic Search",
        "search.enable" to "Enable Agentic Search (as an alternative to RAG for code retrieval)",
        "search.enable.comment" to "Once enabled, code search uses grep/glob/read tools instead of BM25 vector retrieval, making results more accurate and real-time.",
        "search.rounds" to "Maximum search rounds:",
        "search.rounds.comment" to "1 = Single-round mode (fast), 2~5 = Multi-round mode (the model can iterate and search on its own, more accurate but a bit slower)",
        "search.comment1" to "Agentic Search lets models 'search \u2192 read \u2192 judge \u2192 then search again' like human programmers, making it great for situations where you need precise code matching.",
        "search.comment2" to "RAG is still used for document text retrieval (.md/.txt, etc.), and the two complement each other.",

        "image.group.title" to "Image Parsing",
        "image.model" to "Image Parsing Model:",
        "image.model.comment" to "Select the model used for parsing images in chat",
        "image.stepfun.key" to "StepFun API Key:",
        "image.agnes.comment" to "Agnes reuses the API key configured in the API Configuration section above.",
        "image.model.agnes" to "Agnes Image 2.1 Flash",
        "image.model.stepfun" to "StepFun",

        "skill.title" to "\u2699 Skill Settings",
        "skill.back" to "Back to Chat",
        "skill.community.hint" to "<html>Need more Skills? Try the community Skill library \u2192https://github.com/lvesDi005/useful-skill",
        "skill.drop.hint" to "Drag files here to upload, or click the button below to select files",
        "skill.upload" to "\uD83D\uDCC4 Upload Skill File",
        "skill.choose.title" to "Select Skill Files",
        "skill.file.filter" to "Skill Files (*.md, *.txt, *.yaml, *.yml, *.json)",
        "skill.empty" to "No skill files yet. Upload Markdown/text files as AI skill constraints.",
        "skill.preview" to "Preview",
        "skill.delete" to "Delete Skill",
        "skill.delete.confirm" to "Are you sure you want to delete skill",
        "skill.delete.title" to "Delete Skill",
        "skill.delete.yes" to "Delete",
        "skill.delete.no" to "Cancel",
        "skill.duplicate.warning" to "Skill already exists. Please delete or rename the file first.",
        "skill.duplicate.title" to "Duplicate Skill",
        "skill.read.error" to "Cannot read file",
        "skill.read.error.title" to "Read Failed",
        "skill.empty.file" to "File is empty.",
        "skill.empty.file.title" to "Empty File",
        "skill.tag.editor.message" to "Add tags for skill«%s»(comma separated),\ntags help match relevant skills in conversation.\ne.g.: java, spring, mybatis, config",
        "skill.tag.editor.title" to "Edit Tags — %s",
        "skill.file.unsupported" to "File«%s»is not supported. Only %s are allowed.",
        "skill.file.unsupported.title" to "Unsupported File Type",
        "skill.file.too.large" to "File«%s»is %dKB, exceeds 50KB limit",
        "skill.file.too.large.title" to "File Too Large",

        "change.title" to "\uD83D\uDCCB Change Management",
        "change.back" to "Back to Chat",
        "change.rollback" to "Rollback this record",
        "change.delete" to "Delete this record",
        "change.view" to "View Changes",
        "change.rollback.file" to "Rollback this file",
        "change.empty" to "No change records yet.",
        "change.expand" to "\u25B6",
        "change.collapse" to "\u25BC",
        "change.file.icon" to "\u2728",
        "change.file.icon.default" to "\uD83D\uDCC4",
        "change.confirm.rollback" to "Confirm Rollback",
        "change.confirm.rollback.all" to "Rollback All",
        "change.delete.record" to "Delete Record",
        "change.result.success" to "\u2705 Successfully rolled back",
        "change.result.partial" to "\u26A0\uFE0F Partial rollback completed",
        "change.result.failed" to "\u274C Rollback failed",
        "change.diff.title" to "Diff Comparison",
        "change.diff.new.file" to "(New File)",
        "change.diff.backup" to "Backup Version",
        "change.diff.current" to "Current Version",
        "change.rollback.dialog.title" to "Confirm Rollback",
        "change.delete.dialog.title" to "Delete Record",

        "diff.no.change" to "Generated code is identical to the original. No changes needed.",
        "diff.no.change.title" to "No Changes",
        "diff.preview.title" to "Preview Code Changes",
        "diff.apply" to "Apply Changes",
        "diff.original" to "Original Code",
        "diff.generated" to "Generated Code",
        "diff.batch.title" to "Changes Preview ({0} files)",
        "diff.batch.execute" to "Execute All Changes",
        "diff.batch.expand" to "▶ View Changes",
        "diff.batch.collapse" to "▼ Collapse",
        "diff.batch.load" to "Load File System Changes",

        "agent.asking" to "Asking DeepSeek...",
        "agent.explaining" to "Explaining Code...",
        "agent.generating" to "Generating Code...",
        "agent.reviewing" to "Reviewing Code...",
        "agent.optimizing" to "Optimizing Code...",
        "agent.select.code.ask" to "Select code or open a file to ask questions about it.",
        "agent.select.code.explain" to "Select code to explain.",
        "agent.select.code.generate" to "Select code or describe what to generate.",
        "agent.select.code.review" to "Select code to review.",
        "agent.select.code.optimize" to "Select code to optimize.",
        "agent.calling" to "Analyzing code...",
        "agent.config.required" to "Please configure your API Key in Settings...",
        "agent.config.title" to "API Key Required",
        "agent.api.error" to "API Error",
        "agent.title" to "AI Agent",
        "agent.menu.setlang" to "Set Language",
        "agent.menu.explain" to "Explain Code",
        "agent.menu.review" to "Review Code",
        "agent.menu.upload" to "Upload to Chat",

        "translate.title" to "Translate",
        "translate.button" to "Translate",
        "translate.placeholder.source" to "Enter text",
        "translate.placeholder.target" to "Translation",
        "translate.progress" to "Translating...",
        "translate.error" to "Translation failed",
        "translate.no.key" to "Please configure Agnes API Key in Settings.",
        "translate.swap" to "Swap languages",
        "lang.zh" to "Chinese",
        "lang.en" to "English",
        "lang.ja" to "Japanese",
        "lang.ko" to "Korean",
        "lang.fr" to "French",
        "lang.de" to "German",
        "lang.es" to "Spanish",
        "lang.pt" to "Portuguese",
        "lang.ru" to "Russian",
        "lang.th" to "Thai",

        "changelog.title.zh" to "DeepSeek-AI-Helper — Release Notes",
        "changelog.title.en" to "DeepSeek-AI-Helper — Release Notes",
        "changelog.header.zh" to "\uD83D\uDE80 DeepSeek-AI-Helper Update",
        "changelog.header.en" to "\uD83D\uDE80 DeepSeek-AI-Helper Update",
        "changelog.version.zh" to "Current version",
        "changelog.version.en" to "Current version",
        "changelog.setup.zh" to "\u2699 Click here to configure API Key",
        "changelog.setup.en" to "\u2699 Click here to configure API Key",
        "changelog.ok.zh" to "Got it",
        "changelog.ok.en" to "Got it",

        "lang.label" to "Language",
        // "lang.agent.label" merged into lang.label
        "selected.code.remove" to "Remove selected code reference",
        "file.remove" to "Remove",
        "code.copy" to "Copy Code",
        "code.insert" to "Insert at Cursor",
        // ── Announcement banner ──
        "announcement.content" to "Welcome to DeepSeek-AI-Helper! For issues or suggestions, please submit on GitHub.Seeking collaborators to co-build this project with passion—we contribute purely out of love for the cause.",
        "announcement.content.link" to "https://github.com/lvesDi005/DeepSeek-AI-CodeHelper",
        "announcement.dismiss" to "Dismiss",

        // ── MCP Server ──
        "settings.mcp.server" to "MCP Server",
        "settings.mcp.title" to "MCP Server Configuration",
        "settings.mcp.desc" to "MCP (Model Context Protocol) Server exposes IntelliJ IDEA capabilities to external AI clients (Claude Desktop, Cursor, etc.) via a local SSE/HTTP endpoint.",
        "settings.mcp.enable" to "Enable MCP Server",
        "settings.mcp.port" to "Port:",
        "settings.mcp.port.comment" to "(1-65535, default: 8080)",
        "settings.mcp.auto_start" to "Auto-start on IDE launch",
        "settings.mcp.status" to "Status",
        "settings.mcp.status.running" to "Status: Running",
        "settings.mcp.status.stopped" to "Status: Stopped",
        "settings.mcp.start" to "Start Server",
        "settings.mcp.stop" to "Stop Server",
        "settings.mcp.refresh" to "Refresh",
        "settings.mcp.sessions" to "Sessions:",
        "settings.mcp.tools" to "Tools:",

        // ── External MCP ──
        "settings.mcp.external" to "External MCP",
        "settings.mcp.external.title" to "External MCP Servers",
        "settings.mcp.external.desc" to "Connect to external MCP servers to provide additional tools for the Agent mode. Tool names are prefixed with the server name, e.g. <code>stripe_get_balance</code>.",
        "settings.mcp.external.add" to "+ Add MCP Server",
        "settings.mcp.external.name" to "Name:",
        "settings.mcp.external.type" to "Type:",
        "settings.mcp.external.url" to "URL/Command:",
        "settings.mcp.external.args" to "Args:",
        "settings.mcp.external.auto_start" to "Auto-connect on startup",
        "settings.mcp.external.connected" to "● Connected",
        "settings.mcp.external.disconnected" to "○ Disconnected",
        "settings.mcp.external.connect" to "Connect",
        "settings.mcp.external.disconnect" to "Disconnect",
        "settings.mcp.external.edit" to "Edit",
        "settings.mcp.external.remove" to "Remove",
        "settings.mcp.external.test_save" to "Test & Save",
        "settings.mcp.external.cancel" to "Cancel",
        "settings.mcp.external.save" to "Save",
        "settings.mcp.external.refresh_tools" to "Refresh Tools",
        "settings.mcp.external.empty" to "No external MCP servers configured. Click '+ Add MCP Server' to add one.",
        "settings.mcp.external.add_title" to "Add MCP Server",
        "settings.mcp.external.edit_title" to "Edit MCP Server",
        "settings.mcp.external.tools" to "Tools:",
    )
}
