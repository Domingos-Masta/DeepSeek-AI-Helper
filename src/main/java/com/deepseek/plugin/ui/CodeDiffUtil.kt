package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18n
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.ActionListener
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Code change preview utility.
 *
 * Workflow:
 * 1. Show original code and generated code in a side-by-side comparison panel
 * 2. The user reviews and decides whether to "Apply changes"
 */
object CodeDiffUtil {

    /**
     * Diff change information for a single file.
     */
    data class FileDiffItem(
        val filePath: String,
        val action: String, // "write" or "delete"
        val originalContent: String,
        val newContent: String
    )

    /**
     * Shows the diff comparison and asks the user whether to apply the changes.
     *
     * @param project The current project.
     * @param originalCode Original code.
     * @param newCode Newly generated code.
     * @param onApply Callback executed after user confirmation (called on the EDT).
     */
    @JvmStatic
    fun showDiffAndApply(
        project: Project,
        originalCode: String,
        newCode: String,
        onApply: () -> Unit
    ) {
        val dialog = DiffPreviewDialog(project, originalCode, newCode, null)
        if (dialog.showAndGet()) {
            onApply()
        }
    }

    /**
     * Shows the diff comparison and returns whether the user confirmed applying the changes.
     * Automatically returns true when the code is unchanged (no confirmation needed).
     *
     * @param project The current project.
     * @param originalCode Original code.
     * @param newCode Newly generated code.
     * @param filePath Optional file path used for the dialog title.
     * @return true if the user confirmed applying, false to skip.
     */
    @JvmStatic
    fun showDiffAndConfirm(
        project: Project,
        originalCode: String,
        newCode: String,
        filePath: String? = null
    ): Boolean {
        if (originalCode == newCode) {
            return true // no change, auto-confirm
        }
        val dialog = DiffPreviewDialog(project, originalCode, newCode, filePath)
        return dialog.showAndGet()
    }

    /**
     * Shows the diff comparison and asks whether to apply the changes.
     * Automatically skips when the code is unchanged.
     */
    @JvmStatic
    fun showDiffIfChanged(
        project: Project,
        originalCode: String,
        newCode: String,
        onApply: () -> Unit
    ): Boolean {
        if (originalCode == newCode) {
            Messages.showInfoMessage(project, I18n.tr("diff.no.change"), I18n.tr("diff.no.change.title"))
            return false
        }
        showDiffAndApply(project, originalCode, newCode, onApply)
        return true
    }

    /**
     * Batch-shows the change comparison for all files as a summary list, where each
     * file can be expanded to view its diff. After user confirmation, all changes are applied.
     *
     * @param project The current project.
     * @param items List of change information for all files.
     * @return true if the user confirmed applying all changes, false to cancel.
     */
    @JvmStatic
    fun showBatchDiffAndConfirm(
        project: Project,
        items: List<FileDiffItem>
    ): Boolean {
        // Filter out files that actually changed
        val changedItems = items.filter { it.originalContent != it.newContent }
        if (changedItems.isEmpty()) {
            return true // no change, auto-confirm
        }
        val dialog = BatchDiffDialog(project, changedItems)
        return dialog.showAndGet()
    }

    // ================================================================
    // Single-file diff preview dialog
    // ================================================================

    private class DiffPreviewDialog(
        project: Project,
        private val originalCode: String,
        private val newCode: String,
        filePath: String? = null
    ) : DialogWrapper(project, true) {

        init {
            title = if (filePath != null) {
                I18n.tr("diff.preview.title") + " - " + filePath
            } else {
                I18n.tr("diff.preview.title")
            }
            isResizable = true
            setOKButtonText(I18n.tr("diff.apply"))
            setCancelButtonText(I18n.tr("chat.cancel"))
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            panel.border = JBUI.Borders.empty(8)
            panel.preferredSize = Dimension(800, 500)

            // ── Title row ──
            val headerPanel = JPanel(GridLayout(1, 2))
            headerPanel.add(createHeaderLabel(I18n.tr("diff.original"), JBColor(0x666666, 0xBBBBBB)))
            headerPanel.add(createHeaderLabel(I18n.tr("diff.generated"), JBColor(0x4477AA, 0x6699CC)))
            panel.add(headerPanel, BorderLayout.NORTH)

            // ── Left-right comparison area ──
            val diffPanel = JPanel(GridLayout(1, 2, 8, 0))
            diffPanel.add(createCodePanel(originalCode, JBColor(0xF5F5F5, 0x1E1E22)))
            diffPanel.add(createCodePanel(newCode, JBColor(0xF0F7F0, 0x1E2A1E)))
            panel.add(diffPanel, BorderLayout.CENTER)

            return panel
        }

        private fun createHeaderLabel(text: String, color: Color): JLabel {
            return JLabel(text).apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = color
                border = JBUI.Borders.empty(4, 8, 8, 8)
                horizontalAlignment = JLabel.CENTER
            }
        }

        private fun createCodePanel(code: String, bg: Color): JComponent {
            val textArea = JBTextArea(code).apply {
                isEditable = false
                lineWrap = false
                font = JBUI.Fonts.create("Monospaced", 12)
                background = bg
                foreground = JBColor(0x000000, 0xD4D4D4)
                caretColor = foreground
                margin = JBUI.insets(10)
                border = JBUI.Borders.customLine(JBColor(0xCCCCCC, 0x444444), 1)
                selectedTextColor = JBColor.WHITE
                selectionColor = JBColor(0x3399FF, 0x2D5B9E)
            }
            return JBScrollPane(textArea).apply {
                border = JBUI.Borders.empty()
                verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            }
        }
    }

    // ================================================================
    // Batch diff preview dialog — summary list + expandable diff view per file
    // ================================================================

    private class BatchDiffDialog(
        project: Project,
        private val items: List<FileDiffItem>
    ) : DialogWrapper(project, true) {

        init {
            title = I18n.tr("diff.batch.title", items.size)
            isResizable = true
            setOKButtonText(I18n.tr("diff.batch.load"))
            setCancelButtonText(I18n.tr("chat.cancel"))
            init()
        }

        override fun createCenterPanel(): JComponent {
            val outerPanel = JPanel(BorderLayout())
            outerPanel.border = JBUI.Borders.empty(8)
            outerPanel.preferredSize = Dimension(880, 550)

            // ── File list card area (scrollable) ──
            val cardPanel = JPanel()
            cardPanel.layout = BoxLayout(cardPanel, BoxLayout.Y_AXIS)
            cardPanel.border = JBUI.Borders.empty(4)

            for ((index, item) in items.withIndex()) {
                if (index > 0) {
                    cardPanel.add(Box.createVerticalStrut(4))
                }
                cardPanel.add(FileDiffCard(item))
            }

            val scrollPane = JBScrollPane(cardPanel).apply {
                border = JBUI.Borders.customLine(JBColor(0xCCCCCC, 0x444444), 1)
                verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }
            outerPanel.add(scrollPane, BorderLayout.CENTER)

            return outerPanel
        }

        /**
         * Change card for a single file: title row + collapsible diff comparison panel.
         */
        private inner class FileDiffCard(
            private val item: FileDiffItem
        ) : JPanel(BorderLayout()) {
            private var diffVisible = false
            private val diffPanel: JPanel
            private val expandBtn: JButton

            init {
                border = JBUI.Borders.customLine(JBColor(0xBBBBBB, 0x555555), 1)
                val bg = JBColor(0xFAFAFA, 0x2B2B2B)
                background = bg

                // ── Title row ──
                val headerPanel = JPanel(BorderLayout()).apply {
                    background = bg
                    border = JBUI.Borders.empty(4, 8, 4, 8)
                }

                // File path + action marker
                val actionLabel = if (item.action == "write" && item.originalContent.isEmpty()) {
                    "  🆕"
                } else if (item.action == "delete") {
                    "  🗑"
                } else {
                    "  📝"
                }

                val nameLabel = JLabel(item.filePath + actionLabel).apply {
                    font = font.deriveFont(Font.BOLD, 12f)
                    foreground = JBColor(0x000000, 0xD4D4D4)
                }
                headerPanel.add(nameLabel, BorderLayout.WEST)

                // Expand/collapse button
                expandBtn = JButton(I18n.tr("diff.batch.expand")).apply {
                    font = font.deriveFont(11f)
                    border = JBUI.Borders.empty(2, 12)
                    isFocusPainted = false
                    addActionListener {
                        toggleDiff()
                    }
                }
                headerPanel.add(expandBtn, BorderLayout.EAST)

                add(headerPanel, BorderLayout.NORTH)

                // ── diff comparison panel (initially hidden) ──
                diffPanel = createDiffContentPanel(item.originalContent, item.newContent)
                diffPanel.isVisible = false
                add(diffPanel, BorderLayout.CENTER)
            }

            private fun toggleDiff() {
                diffVisible = !diffVisible
                diffPanel.isVisible = diffVisible
                expandBtn.text = if (diffVisible) I18n.tr("diff.batch.collapse") else I18n.tr("diff.batch.expand")
                // Notify the parent container to refresh layout
                revalidate()
                // Find the outermost JScrollPane and scroll the card into view
                if (diffVisible) {
                    scrollRectToVisible(bounds)
                }
            }

            private fun createDiffContentPanel(originalCode: String, newCode: String): JPanel {
                val panel = JPanel(BorderLayout())
                panel.border = JBUI.Borders.empty(4, 8, 8, 8)

                // Title row
                val headerPanel = JPanel(GridLayout(1, 2))
                headerPanel.add(createBatchHeaderLabel(I18n.tr("diff.original"), JBColor(0x666666, 0xBBBBBB)))
                headerPanel.add(createBatchHeaderLabel(I18n.tr("diff.generated"), JBColor(0x4477AA, 0x6699CC)))
                panel.add(headerPanel, BorderLayout.NORTH)

                // Left-right comparison
                val diffContent = JPanel(GridLayout(1, 2, 8, 0))
                diffContent.add(createCodePanel(originalCode, JBColor(0xF5F5F5, 0x1E1E22)))
                diffContent.add(createCodePanel(newCode, JBColor(0xF0F7F0, 0x1E2A1E)))
                panel.add(diffContent, BorderLayout.CENTER)

                return panel
            }

            private fun createBatchHeaderLabel(text: String, color: Color): JLabel {
                return JLabel(text).apply {
                    font = font.deriveFont(Font.BOLD, 11f)
                    foreground = color
                    border = JBUI.Borders.empty(2, 8, 4, 8)
                    horizontalAlignment = SwingConstants.CENTER
                }
            }

            private fun createCodePanel(code: String, bg: Color): JComponent {
                val textArea = JBTextArea(code).apply {
                    isEditable = false
                    lineWrap = false
                    font = JBUI.Fonts.create("Monospaced", 11)
                    background = bg
                    foreground = JBColor(0x000000, 0xD4D4D4)
                    caretColor = foreground
                    margin = JBUI.insets(6)
                    border = JBUI.Borders.customLine(JBColor(0xCCCCCC, 0x444444), 1)
                    selectedTextColor = JBColor.WHITE
                    selectionColor = JBColor(0x3399FF, 0x2D5B9E)
                }
                return JBScrollPane(textArea).apply {
                    border = JBUI.Borders.empty()
                    preferredSize = Dimension(400, 150)
                    verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                    horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                }
            }
        }
    }
}
