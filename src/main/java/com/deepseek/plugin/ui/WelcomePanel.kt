package com.deepseek.plugin.ui

import com.deepseek.plugin.PluginVersion
import com.deepseek.plugin.i18n.I18n
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.*

/**
 * Empty-state welcome panel shown when the chat has no messages yet.
 *
 * Displays the plugin logo, name, a short description, and useful keyboard shortcuts
 * to help users get started. Uses the chat-bubble plugin icon from plugin resources.
 */
class WelcomePanel : JPanel(BorderLayout()) {

    /** Chat-bubble plugin logo rendered to a reasonable display size. */
    private val pluginIcon: Icon? = run {
        try {
            val svg = com.intellij.openapi.util.IconLoader.getIcon("/META-INF/pluginIcon.svg", WelcomePanel::class.java)
            val target = 96
            val img = BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB)
            val g2 = img.createGraphics()
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val scale = target.toDouble() / svg.iconWidth
            g2.scale(scale, scale)
            svg.paintIcon(null, g2, 0, 0)
            g2.dispose()
            ImageIcon(img)
        } catch (_: Exception) {
            null
        }
    }

    init {
        isOpaque = false
        add(createCenterPanel(), BorderLayout.CENTER)
    }

    /**
     * Rebuilds the welcome panel content immediately on language switch
     * (title / subtitle / shortcut hints, etc.).
     */
    fun refreshTexts() {
        removeAll()
        add(createCenterPanel(), BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun createCenterPanel(): JPanel {
        val center = JPanel(GridBagLayout()).apply {
            isOpaque = false
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.CENTER_ALIGNMENT
        }

        // ── Plugin icon (SVG) ──
        if (pluginIcon != null) {
            val iconLabel = JLabel(pluginIcon).apply {
                alignmentX = Component.CENTER_ALIGNMENT
            }
            content.add(iconLabel)
            content.add(Box.createVerticalStrut(12))
        }

        // ── Plugin name ──
        val nameLabel = JLabel(I18n.tr("welcome.title")).apply {
            font = font.deriveFont(Font.BOLD, 18f)
            foreground = JBColor(0x000000, 0xE0E0E0)
            alignmentX = Component.CENTER_ALIGNMENT
        }
        content.add(nameLabel)
        content.add(Box.createVerticalStrut(6))

        // ── Tagline ──
        val tagline = JLabel(I18n.tr("welcome.subtitle")).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = JBColor(0x000000, 0xBBBBBB)
            alignmentX = Component.CENTER_ALIGNMENT
        }
        content.add(tagline)
        content.add(Box.createVerticalStrut(24))

        // ── Quick tips section ──
        val tipsTitle = JLabel(I18n.tr("welcome.quickstart")).apply {
            font = font.deriveFont(Font.BOLD, 13f)
            foreground = JBColor(0x000000, 0xBBBBBB)
            alignmentX = Component.CENTER_ALIGNMENT
        }
        content.add(tipsTitle)
        content.add(Box.createVerticalStrut(10))

        val tips = listOf(
            TipEntry("Enter", I18n.tr("send")),
            TipEntry("Shift + Enter", I18n.tr("newline")),
            TipEntry("Select code", I18n.tr("autoFill")),
            TipEntry("Upload file", I18n.tr("supportedFiles")),
            TipEntry("💬 Q&A / 🤖 Agent", I18n.tr("modeSwitch")),
            TipEntry("Clear session", I18n.tr("clearSession")),
        )

        for ((i, tip) in tips.withIndex()) {
            content.add(createTipRow(tip))
            if (i < tips.lastIndex) {
                content.add(Box.createVerticalStrut(4))
            }
        }

        content.add(Box.createVerticalStrut(20))

        // ── Version info ──
        val versionLabel = JLabel(I18n.tr("welcome.version") + "${PluginVersion.current}").apply {
            font = font.deriveFont(Font.PLAIN, 10f)
            foreground = JBColor(0x000000, 0x999999)
            alignmentX = Component.CENTER_ALIGNMENT
        }
        content.add(versionLabel)

        center.add(content)
        return center
    }

    private fun createTipRow(tip: TipEntry): JPanel {
        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.CENTER_ALIGNMENT
        }

        val keyLabel = JLabel(tip.shortcut).apply {
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = JBColor(0x1A73E8, 0x64B5F6)
            border = JBUI.Borders.empty(1, 6)
        }
        row.add(keyLabel)
        row.add(Box.createHorizontalStrut(8))

        val descLabel = JLabel(tip.description).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = JBColor(0x000000, 0xAAAAAA)
        }
        row.add(descLabel)

        return row
    }

    private data class TipEntry(val shortcut: String, val description: String)
}
