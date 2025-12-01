package com.github.luxusio.claudecodeusageintellij.statusBar

import com.github.luxusio.claudecodeusageintellij.ClaudeBundle
import com.github.luxusio.claudecodeusageintellij.model.UsageSummary
import com.github.luxusio.claudecodeusageintellij.services.ClaudeUsageService
import com.github.luxusio.claudecodeusageintellij.settings.ClaudeUsageSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JPanel

class ClaudeUsageStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = "Claude Code Usage"

    override fun isAvailable(project: Project): Boolean {
        return ClaudeUsageSettings.getInstance().showInStatusBar
    }

    override fun createWidget(project: Project): StatusBarWidget {
        return ClaudeUsageStatusBarWidget(project)
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val WIDGET_ID = "ClaudeUsageStatusBarWidget"
    }
}

class ClaudeUsageStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {

    private var statusBar: StatusBar? = null
    private val usageService = ClaudeUsageService.getInstance()
    private val settings = ClaudeUsageSettings.getInstance()
    private val tokenFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.US)

    private var currentUsage: UsageSummary = UsageSummary.EMPTY
    private val widgetComponent = UsageProgressComponent()

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var updateTask: ScheduledFuture<*>? = null

    init {
        usageService.tokenLimit = settings.tokenLimit
        widgetComponent.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude Usage")
                toolWindow?.show()
            }
        })
    }

    override fun ID(): String = ClaudeUsageStatusBarWidgetFactory.WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        startPeriodicUpdate()
        updateWidget()
    }

    override fun dispose() {
        updateTask?.cancel(true)
        scheduler.shutdown()
    }

    override fun getTooltipText(): String {
        return ClaudeBundle.message(
            "statusBar.tooltip",
            formatTokens(currentUsage.totalTokens),
            formatTokens(currentUsage.tokenLimit),
            String.format("%.1f", currentUsage.usagePercentage)
        )
    }

    override fun getSelectedValue(): String {
        return if (currentUsage.totalTokens > 0) {
            "${formatTokens(currentUsage.totalTokens)} (${String.format("%.0f", currentUsage.usagePercentage)}%)"
        } else {
            ClaudeBundle.message("statusBar.noData")
        }
    }

    override fun getPopup(): com.intellij.openapi.ui.popup.ListPopup? = null

    fun getComponent(): JComponent = widgetComponent

    private fun startPeriodicUpdate() {
        val intervalSeconds = settings.refreshIntervalSeconds.toLong().coerceAtLeast(10)
        updateTask = scheduler.scheduleAtFixedRate({
            updateWidget()
        }, 0, intervalSeconds, TimeUnit.SECONDS)
    }

    private fun updateWidget() {
        usageService.refreshUsageAsync(project) { usage ->
            currentUsage = usage
            widgetComponent.updateUsage(usage)
            statusBar?.updateWidget(ID())
        }
    }

    private fun formatTokens(tokens: Long): String {
        return when {
            tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
            tokens >= 1_000 -> String.format("%.1fK", tokens / 1_000.0)
            else -> tokens.toString()
        }
    }

    /**
     * Custom component that displays a mini progress bar with usage info
     */
    inner class UsageProgressComponent : JPanel() {
        private var percentage: Double = 0.0
        private var displayText: String = ClaudeBundle.message("statusBar.noData")

        init {
            isOpaque = false
            preferredSize = JBUI.size(100, 16)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = getTooltipText()
        }

        fun updateUsage(usage: UsageSummary) {
            percentage = usage.usagePercentage
            displayText = if (usage.totalTokens > 0) {
                "${formatTokens(usage.totalTokens)} (${String.format("%.0f", percentage)}%)"
            } else {
                ClaudeBundle.message("statusBar.noData")
            }
            toolTipText = getTooltipText()
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val width = width
            val height = height
            val barHeight = 4
            val barY = height - barHeight - 2

            // Draw background bar
            g2.color = JBColor(Color(200, 200, 200), Color(60, 60, 60))
            g2.fillRoundRect(0, barY, width, barHeight, 2, 2)

            // Draw progress bar
            val progressWidth = ((percentage / 100.0) * width).toInt().coerceIn(0, width)
            g2.color = when {
                percentage >= 90 -> JBColor.RED
                percentage >= 70 -> JBColor.ORANGE
                else -> JBColor(Color(0, 150, 0), Color(0, 200, 0))
            }
            g2.fillRoundRect(0, barY, progressWidth, barHeight, 2, 2)

            // Draw text
            g2.color = JBColor.foreground()
            g2.font = JBUI.Fonts.smallFont()
            val fm = g2.fontMetrics
            val textY = barY - 2
            g2.drawString(displayText, 0, textY)
        }
    }
}
