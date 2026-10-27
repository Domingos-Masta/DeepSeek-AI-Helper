package com.deepseek.plugin.agent

import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.ui.CodeBlockCard
import com.deepseek.plugin.ui.ResponseSegment
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Displays the AI response content in a green-background popup below the code selected in the editor.
 *
 * Layout:
 * ┌─────────────────────────┐
 * │ The `ComponentActivity` │
 * │ class is part of...     │  ← text body (scrollable in all directions)
 * └─────────────────────────┘
 */
object AgentResultPopup {

    // ── Color scheme ──
    private val GREEN_BG = JBColor(Color(0xE8F5E9), Color(0x1B5E20))
    private val GREEN_BORDER = JBColor(Color(0x4CAF50), Color(0x388E3C))
    private val TEXT_COLOR = JBColor(Color(0x212121), Color(0xE0E0E0))
    private val ACCENT_COLOR = JBColor(Color(0x2E7D32), Color(0xA5D6A7))

    private var activeHint: Balloon? = null

    /** Streaming mode: text area currently receiving appended content */
    private var streamingTextArea: JBTextArea? = null
    /** Streaming mode: current editor (used to position the popup) */
    private var streamingEditor: Editor? = null

    fun dismiss() {
        activeHint?.dispose()
        activeHint = null
        streamingTextArea = null
        streamingEditor = null
    }

    /**
     * Shows a loading hint below the starting line of the selected code.
     */
    fun showLoadingHint(editor: Editor) {
        dismiss()
        streamingEditor = editor

        val label = JLabel("  ⏳ ${I18n.tr("agent.calling")}  ").apply {
            font = JBUI.Fonts.create("SansSerif", 13).deriveFont(Font.BOLD)
            foreground = ACCENT_COLOR
            horizontalAlignment = SwingConstants.CENTER
        }

        val hintPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = true
            background = GREEN_BG
            border = JBUI.Borders.empty(8, 16)
            add(label)
        }

        activeHint = JBPopupFactory.getInstance().createBalloonBuilder(hintPanel)
            .setFillColor(GREEN_BG)
            .setBorderColor(GREEN_BORDER)
            .setBorderInsets(JBUI.insets(0))
            .setFadeoutTime(0)
            .setHideOnKeyOutside(true)
            .setHideOnAction(false)
            .createBalloon()
            .also { it.show(belowSelectionStart(editor), Balloon.Position.below) }
    }

    /**
     * Shows the result popup below the starting line of the selected code.
     *
     * @param editor       current editor
     * @param project      current project
     * @param originalCode original code selected by the user (used as the title header)
     * @param response     AI response content (Markdown format)
     */
    fun showResult(editor: Editor, project: Project, originalCode: String, response: String) {
        dismiss()

        val rootPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = GREEN_BG
            isOpaque = true
        }

        // ── Body: parse the response and render it in segments ──
        val segments = CodeBlockCard.parseResponse(response)
        val bodyPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6, 12, 8, 12)
            background = GREEN_BG
            isOpaque = true
        }

        for (segment in segments) {
            when (segment) {
                is ResponseSegment.Text -> {
                    bodyPanel.add(createTextSegment(segment.content))
                }
                is ResponseSegment.Code -> {
                    bodyPanel.add(Box.createVerticalStrut(4))
                    bodyPanel.add(createTextSegment(segment.content))
                    bodyPanel.add(Box.createVerticalStrut(4))
                }
                is ResponseSegment.Table -> {
                    bodyPanel.add(Box.createVerticalStrut(4))
                    bodyPanel.add(createTextSegment(tableToText(segment)))
                    bodyPanel.add(Box.createVerticalStrut(4))
                }
            }
        }

        rootPanel.add(bodyPanel)

        // Limit the maximum width; height grows with content and scrolls vertically when exceeded
        rootPanel.maximumSize = Dimension(660, Int.MAX_VALUE)

        val scrollPane = JBScrollPane(rootPanel).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(520, 300)
        }

        activeHint = JBPopupFactory.getInstance().createBalloonBuilder(scrollPane)
            .setFillColor(GREEN_BG)
            .setBorderColor(GREEN_BORDER)
            .setBorderInsets(JBUI.insets(4))
            .setFadeoutTime(0)
            .setCloseButtonEnabled(true)
            .setHideOnKeyOutside(true)
            .setHideOnAction(true)
            .setShadow(true)
            .createBalloon()
            .also { it.show(belowSelectionStart(editor), Balloon.Position.below) }
    }

    // ── Streaming output ──

    /**
     * Appends a streaming token to the current popup. On the first call it automatically closes the loading hint and creates the streaming popup.
     */
    fun appendStreamingContent(token: String) {
        if (token.isEmpty()) return

        if (streamingTextArea == null) {
            // First token: close the loading hint and create the streaming popup
            val editor = streamingEditor ?: return
            dismiss()

            val textArea = JBTextArea().apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = JBUI.Fonts.create("SansSerif", 13)
                margin = JBUI.insets(6, 12, 6, 12)
                border = JBUI.Borders.empty()
                background = GREEN_BG
                foreground = TEXT_COLOR
                isOpaque = true
            }
            streamingTextArea = textArea

            val rootPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = GREEN_BG
                isOpaque = true
                add(textArea)
            }

            val scrollPane = JBScrollPane(rootPanel).apply {
                border = JBUI.Borders.empty()
                preferredSize = Dimension(520, 300)
            }

            activeHint = JBPopupFactory.getInstance().createBalloonBuilder(scrollPane)
                .setFillColor(GREEN_BG)
                .setBorderColor(GREEN_BORDER)
                .setBorderInsets(JBUI.insets(4))
                .setFadeoutTime(0)
                .setCloseButtonEnabled(true)
                .setHideOnKeyOutside(true)
                .setHideOnAction(true)
                .setShadow(true)
                .createBalloon()
                .also { it.show(belowSelectionStart(editor), Balloon.Position.below) }
        }
        streamingTextArea?.append(token)
    }

    /** Streaming output finished; clear the references */
    fun finishStreaming() {
        streamingTextArea = null
        streamingEditor = null
    }

    // ── Internal methods ──

    /**
     * Renders a text segment using IDEA's native JBTextArea with the green theme.
     */
    private fun createTextSegment(text: String): JBTextArea {
        return JBTextArea(text.trim()).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("SansSerif", 13)
            margin = JBUI.insets(2, 0, 4, 0)
            border = JBUI.Borders.empty()
            background = GREEN_BG
            foreground = TEXT_COLOR
            isOpaque = true
        }
    }

    /** Converts a table segment to plain text (Markdown table format) */
    private fun tableToText(table: ResponseSegment.Table): String = buildString {
        appendLine(table.headers.joinToString(" | "))
        appendLine(table.headers.joinToString(" | ") { "---" })
        for (row in table.rows) {
            appendLine(row.joinToString(" | "))
        }
    }

    /** Positions the popup below the center of the selected code without covering it */
    private fun belowSelectionStart(editor: Editor): RelativePoint {
        if (!editor.selectionModel.hasSelection()) {
            val pos = editor.offsetToVisualPosition(editor.caretModel.offset)
            val pt = editor.visualPositionToXY(pos)
            return RelativePoint(editor.contentComponent, Point(pt.x, pt.y))
        }
        val startPos = editor.offsetToVisualPosition(editor.selectionModel.selectionStart)
        val endPos = editor.offsetToVisualPosition(editor.selectionModel.selectionEnd)
        val startPt = editor.visualPositionToXY(startPos)
        val endPt = editor.visualPositionToXY(endPos)
        // Horizontal centering: take the middle X coordinate of the selection's start and end
        val centerX = (startPt.x + endPt.x) / 2
        // Vertical: place below the last line of the selected code (to avoid covering it)
        val belowY = endPt.y + editor.lineHeight
        return RelativePoint(editor.contentComponent, Point(centerX, belowY))
    }
}
