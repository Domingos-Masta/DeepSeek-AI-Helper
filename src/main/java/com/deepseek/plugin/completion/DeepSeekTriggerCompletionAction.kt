package com.deepseek.plugin.completion

import com.deepseek.plugin.api.DeepSeekApiClient
import com.deepseek.plugin.api.LlmProviderRegistry
import com.deepseek.plugin.api.TriggerMode
import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware

/**
 * Manually triggers AI completion (Alt+P / context menu).
 *
 * Unlike automatic triggering (CompletionContributor):
 * - Skips the cache and always calls the API
 * - Uses a higher temperature (default 0.2) and more max_tokens (default 512)
 * - Produces longer, more varied completion results
 * - Displays the result as Ghost Text, accepted with Tab
 *
 * Inspired by deepseek-copilot's DeepSeekTriggerCompletionAction.
 */
class DeepSeekTriggerCompletionAction : AnAction(), DumbAware {

    companion object {
        private val LOG = Logger.getInstance(DeepSeekTriggerCompletionAction::class.java)
        private const val ACTION_ID = "DeepSeekAI.TriggerCompletion"
    }

    private val client = DeepSeekApiClient()

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val settings = DeepSeekSettings.instance

        if (!settings.completionEnabled || settings.apiKey.isBlank()) {
            return
        }

        // Position pre-processing
        val document = editor.document
        val caretOffset = editor.caretModel.offset
        val cursorLine = document.getLineNumber(caretOffset)

        // Collect the prefix (up to 30 lines before the caret)
        val startLine = maxOf(0, cursorLine - settings.maxContextLines)
        val startOffset = document.getLineStartOffset(startLine)
        val prefix = document.getText(com.intellij.openapi.util.TextRange(startOffset, caretOffset))

        // Collect the suffix (up to 20 lines after the caret)
        val endLine = minOf(document.lineCount - 1, cursorLine + 20)
        val endOffset = if (endLine > cursorLine) document.getLineEndOffset(endLine) else caretOffset
        val suffix = document.getText(com.intellij.openapi.util.TextRange(caretOffset, endOffset))

        // Determine the language
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val language = when {
            !file.fileType.isBinary -> file.fileType.name
            else -> inferLanguage(file.name)
        }

        // Collect file context (package declaration + imports)
        val fileContext = collectFileContext(document, cursorLine, 30)

        // Update status
        val statusService = CompletionStatusService.instance
        statusService.onGenerating()

        // Dismiss any existing Ghost Text
        GhostTextManager.dismissGhostText(editor)

        LOG.info("Manual trigger | lang=$language | prefixLen=${prefix.length} | suffixLen=${suffix.length}")

        // Providers without FIM support (e.g. Anthropic): notify and exit
        if (!LlmProviderRegistry.get(settings.provider).supportsFim) {
            statusService.onIdle()
            com.intellij.openapi.ui.Messages.showInfoMessage(
                e.project ?: return,
                I18n.tr("completion.fim.unsupported.message"),
                I18n.tr("completion.fim.unsupported.title")
            )
            return
        }

        // Call the FIM API asynchronously (MANUAL mode)
        client.completionFim(
            prefix = prefix,
            suffix = suffix,
            language = language,
            fileContext = fileContext,
            mode = TriggerMode.MANUAL
        ) { suggestionRaw ->
            if (suggestionRaw.isNullOrBlank()) {
                statusService.onIdle()
                return@completionFim
            }

            val lastLine = prefix.lines().lastOrNull() ?: ""
            val suggestion = CompletionPostProcessor.process(suggestionRaw, lastLine, suffix)

            if (suggestion.isBlank()) {
                statusService.onIdle()
                return@completionFim
            }

            // Return to the EDT to show the Ghost Text
            ApplicationManager.getApplication().invokeLater {
                if (!editor.isDisposed) {
                    GhostTextManager.showGhostText(editor, suggestion, caretOffset)
                    LOG.info("Manual completion shown | length=${suggestion.length}")
                    statusService.onReady()
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val settings = DeepSeekSettings.instance
        e.presentation.isEnabledAndVisible =
            editor != null &&
            e.project != null &&
            settings.completionEnabled &&
            settings.apiKey.isNotBlank()
    }

    private fun inferLanguage(fileName: String): String {
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

    private fun collectFileContext(document: com.intellij.openapi.editor.Document, cursorLine: Int, maxLines: Int): String {
        val sb = StringBuilder()
        val headerEnd = minOf(20, cursorLine)
        for (i in 0 until headerEnd) {
            val lineStart = document.getLineStartOffset(i)
            val lineEnd = document.getLineEndOffset(i)
            val line = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))
            if (line.isNotBlank()) sb.appendLine(line)
        }
        if (cursorLine > 20) sb.appendLine("// ...")
        return sb.toString()
    }
}
