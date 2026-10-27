package com.deepseek.plugin.completion

import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile

/**
 * Completion pre-checker — performs quick veto checks before triggering an AI API call.
 *
 * Inspired by deepseek-copilot's CompletionPreChecker,
 * the check chain uses a "one veto rejects all" model:
 * if any check fails → skip this completion.
 *
 * Purpose: save token consumption, avoid useless API calls, and improve the user experience.
 */
object CompletionPreChecker {

    private val LOG = Logger.getInstance(CompletionPreChecker::class.java)

    /** Maximum file size (in bytes); files larger than this skip AI completion */
    private const val MAX_FILE_SIZE_BYTES = 100 * 1024 // 100KB

    /** File extensions that should skip AI completion (lowercase) */
    private val SKIP_EXTENSIONS = setOf(
        "md", "txt", "log", "csv", "tsv",
        "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "gradle", "properties", "env"
    )

    /** File names that should skip AI completion (exact match, lowercase) */
    private val SKIP_FILE_NAMES = setOf(
        "dockerfile", "makefile", "cmakelists.txt",
        "package.json", "package-lock.json", "yarn.lock",
        "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
        ".gitignore", ".dockerignore", ".editorconfig",
        "tsconfig.json", ".eslintrc", ".prettierrc"
    )

    /** Language types that should skip AI completion */
    private val SKIP_LANGUAGE_IDS = setOf(
        "json", "xml", "yaml", "text", "properties",
        "csv", "markdown", "log", "plaintext"
    )

    /**
     * Pre-check entry point.
     *
     * @param parameters completion parameters
     * @return true = may continue (checks passed), false = should skip
     */
    fun canProceed(parameters: CompletionParameters): Boolean {
        val settings = DeepSeekSettings.instance
        if (!settings.completionEnabled || settings.apiKey.isBlank()) return false

        // --- 1. Multi-caret check ---
        if (parameters.editor.caretModel.caretCount > 1) {
            LOG.debug("PreCheck SKIP: multi-caret (${parameters.editor.caretModel.caretCount})")
            return false
        }

        // --- 2. Read-only document check ---
        if (!parameters.editor.document.isWritable) {
            LOG.debug("PreCheck SKIP: read-only document")
            return false
        }

        // --- 3. File size check ---
        val file = parameters.originalFile.virtualFile
        if (file != null && file.length > MAX_FILE_SIZE_BYTES) {
            LOG.debug("PreCheck SKIP: file too large (${file.length} bytes)")
            return false
        }

        // --- 4. File type/extension check ---
        if (shouldSkipByFileType(file, parameters.originalFile.fileType)) {
            return false
        }

        // --- 5. Additional: skip extended completions ---
        if (parameters.isExtendedCompletion) return false

        return true
    }

    /**
     * Determines whether AI completion should be skipped based on file type and extension.
     */
    private fun shouldSkipByFileType(file: VirtualFile?, fileType: FileType): Boolean {
        val languageId = fileType.name.lowercase()
        if (languageId in SKIP_LANGUAGE_IDS) {
            LOG.debug("PreCheck SKIP: language=$languageId")
            return true
        }

        // Plain text files: check the extension
        if (languageId == "plaintext" || languageId == "text") {
            val fileName = file?.name?.lowercase() ?: return false
            val ext = fileName.substringAfterLast('.', "")

            // Check the extension blacklist
            if (ext in SKIP_EXTENSIONS) {
                LOG.debug("PreCheck SKIP: extension=.${ext}")
                return true
            }

            // Check the file name blacklist
            if (fileName in SKIP_FILE_NAMES) {
                LOG.debug("PreCheck SKIP: filename=$fileName")
                return true
            }
        }

        return false
    }
}
