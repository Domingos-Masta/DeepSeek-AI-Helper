package com.deepseek.plugin.completion

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import java.util.concurrent.ConcurrentHashMap

/**
 * Ghost Text manager.
 *
 * Manages the lifecycle of Ghost Text inlays in the editor:
 * - Show/hide Ghost Text
 * - Accept Ghost Text (write its content into the document)
 * - Each editor maintains its own independent Ghost Text state
 *
 * Thread safety: uses a ConcurrentHashMap to store GhostTextSession keyed by editor.
 */
object GhostTextManager {

    private val GHOST_KEY = Key.create<GhostTextSession>("DeepSeekGhostText")
    private val sessions = ConcurrentHashMap<Editor, GhostTextSession>()

    /** Ghost Text session state */
    data class GhostTextSession(
        val text: String,
        val inlineInlay: Inlay<*>?,
        val blockInlay: Inlay<*>?,
        var active: Boolean = true
    )

    /**
     * Shows Ghost Text in the editor.
     *
     * @param editor the target editor
     * @param text the completion text to display
     * @param offset the insertion position (caret offset)
     */
    fun showGhostText(editor: Editor, text: String, offset: Int) {
        if (text.isBlank()) return

        // Dismiss any existing Ghost Text
        dismissGhostText(editor)

        try {
            val lines = text.lines()
            val renderer = GhostTextRenderer(text)

            // First line as an inline inlay
            val inlineInlay = editor.inlayModel.addInlineElement(offset, false, renderer)

            // Subsequent lines as a block inlay
            val blockInlay = if (lines.size > 1) {
                val logicalPos = editor.offsetToLogicalPosition(offset)
                val nextLinePos = LogicalPosition(logicalPos.line + 1, 0)
                val nextLineOffset = editor.logicalPositionToOffset(nextLinePos)
                editor.inlayModel.addBlockElement(nextLineOffset, false, false, 0, renderer)
            } else null

            val session = GhostTextSession(
                text = text,
                inlineInlay = inlineInlay,
                blockInlay = blockInlay,
                active = true
            )
            sessions[editor] = session
            editor.putUserData(GHOST_KEY, session)
        } catch (_: Exception) {
            // Silently ignore inlay creation failures
        }
    }

    /**
     * Hides and cleans up Ghost Text.
     */
    fun dismissGhostText(editor: Editor) {
        val session = sessions.remove(editor) ?: return
        editor.putUserData(GHOST_KEY, null)
        session.active = false

        try {
            session.inlineInlay?.let { Disposer.dispose(it) }
            session.blockInlay?.let { Disposer.dispose(it) }
        } catch (_: Exception) {
            // Silently ignore if the inlay has already been disposed
        }
    }

    /**
     * Accepts Ghost Text: writes the completion text into the editor document.
     *
     * @return whether the acceptance succeeded
     */
    fun acceptGhostText(editor: Editor): Boolean {
        val session = sessions[editor] ?: return false
        if (!session.active || session.text.isBlank()) return false

        val caretOffset = editor.caretModel.offset

        // Write into the document using WriteCommandAction
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(editor.project) {
            try {
                val document = editor.document
                document.insertString(caretOffset, session.text)
                // Move the caret to the end of the completion
                editor.caretModel.moveToOffset(caretOffset + session.text.length)
            } catch (_: Exception) {
                // Silently ignore write failures
            }
        }

        dismissGhostText(editor)
        return true
    }

    /**
     * Checks whether the current editor has active Ghost Text.
     */
    fun hasActiveGhostText(editor: Editor): Boolean {
        return sessions[editor]?.active == true
    }

    /**
     * Gets the text of the currently active Ghost Text.
     */
    fun getActiveText(editor: Editor): String? {
        return sessions[editor]?.takeIf { it.active }?.text
    }
}
