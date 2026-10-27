package com.deepseek.plugin.chat



import com.deepseek.plugin.ui.PluginTheme
import com.deepseek.plugin.api.ChatContentPart
import com.deepseek.plugin.api.ChatMessage

import com.deepseek.plugin.api.ChatSession

import com.deepseek.plugin.api.DOMAIN_RESTRICTION_PROMPT

import com.deepseek.plugin.api.DeepSeekApiClient

import com.deepseek.plugin.api.DeepSeekPluginException
import com.deepseek.plugin.api.ApiException

import com.deepseek.plugin.api.LlmProviderRegistry

import com.deepseek.plugin.api.StepFunApiClient

import com.deepseek.plugin.api.Usage

import com.deepseek.plugin.chat.ChatState

import com.deepseek.plugin.cli.CliAgentProcess

import com.deepseek.plugin.cli.CliAttachmentBundle

import com.deepseek.plugin.cli.CliChangeTracker

import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.i18n.ContentFontChangeListener
import com.deepseek.plugin.i18n.I18nTopics
import com.deepseek.plugin.i18n.LanguageChangeListener
import com.deepseek.plugin.i18n.ThemeChangeListener

import com.deepseek.plugin.context.ChatContextComposer
import com.deepseek.plugin.context.ProjectContextProvider

import com.deepseek.plugin.context.RagRetriever

import com.deepseek.plugin.context.SearchCoordinator

import com.deepseek.plugin.search.AgenticSearch

import com.deepseek.plugin.di.AppModule
import com.deepseek.plugin.access.FileAccessService

import com.deepseek.plugin.search.ToolUseEngine

import com.deepseek.plugin.store.SessionStore

import com.deepseek.plugin.settings.DeepSeekSettings
import com.deepseek.plugin.settings.toSnapshot

import com.deepseek.plugin.ui.AttachedFile

import com.deepseek.plugin.ui.ChangeManagementPanel

import com.deepseek.plugin.ui.ChatInputBar

import com.deepseek.plugin.ui.ChatToolbar

import com.deepseek.plugin.ui.CodeBlockCard
import com.deepseek.plugin.ui.MarkdownRenderer

import com.deepseek.plugin.ui.FileAttachmentPreview

import com.deepseek.plugin.ui.HistoryDialog

import com.deepseek.plugin.ui.MessageBubble

import com.deepseek.plugin.ui.ResponseSegment

import com.deepseek.plugin.ui.SelectedCodePreview

import com.deepseek.plugin.ui.SessionBar

import com.deepseek.plugin.ui.MarqueeLabel

import com.deepseek.plugin.ui.UnifiedSettingsPanel

import com.deepseek.plugin.ui.TranslateDialog

import com.deepseek.plugin.ui.WelcomePanel

import com.intellij.icons.AllIcons

import com.intellij.ide.BrowserUtil

import com.intellij.openapi.Disposable

import com.intellij.openapi.ui.MessageType

import com.intellij.openapi.wm.ToolWindowManager

import com.intellij.openapi.application.ApplicationManager

import com.intellij.openapi.command.WriteCommandAction

import com.intellij.openapi.editor.EditorFactory

import com.intellij.openapi.editor.event.EditorFactoryEvent

import com.intellij.openapi.editor.event.EditorFactoryListener

import com.intellij.openapi.editor.event.SelectionEvent

import com.intellij.openapi.editor.event.SelectionListener

import com.intellij.openapi.fileEditor.FileEditorManager

import com.intellij.openapi.progress.ProgressManager

import com.intellij.openapi.project.Project

import com.intellij.openapi.roots.ProjectRootManager

import com.intellij.openapi.vfs.VirtualFile

import com.intellij.ui.JBColor

import com.intellij.ui.components.*

import com.intellij.util.ui.JBUI

import kotlin.math.ceil

import okhttp3.Request

import okhttp3.sse.EventSource

import java.awt.BasicStroke

import java.awt.BorderLayout

import java.awt.CardLayout

import java.awt.Color

import java.awt.Component

import java.awt.Cursor

import java.awt.Dimension

import java.awt.FlowLayout

import java.awt.Font

import java.awt.Graphics

import java.awt.Graphics2D

import java.awt.RenderingHints

import java.awt.GridBagConstraints

import java.awt.GridBagLayout

import java.awt.event.ComponentAdapter

import java.awt.event.ComponentEvent

import java.awt.event.MouseAdapter

import java.awt.event.MouseEvent

import java.util.concurrent.atomic.AtomicReference

import javax.swing.*

import javax.swing.text.DefaultCaret

import javax.swing.text.DefaultHighlighter

import javax.swing.Timer



enum class ChatMode {

    Q_A, Q_A_SCAN, AGENT, CLI_AGENT

}



class ChatPanel(private val project: Project) : JPanel(CardLayout()), Disposable {



    companion object {

        // Context search limits

        private const val MAX_SEARCH_KEYWORDS = 5

        private const val MAX_FILES_PER_KEYWORD = 3

        private const val MAX_FILE_SIZE = 15000

        private const val MAX_CONTEXT_LINES = 300

        // Phase 0 retry

        private const val MAX_PHASE0_RETRIES = 1

        /** The currently active ChatPanel instance, used by external Actions to push content into the chat panel */

        @JvmStatic

        var currentInstance: ChatPanel? = null

            private set

    }



    private val module = AppModule.get(project)

    internal val client: DeepSeekApiClient = module.apiClient

    private val stepFunClient: StepFunApiClient = module.stepFunClient

    private val contextProvider = module.contextProvider

    private val ragRetriever = module.ragRetriever

    // Q&A retrieval is deliberately restricted to local read-only grep/glob/read tools.
    private val searchCoordinator = module.searchCoordinator

    private val agenticSearch = module.agenticSearch

    /** Unified file access for scanning/searching, with automatic fallback. */
    private val fileAccess: FileAccessService = module.fileAccess

    private val sessionStore = module.sessionStore

    private val sessions = mutableListOf<ChatSession>()

    private var currentSessionIndex = 0

    /** Thread-safe chat state machine — replaces isStreaming + currentEventSource + streamBuffer + streamingBubble + … */

    internal val chatState = AtomicReference<ChatState>(ChatState.Idle)

    private var sessionCounter = 1

    private var currentMode = ChatMode.Q_A_SCAN

    /** Mode dropdown reference (used for the dynamic label of the fourth mode "Claude/Codex") */

    private var modeDropdown: JComboBox<String>? = null

    /** Suppress ActionEvent while the dropdown label is being refreshed, to avoid accidentally switching modes */

    private var suppressingModeDropdown = false



    /** Callback for the intent-confirmation phase — non-null while waiting for the user's additional notes */

    internal var pendingConfirmation: ((clarification: String) -> Unit)? = null



    /** Unified settings page — covers the whole plugin area, including the top icon navigation bar */

    internal val unifiedSettingsPanel: UnifiedSettingsPanel



    /** Change management panel — covers the whole plugin area */

    private val changeManagementPanel: ChangeManagementPanel



    internal val fileOperationExecutor = FileOperationExecutor(

        project = project,

        addMessageLabel = { addMessageLabel(it) },

        getSourceRootPaths = { getSourceRootPaths() }

    )



    private var pipelineTotalTokens: Int = 0

    private var phase0RetryCount: Int = 0



    /** Default / minimum / maximum height of the input area */

    private val defaultInputHeight = 160

    private val minInputHeight = 140

    private val maxInputHeight = 600



    /** P3: virtualized scrolling — index of the first visible message in the current conversation (counted from the end) */

    private var visibleStartIndex = 0

    /** P3: virtualized scrolling — maximum number of messages rendered per batch */

    private val VISIBLE_BATCH_SIZE = 30



    /** Debounced save timer: multiple calls within 500ms trigger only one disk write */

    private val saveTimer = Timer(500) {

        SwingUtilities.invokeLater { doSaveSessions() }

    }.apply { isRepeats = false }



    // ── Thinking animation ──

    private val spinnerChars = listOf("◐", "◓", "◑", "◒")

    /** Rotating step messages shown while the model is reasoning / generating. */
    private val thinkingStepKeys = listOf(
        "chat.step.analyzing",
        "chat.step.searching",
        "chat.step.reasoning",
        "chat.step.generating",
        "chat.step.refining"
    )

    private var thinkingTimer: Timer? = null

    private var spinnerIndex = 0



    // ── Selected code preview state ──

    internal data class SelectedContext(

        val fileName: String,

        val startLine: Int,

        val endLine: Int,

        val snippet: String

    )



    private var selectedContext: SelectedContext? = null

    private val selectedCodePreviewPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {

        isVisible = false

    }



    // ── Message tracking for anchor dots ──

    private data class MessageEntry(

        val panel: JPanel,                  // the user message panel

        val text: String,

        var responsePanel: JPanel? = null   // linked AI response, filled later

    )

    private val userMessages = mutableListOf<MessageEntry>()



    // ── File attachment state ──

    private val attachedFiles = mutableListOf<AttachedFile>()

    @Volatile
    private var isPreparingAttachments = false

    @Volatile
    private var attachmentPreparationGeneration = 0L

    @Volatile
    private var attachmentPreparationTask: java.util.concurrent.Future<*>? = null

    private var preparationCancelUi: (() -> Unit)? = null

    @Volatile
    private var activeCliAttachmentBundle: CliAttachmentBundle? = null

    private val fileAttachmentPanel = JPanel(BorderLayout()).apply {

        isVisible = false

    }



    // ── Streaming state (migrated to the ChatState state machine) ──

    /** Used on the EDT to determine whether streaming is active — driven by chatState */

    private val isStreaming: Boolean
        get() = chatState.get() is ChatState.Streaming || isPreparingAttachments



    // ── UI Components ──



    private val sessionComboBox = JComboBox<String>()



    /** Vertical panel that holds all rendered messages. */

    internal val messagesPanel = JPanel().apply {

        layout = GridBagLayout()

        // Leave 28px of space at the bottom so the last message is not clipped when scrolled to the end

        border = JBUI.Borders.empty(0, 0, 28, 0)

    }



    /** Transparent filler that expands to fill empty vertical space below messages. */

    private val verticalFiller = JPanel().apply { isOpaque = false }



    /** GridBagConstraints for a message row — fills cell completely, no vertical gaps. */

    private val fillWidthConstraints = GridBagConstraints().apply {

        fill = GridBagConstraints.BOTH

        weightx = 1.0

        gridwidth = GridBagConstraints.REMAINDER

    }



    private val autoScrollController = ChatAutoScrollController()

    private val messagesScrollPane = JBScrollPane(messagesPanel).apply {

        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_ALWAYS

        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER

        border = JBUI.Borders.empty()

    }.also { pane ->

        // ── Auto-scroll tracking: pause following when the user scrolls away from the bottom, resume when back at the bottom ──
        // Intent capture goes through the JViewport ChangeListener: wheel/trackpad/drag/keyboard all ultimately change the viewport position and trigger it;
        // Programmatic auto-scroll (settleOnce) is excluded via the programmaticScrollInProgress flag, so it is not mistaken for user input.

        val updateUserScrollIntent = {
            val vp = pane.viewport
            autoScrollController.onUserViewportChanged(vp.viewPosition.y, vp.extentSize.height, vp.viewSize.height)
        }
        pane.viewport.addChangeListener {
            if (!programmaticScrollInProgress) updateUserScrollIntent()
        }
        pane.verticalScrollBar.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent?) {
                updateUserScrollIntent()
                // If the user is back near the bottom after releasing: resume following immediately and reposition, so subsequent content is not stranded below the viewport
                if (autoScrollController.followsBottom) scrollToBottom()
            }
        })

        // ── On window resize: when following, re-layout and reset to the bottom so the scroll range does not lag ──

        pane.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                if (autoScrollController.followsBottom) scrollToBottom()
            }
        })

    }



    /**

     * Whether to auto-scroll to the bottom while the AI is streaming its response.

     * Set to false when the user scrolls up to view history (pauses following),

     * and set to true when the user scrolls to the bottom or sends a new message (resumes following).

     */

    /** Empty-state welcome panel shown when there are no messages. */

    private val welcomePanel = WelcomePanel()



    /** CardLayout container that switches between [welcomePanel] and [messagesScrollPane]. */

    private val messageContainer = JPanel(CardLayout()).apply {

        isOpaque = false

        add(welcomePanel, "welcome")

        add(messagesScrollPane, "messages")

    }



    /** Show the empty-state welcome panel and hide messages. */

    private fun showWelcome() {

        (messageContainer.layout as CardLayout).show(messageContainer, "welcome")

        welcomePanel.revalidate()

        welcomePanel.repaint()

    }



    /** Show the messages panel and hide the welcome background. */

    private fun showMessages() {

        (messageContainer.layout as CardLayout).show(messageContainer, "messages")

        messagesScrollPane.revalidate()

        messagesScrollPane.repaint()

    }



    /**
     * Language-switch live refresh: updates static text such as the send/stop buttons and the welcome panel.
     * Triggered by the [I18nTopics.LANGUAGE_CHANGED] event.
     */
    private fun refreshI18nTexts() {
        try {
            sendStopButton.text = if (isStreaming) I18n.tr("chat.stop.enter") else I18n.tr("chat.send.enter")
            sendStopButton.toolTipText = if (isStreaming) I18n.tr("chat.tooltip.stop") else I18n.tr("chat.tooltip.send")
            welcomePanel.refreshTexts()
            refreshTooltips()
        } catch (_: Exception) {
        }
    }

    /**
     * On language switch, walks the component tree and refreshes all tooltip text marked via [I18n.tooltip].
     */
    private fun refreshTooltips() {
        I18n.refreshTooltips(this)
    }

    /**
     * Font-switch live refresh: walks the message panel and updates the font size of all rendered message bodies.
     * Triggered by the [I18nTopics.CONTENT_FONT_CHANGED] event.
     */
    private fun refreshAllMessageFonts() {
        val size = DeepSeekSettings.instance.contentFontSize
        fun walk(c: java.awt.Component) {
            when (c) {
                is javax.swing.JTextArea -> if (!c.isEditable && !c.isFocusable) {
                    c.font = c.font.deriveFont(Font.PLAIN, size.toFloat())
                }
                is javax.swing.JEditorPane -> if (c.contentType.startsWith("text/html")) {
                    // The StyleSheet must be rebuilt (CSS font-size takes precedence over pane.font), otherwise the already-generated content does not change
                    MarkdownRenderer.refreshFont(c, size)
                }
            }
            if (c is java.awt.Container) {
                for (i in 0 until c.componentCount) walk(c.getComponent(i))
            }
        }
        walk(messagesPanel)
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    /**
     * Plugin-theme-switch live refresh: re-renders the current conversation messages to apply the new color scheme.
     * Triggered by the [I18nTopics.THEME_CHANGED] event.
     */
    private fun refreshThemeColors() {
        try {
            // Update the message panel background (fixed at build time; must be refreshed with the theme)
            val msgBg = PluginTheme.color(0xFFFFFF, 0x2B2B2B)
            messagesScrollPane.background = msgBg
            messagesPanel.background = msgBg
            messagesScrollPane.viewport.background = msgBg
            messagesScrollPane.viewport.isOpaque = true

            val session = currentSession()
            messagesPanel.removeAll()
            userMessages.clear()
            if (session.messages.isEmpty()) {
                showWelcome()
                return
            }
            showMessages()
            addMessageLabel("=== ${session.name} ===")
            val total = session.messages.size
            visibleStartIndex = maxOf(0, total - VISIBLE_BATCH_SIZE)
            renderMessageRange(session, visibleStartIndex, total)
            ensureMessagesFiller()
            messagesPanel.revalidate()
            messagesPanel.repaint()
        } catch (_: Exception) {
        }
    }



    internal val inputArea = AutoResizingTextArea(4, 0, project, { sendMessage() }, { isStreaming },

        onImagePasted = { file -> addFileAttachment(file) }

    )



    // ── Combined send/stop button ──



    internal val sendStopButton = object : JButton(I18n.tr("chat.send.enter")) {

        override fun paintComponent(g: Graphics) {

            val g2 = g.create() as Graphics2D

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val isStop = text?.contains(I18n.tr("chat.stop.enter").take(2), ignoreCase = true) == true

            g2.color = if (model.isRollover) {

                if (isStop) JBColor(0xE57373, 0xEF5350) else JBColor(0xE0E0E0, 0x4A4A4A)

            } else {

                if (isStop) JBColor(0xEF9A9A, 0xC62828) else Color(0, 0, 0, 0)

            }

            g2.fillRoundRect(0, 0, width, height, 8, 8)

            // Rounded border: light green when sending, light red when stopped

            g2.color = if (isStop) JBColor(0xEF9A9A, 0xE57373) else JBColor(0xA5D6A7, 0x66BB6A)

            g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)

            super.paintComponent(g)

            g2.dispose()

        }



        override fun setText(text: String?) {

            super.setText(text)

            // Auto-switch the icon based on the text

            val isStop = text?.contains(I18n.tr("chat.stop.enter").take(2), ignoreCase = true) == true

            icon = if (isStop) AllIcons.Actions.Suspend else AllIcons.Actions.Execute

        }

    }.apply {

        toolTipText = I18n.tr("chat.tooltip.send")

        addActionListener { if (isStreaming) stopStreaming() else sendMessage() }

        isOpaque = false

        isContentAreaFilled = false

        isBorderPainted = false

        isFocusPainted = false

        font = font.deriveFont(Font.BOLD, 12f)

    }



    internal val messageHistory: MutableList<ChatMessage>

        get() = currentSession().messages



    init {

        // ── Subscribe to language/font/theme switch events for live refresh ──
        project.messageBus.connect(this).apply {
            subscribe(I18nTopics.LANGUAGE_CHANGED, object : LanguageChangeListener {
                override fun languageChanged() {
                    refreshI18nTexts()
                }
            })
            subscribe(I18nTopics.CONTENT_FONT_CHANGED, object : ContentFontChangeListener {
                override fun fontChanged() {
                    refreshAllMessageFonts()
                }
            })
            subscribe(I18nTopics.THEME_CHANGED, object : ThemeChangeListener {
                override fun themeChanged() {
                    refreshThemeColors()
                }
            })
        }

        // ── load saved sessions ──

        val saved = sessionStore.load()

        if (saved != null) {

            sessions.addAll(saved.first)

            sessionCounter = saved.second

            for (s in sessions) {

                sessionComboBox.addItem(s.name)

            }

        } else {

            sessions.add(ChatSession(I18n.tr("chat.session.default.name")))

            sessionComboBox.addItem(I18n.tr("chat.session.default.name"))

        }

        sessionComboBox.addActionListener {

            val idx = sessionComboBox.selectedIndex

            if (idx >= 0 && idx < sessions.size && idx != currentSessionIndex) {

                switchToSession(idx)

            }

        }



        // ── Merge the toolbar + conversation bar into a single row ──

        // Left: [▼ Session 1] [+] [🗑 Clear current]

        // Right: [≡ History] [🕐 Change management] [⚙ Settings]

        val topBar = JPanel(BorderLayout()).apply {

            background = JBColor(Color(0xE8E8E8), Color(0x333333))

            isOpaque = true

            add(SessionBar(

                sessionComboBox = sessionComboBox,

                onNewSession = { createNewSession() },

                onClearCurrent = {

                    currentSession().messages.clear()

                    currentSession().totalTokens = 0

                    messagesPanel.removeAll()

                    messagesPanel.revalidate()

                    messagesPanel.repaint()

                    userMessages.clear()

                    showWelcome()

                    saveSessions()

                }

            ), BorderLayout.WEST)

            add(ChatToolbar(

                onShowHistory = { showHistoryDialog() },

                onShowSettings = { showSkillSettings() },

                onShowChangeManagement = { showChangeManagement() }

            ), BorderLayout.EAST)

        }



        // Wrap messages container — anchor dots are embedded per-message

        val messagesWithNav = JPanel(BorderLayout()).apply {

            isOpaque = false

            add(messageContainer, BorderLayout.CENTER)

        }



        // Set the chat area background to be theme-adaptive

        messagesScrollPane.viewport.apply {

            isOpaque = true

            background = PluginTheme.color(0xFFFFFF, 0x2B2B2B)

        }

        messagesScrollPane.background = PluginTheme.color(0xFFFFFF, 0x2B2B2B)

        messagesPanel.background = PluginTheme.color(0xFFFFFF, 0x2B2B2B)

        messagesPanel.isOpaque = true



        // ── Chat input bar ──

        val inputScrollPane = JBScrollPane(inputArea).apply {

            isOpaque = false

            viewport.isOpaque = false

            border = JBUI.Borders.empty()

        }

        val modeDropdownValue = createModeDropdown()

        modeDropdown = modeDropdownValue as JComboBox<String>

        val chatInputBar = ChatInputBar(

            inputScrollPane = inputScrollPane,

            selectedCodePanel = selectedCodePreviewPanel,

            fileAttachmentPanel = fileAttachmentPanel,

            modeSelector = modeDropdownValue,

            settingsButton = createSettingsButton(),

            uploadButton = createUploadButton(),

            translateButton = createTranslateButton(),

            sendStopButton = sendStopButton

        )



        // ── Input area (just ChatInputBar; tabBarArea moved to NORTH) ──

        val inputAreaPanel = JPanel().apply {

            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            isOpaque = false

            preferredSize = Dimension(Short.MAX_VALUE.toInt(), defaultInputHeight)

            add(chatInputBar)

        }



        // The input box drag handle directly adjusts the inputAreaPanel height

        chatInputBar.onResizeRequest = { height ->

            if (height == Int.MAX_VALUE) {

                // Double-click to reset

                inputAreaPanel.preferredSize = Dimension(Short.MAX_VALUE.toInt(), defaultInputHeight)

            } else {

                val clamped = height.coerceIn(minInputHeight, maxInputHeight)

                inputAreaPanel.preferredSize = Dimension(Short.MAX_VALUE.toInt(), clamped)

            }

            inputAreaPanel.revalidate()

            this@ChatPanel.revalidate()

        }



        // ── Announcement banner (shown every time toolwindow opens) ──

        val announcementBanner = JPanel(BorderLayout()).also { banner ->

            banner.apply {

                isOpaque = true

                background = JBColor(0xE3F2FD, 0x1A3A5C) // light blue (light) / dark blue (dark)

                border = JBUI.Borders.empty(6, 12, 6, 4)

                val iconLabel = JLabel("\uD83D\uDCE2").apply {

                    font = font.deriveFont(14f)

                }

                val contentLabel = MarqueeLabel(I18n.tr("announcement.content")).apply {

                    font = font.deriveFont(Font.PLAIN, 12f)

                    foreground = JBColor(0x1565C0, 0x90CAF9)

                    addMouseListener(object : MouseAdapter() {

                        override fun mouseClicked(e: MouseEvent) {

                            BrowserUtil.browse(I18n.tr("announcement.content.link"))

                        }

                    })

                }

                val closeButton = JButton("\u2716").apply {

                    font = font.deriveFont(12f)

                    foreground = JBColor(0x000000, 0xAAAAAA)

                    isOpaque = false

                    isContentAreaFilled = false

                    isBorderPainted = false

                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                    addActionListener {

                        banner.isVisible = false

                    }

                }

                add(iconLabel, BorderLayout.WEST)

                add(contentLabel, BorderLayout.CENTER)

                add(closeButton, BorderLayout.EAST)



                // Add tooltip with dismiss hint

                I18n.tooltip(closeButton, "announcement.dismiss")

            }

        }



        // ── Wrap the entire chat view into one panel for CardLayout ──

        val chatView = JPanel(BorderLayout()).apply {

            isOpaque = true

            val northWrapper = JPanel().apply {

                layout = BoxLayout(this, BoxLayout.Y_AXIS)

                isOpaque = false

                add(topBar)

                add(announcementBanner)

            }

            add(northWrapper, BorderLayout.NORTH)

            add(messagesWithNav, BorderLayout.CENTER)

            add(inputAreaPanel, BorderLayout.SOUTH)

        }



        add(chatView, "chat")



        // ── Unified settings panel (full-coverage overlay) ──

        unifiedSettingsPanel = UnifiedSettingsPanel(project, onClose = { showChatView() })

        add(unifiedSettingsPanel, "settings")



        // ── Change Management panel (full-coverage overlay) ──

        changeManagementPanel = ChangeManagementPanel(project, onClose = { showChatView() })

        add(changeManagementPanel, "changeManagement")



        // Show chat view by default

        showChatView()



        // ── Width listener (removed — anchor dots embedded per-message) ──



        // If we have saved sessions, render the most recently active one

        if (saved != null && sessions.isNotEmpty()) {

            // Find the most recent session (last used)

            val lastActiveIdx = sessions.indices.maxByOrNull { sessions[it].lastActiveTime } ?: 0

            switchToSession(lastActiveIdx)

        } else {

            // Show the empty-state welcome panel

            showWelcome()

        }

        setupSelectionListener()



        // Register as the currently active instance for external callers such as UploadConsoleAction

        currentInstance = this



        // ── Scroll listener (removed — anchor dots embedded per-message) ──

    }



    // ===== Selection → input =====



    private fun setupSelectionListener() {

        val selListener = object : SelectionListener {

            override fun selectionChanged(e: SelectionEvent) {

                ApplicationManager.getApplication().invokeLater { fillInputFromSelection() }

            }

        }

        for (editor in EditorFactory.getInstance().allEditors) {

            editor.selectionModel.addSelectionListener(selListener)

        }

        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {

            override fun editorCreated(event: EditorFactoryEvent) {

                event.editor.selectionModel.addSelectionListener(selListener)

            }

            override fun editorReleased(event: EditorFactoryEvent) {

                event.editor.selectionModel.removeSelectionListener(selListener)

            }

        }, project)

    }



    private fun fillInputFromSelection() {

        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return

        val sm = editor.selectionModel

        if (!sm.hasSelection()) return



        val code = sm.selectedText ?: return

        if (code.isBlank()) return



        val doc = editor.document

        val startLine = doc.getLineNumber(sm.selectionStart) + 1

        val endLine = doc.getLineNumber(sm.selectionEnd) + 1



        val file = editor.virtualFile?.name ?: "unknown"



        // Set the selection context and show the preview card

        setSelectedContext(SelectedContext(file, startLine, endLine, code))

    }



    /**

     * Set the selected code context and update the preview card above the input.

     */

    internal fun consumeSelectedContext(): SelectedContext? {

        val ctx = selectedContext

        selectedContext = null

        return ctx

    }



    private fun setSelectedContext(context: SelectedContext?) {

        selectedContext = context

        selectedCodePreviewPanel.removeAll()



        if (context != null) {

            selectedCodePreviewPanel.add(

                SelectedCodePreview(

                    fileName = context.fileName,

                    startLine = context.startLine,

                    endLine = context.endLine,

                    onDismiss = { setSelectedContext(null) }

                )

            )

            selectedCodePreviewPanel.isVisible = true

        } else {

            selectedCodePreviewPanel.isVisible = false

        }



        selectedCodePreviewPanel.revalidate()

        selectedCodePreviewPanel.repaint()

    }



    /**

     * Pushes text from the console/external source into the chat panel, showing it above the input box as a selected-code tag.

     * Invoked by right-click actions such as [UploadConsoleAction].

     */

    fun setConsoleContext(text: String) {

        val lineCount = text.lines().size

        setSelectedContext(SelectedContext("Console Output", 1, lineCount, text))

    }



    // ===== File attachment management =====



    private fun createSettingsButton(): JComponent {

        val btn = object : JButton() {

            override fun paintComponent(g: Graphics) {

                val g2 = g.create() as Graphics2D

                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

                g2.color = if (model.isRollover) JBColor(0xE0E0E0, 0x4A4A4A) else Color(0, 0, 0, 0)

                g2.fillRoundRect(0, 0, width, height, 8, 8)

                g2.color = JBColor(0x555555, 0xAAAAAA)

                g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                val cx = width / 2

                val cy = height / 2

                val halfLen = 5

                val gap = 4

                for (i in 0..2) {

                    val y = cy + (i - 1) * gap

                    g2.drawLine(cx - halfLen, y, cx + halfLen, y)

                }

                g2.dispose()

            }

        }.apply {

            I18n.tooltip(this, "chat.settings")

            isOpaque = false

            isContentAreaFilled = false

            isBorderPainted = false

            isFocusPainted = false

            preferredSize = Dimension(24, 24)

            minimumSize = Dimension(24, 24)

            maximumSize = Dimension(24, 24)

            addActionListener {

                val popupMenu = JPopupMenu()



                val streamingItem = JCheckBoxMenuItem(I18n.tr("chat.streaming.toggle"), DeepSeekSettings.instance.streamingEnabled).apply {

                    addActionListener { DeepSeekSettings.instance.streamingEnabled = isSelected }

                }



                popupMenu.add(streamingItem)



                val thinkingItem = JCheckBoxMenuItem(I18n.tr("chat.thinking.toggle"), DeepSeekSettings.instance.thinkingEnabled).apply {

                    addActionListener { DeepSeekSettings.instance.thinkingEnabled = isSelected }

                }

                popupMenu.add(thinkingItem)

                // ── Output-speed stepper (toggled with ↑↓ arrows, no dropdown list) ──
                val speedLabel = JLabel(I18n.tr("chat.output.speed") + ":  ").apply {
                    font = font.deriveFont(11f)
                    foreground = JBColor(0x000000, 0xAAAAAA)
                }
                val speedLevels = listOf(
                    I18n.tr("chat.output.speed.fastest"),
                    I18n.tr("chat.output.speed.fast"),
                    I18n.tr("chat.output.speed.medium"),
                    I18n.tr("chat.output.speed.slow")
                )
                val speedSpinner = JSpinner(SpinnerListModel(speedLevels)).apply {
                    value = speedLevels[DeepSeekSettings.instance.outputSpeedLevel]
                    preferredSize = Dimension(100, 24)
                    addChangeListener {
                        val idx = speedLevels.indexOf(value)
                        if (idx >= 0) DeepSeekSettings.instance.outputSpeedLevel = idx
                    }
                    // Make the editor non-editable so it can only be changed via the arrows
                    editor = JSpinner.DefaultEditor(this).apply {
                        textField.isEditable = false
                        textField.font = textField.font.deriveFont(11f)
                    }
                }
                val speedPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(4, 8, 4, 8)
                    add(speedLabel)
                    add(speedSpinner)
                }
                popupMenu.add(speedPanel)
                popupMenu.addSeparator()



                val phaseItem = JMenuItem(I18n.tr("chat.agent.pipeline.settings"))

                phaseItem.addActionListener {

                    showSettingsPage("agentPipeline")

                }

                popupMenu.add(phaseItem)



                popupMenu.show(this, 0, -popupMenu.preferredSize.height)

            }

        }

        return btn

    }



    private fun createUploadButton(): JComponent {

        return createSmallRoundButton(AllIcons.Actions.Upload, "chat.upload") {

            openFileChooser()

        }

    }



    private fun createTranslateButton(): JComponent {

        return createSmallRoundButton(AllIcons.Actions.Preview, "chat.translate") {

            TranslateDialog(this@ChatPanel.project).show()

        }

    }



    /** Creates a uniform, small, rounded icon button */

    private fun createSmallRoundButton(icon: javax.swing.Icon, tooltipKey: String, action: () -> Unit): JComponent {

        return object : JButton(icon) {

            override fun paintComponent(g: Graphics) {

                val g2 = g.create() as Graphics2D

                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                g2.color = if (model.isRollover) JBColor(0xE0E0E0, 0x4A4A4A) else Color(0, 0, 0, 0)

                g2.fillRoundRect(0, 0, width, height, 8, 8)

                super.paintComponent(g)

                g2.dispose()

            }

        }.apply {

            I18n.tooltip(this, tooltipKey)

            isOpaque = false

            isContentAreaFilled = false

            isBorderPainted = false

            isFocusPainted = false

            preferredSize = Dimension(24, 24)

            minimumSize = Dimension(24, 24)

            maximumSize = Dimension(24, 24)

            addActionListener { action() }

        }

    }



    private fun openFileChooser() {

        val chooser = javax.swing.JFileChooser().apply {

            dialogTitle = I18n.tr("skill.choose.title")

            fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY

            isMultiSelectionEnabled = true

            fileFilter = javax.swing.filechooser.FileNameExtensionFilter(

                I18n.tr("attachment.filter"),

                "java", "kt", "kts", "xml", "json", "yaml", "yml", "properties",

                "txt", "md", "sql", "gradle", "ts", "js", "css", "html", "py", "go", "rs", "rb",

                "png", "jpg", "jpeg", "gif", "bmp", "webp", "pdf", "docx", "xlsx", "pptx"

            )

        }



        val result = chooser.showOpenDialog(this)

        if (result == javax.swing.JFileChooser.APPROVE_OPTION) {

            val selectedFiles = chooser.selectedFiles

            for (file in selectedFiles) {

                addFileAttachment(file)

            }

        }

    }



    private fun addFileAttachment(file: java.io.File) {

        // Skip duplicates

        if (attachedFiles.any { it.absolutePath == file.absolutePath }) return

        if (!file.isFile) {
            addMessageLabel(I18n.tr("attachment.not.file", file.name))
            return
        }

        if (file.length() > 25L * 1024 * 1024) {
            addMessageLabel(I18n.tr("attachment.too.large", file.name))
            return
        }

        if (attachedFiles.sumOf { it.size } + file.length() > 50L * 1024 * 1024) {
            addMessageLabel(I18n.tr("attachment.total.too.large"))
            return
        }

        if (attachedFiles.size >= 10) {
            addMessageLabel(I18n.tr("attachment.too.many"))
            return
        }



        val attached = AttachedFile(

            name = file.name,

            absolutePath = file.absolutePath,

            size = file.length()

        )

        if (attached.kind == com.deepseek.plugin.api.AttachmentKind.UNSUPPORTED) {
            val legacyOffice = file.extension.lowercase() in setOf("doc", "xls", "ppt")
            val key = if (legacyOffice) "attachment.legacy.office" else "attachment.unsupported"
            addMessageLabel(I18n.tr(key, file.name))
            return
        }

        attachedFiles.add(attached)

        refreshFileAttachmentPanel()

    }



    /** Called by external components such as PasteImageHandler: adds an image file as a chat attachment */

    fun addFileAttachmentFromExternal(file: java.io.File) {

        currentInstance?.addFileAttachment(file)

    }



    private fun removeFileAttachment(index: Int) {

        if (index in attachedFiles.indices) {

            attachedFiles.removeAt(index)

            refreshFileAttachmentPanel()

        }

    }



    private fun refreshFileAttachmentPanel() {

        fileAttachmentPanel.removeAll()



        if (attachedFiles.isNotEmpty()) {

            fileAttachmentPanel.add(

                FileAttachmentPreview(attachedFiles.toList()) { index -> removeFileAttachment(index) },

                BorderLayout.CENTER

            )

            fileAttachmentPanel.isVisible = true

        } else {

            fileAttachmentPanel.isVisible = false

        }



        fileAttachmentPanel.revalidate()

        fileAttachmentPanel.repaint()

    }



    /**

     * Image file extensions supported by StepFun image parsing.

     */

    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")



    /**

     * Check if a file is an image by its extension.

     */

    private fun isImageFile(fileName: String): Boolean {

        val ext = fileName.substringAfterLast('.', "").lowercase()

        return ext in imageExtensions

    }

    private fun collectImageAttachments(): List<AttachedFile> = buildList {
        addAll(attachedFiles.filter { it.isImage })
        attachedFiles.forEach { source ->
            source.renderedImageFiles.forEachIndexed { index, image ->
                add(AttachedFile("${source.name}-page-${index + 1}.png", image.absolutePath, image.length()))
            }
        }
    }



    /**

     * Build the file context string from attached text files only.

     * Image files are NOT processed here — they are handled asynchronously

     * in [sendMessageWithImages] so the UI shows "Parsing..." instead of a modal dialog.

     */

    internal fun buildTextFileContext(): String {

        if (attachedFiles.isEmpty()) return ""



        val sb = StringBuilder()

        val maxContextChars = 200_000



        // Only process text files; image files are handled separately

        val textFiles = attachedFiles.filter { !it.isImage }



        for (file in textFiles) {

            if (sb.length >= maxContextChars) {
                sb.appendLine("[The remaining attachments were not inlined due to the total context length limit; please narrow the attachment scope and try again.]")
                break
            }

            val content = file.readContent()

            if (content != null) {

                sb.appendLine("The following is the parsed content of attachment `${file.name}` (${file.kind}):")

                file.statusMessage?.let { sb.appendLine("> Note: $it") }

                sb.appendLine("```text")

                val remaining = (maxContextChars - sb.length).coerceAtLeast(0)
                val included = content.take(remaining)
                sb.appendLine(included)

                if (included.length < content.length) {
                    sb.appendLine("[Attachment content truncated to fit the total context length limit]")
                }

                sb.appendLine("```")

                sb.appendLine()

            } else {

                sb.appendLine("[Unable to read attachment `${file.name}`: ${file.statusMessage ?: "Unknown error"}]")

            }

        }



        return sb.toString()

    }



    /**

     * Send a message with images asynchronously.

     *

     * Instead of showing a modal progress dialog, this method:

     * 1. Renders the user message with image file names shown inline

     * 2. Creates an AI streaming bubble with "Parsing..." status

     * 3. Parses images in a background thread

     * 4. Once parsing completes, feeds the enriched text to the AI for final response

     * 5. The streaming bubble transitions from "Parsing..." → AI response

     */

    private fun sendMessageWithImages(

        text: String,

        textFileContext: String,

        imageFiles: List<AttachedFile>,

        projectDir: java.io.File?,

        refContext: List<String>,

        temporarySources: List<AttachedFile> = emptyList()

    ) {

        val settings = DeepSeekSettings.instance

        if (LlmProviderRegistry.get(settings.provider).capabilities.nativeImages &&
            sendMessageWithNativeImages(text, textFileContext, imageFiles, refContext, temporarySources)) {
            return
        }



        // Check API key availability

        val apiKeyMissing = when (settings.imageParsingModel) {

            "stepfun" -> settings.stepFunApiKey.isBlank()

            "nvidia" -> settings.nvidiaApiKey.isBlank()

            else -> settings.agnesApiKey.isBlank()

        }

        if (apiKeyMissing) {

            val providerName = when (settings.imageParsingModel) {

                "stepfun" -> "StepFun"

                "nvidia" -> "NVIDIA"

                else -> "Agnes"

            }

            val msg = I18n.tr("chat.api.key.missing.prefix") + " " + providerName + I18n.tr("chat.api.key.missing.suffix")

            renderUserMessage(text)

            addMessageLabel(I18n.tr("chat.warning.prefix") + " " + msg)

            attachedFiles.clear()

            refreshFileAttachmentPanel()

            temporarySources.forEach { it.cleanupTemporaryFiles() }

            return

        }



        // Build final display text (without image descriptions yet)

        val imageNames = imageFiles.map { it.name }

        val finalText = buildString {

            if (textFileContext.isNotEmpty()) {

                append(textFileContext)

            }

            val ctx = selectedContext

            if (ctx != null) {

                appendLine("[${ctx.fileName}:${ctx.startLine}-${ctx.endLine}]")

                appendLine("```")

                appendLine(ctx.snippet)

                appendLine("```")

                appendLine()

            }

            if (refContext.isNotEmpty()) {

                appendLine(refContext.joinToString("\n\n"))

                appendLine()

            }

            append(text)

        }

        setSelectedContext(null)



        // Render user message with image file names as fileTabs

        renderUserMessageWithImages(finalText, imageNames)



        // Clear attachments

        attachedFiles.clear()

        refreshFileAttachmentPanel()



        // Create streaming bubble with "Parsing..." status

        removeMessagesFiller()

        val streamBubble = MessageBubble(project, MessageBubble.Role.STREAMING)

        val streamTextArea = createParsingArea(streamBubble, I18n.tr("chat.parsing"))

        sendStopButton.text = I18n.tr("chat.stop.enter")

        sendStopButton.toolTipText = I18n.tr("chat.tooltip.stop")



        // Parse images on background thread

        val imagePaths = imageFiles.map { it.absolutePath }

        var isCancelled = false



        ApplicationManager.getApplication().executeOnPooledThread {

            try {

                val imageResults = mutableListOf<Pair<String, String>>()

                for (path in imagePaths) {

                    // Check for cancellation

                    if (isCancelled) break

                    val fileName = java.nio.file.Paths.get(path).fileName.toString()

                    val result = stepFunClient.parseImage(path)

                    val description = result.getOrElse { e -> "[Image parsing failed: ${e.message}]" }

                    imageResults.add(fileName to description)

                }

                temporarySources.forEach { it.cleanupTemporaryFiles() }



                // Switch back to EDT to start AI streaming

                ApplicationManager.getApplication().invokeLater {

                if (isCancelled) {

                    stopThinkingAnimation()

                    chatState.set(ChatState.Idle)

                    messagesPanel.remove(streamBubble)

                    messagesPanel.revalidate()

                    sendStopButton.text = I18n.tr("chat.send.enter")

                    sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

                    return@invokeLater

                }



                stopThinkingAnimation()



                // Build enriched text with image descriptions

                val imageContext = imageResults.joinToString("\n\n") { (name, desc) ->

                    "The following is the parsing result of image `$name`:" +

                    "\n> $desc"

                }

                val enrichedText = if (imageContext.isNotBlank()) {

                    "$finalText\n\n$imageContext"

                } else {

                    finalText

                }



                // Persist only the user's question. Parsed image/attachment context is request-local.
                messageHistory.add(ChatMessage("user", text))

                currentSession().lastActiveTime = System.currentTimeMillis()

                saveSessions()



                // Set up streaming callbacks

                val tokenBuffer = StringBuilder()
                val reasoningBuffer = StringBuilder()
                val flushTimer = Timer(40) {
                    if (tokenBuffer.isNotEmpty()) {
                        val full = tokenBuffer.toString()
                        tokenBuffer.clear()
                        val maxChars = when (DeepSeekSettings.instance.outputSpeedLevel) {
                            1 -> 20
                            2 -> 8
                            3 -> 3
                            else -> Int.MAX_VALUE
                        }
                        val take = minOf(full.length, maxChars)
                        streamTextArea.append(full.substring(0, take))
                        if (take < full.length) tokenBuffer.append(full.substring(take))
                        streamTextArea.revalidate()
                        messagesPanel.revalidate()
                        revalidateAndScroll()
                    }
                }.apply { isRepeats = true }

                val onTokenBlock: (String) -> Unit = { token ->

                    ApplicationManager.getApplication().invokeLater {

                        if (thinkingTimer?.isRunning == true) {

                            thinkingTimer?.stop()

                            thinkingTimer = null

                            streamTextArea.text = ""

                        }

                        tokenBuffer.append(token)

                        val s = chatState.get()

                        if (s is ChatState.Streaming) s.buffer.append(token)

                        if (!flushTimer.isRunning) flushTimer.start()

                    }

                }



                val onCompleteBlock: (String, Usage?) -> Unit = { fullResponse, usage ->

                    ApplicationManager.getApplication().invokeLater {

                        flushTimer.stop()
                        if (tokenBuffer.isNotEmpty()) {
                            val remaining = tokenBuffer.toString()
                            tokenBuffer.clear()
                            streamTextArea.append(remaining)
                            streamTextArea.revalidate()
                            messagesPanel.revalidate()
                            scrollToBottom()
                        }
                        stopThinkingAnimation()

                        val oldState = chatState.getAndSet(ChatState.Idle)

                        if (oldState is ChatState.Streaming) {

                            oldState.eventSource.cancel()

                            removeStreamingArea(oldState, revalidateAfter = false)

                        }



                        val parsed = parseThinkingResponse(fullResponse)

                        val displayContent = parsed.second

                        val reasoning = if (DeepSeekSettings.instance.thinkingEnabled) (parsed.third ?: reasoningBuffer.toString().ifEmpty { null }) else null

                        messageHistory.add(ChatMessage("assistant", displayContent, reasoning = reasoning, usage = usage))

                        renderAssistantMessage(displayContent, reasoning = reasoning, scrollAfter = false)



                        usage?.let {

                            currentSession().totalTokens += it.totalTokens

                        }

                        saveSessions()

                        sendStopButton.text = I18n.tr("chat.send.enter")

                        sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

                        ensureMessagesFiller()

                        revalidateAndScroll()

                    }

                }



                val onErrorBlock: (Throwable) -> Unit = { error ->

                    ApplicationManager.getApplication().invokeLater {

                        flushTimer.stop()
                        if (tokenBuffer.isNotEmpty()) {
                            val remaining = tokenBuffer.toString()
                            tokenBuffer.clear()
                            streamTextArea.append(remaining)
                            streamTextArea.revalidate()
                            messagesPanel.revalidate()
                            scrollToBottom()
                        }
                        stopThinkingAnimation()

                        val oldState = chatState.getAndSet(ChatState.Idle)

                        if (oldState is ChatState.Streaming) {

                            oldState.eventSource.cancel()

                            removeStreamingArea(oldState)

                        }



                        val pluginErr = DeepSeekPluginException(

                            message = error.message ?: I18n.tr("chat.api.call.failed"),

                            cause = error,

                            userMessage = I18n.tr("chat.error.api") + " " + error.message

                        )

                        addMessageLabel(I18n.tr("chat.error.prefix") + " " + pluginErr.userMessage)

                        sendStopButton.text = I18n.tr("chat.send.enter")

                        sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

                        ensureMessagesFiller()

                        scrollToBottom()

                    }

                }



                val composedRequest = composeQaRequest(
                    systemPrompt = buildQaSystemPrompt(text),
                    transientContext = enrichedText,
                    currentUserParts = emptyList()
                )
                if (composedRequest.transientContextTruncated) {
                    addMessageLabel(I18n.tr("chat.context.truncated"))
                }



                val deferredEventSource = DeferredEventSource()
                chatState.set(ChatState.Streaming(
                    eventSource = deferredEventSource,
                    bubble = streamBubble,
                    flushTimer = flushTimer,
                    thinkingTimer = thinkingTimer
                ))

                val eventSource = client.chatStream(

                    messages = composedRequest.messages,

                    onToken = onTokenBlock,

                    onComplete = onCompleteBlock,

                    onError = onErrorBlock,

                    onReasoningToken = { token -> reasoningBuffer.append(token)
                    val s = chatState.get()
                    if (s is ChatState.Streaming) s.reasoningBuffer.append(token)
                }

                )



                deferredEventSource.attach(eventSource)

            }

        }
        catch (e: Exception) {

            System.err.println("[ChatPanel] Image parsing failed unexpectedly: ${e.message}")

            temporarySources.forEach { it.cleanupTemporaryFiles() }

            ApplicationManager.getApplication().invokeLater {

                stopThinkingAnimation()

                chatState.set(ChatState.Idle)

                messagesPanel.remove(streamBubble)

                messagesPanel.revalidate()

                sendStopButton.text = I18n.tr("chat.send.enter")

                sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

                addMessageLabel(I18n.tr("chat.error.prefix") + " Image parsing error: " + (e.message ?: "Unknown error"))

                ensureMessagesFiller()

                scrollToBottom()

            }

        }

        }

    }



    // ===== Toolbar =====



    /**

     * Create a natural-looking toolbar/text button.

     * Modern style: subtle rounded rect background on hover, clean look.

     */

    // ===== Session management =====



    internal fun currentSession() = sessions[currentSessionIndex]



    private fun createNewSession() {

        // Prevent creating a new conversation when the current one is empty, showing a friendly hint

        if (sessions.isNotEmpty() && currentSession().messages.isEmpty()) {

            ToolWindowManager.getInstance(project).notifyByBalloon(

                "DeepSeek-AI-Helper",

                MessageType.INFO,

                I18n.tr("chat.no.messages")

            )

            return

        }

        stopStreaming()

        sessionCounter++

        val name = I18n.tr("chat.session") + " $sessionCounter"

        sessions.add(ChatSession(name))

        val idx = sessions.size - 1

        sessionComboBox.addItem(name)

        currentSessionIndex = idx

        sessionComboBox.selectedIndex = idx

        messagesPanel.removeAll()

        userMessages.clear()

        showWelcome()

        saveSessions()

    }



    private fun clearAllSessions() {

        val result = JOptionPane.showConfirmDialog(

            this,

            I18n.tr("chat.confirm.clear.all.sessions"),

            I18n.tr("chat.clear.all.sessions"),

            JOptionPane.YES_NO_OPTION,

            JOptionPane.WARNING_MESSAGE

        )

        if (result != JOptionPane.YES_OPTION) return



        // Clear all sessions

        sessions.clear()

        sessionComboBox.removeAllItems()

        sessionCounter = 0



        // Reset to a fresh state

        messagesPanel.removeAll()

        userMessages.clear()



        // Create a new session and switch to it

        createNewSession()

    }



    fun switchToSession(index: Int) {

        stopStreaming()

        currentSessionIndex = index

        sessionComboBox.selectedIndex = index

        messagesPanel.removeAll()

        userMessages.clear()

        val session = sessions[index]

        if (session.messages.isEmpty()) {

            showWelcome()

            saveSessions()

            scrollToBottom()

            return

        }

        showMessages()

        addMessageLabel("=== ${session.name} ===")



        // P3: virtualization — only render the most recent VISIBLE_BATCH_SIZE messages

        val total = session.messages.size

        visibleStartIndex = maxOf(0, total - VISIBLE_BATCH_SIZE)

        renderMessageRange(session, visibleStartIndex, total)
        ensureMessagesFiller()



        saveSessions()

        scrollToBottom()

    }



    /**

     * P3: renders the messages within the given range (left-inclusive, right-exclusive).

     * If there are unrendered messages before, adds a "Load more" button at the top.

     */

    private fun renderMessageRange(session: ChatSession, start: Int, end: Int) {

        val messages = session.messages

        // If the start is not 0, insert a "Load more" button at the top

        if (start > 0) {

            val loadMoreBtn = JButton(I18n.tr("chat.load.more")).apply {

                isOpaque = false

                foreground = PluginTheme.color(0x1A73E8, 0x64B5F6)

                font = font.deriveFont(Font.PLAIN, 11f)

                border = JBUI.Borders.empty(4, 0)

                addActionListener {

                    messagesPanel.removeAll()

                    userMessages.clear()

                    val newStart = maxOf(0, start - VISIBLE_BATCH_SIZE)

                    visibleStartIndex = newStart

                    addMessageLabel("=== ${session.name} ===")

                    renderMessageRange(session, newStart, end)

                    ensureMessagesFiller()

                    scrollToBottom()

                }

            }

            messagesPanel.add(loadMoreBtn, fillWidthConstraints)

            messagesPanel.add(Box.createVerticalStrut(4), fillWidthConstraints)

        }

        for (i in start until end) {

            val msg = messages[i]

            when (msg.role) {

                "user" -> renderUserMessage(msg.content)

                "assistant" -> {
                    renderAssistantMessage(msg.content, reasoning = msg.reasoning)
                }

            }

        }

    }



    // ===== History dialog =====



    private fun showHistoryDialog() {

        val dialog = HistoryDialog(

            project, sessions, currentSessionIndex,

            onSwitch = { index -> switchToSession(index) },

            onClearAll = { clearAllSessions() }

        )

        dialog.show()

    }



    // ==================================================================

    // Mode toggle (Q&A / Agent)

    // ==================================================================



    /**

     * Create a dropdown (combobox) for selecting Q&A or Agent mode.

     * Fixed width = half the send button width.

     */

    private fun createModeDropdown(): JComponent {

        val combo = JComboBox(arrayOf(

            I18n.tr("chat.mode.qa"),

            I18n.tr("chat.mode.qa.scan"),

            I18n.tr("chat.mode.agent"),

            cliModeLabel()

        )).apply {

            font = font.deriveFont(Font.PLAIN, 11f)

            isOpaque = false

            selectedIndex = when (currentMode) {

                ChatMode.Q_A -> 0

                ChatMode.Q_A_SCAN -> 1

                ChatMode.AGENT -> 2

                ChatMode.CLI_AGENT -> 3

            }

            addActionListener {

                if (suppressingModeDropdown) return@addActionListener

                val newMode = when (selectedIndex) {

                    1 -> ChatMode.Q_A_SCAN

                    2 -> ChatMode.AGENT

                    3 -> ChatMode.CLI_AGENT

                    else -> ChatMode.Q_A

                }

                if (newMode != currentMode) {

                    currentMode = newMode

                }

            }

        }

        // Use the natural width of the longest option text "Q&A full-text scan" as the fixed global width, not resizing with the window

        val fixedW = combo.preferredSize.width

        val fixedH = combo.preferredSize.height

        combo.preferredSize = Dimension(fixedW, fixedH)

        combo.minimumSize = Dimension(fixedW, fixedH)

        combo.maximumSize = Dimension(fixedW, fixedH)

        return combo

    }

    // ════════════════════════════════════════════════════════════════
    //  Fourth mode: Claude/Codex native CLI Agent
    // ════════════════════════════════════════════════════════════════

    /** Fourth-mode dropdown label: dynamically shows Claude Code / Codex based on the current Provider */
    private fun cliModeLabel(): String = when (DeepSeekSettings.instance.provider) {
        "anthropic" -> "Claude Code"
        "codex" -> "Codex"
        else -> "Claude/Codex"
    }

    /** After a Provider change, refresh the 4th dropdown item label (remove/insert + debounce flag to restore selection, avoiding selectedIndex drift that would accidentally switch modes) */
    private fun updateCliModeLabel() {
        val combo = modeDropdown ?: return
        suppressingModeDropdown = true
        try {
            val sel = combo.selectedIndex
            if (combo.itemCount > 3) {
                combo.removeItemAt(3)
                combo.insertItemAt(cliModeLabel(), 3)
            }
            if (sel in 0..3 && combo.selectedIndex != sel) combo.selectedIndex = sel
        } finally {
            suppressingModeDropdown = false
        }
    }

    /** Placeholder EventSource for the CLI mode, which has no SSE (cancellation is handled by process.destroy) */
    private fun emptyCliEventSource(): EventSource = object : EventSource {
        override fun cancel() {}
        override fun request(): Request = Request.Builder().url("http://localhost/").build()
    }

    /**
     * Fourth mode: drives the local Claude Code / Codex CLI to handle the user's question.
     * Provider=anthropic → claude CLI; Provider=codex → codex CLI.
     * The CLI executes directly as an agent (edits code / runs commands / calls tools), using the local login state for credentials.
     */
    private fun sendCliAgentMessage(text: String) {
        val files = attachedFiles.toList()
        val selectionText = selectedContext?.let { context ->
            buildString {
                appendLine("## Selected code: ${context.fileName}:${context.startLine}-${context.endLine}")
                appendLine("```")
                appendLine(context.snippet)
                appendLine("```")
            }
        }.orEmpty()
        val displayText = buildString {
            append(text)
            if (files.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine(I18n.tr("attachment.label", files.joinToString { it.name }))
            }
        }

        inputArea.text = ""
        attachedFiles.clear()
        refreshFileAttachmentPanel()
        setSelectedContext(null)
        val generation = beginAttachmentPreparation()

        attachmentPreparationTask = ApplicationManager.getApplication().executeOnPooledThread {
            val bundle = try {
                CliAttachmentBundle.prepare(files.map { it.descriptor })
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    if (!finishAttachmentPreparation(generation) || project.isDisposed) return@invokeLater
                    addMessageLabel(I18n.tr("chat.error.prefix") + " " + (e.message ?: I18n.tr("attachment.prepare.failed")))
                }
                return@executeOnPooledThread
            }
            val cliPrompt = buildString {
                append(bundle.promptSection())
                append(selectionText)
                if (selectionText.isNotBlank()) appendLine()
                append(text)
            }
            ApplicationManager.getApplication().invokeLater {
                if (!finishAttachmentPreparation(generation) || project.isDisposed) {
                    bundle.close()
                    return@invokeLater
                }
                launchCliAgentMessage(cliPrompt, displayText, bundle)
            }
        }
    }

    private fun sendMessageWithNativeImages(
        text: String,
        textFileContext: String,
        imageFiles: List<AttachedFile>,
        refContext: List<String>,
        temporarySources: List<AttachedFile>
    ): Boolean {
        val imageMedia = imageFiles.map { file -> file to nativeImageMediaType(file.name) }
        if (imageMedia.any { it.second == null }) return false

        val selectionContext = selectedContext?.let { context ->
            buildString {
                appendLine("[${context.fileName}:${context.startLine}-${context.endLine}]")
                appendLine("```")
                appendLine(context.snippet)
                appendLine("```")
            }
        }.orEmpty()
        val transientContext = listOf(textFileContext, selectionContext, refContext.joinToString("\n\n"))
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val imageNames = imageFiles.map { it.name }

        attachedFiles.clear()
        refreshFileAttachmentPanel()
        setSelectedContext(null)
        val generation = beginAttachmentPreparation()
        attachmentPreparationTask = ApplicationManager.getApplication().executeOnPooledThread {
            val parts = try {
                imageMedia.map { (file, mediaType) ->
                    val bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(file.absolutePath))
                    val data = java.util.Base64.getEncoder().encodeToString(bytes)
                    ChatContentPart(
                        type = com.deepseek.plugin.api.ChatContentType.IMAGE,
                        dataUri = "data:$mediaType;base64,$data",
                        mediaType = mediaType,
                        name = file.name
                    )
                }
            } catch (e: Exception) {
                temporarySources.forEach { it.cleanupTemporaryFiles() }
                ApplicationManager.getApplication().invokeLater {
                    if (!finishAttachmentPreparation(generation) || project.isDisposed) return@invokeLater
                    addMessageLabel(I18n.tr("chat.error.prefix") + " " + (e.message ?: I18n.tr("attachment.prepare.failed")))
                }
                return@executeOnPooledThread
            }

            temporarySources.forEach { it.cleanupTemporaryFiles() }
            ApplicationManager.getApplication().invokeLater {
                if (!finishAttachmentPreparation(generation) || project.isDisposed) return@invokeLater
                messageHistory.add(ChatMessage("user", text))
                renderUserMessageWithImages(text, imageNames)
                currentSession().lastActiveTime = System.currentTimeMillis()
                saveSessions()
                respondDirectly(
                    text = text,
                    userAlreadyRendered = true,
                    transientContext = transientContext,
                    currentUserParts = parts
                )
            }
        }
        return true
    }

    private fun nativeImageMediaType(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> null
    }

    private fun launchCliAgentMessage(
        text: String,
        displayText: String,
        attachmentBundle: CliAttachmentBundle
    ) {
        activeCliAttachmentBundle = attachmentBundle
        val settings = DeepSeekSettings.instance
        val cliType = when (settings.provider) {
            "anthropic" -> "claude"
            "codex" -> "codex"
            else -> {
                attachmentBundle.close()
                activeCliAttachmentBundle = null
                addMessageLabel(I18n.tr("cli.mode.requires.provider"))
                return
            }
        }
        if (CliAgentProcess.findExecutable(cliType) == null) {
            attachmentBundle.close()
            activeCliAttachmentBundle = null
            addMessageLabel(I18n.tr("cli.mode.not.found", cliType))
            return
        }

        inputArea.text = ""
        renderUserMessage(displayText)
        messageHistory.add(ChatMessage("user", displayText))

        // Streaming assistant bubble (must be Role.STREAMING so streamTextArea is non-null)
        val bubble = MessageBubble(project, MessageBubble.Role.STREAMING)
        val textArea = createStreamingArea(bubble)
        sendStopButton.text = I18n.tr("chat.stop.enter")
        sendStopButton.toolTipText = I18n.tr("chat.tooltip.stop")

        // Pre-execution snapshot of the CLI (used for change management)
        val projectDir = java.io.File(project.basePath ?: System.getProperty("user.home"))
        val snapshotBefore = CliChangeTracker.snapshot(projectDir)

        var finalized = false
        val finalizeLock = Any()

        fun finalizeCliStreaming(content: String, reasoning: String?) {
            synchronized(finalizeLock) {
                if (finalized) return
                finalized = true
            }
            attachmentBundle.close()
            activeCliAttachmentBundle = null
            chatState.set(ChatState.Idle)
            stopThinkingAnimation()

            // ① Change comparison → change management (core feature: runs first; UI rendering failures do not affect recording)
            val changedCount = try {
                CliChangeTracker.recordChanges(
                    project, snapshotBefore, CliChangeTracker.snapshot(projectDir),
                    I18n.tr("cli.mode.change.title", cliType)
                )
            } catch (e: Exception) {
                System.err.println("[CLI Agent] recordChanges failed: ${e.message}")
                0
            }
            System.err.println("[CLI Agent] finalize: changedCount=$changedCount")

            // ② UI finalization (rendering exceptions do not block; changes have already been recorded)
            try {
                val parsed = parseThinkingResponse(content)
                val displayContent = parsed.second
                val finalReasoning = if (settings.thinkingEnabled) (parsed.third ?: reasoning) else null
                messageHistory.add(ChatMessage("assistant", displayContent, reasoning = finalReasoning))
                renderAssistantMessage(displayContent, reasoning = finalReasoning)
                saveSessions()
                if (changedCount > 0) {
                    addMessageLabel(I18n.tr("cli.mode.change.recorded", changedCount))
                }
                removeStreamingArea(ChatState.Streaming(emptyCliEventSource(), bubble = bubble))
                sendStopButton.text = I18n.tr("chat.send.enter")
                sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")
                scrollToBottom()
            } catch (e: Exception) {
                System.err.println("[CLI Agent] finalize UI error: ${e.message}")
            }
        }

        val agent = CliAgentProcess(
            cliType = cliType,
            projectDir = projectDir,
            permissionMode = settings.cliAgentPermissionMode,
            prompt = text,
            allowedDirectories = listOf(attachmentBundle.rootDirectory),
            imageFiles = if (cliType == "codex") attachmentBundle.imageFiles else emptyList(),
            onText = { chunk ->
                ApplicationManager.getApplication().invokeLater {
                    if (thinkingTimer?.isRunning == true) {
                        thinkingTimer?.stop()
                        thinkingTimer = null
                        textArea.text = ""
                    }
                    textArea.append(chunk)
                    val state = chatState.get()
                    if (state is ChatState.Streaming && state.bubble === bubble) state.buffer.append(chunk)
                    revalidateAndScroll()
                }
            },
            onThinking = { },
            onComplete = { finalText ->
                ApplicationManager.getApplication().invokeLater {
                    finalizeCliStreaming(finalText, null)
                }
            },
            onError = { msg ->
                ApplicationManager.getApplication().invokeLater {
                    synchronized(finalizeLock) {
                        if (finalized) return@invokeLater
                        finalized = true
                    }
                    attachmentBundle.close()
                    activeCliAttachmentBundle = null
                    chatState.set(ChatState.Idle)
                    addMessageLabel(I18n.tr("chat.error.prefix") + " " + msg)
                    removeStreamingArea(ChatState.Streaming(emptyCliEventSource(), bubble = bubble))
                    sendStopButton.text = I18n.tr("chat.send.enter")
                    sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")
                }
            }
        )

        // Create a placeholder state first, then fill in the process reference after process creation (so the stop button can destroy it)
        chatState.set(
            ChatState.Streaming(
                eventSource = emptyCliEventSource(),
                process = null,
                buffer = StringBuilder(),
                reasoningBuffer = StringBuilder(),
                bubble = bubble
            )
        )
        agent.start()
        chatState.getAndUpdate { s ->
            if (s is ChatState.Streaming && s.process == null && agent.currentProcess != null) {
                s.copy(process = agent.currentProcess)
            } else s
        }
    }

    /** Check the key resolved by the selected chat provider (including Codex env/config sources). */
    private fun hasChatApiKey(settings: DeepSeekSettings): Boolean =
        runCatching {
            LlmProviderRegistry.get(settings.provider)
                .apiKey(settings.toSnapshot())
                .isNotBlank()
        }.getOrDefault(false)

    private fun missingQaApiKeyMessage(settings: DeepSeekSettings): String = when (settings.provider) {
        "anthropic" -> I18n.tr("chat.api.key.required.anthropic")
        "codex" -> I18n.tr("chat.api.key.required.codex")
        else -> I18n.tr("chat.api.key.required")
    }



    // ==================================================================

    // Chat / Streaming

    // ==================================================================



    fun focusInput() {

        inputArea.requestFocusInWindow()

    }

    private fun beginAttachmentPreparation(): Long {
        val generation = ++attachmentPreparationGeneration
        isPreparingAttachments = true
        inputArea.isEnabled = false
        sendStopButton.isEnabled = true
        sendStopButton.text = I18n.tr("chat.stop.enter")
        sendStopButton.toolTipText = I18n.tr("chat.tooltip.stop")
        return generation
    }

    private fun finishAttachmentPreparation(generation: Long): Boolean {
        if (generation != attachmentPreparationGeneration || !isPreparingAttachments) return false
        isPreparingAttachments = false
        attachmentPreparationTask = null
        preparationCancelUi = null
        inputArea.isEnabled = true
        sendStopButton.isEnabled = true
        sendStopButton.text = I18n.tr("chat.send.enter")
        sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")
        return true
    }

    private fun prepareAttachmentsInBackground(): Boolean {
        if (currentMode == ChatMode.CLI_AGENT) return false
        val pending = attachedFiles.filter {
            !it.isImage && it.content == null &&
                it.status != com.deepseek.plugin.api.AttachmentStatus.FAILED
        }
        if (pending.isEmpty()) return false

        val generation = beginAttachmentPreparation()
        attachmentPreparationTask = ApplicationManager.getApplication().executeOnPooledThread {
            pending.forEach { it.readContent() }
            ApplicationManager.getApplication().invokeLater {
                if (!finishAttachmentPreparation(generation) || project.isDisposed) return@invokeLater
                refreshFileAttachmentPanel()
                sendMessage()
            }
        }
        return true
    }



    private fun sendMessage() {

        val text = inputArea.text.trim()

        val hasAttachments = attachedFiles.isNotEmpty() || selectedContext != null

        if (text.isEmpty() && !hasAttachments) return

        if (isStreaming || isPreparingAttachments) return

        // Refresh the fourth-mode dropdown label (dynamically shows Claude Code / Codex based on the Provider)

        updateCliModeLabel()

        // Sending a new message: resume auto-scroll to follow the new reply
        autoScrollController.resumeFollowing()



        // Intent-confirmation phase — the user's additional notes

        pendingConfirmation?.let { callback ->

            if (text.isNotBlank()) {

                inputArea.text = ""

                val cb = pendingConfirmation

                pendingConfirmation = null

                cb?.invoke(text)

            }

            return

        }

        if (prepareAttachmentsInBackground()) return



        if (currentMode == ChatMode.Q_A_SCAN) {

            sendQAScanMessage(text)

            return

        }



        if (currentMode == ChatMode.AGENT) {

            sendAgentMessage(text)

            return

        }



        if (currentMode == ChatMode.CLI_AGENT) {

            sendCliAgentMessage(text)

            return

        }



        val settings = DeepSeekSettings.instance

        if (!hasChatApiKey(settings)) {

            addMessageLabel(missingQaApiKeyMessage(settings))

            return

        }



        inputArea.text = ""



        // Has image → asynchronous image-parsing flow

        val imageFiles = collectImageAttachments()

        if (imageFiles.isNotEmpty()) {

            val fileContext = buildTextFileContext()

            val projectDir = project.basePath?.let { java.io.File(it) }

            val refContext = buildRefContext(projectDir, text)

            sendMessageWithImages(
                text,
                fileContext,
                imageFiles,
                projectDir,
                refContext,
                attachedFiles.toList()
            )

            return

        }



        // No images: keep extracted content transient instead of persisting it in chat history.
        val attachmentSources = attachedFiles.toList()
        val fileContext = buildTextFileContext()
        val transientContext = buildString {
            if (fileContext.isNotBlank()) append(fileContext)
            selectedContext?.let { context ->
                appendLine("[${context.fileName}:${context.startLine}-${context.endLine}]")
                appendLine("```")
                appendLine(context.snippet)
                appendLine("```")
                appendLine()
            }
        }
        val displayText = buildString {
            append(text)
            if (attachmentSources.isNotEmpty()) {
                appendLine()
                appendLine()
                append(I18n.tr("attachment.label", attachmentSources.joinToString { it.name }))
            }
        }
        attachedFiles.clear()
        refreshFileAttachmentPanel()
        setSelectedContext(null)
        attachmentSources.forEach { it.cleanupTemporaryFiles() }
        respondDirectly(text, displayText = displayText, transientContext = transientContext)

        return

    }



    // ==================================================================

    // Q&A Full Scan mode — scan project context then respond as Q&A

    // ==================================================================



    /**

     * Q&A full-text scan mode: first scans the project to obtain relevant context (file structure + related source code),

     * then answers in a Q&A manner (without going through the Agent Pipeline).

     */

    private fun sendQAScanMessage(userText: String) {

        val settings = DeepSeekSettings.instance

        if (!hasChatApiKey(settings)) {

            addMessageLabel(missingQaApiKeyMessage(settings))

            return

        }



        inputArea.text = ""

        // Extract image attachments (full-text scan must also parse images)
        val temporarySources = attachedFiles.toList()
        val originalImageFiles = attachedFiles.filter { it.isImage }
        val imageFiles = collectImageAttachments()
        val imagePaths = imageFiles.map { it.absolutePath }
        // First remove images from attachedFiles (text files are still kept for fileContext)
        attachedFiles.removeAll(originalImageFiles)

        // 1. Build the initial text (including editor-selected code + uploaded file context)

        val fileContext = buildTextFileContext()

        val attachmentAndSelectionContext = buildString {

            val ctx = selectedContext

            if (ctx != null) {

                appendLine("[${ctx.fileName}:${ctx.startLine}-${ctx.endLine}]")

                appendLine("```")

                appendLine(ctx.snippet)

                appendLine("```")

                appendLine()

            }

            if (fileContext.isNotEmpty()) {

                append(fileContext)

                appendLine()

            }

        }

        val displayText = buildString {
            append(userText)
            if (temporarySources.isNotEmpty()) {
                appendLine()
                appendLine()
                append(I18n.tr("attachment.label", temporarySources.joinToString { it.name }))
            }
        }

        attachedFiles.clear()
        refreshFileAttachmentPanel()

        setSelectedContext(null)



        // Show the user message in the chat area (including the selected-code context)

        messageHistory.add(ChatMessage("user", userText))

        renderUserMessage(displayText)

        currentSession().lastActiveTime = System.currentTimeMillis()

        saveSessions()



        // 2. Show the analysis status (using a spinner animation instead of a static label)

        val spinnerChars = listOf("◐", "◓", "◑", "◒")

        var spinnerIdx = 0

        val analysisLabel = JLabel().apply {

            font = font.deriveFont(Font.ITALIC, 11f)

            foreground = PluginTheme.textPrimary()

            border = JBUI.Borders.empty(4, 16, 4, 16)

            text = I18n.tr("chat.analysis.scanning") + " ◐"

        }

        messagesPanel.add(analysisLabel, fillWidthConstraints)

        revalidateAndScroll()

        val analysisAnimTimer = Timer(300) {

            spinnerIdx = (spinnerIdx + 1) % spinnerChars.size

            analysisLabel.text = I18n.tr("chat.analysis.scanning") + " " + spinnerChars[spinnerIdx]

        }.apply { start() }



        // 2. Scan project context in the background (without AI judgement; directly get the structure + search related source code)

        val preparationGeneration = beginAttachmentPreparation()
        preparationCancelUi = {
            analysisAnimTimer.stop()
            messagesPanel.remove(analysisLabel)
            messagesPanel.revalidate()
            messagesPanel.repaint()
        }
        attachmentPreparationTask = ApplicationManager.getApplication().executeOnPooledThread {

            try {

                val projectStructure = buildProjectFileTree()

                val canUseNativeImages = LlmProviderRegistry.get(settings.provider).capabilities.nativeImages &&
                    imageFiles.all { nativeImageMediaType(it.name) != null }
                val nativeImageParts = if (canUseNativeImages) {
                    imageFiles.map { file ->
                        val mediaType = requireNotNull(nativeImageMediaType(file.name))
                        val bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(file.absolutePath))
                        ChatContentPart(
                            type = com.deepseek.plugin.api.ChatContentType.IMAGE,
                            dataUri = "data:$mediaType;base64,${java.util.Base64.getEncoder().encodeToString(bytes)}",
                            mediaType = mediaType,
                            name = file.name
                        )
                    }
                } else emptyList()

                // For non-native formats, continue using the existing image-parsing service as a compatibility fallback.
                val imageContext = if (imagePaths.isNotEmpty() && nativeImageParts.isEmpty()) {
                    val results = imagePaths.map { path ->
                        val fileName = java.nio.file.Paths.get(path).fileName.toString()
                        val description = stepFunClient.parseImage(path)
                            .getOrElse { e -> "[Image parsing failed: " + e.message + "]" }
                        "The parsing result of image `" + fileName + "`:\n> " + description
                    }
                    "\n\n" + results.joinToString("\n\n---\n\n")
                } else ""

                temporarySources.forEach { it.cleanupTemporaryFiles() }

                val searchResult = searchCoordinator.search(
                    userText,
                    useReadOnlyToolLoop = LlmProviderRegistry.get(settings.provider).capabilities.readOnlyTools
                )

                val relatedContext = searchResult.contextText



                val enrichedSearchContext = if (imageContext.isNotEmpty()) {
                    imageContext + "\n\n---\n\n" + relatedContext
                } else relatedContext

                val reasoningDetail = buildString {

                    appendLine("### Project Context Scan Result")

                    appendLine()

                    appendLine("User question: $userText")

                    appendLine()

                    if (projectStructure.isNotBlank()) {

                        appendLine("✅ Scanned project structure (${projectStructure.lines().size} lines)")

                    }

                    if (relatedContext.isNotBlank()) {

                        appendLine("✅ Searched related source files (${relatedContext.lines().size} lines)")

                    }

                    if (projectStructure.isBlank() && relatedContext.isBlank()) {

                        appendLine("⚠️ No project context found")

                    }

                }



                val context = buildString {

                    if (projectStructure.isNotBlank()) {

                        appendLine("[Project Structure Reference]")

                        append(projectStructure)

                        appendLine()

                    }

                    if (enrichedSearchContext.isNotBlank()) {

                        appendLine("[Related Source File Content]")

                        append(enrichedSearchContext)

                    }

                }



                val transientContext = listOf(context, attachmentAndSelectionContext)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n---\n\n")



                // 5. Switch back to the EDT, show the analysis reasoning process + start answering

                ApplicationManager.getApplication().invokeLater {

                    if (!finishAttachmentPreparation(preparationGeneration) || project.isDisposed) return@invokeLater

                    analysisAnimTimer.stop()

                    messagesPanel.remove(analysisLabel)



                    // Show the collapsible reasoning area with the analysis result

                    if (reasoningDetail.isNotBlank()) {

                        val analysisBubble = MessageBubble(

                            project = project,

                            role = MessageBubble.Role.ASSISTANT,

                            content = I18n.tr("chat.analysis.complete"),

                            reasoning = if (DeepSeekSettings.instance.thinkingEnabled) reasoningDetail else null

                        )

                        messagesPanel.add(analysisBubble, fillWidthConstraints)

                        messagesPanel.add(Box.createVerticalStrut(12), fillWidthConstraints)

                        revalidateAndScroll()

                    }



                    // Reuse respondDirectly (already rendered + already saved; pass userAlreadyRendered = true to skip duplication)

                    respondDirectly(
                        userText,
                        userAlreadyRendered = true,
                        transientContext = transientContext,
                        currentUserParts = nativeImageParts
                    )

                }



            } catch (e: Exception) {

                temporarySources.forEach { it.cleanupTemporaryFiles() }

                ApplicationManager.getApplication().invokeLater {

                    if (!finishAttachmentPreparation(preparationGeneration) || project.isDisposed) return@invokeLater

                    analysisAnimTimer.stop()

                    messagesPanel.remove(analysisLabel)

                    addMessageLabel("⚠️ " + I18n.tr("chat.analysis.failed") + " ${e.message}")

                    respondDirectly(
                        userText,
                        userAlreadyRendered = true,
                        transientContext = attachmentAndSelectionContext
                    )

                }

            }

        }

    }



    // ==================================================================

    // Agent mode — scan project, call AI, apply file changes

    // ==================================================================



    /** Maximum file content length sent as context to the AI. */

    internal fun buildProjectStructure(maxFiles: Int = 30): String {

        val sb = StringBuilder()

        sb.appendLine("The current project file structure and contents are as follows:\n")

        try {

            val contentRoots = ProjectRootManager.getInstance(project).contentSourceRoots

            for (root in contentRoots) {

                collectFilesForContext(root.path, sb, maxFiles, 0)

            }

        } catch (_: Exception) {

            sb.appendLine("(Unable to read the project file structure)")

        }

        return sb.toString()

    }



    private fun collectFilesForContext(

        rootPath: String,

        sb: StringBuilder,

        maxFiles: Int,

        depth: Int

    ) {

        if (depth > 15) return

        val entries = try {

            fileAccess.listDirectory(rootPath, project)

        } catch (_: Exception) { return }

        for (entry in entries) {

            if (sb.count { it == '\n' } >= maxFiles * 3) return

            if (entry.isDirectory) {

                val name = entry.name

                if (name.startsWith(".") || name == "node_modules" || name == "build" ||

                    name == "target" || name == ".gradle" || name == "idea" ||

                    name == "out" || name == "dist" || name == ".git"

                ) continue

                collectFilesForContext(entry.path, sb, maxFiles, depth + 1)

            } else if (isSourceExt(entry.name.substringAfterLast('.', ""))) {

                val relativePath = entry.path

                val content = try {

                    fileAccess.readFile(entry.path, project)

                } catch (_: Exception) { null }

                if (content == null || content.length > 8000) continue

                sb.appendLine("--- $relativePath ---")

                sb.appendLine(content)

                sb.appendLine()

            }

        }

    }



    private fun isSourceExt(ext: String?): Boolean = ChatContextComposer.isSourceExt(ext)



    /**

     * Reads file content from an @filename reference as context.

     */

    private fun buildRefContext(projectDir: java.io.File?, text: String): List<String> =
        ChatContextComposer.buildRefContext(projectDir, text)




    /**

     * Direct-answer mode: does not read source files or scan directories; it only sends the user's question to the AI.

     */

    private fun buildQaSystemPrompt(text: String): String = buildString {
        appendLine(DOMAIN_RESTRICTION_PROMPT)
        appendLine()
        appendLine("You are an AI coding assistant that helps users answer technical questions accurately and concisely.")
        appendLine("Prefer verifiable conclusions and the necessary code. Do not output hidden chains of thought; when an explanation is needed, give only a concise justification.")
        appendLine(if (DeepSeekSettings.instance.language == "en") "Please reply in English." else "Please reply in English.")

        val skillsContent = unifiedSettingsPanel.getEnabledSkillsContent(text)
        if (skillsContent.isNotBlank()) {
            appendLine()
            append(skillsContent)
        }
    }

    internal fun buildProjectFileTree(maxFiles: Int = 200): String {
        val base = project.basePath?.let { java.io.File(it) } ?: return ""
        val ignored = setOf(".git", ".gradle", ".idea", "build", "out", "target", "node_modules")
        val paths = mutableListOf<String>()

        fun visit(directory: java.io.File) {
            if (paths.size >= maxFiles || Thread.currentThread().isInterrupted) return
            directory.listFiles()
                ?.sortedWith(compareBy<java.io.File>({ !it.isDirectory }, { it.name.lowercase() }))
                ?.forEach { child ->
                    if (paths.size >= maxFiles || Thread.currentThread().isInterrupted) return
                    if (child.isDirectory) {
                        if (child.name !in ignored && !child.name.startsWith('.')) visit(child)
                    } else if (isSourceExt(child.extension.lowercase())) {
                        paths += child.relativeTo(base).invariantSeparatorsPath
                    }
                }
        }

        visit(base)
        return buildString {
            appendLine("Current project file paths:")
            paths.forEach { appendLine("- $it") }
            if (paths.size >= maxFiles) appendLine("- ...(file list truncated)")
        }
    }

    private fun composeQaRequest(
        systemPrompt: String,
        transientContext: String,
        currentUserParts: List<ChatContentPart>
    ): QaComposedRequest {
        val settings = DeepSeekSettings.instance
        val provider = LlmProviderRegistry.get(settings.provider)
        return QaRequestComposer.compose(
            systemPrompt = systemPrompt,
            history = messageHistory.toList(),
            transientContext = transientContext,
            budget = QaRequestBudget(
                maxContextTokens = provider.capabilities.maxContextTokens,
                reservedOutputTokens = settings.maxTokens.coerceAtMost(provider.capabilities.maxContextTokens / 2)
            ),
            currentUserParts = currentUserParts
        )
    }

    private fun respondDirectly(
        text: String,
        userAlreadyRendered: Boolean = false,
        displayText: String = text,
        transientContext: String = "",
        currentUserParts: List<ChatContentPart> = emptyList()
    ) {

        val settings = DeepSeekSettings.instance



        if (!userAlreadyRendered) {

            messageHistory.add(ChatMessage("user", text))

            renderUserMessage(displayText)

            currentSession().lastActiveTime = System.currentTimeMillis()

            saveSessions()

        }



        if (!settings.streamingEnabled) {

            respondDirectlySync(text, transientContext, currentUserParts)

            return

        }



        removeMessagesFiller()

        val streamBubble = MessageBubble(project, MessageBubble.Role.STREAMING)

        val streamTextArea = createStreamingArea(streamBubble)

        sendStopButton.text = I18n.tr("chat.stop.enter")

        sendStopButton.toolTipText = I18n.tr("chat.tooltip.stop")



        // ── Smooth output: token buffer queue + scheduled flush ──

        val tokenBuffer = StringBuilder()

        val reasoningBuffer = StringBuilder()  // accumulate reasoning_content

        val flushTimer = Timer(40) {

            if (tokenBuffer.isNotEmpty()) {

                val full = tokenBuffer.toString()

                tokenBuffer.clear()

                val maxChars = when (DeepSeekSettings.instance.outputSpeedLevel) {
                    1 -> 20
                    2 -> 8
                    3 -> 3
                    else -> Int.MAX_VALUE
                }
                val take = minOf(full.length, maxChars)
                streamTextArea.append(full.substring(0, take))
                if (take < full.length) tokenBuffer.append(full.substring(take))

                streamTextArea.revalidate()

                messagesPanel.revalidate()

                scrollToBottom()

            }

        }.apply { isRepeats = true }



        val onTokenBlock: (String) -> Unit = { token ->

            ApplicationManager.getApplication().invokeLater {

                if (thinkingTimer?.isRunning == true) { thinkingTimer?.stop(); thinkingTimer = null; streamTextArea.text = "" }

                tokenBuffer.append(token)

                val s = chatState.get()

                if (s is ChatState.Streaming) s.buffer.append(token)

                if (!flushTimer.isRunning) flushTimer.start()

            }

        }

        val onCompleteBlock: (String, Usage?) -> Unit = { fullResponse, usage ->

            ApplicationManager.getApplication().invokeLater {

                flushTimer.stop()

                // Flush the remaining buffer

                if (tokenBuffer.isNotEmpty()) {

                    streamTextArea.append(tokenBuffer.toString())

                    tokenBuffer.clear()

                    streamTextArea.revalidate()

                    messagesPanel.revalidate()

                }

                stopThinkingAnimation()

                val oldState = chatState.getAndSet(ChatState.Idle)

                if (oldState is ChatState.Streaming) {
                    oldState.eventSource.cancel()
                    removeStreamingArea(oldState, revalidateAfter = false)
                }

                // Parse the reasoning process and final answer from the response

                val parsed = parseThinkingResponse(fullResponse)

                val reasoning = if (DeepSeekSettings.instance.thinkingEnabled) (parsed.third ?: reasoningBuffer.toString().ifEmpty { null }) else null

                val displayContent = parsed.second

                messageHistory.add(ChatMessage("assistant", displayContent, reasoning = reasoning, usage = usage))

                renderAssistantMessage(displayContent, reasoning = reasoning, scrollAfter = false)

                usage?.let {
                    currentSession().totalTokens += it.totalTokens
                }

                saveSessions()
                sendStopButton.text = I18n.tr("chat.send.enter")
                sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")
                ensureMessagesFiller()
                revalidateAndScroll()

            }

        }

        val onErrorBlock: (Throwable) -> Unit = { error ->

            ApplicationManager.getApplication().invokeLater {

                flushTimer.stop()

                // Flush the remaining buffer

                if (tokenBuffer.isNotEmpty()) {

                    streamTextArea.append(tokenBuffer.toString())

                    tokenBuffer.clear()

                }

                stopThinkingAnimation()

                val oldState = chatState.getAndSet(ChatState.Idle)

                if (oldState is ChatState.Streaming) {
                    oldState.eventSource.cancel()
                    val partialContent = oldState.buffer.toString()
                    if (partialContent.isNotBlank()) {
                        val parsed = parseThinkingResponse(partialContent)
                        val partialReasoning = if (DeepSeekSettings.instance.thinkingEnabled)
                            (parsed.third ?: oldState.reasoningBuffer.toString().ifEmpty { null }) else null
                        messageHistory.add(ChatMessage("assistant", parsed.second, reasoning = partialReasoning))
                        renderAssistantMessage(parsed.second, reasoning = partialReasoning)
                        saveSessions()
                    }
                    removeStreamingArea(oldState)
                }

                addMessageLabel(I18n.tr("chat.error.prefix") + " " + error.message); sendStopButton.text = I18n.tr("chat.send.enter"); sendStopButton.toolTipText = I18n.tr("chat.tooltip.send"); ensureMessagesFiller(); scrollToBottom()

            }

        }



        val qaSystemPrompt = buildQaSystemPrompt(text)
        val composedRequest = composeQaRequest(
            systemPrompt = qaSystemPrompt,
            transientContext = transientContext,
            currentUserParts = currentUserParts
        )
        if (composedRequest.transientContextTruncated) {
            addMessageLabel(I18n.tr("chat.context.truncated"))
        }



        val deferredEventSource = DeferredEventSource()
        chatState.set(ChatState.Streaming(
            eventSource = deferredEventSource,
            bubble = streamBubble,
            flushTimer = flushTimer,
            thinkingTimer = thinkingTimer
        ))

        fun startAttempt(attempt: Int) {
            val eventSource = client.chatStream(
                messages = composedRequest.messages,
                onToken = onTokenBlock,
                onComplete = onCompleteBlock,
                onError = { error ->
                    val state = chatState.get()
                    val canRetry = attempt == 0 && state is ChatState.Streaming &&
                        state.eventSource === deferredEventSource && state.buffer.isEmpty() &&
                        isRetryableQaError(error)
                    if (canRetry) {
                        ApplicationManager.getApplication().executeOnPooledThread {
                            try { Thread.sleep(500) } catch (_: InterruptedException) { return@executeOnPooledThread }
                            val current = chatState.get()
                            if (current is ChatState.Streaming && current.eventSource === deferredEventSource) {
                                startAttempt(attempt + 1)
                            }
                        }
                    } else {
                        onErrorBlock(error)
                    }
                },
                onReasoningToken = { token ->
                    reasoningBuffer.append(token)
                    val state = chatState.get()
                    if (state is ChatState.Streaming) state.reasoningBuffer.append(token)
                }
            )
            deferredEventSource.attach(eventSource)
        }
        startAttempt(0)

    }



    /**

     * Synchronous answer mode (used when streaming is off): calls the API once and renders the full result.

     */

    private fun respondDirectlySync(
        text: String,
        transientContext: String,
        currentUserParts: List<ChatContentPart>
    ) {

        val settings = DeepSeekSettings.instance

        val qaSystemPrompt = buildQaSystemPrompt(text)
        val composedRequest = composeQaRequest(
            systemPrompt = qaSystemPrompt,
            transientContext = transientContext,
            currentUserParts = currentUserParts
        )



        addMessageLabel(I18n.tr("chat.info.syncing"))

        ApplicationManager.getApplication().executeOnPooledThread {

            val result = client.chatSync(composedRequest.messages)

            ApplicationManager.getApplication().invokeLater {

                result.onSuccess { fullResponse ->

                    val parsed = parseThinkingResponse(fullResponse)

                    val displayContent = parsed.second

                    val reasoning = if (DeepSeekSettings.instance.thinkingEnabled) parsed.third else null

                    messageHistory.add(ChatMessage("assistant", displayContent, reasoning = reasoning))

                    renderAssistantMessage(displayContent, reasoning = reasoning)

                    currentSession().lastActiveTime = System.currentTimeMillis()

                    saveSessions()

                    ensureMessagesFiller()

                    scrollToBottom()

                    sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

                }.onFailure { error ->

                    addMessageLabel(I18n.tr("chat.error.prefix") + " " + error.message)

                    sendStopButton.text = I18n.tr("chat.send.enter")

                    sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

                    ensureMessagesFiller()

                    scrollToBottom()

                }

            }

        }

    }



    private fun sendAgentMessage(userText: String) {
        val imageFiles = collectImageAttachments()
        if (imageFiles.isEmpty()) {
            launchAgentMessage(userText)
            return
        }

        val temporarySources = attachedFiles.toList()
        val fileContext = buildTextFileContext()
        val selectionContext = selectedContext?.let { context ->
            buildString {
                appendLine("[${context.fileName}:${context.startLine}-${context.endLine}]")
                appendLine("```")
                appendLine(context.snippet)
                appendLine("```")
            }
        }.orEmpty()
        val imagePaths = imageFiles.map { it.absolutePath }

        inputArea.text = ""
        attachedFiles.clear()
        refreshFileAttachmentPanel()
        setSelectedContext(null)
        val generation = beginAttachmentPreparation()

        attachmentPreparationTask = ApplicationManager.getApplication().executeOnPooledThread {
            val preparation = runCatching {
                val imageContext = imagePaths.joinToString("\n\n") { path ->
                    val fileName = java.nio.file.Paths.get(path).fileName.toString()
                    val description = stepFunClient.parseImage(path)
                        .getOrElse { error -> "[Image parsing failed: ${error.message}]" }
                    "The following is the parsing result of image `$fileName`:\n> $description"
                }
                buildString {
                    append(fileContext)
                    append(selectionContext)
                    if (selectionContext.isNotBlank()) appendLine()
                    if (imageContext.isNotBlank()) {
                        appendLine(imageContext)
                        appendLine()
                    }
                    append(userText)
                }
            }
            temporarySources.forEach { it.cleanupTemporaryFiles() }
            ApplicationManager.getApplication().invokeLater {
                if (!finishAttachmentPreparation(generation) || project.isDisposed) return@invokeLater
                preparation.fold(
                    onSuccess = { launchAgentMessage(it) },
                    onFailure = { error ->
                        addMessageLabel(I18n.tr("chat.error.prefix") + " " + (error.message ?: I18n.tr("attachment.prepare.failed")))
                    }
                )
            }
        }
    }

    private fun isRetryableQaError(error: Throwable): Boolean {
        if (error is java.io.IOException) return true
        val code = (error as? ApiException)?.httpCode ?: return false
        return code == 408 || code == 429 || code >= 500
    }

    private fun launchAgentMessage(userText: String) {

        pipelineTotalTokens = 0

        phase0RetryCount = 0

        val settings = DeepSeekSettings.instance



        inputArea.text = ""



        // Build file context and user message

        val fileContext = buildTextFileContext()

        val finalText = buildString {

            if (fileContext.isNotEmpty()) {

                append(fileContext)

            }

            val ctx = selectedContext

            if (ctx != null) {

                appendLine("[${ctx.fileName}:${ctx.startLine}-${ctx.endLine}]")

                appendLine("```")

                appendLine(ctx.snippet)

                appendLine("```")

                appendLine()

            }

            append(userText)

        }

        attachedFiles.clear()
        refreshFileAttachmentPanel()

        setSelectedContext(null)



        // Add the message to the session first, then render the bubble (ensures the delete button can find the correct message index)

        messageHistory.add(ChatMessage("user", finalText))

        renderUserMessage(finalText)

        currentSession().lastActiveTime = System.currentTimeMillis()

        saveSessions()



        // Build project structure context

        val projectStructure = buildProjectStructure()



        // ── Search for related context via SearchCoordinator ──

        val searchResult = searchCoordinator.search(finalText)

        val relatedContext = searchResult.contextText

        val hasRelatedContext = relatedContext.isNotEmpty()



        // Source roots hint

        val sourceRoots = getSourceRootPaths()

        val sourceRootsHint = if (sourceRoots.isNotEmpty()) {

            "The project source roots are:\n" + sourceRoots.joinToString("\n") { "- $it" }

        } else ""



        // Get the skill content (shared by all phases)

        val skillsContent = unifiedSettingsPanel.getEnabledSkillsContent(userText)



        // ════════════════════════════════════════════════════════════

        //  Intent-confirmation Phase — decided by the Agent Pipeline configuration

        // ════════════════════════════════════════════════════════════

        val p0Provider = LlmProviderRegistry.get(settings.agentPhase0Provider)

        val p0ApiKey = p0Provider.apiKey(settings.toSnapshot())



        val p1Provider = LlmProviderRegistry.get(settings.agentPhase1Provider)

        val p1Model = settings.agentPhase1Model



        if (settings.agentPhase0Enabled && p0ApiKey.isNotBlank()) {

            // Phase 0 configured → perform intent confirmation first

            startIntentConfirmation(

                userText = userText,

                projectStructure = projectStructure,

                relatedContext = relatedContext,

                finalText = finalText,

                sourceRootsHint = sourceRootsHint,

                skillsContent = skillsContent

            )

        } else if (settings.agentPhase1Enabled) {

            // Phase 0 not configured / disabled → enter the planning phase

            addMessageLabel(phaseTransitionLabel(p1Model, "planning"))

            startPlanPhase(

                finalText = finalText,

                projectStructure = projectStructure,

                relatedContext = relatedContext,

                sourceRootsHint = sourceRootsHint,

                skillsContent = skillsContent

            )

        } else {

            // Both Phase 0 and Phase 1 disabled → enter the coding phase directly

            val p2Provider = LlmProviderRegistry.get(settings.agentPhase2Provider)

            val snap = settings.toSnapshot()
            val p2Config = Pair(p2Provider.baseUrl(snap), p2Provider.apiKey(snap))

            val p2Model = settings.agentPhase2Model

            addMessageLabel(I18n.tr("chat.agent.single.file"))

            addMessageLabel(phaseTransitionLabel(p2Model, "coding"))

            startCodePhase(

                p2Config = p2Config,

                p2Model = p2Model,

                planResponse = finalText,

                projectStructure = projectStructure,

                relatedContext = relatedContext,

                sourceRootsHint = sourceRootsHint,

                skillsContent = skillsContent,

                finalText = finalText,

                directFromPhase0 = true

            )

        }

    }



    // ════════════════════════════════════════════════════════════════

    //  Intent-confirmation Phase — Agnes understands the requirement; only after user confirmation does it enter the planning phase

    // ════════════════════════════════════════════════════════════════



    /**

     * Intent confirmation and loop:

     * 1. Call Agnes to analyze the user's requirement and produce a one-sentence summary

     * 2. Show Yes/No buttons for the user to confirm

     * 3. Yes → enter the planning Agent

     * 4. No → the user adds notes → call Agnes again combining the previous analysis result → return to step 2

     */

    private fun startIntentConfirmation(

        userText: String,

        projectStructure: String,

        relatedContext: String,

        finalText: String,

        sourceRootsHint: String,

        skillsContent: String,

        previousAnalysis: String? = null

    ) {

        val s = DeepSeekSettings.instance

        val p0Provider = LlmProviderRegistry.get(s.agentPhase0Provider)

        val snap = s.toSnapshot()
        val phase0Config = Pair(p0Provider.baseUrl(snap), p0Provider.apiKey(snap))

        val phase0Model = s.agentPhase0Model



        addMessageLabel("🤔 " + p0Provider.displayName +  I18n.tr("chat.agent.understanding") + "...")



        // ═══ Animated loading indicator (spinning characters) replacing the static label ═══

        val (analysisLabel, animTimer) = createAnalysisAnimation(p0Provider.displayName, "chat.agent.understanding")

        val analysisLabelAdded = messagesPanel.componentCount - 2



        val analysisPrompt = buildString {

            appendLine(DOMAIN_RESTRICTION_PROMPT)

            appendLine()

            appendLine("You are a requirements analysis assistant. Analyze the user's needs for the codebase and output only a JSON object (no markdown code blocks, no other text):")

            appendLine()

            appendLine("{")

            appendLine("  \"intent\": \"A one-sentence summary of the user's core intent\",")

            appendLine("  \"isClear\": true,")

            appendLine("  \"complexity\": \"simple\",    // simple=simple task / complex=complex task")

            appendLine("  \"workload\": \"The number of files involved and the scope of changes\",")

            appendLine("  \"dependency\": \"Dependencies and coupling between files\",")

            appendLine("  \"reason\": \"Based on the combined workload and relatedness, explain why this is judged simple or complex\"")

            appendLine("}")

            appendLine("## Project Structure")

            appendLine(projectStructure)

            if (relatedContext.isNotBlank()) {

                appendLine("## Related Source Files")

                appendLine(relatedContext.take(1000))

            }

            appendLine()

            if (previousAnalysis != null) {

                appendLine("Your previous analysis: $previousAnalysis")

                appendLine("User's additional notes: $userText")

                appendLine("Please re-analyze taking the additional information into account and output in the format above.")

            } else {

                appendLine("User requirement: $userText")

            }

        }



        // Run the network request on a background thread to avoid blocking the EDT

        ApplicationManager.getApplication().executeOnPooledThread {

            val result = client.chatSyncWithExplicitConfig(

                baseUrl = phase0Config.first,

                apiKey = phase0Config.second,

                model = phase0Model,

                temperature = 0.3,

                maxTokens = 512,

                messages = listOf(ChatMessage("user", analysisPrompt)),

                protocol = p0Provider.protocol

            )



            // Switch back to the EDT for UI updates

            ApplicationManager.getApplication().invokeLater {

                animTimer.stop()

                if (analysisLabelAdded < messagesPanel.componentCount && messagesPanel.getComponent(analysisLabelAdded) === analysisLabel) {

                    messagesPanel.remove(analysisLabel)

                }

                revalidateAndScroll()

                result.onSuccess { interpretation ->

                    // Parse the JSON returned by the AI

                    val cleanInterpretation = interpretation.trim()

                    System.err.println("[Phase0] raw response: $cleanInterpretation")

                    var intent: String = ""

                    var isClear: Boolean = true

                    var complexity: String = "complex"

                    var workload: String = ""

                    var dependency: String = ""

                    var reason: String = ""

                    try {

                        val jsonBody = cleanInterpretation

                            .substringAfter("{")

                            .let { it.substringBeforeLast("}") }

                        val jsonText = "{$jsonBody}"

                        fun extractStr(json: String, key: String): String? {

                            val r = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)

                            return r?.groupValues?.get(1)?.trim()

                        }

                        fun extractBool(json: String, key: String): Boolean? {

                            val r = Regex("\"$key\"\\s*:\\s*(true|false)").find(json)

                            return r?.groupValues?.get(1)?.toBoolean()

                        }

                        val rawComplexity = (extractStr(jsonText, "complexity") ?: "").lowercase()

                        intent = extractStr(jsonText, "intent")

                            ?: cleanInterpretation.removePrefix("Analysis:").removePrefix("Analysis result:").trim()

                        isClear = extractBool(jsonText, "isClear") ?: true

                        complexity = if (rawComplexity in listOf("simple", "complex")) rawComplexity else "complex"

                        workload = extractStr(jsonText, "workload") ?: ""

                        dependency = extractStr(jsonText, "dependency") ?: ""

                        reason = extractStr(jsonText, "reason") ?: ""

                    } catch (e: Exception) {

                        System.err.println("[Phase0] JSON parse error: ${e.message}")

                        intent = cleanInterpretation.removePrefix("Analysis:").removePrefix("Analysis result:").trim()

                    }



                    // Build the detailed display information

                    val detailLines = buildString {

                        appendLine(intent)

                        if (workload.isNotEmpty()) appendLine("📊 Workload: $workload")

                        if (dependency.isNotEmpty()) appendLine("🔗 Relatedness: $dependency")

                        if (complexity == "simple") appendLine("📋 Verdict: simple task → code directly")

                        else appendLine("📋 Verdict: complex task → run the full pipeline")

                    }

                    addMessageLabel(I18n.tr("chat.agent.interprets") + " " + p0Provider.displayName)

                    addMessageLabel(detailLines)



                    // ── Confirmation buttons ──

                    val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {

                        isOpaque = false

                        maximumSize = Dimension(Short.MAX_VALUE.toInt(), 40)

                    }



                    val p1ProviderLocal = LlmProviderRegistry.get(s.agentPhase1Provider)

                    val snap = s.toSnapshot()
                    val p1ConfigLocal = Pair(p1ProviderLocal.baseUrl(snap), p1ProviderLocal.apiKey(snap))

                    val p1ModelLocal = s.agentPhase1Model



                    val yesBtn = JButton(I18n.tr("chat.yes.this.is.what.i.mean")).apply {

                        addActionListener {

                            messagesPanel.remove(buttonPanel)

                            revalidateAndScroll()

                            if (complexity == "simple" || !s.agentPhase1Enabled) {

                                // Simple task or Phase 1 disabled → skip planning and review, enter the coding phase directly

                                addMessageLabel(I18n.tr("chat.agent.single.file"))

                                val p2Provider = LlmProviderRegistry.get(s.agentPhase2Provider)

                                val snap = s.toSnapshot()
                                val p2Config = Pair(p2Provider.baseUrl(snap), p2Provider.apiKey(snap))

                                val p2Model = s.agentPhase2Model

                                addMessageLabel(phaseTransitionLabel(p2Model, "coding"))

                                startCodePhase(

                                    p2Config = p2Config,

                                    p2Model = p2Model,

                                    planResponse = finalText,

                                    projectStructure = projectStructure,

                                    relatedContext = relatedContext,

                                    sourceRootsHint = sourceRootsHint,

                                    skillsContent = skillsContent,

                                    finalText = finalText,

                                    directFromPhase0 = true

                                )

                            } else {

                                // Complex task → run the full pipeline

                                addMessageLabel(phaseTransitionLabel(p1ModelLocal, "planning"))

                                startPlanPhase(

                                    finalText = finalText,

                                    projectStructure = projectStructure,

                                    relatedContext = relatedContext,

                                    sourceRootsHint = sourceRootsHint,

                                    skillsContent = skillsContent

                                )

                            }

                        }

                    }

                    val noBtn = JButton(I18n.tr("chat.no.i.need.to.supplement")).apply {

                        addActionListener {

                            messagesPanel.remove(buttonPanel)

                            revalidateAndScroll()

                            addMessageLabel(I18n.tr("chat.info.supplement"))



                            // Set the callback, waiting for the user to add notes in the input area

                            pendingConfirmation = { clarification ->

                                // Recursion — call Agnes again with the additional notes + previous analysis

                                startIntentConfirmation(

                                    userText = clarification,

                                    projectStructure = projectStructure,

                                    relatedContext = relatedContext,

                                    finalText = finalText,

                                    sourceRootsHint = sourceRootsHint,

                                    skillsContent = skillsContent,

                                    previousAnalysis = intent

                                )

                            }

                        }

                    }



                    buttonPanel.add(yesBtn)

                    buttonPanel.add(noBtn)

                    messagesPanel.add(buttonPanel, fillWidthConstraints)

                    messagesPanel.add(Box.createVerticalStrut(4), fillWidthConstraints)

                    revalidateAndScroll()



                }.onFailure { error ->

                    if (phase0RetryCount < MAX_PHASE0_RETRIES) {

                        phase0RetryCount++

                        addMessageLabel("--- " + p0Provider.displayName + " retry ${phase0RetryCount}/${MAX_PHASE0_RETRIES} ---")

                        startIntentConfirmation(

                            userText = userText,

                            projectStructure = projectStructure,

                            relatedContext = relatedContext,

                            finalText = finalText,

                            sourceRootsHint = sourceRootsHint,

                            skillsContent = skillsContent

                        )

                        return@invokeLater

                    }

                    addMessageLabel(I18n.tr("chat.phase0.failed") + " " + error.message + I18n.tr("chat.phase0.failed.suffix"))

                    if (s.agentPhase1Enabled) {

                        val p1ProviderErr = LlmProviderRegistry.get(s.agentPhase1Provider)

                        val p1ModelErr = s.agentPhase1Model

                        addMessageLabel(phaseTransitionLabel(p1ModelErr, "planning"))

                        startPlanPhase(

                            finalText = finalText,

                            projectStructure = projectStructure,

                            relatedContext = relatedContext,

                            sourceRootsHint = sourceRootsHint,

                            skillsContent = skillsContent

                        )

                    } else {

                        addMessageLabel(I18n.tr("chat.agent.single.file"))

                        val p2ProviderErr = LlmProviderRegistry.get(s.agentPhase2Provider)

                        val snap = s.toSnapshot()
                        val p2ConfigErr = Pair(p2ProviderErr.baseUrl(snap), p2ProviderErr.apiKey(snap))

                        val p2ModelErr = s.agentPhase2Model

                        addMessageLabel(phaseTransitionLabel(p2ModelErr, "coding"))

                        startCodePhase(

                            p2Config = p2ConfigErr,

                            p2Model = p2ModelErr,

                            planResponse = finalText,

                            projectStructure = projectStructure,

                            relatedContext = relatedContext,

                            sourceRootsHint = sourceRootsHint,

                            skillsContent = skillsContent,

                            finalText = finalText,

                            directFromPhase0 = true

                        )

                    }

                }

            }

        }

    }



    // ════════════════════════════════════════════════════════════════

    //  Phase 1: planning Agent — DeepSeek-V4-Pro

    // ════════════════════════════════════════════════════════════════



    private fun startPlanPhase(

        finalText: String,

        projectStructure: String,

        relatedContext: String,

        sourceRootsHint: String,

        skillsContent: String

    ) {

        val s = DeepSeekSettings.instance

        val p1Provider = LlmProviderRegistry.get(s.agentPhase1Provider)

        val snap = s.toSnapshot()
        val p1Config = Pair(p1Provider.baseUrl(snap), p1Provider.apiKey(snap))

        val p1Model = s.agentPhase1Model



        // Build the system prompt for the planning phase (built inside startPlanPhase to avoid duplication with sendAgentMessage)

        val planSystemPrompt = buildString {

            appendLine(DOMAIN_RESTRICTION_PROMPT)

            appendLine()

            appendLine("You are the planning Agent (Planner) of the code assistant. Your task is to analyze the user's requirement and, together with the project structure and related source files, produce a detailed code-change plan.")

            appendLine()

            appendLine("## Project Structure")

            appendLine(projectStructure)

            appendLine()

            if (relatedContext.isNotEmpty()) {

                appendLine("## Related Source File Content")

                appendLine(relatedContext)

                appendLine()

            }

            if (sourceRootsHint.isNotEmpty()) {

                appendLine("## Project Source Roots")

                appendLine(sourceRootsHint)

                appendLine()

            }

            appendLine("## Output Format")

            appendLine("Please output a structured plan that includes:")

            appendLine("1. A list of files to create/modify/delete, with the path and operation type for each file")

            appendLine("2. Detailed change points or new-content descriptions for each file")

            appendLine("3. Dependencies or notes (e.g. an interface must be created before its implementation)")

            appendLine()

            appendLine("Output in clear Markdown format.")

            if (skillsContent.isNotBlank()) {

                append(skillsContent)

            }

        }



        removeMessagesFiller()

        val planBubble = MessageBubble(project, MessageBubble.Role.STREAMING)

        val planTextArea = createStreamingArea(planBubble)

        sendStopButton.text = I18n.tr("chat.stop.enter")

        sendStopButton.toolTipText = I18n.tr("chat.tooltip.stop")



        val (onToken, onComplete, onError, onReasoningToken) = createPhaseCallbacks(

            textArea = planTextArea,

            errorLabelKey = I18n.tr("chat.planning.agent.error"),

            onPhaseComplete = { fullResponse, _ ->

                // ── Transition to Phase 2 ──

                val p2Provider = LlmProviderRegistry.get(s.agentPhase2Provider)

                val snap = s.toSnapshot()
                val p2Config = Pair(p2Provider.baseUrl(snap), p2Provider.apiKey(snap))

                val p2Model = s.agentPhase2Model

                addMessageLabel(phaseTransitionLabel(p2Model, "coding"))

                startCodePhase(p2Config, p2Model, fullResponse, projectStructure,

                    relatedContext, sourceRootsHint, skillsContent, finalText,

                    protocol = p2Provider.protocol)

            }

        )



        val eventSource = client.chatStreamWithExplicitConfig(

            baseUrl = p1Config.first, apiKey = p1Config.second,

            model = p1Model, temperature = 0.7, maxTokens = 4096,

            messages = listOf(ChatMessage("system", planSystemPrompt), ChatMessage("user", finalText)),

            protocol = p1Provider.protocol,

            onToken = onToken, onComplete = onComplete, onError = onError,

            onReasoningToken = onReasoningToken

        )

        chatState.set(ChatState.Streaming(eventSource = eventSource, bubble = planBubble))

    }



    // ════════════════════════════════════════════════════════════════

    //  Phase 2: coding Agent — DeepSeek-V4-Flash

    // ════════════════════════════════════════════════════════════════



    private fun startCodePhase(

        p2Config: Pair<String, String>,

        p2Model: String,

        planResponse: String,

        projectStructure: String,

        relatedContext: String,

        sourceRootsHint: String,

        skillsContent: String,

        finalText: String,

        directFromPhase0: Boolean = false,

        protocol: String = "openai"

    ) {

        removeMessagesFiller()

        val codeBubble = MessageBubble(project, MessageBubble.Role.STREAMING)

        val codeTextArea = createStreamingArea(codeBubble)

        sendStopButton.text = I18n.tr("chat.stop.enter")

        sendStopButton.toolTipText = I18n.tr("chat.tooltip.stop")



        val systemPrompt = buildString {

            appendLine(DOMAIN_RESTRICTION_PROMPT)

            appendLine()

            appendLine("You are the coding Agent (Coder) of the code assistant. Your task is to generate concrete code changes based on ${if (directFromPhase0) "the user's requirements" else "the plan produced by the planning Agent"}.")

            appendLine()

            appendLine("## Project Structure")

            appendLine(projectStructure)

            appendLine()

            if (relatedContext.isNotEmpty()) {

                appendLine("## Related Source File Content")

                appendLine(relatedContext)

                appendLine()

            }

            if (sourceRootsHint.isNotEmpty()) {

                appendLine("## Project Source Roots")

                appendLine(sourceRootsHint)

                appendLine()

                appendLine("**Important: when creating new files you must use one of the existing source roots above; do not create a new source root.**")

                appendLine()

            }

            appendLine(if (directFromPhase0) "## User Requirements" else "## Planning Agent's Plan")

            appendLine(planResponse)

            appendLine()

            appendLine("## Output Format")

            appendLine("For each file to create or modify, use the following format:")

            appendLine()

            appendLine("<file path=\"relative/path/filename.extension\">")

            appendLine("```language-name")

            appendLine("File content...")

            appendLine("```")

            appendLine("</file>")

            appendLine()

            appendLine("To delete a file, use:")

            appendLine("<file path=\"relative/path/filename.extension\" action=\"delete\"></file>")

            appendLine()

            appendLine("## Rules")

            appendLine("1. All paths are relative to the project root")

            appendLine("2. For existing files, add/delete/modify based on the related source file content and output the **complete new file content** (do not omit anything)")

            appendLine("3. [Important] When creating a new file, it must be placed under an existing package directory; creating a new top-level source directory is forbidden")

            appendLine("4. Prefer the frameworks, patterns, and coding style already used in the project")

            appendLine("5. After outputting the file changes, briefly summarize what you changed")

            appendLine("6. The project's startup class must be placed in the same directory level as the controller, service, entity packages, not inside a sub-package")

            if (skillsContent.isNotBlank()) append(skillsContent)

        }



        val (onToken, onComplete, onError, onReasoningToken) = createPhaseCallbacks(

            textArea = codeTextArea,

            errorLabelKey = I18n.tr("chat.coding.agent.error"),

            onPhaseComplete = { fullResponse, _ ->

                // Parse and execute the file operations

                val operations = fileOperationExecutor.parseFileOperations(fullResponse)

                if (operations.isNotEmpty()) {

                    fileOperationExecutor.applyFileOperations(operations, planResponse)

                }



                // ── Transition to Phase 3 ──

                val s = DeepSeekSettings.instance

                if (directFromPhase0 || !s.agentPhase3Enabled) {

                    addMessageLabel(I18n.tr("chat.info.skip.review"))

                    finalizeAgentSession()

                } else {

                    val p3Provider = LlmProviderRegistry.get(s.agentPhase3Provider)

                    val p3ApiKey = p3Provider.apiKey(s.toSnapshot())

                    if (p3ApiKey.isNotBlank()) {

                        val p3Config = Pair(p3Provider.baseUrl(s.toSnapshot()), p3ApiKey)

                        val p3Model = s.agentPhase3Model

                        addMessageLabel(phaseTransitionLabel(p3Model, "reviewing"))

                        startReviewPhase(p3Config, p3Model, planResponse, fullResponse,

                            protocol = p3Provider.protocol)

                    } else {

                        addMessageLabel(I18n.tr("chat.info.skip.review"))

                        finalizeAgentSession()

                    }

                }

            }

        )



        val eventSource = client.chatStreamWithExplicitConfig(

            baseUrl = p2Config.first, apiKey = p2Config.second,

            model = p2Model, temperature = 0.7, maxTokens = 8192,

            messages = listOf(ChatMessage("system", systemPrompt), ChatMessage("user", "Generate the code according to the plan above.")),

            protocol = protocol,

            onToken = onToken, onComplete = onComplete, onError = onError,

            onReasoningToken = onReasoningToken

        )

        chatState.set(ChatState.Streaming(eventSource = eventSource, bubble = codeBubble))

    }



    // ════════════════════════════════════════════════════════════════

    //  Phase 3: review Agent — Agnes-2.0-Flash

    // ════════════════════════════════════════════════════════════════



    private fun startReviewPhase(

        reviewConfig: Pair<String, String>,

        reviewModel: String,

        planResponse: String,

        codeResponse: String,

        protocol: String = "openai"

    ) {

        removeMessagesFiller()

        val reviewBubble = MessageBubble(project, MessageBubble.Role.STREAMING)

        val reviewTextArea = createStreamingArea(reviewBubble)

        sendStopButton.text = I18n.tr("chat.stop.enter")

        sendStopButton.toolTipText = I18n.tr("chat.tooltip.stop")



        val systemPrompt = buildString {

            appendLine(DOMAIN_RESTRICTION_PROMPT)

            appendLine()

            appendLine("You are a professional code-review Agent (Reviewer). Your task is to review the code changes produced by the coding Agent.")

            appendLine()

            appendLine("## Original Plan")

            appendLine(planResponse)

            appendLine()

            appendLine("## Generated Code")

            appendLine(codeResponse)

            appendLine()

            appendLine("## Review Points")

            appendLine("Please review along the following dimensions:")

            appendLine("1. ✅ **Correctness**: Does the code correctly implement the requirements in the plan?")

            appendLine("2. 🔒 **Security**: Are there any security vulnerabilities?")

            appendLine("3. ⚡ **Performance**: Are there any performance issues?")

            appendLine("4. 📐 **Coding standards**: Does it comply with the coding standards?")

            appendLine("5. 📋 **Completeness**: Were all files generated correctly?")

            appendLine()

            appendLine("If problems are found, point out their locations and suggest changes. If there are no problems, give an overall assessment.")

        }



        val (onToken, onComplete, onError, onReasoningToken) = createPhaseCallbacks(

            textArea = reviewTextArea,

            errorLabelKey = I18n.tr("chat.review.agent.error"),

            onPhaseComplete = { _, _ ->
                finalizeAgentSession()

            }

        )



        val eventSource = client.chatStreamWithExplicitConfig(

            baseUrl = reviewConfig.first, apiKey = reviewConfig.second,

            model = reviewModel, temperature = 0.3, maxTokens = 2048,

            messages = listOf(ChatMessage("system", systemPrompt), ChatMessage("user", "Please review the code changes above.")),

            protocol = protocol,

            onToken = onToken, onComplete = onComplete, onError = onError,

            onReasoningToken = onReasoningToken

        )

        chatState.set(ChatState.Streaming(eventSource = eventSource, bubble = reviewBubble))

    }



    /** Clears the current streaming state and resets the send button. */

    internal fun cleanupStreamingAndResetButton() {

        val oldState = chatState.getAndSet(ChatState.Idle)

        if (oldState is ChatState.Streaming) {

            oldState.eventSource.cancel()

            removeStreamingArea(oldState)

        }

        sendStopButton.text = I18n.tr("chat.send.enter")

        sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

    }



    /** Finishes the Agent conversation (save and fill). */

    internal fun finalizeAgentSession() {

        saveSessions()

        if (pipelineTotalTokens > 0) {

            addMessageLabel("--- Pipeline Total: ${pipelineTotalTokens} tokens ---")

        }

        sendStopButton.text = I18n.tr("chat.send.enter")

        sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

        ensureMessagesFiller()

        scrollToBottom()

    }



    internal fun getSourceRootPaths(): List<String> {

        val roots = mutableListOf<String>()

        try {

            // Source roots (src/main/java, src/main/kotlin, etc.)

            val sourceRoots = ProjectRootManager.getInstance(project).contentSourceRoots

            for (root in sourceRoots) {

                roots.add(root.path.replace('\\', '/'))

            }

            // Also include content roots (the module root)

            val contentRoots = ProjectRootManager.getInstance(project).contentRoots

            for (root in contentRoots) {

                val path = root.path.replace('\\', '/')

                if (path !in roots) roots.add(path)

            }

        } catch (_: Exception) { }



        // Also scan the file system for common source directories

        val basePath = project.basePath ?: return roots

        val baseDir = java.io.File(basePath)

        val commonDirs = listOf(

            "src/main/java", "src/main/kotlin", "src/test/java", "src/test/kotlin",

            "src/main/resources", "src/test/resources"

        )

        for (dir in commonDirs) {

            val f = java.io.File(baseDir, dir)

            if (f.exists()) {

                val path = f.canonicalPath.replace('\\', '/')

                if (path !in roots) roots.add(path)

            }

        }



        return roots

    }



    private fun stopThinkingAnimation() {

        thinkingTimer?.stop()

        thinkingTimer = null

    }

    private fun stopStreaming() {

        if (isPreparingAttachments) {
            attachmentPreparationGeneration++
            attachmentPreparationTask?.cancel(true)
            attachmentPreparationTask = null
            isPreparingAttachments = false
            inputArea.isEnabled = true
            preparationCancelUi?.invoke()
            preparationCancelUi = null
        }

        activeCliAttachmentBundle?.close()
        activeCliAttachmentBundle = null

        stopThinkingAnimation()

        val oldState = chatState.getAndSet(ChatState.Idle)

        if (oldState is ChatState.Streaming) {

            // Stop all running Timers so they don't touch components that have already been removed
            oldState.flushTimer?.stop()
            oldState.thinkingTimer?.stop()

            oldState.eventSource.cancel()

            // CLI Agent mode: destroy the child process (graceful destroy first, force-kill after 3s)

            oldState.process?.let { p ->

                p.destroy()

                Thread({

                    try {

                        if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()

                    } catch (_: Exception) {

                        p.destroyForcibly()

                    }

                }, "cli-agent-stop").apply { isDaemon = true; start() }

            }

            // Read the accumulated streaming content and render it as a complete ASSISTANT bubble
            val partialContent = oldState.buffer.toString()
            val partialReasoning = oldState.reasoningBuffer.toString().ifEmpty { null }

            if (partialContent.isNotBlank()) {
                val parsed = parseThinkingResponse(partialContent)
                val displayContent = parsed.second
                val reasoning = if (DeepSeekSettings.instance.thinkingEnabled)
                    (parsed.third ?: partialReasoning) else null
                messageHistory.add(ChatMessage("assistant", displayContent, reasoning = reasoning))
                renderAssistantMessage(displayContent, reasoning = reasoning)
                saveSessions()
            }

            removeStreamingArea(oldState)
        }

        sendStopButton.text = I18n.tr("chat.send.enter")

        sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

        ensureMessagesFiller()

        scrollToBottom()

    }



    internal fun saveSessions() {

        // Debounce: multiple calls within 500ms are merged into a single disk write

        saveTimer.restart()

    }



    /** The actual persistence logic (triggered by the debounce timer) */

    private fun doSaveSessions() {

        currentSession().lastActiveTime = System.currentTimeMillis()

        sessionStore.save(sessions, sessionCounter)

    }



    // ════════════════════════════════════════════════════════════════

    //  CardLayout navigation — chat view ↔ settings view

    // ════════════════════════════════════════════════════════════════



    /**

     * Switch back to the main chat view from the settings panel.

     */

    fun showChatView() {

        (layout as CardLayout).show(this, "chat")

    }



    /**

     * Switch to the unified settings panel, showing the given sub-page.

     */

    fun showSettingsPage(pageKey: String) {

        (layout as CardLayout).show(this, "settings")

        unifiedSettingsPanel.showPage(pageKey)

    }



    /**

     * Switch to the unified settings panel.

     */

    private fun showSkillSettings() {

        showSettingsPage("skillSettings")

    }



    /**

     * Switch to the full-coverage change management panel,

     * and refresh the record list from the in-memory store.

     */

    private fun showChangeManagement() {

        changeManagementPanel.refreshRecords()

        (layout as CardLayout).show(this, "changeManagement")

    }



    override fun dispose() {

        currentInstance = null

        stopThinkingAnimation()

        // Synchronous save on close (skips debounce, ensuring data is flushed to disk)

        saveTimer.stop()

        doSaveSessions()

    }



    // ════════════════════════════════════════════════════════════════

    //  Agentic Search helper methods

    // ════════════════════════════════════════════════════════════════



    /**

     * Determines whether the user's query is code-related.

     * Code queries use Agentic Search; documentation queries use RAG.

     */

    private fun isCodeQuery(query: String): Boolean = ChatContextComposer.isCodeQuery(query)




    /**

     * Single-round Agentic Search: extract keywords → grep → build context.

     * Used in single-round mode (agenticSearchMaxRounds <= 1).

     */

    private fun buildCodeSearchContext(query: String): String =
        ChatContextComposer.buildCodeSearchContext(query, agenticSearch)




    /**

     * Extracts search keywords from the user's query.

     */

    private fun extractSearchKeywords(query: String): List<String> =
        ChatContextComposer.extractSearchKeywords(query)




    /**

     * Based on the user's request text, silently searches for related source files and reads their full content.

     * Returns a formatted string for use in the Agent system prompt.

     */

    internal fun buildRelatedFileContext(userText: String): String =
        ChatContextComposer.buildRelatedFileContext(userText, project.basePath ?: "", agenticSearch)




    // ════════════════════════════════════════════════════════════════

    //  UI display truncation utilities

    // ════════════════════════════════════════════════════════════════



    /**

     * Truncates the content of all ```code blocks``` in the string to the first [maxLines] lines.

     * Affects only UI display, not the full message content.

     * Format:

     *   ```lang

     *   Line 1

     *   ...

     *   Line N (when N > maxLines)

     *   ......

     *   ```

     */

    private fun truncateCodeBlocks(text: String, maxLines: Int = 20): String =
        ChatContextComposer.truncateCodeBlocks(text, maxLines)




    // ==================================================================

    // Component builders — modular code block cards

    // ==================================================================



    /** Pending scroll-positioning task marker — merges multiple scrollToBottom calls to avoid EDT pile-up while streaming. */
    // The three settle-state fields below may only be read/written on the EDT (scrollToBottom/settleOnce and all callers run on the EDT).
    private var settlePending = false

    /** settleOnce is programmatically writing the scroll position — the viewport ChangeListener uses this to exclude its own scrolling, avoiding a false user-input detection. */
    private var programmaticScrollInProgress = false

    /** Remaining scroll-settle catch-up count — limits nested re-layouts after asynchronous HTML layout. */
    private var settleBudget = 0

    /** The scrollbar maximum at the last positioning, used to determine whether the height is still changing. */
    private var settleLastMaximum = -1

    internal fun scrollToBottom() {
        // The user has scrolled up to view history: do not force-scroll back to the bottom
        if (!autoScrollController.followsBottom) return

        if (settlePending) {
            // A pending task already exists: increase the catch-up budget so it adapts to the latest height when it runs
            settleBudget = 5
            return
        }
        settlePending = true
        settleBudget = 5
        settleLastMaximum = -1
        SwingUtilities.invokeLater { settleOnce() }
    }

    private fun settleOnce() {
        settlePending = false
        if (!autoScrollController.followsBottom) return
        messagesPanel.revalidate()
        messagesScrollPane.validate()
        val vsb = messagesScrollPane.verticalScrollBar
        // The user is dragging the scrollbar: let the user win, skip this positioning; mouseReleased resumes following after release
        if (vsb.valueIsAdjusting) return
        programmaticScrollInProgress = true
        try {
            vsb.value = (vsb.maximum - vsb.visibleAmount).coerceAtLeast(vsb.minimum)
        } finally {
            programmaticScrollInProgress = false
        }
        // Markdown JEditorPane performs asynchronous HTML layout: its height can
        // keep changing for several EDT passes. Keep settling until the maximum
        // stabilizes (or the retry budget is exhausted) so the final answer
        // is never left partially below the viewport.
        val maxChanged = vsb.maximum != settleLastMaximum
        settleLastMaximum = vsb.maximum
        // Streaming in progress: the next flush will call scrollToBottom again, so there is no need to catch up here;
        // Only continue catching up until the height stabilizes after streaming ends.
        if (chatState.get() !is ChatState.Streaming && maxChanged && settleBudget > 0) {
            settleBudget--
            settlePending = true
            SwingUtilities.invokeLater { settleOnce() }
        }
    }



    /**

     * Add a simple text label (system messages, tokens, errors).

     */

    internal fun addMessageLabel(text: String, scrollAfter: Boolean = true): JBTextArea {

        showMessages()
        removeMessagesFiller()

        val label = JBTextArea(text).apply {

            isEditable = false

            lineWrap = true

            wrapStyleWord = true

            font = JBUI.Fonts.create("Monospaced", 12)

            foreground = PluginTheme.textPrimary()

            background = messagesPanel.background

            margin = JBUI.insets(4, 8, 4, 8)

            border = JBUI.Borders.empty()

            // Ensure label fills available width

            alignmentX = Component.LEFT_ALIGNMENT

        }

        messagesPanel.add(label, fillWidthConstraints)

        messagesPanel.add(Box.createVerticalStrut(2), fillWidthConstraints)

        if (scrollAfter) revalidateAndScroll()

        return label

    }

    /**

     * Render a user message as a modern right-aligned chat bubble,

     * with an ✕ delete button in the top-right corner.

     */

    internal fun renderUserMessage(content: String) {

        showMessages()
        removeMessagesFiller()



        // Compute the index of this user message in session.messages

        val session = currentSession()

        val userMsgIndices = session.messages.indices

            .filter { session.messages[it].role == "user" }

        val currentUserIdx = userMessages.size

        val msgIndex = userMsgIndices.getOrNull(currentUserIdx)



        val onDeleteAction = if (msgIndex != null) {

            {

                deleteMessagePair(session, msgIndex)

            }

        } else null



        // When rendering the bubble, truncate code blocks to the first 20 lines (UI display only; the full content sent to the LLM is unaffected)

        val displayContent = truncateCodeBlocks(content, maxLines = 20)



        val bubble = MessageBubble(

            project, MessageBubble.Role.USER, displayContent,

            onDelete = onDeleteAction

        )

        // Add the bubble directly to messagesPanel (MessageBubble draws its own alignment and rounded background)

        messagesPanel.add(bubble, fillWidthConstraints)

        // Message spacing

        messagesPanel.add(Box.createVerticalStrut(4), fillWidthConstraints)



        // Track for nav sidebar (only user messages) — the nav sidebar keeps the full content

        userMessages.add(MessageEntry(bubble, content))

        revalidateAndScroll()

    }



    /**

     * Render a user message that includes image file names as file tabs.

     * Images are shown inline in the bubble while they're being parsed in the background.

     */

    private fun renderUserMessageWithImages(content: String, imageNames: List<String>) {

        showMessages()
        removeMessagesFiller()



        // Compute the index of this user message in session.messages

        val session = currentSession()

        val userMsgIndices = session.messages.indices

            .filter { session.messages[it].role == "user" }

        val currentUserIdx = userMessages.size

        val msgIndex = userMsgIndices.getOrNull(currentUserIdx)



        val onDeleteAction = if (msgIndex != null) {

            { deleteMessagePair(session, msgIndex) }

        } else null



        // When rendering the bubble, truncate code blocks to the first 20 lines (UI display only; the full content sent to the LLM is unaffected)

        val displayContent = truncateCodeBlocks(content, maxLines = 20)



        val bubble = MessageBubble(

            project, MessageBubble.Role.USER, displayContent,

            onDelete = onDeleteAction,

            fileTabs = imageNames

        )

        messagesPanel.add(bubble, fillWidthConstraints)

        messagesPanel.add(Box.createVerticalStrut(4), fillWidthConstraints)



        userMessages.add(MessageEntry(bubble, content))

        revalidateAndScroll()

    }



    /**

     * Render an assistant message — card style with accent bar + avatar.

     */

    internal fun renderAssistantMessage(
        content: String,
        reasoning: String? = null,
        scrollAfter: Boolean = true
    ) {

        showMessages()
        removeMessagesFiller()

        val bubble = MessageBubble(project, MessageBubble.Role.ASSISTANT, content, reasoning = reasoning)

        // Add the bubble directly to messagesPanel

        messagesPanel.add(bubble, fillWidthConstraints)

        messagesPanel.add(Box.createVerticalStrut(12), fillWidthConstraints)



        // Link this response to the most recent user question without a response yet

        val lastUserMsg = userMessages.lastOrNull { it.responsePanel == null }

        lastUserMsg?.responsePanel = bubble



        if (scrollAfter) {
            // The final bubble renders Markdown through JEditorPane, whose HTML layout
            // completes asynchronously. Force an extra layout pass before scrolling
            // so the viewport lands on the bottom of the *real* answer height.
            bubble.revalidate()
            messagesPanel.revalidate()
            scrollToBottom()
        }

    }



    /**

     * Create a temporary streaming message bubble.

     * Shows a "Thinking... ◐" spinning animation until the first token arrives.

     */

    internal fun createStreamingArea(bubble: MessageBubble): JBTextArea {
        removeMessagesFiller()

        // Add the bubble directly to messagesPanel

        messagesPanel.add(bubble, fillWidthConstraints)

        val spacer = Box.createVerticalStrut(12)
        bubble.putClientProperty("chat.streaming.spacer", spacer)
        messagesPanel.add(spacer, fillWidthConstraints)

        revalidateAndScroll()



        // ── Start thinking animation ──

        val textArea = bubble.streamTextArea!!

        spinnerIndex = 0

        thinkingTimer = Timer(300) {

            spinnerIndex = (spinnerIndex + 1) % spinnerChars.size

            val stepIdx = (spinnerIndex / 5) % thinkingStepKeys.size

            textArea.text = I18n.tr(thinkingStepKeys[stepIdx]) + " " + spinnerChars[spinnerIndex]

        }.apply { start() }

        return textArea

    }



    /**

     * Create a streaming bubble with a custom initial status text (e.g. "Parsing...").

     * Used for the image parsing phase before AI streaming begins.

     */

    private fun createParsingArea(bubble: MessageBubble, statusText: String = I18n.tr("chat.parsing")): JBTextArea {
        removeMessagesFiller()

        messagesPanel.add(bubble, fillWidthConstraints)

        val spacer = Box.createVerticalStrut(12)
        bubble.putClientProperty("chat.streaming.spacer", spacer)
        messagesPanel.add(spacer, fillWidthConstraints)

        revalidateAndScroll()



        val textArea = bubble.streamTextArea!!

        spinnerIndex = 0

        thinkingTimer = Timer(300) {

            spinnerIndex = (spinnerIndex + 1) % spinnerChars.size

            textArea.text = "... $statusText ${spinnerChars[spinnerIndex]}"

        }.apply { start() }

        return textArea
    }



    /** Remove the streaming bubble and stop the thinking animation. */

    internal fun removeStreamingArea(state: ChatState.Streaming, revalidateAfter: Boolean = true) {

        val bubble = state.bubble

        // Bubbles are currently placed directly in messagesPanel (no padded wrapper)

        messagesPanel.remove(bubble)
        (bubble.getClientProperty("chat.streaming.spacer") as? Component)?.let(messagesPanel::remove)

        if (revalidateAfter) {
            messagesPanel.revalidate()
            messagesPanel.repaint()
        }

    }



    // ════════════════════════════════════════════════════════════════

    //  Anchor dot timeline (left-side blue dots)

    // ════════════════════════════════════════════════════════════════



    // ════════════════════════════════════════════════════════════════

    //  (The old dotted timeline has been removed — replaced with a modern bubble-card layout)

    // ════════════════════════════════════════════════════════════════



    /**

     * Deletes a user message and its corresponding AI reply, then re-renders the conversation.

     */

    private fun deleteMessagePair(session: ChatSession, userMsgIndex: Int) {

        val indicesToRemove = mutableListOf(userMsgIndex)

        // If the next one is an AI reply, delete it too

        val nextIdx = userMsgIndex + 1

        if (nextIdx < session.messages.size && session.messages[nextIdx].role == "assistant") {

            indicesToRemove.add(nextIdx)

        }

        // Delete from the end so indexes stay valid

        indicesToRemove.sortedDescending().forEach { session.messages.removeAt(it) }



        // Re-render the whole conversation

        messagesPanel.removeAll()

        userMessages.clear()

        if (session.messages.isEmpty()) {

            showWelcome()

        } else {

            showMessages()

            addMessageLabel("=== ${session.name} ===")

            renderMessageRange(session, 0, session.messages.size)

            ensureMessagesFiller()

        }

        saveSessions()

        scrollToBottom()

    }



    internal fun revalidateAndScroll() {

        messagesPanel.revalidate()

        messagesPanel.repaint()

        scrollToBottom()

    }



    /**

     * Remove the vertical filler from messagesPanel, so messages take their

     * natural height and the scroll pane viewport expands gradually as content

     * streams in (little-by-little expansion).

     */

    internal fun removeMessagesFiller() {

        messagesPanel.remove(verticalFiller)

    }



    /**

     * Ensure the vertical filler is present at the end of messagesPanel.

     * The filler has weighty=1.0 so it expands to take any extra vertical space,

     * pushing messages to the top and eliminating blank space below.

     */

    internal fun ensureMessagesFiller() {
        if (messagesPanel.componentCount > 0 &&
            messagesPanel.getComponent(messagesPanel.componentCount - 1) === verticalFiller) return
        messagesPanel.remove(verticalFiller)

        messagesPanel.add(verticalFiller, GridBagConstraints().apply {

            fill = GridBagConstraints.BOTH

            weightx = 1.0

            weighty = 1.0

            gridwidth = GridBagConstraints.REMAINDER

        })

    }



    // ════════════════════════════════════════════════════════════════

    //  Shared animation helper — extracted to eliminate duplication

    // ════════════════════════════════════════════════════════════════



    /**

     * Creates a spinning-character animation label and appends it to [messagesPanel].

     * The label shows "🤔 {displayName} {statusText} ◐" with a rotating spinner.

     *

     * @param providerDisplayName Provider display name shown in the label

     * @param statusKey           i18n key for the status text (e.g. "chat.agent.analyzing")

     * @return Pair of (label, timer) — caller must stop the timer and remove the label when done

     */

    internal fun createAnalysisAnimation(providerDisplayName: String, statusKey: String): Pair<JBTextArea, Timer> {

        val animChars = "◐◓◑◒"

        val label = JBTextArea("🤔 $providerDisplayName ${I18n.tr(statusKey)} ◐").apply {

            isEditable = false

            lineWrap = true

            wrapStyleWord = true

            font = JBUI.Fonts.create("Monospaced", 14)

            foreground = PluginTheme.textPrimary()

            background = messagesPanel.background

            margin = JBUI.insets(4, 8, 4, 8)

            border = JBUI.Borders.empty()

            alignmentX = Component.LEFT_ALIGNMENT

        }

        messagesPanel.add(label, fillWidthConstraints)

        messagesPanel.add(Box.createVerticalStrut(2), fillWidthConstraints)

        revalidateAndScroll()



        val timer = Timer(300) {

            val idx = (System.currentTimeMillis() / 300).toInt() % 4

            label.text = "🤔 $providerDisplayName ${I18n.tr(statusKey)} ${animChars[idx]}"

        }

        timer.start()

        return Pair(label, timer)

    }



    // ════════════════════════════════════════════════════════════════

    //  Shared streaming callback factory — extracted to eliminate duplication

    // ════════════════════════════════════════════════════════════════



    /**

     * Creates standard streaming callbacks (onToken, onComplete, onError) for pipeline phases.

     * Handles EDT scheduling, streaming cleanup, message persistence, and token tracking.

     *

     * @param textArea       The text area to append tokens to

     * @param errorLabelKey  i18n key for the error message label

     * @param onPhaseComplete Phase-specific logic to run after the common onComplete handling

     * @return Triple of (onToken, onComplete, onError)

     */

    internal fun createPhaseCallbacks(

        textArea: JBTextArea,

        errorLabelKey: String,

        onPhaseComplete: (fullResponse: String, usage: Usage?) -> Unit

    ): PhaseCallbacksWithReasoning {

        val reasoningBuffer = StringBuilder()
        val tokenBuffer = StringBuilder()
        val flushTimer = Timer(40) {
            if (tokenBuffer.isNotEmpty()) {
                val full = tokenBuffer.toString()
                tokenBuffer.clear()
                val maxChars = when (DeepSeekSettings.instance.outputSpeedLevel) {
                    1 -> 20
                    2 -> 8
                    3 -> 3
                    else -> Int.MAX_VALUE
                }
                val take = minOf(full.length, maxChars)
                textArea.append(full.substring(0, take))
                if (take < full.length) tokenBuffer.append(full.substring(take))
                messagesPanel.revalidate()
                scrollToBottom()
            }
        }.apply { isRepeats = true }

        val onToken: (String) -> Unit = { token ->

            ApplicationManager.getApplication().invokeLater {
                if (thinkingTimer?.isRunning == true) { thinkingTimer?.stop(); thinkingTimer = null; textArea.text = "" }
                tokenBuffer.append(token)
                if (!flushTimer.isRunning) flushTimer.start()
            }

        }

        val onReasoningToken: (String) -> Unit = { token ->

            reasoningBuffer.append(token)

        }

        val onComplete: (String, Usage?) -> Unit = { fullResponse, usage ->

            ApplicationManager.getApplication().invokeLater {
                flushTimer.stop()
                if (tokenBuffer.isNotEmpty()) {
                    textArea.append(tokenBuffer.toString())
                    tokenBuffer.clear()
                    messagesPanel.revalidate()
                    scrollToBottom()
                }

                if (chatState.get() is ChatState.Streaming)

                    cleanupStreamingAndResetButton()

                val reasoning = reasoningBuffer.toString().ifEmpty { null }

                messageHistory.add(ChatMessage("assistant", fullResponse, reasoning = reasoning, usage = usage))

                renderAssistantMessage(fullResponse, reasoning = reasoning, scrollAfter = false)

                usage?.let {
                    currentSession().totalTokens += it.totalTokens
                }

                revalidateAndScroll()

                onPhaseComplete(fullResponse, usage)

            }

        }

        val onError: (Throwable) -> Unit = { error ->

            ApplicationManager.getApplication().invokeLater {
                flushTimer.stop()
                if (tokenBuffer.isNotEmpty()) {
                    textArea.append(tokenBuffer.toString())
                    tokenBuffer.clear()
                    messagesPanel.revalidate()
                    scrollToBottom()
                }

                if (chatState.get() is ChatState.Streaming)

                    cleanupStreamingAndResetButton()

                addMessageLabel("$errorLabelKey ${error.message}")

                ensureMessagesFiller(); scrollToBottom()

            }

        }
        return PhaseCallbacksWithReasoning(onToken, onComplete, onError, onReasoningToken)

    }



    /** Callback bundle returned by [createPhaseCallbacks], includes reasoning token handler. */

    internal data class PhaseCallbacksWithReasoning(

        val onToken: (String) -> Unit,

        val onComplete: (String, Usage?) -> Unit,

        val onError: (Throwable) -> Unit,

        val onReasoningToken: (String) -> Unit

    )



    /** Format a phase transition label: "{prefix}{model}{suffix}" using i18n keys. */

    private fun phaseTransitionLabel(model: String, phaseKey: String): String =

        I18n.tr("chat.agent.${phaseKey}.prefix") + model + I18n.tr("chat.agent.${phaseKey}.suffix")



    // ════════════════════════════════════════════════════════════════

    //  Reasoning-process parsing — extract <thinking> blocks and the final answer from the AI response

    // ════════════════════════════════════════════════════════════════



    /**

     * Parses the AI response, extracting the reasoning process and the final answer.

     *

     * @param response raw AI response text

     * @return Triple(raw response, display content, reasoning text)

     *         - first:  raw full response

     *         - second: final answer part (content after removing <thinking> blocks)

     *         - third:  reasoning text (content inside <thinking> blocks), or null when there is no thinking block

     */

    private fun parseThinkingResponse(response: String): Triple<String, String, String?> {

        val thinkingRegex = Regex("<thinking>([\\s\\S]*?)</thinking>", RegexOption.MULTILINE)

        val match = thinkingRegex.find(response)



        if (match == null) {

            // No thinking block → treat the whole text as the final answer

            return Triple(response, response, null)

        }



        val thinkingContent = match.groupValues[1].trim()

        // Remove <thinking> blocks; the remainder is the display content

        val displayContent = response.replace(thinkingRegex, "").trim()



        return Triple(response, displayContent.ifEmpty { response }, thinkingContent.ifEmpty { null })

    }

}
