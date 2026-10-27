package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18nTopics
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * Independent theme mode for the plugin.
 */
enum class PluginThemeMode {
    /** Follow the global IDE theme (default). */
    FOLLOW,
    /** Fixed dark (affects only plugin UI, not the global IDE theme). */
    DARK,
    /** Fixed light (affects only plugin UI, not the global IDE theme). */
    LIGHT
}

/**
 * Independent theme palette for the plugin.
 *
 * Plugin UI colors are obtained uniformly through [color], with [mode] deciding
 * whether to use the light or dark palette:
 * - FOLLOW: delegates to [JBColor] (follows the global IDE theme); it also
 *   **listens for IDE theme changes** and broadcasts [I18nTopics.THEME_CHANGED]
 *   when the IDE switches themes, so the plugin UI refreshes immediately
 * - DARK/LIGHT: always uses the plugin's own palette — it neither follows IDE
 *   changes nor affects the global IDE theme
 *
 * The theme system is relatively independent: it can follow the IDE or be set on its own.
 */
object PluginTheme {

    @Volatile
    private var listenerInitialized = false

    /** Current theme mode (read from persisted settings). */
    var mode: PluginThemeMode
        get() = when (DeepSeekSettings.instance.pluginTheme) {
            "dark" -> PluginThemeMode.DARK
            "light" -> PluginThemeMode.LIGHT
            else -> PluginThemeMode.FOLLOW
        }
        set(value) {
            DeepSeekSettings.instance.pluginTheme = when (value) {
                PluginThemeMode.DARK -> "dark"
                PluginThemeMode.LIGHT -> "light"
                PluginThemeMode.FOLLOW -> "follow"
            }
        }

    /** Whether the current palette is dark. */
    fun isDark(): Boolean = when (mode) {
        PluginThemeMode.DARK -> true
        PluginThemeMode.LIGHT -> false
        PluginThemeMode.FOLLOW -> com.intellij.ide.ui.LafManager.getInstance().currentUIThemeLookAndFeel.isDark
    }

    /**
     * Returns a color for the current theme mode: [light] for the light theme, [dark] for the dark theme.
     * In FOLLOW mode this is equivalent to [JBColor] (light/dark follows IDE switching).
     */
    fun color(light: Color, dark: Color): Color = when (mode) {
        PluginThemeMode.DARK -> dark
        PluginThemeMode.LIGHT -> light
        PluginThemeMode.FOLLOW -> JBColor(light, dark)
    }

    /** int RGB overload. */
    fun color(lightRgb: Int, darkRgb: Int): Color = color(Color(lightRgb), Color(darkRgb))

    // ════════════════════════════════════════════════════════════════
    //  Semantic palette (following the mandatory UI rendering spec: light/dark dual theme)
    //  Light: background #FFFFFF  heading #111111  body #262626  border #D0D0D0
    //  Dark:  background #2B2B2B  heading #F2F2F2  body #E3E3E3  border #555555
    // ════════════════════════════════════════════════════════════════

    /** Message background: light #FFFFFF / dark #2B2B2B. */
    fun background(): Color = color(0xFFFFFF, 0x2B2B2B)

    /** Border: light #D0D0D0 / dark #555555. */
    fun border(): Color = color(0xD0D0D0, 0x555555)

    /** Body text: light #111111 / dark #F2F2F2 (same level as headings; hierarchy is conveyed by bold, keeping body text absolutely clear). */
    fun textPrimary(): Color = color(0x111111, 0xF2F2F2)

    /** Secondary text (standard body contrast, never faded): same color as body text. */
    fun textSecondary(): Color = color(0x111111, 0xF2F2F2)

    /** Heading (used with bold): light #111111 / dark #F2F2F2. */
    fun textHeading(): Color = color(0x111111, 0xF2F2F2)

    /** Muted text (timestamps/version numbers and other non-critical info, still readable): light #333333 / dark #CCCCCC. */
    fun textMuted(): Color = color(0x333333, 0xCCCCCC)

    /** Link color. */
    fun link(): Color = color(0x1A73E8, 0x64B5F6)

    /** Switches the plugin theme and broadcasts a refresh event. */
    fun setTheme(mode: PluginThemeMode) {
        if (this.mode == mode) return
        this.mode = mode
        broadcastThemeChanged()
    }

    /**
     * Listens for global IDE theme changes: in FOLLOW mode the plugin UI refreshes along.
     * Idempotent; call once at application startup.
     */
    fun ensureIdeThemeListener() {
        if (listenerInitialized) return
        synchronized(this) {
            if (listenerInitialized) return
            listenerInitialized = true
        }
        try {
            ApplicationManager.getApplication().messageBus.connect().subscribe(
                LafManagerListener.TOPIC,
                LafManagerListener {
                    // FOLLOW mode follows the IDE; DARK/LIGHT keep fixed palettes and ignore changes
                    if (mode == PluginThemeMode.FOLLOW) {
                        broadcastThemeChanged()
                    }
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun broadcastThemeChanged() {
        try {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(I18nTopics.THEME_CHANGED)
                .themeChanged()
        } catch (_: Exception) {
        }
    }
}
