package com.deepseek.plugin.context

import com.deepseek.plugin.search.ToolUseEngine
import com.intellij.openapi.project.Project

/**
 * Unified search-routing coordinator.
 *
 * Automatically routes to the appropriate retrieval engine based on the type of user question:
 * - Code query (contains class/function names or code keywords) → ToolUseEngine (Agentic Search: multi-round grep/glob/read)
 * - Document query (natural language/config/docs) → RagRetriever (Lucene BM25 full-text search)
 * - Supplement: ProjectContextProvider (class-name suffix association mapping)
 *
 * Usage:
 *   val coordinator = SearchCoordinator(project)
 *   val result = coordinator.search("a user question about getUserById")
 *   // result.contextText is the formatted context text that can be injected directly into the system prompt
 */
class SearchCoordinator(
    private val project: Project,
    private val externalToolDefinitions: String = "",
    private val externalToolExecutor: ((String, Map<String, String>) -> String?)? = null
) {

    private val toolUseEngine = ToolUseEngine(project, externalToolDefinitions = externalToolDefinitions, externalToolExecutor = externalToolExecutor)
    private val ragRetriever = RagRetriever(project)
    private val contextProvider = ProjectContextProvider(project)

    /**
     * Search result wrapper.
     * @param contextText Formatted context text that can be injected directly into the LLM system prompt
     */
    data class SearchResult(val contextText: String)

    /**
     * Executes the search: automatically determines the query type and routes accordingly.
     *
     * @param userMessage The user's original question
     * @return SearchResult containing the formatted context; contextText is an empty string when there are no results
     */
    fun search(userMessage: String, useReadOnlyToolLoop: Boolean = false): SearchResult {
        val settings = com.deepseek.plugin.settings.DeepSeekSettings.instance
        val isCode = isCodeQuery(userMessage)

        val codeCtx: String
        val docCtx: String

        if (isCode && settings.agenticSearchEnabled) {
            // Code query → Agentic Search (single-round grep search)
            val result = toolUseEngine.execute(
                userMessage = userMessage,
                singleRound = !useReadOnlyToolLoop
            )
            codeCtx = result.searchContext
            docCtx = ""
        } else {
            codeCtx = ""
            // Non-code query or code search disabled → RAG document retrieval
            docCtx = ragRetriever.retrieve(userMessage)
        }

        // Supplement: class-name association context (applies to all queries)
        val projectCtx = contextProvider.getRelatedContext(userMessage)

        val combined = buildString {
            if (codeCtx.isNotBlank()) {
                appendLine("### Agentic Search results")
                appendLine(codeCtx)
                appendLine()
            }
            if (docCtx.isNotBlank()) {
                appendLine("### Document search results")
                appendLine(docCtx)
                appendLine()
            }
            if (projectCtx.isNotBlank()) {
                appendLine("### Related class files")
                appendLine(projectCtx)
                appendLine()
            }
        }

        return SearchResult(contextText = combined.trimEnd())
    }

    // ════════════════════════════════════════════════════════════════
    //  Query type detection
    // ════════════════════════════════════════════════════════════════

    /**
     * Determines whether the user's query is code-related.
     * Code queries use Agentic Search; non-code queries use RAG.
     */
    private fun isCodeQuery(query: String): Boolean {
        // Contains CamelCase class/function names
        val camelCasePattern = Regex("""\b[A-Z][a-zA-Z0-9]{2,}\b""")
        if (camelCasePattern.containsMatchIn(query)) return true

        // Contains code keywords
        val codeKeywords = listOf(
            "class", "function", "method", "interface", "enum", "annotation",
            "import", "package", "extends", "implements", "override",
            "controller", "service", "mapper", "repository", "entity",
            "dto", "vo", "po", "bo", "config", "handler", "util",
            "getter", "setter", "constructor", "bean", "component",
            "api", "rest", "endpoint", "route", "mapping",
            "database", "table", "field", "interface", "implementation", "inheritance",
            "get", "set", "find", "search", "query", "update", "save", "delete", "create",
            // Added: framework/technology keywords
            "spring", "mybatis", "jpa", "hibernate", "controller", "service",
            "Autowired", "RequestMapping", "GetMapping", "PostMapping"
        )
        val queryLower = query.lowercase()
        for (kw in codeKeywords) {
            if (queryLower.contains(kw)) return true
        }

        // Contains possible code paths joined with ., #, ::
        val codePathPattern = Regex("""[\w.]+\.\w+""")
        if (codePathPattern.containsMatchIn(query)) return true

        return false
    }
}
