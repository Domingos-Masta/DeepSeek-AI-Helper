package com.deepseek.plugin.ui

import com.deepseek.plugin.settings.SkillData
import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.settings.SkillStore
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*
import javax.swing.border.CompoundBorder

/**
 * A full-coverage settings panel that manages AI skill files.
 *
 * This panel replaces the entire chat area (messages + input) when active.
 * Users can upload, enable/disable, view, and delete skill files that provide
 * constraints and guidance for the AI model.
 *
 * @param project   The current IntelliJ project.
 * @param onClose   Called when the user closes this settings panel to return to chat.
 */
class SkillSettingsPanel(
    private val project: Project,
    private val onClose: () -> Unit,
    /** When false, the header bar (title + hint + close button) is hidden.
     *  Used when this panel is embedded inside [UnifiedSettingsPanel]. */
    private val showHeader: Boolean = true
) : JPanel(BorderLayout()) {

    companion object {
        /** Maximum size of a single skill file: 50 KB. */
        private const val MAX_SKILL_FILE_SIZE = 50 * 1024L
        /** Supported skill file extensions. */
        private val ALLOWED_EXTENSIONS = setOf("md", "txt", "yaml", "yml", "json")
    }

    private val skillStore = SkillStore(project.basePath)
    private val skills = skillStore.load()
    private val skillListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    init {
        background = JBColor(Color(0xF0F0F0), Color(0x3C3F41))
        border = JBUI.Borders.empty()

        // ── Header bar with title, hint link, and close button (conditional) ──
        if (showHeader) {
            val headerPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = CompoundBorder(
                    JBUI.Borders.empty(8, 12, 8, 12),
                    JBUI.Borders.customLineBottom(JBColor(Color(0xD0D0D0), Color(0x555555)))
                )
                maximumSize = Dimension(Short.MAX_VALUE.toInt(), 80)
            }

            // Title row
            val titleRow = JPanel(BorderLayout()).apply {
                isOpaque = false
                maximumSize = Dimension(Short.MAX_VALUE.toInt(), 30)
            }

            val titleLabel = JLabel(I18n.tr("skill.title")).apply {
                font = JBUI.Fonts.label().asBold()
                foreground = JBColor(Color(0x1A1A1A), Color(0xBBBBBB))
            }
            titleRow.add(titleLabel, BorderLayout.WEST)

            val closeButton = createToolbarButton(
                icon = AllIcons.Actions.Close,
                tooltip = I18n.tr("skill.back"),
                tooltipKey = "skill.back",
                onClick = { onClose() }
            )
            titleRow.add(closeButton, BorderLayout.EAST)

            headerPanel.add(titleRow)

            // Hint row — clickable link to community skill library
            val hintRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 2)).apply {
                isOpaque = false
                maximumSize = Dimension(Short.MAX_VALUE.toInt(), 28)
            }

            val hintLabel = JLabel(
                I18n.tr("skill.community.hint"),
            ).apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        BrowserUtil.browse("https://github.com/lvesDi005/useful-skill")
                    }
                })
            }
            hintRow.add(hintLabel)

            headerPanel.add(hintRow)

            add(headerPanel, BorderLayout.NORTH)
        }

        // ── Center: skill list in a scroll pane ──
        val viewWrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(skillListPanel)
        }
        val scrollPane = JBScrollPane(viewWrapper).apply {
            isOpaque = false
            viewport.isOpaque = false
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        val centerWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12, 8, 12)
            add(scrollPane, BorderLayout.CENTER)
        }
        add(centerWrapper, BorderLayout.CENTER)

        // ── Bottom: centered upload hint + button ──
        val bottomPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(6, 12, 12, 12)
            alignmentX = Component.CENTER_ALIGNMENT
        }

        // Drag-and-drop support on the whole panel
        setupDropTarget(this)

        // Hint text — centered above the button
        val dropHintLabel = JLabel(I18n.tr("skill.drop.hint"), SwingConstants.CENTER).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor(Color.GRAY, Color(0x888888))
            alignmentX = Component.CENTER_ALIGNMENT
        }
        bottomPanel.add(dropHintLabel)
        bottomPanel.add(Box.createVerticalStrut(6))

        // Upload button — centered
        val uploadButton = JButton(I18n.tr("skill.upload"), AllIcons.General.Add).apply {
            addActionListener { chooseAndUploadSkill() }
            font = JBUI.Fonts.label()
            alignmentX = Component.CENTER_ALIGNMENT
        }
        bottomPanel.add(uploadButton)

        add(bottomPanel, BorderLayout.SOUTH)

        // ── Render existing skills ──
        refreshSkillList()
    }

    /**
     * Set up drag-and-drop for uploading skill files.
     */
    private fun setupDropTarget(panel: JPanel) {
        val dropTarget = DropTarget()
        panel.dropTarget = dropTarget

        dropTarget.addDropTargetListener(object : DropTargetAdapter() {
            override fun drop(event: DropTargetDropEvent) {
                event.acceptDrop(DnDConstants.ACTION_COPY)
                val transferable = event.transferable
                if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    @Suppress("UNCHECKED_CAST")
                    val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                    for (file in files) {
                        // Check the extension for drag-and-drop paths too (consistent with the file chooser)
                        val ext = file.extension?.lowercase()
                        if (ext == null || ext !in ALLOWED_EXTENSIONS) {
                            Messages.showWarningDialog(
                I18n.tr("skill.file.unsupported", file.name, ALLOWED_EXTENSIONS.joinToString(", ") { ".$it" }),
                I18n.tr("skill.file.unsupported.title")
            )
                            continue
                        }
                        importSkillFromFile(file)
                    }
                }
                event.dropComplete(true)
            }

            override fun dragOver(event: DropTargetDragEvent) {
                event.acceptDrag(DnDConstants.ACTION_COPY)
            }
        })
    }

    /**
     * Open a file chooser for the user to select skill files.
     */
    private fun chooseAndUploadSkill() {
        val fileChooser = JFileChooser().apply {
            dialogTitle = I18n.tr("skill.choose.title")
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = true
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                I18n.tr("skill.file.filter"),
                *ALLOWED_EXTENSIONS.toTypedArray()
            )
        }

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (file in fileChooser.selectedFiles) {
                importSkillFromFile(file)
            }
        }
    }

    /**
     * Import a skill file: read its content and add to the list.
     */
    private fun importSkillFromFile(file: File) {
        if (!file.exists() || !file.isFile) return

        // Check file size
        if (file.length() > MAX_SKILL_FILE_SIZE) {
            Messages.showWarningDialog(
                I18n.tr("skill.file.too.large", file.name, file.length() / 1024),
                I18n.tr("skill.file.too.large.title")
            )
            return
        }

        // Check for duplicate name
        val name = file.nameWithoutExtension
        if (skills.any { it.name == name }) {
            Messages.showWarningDialog(
                I18n.tr("skill.duplicate.warning", name),
                I18n.tr("skill.duplicate.title")
            )
            return
        }

        val content = try {
            file.readText(Charsets.UTF_8).trim()
        } catch (e: Exception) {
            Messages.showErrorDialog(I18n.tr("skill.read.error", e.message), I18n.tr("skill.read.error.title"))
            return
        }

        if (content.isEmpty()) {
            Messages.showWarningDialog(I18n.tr("skill.empty.file", file.name), I18n.tr("skill.empty.file.title"))
            return
        }

        val skill = SkillData(
            name = name,
            content = content,
            enabled = true,
            filePath = file.absolutePath,
            tags = emptyList()
        )
        skills.add(skill)
        saveAndRefresh()

        // Prompt for tags after a successful upload
        showTagEditor(skills.lastIndex)
    }

    /**
     * Shows the tag editor dialog (pops up automatically after upload / opened manually by clicking the tag).
     */
    private fun showTagEditor(skillIndex: Int) {
        if (skillIndex < 0 || skillIndex >= skills.size) return
        val skill = skills[skillIndex]
        val currentTags = skill.tags?.joinToString(", ") ?: ""
        val result = Messages.showInputDialog(
            this,
            I18n.tr("skill.tag.editor.message", skill.name),
            I18n.tr("skill.tag.editor.title", skill.name),
            Messages.getQuestionIcon(),
            currentTags,
            null
        )
        if (result != null) {
            val newTags = result.split(",", "，")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            skills[skillIndex] = skill.copy(tags = newTags)
            saveAndRefresh()
        }
    }

    /**
     * Add a new skill card to the list panel.
     */
    private fun refreshSkillList() {
        skillListPanel.removeAll()

        if (skills.isEmpty()) {
            val emptyLabel = JLabel(I18n.tr("skill.empty"), SwingConstants.CENTER).apply {
                font = JBUI.Fonts.label()
                foreground = JBColor(Color(0x888888), Color(0x777777))
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(20, 0)
            }
            skillListPanel.add(emptyLabel)
        } else {
            for ((index, skill) in skills.withIndex()) {
                skillListPanel.add(createSkillCard(index, skill))
                skillListPanel.add(Box.createVerticalStrut(6))
            }
        }

        skillListPanel.revalidate()
        skillListPanel.repaint()
    }

    /**
     * Create a card UI for a single skill item.
     */
    private fun createSkillCard(index: Int, skill: SkillData): JPanel {
        val card = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(Color(0xFFFFFF), Color(0x4A4A4A))
            border = CompoundBorder(
                JBUI.Borders.customLine(JBColor(Color(0xD0D0D0), Color(0x555555)), 1),
                JBUI.Borders.empty(8, 10)
            )
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 72)
        }

        // Enable/disable checkbox
        val enabledCheckBox = JCheckBox("", skill.enabled).apply {
            isOpaque = false
            addActionListener {
                skills[index] = skills[index].copy(enabled = isSelected)
                saveAndRefresh()
            }
        }

        // Skill name
        val nameLabel = JLabel(skill.name).apply {
            font = JBUI.Fonts.label().asBold()
            foreground = JBColor(Color(0x1A1A1A), Color(0xBBBBBB))
        }

        // Preview of content (first line)
        val previewLine = skill.content.lines().firstOrNull()?.take(60) ?: ""
        val previewLabel = JLabel(if (previewLine.isNotEmpty()) previewLine else "(empty)").apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor(Color(0x888888), Color(0x777777))
        }

        // Tags label (clickable) — fixed maximum display width
        val tagText = if (skill.tags?.isNotEmpty() == true) {
            "🏷️ " + (skill.tags?.joinToString(", ") ?: "")
        } else "🏷️ Click to add tags"
        val displayText = if (tagText.length > 50) tagText.take(47) + "..." else tagText
        val tagsLabel = JLabel(displayText).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor(Color(0x6666AA), Color(0x9999CC))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    showTagEditor(index)
                }
            })
        }
        // Wrap the tag row in a FlowLayout so it is not stretched by GridBagLayout weightx
        val tagsWrapper = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(tagsLabel)
        }

        val leftPanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            val gbc = GridBagConstraints()
            gbc.anchor = GridBagConstraints.WEST
            gbc.insets = JBUI.insets(0, 0, 0, 0)

            gbc.gridx = 0
            gbc.gridy = 0
            gbc.weightx = 0.0
            add(enabledCheckBox, gbc)

            gbc.gridx = 1
            gbc.gridy = 0
            gbc.weightx = 1.0
            gbc.insets = JBUI.insets(0, 4, 0, 0)
            add(nameLabel, gbc)

            gbc.gridx = 1
            gbc.gridy = 1
            gbc.insets = JBUI.insets(0, 4, 0, 0)
            add(previewLabel, gbc)

            gbc.gridx = 1
            gbc.gridy = 2
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            gbc.insets = JBUI.insets(0, 4, 0, 0)
            add(tagsWrapper, gbc)
        }

        card.add(leftPanel, BorderLayout.CENTER)

        // View button
        val viewButton = createToolbarButton(
            icon = AllIcons.Actions.Preview,
            tooltip = I18n.tr("skill.preview"),
            tooltipKey = "skill.preview",
            size = 28,
            onClick = { showSkillPreview(skill) }
        )

        // Delete button
        val deleteButton = createToolbarButton(
            icon = AllIcons.Actions.GC,
            tooltip = I18n.tr("skill.delete"),
            tooltipKey = "skill.delete",
            size = 28,
            onClick = {
                val confirm = Messages.showYesNoDialog(
                    I18n.tr("skill.delete.confirm", skill.name),
                    I18n.tr("skill.delete"),
                    I18n.tr("skill.delete.yes"),
                    I18n.tr("skill.delete.no"),
                    Messages.getQuestionIcon()
                )
                if (confirm == Messages.YES) {
                    skills.removeAt(index)
                    saveAndRefresh()
                }
            }
        )

        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
            isOpaque = false
            add(viewButton)
            add(deleteButton)
        }
        card.add(rightPanel, BorderLayout.EAST)

        return card
    }

    /**
     * Show a dialog with the full skill content (read-only).
     */
    private fun showSkillPreview(skill: SkillData) {
        val textArea = JBTextArea(skill.content).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("Monospaced", 12)
            margin = JBUI.insets(8)
        }
        val scrollPane = JBScrollPane(textArea).apply {
            preferredSize = Dimension(500, 400)
            border = JBUI.Borders.empty()
        }

        val panel = JPanel(BorderLayout()).apply {
            add(scrollPane, BorderLayout.CENTER)
        }

        val dialog = object : JDialog() {
            override fun dispose() {
                super.dispose()
            }
        }
        dialog.title = I18n.tr("skill.preview") + " - ${skill.name}"
        dialog.contentPane = panel
        dialog.pack()
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
    }

    /**
     * Save skills to disk and refresh the UI list.
     */
    private fun saveAndRefresh() {
        skillStore.save(skills)
        refreshSkillList()
    }

    /**
     * Get all currently enabled skills' content as a combined string,
     * formatted for injection into the system prompt.
     */
    /**
     * Get relevant enabled skills' content for injection into the system prompt.
     *
     * @param userMessage The user's current question. Only relevant skills are injected,
     *                    filtered by tags/keywords; an empty string injects no skills
     *                    (and consumes no tokens).
     * @return Formatted skill Markdown, or an empty string when no skills are relevant.
     */
    fun getEnabledSkillsContent(userMessage: String = ""): String {
        val enabled = skills.filter { it.enabled }
        if (enabled.isEmpty()) return ""

        // Do not inject any skills when userMessage is empty
        if (userMessage.isBlank()) return ""

        // Filter on demand: keep only the skills relevant to the user's question
        val relevant = filterRelevantSkills(enabled, userMessage)
        if (relevant.isEmpty()) return ""

        return buildString {
            appendLine()
            appendLine("## Currently loaded skills (Skill)")
            appendLine("The following is your currently loaded skill list. When the user asks what skills you have, list the names and purposes of these skills truthfully.")
            appendLine()
            for (skill in relevant) {
                val tagInfo = if (skill.tags?.isNotEmpty() == true) " [${skill.tags?.joinToString(", ") ?: ""}]" else ""
                appendLine("### Skill name: ${skill.name}$tagInfo")
                appendLine(skill.content)
                appendLine()
            }
        }
    }

    /**
     * Filters skills by the user's question.
     * Strategy: tag-first matching → fall back to name/content keyword matching when no tag matches.
     */
    private fun filterRelevantSkills(skills: List<SkillData>, userMessage: String): List<SkillData> {
        val queryLower = userMessage.lowercase()

        // Strategy 1: tag matching
        val tagMatched = skills.filter { skill ->
            val tags = skill.tags ?: return@filter false
            tags.isNotEmpty() && tags.any { tag ->
                queryLower.contains(tag.lowercase())
            }
        }
        if (tagMatched.isNotEmpty()) return tagMatched

        // Strategy 2: fall back to keyword matching (skill name + content)
        val keywords = extractKeywordsFromQuery(queryLower)
        if (keywords.isEmpty()) return emptyList()  // no keywords, inject nothing

        return skills.filter { skill ->
            val searchSpace = skill.name.lowercase() + " " + skill.content.lowercase()
            keywords.any { searchSpace.contains(it) }
        }
    }

    /**
     * Extracts search keywords from the user query.
     */
    private fun extractKeywordsFromQuery(queryLower: String): List<String> {
        val keywords = mutableSetOf<String>()

        // Extract CamelCase words (class/function names)
        val camelPattern = Regex("""[a-z][a-zA-Z0-9]{2,}""")
        keywords.addAll(camelPattern.findAll(queryLower).map { it.value.lowercase() })

        // Extract common technical keywords
        val techKeywords = listOf(
            "java", "kotlin", "spring", "mybatis", "jpa", "hibernate",
            "redis", "kafka", "rabbitmq", "docker", "kubernetes",
            "sql", "nosql", "mongodb", "elasticsearch",
            "rest", "api", "graphql", "grpc",
            "config", "security", "auth", "cache", "logging",
            "test", "unit", "integration", "deploy", "ci", "cd"
        )
        for (kw in techKeywords) {
            if (queryLower.contains(kw)) keywords.add(kw)
        }

        return keywords.toList().filter { it.length >= 2 }
    }
}
