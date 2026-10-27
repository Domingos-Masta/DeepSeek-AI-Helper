package com.deepseek.plugin.context

/**
 * Splits a code file into chunks of [maxLines] lines, keeping [overlap] lines of overlap before and after
 * so methods/logic are not cut abruptly at boundaries, which would hurt retrieval and readability
 */
class ChunkSplitter(
    private val maxLines: Int = 60,
    private val overlap: Int = 5
) {

    fun split(filePath: String, content: String): List<CodeChunk> {
        val lines = content.lines()
        if (lines.isEmpty()) return emptyList()

        val chunks = mutableListOf<CodeChunk>()
        var start = 0
        var chunkIndex = 0

        while (start < lines.size) {
            val actualEnd = minOf(start + maxLines, lines.size)

            // Actual chunk range
            val rangeStart = start
            val rangeEnd = actualEnd

            // Store with preceding/following overlap context
            val storeStart = maxOf(0, start - overlap)
            val storeEnd = minOf(actualEnd + overlap, lines.size)
            val chunkLines = lines.subList(storeStart, storeEnd)

            chunks.add(
                CodeChunk(
                    id = "$filePath#chunk$chunkIndex",
                    filePath = filePath,
                    startLine = rangeStart,
                    endLine = rangeEnd - 1,
                    content = chunkLines.joinToString("\n")
                )
            )

            start = actualEnd
            chunkIndex++
        }

        return chunks
    }
}

/**
 * A chunk in a code file
 * @param id          Unique identifier, format: "relativePath#chunkIndex"
 * @param filePath    Path relative to the source root, e.g. "com/example/UserService.java"
 * @param startLine   Starting line number of the chunk in the file (0-based)
 * @param endLine     Ending line number of the chunk in the file (0-based)
 * @param content     Chunk code content (includes preceding/following overlap lines for easier context)
 */
data class CodeChunk(
    val id: String,
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val content: String
)
