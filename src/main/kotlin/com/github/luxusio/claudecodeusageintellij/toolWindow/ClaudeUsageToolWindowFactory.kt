package com.github.luxusio.claudecodeusageintellij.toolWindow

import com.github.luxusio.claudecodeusageintellij.ClaudeBundle
import com.github.luxusio.claudecodeusageintellij.model.UsageSummary
import com.github.luxusio.claudecodeusageintellij.services.ClaudeUsageService
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.*
import javax.swing.*
import javax.swing.table.DefaultTableModel

class ClaudeUsageToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ClaudeUsagePanel()
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}

class ClaudeUsagePanel : JBPanel<ClaudeUsagePanel>(BorderLayout()) {

    private val usageService = ClaudeUsageService.getInstance()
    private val tokenFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.US)
    private val costFormat = DecimalFormat("$#,##0.00")

    private val todayTokensLabel = JBLabel("-")
    private val todayCostLabel = JBLabel("-")
    private val monthlyTokensLabel = JBLabel("-")
    private val monthlyCostLabel = JBLabel("-")
    private val statusLabel = JBLabel(ClaudeBundle.message("usage.loading"))
    private val refreshButton = JButton(ClaudeBundle.message("usage.refresh"))

    private val tableModel = DefaultTableModel(
        arrayOf(
            ClaudeBundle.message("usage.table.date"),
            ClaudeBundle.message("usage.table.tokens"),
            ClaudeBundle.message("usage.table.cost")
        ),
        0
    )
    private val historyTable = JBTable(tableModel)

    init {
        border = JBUI.Borders.empty(10)
        setupUI()
        loadUsageData()
    }

    private fun setupUI() {
        // Summary Panel
        val summaryPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyBottom(10)

            // Title
            add(JBLabel(ClaudeBundle.message("usage.title")).apply {
                font = font.deriveFont(Font.BOLD, 16f)
                border = JBUI.Borders.emptyBottom(10)
            })

            // Today's Usage
            add(createSectionPanel(ClaudeBundle.message("usage.today"), todayTokensLabel, todayCostLabel))

            // Monthly Usage
            add(Box.createVerticalStrut(10))
            add(createSectionPanel(ClaudeBundle.message("usage.monthly"), monthlyTokensLabel, monthlyCostLabel))
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
            loadUsageData(forceRefresh = true)
        }

        // Layout
        add(summaryPanel, BorderLayout.NORTH)
        add(tableScrollPane, BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.SOUTH)
    }

    private fun createSectionPanel(
        title: String,
        tokensLabel: JBLabel,
        costLabel: JBLabel
    ): JPanel {
        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1),
                JBUI.Borders.empty(8)
            )

            add(JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD, 13f)
            })

            add(Box.createVerticalStrut(5))

            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(JBLabel("${ClaudeBundle.message("usage.tokens")}: "))
                add(tokensLabel)
            })

            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(JBLabel("${ClaudeBundle.message("usage.cost")}: "))
                add(costLabel)
            })
        }
    }

    private fun loadUsageData(forceRefresh: Boolean = false) {
        statusLabel.text = ClaudeBundle.message("usage.loading")
        refreshButton.isEnabled = false

        usageService.refreshUsageAsync { usage ->
            updateUI(usage)
        }
    }

    private fun updateUI(usage: UsageSummary) {
        todayTokensLabel.text = tokenFormat.format(usage.todayTokens)
        todayCostLabel.text = costFormat.format(usage.todayCost)
        monthlyTokensLabel.text = tokenFormat.format(usage.monthlyTokens)
        monthlyCostLabel.text = costFormat.format(usage.monthlyCost)

        // Update table
        tableModel.rowCount = 0
        usage.dailyEntries.sortedByDescending { it.date }.forEach { entry ->
            tableModel.addRow(
                arrayOf(
                    entry.date,
                    tokenFormat.format(entry.tokensUsed),
                    costFormat.format(entry.costUsd)
                )
            )
        }

        statusLabel.text = if (usage == UsageSummary.EMPTY) {
            ClaudeBundle.message("usage.noData")
        } else {
            ClaudeBundle.message("usage.lastUpdated", java.text.SimpleDateFormat("HH:mm:ss").format(Date(usage.lastUpdated)))
        }
        refreshButton.isEnabled = true
    }
}
