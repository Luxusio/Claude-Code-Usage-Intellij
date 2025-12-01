package com.github.luxusio.claudecodeusageintellij.toolWindow

import com.github.luxusio.claudecodeusageintellij.ClaudeBundle
import com.github.luxusio.claudecodeusageintellij.api.ClaudeUsageApiClient
import com.github.luxusio.claudecodeusageintellij.api.UsageResponse
import com.github.luxusio.claudecodeusageintellij.model.UsageSummary
import com.github.luxusio.claudecodeusageintellij.oauth.ClaudeOAuthService
import com.github.luxusio.claudecodeusageintellij.services.ClaudeUsageService
import com.github.luxusio.claudecodeusageintellij.settings.ClaudeUsageSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.table.DefaultTableModel

class ClaudeUsageToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ClaudeUsagePanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}

class ClaudeUsagePanel(private val project: Project) : JBPanel<ClaudeUsagePanel>(BorderLayout()) {

    private val oauthService = ClaudeOAuthService.getInstance()
    private val usageApiClient = ClaudeUsageApiClient.getInstance()
    private val usageService = ClaudeUsageService.getInstance()
    private val settings = ClaudeUsageSettings.getInstance()
    private val tokenFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.US)

    // Rate Limit Info (from API)
    private val sessionProgressBar = JProgressBar(0, 100).apply {
        isStringPainted = true
        preferredSize = JBUI.size(300, 24)
    }
    private val sessionResetLabel = JBLabel("-")
    private val weeklyProgressBar = JProgressBar(0, 100).apply {
        isStringPainted = true
        preferredSize = JBUI.size(300, 24)
    }
    private val weeklyResetLabel = JBLabel("-")
    private val sonnetProgressBar = JProgressBar(0, 100).apply {
        isStringPainted = true
        preferredSize = JBUI.size(300, 24)
    }
    private val sonnetResetLabel = JBLabel("-")

    // Auth UI
    private val loginButton = JButton("Login with Claude")
    private val logoutButton = JButton("Logout")
    private val authStatusLabel = JBLabel("Not authenticated")

    // Legacy: Session file based info
    private val usageProgressBar = JProgressBar(0, 100).apply {
        isStringPainted = true
        preferredSize = JBUI.size(200, 24)
    }
    private val usagePercentLabel = JBLabel("0%")
    private val sessionIdLabel = JBLabel("-")
    private val totalTokensLabel = JBLabel("-")
    private val limitLabel = JBLabel("-")
    private val inputTokensLabel = JBLabel("-")
    private val outputTokensLabel = JBLabel("-")
    private val cacheCreationLabel = JBLabel("-")
    private val cacheReadLabel = JBLabel("-")

    private val statusLabel = JBLabel(ClaudeBundle.message("usage.loading"))
    private val refreshButton = JButton(ClaudeBundle.message("usage.refresh"))

    // History table
    private val tableModel = object : DefaultTableModel(
        arrayOf(
            ClaudeBundle.message("usage.table.date"),
            ClaudeBundle.message("usage.table.tokens"),
            ClaudeBundle.message("usage.table.sessions")
        ),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val historyTable = JBTable(tableModel)

    // Store organization ID
    private var organizationId: String? = null

    init {
        usageService.tokenLimit = settings.tokenLimit
        border = JBUI.Borders.empty(10)
        setupUI()
        checkAuthAndLoadData()
    }

    private fun setupUI() {
        val mainPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            // Title
            add(JBLabel(ClaudeBundle.message("usage.title")).apply {
                font = font.deriveFont(Font.BOLD, 16f)
                border = JBUI.Borders.emptyBottom(10)
                alignmentX = LEFT_ALIGNMENT
            })

            // Auth Section
            add(createAuthSection())
            add(Box.createVerticalStrut(15))

            // Rate Limit Section (from API)
            add(createRateLimitSection())
            add(Box.createVerticalStrut(15))

            // Session Details Section
            add(createSessionDetailsSection())
            add(Box.createVerticalStrut(15))

            // Token Breakdown Section
            add(createTokenBreakdownSection())
        }

        // History Table
        historyTable.apply {
            fillsViewportHeight = true
            setShowGrid(true)
            tableHeader.reorderingAllowed = false
        }

        val tableScrollPane = JBScrollPane(historyTable).apply {
            border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1)
        }

        // Button Panel
        val buttonPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
            add(refreshButton)
            add(statusLabel)
        }

        refreshButton.addActionListener {
            checkAuthAndLoadData(forceRefresh = true)
        }

        loginButton.addActionListener {
            loginButton.isEnabled = false
            authStatusLabel.text = "Authenticating..."
            ApplicationManager.getApplication().executeOnPooledThread {
                oauthService.authenticate().thenAccept { success ->
                    ApplicationManager.getApplication().invokeLater {
                        if (success) {
                            updateAuthUI()
                            loadRateLimitData()
                        } else {
                            authStatusLabel.text = "Authentication failed"
                        }
                        loginButton.isEnabled = true
                    }
                }
            }
        }

        logoutButton.addActionListener {
            oauthService.logout()
            usageApiClient.clearCache()
            updateAuthUI()
        }

        // Layout
        add(mainPanel, BorderLayout.NORTH)
        add(tableScrollPane, BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.SOUTH)
    }

    private fun createAuthSection(): JPanel {
        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1),
                JBUI.Borders.empty(10)
            )

            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
                alignmentX = LEFT_ALIGNMENT
                add(loginButton)
                add(logoutButton)
                add(authStatusLabel)
            })
        }
    }

    private fun createRateLimitSection(): JPanel {
        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1),
                JBUI.Borders.empty(10)
            )

            add(JBLabel("Rate Limits (Live)").apply {
                font = font.deriveFont(Font.BOLD, 14f)
            })
            add(Box.createVerticalStrut(10))

            // Current Session
            add(JBLabel("Current Session").apply { font = font.deriveFont(Font.BOLD) })
            add(Box.createVerticalStrut(5))
            add(sessionProgressBar)
            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                alignmentX = LEFT_ALIGNMENT
                add(JBLabel("Resets: "))
                add(sessionResetLabel)
            })
            add(Box.createVerticalStrut(10))

            // Weekly (All Models)
            add(JBLabel("Weekly (All Models)").apply { font = font.deriveFont(Font.BOLD) })
            add(Box.createVerticalStrut(5))
            add(weeklyProgressBar)
            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                alignmentX = LEFT_ALIGNMENT
                add(JBLabel("Resets: "))
                add(weeklyResetLabel)
            })
            add(Box.createVerticalStrut(10))

            // Weekly (Sonnet Only)
            add(JBLabel("Weekly (Sonnet Only)").apply { font = font.deriveFont(Font.BOLD) })
            add(Box.createVerticalStrut(5))
            add(sonnetProgressBar)
            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                alignmentX = LEFT_ALIGNMENT
                add(JBLabel("Resets: "))
                add(sonnetResetLabel)
            })
        }
    }

    private fun createProgressSection(): JPanel {
        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1),
                JBUI.Borders.empty(10)
            )

            add(JBLabel(ClaudeBundle.message("usage.session")).apply {
                font = font.deriveFont(Font.BOLD, 14f)
            })
            add(Box.createVerticalStrut(10))

            // Progress bar with percentage
            add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
                alignmentX = LEFT_ALIGNMENT
                add(usageProgressBar, BorderLayout.CENTER)
                add(Box.createHorizontalStrut(10), BorderLayout.EAST)
            })

            add(Box.createVerticalStrut(5))

            // Usage text
            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                alignmentX = LEFT_ALIGNMENT
                add(totalTokensLabel)
                add(JBLabel(" / "))
                add(limitLabel)
                add(JBLabel(" ("))
                add(usagePercentLabel)
                add(JBLabel(")"))
            })
        }
    }

    private fun createSessionDetailsSection(): JPanel {
        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT

            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                alignmentX = LEFT_ALIGNMENT
                add(JBLabel("${ClaudeBundle.message("usage.sessionId")}: ").apply {
                    font = font.deriveFont(Font.BOLD)
                })
                add(sessionIdLabel)
            })
        }
    }

    private fun createTokenBreakdownSection(): JPanel {
        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1),
                JBUI.Borders.empty(10)
            )

            add(JBLabel("Token Breakdown").apply {
                font = font.deriveFont(Font.BOLD, 13f)
            })
            add(Box.createVerticalStrut(8))

            // Token breakdown grid
            add(createLabeledRow(ClaudeBundle.message("usage.inputTokens"), inputTokensLabel))
            add(Box.createVerticalStrut(4))
            add(createLabeledRow(ClaudeBundle.message("usage.outputTokens"), outputTokensLabel))
            add(Box.createVerticalStrut(4))
            add(createLabeledRow(ClaudeBundle.message("usage.cacheCreationTokens"), cacheCreationLabel))
            add(Box.createVerticalStrut(4))
            add(createLabeledRow(ClaudeBundle.message("usage.cacheReadTokens"), cacheReadLabel))
        }
    }

    private fun createLabeledRow(label: String, valueLabel: JBLabel): JPanel {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = LEFT_ALIGNMENT
            add(JBLabel("$label: ").apply {
                preferredSize = JBUI.size(120, preferredSize.height)
            })
            add(valueLabel)
        }
    }

    private fun checkAuthAndLoadData(forceRefresh: Boolean = false) {
        updateAuthUI()

        if (oauthService.isAuthenticated()) {
            loadRateLimitData(forceRefresh)
        }

        // Also load legacy session-based data
        loadUsageData(forceRefresh)
    }

    private fun updateAuthUI() {
        val isAuthenticated = oauthService.isAuthenticated()
        loginButton.isVisible = !isAuthenticated
        logoutButton.isVisible = isAuthenticated
        authStatusLabel.text = if (isAuthenticated) "Authenticated ✓" else "Not authenticated"
    }

    private fun loadRateLimitData(forceRefresh: Boolean = false) {
        ApplicationManager.getApplication().executeOnPooledThread {
            // First get organization ID if we don't have it
            if (organizationId == null) {
                organizationId = usageApiClient.fetchOrganizationId()
            }

            val orgId = organizationId
            if (orgId != null) {
                val usage = usageApiClient.fetchUsage(orgId, forceRefresh)
                ApplicationManager.getApplication().invokeLater {
                    if (usage != null) {
                        updateRateLimitUI(usage)
                    }
                }
            }
        }
    }

    private fun updateRateLimitUI(usage: UsageResponse) {
        // Update session progress
        usage.session?.let { session ->
            val percent = (session.usedPercent ?: 0.0).toInt().coerceIn(0, 100)
            sessionProgressBar.value = percent
            sessionProgressBar.string = "${percent}% used"
            sessionProgressBar.foreground = getProgressColor(percent)
            sessionResetLabel.text = session.getFormattedResetTime()
        }

        // Update weekly progress
        usage.weekly?.let { weekly ->
            val percent = (weekly.usedPercent ?: 0.0).toInt().coerceIn(0, 100)
            weeklyProgressBar.value = percent
            weeklyProgressBar.string = "${percent}% used"
            weeklyProgressBar.foreground = getProgressColor(percent)
            weeklyResetLabel.text = weekly.getFormattedResetDate()
        }

        // Update sonnet progress
        usage.weeklySonnet?.let { sonnet ->
            val percent = (sonnet.usedPercent ?: 0.0).toInt().coerceIn(0, 100)
            sonnetProgressBar.value = percent
            sonnetProgressBar.string = "${percent}% used"
            sonnetProgressBar.foreground = getProgressColor(percent)
            sonnetResetLabel.text = sonnet.getFormattedResetDate()
        }
    }

    private fun getProgressColor(percent: Int): Color {
        return when {
            percent >= 90 -> JBColor.RED
            percent >= 70 -> JBColor.ORANGE
            else -> JBColor(Color(0, 150, 0), Color(0, 200, 0))
        }
    }

    private fun loadUsageData(forceRefresh: Boolean = false) {
        statusLabel.text = ClaudeBundle.message("usage.loading")
        refreshButton.isEnabled = false

        usageService.refreshUsageAsync(project) { usage ->
            updateUI(usage)
            loadHistoryData()
        }
    }

    private fun loadHistoryData() {
        val dailyUsage = usageService.getDailyUsage(30)

        tableModel.rowCount = 0
        dailyUsage.forEach { entry ->
            tableModel.addRow(
                arrayOf(
                    entry.date.toString(),
                    tokenFormat.format(entry.totalTokens),
                    entry.sessionCount.toString()
                )
            )
        }
    }

    private fun updateUI(usage: UsageSummary) {
        // Update progress bar
        val percentage = usage.usagePercentage.toInt()
        usageProgressBar.value = percentage
        usageProgressBar.string = "${percentage}%"

        // Set progress bar color based on usage
        usageProgressBar.foreground = when {
            percentage >= 90 -> JBColor.RED
            percentage >= 70 -> JBColor.ORANGE
            else -> JBColor(Color(0, 150, 0), Color(0, 200, 0))
        }

        usagePercentLabel.text = String.format("%.1f%%", usage.usagePercentage)
        totalTokensLabel.text = tokenFormat.format(usage.totalTokens)
        limitLabel.text = tokenFormat.format(usage.tokenLimit)

        // Update session details
        sessionIdLabel.text = if (usage.sessionId.isNotEmpty()) usage.sessionId.take(8) + "..." else "-"

        // Update token breakdown
        inputTokensLabel.text = tokenFormat.format(usage.inputTokens)
        outputTokensLabel.text = tokenFormat.format(usage.outputTokens)
        cacheCreationLabel.text = tokenFormat.format(usage.cacheCreationTokens)
        cacheReadLabel.text = tokenFormat.format(usage.cacheReadTokens)

        // Update status
        statusLabel.text = if (usage == UsageSummary.EMPTY) {
            ClaudeBundle.message("usage.noData")
        } else {
            ClaudeBundle.message("usage.lastUpdated", SimpleDateFormat("HH:mm:ss").format(Date(usage.lastUpdated)))
        }
        refreshButton.isEnabled = true
    }
}
