package com.deepseek.plugin.completion

import com.deepseek.plugin.api.DeepSeekApiClient
import com.deepseek.plugin.api.LlmProviderRegistry
import com.deepseek.plugin.api.TriggerMode
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

/**
 * Generic code completion entry point (all languages).
 *
 * Registers [DeepSeekCompletionProvider] — AI-driven FIM completion.
 *
 * Java-specific static analysis pre-filtering is in [JavaCompletionContributor],
 * which only takes effect in IDEs that include the Java plugin, such as IntelliJ IDEA.
 */
class DeepSeekCompletionContributor : CompletionContributor() {

    companion object {
        private val LOG = Logger.getInstance(DeepSeekCompletionContributor::class.java)
    }

    init {
        // AI Provider — FIM completion common to all languages
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            DeepSeekCompletionProvider()
        )
    }
}

class DeepSeekCompletionProvider : CompletionProvider<CompletionParameters>() {

    companion object {
        private val LOG = Logger.getInstance(DeepSeekCompletionProvider::class.java)
    }

    private val client = DeepSeekApiClient()
    private val pipeline: CompletionPipeline = DefaultCompletionPipeline()

    // Prevent duplicate requests within a short period
    @Volatile private var lastRequestTime: Long = 0

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        // --- 0. PreChecker pre-check chain ---
        if (!pipeline.canProceed(parameters)) return

        val settings = DeepSeekSettings.instance
        if (parameters.isExtendedCompletion) return

        // Collect the prefix text (before the caret)
        val prefix = getPrefixText(parameters)
        val prefixTrimmed = prefix.trimEnd()

        // Minimum character count check
        val lastLine = prefixTrimmed.lines().lastOrNull() ?: ""
        if (lastLine.length < settings.completionMinPrefix) return

        // --- 2.5 Python pre-check layer (PyCharm-specific) ---
        // Recognize built-in functions/keywords/magic methods → skip the AI call
        if (!PythonCompletionPreChecker.shouldUseAI(
                element = parameters.position,
                file = parameters.originalFile,
                prefix = lastLine
            )
        ) return

        // --- 3. Debounce ---
        val now = System.currentTimeMillis()
        val elapsed = now - lastRequestTime
        if (elapsed < settings.completionDelayMs && elapsed > 0) return
        lastRequestTime = now

        // --- 3. Collect the suffix text (after the caret) ---
        val suffix = getSuffixText(parameters)

        // --- 4. Collect language and file context ---
        val language = getLanguage(parameters)
        val fileContext = collectFileContext(parameters, settings.maxContextLines)

        LOG.info("AI Completion triggered | lang=$language | prefixLen=${prefix.length} | suffixLen=${suffix.length}")

        val statusService = CompletionStatusService.instance
        val filePath = parameters.originalFile.virtualFile?.path ?: ""
        val editor = parameters.editor
        val caretOffset = parameters.offset
        val cursorLine = editor.document.getLineNumber(caretOffset)
        val cursorColumn = caretOffset - editor.document.getLineStartOffset(cursorLine)

        // --- 4.5 FIM capability check: providers without FIM support (e.g. Anthropic) are skipped directly to avoid calling the nonexistent /completions endpoint ---
        if (!LlmProviderRegistry.get(settings.provider).supportsFim) {
            statusService.onIdle()
            return
        }

        // --- 5. Cache lookup (skip duplicate API calls) ---
        if (settings.completionCacheEnabled) {
            val cached = pipeline.getCached(filePath, cursorLine, cursorColumn, lastLine)
            if (cached != null) {
                LOG.debug("Cache HIT: returning cached suggestion")
                statusService.onReady()
                ApplicationManager.getApplication().invokeLater {
                    try {
                        result.addElement(
                            PrioritizedLookupElement.withPriority(
                                LookupElementBuilder.create(cached)
                                    .withTypeText("DeepSeek-AI-Helper", true)
                                    .withIcon(com.intellij.icons.AllIcons.Actions.Find),
                                Double.MAX_VALUE
                            )
                        )

                        // Ghost Text mode
                        if (settings.completionGhostTextEnabled && !editor.isDisposed) {
                            GhostTextManager.showGhostText(editor, cached, caretOffset)
                        }
                    } catch (e: Exception) {
                        LOG.warn("Failed to add cached completion element", e)
                    }
                }
                return
            }
            LOG.debug("Cache MISS: will call API")
        }

        // --- 6. Call the FIM API asynchronously ---
        statusService.onGenerating()

        // Streaming/synchronous branch: prefer streaming display in Ghost Text mode
        if (settings.completionGhostTextEnabled && !editor.isDisposed) {
            // === Streaming FIM path: show the first token in Ghost Text immediately ===
            var accumulatedPrefix = ""
            client.completionFimStream(
                prefix = prefix,
                suffix = suffix,
                language = language,
                fileContext = fileContext,
                mode = TriggerMode.AUTO,
                onToken = { token ->
                    accumulatedPrefix += token
                    ApplicationManager.getApplication().invokeLater {
                        if (!editor.isDisposed) {
                            GhostTextManager.showGhostText(editor, accumulatedPrefix, caretOffset)
                        }
                    }
                },
                onComplete = { fullRaw ->
                    val suggestion = pipeline.postProcess(fullRaw, lastLine, suffix)
                    if (suggestion.isBlank()) {
                        statusService.onIdle()
                        return@completionFimStream
                    }
                    if (settings.completionCacheEnabled) {
                        pipeline.putCached(filePath, cursorLine, cursorColumn, lastLine, suggestion)
                    }
                    ApplicationManager.getApplication().invokeLater {
                        try {
                            result.addElement(
                                PrioritizedLookupElement.withPriority(
                                    LookupElementBuilder.create(suggestion)
                                        .withTypeText("DeepSeek-AI-Helper", true)
                                        .withIcon(com.intellij.icons.AllIcons.Actions.Find),
                                    Double.MAX_VALUE
                                )
                            )
                            LOG.info("AI Completion (stream) | length=${suggestion.length}")
                            statusService.onReady()
                        } catch (e: Exception) {
                            LOG.warn("Failed to add streamed AI completion element", e)
                            statusService.onError(e.message ?: "Stream completion error")
                        }
                    }
                },
                onError = { error ->
                    statusService.onError(error.message ?: "FIM stream error")
                    if (statusService.getConsecutiveErrors() >= 3) {
                        ApplicationManager.getApplication().invokeLater {
                            val project = parameters.editor.project ?: return@invokeLater
                            if (!project.isDisposed) {
                                val errMsg = statusService.getErrorMessage().ifBlank { "Please check the API key or network connection" }
                                ToolWindowManager.getInstance(project).notifyByBalloon(
                                    "DeepSeek-AI-Helper",
                                    MessageType.WARNING,
                                    "AI completion failed repeatedly (x" + statusService.getConsecutiveErrors() + "): " + errMsg
                                )
                                statusService.resetConsecutiveErrors()
                            }
                        }
                    }
                    statusService.onIdle()
                }
            )
        } else {
            // === Synchronous FIM path (lookup list mode) ===
            client.completionFim(
                prefix = prefix,
                suffix = suffix,
                language = language,
                fileContext = fileContext,
                mode = TriggerMode.AUTO
            ) { suggestionRaw ->
                if (suggestionRaw.isNullOrBlank()) {
                    if (statusService.getConsecutiveErrors() >= 3) {
                        ApplicationManager.getApplication().invokeLater {
                            val project = parameters.editor.project ?: return@invokeLater
                            if (!project.isDisposed) {
                                val errMsg = statusService.getErrorMessage().ifBlank { "Please check the API key or network connection" }
                                ToolWindowManager.getInstance(project).notifyByBalloon(
                                    "DeepSeek-AI-Helper",
                                    MessageType.WARNING,
                                    "AI completion failed repeatedly (x" + statusService.getConsecutiveErrors() + "): " + errMsg
                                )
                                statusService.resetConsecutiveErrors()
                            }
                        }
                    }
                    statusService.onIdle()
                    return@completionFim
                }

                val suggestion = pipeline.postProcess(suggestionRaw, lastLine, suffix)
                if (suggestion.isBlank()) {
                    statusService.onIdle()
                    return@completionFim
                }

                if (settings.completionCacheEnabled) {
                    pipeline.putCached(filePath, cursorLine, cursorColumn, lastLine, suggestion)
                }

                ApplicationManager.getApplication().invokeLater {
                    try {
                        result.addElement(
                            PrioritizedLookupElement.withPriority(
                                LookupElementBuilder.create(suggestion)
                                    .withTypeText("DeepSeek-AI-Helper", true)
                                    .withIcon(com.intellij.icons.AllIcons.Actions.Find),
                                Double.MAX_VALUE
                            )
                        )

                        if (settings.completionGhostTextEnabled && !editor.isDisposed) {
                            GhostTextManager.showGhostText(editor, suggestion, caretOffset)
                        }

                        LOG.info("AI Completion added | length=${suggestion.length} | preview=${suggestion.take(40).replace("\n", "\\n")}")
                        statusService.onReady()
                    } catch (e: Exception) {
                        LOG.warn("Failed to add AI completion element", e)
                        statusService.onError(e.message ?: "Failed to add completion")
                    }
                }
            }
        }
    }

    // ===================== Context collection =====================

    /**
     * Gets the code text before the caret (up to maxContextLines lines).
     */
    private fun getPrefixText(parameters: CompletionParameters): String {
        val document = parameters.editor.document
        val offset = parameters.offset
        val lineCount = document.getLineCount()
        val cursorLine = document.getLineNumber(offset)
        val settings = DeepSeekSettings.instance

        // Take N lines before the caret
        val startLine = maxOf(0, cursorLine - settings.maxContextLines)
        val startOffset = document.getLineStartOffset(startLine)
        return document.getText(com.intellij.openapi.util.TextRange(startOffset, offset))
    }

    /**
     * Gets the code text after the caret (up to 20 lines).
     */
    private fun getSuffixText(parameters: CompletionParameters): String {
        val document = parameters.editor.document
        val offset = parameters.offset
        val lineCount = document.getLineCount()
        val cursorLine = document.getLineNumber(offset)

        val endLine = minOf(lineCount - 1, cursorLine + 20)
        if (endLine <= cursorLine) return ""

        val endOffset = document.getLineEndOffset(endLine)
        return document.getText(com.intellij.openapi.util.TextRange(offset, endOffset))
    }

    /**
     * Gets the file language name.
     */
    private fun getLanguage(parameters: CompletionParameters): String {
        val fileType = parameters.originalFile.fileType
        return when {
            !fileType.isBinary && fileType !is PlainTextFileType -> fileType.name
            else -> inferLanguageFromFile(parameters.originalFile.name)
        }
    }

    private fun inferLanguageFromFile(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "java" -> "JAVA"
            "kt", "kts" -> "Kotlin"
            "py" -> "Python"
            "js" -> "JavaScript"
            "ts" -> "TypeScript"
            "go" -> "Go"
            "rs" -> "Rust"
            "cpp", "cc", "cxx" -> "C++"
            "c" -> "C"
            "cs" -> "C#"
            "rb" -> "Ruby"
            "php" -> "PHP"
            "swift" -> "Swift"
            "scala" -> "Scala"
            "xml" -> "XML"
            "json" -> "JSON"
            "yaml", "yml" -> "YAML"
            "sql" -> "SQL"
            "sh", "bash" -> "Shell"
            "" -> "text"
            else -> ext.uppercase()
        }
    }

    /**
     * Collects file-level context: package declaration, imports, and outer class/function signatures.
     */
    private fun collectFileContext(parameters: CompletionParameters, maxLines: Int): String {
        val document = parameters.editor.document
        val offset = parameters.offset
        val cursorLine = document.getLineNumber(offset)

        val sb = StringBuilder()

        // File header (package, imports) — at most the first 20 lines
        val headerEnd = minOf(20, cursorLine)
        for (i in 0 until headerEnd) {
            val lineStart = document.getLineStartOffset(i)
            val lineEnd = document.getLineEndOffset(i)
            val line = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))
            if (line.isNotBlank()) {
                sb.appendLine(line)
            }
        }

        // If the caret is close to the file header, do not duplicate
        if (cursorLine > 20) {
            sb.appendLine("// ...")
        }

        return sb.toString()
    }
}
