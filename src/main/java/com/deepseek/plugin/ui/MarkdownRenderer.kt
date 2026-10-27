package com.deepseek.plugin.ui

import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import javax.swing.JEditorPane
import java.awt.Dimension
import javax.swing.plaf.basic.BasicTextUI
import javax.swing.text.View
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

/**
 * Converts Markdown text to a styled [JEditorPane] for rendering in chat bubbles.
 *
 * Uses IntelliJ's bundled `org.intellij.markdown` library (from lib-client.jar)
 * to parse CommonMark and generate HTML, then renders through Swing's HTML 3.2
 * engine with IDE-aware colours (light/dark theme).
 *
 * Code fences are NOT handled here — the caller should extract ``` blocks
 * via [CodeBlockCard.parseResponse] and pass only text segments.
 */
object MarkdownRenderer {

    /**
     * Regex that matches candidate English/code terms.
     * Rule: a 2-60 character sequence starting with [a-zA-Z_] and containing
     * letters/digits/dots/underscores/angle brackets/square brackets/#, then filtered
     * by [isTechnicalTerm] to keep only genuine code-term formats.
     */
    private val REGEX_INLINE_CODE = Regex(
        "(?<=[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef\\s，。、；：！？）()" +
        "\\-–—/:;,.?!\"'\\[{(]|^)" +
        "([a-zA-Z_][\\w.<>()\\[\\]#{}:]{1,60})" +
        "(?=[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef\\s，。、；：！？）()" +
        "\\-–—/:;,.?!\"'\\]})]|$)"
    )

    /**
     * Create a [JEditorPane] that renders [markdownText] with IDE theme colours.
     *
     * @param markdownText  Markdown source (paragraphs, inline formatting, lists, headings)
     * @param fontSize      Font size in points (default 13)
     * @param fgColor       Text colour — defaults to [JBColor.foreground]
     * @param bgColor       Background colour — defaults to transparent/panel colour
     * @param linkCallback  Optional handler for link clicks (e.g. open in browser)
     */
    fun createPane(
        markdownText: String,
        fontSize: Int = DeepSeekSettings.instance.contentFontSize,
        fgColor: java.awt.Color = JBColor.foreground(),
        bgColor: java.awt.Color? = null,
        linkCallback: ((String) -> Unit)? = null
    ): JEditorPane {
        // Preprocess: automatically wrap code terms in backticks to make them stand out in body text
        val highlighted = highlightInlineCode(markdownText)
        val html = toHtml(highlighted)
        val pane = object : JEditorPane("text/html", html) {
            override fun getPreferredSize(): Dimension {
                // The base implementation reports the unwrapped natural width and
                // a single-line height. When GridBagLayout constrains the bubble to
                // the viewport width, text wraps but the reported height stays
                // one line, collapsing the bubble and clipping the answer.
                // Recompute height from the HTML root view at the current width.
                val base = super.getPreferredSize()
                if (width <= 0) return base
                val ui = getUI() as? BasicTextUI ?: return base
                val root = ui.getRootView(this) ?: return base
                root.setSize(width.toFloat(), (Int.MAX_VALUE / 2).toFloat())
                val required = root.getPreferredSpan(View.Y_AXIS).toInt()
                return Dimension(base.width, required + insets.top + insets.bottom)
            }
        }.apply {
            isEditable = false
            isOpaque = bgColor != null
            // Set the foreground explicitly: ensures refreshFont and similar code reading
            // pane.foreground use the plugin theme color, instead of falling back to
            // JBColor.foreground() (which follows the IDE, darkening body text when the
            // plugin is dark but the IDE is light)
            foreground = fgColor
            background = bgColor ?: java.awt.Color(0, 0, 0, 0) // transparent
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = font.deriveFont(fontSize.toFloat())
            border = JBUI.Borders.empty()
            margin = JBUI.insets(0)
            // Apply custom styles
            (editorKit as? HTMLEditorKit)?.styleSheet = createStyleSheet(fontSize, fgColor)
        }

        // Handle link clicks
        if (linkCallback != null) {
            pane.addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    linkCallback(e.url?.toString() ?: e.description)
                }
            }
        }

        return pane
    }

    /**
     * Updates the font size of an already-rendered [JEditorPane].
     *
     * Setting pane.font alone is not enough — the HTML content is driven by the
     * [StyleSheet] CSS rules (`body { font-size: XXpt }`), which take priority over
     * JEditorPane's font property. Therefore the StyleSheet must be rebuilt with the
     * new font size for the already-generated content to change immediately.
     */
    fun refreshFont(pane: JEditorPane, fontSize: Int) {
        pane.font = pane.font.deriveFont(fontSize.toFloat())
        val kit = pane.editorKit as? HTMLEditorKit
        if (kit != null) {
            kit.styleSheet = createStyleSheet(fontSize, pane.foreground ?: PluginTheme.textPrimary())
            pane.revalidate()
            pane.repaint()
        }
    }

    /**
     * Convert Markdown text to an HTML string suitable for embedding in a
     * JEditorPane. Produces a full <html><body>...</body></html> document.
     */
    fun toHtml(markdownText: String): String {
        val flavour = CommonMarkFlavourDescriptor()
        @Suppress("DEPRECATION")
        val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdownText)
        val generator = HtmlGenerator(markdownText, parsedTree, flavour, false)
        return generator.generateHtml()
    }

    /**
     * Wraps code terms in text with backticks so they get inline-code styling
     * (monospace font + background) when rendered as Markdown, making them stand out.
     *
     * Uses [isTechnicalTerm] to judge by word shape, recognizing only genuine code
     * terms (e.g. camelCase, PascalCase, ALL_CAPS, identifiers with special characters)
     * and leaving ordinary English words untouched.
     *
     * Examples:
     *  - "Render it with MessageBubble" → "Render it with `MessageBubble`"
     *  - "call getUser() to fetch" → "call `getUser()` to fetch"
     *  - "the API returns JSON" → "the `API` returns `JSON`"
     *
     * Content already wrapped in `` ` `` is not wrapped again.
     */
    fun highlightInlineCode(text: String): String {
        val sb = StringBuilder()
        var lastEnd = 0

        for (match in REGEX_INLINE_CODE.findAll(text)) {
            val start = match.range.first

            // Filter by word shape: keep only genuine code terms (not ordinary English words)
            if (!isTechnicalTerm(match.value)) continue

            // Skip content already wrapped in backticks (preceded by ` with a matching closing `)
            val alreadyWrapped = start > 0 && text[start - 1] == '`'

            if (!alreadyWrapped) {
                sb.append(text, lastEnd, start)
                sb.append('`')
                sb.append(match.value)
                sb.append('`')
                lastEnd = match.range.last + 1
            }
        }

        sb.append(text.substring(lastEnd))
        return sb.toString()
    }

    // ── Private helpers ──

    /**
     * Determines whether a word is a code term (rather than an ordinary English word).
     *
     * Code terms usually have one of these characteristics:
     *  - Contains non-letter characters (dot, underscore, angle brackets, parentheses, digits, etc.): `List<String>`, `user_name`, `@Override`
     *  - All uppercase and length ≥ 2: `API`, `JSON`, `MAX` (constants/abbreviations)
     *  - An uppercase letter beyond the first character (camelCase / PascalCase): `getUser`, `MessageBubble`, `JavaScript`
     */
    private fun isTechnicalTerm(word: String): Boolean {
        if (word.length < 2) return false

        // Contains non-letter characters → code identifier (underscore, dot, brackets, digits, etc.)
        if (word.any { !it.isLetter() }) return true

        val hasLower = word.any { it.isLowerCase() }
        val hasUpper = word.any { it.isUpperCase() }

        // All uppercase → constant/abbreviation (API, JSON, MAX)
        if (hasUpper && !hasLower) return true

        // An uppercase beyond the first character → camelCase / PascalCase (getUser, MessageBubble, JavaScript)
        if (hasLower && word.substring(1).any { it.isUpperCase() }) return true

        return false
    }

    private fun createStyleSheet(fontSize: Int, fgColor: java.awt.Color): StyleSheet {
        val ss = StyleSheet()
        val hexFg = toHex(fgColor)
        val hexHeading = toHex(PluginTheme.textHeading())
        val hexBorder = toHex(PluginTheme.border())
        val codeBg = toHex(PluginTheme.color(0xE8E8E8, 0x33333A))
        val codeFg = toHex(PluginTheme.color(0x1976D2, 0x7CB7FF))
        val linkColor = toHex(PluginTheme.link())

        ss.addRule("body { font-family: sans-serif; font-size: ${fontSize}pt; color: $hexFg; margin: 0; padding: 0; text-align: left; }")
        ss.addRule("p { margin: 0 0 6px 0; padding: 0; text-align: left; }")
        // Secondary headings: follow the theme's high contrast (#111111/#F2F2F2 bold); no faint light-gray text
        ss.addRule("h1, h2, h3, h4, h5, h6 { margin: 10px 0 4px 0; margin-left: 0; padding-left: 0; font-weight: bold; color: $hexHeading; text-align: left; }")
        ss.addRule("ul, ol { margin: 2px 0 6px 0; padding-left: 22px; color: $hexFg; }")
        // List items explicitly inherit the high-contrast body color: in Swing HTML rendering `li` does not always inherit the body color, so it must be set explicitly to avoid faint small gray text
        ss.addRule("li { margin: 2px 0; color: $hexFg; }")
        // Defensive: even if content contains small/sub/sup, force body-level font size and contrast; no shrinking or fading
        ss.addRule("small, sub, sup { font-size: inherit; color: $hexFg; }")
        ss.addRule("a { color: $linkColor; text-decoration: underline; }")
        // Inline code: monospace + background + a color distinct from body text so it stands out from Chinese
        ss.addRule("code { font-family: monospace; font-size: ${fontSize - 1}pt; background-color: $codeBg; color: $codeFg; padding: 1px 4px; }")
        ss.addRule("pre { background-color: transparent; padding: 0; margin: 0; }")
        // Blockquote: standard body contrast (not faded), distinguished by a left border
        ss.addRule("blockquote { margin: 4px 0; padding: 2px 10px; border-left: 3px solid $hexBorder; color: $hexFg; }")
        ss.addRule("hr { border: none; border-top: 1px solid $hexBorder; margin: 10px 0; }")
        ss.addRule("strong { font-weight: bold; }")
        ss.addRule("em { font-style: italic; }")
        return ss
    }

    private fun toHex(color: java.awt.Color): String {
        return String.format("#%02x%02x%02x", color.red, color.green, color.blue)
    }
}
