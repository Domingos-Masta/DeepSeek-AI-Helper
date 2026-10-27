package com.deepseek.plugin.ui

import com.deepseek.plugin.ui.CodeBlockCard.Companion.parseResponse
import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagLayout
import java.awt.GridBagConstraints
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.ActionListener
import kotlin.math.ceil
import java.awt.FontMetrics
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.border.EmptyBorder
import javax.swing.text.DefaultCaret

/**
 * Dark flat chat message bubble component.
 *
 * Self-drawn unit — each MessageBubble draws its own rounded background and does
 * not rely on the parent container background, so changes to one bubble do not
 * affect how other bubbles are displayed.
 *
 * Design language:
 * ─────────────────────────────────────────────
 * [User message] → right-aligned card
 *
 *  ┌──────────────────────────────────────┐
 *  │  (n)  14:32                     🗑  │  ← orange avatar + time + delete
 *  │  ☕ index.js   ☕ app.css             │  ← file tags
 *  │  ──────────────────────────────────  │  ← divider
 *  │  Body content...                     │  ← white body text
 *  └──────────────────────────────────────┘
 *
 * ─────────────────────────────────────────────
 * [AI reply]   → left-aligned card
 *
 *  ┌──────────────────────────────────────┐
 *  │  ○  DP Helper                        │  ← ring icon + name
 *  │                                      │
 *  │  Reply content...                    │
 *  │                                      │
 *  │  ┌────────────────────────┐          │
 *  │  │  Code block (CodeBlockCard) │      │
 *  │  └────────────────────────┘          │
 *  └──────────────────────────────────────┘
 */
class MessageBubble(
    private val project: Any? = null,
    val role: Role,
    content: String = "",
    segments: List<ResponseSegment>? = null,
    /** Callback for the delete button at the top right of user messages (pass null to hide the delete button). */
    private val onDelete: (() -> Unit)? = null,
    /** List of code file tags (shown in user messages with a ☕ icon). */
    private val fileTabs: List<String> = emptyList(),
    /** The model's deep reasoning process (reasoning_content), used only by the ASSISTANT role. */
    private val reasoning: String? = null
) : JPanel(GridBagLayout()) {

    /** Message creation time (HH:mm format). */
    val timestamp: String = formatTimestamp(System.currentTimeMillis())

    /** Streaming: text area for incrementally appending tokens (no scroll pane; it grows naturally with content).
     *  Overrides getPreferredSize() to correctly reflect the visual line count after lineWrap,
     *  preventing a long paragraph (with no newlines) from being counted as one line and squashing the bubble. */
    val streamTextArea: JBTextArea? = if (role == Role.STREAMING)
        object : JBTextArea() {
            override fun getPreferredSize(): Dimension {
                val size = super.getPreferredSize()
                if (!lineWrap || width <= 0) return size
                val ins = insets
                val lineH = getRowHeight()
                val avail = width - ins.left - ins.right
                if (avail <= 0 || lineH <= 0) return size
                val fm = getFontMetrics(font)
                val txt = text ?: ""
                val logicalLines = txt.split("\n")
                var totalH = 0
                for (line in logicalLines) {
                    if (line.isEmpty()) {
                        totalH += lineH
                    } else {
                        val lineW = fm.stringWidth(line)
                        val wrappedLines = ceil(lineW.toDouble() / avail).toInt()
                        totalH += lineH * maxOf(1, wrappedLines)
                    }
                }
                return Dimension(size.width, totalH + ins.top + ins.bottom)
            }
        }
    else null

    enum class Role {
        USER,
        ASSISTANT,
        STREAMING
    }

    /** Anchor direction for drawing this bubble's rounded background (USER=left-aligned, ASSISTANT=left-aligned). */
    private val anchor: Int
        get() = GridBagConstraints.WEST

    // ── Visual content panel (everything inside the rounded rectangle) ──
    private lateinit var contentPanel: JPanel

    /** Expanded/collapsed state of the reasoning panel. */
    private var reasoningExpanded = false

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT

        when (role) {
            Role.USER -> setupUserMessage(content)
            Role.ASSISTANT -> setupAssistantMessage(content, segments, reasoning)
            Role.STREAMING -> setupStreamingArea()
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Self-drawn rounded background — drawn only in the contentPanel area, not occupying the alignment padding
    // ════════════════════════════════════════════════════════════════

    override fun paintComponent(g: Graphics) {
        if (!::contentPanel.isInitialized) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = 18
        val b = contentPanel.bounds
        // User messages get a subtle accent surface (Copilot-style); assistant messages stay on the panel background.
        g2.color = when (role) {
            Role.USER -> PluginTheme.color(0xEAF3FF, 0x2A3547)
            else -> PluginTheme.background()
        }
        g2.fillRoundRect(b.x, b.y, b.width, b.height, arc, arc)
        g2.color = when (role) {
            Role.USER -> PluginTheme.color(0xC7DCFF, 0x3E4C63)
            else -> PluginTheme.color(0xE8EAED, 0x3A3A3A)
        }
        g2.drawRoundRect(b.x, b.y, b.width, b.height, arc, arc)
        g2.dispose()
    }

    /**
     * Adds [contentPanel] to this component (GridBagLayout) with anchor alignment.
     * Called by each setupXxxMessage method after building contentPanel.
     */
    private fun attachContent() {
        val c = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            anchor = this@MessageBubble.anchor
        }
        add(contentPanel, c)
    }

    // ════════════════════════════════════════════════════════════════
    //  User message — right-aligned card: avatar + time + delete + file tags + divider + body
    // ════════════════════════════════════════════════════════════════

    /** Purple circle + toolwindow.svg drawn centered (scaled proportionally). */
    private fun createUserAvatar(): JPanel {
        val icon = IconLoader.getIcon("/icons/toolwindow.svg", MessageBubble::class.java)
        return object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                val size = minOf(width, height)
                g2.color = PluginTheme.color(0x7C3AED, 0x7C3AED)
                g2.fillOval(0, 0, size, size)
                val padding = 4
                val targetSize = size - padding * 2
                val scale = targetSize.toDouble() / icon.iconWidth
                val scaledW = (icon.iconWidth * scale).toInt()
                val scaledH = (icon.iconHeight * scale).toInt()
                val ix = (width - scaledW) / 2
                val iy = (height - scaledH) / 2
                g2.translate(ix, iy)
                g2.scale(scale, scale)
                icon.paintIcon(this, g2, 0, 0)
                g2.dispose()
            }
        }.apply {
            preferredSize = Dimension(30, 30)
            minimumSize = Dimension(30, 30)
            maximumSize = Dimension(30, 30)
            isOpaque = false
        }
    }

    private fun setupUserMessage(content: String) {
        // ── Avatar ──
        val avatar = createUserAvatar()

        // ── Timestamp ──
        val timeLabel = JLabel(timestamp).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = PluginTheme.textMuted()
        }

        // ── Delete button (InplaceButton: native hover highlight + click feedback) ──
        val deleteBtn = InplaceButton(
            I18n.tr("bubble.delete"),
            AllIcons.Actions.Close,
            ActionListener { onDelete?.invoke() }
        ).apply {
            isVisible = onDelete != null
        }

        // ── Header row ──
        val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(avatar)
            val nameLabel = JLabel(I18n.tr("bubble.me")).apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = PluginTheme.textHeading()
            }
            add(nameLabel)
            add(timeLabel)
        }
        val headerRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(headerLeft, BorderLayout.WEST)
            add(deleteBtn, BorderLayout.EAST)
        }

        // ── File tag row ──
        val fileTabRow = if (fileTabs.isNotEmpty()) {
            val panel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                border = EmptyBorder(0, 0, 0, 0)
            }
            for (tab in fileTabs) {
                val tabLabel = JLabel("\u2615 " + tab).apply {
                    font = font.deriveFont(Font.PLAIN, 11f)
                    foreground = PluginTheme.textPrimary()
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(PluginTheme.border(), 1),
                        EmptyBorder(2, 8, 2, 8)
                    )
                }
                panel.add(tabLabel)
            }
            panel
        } else null

        // ── Divider ──
        val divider = JPanel().apply {
            preferredSize = Dimension(1, 1)
            maximumSize = Dimension(Int.MAX_VALUE, 1)
            background = PluginTheme.border()
            isOpaque = true
        }

        // ── Body ──
        val textArea = JTextArea(content).apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = font.deriveFont(Font.PLAIN, DeepSeekSettings.instance.contentFontSize.toFloat())
            foreground = PluginTheme.textPrimary()
            background = Color(0, 0, 0, 0)
            highlighter = null
            caretColor = Color(0, 0, 0, 0)
            alignmentX = Component.LEFT_ALIGNMENT
            border = EmptyBorder(0, 0, 0, 0)
        }

        // ── Assemble the content panel ──
        contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = EmptyBorder(12, 16, 16, 16)
            add(headerRow)
            add(Box.createVerticalStrut(10))
            if (fileTabRow != null) {
                add(fileTabRow)
                add(Box.createVerticalStrut(10))
            }
            add(divider)
            add(Box.createVerticalStrut(10))
            add(textArea)
        }

        attachContent()
    }

    // ════════════════════════════════════════════════════════════════
    //  AI reply — left-aligned card + ring icon + name + content block
    // ════════════════════════════════════════════════════════════════

    private fun setupAssistantMessage(
        content: String,
        segments: List<ResponseSegment>?,
        reasoning: String? = null
    ) {
        val header = createModernHeader()

        val contentBody = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val resolvedSegments = segments ?: parseResponse(content)
        for (i in resolvedSegments.indices) {
            val seg = resolvedSegments[i]
            val isFirst = i == 0
            when (seg) {
                is ResponseSegment.Text -> {
                    if (!isFirst) contentBody.add(Box.createVerticalStrut(8))
                    val contentFontSize = DeepSeekSettings.instance.contentFontSize
                    val mdPane = MarkdownRenderer.createPane(
                        markdownText = seg.content,
                        fontSize = contentFontSize,
                        fgColor = PluginTheme.textPrimary(),
                        bgColor = null
                    ).apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                    }
                    contentBody.add(mdPane)
                }
                is ResponseSegment.Code -> {
                    if (!isFirst) contentBody.add(Box.createVerticalStrut(10))
                    val codeCard = CodeBlockCard(
                        project = project as? com.intellij.openapi.project.Project,
                        code = seg.content,
                        language = seg.language,
                        showInsertButton = true
                    ).apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                    }
                    contentBody.add(codeCard)
                }
                is ResponseSegment.Table -> {
                    if (!isFirst) contentBody.add(Box.createVerticalStrut(8))
                    val tablePanel = MessageTable(seg.headers, seg.rows).apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                    }
                    contentBody.add(tablePanel)
                }
            }
        }

        if (contentBody.componentCount == 0) {
            contentBody.add(Box.createVerticalStrut(2))
        }

        // ── Center panel: reasoning (optional) + final answer ──
        val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            if (reasoning != null && reasoning.isNotBlank()) {
                add(createReasoningPanel(reasoning))
                add(Box.createVerticalStrut(12))
            }
            add(contentBody)
        }

        contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(12, 16, 16, 16)
            add(header, BorderLayout.NORTH)
            add(centerPanel, BorderLayout.CENTER)
        }

        attachContent()
    }

    // ════════════════════════════════════════════════════════════════
    //  Collapsible "Reasoning process" panel
    // ════════════════════════════════════════════════════════════════

    /**
     * Creates a collapsible reasoning panel between the AI reply header and body.
     * - Collapsed: shows "🧠 Reasoning process  ▸  xxx characters total"
     * - Expanded: shows the reasoning content (gray background + gray text) and can be collapsed again
     */
    private fun createReasoningPanel(reasoningText: String): JPanel {
        val reasoningBody = JPanel(BorderLayout())
        reasoningBody.isOpaque = false

        // ── Content area (initially hidden) ──
        val contentArea = JBTextArea(reasoningText.trim()).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("SansSerif", (DeepSeekSettings.instance.contentFontSize - 1).coerceAtLeast(10))
            margin = JBUI.insets(8, 10, 8, 10)
            border = JBUI.Borders.customLine(PluginTheme.border(), 1)
            background = PluginTheme.color(0xF5F5F5, 0x262626)
            foreground = PluginTheme.textPrimary()
            isOpaque = true
            isVisible = false
        }

        // ── Header row: ▶ Reasoning process  xxx characters total ──
        val toggleArrow = JLabel("\u25B6") // ▶ (collapsed)
        toggleArrow.font = toggleArrow.font.deriveFont(10f)
        toggleArrow.foreground = PluginTheme.textHeading()

        val titleLabel = JLabel(I18n.tr("bubble.reasoning")).apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = PluginTheme.textHeading()
        }

        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            add(toggleArrow)
            add(titleLabel)
        }

        reasoningBody.add(headerPanel, BorderLayout.NORTH)

        // ── Click to toggle expand/collapse ──
        reasoningExpanded = false
        val toggleListener = object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                reasoningExpanded = !reasoningExpanded
                contentArea.isVisible = reasoningExpanded
                toggleArrow.text = if (reasoningExpanded) "\u25BC" else "\u25B6" // ▼ / ▶
                // Trigger the parent container to re-layout
                reasoningBody.revalidate()
                var parent = reasoningBody.parent
                while (parent != null) {
                    parent.revalidate()
                    parent = parent.parent
                }
            }
        }
        headerPanel.addMouseListener(toggleListener)

        reasoningBody.add(contentArea, BorderLayout.CENTER)

        // Wrap everything in one layer to keep the vertical layout correct inside BoxLayout
        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
            add(reasoningBody, BorderLayout.NORTH)
        }
        return wrapper
    }

    // ════════════════════════════════════════════════════════════════
    //  AI brand ring icon
    // ════════════════════════════════════════════════════════════════

    private fun createRingIcon(): JPanel {
        val icon = IconLoader.getIcon("/icons/action.svg", MessageBubble::class.java)
        return object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                val size = minOf(width, height)
                g2.color = PluginTheme.color(0x7C3AED, 0x7C3AED)
                g2.fillOval(0, 0, size, size)
                val padding = 3
                val targetSize = size - padding * 2
                val scale = targetSize.toDouble() / icon.iconWidth
                val scaledW = (icon.iconWidth * scale).toInt()
                val scaledH = (icon.iconHeight * scale).toInt()
                val ix = (width - scaledW) / 2
                val iy = (height - scaledH) / 2
                g2.translate(ix, iy)
                g2.scale(scale, scale)
                icon.paintIcon(this, g2, 0, 0)
                g2.dispose()
            }
        }.apply {
            preferredSize = Dimension(26, 26)
            minimumSize = Dimension(26, 26)
            maximumSize = Dimension(26, 26)
            isOpaque = false
        }
    }

    private fun createModernHeader(): JPanel {
        val ringIcon = createRingIcon()
        val nameLabel = JLabel(I18n.tr("bubble.dp.helper")).apply {
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = PluginTheme.textHeading()
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            border = EmptyBorder(0, 0, 8, 0)
            add(ringIcon)
            add(nameLabel)
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Streaming response area — lightweight thinking animation
    // ════════════════════════════════════════════════════════════════

    private fun setupStreamingArea() {
        val ringIcon = createRingIcon()
        val nameLabel = JLabel(I18n.tr("bubble.dp.helper")).apply {
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = PluginTheme.textHeading()
        }
        val thinkingLabel = JLabel(I18n.tr("bubble.thinking")).apply {
            font = font.deriveFont(Font.ITALIC, 13f)
            foreground = PluginTheme.textPrimary()
        }

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(ringIcon)
            add(nameLabel)
            add(thinkingLabel)
        }

        streamTextArea!!.apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = font.deriveFont(Font.PLAIN, DeepSeekSettings.instance.contentFontSize.toFloat())
            background = Color(0, 0, 0, 0)
            margin = JBUI.insets(0, 0, 0, 0)
            border = EmptyBorder(0, 0, 0, 0)
            foreground = PluginTheme.textPrimary()
            (caret as DefaultCaret).updatePolicy = DefaultCaret.ALWAYS_UPDATE
            alignmentX = Component.LEFT_ALIGNMENT
            text = "... Thinking \u25D0"
        }

        contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(12, 16, 16, 16)
            add(header, BorderLayout.NORTH)
            add(streamTextArea!!, BorderLayout.CENTER)
        }

        attachContent()
    }

    companion object {
        /** Formats the timestamp as HH:mm. */
        private fun formatTimestamp(millis: Long): String {
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            return fmt.format(java.util.Date(millis))
        }
    }
}
