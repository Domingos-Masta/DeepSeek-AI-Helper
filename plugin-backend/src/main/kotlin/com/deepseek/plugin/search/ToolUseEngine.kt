package com.deepseek.plugin.search

import com.deepseek.plugin.api.*

import com.deepseek.plugin.api.ChatMessage
import com.deepseek.plugin.api.DOMAIN_RESTRICTION_PROMPT
import com.deepseek.plugin.api.DeepSeekApiClient
import com.intellij.openapi.project.Project

/**
 * Tool-call loop engine.
 *
 * Implements the core loop of Agentic Search + external MCP tools:
 * 1. Send the system prompt (with tool definitions, including external MCP tools) + user question → LLM
 * 2. Parse tool calls from the LLM response (XML format)
 * 3. Execute the tool calls (grep / glob / read / external MCP tools)
 * 4. Feed the tool results back to the LLM
 * 5. Repeat 2-4 until the LLM gives a final answer or the maximum rounds are reached
 *
 * External MCP tools are integrated via the [externalToolDefinitions] and [externalToolExecutor] parameters.
 * Tool naming format: {serverName}_{toolName}, e.g. "stripe_get_balance"
 */
class ToolUseEngine(
    private val project: Project,
    private val maxRounds: Int = 3,
    private val externalToolDefinitions: String = "",
    private val externalToolExecutor: ((String, Map<String, String>) -> String?)? = null
) {
    private val searchEngine = AgenticSearch(project)
    private val apiClient = DeepSeekApiClient()

    companion object {
        private val TOOL_CALL_REGEX = Regex(
            """<tool\s+name="([^"]+)"\s+params='(\{.*?\})'\s*/?>""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
    }

    /** Build dynamic tool definitions including external MCP tools. */
    private fun buildToolDefinitions(): String {
        return buildString {
            appendLine("${DOMAIN_RESTRICTION_PROMPT}")
            appendLine()
            appendLine("You are a code search assistant. You can use the following tools to search the project's codebase autonomously to answer the user's questions.")
            appendLine()
            appendLine("## Available tools")
            appendLine()
            appendLine("### grep(query: String, filePattern: String?) → search results")
            appendLine("Performs content search over the project code (similar to ripgrep/grep).")
            appendLine("- query: search keyword or regular expression (required)")
            appendLine("- filePattern: optional path filter pattern, e.g. \"*.java\", \"**/controller/*.kt\" (optional)")
            appendLine("Returns matching file paths, line numbers, and content snippets.")
            appendLine()
            appendLine("### glob(pattern: String) → file list")
            appendLine("Finds files by name pattern.")
            appendLine("- pattern: file name pattern, e.g. \"**/*Controller.java\", \"**/User*.kt\"")
            appendLine("Returns the list of matching file paths.")
            appendLine()
            appendLine("### read(path: String, startLine?: String, endLine?: String) → file content")
            appendLine("Reads a content snippet from a specified file.")
            appendLine("- path: file path relative to the project root (required)")
            appendLine("- startLine: optional start line number (1-based)")
            appendLine("- endLine: optional end line number (1-based)")
            appendLine("Returns the file content.")
            appendLine()

            if (externalToolDefinitions.isNotBlank()) {
                appendLine(externalToolDefinitions)
                appendLine()
            }

            appendLine("## Usage rules")
            appendLine()
            appendLine("1. When you need to search code, output a tool-call XML. The format must be exactly as follows (JSON keys must be double-quoted):")
            appendLine("   <tool name=\"grep\" params='{\"query\":\"getUserById\",\"filePattern\":\"*.java\"}'></tool>")
            appendLine("   <tool name=\"glob\" params='{\"pattern\":\"**/*Controller*\"}'></tool>")
            appendLine("   <tool name=\"read\" params='{\"path\":\"src/main/.../UserService.java\"}'></tool>")
            if (externalToolDefinitions.isNotBlank()) {
                appendLine("   External MCP tool example:")
                appendLine("   <tool name=\"stripe_get_balance\" params='{}'></tool>")
                appendLine("   <tool name=\"github_search_issues\" params='{\"query\":\"bug\"}'></tool>")
            }
            appendLine()
            appendLine("2. Call only one tool at a time, and wait for the result before deciding the next step.")
            appendLine()
            appendLine("3. Search strategy (think like a human programmer):")
            appendLine("   - First use grep to search for key function/class/variable names")
            appendLine("   or use glob to locate file names")
            appendLine("   - Then use read to read the specific file content")
            appendLine("   - Decide the next search direction based on what you read")
            appendLine("   - Trace imports and call relationships to understand code structure")
            appendLine()
            appendLine("4. Once you have collected enough information, give the final answer (do not output tool-call XML).")
            appendLine()
            appendLine("5. If the search result is empty, try different search terms or file patterns.")
            appendLine()
            appendLine("6. In the tool argument JSON, string values must be double-quoted; booleans/numbers are not quoted.")
        }.trimIndent()
    }

    /**
     * Executes a query using Agentic Search.
     */
    fun execute(
        userMessage: String,
        systemPrompt: String? = null,
        singleRound: Boolean = false
    ): ExecuteResult {
        if (singleRound) {
            return executeSingleRound(userMessage, systemPrompt)
        }
        return executeMultiRound(userMessage, systemPrompt)
    }

    private fun executeSingleRound(
        userMessage: String,
        systemPrompt: String?
    ): ExecuteResult {
        val keywords = extractKeywords(userMessage)
        val grepResults = mutableListOf<GrepResult>()

        for (kw in keywords.take(3)) {
            val result = searchEngine.grep(kw)
            if (result.matches.isNotEmpty()) {
                grepResults.add(result)
            }
        }

        val searchContext = buildSearchContext(grepResults)

        val finalPrompt = buildString {
            systemPrompt?.let { appendLine(it).appendLine() }
            appendLine("Below is the project code context found based on the user's question:")
            appendLine(searchContext)
            appendLine()
            appendLine("Based on the project code context above, answer the following user question:")
            appendLine(userMessage)
        }

        return ExecuteResult(
            answer = finalPrompt,
            searchContext = searchContext,
            toolCalls = emptyList(),
            rounds = 1
        )
    }

    private fun executeMultiRound(
        userMessage: String,
        systemPrompt: String?
    ): ExecuteResult {
        val messages = mutableListOf<ChatMessage>()
        val allToolCalls = mutableListOf<ToolCall>()
        val collectedToolContext = mutableListOf<String>()

        val fullSystemPrompt = buildString {
            systemPrompt?.let { appendLine(it).appendLine() }
            appendLine(buildToolDefinitions())
        }
        messages.add(ChatMessage("system", fullSystemPrompt))
        messages.add(ChatMessage("user", userMessage))

        var finalAnswer: String? = null

        for (round in 1..maxRounds) {
            val response = apiClient.chatSync(messages)
            if (response.isFailure) {
                return ExecuteResult(
                    answer = "Search error: ${response.exceptionOrNull()?.message}",
                    searchContext = "",
                    toolCalls = allToolCalls,
                    rounds = round
                )
            }

            val content = response.getOrThrow()
            val toolCall = parseToolCall(content)

            if (toolCall == null) {
                finalAnswer = content
                break
            }

            allToolCalls.add(toolCall)

            val toolResult = executeTool(toolCall)
            collectedToolContext += formatToolResult(toolCall, toolResult)

            val textPart = content.replace(TOOL_CALL_REGEX, "").trim()
            if (textPart.isNotEmpty()) {
                messages.add(ChatMessage("assistant", textPart))
            } else {
                messages.add(ChatMessage("assistant", "[Tool call: ${toolCall.name}]"))
            }

            messages.add(ChatMessage("user", formatToolResult(toolCall, toolResult)))
        }

        val answer = finalAnswer ?: "Maximum search rounds reached (${maxRounds}); answer based on the existing search results."

        return ExecuteResult(
            answer = answer,
            searchContext = collectedToolContext.joinToString("\n\n---\n\n"),
            toolCalls = allToolCalls,
            rounds = allToolCalls.size
        )
    }

    // ════════════════════════════════════════════════════════════════
    //  Tool call parsing
    // ════════════════════════════════════════════════════════════════

    private fun parseToolCall(response: String): ToolCall? {
        val match = TOOL_CALL_REGEX.find(response) ?: return null
        val name = match.groupValues[1]
        val paramsJson = match.groupValues[2]

        val params = try {
            val map = mutableMapOf<String, String>()
            val entriesRegex = Regex(""""(\w+)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            for (entry in entriesRegex.findAll(paramsJson)) {
                map[entry.groupValues[1]] = entry.groupValues[2]
            }
            val simpleRegex = Regex(""""(\w+)"\s*:\s*(\d+)""")
            for (entry in simpleRegex.findAll(paramsJson)) {
                map[entry.groupValues[1]] = entry.groupValues[2]
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }

        return ToolCall(
            name = name.lowercase(),
            params = params,
            rawXml = match.value
        )
    }

    // ════════════════════════════════════════════════════════════════
    //  Tool execution
    // ════════════════════════════════════════════════════════════════

    private fun executeTool(toolCall: ToolCall): ToolResult {
        return try {
            when (toolCall.name) {
                "grep" -> {
                    val query = toolCall.params["query"] ?: return ToolResult.Error("grep: missing 'query' param")
                    val filePattern = toolCall.params["filePattern"]
                    val result = searchEngine.grep(query, filePattern)
                    ToolResult.Grep(result)
                }
                "glob" -> {
                    val pattern = toolCall.params["pattern"] ?: return ToolResult.Error("glob: missing 'pattern' param")
                    val result = searchEngine.glob(pattern)
                    ToolResult.Glob(result)
                }
                "read" -> {
                    val path = toolCall.params["path"] ?: return ToolResult.Error("read: missing 'path' param")
                    val startLine = toolCall.params["startLine"]?.toIntOrNull()
                    val endLine = toolCall.params["endLine"]?.toIntOrNull()
                    val result = searchEngine.read(path, startLine, endLine)
                    ToolResult.Read(result)
                }
                else -> {
                    // Try external MCP tool
                    if (externalToolExecutor != null) {
                        val externalResult = externalToolExecutor(toolCall.name, toolCall.params)
                        if (externalResult != null) {
                            ToolResult.External(externalResult)
                        } else {
                            ToolResult.Error("Unknown tool: ${toolCall.name}; available tools: grep, glob, read")
                        }
                    } else {
                        ToolResult.Error("Unknown tool: ${toolCall.name}; available tools: grep, glob, read")
                    }
                }
            }
        } catch (e: Exception) {
            ToolResult.Error("Error executing ${toolCall.name}: ${e.message ?: "unknown error"}")
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Result formatting
    // ════════════════════════════════════════════════════════════════

    private fun formatToolResult(toolCall: ToolCall, result: ToolResult): String {
        val sb = StringBuilder()
        sb.appendLine("## Tool call result: ${toolCall.name}")
        sb.appendLine("Arguments: ${
            toolCall.params.entries.joinToString(", ") { "${it.key}=${it.value}" }
        }")
        sb.appendLine()

        when (result) {
            is ToolResult.Grep -> {
                val grep = result.result
                if (grep.matches.isEmpty()) {
                    sb.appendLine("No matching results.")
                } else {
                    sb.appendLine("${grep.totalMatches} matches total (showing first ${grep.matches.size}):")
                    sb.appendLine()
                    sb.appendLine("| File | Line | Content |")
                    sb.appendLine("|------|------|------|")
                    for (match in grep.matches.take(50)) {
                        val escapedLine = match.lineText
                            .replace("|", "\\|")
                            .take(120)
                        sb.appendLine("| `${match.filePath}` | ${match.lineNumber} | `${escapedLine}` |")
                    }
                    if (grep.truncated) {
                        sb.appendLine()
                        sb.appendLine("(too many results, truncated; refine the search term to narrow the scope)")
                    }
                }
            }
            is ToolResult.Glob -> {
                val glob = result.result
                if (glob.files.isEmpty()) {
                    sb.appendLine("No matching files.")
                } else {
                    sb.appendLine("Found ${glob.files.size} matching files:")
                    for (file in glob.files.take(50)) {
                        sb.appendLine("- `$file`")
                    }
                    if (glob.files.size > 50) {
                        sb.appendLine("(showing first 50 only)")
                    }
                }
            }
            is ToolResult.Read -> {
                val read = result.result
                if (read.content.isEmpty()) {
                    sb.appendLine("File does not exist or cannot be read: `${read.filePath}`")
                    sb.appendLine()
                    sb.appendLine("Use the glob tool to confirm the file path is correct.")
                } else {
                    sb.appendLine("File: `${read.filePath}` (${read.totalLines} lines total, showing L${read.startLine}-${read.endLine}):")
                    sb.appendLine()
                    sb.appendLine("```")
                    val lines = read.content.lines()
                    val lineNumWidth = read.endLine.toString().length
                    for ((i, line) in lines.withIndex()) {
                        val lineNum = read.startLine + i
                        sb.appendLine("${"$lineNum".padStart(lineNumWidth)}│$line")
                    }
                    sb.appendLine("```")
                    if (read.endLine < read.totalLines) {
                        sb.appendLine("(${read.totalLines - read.endLine} more lines not shown; use read with a larger range)")
                    }
                }
            }
            is ToolResult.Error -> {
                sb.appendLine("Error: ${result.message}")
            }
            is ToolResult.External -> {
                sb.appendLine(result.text)
            }
        }

        sb.appendLine()
        sb.appendLine("Based on the search results above, continue analyzing.")
        sb.appendLine("If you need more information, call tools again. If you have enough, give the final answer directly.")

        return sb.toString()
    }

    // ════════════════════════════════════════════════════════════════
    //  Single-round mode helpers
    // ════════════════════════════════════════════════════════════════

    private fun extractKeywords(message: String): List<String> {
        val camelCasePattern = Regex("""\b([A-Z][a-zA-Z0-9]{1,})\b""")
        val camelMatches = camelCasePattern.findAll(message).map { it.value }.toList()

        val snakePattern = Regex("""\b([a-z][a-z0-9_]{1,}(?:get|set|find|search|update|delete|save|create|add|remove)[a-z0-9_]*)\b""")
        val snakeMatches = snakePattern.findAll(message).map { it.value }.toList()

        val quotePattern = Regex("""[""']([^""']{2,})[""']""")
        val quoteMatches = quotePattern.findAll(message).map { it.groupValues[1] }.toList()

        val identPattern = Regex("""\b([a-z][a-zA-Z0-9]{3,})\b""")
        val identMatches = identPattern.findAll(message).map { it.value }.toList()

        return (camelMatches + snakeMatches + quoteMatches + identMatches)
            .distinct()
            .filter { it.length >= 2 }
            .take(10)
    }

    private fun buildSearchContext(grepResults: List<GrepResult>): String {
        if (grepResults.isEmpty()) return "(no relevant code context found)"

        val sb = StringBuilder()
        for (result in grepResults) {
            if (result.matches.isEmpty()) continue
            sb.appendLine("### Search: `${result.query}`")
            sb.appendLine()

            val byFile = result.matches.groupBy { it.filePath }
            for ((filePath, matches) in byFile) {
                sb.appendLine("📄 `$filePath`:")
                for (match in matches.take(10)) {
                    sb.appendLine("  L${match.lineNumber}: ${match.lineText.take(150)}")
                }
                if (matches.size > 10) {
                    sb.appendLine("  ... (${matches.size - 10} more matches)")
                }
                sb.appendLine()
            }
        }
        return sb.toString()
    }

    data class ExecuteResult(
        val answer: String,
        val searchContext: String,
        val toolCalls: List<ToolCall>,
        val rounds: Int
    )
}
