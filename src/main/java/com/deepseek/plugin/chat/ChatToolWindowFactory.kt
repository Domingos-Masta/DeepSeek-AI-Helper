package com.deepseek.plugin.chat

import com.deepseek.plugin.PluginVersion
import com.deepseek.plugin.settings.DeepSeekSettings
import com.deepseek.plugin.ui.ChangelogDialog
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ChatToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatPanel = ChatPanel(project)
        val content = ContentFactory.getInstance().createContent(
            chatPanel, "", false
        )
        toolWindow.contentManager.addContent(content)

        // Focus input when window is activated
        toolWindow.activate {
            chatPanel.focusInput()
        }

        // ── Check version and show changelog on update ──
        checkVersionAndShowChangelog(project)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    /**
     * Compare the last-seen version with the current plugin version.
     * If they differ, show the changelog dialog and update the stored version.
     */
    private fun checkVersionAndShowChangelog(project: Project) {
        val settings = DeepSeekSettings.instance
        val currentVersion = PluginVersion.current

        // First install or version changed → show changelog
        if (settings.lastSeenVersion != currentVersion) {
            val previousVersion = settings.lastSeenVersion.ifBlank { null }

            val dialog = ChangelogDialog(
                project = project,
                previousVersion = previousVersion,
                currentVersion = currentVersion,
                changeLogHtml = CHANGELOG_HTML,
                changeLogHtmlEn = CHANGELOG_HTML_EN,
                initialLanguage = settings.changelogLanguage
            )
            dialog.show()

            // Persist language preference after dialog closes
            settings.changelogLanguage = dialog.selectedLanguage

            // Persist the new version so the dialog doesn't show again
            settings.lastSeenVersion = currentVersion

            // Navigate to settings page if user clicked the setup link
            if (dialog.navigateToSettingsOnClose) {
                ChatPanel.currentInstance?.showSettingsPage("apiConfig")
            }
        }
    }

    companion object {
        /**
         * HTML changelog content displayed in the update dialog.
         */
        private val CHANGELOG_HTML = """
            <h3>v2.8.0</h3>
            <ul>
              <li>[New] Chat-bubble style icons for the tool window, actions, and assistant avatar</li>
              <li>[Improved] Live step-by-step status (analyzing → reasoning → generating) while streaming instead of a static spinner</li>
              <li>[Improved] Copilot-style chat UI polish</li>
              <li>[Changed] Plugin renamed to DeepSeek-AI-Helper</li>
            </ul>
            <h3>v2.7.6</h3>
            <ul>
              <li>[Fix] UI truncation</li>
            </ul>
            <h3>v2.7.5</h3>
            <ul>
              <li>[New] Unified document parsing — supports PDF, DOCX, XLSX, PPTX and text attachments, automatically detecting the file format and extracting structured text content</li>
              <li>[New] Scanned PDF parsing — PDF pages with no extractable text are rendered as images and passed to a vision-capable model for further understanding</li>
              <li>[New] Native attachment support for Claude Code / Codex CLI — documents, images and parsing results are safely passed according to each CLI's native parameters and input methods, making full use of Claude and Codex file and vision capabilities</li>
              <li>[Fix] Unified attachment routing for Q&amp;A, Q&amp;A full-text scan, Agent and Claude/Codex modes, preventing uploaded documents from being left out of the model context</li>
              <li>[Improved] Added attachment size/count limits, format validation, background parsing, task cancellation and temp-file cleanup, plus document-parsing tests and an attachment rework acceptance matrix</li>
              <li>[Improved] Claude/Codex Q&amp;A context governance — regular Q&amp;A no longer implicitly scans the project; attachments, source code and scan results are used only for the current request; history is trimmed by the Provider's context window while keeping the most recent complete conversation</li>
              <li>[Enhanced] Native Q&amp;A capability for Claude/Codex — Claude supports native image messages; Codex supports native images and reasoning effort; full-text scan only exposes local read-only retrieval tools, with improved streaming errors, cancellation, retries and partial-answer retention</li>
              <li>[Fix] Bottom Token stats could be incomplete after an answer finished — distinguish user scrolling from layout changes, stabilize bottom positioning after completion, and clean up the streaming placeholder spacing; Token usage is persisted with the conversation and restored after switching conversations</li>
            </ul>
            <h3>v2.7.4</h3>
            <ul>
              <li>[New] Native Codex Responses API protocol — added the <code>codex-responses</code> protocol; the Codex (OpenAI) provider now calls the OpenAI Responses API directly (<code>POST /v1/responses</code>), compatible with cc-switch "provider native Responses direct connection" configurations; system messages are automatically converted to the <code>instructions</code> field; streaming SSE listens for <code>response.output_text.delta</code> token-by-token callbacks; supports new Responses-API-only models such as gpt-5.2-codex and gpt-5.1-codex</li>
              <li>[Fix] Fixed the issue where claude and codex were unavailable in Q&amp;A mode. Both codex and claude now work in their respective CLI modes and in Q&amp;A mode</li>
            </ul>
            <h3>v2.7.3</h3>
            <ul>
              <li>[New] Fourth mode "Claude/Codex" — drives the native Agent capabilities of the local Claude Code / Codex CLI (Q&amp;A, code editing, command execution, tool calling), reusing the local login state for credentials; the mode dropdown dynamically shows Claude Code / Codex based on the Provider; supports permission policies (file edits only / skip all permissions / plan only); before/after file snapshots are compared and changes are automatically recorded to "Change Management" for rollback</li>
            </ul>
            <h3>v2.7.2</h3>
            <ul>
              <li>[New] Claude (Anthropic) Provider — added Anthropic/Claude support via an OpenAI-compatible endpoint, so Claude models can be used directly in chat, Agent and right-click features</li>
              <li>[New] Automatically reads local Claude configuration — API Key resolution chain: settings panel → ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN environment variables (AUTH_TOKEN corresponds to the Claude subscription OAuth login state) → explicit authorization to read ~/.claude/settings.json</li>
              <li>[New] Settings panel gains a Claude configuration group — model dropdown (claude-sonnet-4-5 / claude-opus-4-5 etc.), Base URL configuration (supports the ANTHROPIC_BASE_URL environment variable), and live display of the current Key source</li>
              <li>[Security] OAuth credential protection — the OAuth token from ~/.claude is sent only to the official Anthropic endpoint, preventing leakage to third-party Base URLs</li>
              <li>[Improved] Code-completion protection — Providers that do not support FIM (Anthropic etc.) automatically skip completion requests; Alt+P manual completion shows a clear hint</li>
              <li>[New] Codex (OpenAI) Provider — reuses the local Codex CLI login state (OPENAI_API_KEY / CODEX_API_KEY in ~/.codex/auth.json), supports OPENAI_API_KEY / CODEX_API_KEY / OPENAI_BASE_URL environment variables, and automatically reads the model and proxy Base URL from ~/.codex/config.toml (including cc-switch's model_providers configuration)</li>
              <li>[Fix] Claude / Codex 403 errors — removed runtime auto-reading of local credentials (OAuth tokens cannot be used against API endpoints), preventing unauthorized credentials from being sent and causing 403</li>
              <li>[Fix] Claude cc-switch relay integration — added the native Anthropic Messages API protocol (POST /v1/messages + x-api-key + anthropic-version), supports Anthropic-compatible endpoints pointed to by ANTHROPIC_BASE_URL (e.g. https://api.deepseek.com/anthropic), and supports ANTHROPIC_DEFAULT_SONNET/HAIKU/OPUS_MODEL model mapping</li>
              <li>[Improved] Separate Claude / Codex configuration pages — a "Claude / Codex" page was added to settings: Claude provides a "Use local settings.json" button and Codex a "Use local configuration" button, which explicitly authorize reading ~/.claude/settings.json and ~/.codex/config.toml + auth.json before writing them into the settings</li>
            </ul>
            <h3>v2.7.1</h3>
            <ul>
              <li>[New] Zhipu configuration item</li>
            </ul>
            <h3>v2.7.0</h3>
            <ul>
              <li>Switch Agnes to a mainland-China-accessible endpoint</li>
              <li>[New] MCP Server — starts an MCP (Model Context Protocol) service inside the IDE, exposing IDEA capabilities via an SSE/HTTP endpoint; external AI clients (Claude Desktop, Cursor, etc.) can connect and call tools for file read/write, code search, symbol navigation and more</li>
              <li>[New] MCP settings — an "MCP Server" tab was added to the theme settings, with options for enable/disable, port (default 8080) and auto-start</li>
              <li>[New] 8 built-in MCP tools — read_file / write_file / list_directory / search_in_project / find_symbol / get_project_info / get_open_files / get_active_editor_content</li>
              <li>[New] Extension Point — provides the mcpToolProvider EP for other plugins to register custom MCP tools</li>
            </ul>
            <h3>v2.6.2</h3>
            <ul>
              <li>[New] Reasoning-process toggle — a "Show reasoning process" checkbox was added to the settings menu; when off, Q&amp;A / Q&amp;A full-text scan modes hide the AI's reasoning content, while AGENT mode is unaffected</li>
              <li>[Improved] Stop keeps content — when Stop is clicked, the streamed content already generated is automatically kept and rendered as a complete message bubble (Markdown / code blocks / tables), instead of being lost</li>
              <li>[Fix] Agent content left alignment — fixed the excessive left margin below code blocks in Agent mode</li>
            </ul>
            <h3>v2.6.1</h3>
            <ul>
            <li>Layout optimization</li>
            </ul>
            <h3>v2.6.0</h3>
            <ul>
              <li>[New] Deep reasoning display — the reasoning content (reasoning_content) in AI replies is shown collapsibly, can be expanded/collapsed, has a character count, and adapts to dark/light themes</li>
              <li>[New] Font size setting — the theme settings gained a "Font size" dropdown (12px~16px) that controls the chat panel content font size</li>
              <li>[New] Merged language settings — "Interface language" and "AI output language" were merged into a single "Language" setting</li>
              <li>[New] OpenRouter default model change — the default model changed to inclusionai/ling-3.0-flash:free, and the model input box became an editable dropdown</li>
              <li>[Improved] Code completion — Ghost Text enabled by default, AI status restored in the status bar, bubble hints for consecutive failures, and a Python pre-check layer to reduce invalid API calls</li>
              <li>[Improved] Code completion — streaming FIM: SSE returns token by token, showing the first token in Ghost Text immediately to reduce perceived latency</li>
              <li>[Improved] Code completion — added the Ctrl+Shift+, shortcut to toggle completion on/off</li>
            </ul>
            <h3>v2.5.9</h3>
            <ul>
              <li>Agent mode intent-parsing optimization</li>
            </ul>
            <h3>v2.5.8</h3>
            <ul>
              <li>UI optimization</li>
            </ul>
            <h3>v2.5.7</h3>
            <ul>
              <li>[New] Right-click menu "Set Language / Set Language" — jumps to the theme settings in one click and independently controls the right-click output language (isolated from the chat panel)</li>
              <li>[New] Independent right-click output-language setting — a "Right-click output language" dropdown was added to the theme settings, supporting Chinese/English toggle</li>
              <li>[Improved] Right-click menu text is dynamically bilingual — Set Language / Explain Code / Review Code / Upload to Chat update in real time with the language setting</li>
              <li>[Improved] Explain/Review streaming output — changed to streaming SSE token-by-token display, removed the domain-restriction prompt, and shows the first token immediately, improving response speed by 30-50%</li>
            </ul>
            <h3>v2.5.6</h3>
            <ul>
              <li>Optimized image processing</li>
            </ul>
            <h3>v2.5.5</h3>
            <ul>
              <li>Agent-mode pipeline optimization</li>
              <li>Added Agent-mode Token statistics</li>
            </ul>
            <h3>v2.5.4</h3>
            <ul>
              <li>NVIDIA NIM model support + OpenRouter model support — added an NVIDIA API Provider (z-ai/glm-5.2, minimaxai/minimax-m3, stepfun-ai/step-3.7-flash) + OpenRouter API Provider, with a dropdown to select models</li>
              <li>Agent planning/coding phases use the current Provider model — Phase 1 (planning) + Phase 2 (coding) no longer hard-code DeepSeek; instead they use the Provider + Model selected in Settings</li>
              <li>Independent per-Phase Agent Pipeline configuration — Settings gained an "Agent Pipeline (Agent pipeline configuration)" section, allowing a Provider + Model to be specified for each of the four phases: intent confirmation, planning, coding and review</li>
              <li>Settings button + popup menu — a ⚙ gear settings button was added to the left of the status bar; its popup menu includes Q&amp;A/Agent mode switching + a shortcut to the Agent Pipeline Phase settings</li>
              <li>Fixed the NVIDIA API request format — topP/frequencyPenalty/presencePenalty parameters changed to snake_case, complying with the OpenAI protocol specification</li>
              <li>Q&amp;A intent classification — in Q&amp;A mode the Phase 0 model is called first to recognize user intent; ordinary questions are answered directly (no project scan), and source file context is read automatically on errors or when code is selected</li>
              <li>Model catalog dialog — a "View Models" button was added to Settings; clicking it opens a dialog showing the available model list for each vendor</li>
            </ul>
            <h3>v2.5.3</h3>
            <ul>
              <li>Image-parsing settings upgrade — "StepFun Image Parsing (Image Parsing)" in Settings became a first-level "Image Parsing (Image Parsing)" title with a new dropdown to select the parsing model: Agnes Image 2.1 Flash / StepFun, defaulting to Agnes</li>
              <li>Key reuse — Agnes reuses its own configured API Key with no extra input needed; StepFun keeps a separate key input</li>
              <li>Clipboard image paste — the input box supports Ctrl+V to paste clipboard images directly, automatically adding them as attachments</li>
              <li>Image parsing without modal dialogs — images are shown directly in the user message bubble, the AI streaming bubble shows a "Parsing..." status, and after parsing completes the result is automatically fed to the AI for a second-pass optimized output, with no popup interruption throughout</li>
            </ul>
            <h3>v2.5.2</h3>
            <ul>
              <li>Deep domain restriction — injects a CS-only domain-restriction prompt into all LLM interactions in the plugin, locking the model into a pure computer-science domain assistant</li>
              <li>Agent self-introduction — when a non-CS topic is out of scope, the model replaces a cold refusal with a complete but concise self-introduction, guiding the user back to technical topics</li>
              <li>Absolute jailbreak defense — for non-CS topics, applies topic whitelist filtering and outputs a fixed self-explanation verbatim, strictly forbidding any additional content</li>
              <li>Edge-case adjudication rules — for technical ethics or option comparisons, analyze only pure technical quantitative metrics such as time complexity and throughput</li>
              <li>Output format specification — enforces structured layered Markdown replies, language-type highlighting for code syntax, and acknowledging unknown concepts to forbid hallucination</li>
              <li>Full coverage injection — the restriction applies to all 6 LLM paths: Q&amp;A / multi-phase Agent / right-click Agent / code completion / Agentic Search / translation</li>
            </ul>
            <h3>v2.5.1</h3>
            <ul>
              <li>Change-management panel — after Agent mode modifies files, .bak files are no longer generated in the source directory; changes are now uniformly recorded in the "Change Management" panel</li>
              <li>A "Change Management" button (using a native icon) was added to the toolbar to open the change-management page</li>
              <li>Every Agent operation automatically creates a change record with a title format of "Changes for requirement xx" + timestamp</li>
              <li>Records can be expanded to view the specific list of modified files</li>
              <li>↩ Each file provides a "Rollback" button to restore the original content in one click</li>
              <li>👁 Each file provides a "View Changes" button that opens the IntelliJ Diff window to compare differences</li>
            </ul>
            <h3>v2.5.0</h3>
            <ul>
              <li>Introduced multi-agent division of labor — Agent mode was split into three dedicated Agent pipeline jobs</li>
              <li>Planning Agent (DeepSeek-V4-Pro) — analyzes requirements and produces a code-change plan</li>
              <li>Coding Agent (DeepSeek-V4-Flash) — generates concrete code changes according to the plan</li>
              <li>Review Agent (Agnes-2.0-Flash) — reviews code quality, security and correctness</li>
              <li>Q&amp;A mode remains single-agent and unaffected</li>
              <li>API client extension — added chatStreamWithExplicitConfig and chatSyncWithExplicitConfig methods to support specifying different models/Providers per Agent</li>
              <li>Agent-mode file-operation backup mechanism remains unchanged, automatically creating a .bak backup before modifying</li>
            </ul>
            <h3>v2.4.4</h3>
            <ul>
              <li>Added skill-settings feature — a gear settings button was added to the conversation bar to open the full-area skill-management panel</li>
              <li>Supports uploading skill files (.md/.txt/.yaml) — both button upload and drag-and-drop upload</li>
              <li>Skill list management — enable/disable toggles, content preview and skill deletion</li>
              <li>Skill injection into the system prompt — enabled skill content is automatically appended to the Agent-mode system prompt to constrain and standardize AI behavior</li>
              <li>Community Skill library link — the settings panel provides a shortcut to the community Skill library</li>
            </ul>
            <h3>v2.4.3</h3>
            <ul>
              <li>Introduced Agentic Search (Agentic code search) — replaces RAG for code retrieval: the model can autonomously call grep/glob/read tools like a human programmer "search → read → judge → search again", with code matching far exceeding BM25 semantic retrieval</li>
              <li>RAG returns to text/document retrieval — RagIndexer only indexes document files such as .md/.txt/.rst; code search uses AgenticSearch (grep/glob/read) for zero-latency, high-precision matching</li>
              <li>Added Agentic Search configuration — can be enabled/disabled in settings, with a configurable number of search rounds (1 = single-round fast mode, >1 = multi-round deep search mode)</li>
              <li>Added automatic query classification — the system automatically determines whether the user's question is code-related or document-related and chooses the optimal retrieval strategy</li>
            </ul>
            <h3>v2.4.2</h3>
            <ul>
              <li>Introduced RAG (BM25 + per-line chunking) — used for text/document retrieval</li>
            </ul>
            <h3>v2.4.1</h3>
            <ul>
              <li>The streaming reply message box auto-grows with content — removed the fixed JBScrollPane height; streamTextArea is placed directly in a BorderLayout, and revalidate() is called after each token append to trigger layout recalculation, so the bubble grows gradually with the AI's character-by-character output instead of jumping after full generation</li>
              <li>Each message bubble independently draws its own rounded background — a flattened architecture removed the four nested layers card/outer/wrapper/padded; MessageBubble now uses GridBagLayout to draw itself, so resizing one bubble does not affect other bubbles' layout</li>
              <li>Compact code-block layout — font 13→12, padding 14x18→8x14, HTML line height 1.35, so code displays more compactly</li>
            </ul>
            <h3>v2.4.0</h3>
            <ul>
              <li>Supports non-Java IDEs such as PyCharm / DataGrip — the Java dependency became optional; chat/Agent/AI completion work normally in IDEs without the Java plugin, and Java-specific features (static analysis, annotation/comment-aware completion) degrade gracefully</li>
              <li>Button tooltip fix — tooltips for 9 buttons (usage view, conversation history, new conversation, etc.) now display correctly</li>
            </ul>
            <h3>v2.3.5</h3>
            <ul>
              <li>Fixed newline failures and input-box truncation</li>
            </ul>
            <h3>v2.3.4</h3>
            <ul>
              <li>Added code-change preview — after Generate/Optimize produces code, a side-by-side comparison page pops up for the user to review before confirming whether to save</li>
              <li>Added translation dialog — a translation entry was added next to the upload button in the chat input bar, opening a horizontal two-column translation UI supporting 10 languages</li>
            </ul>
            <h3>v2.3.3</h3>
            <ul>
              <li>Dark flat UI rework — all message cards use large rounded corners (arc=18) and a unified dark background of #24242A</li>
              <li>Unified purple circular avatars — both user (toolwindow.svg) and AI (action.svg) are 26×26 purple circles for visual consistency</li>
              <li>A "me" name label was added to the right of the user avatar, symmetric with the AI "DP Helper"</li>
              <li>Balanced card padding — unified EmptyBorder(12,16,16,16) to remove excess top whitespace</li>
              <li>Delete button reuses the system icon — emoji replaced with AllIcons.Actions.Close, consistent with the clear button</li>
              <li>Full code panel rendering — removed JBScrollPane; all code lines render without truncation or scrolling, with padding increased to 14×18</li>
              <li>Added a visual table component — MessageTable is a unified GridBagLayout grid supporting ✓/✕ colored markers and automatic wrapping</li>
              <li>Automatic Markdown pipe-table detection — parseResponse() automatically detects |...| table syntax and renders it as MessageTable</li>
            </ul>
            <h3>v2.3.2</h3>
            <ul>
              <li>New feature: Upload to Chat (uploads console-selected content to the chat panel)</li>
            </ul>            
            <h3>v2.3.1</h3>
            <ul>
              <li>Architecture optimization</li>
            </ul>
            <h3>v2.3.0</h3>
            <ul>
              <li>Annotation-aware completion — automatically analyzes annotations such as @Data/@Service/@RestController/@Entity and recommends synthetic methods (Lombok getter/setter/builder) and code patterns (Spring REST skeleton, JPA mapping, constructor injection)</li>
              <li>Comment-aware completion — write natural language in comments to generate code, supporting Chinese: <code>// delete order by id</code> → deleteById, <code>// query all</code> → findAll, <code>// get name field</code> → getName and 30+ other patterns</li>
              <li>@Annotation-name auto-completion — when typing @ at a class/method/field/parameter position, automatically recommends the annotation list applicable to the current context</li>
              <li>Added independent settings toggles — "annotation-aware completion" and "comment-aware completion" can be enabled/disabled separately</li>
              <li>Static-analysis pre-filtering — IntelliJ PSI/AST-based static completion acts as a pre-filter layer for AI completion, reducing unnecessary API calls</li>
            </ul>
            <h3>v2.2.5</h3>
            <ul>
              <li>Send and newline changed to Enter / Shift+Enter</li>
            </ul>
            <h3>v2.2.4</h3>
            <ul>
              <li>Responsive message-panel layout — message bubbles auto-fit their width and dynamically reflow when the window resizes, eliminating excess vertical whitespace around bubbles</li>
              <li>Continuous split-pane layout — dragging the divider between messages and the input area updates content in real time</li>
              <li>The input-area drag handle now directly adjusts the divider position, making the operation more controllable</li>
              <li>Maximum code-block height increased from 300px to 1500px to fit long code snippets</li>
              <li>Tightened message-bubble padding to reduce vertical whitespace around user/assistant messages</li>
            </ul>
            <h3>v2.2.3</h3>
            <ul>
              <li>Fix: button click events</li>
            </ul>
            <h3>v2.2.2</h3>
            <ul>
              <li>Fix: JTextComponent.modelToView(int) -> modelToView2D(int)</li>
            </ul>
            <h3>v2.2.1</h3>
            <ul>
              <li>VSCode industrial dark-theme input panel — 4-layer vertical layout: notification bar, toolbar, resizable input area, status bar</li>
              <li>Input area auto-resizing — up to 12 rows, scrolls beyond that, supports drag-to-adjust height</li>
              <li>Concise selected-code badge — shows only the file name and line range, not the code snippet</li>
              <li>Attachment upload and UI operations use native IntelliJ icons</li>
            </ul>
            <h3>v2.2.0</h3>
            <ul>
              <li>Multi-model provider support — the settings panel can switch between DeepSeek and Agnes 2.0 Flash</li>
              <li>Agnes 2.0 Flash integration — configurable API Key, model name and Base URL</li>
              <li>Settings UI update — added an API Provider selector and a dedicated Agnes configuration area</li>
            </ul>
            <h3>v2.1.0</h3>
            <ul>
              <li>Version-update popup — automatically shows the changelog after a plugin update</li>
              <li>Multiple bug fixes and stability improvements</li>
              <li>Backward compatible with IDEA 24.1.x ~ 26.3.x</li>
            </ul>
            <h3>v2.0.2</h3>
            <ul>
              <li>Added Agent mode — switch between "💬 Q&amp;A" and "🤖 Agent" in the dropdown at the bottom of the chat panel</li>
              <li>In Agent mode the AI automatically scans the project directory, locates target files, and performs create/modify/delete operations according to the user's request</li>
              <li>Recursively collects project source files (skipping .git/build/target/node_modules etc.) as AI context</li>
              <li>The AI outputs structured <code>&lt;file path="..."&gt;</code> tags to specify file operations</li>
              <li>Path-traversal protection — canonicalized path validation ensures all write operations stay inside the project root</li>
              <li>User confirmation dialog — shows the complete list of pending file operations before executing</li>
              <li>Agent mode supports multi-turn conversations — the full message history is kept between consecutive requests</li>
              <li>When Agent is active the send button text changes to "Execute (Enter)"</li>
            </ul>
            <h3>v2.0.1</h3>
            <ul>
              <li>Large-scale UI rework — ChatPanel split into modular components (MessageBubble, ChatInputBar, ChatToolbar, SessionBar)</li>
              <li>AI replies support Markdown rendering (bold, italic, inline code, links, lists)</li>
              <li>Message beautification: rounded corners, shadows, circular avatar icons (user/assistant), timestamp display</li>
              <li>Usage and history dialogs extracted into separate classes</li>
              <li>Packaging optimization — stripped Android/security platform classes from the okhttp JAR to pass Plugin Verifier</li>
              <li>Fixed IntelliJ 2025.1/2025.2 compatibility issues</li>
            </ul>
            <h3>v1.0.5</h3>
            <ul>
              <li>Added issue-navigation sidebar — shows 6 nodes at a time with dynamic updates on scroll; fixed the issue where clicking a node did not scroll to the corresponding reply</li>
              <li>Message bubbles auto-fit their width (75% of container width) for a more balanced, readable layout</li>
            </ul>
            <h3>v1.0.4</h3>
            <ul>
              <li>Chat input file-upload button — click to select a local file, shown above the input area as an upload preview card; file content is sent with the message as context</li>
              <li>Selected-code preview card — shows the file, line range and the first 3 lines of the selected code, with one-click close</li>
              <li>Code block card (CodeBlockCard) extracted as a reusable UI component with copy and insert buttons</li>
              <li>Clear button moved to the conversation bar for easier conversation management</li>
            </ul>
            <h3>v1.0.3</h3>
            <ul>
              <li>Fixed the issue of AI replies consuming double Tokens</li>
              <li>Fixed the oversized right-click menu icon</li>
              <li>Fixed the oversized Agent result dialog</li>
              <li>Improved code completion: single-suggestion mode, optimized FIM prompts</li>
            </ul>
            <h3>v1.0.0</h3>
            <ul>
              <li>AI-driven inline code completion</li>
              <li>Streaming chat Q&amp;A panel</li>
              <li>Agent mode: explain code, generate code, review code, optimize code</li>
              <li>Right-click menu integration</li>
              <li>API Key configuration page</li>
            </ul>
        """.trimIndent()

        /**
         * English version of the changelog HTML.
         */
        private val CHANGELOG_HTML_EN = """
            <h3>v2.8.0</h3>
            <ul>
              <li>[New] Chat-bubble style icons for the tool window, actions, and assistant avatar</li>
              <li>[Improved] Live step-by-step status (analyzing → reasoning → generating) while streaming instead of a static spinner</li>
              <li>[Improved] Copilot-style chat UI polish</li>
              <li>[Changed] Plugin renamed to DeepSeek-AI-Helper</li>
            </ul>
            <h3>v2.7.6</h3>
            <ul>
              <li>[Fixed] UI cutoff</li>
            </ul>
            <h3>v2.7.5</h3>
            <ul>
              <li>[New] Unified document parsing — Supports PDF, DOCX, XLSX, PPTX, and text attachments, with automatic format detection and structured text extraction</li>
              <li>[New] Scanned PDF support — PDF pages without extractable text are rendered as images for continued analysis by vision-capable models</li>
              <li>[New] Native attachments for Claude Code / Codex CLI — Documents, images, and extracted content are passed safely through each CLI's native arguments and input flow, making full use of Claude and Codex file and vision capabilities</li>
              <li>[Fixed] Unified attachment routing across Q&amp;A, Q&amp;A Full Scan, Agent, and Claude/Codex modes so uploaded documents consistently reach model context</li>
              <li>[Improved] Added attachment count and size limits, format validation, background parsing, cancellation, temporary-file cleanup, document parsing tests, and an attachment-refactor acceptance matrix</li>
              <li>[Improved] Claude/Codex Q&amp;A context management — Normal Q&amp;A no longer scans the project implicitly; attachments, source, and scan results are request-local; history is trimmed to each provider's context window while preserving recent complete turns</li>
              <li>[Enhanced] Native Claude/Codex Q&amp;A — Claude supports native image messages, Codex supports native images and reasoning effort, Full Scan exposes only local read-only search tools, and streaming now handles cancellation, retries, partial answers, and protocol errors more reliably</li>
              <li>[Fixed] Token usage could be partially clipped after an answer completed — User scrolling is now separated from layout changes, final bottom positioning is stabilized, streaming spacer rows are cleaned up, and usage persists across session redraws</li>
            </ul>
            <h3>v2.7.4</h3>
            <ul>
              <li>[New] Native Responses API protocol for Codex — Added <code>codex-responses</code> protocol; the Codex (OpenAI) provider now calls the OpenAI Responses API directly (<code>POST /v1/responses</code>), compatible with cc-switch's "vendor-native Responses direct" configuration; system messages are automatically mapped to the <code>instructions</code> field, streaming SSE listens to <code>response.output_text.delta</code> for per-token callbacks; supports gpt-5.2-codex, gpt-5.1-codex, and other Responses-API-only models</li>
              <li>[Fixed] Claude and Codex were unavailable in Q&A mode. Now Codex and Claude are available in both their CLI mode and Q&A mode.</li>
            </ul>
            <h3>v2.7.3</h3>
            <ul>
              <li>[New] Mode 4 "Claude/Codex" — Drives the local Claude Code / Codex CLI as a native agent (Q&A, code editing, running commands, calling tools), reusing your local login; the mode dropdown label follows the Provider (Claude Code / Codex); permission policy (accept file edits / skip all permissions / plan only); snapshot diff before/after execution is recorded into Change Management for rollback</li>
            </ul>
            <h3>v2.7.2</h3>
            <ul>
              <li>[New] Claude (Anthropic) Provider — Added Anthropic/Claude support via the OpenAI-compatible endpoint; use Claude models in Chat, Agent, and right-click features</li>
              <li>[New] Auto-read local Claude config — API key resolution: Settings panel → ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN env vars (AUTH_TOKEN = Claude subscription OAuth login) → explicitly authorized read of ~/.claude/settings.json</li>
              <li>[New] Claude config group in Settings — Model dropdown (claude-sonnet-4-5 / claude-opus-4-5, etc.), Base URL field (ANTHROPIC_BASE_URL env var supported), real-time key source indicator</li>
              <li>[Security] OAuth credential protection — OAuth tokens from ~/.claude are sent only to Anthropic official endpoints, never to third-party Base URLs</li>
              <li>[Improved] Completion guard — Providers without FIM support (e.g. Anthropic) skip completion requests; Alt+P manual completion shows a clear message</li>
              <li>[New] Codex (OpenAI) Provider — Reuses your local Codex CLI login (~/.codex/auth.json OPENAI_API_KEY / CODEX_API_KEY), supports OPENAI_API_KEY / CODEX_API_KEY / OPENAI_BASE_URL env vars, model & proxy Base URL auto-read from ~/.codex/config.toml (incl. cc-switch model_providers config)</li>
              <li>[Fixed] Claude / Codex 403 errors — Removed automatic local-credential loading at runtime (OAuth tokens cannot be used with the API endpoints), preventing unauthorized credentials from being sent and causing 403</li>
              <li>[Fixed] Claude with cc-switch proxies — Added native Anthropic Messages API protocol (POST /v1/messages + x-api-key + anthropic-version), supporting ANTHROPIC_BASE_URL pointing to Anthropic-compatible endpoints (e.g. https://api.deepseek.com/anthropic), plus ANTHROPIC_DEFAULT_SONNET/HAIKU/OPUS_MODEL model mapping</li>
              <li>[Improved] Dedicated Claude / Codex config page — New "Claude / Codex" settings page: Claude has a "Use local settings.json" button, Codex has a "Use local config" button, explicitly authorizing reads of ~/.claude/settings.json, ~/.codex/config.toml + auth.json before writing them into settings</li>
            </ul>
            <h3>v2.7.1</h3>
            <ul>
              <li>[New] Zhipu configuration options</li>
            </ul>
            <h3>v2.7.0</h3>
            <ul>
              <li>Switch to Agnes is available in China</li>
              <li>[New] MCP Server — Launches an MCP (Model Context Protocol) server within the IDE, exposing IDEA capabilities via SSE/HTTP endpoint. External AI clients (Claude Desktop, Cursor, etc.) can connect to invoke tools: file read/write, code search, symbol navigation, and more</li>
              <li>[New] MCP Settings — New "MCP Server" tab in theme settings: enable/disable, port (default 8080), auto-start on IDE launch</li>
              <li>[New] 8 built-in MCP Tools — read_file / write_file / list_directory / search_in_project / find_symbol / get_project_info / get_open_files / get_active_editor_content</li>
              <li>[New] Extension Point — Exposes mcpToolProvider EP for other plugins to register custom MCP Tools</li>
            </ul>
            <h3>v2.6.2</h3>
            <ul>
              <li>[New] Reasoning toggle — New "Show Reasoning" checkbox in settings menu; when disabled, hides AI reasoning content in Q&A and Q&A Full Scan modes (Agent mode unaffected)</li>
              <li>[Improved] Stop preserves content — Clicking Stop now retains streamed content and renders it as a complete message bubble (Markdown/code blocks/tables) instead of discarding it</li>
              <li>[Fixed] Agent left-alignment — Fixed excessive left margin on text below code blocks in Agent mode</li>
            </ul>
            <h3>v2.6.1</h3>
            <ul>
            <li>Layout optimization</li>
            </ul>
            <h3>v2.6.0</h3>
            <ul>
              <li>[New] Reasoning display — AI reasoning content (reasoning_content) shown in collapsible panel with word count</li>
              <li>[New] Font Size setting — Theme settings now include "Font Size" dropdown (12px~16px)</li>
              <li>[New] Unified Language setting — "UI Language" and "AI Output Language" merged into single "Language"</li>
              <li>[New] OpenRouter default model changed to inclusionai/ling-3.0-flash:free; model selector now editable</li>
              <li>[Improved] Code Completion — Ghost Text enabled by default, status bar restored, failure notifications, Python pre-check</li>
              <li>[Improved] Code Completion — Streaming FIM: SSE token-by-token, first token appears immediately as Ghost Text</li>
              <li>[Improved] Code Completion — New Ctrl+Shift+, shortcut to toggle on/off</li>
            </ul>
            <h3>v2.5.9</h3>
            <ul>
            <li>Optimized intent parsing in agent mode</li>
            </ul>
            <h3>v2.5.8</h3>
            <ul>
            <li>UI optimization</li>
            </ul>
            <h3>v2.5.7</h3>
            <ul>
              <li>[New] Right-click "Set Language" — One-click jump to theme settings, independently control agent output language (isolated from chat panel)</li>
              <li>[New] Independent Agent Language Setting — New "Agent Output Language" dropdown in theme settings, supports Chinese/English toggle</li>
              <li>[Improved] Dynamic menu text — Set Language / Explain Code / Review Code / Upload to Chat update in real-time as language changes</li>
              <li>[Improved] Explain/Review streaming output — Switched to SSE streaming (token-by-token), removed domain restriction prompt, first token appears immediately — 30-50% faster response</li>
            </ul>
            <h3>v2.5.6</h3>
            <ul>
              <li>Optimize image processing</li>
            </ul>
            <h3>v2.5.5</h3>
            <ul>
            <li>Optimized agent mode pipeline</li>
            <li>Added agent mode Token statistics</li>
            </ul>
            <h3>v2.5.4</h3>
            <ul>
              <li>NVIDIA NIM Support+OpenRouter Support — New NVIDIA API Provider with model selection dropdown: z-ai/glm-5.2, minimaxai/minimax-m3, stepfun-ai/step-3.7-flash</li>
              <li>Agent Phase 1+2 Uses Current Provider — Planning and Coding phases no longer hardcode DeepSeek; they use the provider and model selected in Settings</li>
              <li>Per-Phase Agent Pipeline Configuration — New "Agent Pipeline" settings section lets you assign independent Provider + Model for each phase: Intent Confirmation, Planning, Coding, Review</li>
              <li>Settings Button + Popup Menu — Gear icon on the status bar left side replaces the mode dropdown; popup menu includes Q&A/Agent mode switch and quick access to Agent Pipeline Phase settings</li>
              <li>Fixed NVIDIA API Request Format — topP/frequencyPenalty/presencePenalty parameters now use snake_case for OpenAI protocol compatibility</li>
              <li>Q&A Intent Classification — Q&A mode now classifies user intent via Phase 0 model first: general questions answered directly (no project scan), errors/selected code trigger source file reading + project scan</li>
              <li>Model Catalog Dialog — New "View Models" button in Settings shows a dialog listing all available models per provider</li>
            </ul>
            <h3>v2.5.3</h3>
            <ul>
              <li>Image Parsing Settings Upgrade — Changed "StepFun Image Parsing" to a top-level "Image Parsing" section with a dropdown to select the parsing model: Agnes Image 2.1 Flash / StepFun (default: Agnes)</li>
              <li>Key Reuse — Agnes reuse already-configured API keys; StepFun retains its own dedicated key field</li>
              <li>Clipboard Image Paste — Input area now supports Ctrl+V to paste images from clipboard, automatically added as attachments</li>
              <li>No More Modal Dialogs — Images are displayed inline in the user message bubble; the AI streaming bubble shows "Parsing..." status; parsed results are automatically fed to the AI for a second-pass optimization — all without blocking popups</li>
            </ul>
            <h3>v2.5.2</h3>
            <ul>
              <li>Deep Domain Restriction — Injected a CS-only domain restriction prompt into every LLM interaction path within the plugin, locking the model as a pure computer science AI assistant</li>
              <li>Agent Self-Introduction — When users ask non-CS questions, the model responds with a concise self-introduction instead of a cold rejection, gently guiding them back to technical topics</li>
              <li>Absolute Jailbreak Defense — Topic whitelist filtering for non-CS subjects; verbatim output of the fixed self-intro with no additional content allowed</li>
              <li>Edge Case Ruling — When addressing technical ethics or technology comparison, analysis is limited to purely quantitative metrics (time complexity, throughput, etc.)</li>
              <li>Output Format Standards — Enforced structured Markdown hierarchical replies, syntax-highlighted code blocks with language labels, and admitting unknown concepts instead of hallucinating</li>
              <li>Full Coverage — Restriction applied to all 6 LLM paths: Q&A, multi-stage Agent, right-click Agent actions, code completion (FIM), Agentic Search, and Translation</li>
            </ul>
            <h3>v2.5.1</h3>
            <ul>
               <li>Change Management Panel — Agent mode no longer creates .bak files; all changes are now recorded in the unified Change Management panel</li>
               <li>New "Change Management" toolbar button (native icon) to access the panel</li>
               <li>Each Agent operation auto-creates a change record with title format: "Change for 'xxx request'" + timestamp</li>
               <li>Records are expandable to show the list of modified files</li>
               <li>↩ Each file has a "Rollback" button to restore original content with one click</li>
               <li>👁 Each file has a "View Changes" button that opens the IntelliJ Diff window</li>
            </ul>
            <h3>v2.5.0</h3>
            <ul>
              <li>Multi-Agent collaboration introduced — Agent mode split into three specialized agents working in a pipeline</li>
              <li>Planner Agent (DeepSeek-V4-Pro) — analyzes requirements and produces a structured code modification plan</li>
              <li>Coder Agent (DeepSeek-V4-Flash) — generates concrete code changes based on the plan</li>
              <li>Reviewer Agent (Agnes-2.0-Flash) — reviews code quality, security, and correctness</li>
              <li>Q&A mode remains single-agent, unaffected</li>
              <li>API client extended — new chatStreamWithExplicitConfig and chatSyncWithExplicitConfig methods for per-agent model/provider configuration</li>
              <li>Agent mode file backup mechanism preserved — .bak backup created before every file modification</li>
            </ul>
            <h3>v2.4.4</h3>
            <ul>
              <li>New Skill Settings — gear icon added to the toolbar for accessing the full-area skill management panel</li>
              <li>Upload skill files (.md/.txt/.yaml) — via file chooser button or drag-and-drop</li>
              <li>Skill list management — enable/disable toggle, content preview, delete skill</li>
              <li>Skills injected into system prompt — enabled skills are automatically appended to the Agent mode system prompt to constrain and guide AI behavior</li>
              <li>Community Skill Library link — quick-access link in the settings panel to the community-maintained skill collection</li>
            </ul>
            <h3>v2.4.3</h3>
            <ul>
              <li>Agentic Search replaces RAG for code retrieval — the model autonomously calls grep/glob/read tools like a human programmer: search, read, judge, re-search. Deterministic exact matching far beyond BM25 semantic retrieval</li>
              <li>RAG scoped to text/document retrieval — indexes only .md/.txt/.rst etc. documentation files</li>
              <li>New Agentic Search settings — enable/disable, configure search rounds (1=fast single-round, >1=deep multi-round)</li>
              <li>Auto query classification — system detects code vs doc queries for optimal search strategy</li>
            </ul>
            <h3>v2.4.2</h3>
            <ul>
              <li>RAG (BM25 + line-based chunking) — for text/document retrieval</li>
            </ul>
            <h3>v2.4.1</h3>
            <ul>
              <li>Streaming message bubble now auto-expands with content — Removed JBScrollPane fixed height, streamTextArea placed directly in BorderLayout, revalidate() triggers layout recalc on each token append, the bubble grows smoothly as AI generates text instead of jumping to full height on completion</li>
              <li>Each message bubble is now a self-contained unit — Flattened architecture, removed 4 layers of nesting (card/outer/wrapper/padded), MessageBubble uses GridBagLayout and self-draws its rounded background; one bubble's resize no longer affects others</li>
              <li>Tighter code block layout — Font 13→12, padding 14×18→8×14, HTML line-height set to 1.35 for a more compact display</li>
            </ul>
            <h3>v2.4.0</h3>
            <ul>
              <li>Cross-IDE support — Java dependency is now optional; chat panel, Agent actions, and AI completion work in PyCharm / DataGrip etc., Java-specific features (static analysis, annotation/comment-aware completion) gracefully degrade</li>
              <li>Button tooltip fix — All 9 toolbar buttons now show tooltips on hover</li>
            </ul>
            <h3>v2.3.5</h3>
            <ul>
              <li>Fixed broken line breaks and input box truncation</li>
            </ul>
            <h3>v2.3.4</h3>
            <ul>
              <li>New code diff preview — Generate/Optimize actions now show a side-by-side diff before applying, user reviews and confirms changes</li>
              <li>New translate dialog — Added translate button next to file upload in chat input bar, opens a two-column translation UI supporting 10 languages</li>
            </ul>
            <h3>v2.3.3</h3>
            <ul>
              <li>Dark flat UI redesign — All message cards use large rounded corners (arc=18), unified dark background #24242A</li>
              <li>Unified purple circular avatars — User (toolwindow.svg) and AI (action.svg) both 26×26 purple circles for consistent look</li>
              <li>Added "me" label next to user avatar, symmetric with AI's "DP Helper"</li>
              <li>Balanced card padding — Unified EmptyBorder(12,16,16,16), eliminated excess top whitespace</li>
              <li>Delete button uses system icon — Replaced emoji with AllIcons.Actions.Close, matching the clear button</li>
              <li>Code panel full rendering — Removed JBScrollPane, all code lines fully visible without scrolling, padding increased to 14×18</li>
              <li>New visual table component — MessageTable with unified GridBagLayout grid, colored ✓/✕ markers, auto-wrapping text</li>
              <li>Auto-detection of Markdown pipe tables — parseResponse() automatically recognizes |...| table syntax and renders as MessageTable</li>
            </ul>
            <h3>v2.3.2</h3>
            <ul>
              <li>New feature: Upload to Chat (upload selected content from the console to the chat panel)</li>
            </ul>
            <h3>v2.3.1</h3>
            <ul>
              <li>Architecture optimization</li>
            </ul>
            <h3>v2.3.0</h3>
            <ul>
              <li>Annotation-aware completion — Automatically analyzes @Data/@Service/@RestController/@Entity annotations and recommends synthetic methods (Lombok getter/setter/builder) and code patterns (Spring REST skeleton, JPA mapping, constructor injection)</li>
              <li>Comment-aware completion — Write natural language in comments to generate code, supporting Chinese: <code>// delete order by id</code> → deleteById, <code>// query all</code> → findAll, <code>// get name field</code> → getName and 30+ other patterns</li>
              <li>@-annotation auto-completion — When typing @ on a class/method/field/parameter, automatically recommends applicable annotations for the current context</li>
              <li>New independent settings toggles — Enable/disable "Annotation-aware completion" and "Comment-aware completion" separately</li>
              <li>Static analysis pre-filter — IntelliJ PSI/AST-based static completion as a pre-filter layer for AI completion, reducing unnecessary API calls</li>
            </ul>
            <h3>v2.2.5</h3>
            <ul>
              <li>Send and newline changed to Enter / Shift+Enter</li>
            </ul>
            <h3>v2.2.4</h3>
            <ul>
              <li>Responsive message panel layout — Message bubbles auto-fit width, dynamically reflow on window resize, eliminating excess vertical space around bubbles</li>
              <li>Continuous split-pane layout — Dragging the divider between messages and input area updates content in real-time</li>
              <li>Input area drag handle now directly adjusts divider position for more controllable operation</li>
              <li>Max code block height increased from 300px to 1500px for long code snippets</li>
              <li>Tighter padding inside message bubbles, reducing vertical whitespace around user/assistant messages</li>
            </ul>
            <h3>v2.2.3</h3>
            <ul>
              <li>Fixed: button click event handling</li>
            </ul>
            <h3>v2.2.2</h3>
            <ul>
              <li>Fixed: JTextComponent.modelToView(int) → modelToView2D(int)</li>
            </ul>
            <h3>v2.2.1</h3>
            <ul>
              <li>VSCode industrial dark theme input panel — 4-layer vertical layout: notification bar, toolbar, scalable input area, status bar</li>
              <li>Auto-expanding input area — up to 12 lines, scrolls beyond, supports drag-to-resize</li>
              <li>Compact selected-code badge — shows only file name and line range, no code snippet preview</li>
              <li>Attachment upload and UI operations use IntelliJ native icons</li>
            </ul>
            <h3>v2.2.0</h3>
            <ul>
              <li>Multi-model provider support — settings panel can switch between DeepSeek and Agnes 2.0 Flash</li>
              <li>Agnes 2.0 Flash integration — configurable API Key, model name, and Base URL</li>
              <li>Settings UI update — new API Provider selector and Agnes-specific config area</li>
            </ul>
            <h3>v2.1.0</h3>
            <ul>
              <li>Version update dialog — automatically shows release notes after plugin update</li>
              <li>Multiple bug fixes and stability improvements</li>
              <li>Backward compatible with IDEA 24.1.x ~ 26.3.x</li>
            </ul>
            <h3>v2.0.2</h3>
            <ul>
              <li>New Agent mode — switch between "💬 Q&A" and "🤖 Agent" via dropdown at the bottom of the chat panel</li>
              <li>In Agent mode, AI automatically scans the project directory, locates target files, and performs create/modify/delete operations based on user requests</li>
              <li>Recursively collects project source files (skipping .git/build/target/node_modules etc.) as AI context</li>
              <li>AI outputs structured <code>&lt;file path="..."&gt;</code> tags to specify file operations</li>
              <li>Path traversal protection — normalized path validation ensures all write operations stay within the project root</li>
              <li>User confirmation dialog — shows complete file operation list before execution</li>
              <li>Multi-turn conversation support in Agent mode — full message history preserved across consecutive requests</li>
              <li>Send button text changes to "Execute (Enter)" when Agent is active</li>
            </ul>
            <h3>v2.0.1</h3>
            <ul>
              <li>Major UI refactor — ChatPanel split into modular components (MessageBubble, ChatInputBar, ChatToolbar, SessionBar)</li>
              <li>AI replies now support Markdown rendering (bold, italic, inline code, links, lists)</li>
              <li>Message beautification: rounded corners, shadows, circular avatar icons (user/assistant), timestamp display</li>
              <li>Usage and history dialogs extracted into standalone classes</li>
              <li>Optimized packaging — stripped Android/security classes from okhttp JAR to pass Plugin Verifier</li>
              <li>Fixed IntelliJ 2025.1/2025.2 compatibility issues</li>
            </ul>
            <h3>v1.0.5</h3>
            <ul>
              <li>New Question Navigator sidebar — shows 6 nodes at a time, dynamically updates on scroll, fixed issue where clicking a node didn't scroll to the corresponding reply</li>
              <li>Message bubbles now auto-fit to width (75% of container), more balanced and readable layout</li>
            </ul>
            <h3>v1.0.4</h3>
            <ul>
              <li>File upload button in chat input area — click to select local file, displayed as upload preview card above the input area; file content sent as context with the message</li>
              <li>Selected code preview card — shows file, line range, and first 3 lines of selected code, supports one-click close</li>
              <li>CodeBlockCard extracted as reusable UI component with copy and insert buttons</li>
              <li>Clear button moved to session bar for easier session management</li>
            </ul>
            <h3>v1.0.3</h3>
            <ul>
              <li>Fixed AI reply consuming double tokens issue</li>
              <li>Fixed right-click context menu icon size issue</li>
              <li>Fixed Agent result dialog size issue</li>
              <li>Improved code completion: single-suggestion mode, optimized FIM prompt</li>
            </ul>
            <h3>v1.0.0</h3>
            <ul>
              <li>AI-powered inline code completion</li>
              <li>Streaming chat Q&A panel</li>
              <li>Agent mode: explain code, generate code, review code, optimize code</li>
              <li>Right-click context menu integration</li>
              <li>API Key configuration page</li>
            </ul>
        """.trimIndent()
    }
}
