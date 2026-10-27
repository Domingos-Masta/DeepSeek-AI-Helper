package com.deepseek.plugin.i18n

import com.intellij.util.messages.Topic

/**
 * Interface language change listener.
 * Broadcast via MessageBus so each UI panel can refresh its copy immediately without reopening settings.
 */
interface LanguageChangeListener {
    fun languageChanged()
}

/**
 * Content font size change listener.
 * The chat panel subscribes to refresh the font size of already-rendered messages immediately.
 */
interface ContentFontChangeListener {
    fun fontChanged()
}

/**
 * Plugin theme change listener.
 * Each plugin panel subscribes to refresh its own colors immediately (without changing the IDE global theme).
 */
interface ThemeChangeListener {
    fun themeChanged()
}

/**
 * Global UI refresh event bus (IntelliJ MessageBus).
 */
object I18nTopics {
    /** Language change event */
    val LANGUAGE_CHANGED: Topic<LanguageChangeListener> =
        Topic("DeepSeekAI.LanguageChanged", LanguageChangeListener::class.java)

    /** Content font size change event */
    val CONTENT_FONT_CHANGED: Topic<ContentFontChangeListener> =
        Topic("DeepSeekAI.ContentFontChanged", ContentFontChangeListener::class.java)

    /** Plugin standalone theme change event */
    val THEME_CHANGED: Topic<ThemeChangeListener> =
        Topic("DeepSeekAI.ThemeChanged", ThemeChangeListener::class.java)
}
