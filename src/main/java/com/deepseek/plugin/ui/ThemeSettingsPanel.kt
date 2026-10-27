package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.i18n.I18nTopics
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager

/**
 * Theme settings panel — system/dark/light theme switching with instant language and font refresh.
 *
 * Improvements:
 * - Uses the IntelliJ UI DSL (panel/group/row) layout, consistent with the other settings panels
 * - Theme buttons support hover highlight and keyboard operation (Tab/arrow keys/Enter)
 * - Icons scale via JBUI.scale() for HiDPI
 * - Language changes publish a MessageBus event so all panels refresh instantly (no need to reopen settings)
 * - Font changes publish a MessageBus event so chat message bubbles change size instantly
 * - Colors use UIManager keys, adapting to any LAF
 */
class ThemeSettingsPanel : JPanel(BorderLayout()) {

    private val settings = DeepSeekSettings.instance
    private var selectedTheme: String = detectCurrentTheme()
    private val themeButtons = mutableListOf<ThemeButton>()

    init {
        isOpaque = false
        border = JBUI.Borders.empty(20, 20, 20, 20)

        val form = panel {
            // ── Theme selection ──
            group(I18n.tr("settings.theme.group")) {
                row {
                    cell(createThemeRow()).align(AlignX.FILL)
                }
            }

            // ── Language selector ──
            group(I18n.tr("settings.language.group")) {
                row(I18n.tr("lang.label")) {
                    val langs = listOf("en" to "English")
                    val langCombo = com.intellij.openapi.ui.ComboBox(langs.map { it.second }.toTypedArray())
                    langCombo.selectedIndex = langs.indexOfFirst { it.first == settings.language }.coerceAtLeast(0)
                    langCombo.addActionListener {
                        val idx = langCombo.selectedIndex
                        if (idx in langs.indices) {
                            val lang = langs[idx].first
                            settings.language = lang
                            // The I18n.currentLang setter automatically publishes the language-change event
                            I18n.currentLang = lang
                            // Refresh this panel's text (title/group titles/buttons)
                            refreshPanelTexts()
                        }
                    }
                    cell(langCombo)
                    comment(I18n.tr("settings.language.comment"))
                }
            }

            // ── Font size selector ──
            group(I18n.tr("settings.font.group")) {
                row(I18n.tr("settings.font.size")) {
                    val fontSizes = listOf(12, 13, 14, 15, 16)
                    val fontCombo = com.intellij.openapi.ui.ComboBox(fontSizes.map { "${it}px" }.toTypedArray())
                    fontCombo.selectedIndex = fontSizes.indexOf(settings.contentFontSize).coerceAtLeast(0)
                    fontCombo.addActionListener {
                        val idx = fontCombo.selectedIndex
                        if (idx in fontSizes.indices) {
                            settings.contentFontSize = fontSizes[idx]
                            // Publish the font-change event so the chat panel refreshes instantly
                            publishFontChanged()
                        }
                    }
                    cell(fontCombo)
                    comment(I18n.tr("settings.font.comment"))
                }
            }
        }

        add(form, BorderLayout.CENTER)
    }

    // ════════════════════════════════════════════════════════════════
    //  Theme button row
    // ════════════════════════════════════════════════════════════════

    private fun createThemeRow(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 4, 0)
        }

        val followBtn = ThemeButton(
            title = I18n.tr("settings.theme.follow"),
            icon = createFollowIcon(),
            themeKey = "follow"
        )
        val darkBtn = ThemeButton(
            title = I18n.tr("settings.theme.dark"),
            icon = createDarkIcon(),
            themeKey = "dark"
        )
        val lightBtn = ThemeButton(
            title = I18n.tr("settings.theme.light"),
            icon = createLightIcon(),
            themeKey = "light"
        )

        themeButtons.addAll(listOf(followBtn, darkBtn, lightBtn))
        row.add(followBtn)
        row.add(darkBtn)
        row.add(lightBtn)
        return row
    }

    /**
     * Theme selection button — supports hover highlight, selected state, focus outline, and keyboard operation.
     */
    private inner class ThemeButton(
        private val title: String,
        private val icon: Icon,
        private val themeKey: String
    ) : JComponent() {

        private var hover = false

        init {
            isOpaque = false
            preferredSize = JBUI.size(72, 64)
            minimumSize = JBUI.size(72, 64)
            maximumSize = JBUI.size(72, 64)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isFocusable = true

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
                override fun mouseClicked(e: MouseEvent) { select() }
            })
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> { select(); e.consume() }
                        KeyEvent.VK_LEFT, KeyEvent.VK_UP -> moveFocus(-1)
                        KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN -> moveFocus(1)
                    }
                }
            })
            addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) { repaint() }
                override fun focusLost(e: FocusEvent) { repaint() }
            })

            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 12, 8, 12)
            add(Box.createVerticalGlue())
            add(JLabel(icon).apply { alignmentX = Component.CENTER_ALIGNMENT })
            add(Box.createVerticalStrut(4))
            add(JLabel(title).apply {
                font = JBUI.Fonts.smallFont().asBold()
                foreground = UIManager.getColor("Label.foreground") ?: JBColor(0x1A1A1A, 0xBBBBBB)
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(Box.createVerticalGlue())
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width
            val h = height

            when {
                selectedTheme == themeKey -> {
                    g2.color = UIManager.getColor("List.selectionBackground")
                        ?: JBColor(0xE3F0FF, 0x3A4A6A)
                    g2.fillRoundRect(0, 0, w, h, JBUI.scale(8), JBUI.scale(8))
                }
                hover -> {
                    g2.color = UIManager.getColor("List.background")
                        ?: JBColor(0xF0F0F0, 0x3C3F41)
                    g2.fillRoundRect(0, 0, w, h, JBUI.scale(8), JBUI.scale(8))
                }
            }

            if (isFocusOwner) {
                g2.color = UIManager.getColor("Focus.color")
                    ?: JBColor(0x1A73E8, 0x64B5F6)
                g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
                g2.drawRoundRect(0, 0, w - 1, h - 1, JBUI.scale(8), JBUI.scale(8))
            }
            g2.dispose()
            super.paintComponent(g)
        }

        private fun select() {
            applyTheme(themeKey)
            selectedTheme = themeKey
            themeButtons.forEach { it.repaint() }
        }

        private fun moveFocus(dir: Int) {
            val idx = themeButtons.indexOf(this)
            if (idx < 0) return
            val next = (idx + dir + themeButtons.size) % themeButtons.size
            themeButtons[next].requestFocusInWindow()
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Theme application
    // ════════════════════════════════════════════════════════════════

    /**
     * Applies the plugin's standalone theme: only updates the plugin's own color state and broadcasts a refresh event,
     * without calling LafManager — so the IDE global theme is unaffected.
     */
    private fun applyTheme(themeKey: String) {
        val mode = when (themeKey) {
            "dark" -> PluginThemeMode.DARK
            "light" -> PluginThemeMode.LIGHT
            else -> PluginThemeMode.FOLLOW
        }
        PluginTheme.setTheme(mode)
    }

    /** Current plugin theme (read from persisted settings). */
    private fun detectCurrentTheme(): String = when (DeepSeekSettings.instance.pluginTheme) {
        "dark" -> "dark"
        "light" -> "light"
        else -> "follow"
    }

    // ════════════════════════════════════════════════════════════════
    //  Instant refresh
    // ════════════════════════════════════════════════════════════════

    /** Publishes the font-change event so the chat panel and other subscribers refresh instantly. */
    private fun publishFontChanged() {
        try {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(I18nTopics.CONTENT_FONT_CHANGED)
                .fontChanged()
        } catch (_: Exception) {
        }
    }

    /** Refreshes all text components in this panel that carry an i18n key. */
    private fun refreshPanelTexts() {
        fun walk(c: Component) {
            when (c) {
                is JLabel -> {
                    val k = c.getClientProperty(I18n.KEY_I18N) as? String
                    if (k != null) c.text = I18n.tr(k)
                }
                is JButton -> {
                    val k = c.getClientProperty(I18n.KEY_I18N) as? String
                    if (k != null) c.text = I18n.tr(k)
                }
            }
            if (c is Container) {
                for (i in 0 until c.componentCount) walk(c.getComponent(i))
            }
        }
        walk(this)
        revalidate()
        repaint()
    }

    // ════════════════════════════════════════════════════════════════
    //  Icon helpers (JBUI.scale for HiDPI)
    // ════════════════════════════════════════════════════════════════

    private fun createFollowIcon(): Icon = HiDpiIcon { g2, x, y, _, _ ->
        g2.color = UIManager.getColor("Label.foreground") ?: JBColor(0x555555, 0xAAAAAA)
        g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.drawOval(x + JBUI.scale(3), y + JBUI.scale(3), JBUI.scale(10), JBUI.scale(10))
        g2.fillOval(x + JBUI.scale(5), y + JBUI.scale(5), JBUI.scale(6), JBUI.scale(6))
        for (angle in 0 until 360 step 45) {
            val rad = Math.toRadians(angle.toDouble())
            val r1 = 7.0; val r2 = 9.0
            g2.drawLine(
                (x + JBUI.scale(8) + r1 * Math.cos(rad)).toInt(),
                (y + JBUI.scale(8) + r1 * Math.sin(rad)).toInt(),
                (x + JBUI.scale(8) + r2 * Math.cos(rad)).toInt(),
                (y + JBUI.scale(8) + r2 * Math.sin(rad)).toInt()
            )
        }
    }

    private fun createDarkIcon(): Icon = HiDpiIcon { g2, x, y, w, h ->
        g2.color = Color(0x3C3F41)
        g2.fillRoundRect(x, y, w, h, JBUI.scale(4), JBUI.scale(4))
        g2.color = Color(0xF0E68C)
        g2.fillOval(x + JBUI.scale(3), y + JBUI.scale(3), JBUI.scale(10), JBUI.scale(10))
        g2.color = Color(0x3C3F41)
        g2.fillOval(x + JBUI.scale(7), y + JBUI.scale(2), JBUI.scale(8), JBUI.scale(8))
    }

    private fun createLightIcon(): Icon = HiDpiIcon { g2, x, y, w, h ->
        g2.color = Color(0xFFFFFF)
        g2.fillRoundRect(x, y, w, h, JBUI.scale(4), JBUI.scale(4))
        g2.color = Color(0xE8A800)
        g2.fillOval(x + JBUI.scale(4), y + JBUI.scale(4), JBUI.scale(8), JBUI.scale(8))
        g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (angle in 0 until 360 step 45) {
            val rad = Math.toRadians(angle.toDouble())
            val r1 = 6.0; val r2 = 8.0
            g2.drawLine(
                (x + JBUI.scale(8) + r1 * Math.cos(rad)).toInt(),
                (y + JBUI.scale(8) + r1 * Math.sin(rad)).toInt(),
                (x + JBUI.scale(8) + r2 * Math.cos(rad)).toInt(),
                (y + JBUI.scale(8) + r2 * Math.sin(rad)).toInt()
            )
        }
    }

    /**
     * HiDPI-adapted icon: drawn with JBUI.scale for crispness on high-DPI screens.
     */
    private class HiDpiIcon(
        private val draw: (Graphics2D, Int, Int, Int, Int) -> Unit
    ) : Icon {
        override fun getIconWidth(): Int = JBUI.scale(16)
        override fun getIconHeight(): Int = JBUI.scale(16)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            draw(g2, x, y, JBUI.scale(16), JBUI.scale(16))
            g2.dispose()
        }
    }

    companion object {
    }
}
