package com.deepseek.plugin.api

/**
 * A single match in grep search results.
 */
data class GrepMatch(
    val filePath: String,
    val lineNumber: Int,
    val lineText: String,
    val matchStart: Int,
    val matchEnd: Int
)

/**
 * The overall result of a grep search.
 */
data class GrepResult(
    val query: String,
    val matches: List<GrepMatch>,
    val totalMatches: Int,
    val truncated: Boolean
)

/**
 * glob result.
 */
data class GlobResult(
    val pattern: String,
    val files: List<String>
)

/**
 * The result of reading file contents.
 */
data class ReadResult(
    val filePath: String,
    val content: String,
    val startLine: Int,
    val endLine: Int,
    val totalLines: Int
)

/**
 * Unified return type for tool calls.
 */
sealed class ToolResult {
    data class Grep(val result: GrepResult) : ToolResult()
    data class Glob(val result: GlobResult) : ToolResult()
    data class Read(val result: ReadResult) : ToolResult()
    data class Error(val message: String) : ToolResult()
    data class External(val text: String) : ToolResult()
}

/**
 * A tool call parsed from the model response.
 */
data class ToolCall(
    val name: String,        // "grep", "glob", "read"
    val params: Map<String, String>,
    val rawXml: String       // raw XML fragment, used to remove from the response
)

/**
 * Result feedback for a tool call.
 */
data class ToolResultMessage(
    val toolName: String,
    val params: Map<String, String>,
    val result: ToolResult
)
