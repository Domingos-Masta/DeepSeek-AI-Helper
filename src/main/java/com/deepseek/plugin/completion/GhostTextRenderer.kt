package com.deepseek.plugin.completion

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform

/**
 * Ghost Text renderer.
 *
 * Draws the completion suggestion as gray, semi-transparent text at the caret position.
 * Supports multi-line completion: the first line appears as an inline inlay immediately after the caret,
 * and subsequent lines appear as a block inlay below the caret line.
 *
 * Drawing is controlled via the [EditorCustomElementRenderer] interface.
 */
class GhostTextRenderer(
    private val text: String,
    /** Width (in pixels) of the completion text already occupied by the editor itself, used to offset the inline element */
    private val offsetX: Int = 0
) : com.intellij.openapi.editor.EditorCustomElementRenderer {

    /** Cached line height */
    private var lineHeight: Int = -1

    /** Cached font metrics */
    private var fontMetrics: java.awt.FontMetrics? = null

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val metrics = getFontMetrics(editor)
        if (metrics == null) {
            // Fallback: approximate 8px per character
            return text.length * 8 + offsetX
        }
        val firstLine = text.lines().firstOrNull() ?: text
        val width = metrics.stringWidth(firstLine) + offsetX + 4 // +4 margin
        return maxOf(width, 10)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val lineH = getLineHeight(editor)
        return if (lineH > 0) lineH else editor.lineHeight
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val metrics = getFontMetrics(editor) ?: return

        // Semi-transparent gray — classic Ghost Text style
        g.color = JBColor(GHOST_COLOR_LIGHT, GHOST_COLOR_DARK)
        g.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)

        val lines = text.lines()
        val lineH = getLineHeight(editor)

        // Draw the first line (inline)
        var y = targetRegion.y + metrics.ascent
        g.drawString(lines.first(), targetRegion.x + offsetX, y)

        // Draw subsequent lines (block — if there are multiple lines)
        for (i in 1 until lines.size) {
            y += lineH
            g.drawString(lines[i], targetRegion.x, y)
        }
    }

    private fun getFontMetrics(editor: Editor): java.awt.FontMetrics? {
        if (fontMetrics == null) {
            try {
                val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
                val ctx = FontRenderContext(AffineTransform(), true, true)
                val frc = editor.contentComponent.getFontMetrics(font)
                fontMetrics = editor.contentComponent.getFontMetrics(font)
            } catch (_: Exception) {
                return null
            }
        }
        return fontMetrics
    }

    private fun getLineHeight(editor: Editor): Int {
        if (lineHeight < 0) {
            lineHeight = editor.lineHeight
        }
        return lineHeight
    }

    companion object {
        private val GHOST_COLOR_LIGHT = java.awt.Color(128, 128, 128, 120)
        private val GHOST_COLOR_DARK = java.awt.Color(160, 160, 160, 100)
    }
}
