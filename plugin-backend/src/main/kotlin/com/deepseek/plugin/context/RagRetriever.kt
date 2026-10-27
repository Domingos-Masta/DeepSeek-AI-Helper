package com.deepseek.plugin.context

import com.intellij.openapi.project.Project
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.ScoreDoc
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.TopDocs

/**
 * BM25 retrieval entry point (for text/document only).
 * Takes a user's natural-language question, recalls the most relevant document text chunks from RagIndexer's Lucene index,
 * then groups them by file, deduplicates, and formats them into context text.
 *
 * Note: code file search has been superseded by AgenticSearch; use com.deepseek.plugin.search.AgenticSearch.
 */
class RagRetriever(private val project: Project) {

    private val indexer = RagIndexer(project)
    private val retrieverLock = Any()

    // Maximum characters per chunk (prevents oversized chunks from overflowing the context)
    private val maxChunkChars = 2000

    /**
     * Retrieval entry point.
     * @param queryText The user's original question
     * @param topN      Maximum number of chunks to return (default 8)
     * @return          Formatted context string; empty string when nothing is retrieved
     */
    fun retrieve(queryText: String, topN: Int = 8): String {
        // 1. Ensure the index is up to date
        indexer.ensureFresh()

        // 2. BM25 retrieval
        val results = search(queryText, topN * 2)
        if (results.isEmpty()) return ""

        // 3. Post-processing: group by file + sort by line number + truncate
        val processed = postProcess(results, topN)

        // 4. Format into context text
        return formatContext(processed)
    }

    // ════════════════════════════════════════════════════════════════
    //  Lucene retrieval (manually builds a boolean query, no QueryParser dependency)
    // ════════════════════════════════════════════════════════════════

    private fun search(queryText: String, topN: Int): List<ScoredChunk> {
        synchronized(retrieverLock) {
            val reader = DirectoryReader.open(indexer.getDirectory())
            val searcher = IndexSearcher(reader)

            try {
                // Tokenize the user question with the Analyzer; turn each token into a TermQuery
                val terms = tokenize(queryText)
                if (terms.isEmpty()) return emptyList()

                val booleanQuery = BooleanQuery.Builder()
                for (term in terms) {
                    booleanQuery.add(
                        TermQuery(Term("content", term)),
                        BooleanClause.Occur.SHOULD
                    )
                }

                val topDocs: TopDocs = searcher.search(booleanQuery.build(), topN)

                return topDocs.scoreDocs.map { sd: ScoreDoc ->
                    val doc = searcher.storedFields().document(sd.doc)
                    ScoredChunk(
                        chunk = CodeChunk(
                            id = doc.get("id"),
                            filePath = doc.get("filePath"),
                            startLine = doc.get("startLineStr")?.toIntOrNull() ?: 0,
                            endLine = doc.get("endLineStr")?.toIntOrNull() ?: 0,
                            content = doc.get("content") ?: ""
                        ),
                        score = sd.score
                    )
                }
            } finally {
                reader.close()
            }
        }
    }

    /**
     * Splits text into tokens using StandardAnalyzer.
     * Filters out stop words, words that are too short (<2 chars), and purely numeric words.
     */
    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val analyzer = indexer.getAnalyzer()
        val tokenStream = analyzer.tokenStream("content", text)
        tokenStream.reset()

        val charTermAttr = tokenStream.addAttribute(CharTermAttribute::class.java)
        while (tokenStream.incrementToken()) {
            val term = charTermAttr.toString()
            if (term.length >= 2 && !term.all { it.isDigit() }) {
                tokens.add(term)
            }
        }
        tokenStream.end()
        tokenStream.close()
        return tokens
    }

    // ════════════════════════════════════════════════════════════════
    //  Post-processing — dedupe + group by file + sort by line number + token budget control
    // ════════════════════════════════════════════════════════════════

    private data class ScoredChunk(
        val chunk: CodeChunk,
        val score: Float
    )

    private fun postProcess(results: List<ScoredChunk>, maxChunks: Int): List<CodeChunk> {
        // Group by file, and within each group sort by line number
        val grouped = results.groupBy { it.chunk.filePath }
            .mapValues { (_, chunks) ->
                chunks.sortedBy { it.chunk.startLine }
                    .distinctBy { "${it.chunk.startLine}-${it.chunk.endLine}" }
            }

        // Sort by each file's highest score and take the top maxChunks chunks
        val fileBestScore = grouped.mapValues { (_, chunks) ->
            chunks.maxOfOrNull { it.score } ?: 0f
        }

        val sortedFiles = fileBestScore.entries
            .sortedByDescending { it.value }

        val finalChunks = mutableListOf<CodeChunk>()
        val seenFiles = mutableSetOf<String>()

        for ((filePath, _) in sortedFiles) {
            if (finalChunks.size >= maxChunks) break
            val fileChunks = grouped[filePath] ?: continue
            for (sc in fileChunks) {
                if (finalChunks.size >= maxChunks) break
                finalChunks.add(sc.chunk)
            }
            seenFiles.add(filePath)
        }

        return finalChunks
    }

    // ════════════════════════════════════════════════════════════════
    //  Format into a context string
    // ════════════════════════════════════════════════════════════════

    private fun formatContext(chunks: List<CodeChunk>): String {
        if (chunks.isEmpty()) return ""

        val sb = StringBuilder()
        // Group by file before output, to avoid repeating the file header for the same file
        val byFile = chunks.groupBy { it.filePath }

        sb.appendLine("Below is the project code context related to the current question:\n")

        for ((filePath, fileChunks) in byFile) {
            val sorted = fileChunks.sortedBy { it.startLine }
            sb.appendLine("📄 `$filePath`:")

            for (chunk in sorted) {
                val lineRange = "L${chunk.startLine + 1}-L${chunk.endLine + 1}" // convert to 1-based
                sb.appendLine("   --- $lineRange ---")

                val content = chunk.content
                if (content.length > maxChunkChars) {
                    sb.appendLine(content.take(maxChunkChars))
                    sb.appendLine("   // ... (${content.length - maxChunkChars} characters remaining)")
                } else {
                    val lang = detectLanguage(filePath)
                    if (lang != null) sb.appendLine("```$lang") else sb.appendLine("```")
                    sb.appendLine(content)
                    sb.appendLine("```")
                }
                sb.appendLine()
            }
        }

        return sb.toString()
    }

    private fun detectLanguage(filePath: String): String? {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "java" -> "java"
            "kt", "kts" -> "kotlin"
            "xml" -> "xml"
            "yaml", "yml" -> "yaml"
            "properties" -> "properties"
            "sql" -> "sql"
            "py" -> "python"
            "js" -> "javascript"
            "ts" -> "typescript"
            "vue" -> "vue"
            "css" -> "css"
            "html" -> "html"
            "md" -> "markdown"
            "gradle" -> "groovy"
            else -> null
        }
    }
}
