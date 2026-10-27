package com.deepseek.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import com.deepseek.plugin.i18n.I18n

/**
 * All-in-one chat input component.
 *
 * Layout (top to bottom):
 * ┌──────────────────────────────────────────────┐  ← Drag divider (light cyan, centered at 1/3 width, visible when unfocused)
 * ├──────────────────────────────────────────────┤
 * │  📄 JenkinsDemoApplication.java: 6-6    ✕   │  ← File tags integrated at the top of the input
 * │  📄 app.java  2.3 KB                    ✕   │
 * │                                              │
 * │  Type a message... @mention file · Enter to send ·... │  ← Theme-adaptive input area
 * │                                              │
 * ├──────────────────────────────────────────────┤
 * │  💬 Q&A ▾               📤 [▶ Send (Enter)]  │  ← Status bar
 * └──────────────────────────────────────────────┘
 *
 * The whole panel background is theme-adaptive, with a theme-adaptive input area
 * in the lower half. The thin light-cyan divider at the top can be dragged to
 * resize and double-clicked to reset.
 */
class ChatInputBar(
    private val inputScrollPane: JBScrollPane,
    selectedCodePanel: JPanel?,
    fileAttachmentPanel: JPanel?,
    modeSelector: JComponent?,
    uploadButton: JComponent,
    translateButton: JComponent,
    settingsButton: JComponent,
    sendStopButton: JComponent
) : JPanel(BorderLayout()) {

    /** Callback: drag to resize the input area height; the parameter is the target height in pixels. Int.MAX_VALUE means reset on double-click. */
    var onResizeRequest: ((Int) -> Unit)? = null

    companion object {
        private val C_DARK_BG = JBColor(0xE8E8E8, 0x1E1E1E)
        private val C_INPUT_BG = JBColor(0xFFFFFF, 0x2B2B2B)
        private val C_INPUT_BORDER = JBColor(0xD0D0D0, 0x3C3C3C)
        private val C_INPUT_BORDER_FOCUS = JBColor(0x1A73E8, 0x64B5F6)
        private val C_DRAG_BAR = JBColor(0x80DEEA, 0x4DD0E1)
        private val C_DRAG_BAR_HOVER = JBColor(0x4DD0E1, 0x26C6DA)
        private val C_STATUS_BG = JBColor(0xE8E8E8, 0x2D2D2D)
        /** Send-button row background — one shade darker than the input area (C_INPUT_BG). */
        private val C_SEND_BG = JBColor(0xE8E8E8, 0x1E1E1E)
    }

    init {
        isOpaque = true
        background = C_DARK_BG

        // ═══════ Layer 1: Drag handle bar (top, always-present drag zone) ═══════
        add(createDragZone(), BorderLayout.NORTH)

        // ═══════ Layer 2: Body — unified rounded background + focused blue glow border ═══════
        val body = object : JPanel() {
            private var focused = false

            init {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false

                // Listen to input focus to control the outer panel border color
                inputScrollPane.components.forEach { comp ->
                    if (comp is javax.swing.JViewport) {
                        comp.components.forEach { c ->
                            if (c is javax.swing.text.JTextComponent) {
                                c.addFocusListener(object : java.awt.event.FocusAdapter() {
                                    override fun focusGained(e: java.awt.event.FocusEvent) {
                                        focused = true
                                        repaint()
                                    }
                                    override fun focusLost(e: java.awt.event.FocusEvent) {
                                        focused = false
                                        repaint()
                                    }
                                })
                            }
                        }
                    }
                }
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val arc = 8
                    // Fill the whole area (input background; the status bar region will be overlaid by C_SEND_BG)
                    g2.color = C_INPUT_BG
                    g2.fillRoundRect(0, 0, width, height, arc, arc)
                } finally {
                    g2.dispose()
                }
            }

            override fun paint(g: Graphics) {
                super.paint(g)
                // Draw the border after child components are painted so it is not covered by the status bar
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val arc = 8
                    g2.color = if (focused) C_INPUT_BORDER_FOCUS else C_INPUT_BORDER
                    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                } finally {
                    g2.dispose()
                }
            }
        }

        // ── Input area: theme-adaptive background, file tags at top ──
        body.add(createInputArea(inputScrollPane, selectedCodePanel, fileAttachmentPanel))

        // ── Status bar ──
        body.add(createStatusBar(settingsButton, uploadButton, translateButton, modeSelector, sendStopButton))

        add(body, BorderLayout.CENTER)
    }

    // ──────────────────────────────────────────────
    //  Drag Zone (top resize handle)
    // ──────────────────────────────────────────────

    private fun createDragZone(): JPanel {
        return object : JPanel() {
            private var hovered = false
            private var inputFocused = false
            private var dragStartY = 0
            private var dragStartHeight = 0

            init {
                preferredSize = Dimension(Short.MAX_VALUE.toInt(), 8)
                minimumSize = Dimension(0, 8)
                maximumSize = Dimension(Short.MAX_VALUE.toInt(), 8)
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
                I18n.tooltip(this, "input.drag.resize")

                // Listen to input focus changes to control the divider's visibility
                inputScrollPane.components.forEach { comp: java.awt.Component ->
                    if (comp is javax.swing.JViewport) {
                        comp.components.forEach { c ->
                            if (c is javax.swing.text.JTextComponent) {
                                c.addFocusListener(object : java.awt.event.FocusAdapter() {
                                    override fun focusGained(e: java.awt.event.FocusEvent) {
                                        inputFocused = true
                                        repaint()
                                    }

                                    override fun focusLost(e: java.awt.event.FocusEvent) {
                                        inputFocused = false
                                        repaint()
                                    }
                                })
                            }
                        }
                    }
                }

                addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        dragStartY = e.yOnScreen
                        dragStartHeight = this@ChatInputBar.parent?.height ?: height
                    }

                    override fun mouseEntered(e: MouseEvent) {
                        hovered = true
                        repaint()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        hovered = false
                        repaint()
                    }

                    override fun mouseClicked(e: MouseEvent) {
                        if (e.clickCount == 2) {
                            onResizeRequest?.invoke(Int.MAX_VALUE)
                        }
                    }
                })

                addMouseMotionListener(object : MouseMotionAdapter() {
                    override fun mouseDragged(e: MouseEvent) {
                        // Absolute height calculation: compute the new height from the drag start point for a smoother feel
                        val delta = e.yOnScreen - dragStartY
                        val newHeight = dragStartHeight - delta
                        onResizeRequest?.invoke(newHeight)
                    }
                })
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                // Hide the divider when the input is focused
                if (inputFocused) return

                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                    // Divider width is 1/3 of the parent container, horizontally centered
                    val parentW = parent?.width ?: width
                    val barWidth = (parentW / 3).coerceAtLeast(60)
                    val barX = (width - barWidth) / 2
                    val barY = (height - 3) / 2

                    g2.color = if (hovered) C_DRAG_BAR_HOVER else C_DRAG_BAR
                    g2.fillRoundRect(barX, barY, barWidth, 3, 2, 2)
                } finally {
                    g2.dispose()
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Input Area (theme-adaptive, file tags at top)
    // ──────────────────────────────────────────────

    private fun createInputArea(
        scrollPane: JBScrollPane,
        selectedCodePanel: JPanel?,
        fileAttachmentPanel: JPanel?
    ): JPanel {
        val wrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 4, 8)
        }

        // ── Tags row: file/code badges at top of input ──
        val tagsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
        }
        var hasTags = false
        if (selectedCodePanel != null) {
            tagsPanel.add(selectedCodePanel)
            hasTags = true
        }
        if (fileAttachmentPanel != null) {
            tagsPanel.add(fileAttachmentPanel)
            hasTags = true
        }
        if (hasTags) {
            wrapper.add(tagsPanel)
            // Fix the tag area height so it does not change when the input stretches or shrinks
            val fixedH = tagsPanel.preferredSize.height
            tagsPanel.maximumSize = Dimension(Short.MAX_VALUE.toInt(), fixedH)
            tagsPanel.minimumSize = Dimension(0, fixedH)
        }

        // ── Text input area (transparent, rendering on theme-adaptive bg) ──
        scrollPane.isOpaque = false
        scrollPane.viewport.isOpaque = false
        scrollPane.border = JBUI.Borders.empty()
        wrapper.add(scrollPane)

        return wrapper
    }

    // ──────────────────────────────────────────────
    //  Status Bar
    // ──────────────────────────────────────────────

    private fun createStatusBar(
        settingsButton: JComponent,
        uploadButton: JComponent,
        translateButton: JComponent,
        modeSelector: JComponent?,
        sendStopButton: JComponent
    ): JPanel {
        val bar = JPanel(BorderLayout()).apply {
            background = C_SEND_BG
            isOpaque = true
            border = JBUI.Borders.empty(6, 10, 6, 8)
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 44)
        }

        // ── Left: settings + upload + translate ──
        val leftGroup = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(settingsButton)
            add(Box.createHorizontalStrut(6))
            add(uploadButton)
            add(Box.createHorizontalStrut(6))
            add(translateButton)
        }
        bar.add(leftGroup, BorderLayout.WEST)

        // ── Center: mode selector (centered container, not stretched) ──
        if (modeSelector != null) {
            val centerWrapper = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
                isOpaque = false
                add(modeSelector)
            }
            bar.add(centerWrapper, BorderLayout.CENTER)
        }

        // ── Right: send/stop button ──
        bar.add(sendStopButton, BorderLayout.EAST)

        return bar
    }
}
