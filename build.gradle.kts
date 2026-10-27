import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

allprojects {
    group = "com.deepseek.plugin"
    version = "2.8.0"
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Submodule dependencies
    implementation(project(":plugin-api"))
    implementation(project(":plugin-backend"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        create("IU", "2024.3")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        // Provides Java PSI classes at compile time; controlled at runtime via plugin.xml optional dependencies
        bundledPlugin("com.intellij.java")
    }
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    pluginConfiguration {
        name = "DeepSeek-AI-Helper"
        description = """
            <h2>DeepSeek-AI-Helper - JETBRAINS Plugin</h2>
            <ul>
              <li><b>Code Completion</b> - AI-powered inline suggestions + static analysis (annotation-aware + comment-aware)</li>
              <li><b>Chat Panel</b> - Sidebar chat window with streaming replies and file attachments</li>
              <li><b>Agent Actions</b> - Right-click to Ask DeepSeek / Explain Code / Generate / Review / Optimize</li>
            </ul>
            <p>Compatible with <b>IntelliJ IDEA</b>, <b>PyCharm</b>, <b>DataGrip</b> and other IntelliJ-based IDEs.
            Java-specific features (annotation-aware completion, static analysis) require the Java plugin.</p>
            <br>
            <p>Configure API Key in ⚙Settings</p>
        """.trimIndent()
        ideaVersion {
            sinceBuild = "241"
            untilBuild = "263.*"
        }
        changeNotes = """
            <h1>Chinese</h1>
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
              <li>[Enhanced] Native Claude/Codex Q&amp;A — Claude supports native image messages, Codex supports native images and reasoning effort, Full Scan exposes only local read-only search tools, and streaming now handles errors, cancellation, retries, and partial-answer retention more reliably</li>
              <li>[Fixed] Token usage could be partially clipped after an answer completed — User scrolling is now separated from layout changes, final bottom positioning is stabilized, streaming spacer rows are cleaned up, and usage persists across session redraws</li>
            </ul>
            <h3>v2.7.4</h3>
            <ul>
              <li>[New] Native Responses API protocol for Codex — Added <code>codex-responses</code> protocol; the Codex (OpenAI) provider now calls the OpenAI Responses API directly (<code>POST /v1/responses</code>), compatible with cc-switch's "vendor-native Responses direct" configuration; system messages are automatically mapped to the <code>instructions</code> field, streaming SSE listens to <code>response.output_text.delta</code> for per-token callbacks; supports gpt-5.2-codex, gpt-5.1-codex, and other Responses-API-only models</li>
              <li>[Fixed] Claude and Codex were unavailable in Q&A mode. Now Codex and Claude are available in both their CLI mode and Q&A mode.</li>
            </ul>
            <h3>v2.7.3</h3>
            <ul>
              <li>[New] Mode 4 "Claude/Codex" — Drives the local Claude Code / Codex CLI as a native agent (Q&amp;A, code editing, running commands, calling tools), reusing your local login; the mode dropdown label follows the Provider (Claude Code / Codex); permission policy (accept file edits / skip all permissions / plan only); snapshot diff before/after execution is recorded into Change Management for rollback</li>
            </ul>
            <h3>v2.7.2</h3>
            <ul>
              <li>[New] Claude (Anthropic) Provider — Added Anthropic/Claude support via the OpenAI-compatible endpoint; use Claude models in Chat, Agent, and right-click features</li>
              <li>[New] Auto-read local Claude config — API key resolution: Settings panel → ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN env vars (AUTH_TOKEN = Claude subscription OAuth login) → explicitly authorized read of ~/.claude/settings.json</li>
              <li>[New] Claude config group in Settings — Model dropdown (claude-sonnet-4-5 / claude-opus-4-5, etc.), Base URL field (ANTHROPIC_BASE_URL env var supported), real-time key source indicator</li>
              <li>[Security] OAuth credential protection — OAuth tokens from ~/.claude are sent only to Anthropic official endpoints, never to third-party Base URLs</li>
              <li>[Improved] Completion guard — Providers without FIM support (e.g. Anthropic) skip completion requests; Alt+P manual completion shows a clear message</li>
              <li>[New] Codex (OpenAI) Provider — Reuses your local Codex CLI login (~/.codex/auth.json OPENAI_API_KEY / CODEX_API_KEY), supports OPENAI_API_KEY / CODEX_API_KEY / OPENAI_BASE_URL env vars, model &amp; proxy Base URL auto-read from ~/.codex/config.toml (incl. cc-switch model_providers config)</li>
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
              <li>[New] Reasoning display — AI reasoning content (reasoning_content) shown in a collapsible panel with word count, supports light/dark themes</li>
              <li>[New] Font Size setting — Theme settings now include a "Font Size" dropdown (12px~16px) controlling chat content text size</li>
              <li>[New] Unified Language setting — "UI Language" and "AI Output Language" merged into a single "Language" setting</li>
              <li>[New] OpenRouter default model changed to inclusionai/ling-3.0-flash:free; model selector now an editable combo box</li>
              <li>[Improved] Code Completion — Ghost Text enabled by default, status bar widget restored, consecutive failure balloon notifications, Python pre-check layer reduces unnecessary API calls</li>
              <li>[Improved] Code Completion — Streaming FIM: SSE token-by-token response, first token displays immediately as Ghost Text, reducing perceived latency</li>
              <li>[Improved] Code Completion — New Ctrl+Shift+, shortcut to toggle completion on/off</li>
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
              <li>NVIDIA NIM + OpenRouter model support — Added NVIDIA API Provider (z-ai/glm-5.2, minimaxai/minimax-m3, stepfun-ai/step-3.7-flash) + OpenRouter API Provider, with a dropdown for model selection</li>
              <li>Agent Phase 1+2 Uses Current Provider — Planning and Coding phases no longer hardcode DeepSeek; they use the provider and model selected in Settings</li>
              <li>Per-Phase Agent Pipeline Configuration — New "Agent Pipeline" settings section lets you assign independent Provider + Model for each phase: Intent Confirmation, Planning, Coding, Review</li>
              <li>Settings Button + Popup Menu — Gear icon on the status bar left side replaces the mode dropdown; popup menu includes Q&A/Agent mode switch and quick access to Agent Pipeline Phase settings</li>
              <li>Fixed NVIDIA API Request Format — topP/frequencyPenalty/presencePenalty parameters now use snake_case for OpenAI protocol compatibility</li>
            </ul>
            <h3>v2.5.3</h3>
            <ul>
              <li>Image Parsing Settings Upgrade — Changed "StepFun Image Parsing" to a top-level "Image Parsing" section with a dropdown to select the parsing model: Agnes Image 2.1 Flash / StepFun (default: Agnes)</li>
              <li>Key Reuse — Agnes reuses already-configured API keys; StepFun retains its own dedicated key field</li>
              <li>Clipboard Image Paste — Input area now supports Ctrl+V to paste images from clipboard, automatically added as attachments</li>
              <li>No More Modal Dialogs — Images are displayed inline in the user message bubble; the AI streaming bubble shows "Parsing..." status; parsed results are automatically fed to the AI for a second-pass optimization — all without blocking popups</li>
            </ul>
            <h3>v2.5.2</h3>
            <ul>
              <li>Deep Domain Restriction — Injected a CS-only domain restriction prompt into every LLM interaction path within the plugin, locking the model as a pure computer science reasoning engine</li>
              <li>Absolute Jailbreak Defense — Topic whitelist filtering for non-CS subjects; boundary-crossing requests receive a standardized refusal response with no additional explanation</li>
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
              <li>Skills injected into system prompt — enabled skills are automatically appended to the Agent mode system prompt, constraining and guiding AI behavior</li>
              <li>Community Skill Library link — quick-access link in the settings panel to the community-maintained skill collection</li>
            </ul>
            <h3>v2.4.3</h3>
            <ul>
              <li>Agentic Search replaces RAG for code retrieval — the model autonomously calls grep/glob/read tools like a human programmer: search, read, judge, re-search. Deterministic exact matching far beyond BM25 semantic retrieval</li>
              <li>RAG scoped to text/document retrieval — indexes only .md/.txt/.rst etc. documentation files; code search uses AgenticSearch (grep/glob/read) for zero-latency, high-precision matching</li>
              <li>New Agentic Search settings — enable/disable, configure search rounds (1=fast single-round, &gt;1=deep multi-round)</li>
              <li>Auto query classification — system detects code vs doc queries for optimal search strategy</li>
            </ul>
            <h3>v2.4.2</h3>
            <ul>
              <li>RAG (BM25 + line-based chunking) — ChunkSplitter divides source code into 60-line chunks with 5-line overlap; RagIndexer builds an in-memory ByteBuffersDirectory Lucene index with StandardAnalyzer, supports full rebuild and incremental dirty-file updates; RagRetriever recalls top 8 chunks via BM25 and formats them as code context, replacing the previous class-name exact-matching approach with natural-language semantic retrieval</li>
              <li>Each message bubble draws its own rounded background — flat architecture, removed card/outer/wrapper/padded four-layer nesting, MessageBubble redrawn with GridBagLayout so resizing one bubble does not affect others</li>
              <li>Compact code block layout — font 13→12, padding 14x18→8x14, HTML line height set to 1.35 for more compact code display</li>
            </ul>
            <h3>v2.4.1</h3>
            <ul>
              <li>Streaming message bubble now auto-expands with content — Removed JBScrollPane fixed height, streamTextArea placed directly in BorderLayout, revalidate() triggers layout recalc on each token append, the bubble grows smoothly as AI generates text instead of jumping to full height on completion</li>
            </ul>
            <h3>v2.4.0</h3>
            <ul>
              <li>Cross-IDE support — Java dependency is now optional; chat panel, Agent actions, and AI completion work in PyCharm / DataGrip etc., Java-specific features (static analysis, annotation/comment-aware completion) gracefully degrade</li>
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
              <li>Fixed: JTextComponent.modelToView(int) -> modelToView2D(int)</li>
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
            <h1>English</h1>
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
              <li>[New] Reasoning display — AI reasoning content (reasoning_content) shown in collapsible panel with word count, supports light/dark themes</li>
              <li>[New] Font Size setting — Theme settings now include "Font Size" dropdown (12px~16px) controlling chat content text size</li>
              <li>[New] Unified Language setting — "UI Language" and "AI Output Language" merged into single "Language" setting</li>
              <li>[New] OpenRouter default model changed to inclusionai/ling-3.0-flash:free; model selector now an editable combo box</li>
              <li>[Improved] Code Completion — Ghost Text enabled by default, status bar widget restored, consecutive failure balloon notifications, Python pre-check layer reduces unnecessary API calls</li>
              <li>[Improved] Code Completion — Streaming FIM: SSE token-by-token response, first token displays immediately as Ghost Text, reducing perceived latency</li>
              <li>[Improved] Code Completion — New Ctrl+Shift+, shortcut to toggle completion on/off</li>
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
              <li>Deep Domain Restriction — Injected a CS-only domain restriction prompt into every LLM interaction path within the plugin, locking the model as a pure computer science reasoning engine</li>
              <li>Absolute Jailbreak Defense — Topic whitelist filtering for non-CS subjects; boundary-crossing requests receive a standardized refusal response with no additional explanation</li>
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
              <li>RAG (BM25 + line-based chunking) — ChunkSplitter divides source code into 60-line chunks with 5-line overlap; RagIndexer builds an in-memory ByteBuffersDirectory Lucene index with StandardAnalyzer, supports full rebuild and incremental dirty-file updates; RagRetriever recalls top 8 chunks via BM25 and formats them as code context, replacing the previous class-name exact-matching approach with natural-language semantic retrieval</li>
            </ul>
            <h3>v2.4.1</h3>
            <ul>
              <li>Streaming message bubble now auto-expands with content — Removed JBScrollPane fixed height, streamTextArea placed directly in BorderLayout, revalidate() triggers layout recalc on each token append, the bubble grows smoothly as AI generates text instead of jumping to full height on completion</li>
            </ul>
            <h3>v2.4.0</h3>
            <ul>
              <li>Cross-IDE support — Java dependency is now optional; chat panel, Agent actions, and AI completion work in PyCharm / DataGrip etc., Java-specific features (static analysis, annotation/comment-aware completion) gracefully degrade</li>
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

// Filter Android/security classes out of the bundled okhttp JAR.
// Implemented with the JDK zip API so the build has no external Python dependency.
val filterOkhttpLib by tasks.registering {
    dependsOn("prepareSandbox")
    // doLast captures script-scope references; not compatible with the configuration cache
    notCompatibleWithConfigurationCache("rewrites sandbox JARs in-place")
    doLast {
        val sandboxDir = layout.buildDirectory.dir("idea-sandbox").get()
        val excludePrefixes = listOf(
            "okhttp3/internal/platform/android/",
            "okhttp3/internal/platform/Android10Platform",
            "okhttp3/internal/platform/AndroidPlatform",
            "okhttp3/internal/platform/ConscryptPlatform",
            "okhttp3/internal/platform/BouncyCastlePlatform",
            "okhttp3/internal/platform/OpenJSSEPlatform",
        )
        sandboxDir.asFile.walkTopDown().filter {
            it.name == "okhttp-4.12.0.jar"
        }.forEach { jarFile ->
            val tmp = File(jarFile.absolutePath + ".tmp")
            ZipFile(jarFile).use { zin ->
                ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zout ->
                    val entries = zin.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (excludePrefixes.none { entry.name.startsWith(it) }) {
                            zout.putNextEntry(ZipEntry(entry.name))
                            zin.getInputStream(entry).use { it.copyTo(zout) }
                            zout.closeEntry()
                        }
                    }
                }
            }
            if (!jarFile.delete()) throw RuntimeException("cannot delete ${jarFile.absolutePath}")
            if (!tmp.renameTo(jarFile)) throw RuntimeException("cannot move tmp file into place")
            logger.lifecycle("Filtered: ${jarFile.absolutePath}")
        }
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    } 
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    // Third-party deps are placed in lib/ by IntelliJ Platform Gradle Plugin 2.x automatically.
    // Do NOT extract them into the main plugin JAR — that would cause the Plugin Verifier
    // to attribute their internal deprecated API usage to our plugin.
    // The filterOkhttpLib task below handles stripping Android/security classes from lib/ JARs.

    // Ensure okhttp filtering happens before building the plugin ZIP and running IDE
    named("buildPlugin") {
        dependsOn(filterOkhttpLib)
    }
    named("runIde") {
        dependsOn(filterOkhttpLib)
    }
}
