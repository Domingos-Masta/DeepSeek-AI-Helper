package com.deepseek.plugin.completion

import com.intellij.openapi.diagnostic.Logger

/**
 * Post-processor for completion results.
 *
 * Inspired by deepseek-copilot's CompletionPostProcessor,
 * implements line-level + character-level overlap removal + bracket balancing + cursor marker cleanup,
 * ensuring the completion result does not duplicate code that already exists after the caret and is syntactically reasonable.
 */
object CompletionPostProcessor {

    private val LOG = Logger.getInstance(CompletionPostProcessor::class.java)

    /** Bracket pair mapping: closing bracket → opening bracket */
    private val CLOSE_TO_OPEN = mapOf(
        ')' to '(', '}' to '{', ']' to '[',
        '"' to '"', '\'' to '\''
    )

    /** Set of all opening brackets */
    private val OPEN_CHARS = CLOSE_TO_OPEN.values.toSet()

    /** Set of all closing brackets */
    private val CLOSE_CHARS = CLOSE_TO_OPEN.keys.toSet()

    /** Characters that skip pairing validation (quotes) */
    private val SYMMETRIC_CHARS = setOf('"', '\'')

    /**
     * Full post-processing pipeline.
     *
     * 1. Normalize line endings CRLF/CR → LF
     * 2. Remove Markdown code block markers
     * 3. Remove natural-language preambles ("Here is the code...")
     * 4. Remove the overlapping portion with the text after the caret (suffix) (line-level + character-level)
     * 5. Remove the last word if the model repeated it before the caret
     * 6. Fix bracket/quote balance (remove extra closing brackets / complete missing closing brackets)
     * 7. Remove cursor markers (<|cursor|> / $0 / [CURSOR])
     * 8. Trim leading/trailing whitespace
     *
     * @param raw the raw text returned by the model
     * @param prefixLastLine the text of the caret's line (the part before the caret)
     * @param suffix the text after the caret (used for overlap removal)
     * @return the cleaned completion text
     */
    fun process(raw: String, prefixLastLine: String, suffix: String): String {
        var cleaned = raw

        // 0) Normalize line endings
        cleaned = cleaned.replace("\r\n", "\n").replace('\r', '\n')

        // 1) Remove Markdown code block markers
        cleaned = cleaned.replace(Regex("```[\\s\\S]*?```"), "").trim()
        cleaned = cleaned.replace(Regex("^```\\w*\\s*", RegexOption.MULTILINE), "")
        cleaned = cleaned.replace(Regex("```\\s*$", RegexOption.MULTILINE), "")

        // 2) Remove natural-language preambles such as "Completion:" and "Here is the completion:"
        cleaned = cleaned.replace(Regex("^(?i)\\s*(completion|here\\s+is|suggestion|result)\\s*[:：]\\s*"), "")

        // 3) Line-level + character-level overlap removal (core improvement)
        cleaned = findMaxOverlapSuffixPrefix(cleaned, suffix)

        // 4) Remove the last word if the model repeated it before the caret
        val lastWord = prefixLastLine.split(Regex("[\\s(){}\\[\\]=;,.]+")).lastOrNull()?.trim() ?: ""
        if (lastWord.isNotBlank() && lastWord.length >= 2 && cleaned.startsWith(lastWord)) {
            cleaned = cleaned.removePrefix(lastWord)
        }

        // 5) Bracket/quote balance fix (new)
        cleaned = normalizeCharPairs(cleaned)

        // 6) Remove cursor markers
        cleaned = replaceCursorLocation(cleaned)

        // 7) Remove leading blank lines
        cleaned = cleaned.trimStart()

        return cleaned
    }

    // ===================== Bracket/quote balancing =====================

    /**
     * Bracket/quote balance repair.
     *
     * Uses a stack to scan the bracket/quote pairs in the LLM output:
     * - Removes extra closing brackets (models often output an extra } or ))
     * - Completes missing closing brackets (when the model output is truncated)
     *
     * Supported symbols: () {} [] "" ''
     */
    private fun normalizeCharPairs(text: String): String {
        if (text.isBlank()) return text

        val stack = ArrayDeque<BracketInfo>()
        val result = StringBuilder()

        for (ch in text) {
            when {
                // Opening bracket: push onto the stack
                ch in OPEN_CHARS -> {
                    stack.addLast(BracketInfo(ch, result.length))
                    result.append(ch)
                }
                // Closing bracket
                ch in CLOSE_CHARS -> {
                    val expectedOpen = CLOSE_TO_OPEN[ch]
                    if (stack.isEmpty()) {
                        // Extra closing bracket — skip (do not append)
                        continue
                    }
                    if (ch in SYMMETRIC_CHARS && stack.last().char == ch) {
                        // Symmetric quotes paired successfully
                        stack.removeLast()
                        result.append(ch)
                    } else if (stack.last().char == expectedOpen) {
                        // Normal pairing
                        stack.removeLast()
                        result.append(ch)
                    } else {
                        // Mismatched brackets — skip this extra closing bracket
                        // e.g. text = "func(a }" → skip the }
                        continue
                    }
                }
                else -> result.append(ch)
            }
        }

        // Complete unclosed opening brackets (only appended at the end of the text)
        // Complete from the bottom of the stack to the top (the earliest opened bracket closes first)
        if (stack.isNotEmpty()) {
            // Check every unclosed bracket in the stack and complete them only at the end
            for (info in stack) {
                val closeChar = findCloseChar(info.char)
                if (closeChar != null) {
                    result.append(closeChar)
                }
            }
        }

        return result.toString()
    }

    /** Bracket position information */
    private data class BracketInfo(
        val char: Char,
        val position: Int
    )

    /** Finds the closing bracket for an opening bracket */
    private fun findCloseChar(open: Char): Char? {
        return when (open) {
            '(' -> ')'
            '{' -> '}'
            '[' -> ']'
            '"' -> '"'
            '\'' -> '\''
            else -> null
        }
    }

    // ===================== Cursor marker cleanup =====================

    /**
     * Removes cursor position markers that LLMs commonly output.
     */
    private fun replaceCursorLocation(text: String): String {
        var result = text
        // Common marker formats
        result = result.replace(Regex("<\\|?cursor\\|?>"), "")
        result = result.replace("\\$0", "")
        result = result.replace(Regex("\\[CURSOR]"), "")
        result = result.replace(Regex("\\[cursor]"), "")
        return result.trim()
    }

    // ===================== Line-level + character-level overlap removal =====================

    /**
     * Line-level + character-level overlap removal.
     *
     * Algorithm:
     * 1. Split the completion text by lines
     * 2. Starting from the last line of the completion text, try to match it with the first line of the suffix
     * 3. If a line matches, continue matching more lines
     * 4. If line-level matching fails, fall back to character-level matching (matching from the start of the suffix)
     * 5. Cut off the overlapping portion of the completion text
     *
     * @param completion the completion text returned by the model
     * @param suffix the code text after the caret
     * @return the completion text with the overlap removed
     */
    private fun findMaxOverlapSuffixPrefix(completion: String, suffix: String): String {
        if (completion.isBlank() || suffix.isBlank()) return completion

        val completionLines = completion.split("\n")
        val suffixLines = suffix.split("\n")

        // ─── Phase 1: line-level overlap detection ───
        val maxOverlapLines = minOf(completionLines.size, suffixLines.size)
        var bestLineOverlap = 0

        for (overlap in 1..maxOverlapLines) {
            val compSegment = completionLines.takeLast(overlap).joinToString("\n").trimEnd()
            val suffixSegment = suffixLines.take(overlap).joinToString("\n").trimEnd()
            if (compSegment == suffixSegment) {
                bestLineOverlap = overlap
            }
        }

        if (bestLineOverlap > 0) {
            val trimmed = completionLines.dropLast(bestLineOverlap).joinToString("\n").trimEnd()
            if (trimmed.isNotBlank()) {
                return trimmed
            }
        }

        // ─── Phase 2: character-level overlap detection ───
        val compText = completion.trimEnd()
        val suffixText = suffix.trimStart()

        var bestCharOverlap = 0
        for (overlapLen in suffixText.length downTo 2) {
            val suffixStart = suffixText.take(overlapLen)
            if (compText.endsWith(suffixStart)) {
                bestCharOverlap = overlapLen
                break
            }
        }

        if (bestCharOverlap > 0) {
            val trimmed = compText.dropLast(bestCharOverlap).trimEnd()
            if (trimmed.isNotBlank()) {
                return trimmed
            }
        }

        return completion.trimEnd()
    }
}
