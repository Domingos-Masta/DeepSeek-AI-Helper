package com.deepseek.plugin.completion

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import java.awt.event.KeyEvent

/**
 * Action that accepts a Ghost Text completion.
 *
 * When the editor has Ghost Text managed by [GhostTextManager],
 * pressing Tab (or a custom shortcut) writes its content into the document.
 *
 * When there is no Ghost Text, it dispatches a Tab key event to the editor component to trigger the default behavior
 * (indentation / code completion), avoiding directly invoking other AnActions (which would violate the OverrideOnly API constraint).
 */
class AcceptGhostTextAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        if (GhostTextManager.acceptGhostText(editor)) {
            // Ghost Text was accepted; no need to run the default behavior
            return
        }
        // When there is no Ghost Text, dispatch a Tab key event to the editor component to trigger the default behavior
        dispatchTabKey(editor)
    }

    private fun dispatchTabKey(editor: Editor) {
        val component = editor.component
        val now = System.currentTimeMillis()
        val pressed = KeyEvent(
            component, KeyEvent.KEY_PRESSED, now, 0,
            KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED
        )
        val released = KeyEvent(
            component, KeyEvent.KEY_RELEASED, now + 1, 0,
            KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED
        )
        component.dispatchEvent(pressed)
        component.dispatchEvent(released)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor != null && GhostTextManager.hasActiveGhostText(editor)) {
            e.presentation.isEnabledAndVisible = true
            e.presentation.text = "Accept DeepSeek Completion"
        } else {
            e.presentation.isEnabledAndVisible = false
        }
    }
}
