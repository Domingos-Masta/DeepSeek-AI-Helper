package com.deepseek.plugin.ui

import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.Timer
import javax.swing.UIManager

/**
 * Marquee label — text scrolls horizontally from right to left in a loop.
 *
 * - Scrolling only starts when the text is wider than the component
 * - Pauses on mouse hover and resumes on exit
 * - The Timer stops automatically when the component is removed from its container
 */
class MarqueeLabel(
    /** Text to display. */
    private var displayText: String
) : JComponent() {

    /** Current draw offset in pixels; negative values shift left. */
    private var scrollOffset = 0f

    /** Swing Timer that drives the scrolling. */
    private var scrollTimer: Timer? = null

    /** Whether the mouse is currently hovering. */
    private var hovered = false

    /** Cached text width in pixels, to avoid recomputing every frame. */
    private var textPxWidth = 0

    /** Whether scrolling is needed. */
    private val needsScroll: Boolean
        get() {
            updateTextWidth()
            return textPxWidth > width && width > 0
        }

    init {
        isOpaque = false
        // JComponent has no default font; set one explicitly so deriveFont() works
        font = UIManager.getFont("Label.font") ?: Font("Dialog", Font.PLAIN, 12)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                scrollTimer?.stop()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                if (needsScroll) ensureTimerRunning()
            }
        })
    }

    // ── Text width cache ──

    private fun updateTextWidth() {
        val fm: FontMetrics = getFontMetrics(font)
        textPxWidth = fm.stringWidth(displayText)
    }

    // ── Timer management ──

    private fun ensureTimerRunning() {
        if (!needsScroll) return
        if (scrollTimer != null && scrollTimer!!.isRunning) return
        scrollOffset = width.toFloat()
        scrollTimer = Timer(30) {
            scrollOffset -= 1.5f
            // Text fully exits the left edge → re-enter from the right
            if (scrollOffset < -textPxWidth) {
                scrollOffset = width.toFloat()
            }
            repaint()
        }.apply { start() }
    }

    private fun stopTimer() {
        scrollTimer?.stop()
        scrollTimer = null
    }

    // ── Lifecycle ──

    override fun addNotify() {
        super.addNotify()
        if (needsScroll) ensureTimerRunning()
    }

    override fun removeNotify() {
        stopTimer()
        super.removeNotify()
    }

    // ── Size hint: let the layout manager reserve space by text width ──

    override fun getPreferredSize(): Dimension {
        updateTextWidth()
        val fm: FontMetrics = getFontMetrics(font)
        return Dimension(textPxWidth + 4, fm.height + 4)
    }

    override fun getMinimumSize(): Dimension {
        val fm: FontMetrics = getFontMetrics(font)
        return Dimension(20, fm.height + 4)
    }

    // ── Painting ──

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        if (displayText.isEmpty()) return

        // Start the Timer on first paint or after the width changes
        if (!hovered && needsScroll && (scrollTimer == null || !scrollTimer!!.isRunning)) {
            ensureTimerRunning()
        }

        val g2d = g.create() as Graphics2D
        try {
            g2d.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            )
            g2d.font = font
            g2d.color = foreground

            val fm: FontMetrics = g2d.fontMetrics
            val textH = fm.ascent
            // Vertically centered
            val y = (height - fm.height) / 2f + textH

            if (!needsScroll) {
                // Text is not long → draw it statically and centered
                g2d.drawString(displayText, 4f, y)
            } else {
                // Draw the main text
                g2d.drawString(displayText, scrollOffset, y)
                // If there is trailing blank space → draw a duplicate for a seamless loop
                if (scrollOffset + textPxWidth < width) {
                    g2d.drawString(displayText, scrollOffset + textPxWidth + width, y)
                }
            }
        } finally {
            g2d.dispose()
        }
    }

    // ── Public API ──

    /** Updates the text and restarts scrolling if needed. */
    fun setText(newText: String) {
        displayText = newText
        textPxWidth = 0  // force recompute
        stopTimer()
        if (needsScroll) ensureTimerRunning()
        repaint()
    }
}
