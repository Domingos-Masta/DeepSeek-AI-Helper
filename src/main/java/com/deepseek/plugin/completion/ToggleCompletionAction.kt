package com.deepseek.plugin.completion

import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Quickly toggles AI completion on/off.
 * Default shortcut: Ctrl+Shift+, (the user must bind it in the Keymap).
 */
class ToggleCompletionAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val settings = DeepSeekSettings.instance
        settings.completionEnabled = !settings.completionEnabled
        val status = if (settings.completionEnabled) "enabled" else "disabled"
        // Notify via the status bar (no dialog, to avoid interruption)
        val project = e.project ?: return
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project).notifyByBalloon(
            "DeepSeek-AI-Helper",
            com.intellij.openapi.ui.MessageType.INFO,
            "AI Completion $status"
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
        val settings = DeepSeekSettings.instance
        e.presentation.text = if (settings.completionEnabled)
            "Disable AI Completion" else "Enable AI Completion"
    }
}
