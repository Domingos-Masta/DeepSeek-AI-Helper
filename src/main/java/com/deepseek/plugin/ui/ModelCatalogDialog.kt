package com.deepseek.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * Model catalog dialog — shows the list of models supported by each provider.
 * Standalone dialog that does not block the user from filling in other settings.
 */
class ModelCatalogDialog : DialogWrapper(true) {

    private data class ModelEntry(
        val provider: String,
        val providerDisplay: String,
        val modelId: String,
        val notes: String
    )

    private val models = listOf(
        // DeepSeek
        ModelEntry("deepseek", "DeepSeek", "deepseek-v4-flash", "Fast coding, supports FIM code completion (default)."),
        ModelEntry("deepseek", "DeepSeek", "deepseek-v4-pro", "Deep reasoning/planning, suited to the Planner role."),
        // Agnes
        ModelEntry("agnes", "Agnes 2.5 Flash", "agnes-2.5-flash", "Lightweight analysis/review, suited to intent confirmation and Reviewer."),
        // NVIDIA
        ModelEntry("nvidia", "NVIDIA NIM", "z-ai/glm-5.2", "GLM-5.2 has strong Chinese comprehension, suited to requirements analysis."),
        ModelEntry("nvidia", "NVIDIA NIM", "minimaxai/minimax-m3", "MiniMax M3 balanced reasoning, suited to code review."),
        ModelEntry("nvidia", "NVIDIA NIM", "stepfun-ai/step-3.7-flash", "StepFun Flash, fast with low cost."),
        // StepFun (image parsing)
        ModelEntry("stepfun", "StepFun", "step-1o-turbo-vision", "Multimodal vision parsing (dedicated to image parsing)."),
        // OpenRouter
        ModelEntry("openrouter", "OpenRouter", "inclusionai/ling-3.0-flash:free", "Ling 3.0 Flash free version, suited to everyday conversation and code."),
        // Zhipu AI
        ModelEntry("zhipu", "BigModel", "glm-4", "GLM-4 general model, suited to everyday conversation and code."),
        ModelEntry("zhipu", "BigModel", "glm-4-flash", "GLM-4-Flash lightweight fast version."),
        ModelEntry("zhipu", "BigModel", "glm-4v", "GLM-4V multimodal vision version."),
        // Claude (Anthropic)
        ModelEntry("anthropic", "Claude (Anthropic)", "claude-sonnet-4-5", "Claude Sonnet 4.5 balanced performance, suited to coding and conversation."),
        ModelEntry("anthropic", "Claude (Anthropic)", "claude-opus-4-5", "Claude Opus 4.5 strongest reasoning, suited to complex tasks."),
        ModelEntry("anthropic", "Claude (Anthropic)", "claude-3-5-haiku-20241022", "Haiku lightweight and fast, low cost."),
        // Codex (OpenAI)
        ModelEntry("codex", "Codex (OpenAI)", "gpt-5.2-codex", "Default Codex model, suited to coding and Agent tasks."),
        ModelEntry("codex", "Codex (OpenAI)", "gpt-5.1-codex", "Iterative Codex version with stronger reasoning."),
        ModelEntry("codex", "Codex (OpenAI)", "gpt-5-codex", "Codex standard edition."),
        ModelEntry("codex", "Codex (OpenAI)", "gpt-4o", "General multimodal model."),
        ModelEntry("openrouter", "OpenRouter", "poolside/laguna-xs-2.1:free", "Poolside Laguna XS free version, suited to code generation.")
    )

    init {
        title = "Model Catalog"
        isModal = false  // non-modal, does not block the user from filling in other information
        init()
    }

    override fun createCenterPanel(): JComponent {
        val columnNames = arrayOf("Provider", "Model ID", "Notes")
        val data = models.map { arrayOf(it.providerDisplay, it.modelId, it.notes) }.toTypedArray()

        val tableModel = object : DefaultTableModel(data, columnNames) {
            override fun isCellEditable(row: Int, column: Int) = false
        }

        val table = JTable(tableModel).apply {
            setShowGrid(true)
            gridColor = JBColor(0xD0D0D0, 0x4A4A4A)
            rowHeight = 28
            font = JBUI.Fonts.label()
            getColumnModel().getColumn(0).preferredWidth = 140
            getColumnModel().getColumn(1).preferredWidth = 220
            getColumnModel().getColumn(2).preferredWidth = 180
            autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS

            // Header rendering
            tableHeader.font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD)
            tableHeader.background = JBColor(0xF0F0F0, 0x3C3C3C)

            // Alternating row colors
            val alternateColor = JBColor(0xF5F5F5, 0x2A2A2A)
            setDefaultRenderer(String::class.java, object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?,
                    isSelected: Boolean, hasFocus: Boolean,
                    row: Int, column: Int
                ): java.awt.Component {
                    val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
                    if (!isSelected) {
                        comp.background = if (row % 2 == 0) table.background else alternateColor
                    }
                    border = JBUI.Borders.empty(2, 8, 2, 8)
                    return comp
                }
            })
        }

        val scrollPane = JBScrollPane(table).apply {
            preferredSize = Dimension(520, 300)
            border = JBUI.Borders.empty(8)
        }

        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 4, 4, 4)
            add(scrollPane)
        }
        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction.apply { putValue(Action.NAME, "Close") })
    }
}
