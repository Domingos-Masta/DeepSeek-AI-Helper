package com.deepseek.plugin.agent

import com.deepseek.plugin.chat.ChatPanel
import com.deepseek.plugin.i18n.I18n
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Opens the theme settings page so the user can configure the right-click output language.
 */
class SetLanguageAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
        e.presentation.text = I18n.tr("agent.menu.setlang")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Activate the tool window
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("DeepSeek-AI-Helper")
        toolWindow?.activate(null)

        // Navigate to the theme settings page
        ChatPanel.currentInstance?.showSettingsPage("themeSettings")
    }
}
