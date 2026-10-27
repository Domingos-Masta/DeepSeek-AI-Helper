package com.deepseek.plugin.agent

import com.deepseek.plugin.chat.ChatPanel
import com.deepseek.plugin.i18n.I18n
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager

/**
 * "Uploads" the text selected in the console to the chat panel, displaying it above the input box as a selected-code tag.
 *
 * The user can select text in the Run/Debug Console → right-click → DeepSeek AI → Upload to Chat.
 * The selected text appears above the chat input box as a "Console Output: 1-XX" tag,
 * and the user can then ask questions in the chat panel; the AI will answer using that context.
 */
class UploadConsoleAction : AnAction() {

    /**
     * Uses the EDT thread model to ensure UI data such as [CommonDataKeys.EDITOR] is accessible in the console context.
     * Fetching the console editor on a BGT thread may return null.
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
        e.presentation.text = I18n.tr("agent.menu.upload")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Get the selected text — on the EDT thread CommonDataKeys.EDITOR is also available in the console
        val editor = e.getData(CommonDataKeys.EDITOR)
        val selectedText = editor?.selectionModel?.selectedText ?: return
        if (selectedText.isBlank()) return

        // Activate the DeepSeek AI chat panel (the callback ensures content is pushed only after the panel is initialized)
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("DeepSeek-AI-Helper")
        toolWindow?.activate {
            // Prefer currentInstance, falling back to searching the tool window content
            val panel = ChatPanel.currentInstance ?: findChatPanel(toolWindow)
            panel?.let {
                it.setConsoleContext(selectedText)
                it.focusInput()
            }
        }
    }

    /**
     * Finds a [ChatPanel] instance in the ToolWindow's content.
     * Used as a fallback when [ChatPanel.currentInstance] is null
     * (e.g. there can be a timing gap when the tool window is closed and then reactivated).
     */
    private fun findChatPanel(toolWindow: ToolWindow): ChatPanel? {
        for (i in 0 until toolWindow.contentManager.contentCount) {
            val content = toolWindow.contentManager.getContent(i) ?: continue
            if (content.component is ChatPanel) {
                return content.component as ChatPanel
            }
        }
        return null
    }
}
