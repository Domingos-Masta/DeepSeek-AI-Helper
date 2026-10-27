package com.deepseek.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/**
 * Complete all-in-one dark table component.
 *
 * Features:
 * - paintComponent draws all horizontal and vertical grid lines uniformly, with no double borders or breaks
 * - GridBagLayout global layout keeps every column strictly aligned
 * - Bold, highlighted headers with auto-wrapping data rows
 * - Cells can embed colored markers (green ✓ / red ✕), centered
 * - Long text wraps automatically without overflow or truncation
 * - Uniform padding, vertically centered
 */
class MessageTable(
    headers: List<String>,
    rows: List<List<String>>
) : JPanel(GridBagLayout()) {

    private val gridColor = PluginTheme.border()
    private val headerBg = PluginTheme.color(0xE8E8E8, 0x2B2B2B)
    private val cellBg = PluginTheme.background()
    private val headerFg = PluginTheme.textHeading()
    private val cellFg = PluginTheme.textPrimary()
    private val cellPadding = EmptyBorder(7, 10, 7, 10)

    private val rowCount: Int
    private val colCount: Int

    init {
        isOpaque = false
        colCount = headers.size
        rowCount = rows.size

        // ── Build all cells with GridBagLayout ──
        // Header row
        for (col in 0 until colCount) {
            val headerText = headers.getOrElse(col) { "" }
            val cell = createCell(headerText, isHeader = true, row = 0, col = col)
            val gbc = cellConstraints(row = 0, col = col)
            add(cell, gbc)
        }

        // Data rows
        for (row in 0 until rowCount) {
            val rowData = rows.getOrElse(row) { emptyList() }
            for (col in 0 until colCount) {
                val cellText = rowData.getOrElse(col) { "" }
                val cell = createCell(cellText, isHeader = false, row = row + 1, col = col)
                val gbc = cellConstraints(row = row + 1, col = col)
                add(cell, gbc)
            }
        }
    }

    private fun cellConstraints(row: Int, col: Int): GridBagConstraints {
        return GridBagConstraints().apply {
            gridx = col
            gridy = row
            weightx = 1.0
            weighty = 0.0
            fill = GridBagConstraints.BOTH
            // No insets — grid lines are drawn uniformly by paintComponent
            insets = java.awt.Insets(0, 0, 0, 0)
        }
    }

    private fun createCell(text: String, isHeader: Boolean, row: Int, col: Int): JPanel {
        val isMarker = text.matches(Regex("\\s*[✓✔✅✕×❌]\\s*"))

        val contentComponent: Component = if (isHeader || isMarker) {
            // Header or pure-marker cell → centered JLabel
            val label = JLabel(formatCellText(text), SwingConstants.CENTER).apply {
                font = this.font.deriveFont(if (isHeader) Font.BOLD else Font.PLAIN, 12f)
                foreground = if (isHeader) headerFg else cellFg
            }
            label
        } else {
            // Data cell → auto-wrapping JBTextArea
            val ta = JBTextArea(text).apply {
                isEditable = false
                isFocusable = false
                lineWrap = true
                wrapStyleWord = true
                font = this.font.deriveFont(Font.PLAIN, 12f)
                foreground = cellFg
                background = Color(0, 0, 0, 0)
                isOpaque = false
                border = EmptyBorder(0, 0, 0, 0)
                margin = java.awt.Insets(0, 0, 0, 0)
            }
            ta
        }

        return object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (isHeader) headerBg else cellBg
                g2.fillRect(0, 0, width, height)

                // If it is a marker cell, draw the colored marker
                if (isMarker && !isHeader) {
                    drawMarker(g2, text)
                }
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = cellPadding
            add(contentComponent, BorderLayout.CENTER)
            // Store the row/column position for paintComponent grid lines
            putClientProperty("row", row)
            putClientProperty("col", col)
        }
    }

    /** Draws the colored ✓ / ✕ centered inside the cell. */
    private fun drawMarker(g2: Graphics2D, text: String) {
        val marker = text.trim()
        val isGreen = marker in setOf("✓", "✔", "✅")
        val isRed = marker in setOf("✕", "×", "❌")
        if (!isGreen && !isRed) return

        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.font = g2.font.deriveFont(Font.BOLD, 16f)
        val fm = g2.fontMetrics
        val display = if (isGreen) "✓" else "✕"
        val x = (width - fm.stringWidth(display)) / 2
        val y = (height + fm.ascent) / 2 - 2
        g2.color = if (isGreen) Color(0x43A047) else Color(0xE53935)
        g2.drawString(display, x, y)
    }

    /** Formats header/simple cell text: wraps colored markers in HTML. */
    private fun formatCellText(text: String): String {
        var r = text
            .replace("✕", "<font color='#E53935'>✕</font>")
            .replace("×", "<font color='#E53935'>×</font>")
            .replace("✓", "<font color='#43A047'>✓</font>")
            .replace("✔", "<font color='#43A047'>✔</font>")
            .replace("✅", "<font color='#43A047'>✅</font>")
            .replace("❌", "<font color='#E53935'>❌</font>")
        if (r != text) {
            r = "<html><div style='text-align:center'>$r</div></html>"
        }
        return r
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.dispose()
        super.paintComponent(g)
    }

    override fun paintChildren(g: Graphics) {
        super.paintChildren(g)
        // Draw grid lines after child components are painted (overlaid on top of the children)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = gridColor

        // Collect the positions of all child components
        val cells = mutableMapOf<Pair<Int, Int>, Component>() // (row, col) -> component
        var maxRow = 0
        var maxCol = 0
        for (comp in components) {
            val jc = comp as? javax.swing.JComponent ?: continue
            val r = jc.getClientProperty("row") as? Int ?: continue
            val c = jc.getClientProperty("col") as? Int ?: continue
            cells[Pair(r, c)] = comp
            maxRow = maxOf(maxRow, r)
            maxCol = maxOf(maxCol, c)
        }
        if (cells.isEmpty()) {
            g2.dispose()
            return
        }

        // Collect by row and draw horizontal lines
        for (row in 0..maxRow) {
            // Find the leftmost and rightmost cells in this row
            var leftX = Int.MAX_VALUE
            var rightX = 0
            var hasCells = false
            for (col in 0..maxCol) {
                val comp = cells[Pair(row, col)] ?: continue
                hasCells = true
                leftX = minOf(leftX, comp.x)
                rightX = maxOf(rightX, comp.x + comp.width)
            }
            if (!hasCells) continue
            // Draw a horizontal line at the top of the row (skip the first row's top because the table itself may have rounded corners)
            if (row > 0) {
                g2.drawLine(leftX, cells[Pair(row, 0)]!!.y, rightX, cells[Pair(row, 0)]!!.y)
            }
            // Draw a horizontal line at the bottom of the row
            // Use any component in this row
            val anyComp = cells.entries.first { it.key.first == row }.value
            g2.drawLine(leftX, anyComp.y + anyComp.height, rightX, anyComp.y + anyComp.height)
        }

        // Collect by column and draw vertical lines
        for (col in 0..maxCol) {
            var topY = Int.MAX_VALUE
            var bottomY = 0
            var hasCells = false
            for (row in 0..maxRow) {
                val comp = cells[Pair(row, col)] ?: continue
                hasCells = true
                topY = minOf(topY, comp.y)
                bottomY = maxOf(bottomY, comp.y + comp.height)
            }
            if (!hasCells) continue
            // Column left edge (skip the first column's left edge)
            if (col > 0) {
                g2.drawLine(cells[Pair(0, col)]!!.x, topY, cells[Pair(0, col)]!!.x, bottomY)
            }
            // Column right edge
            val anyComp = cells.entries.first { it.key.second == col }.value
            g2.drawLine(anyComp.x + anyComp.width, topY, anyComp.x + anyComp.width, bottomY)
        }

        g2.dispose()
    }
}
