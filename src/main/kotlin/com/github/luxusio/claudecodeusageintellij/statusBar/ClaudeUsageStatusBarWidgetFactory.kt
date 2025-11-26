package com.github.luxusio.claudecodeusageintellij.statusBar

import com.github.luxusio.claudecodeusageintellij.ClaudeBundle
import com.github.luxusio.claudecodeusageintellij.services.ClaudeUsageService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ClaudeUsageStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = "Claude Code Usage"

    override fun isAvailable(project: Project): Boolean = true

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

class ClaudeUsageStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private val usageService = ClaudeUsageService.getInstance()
    private val tokenFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.US)
    private var currentText: String = ClaudeBundle.message("statusBar.noData")
    private var tooltipText: String = ""

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var updateTask: ScheduledFuture<*>? = null

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

    override fun getText(): String = currentText

    override fun getTooltipText(): String = tooltipText

    override fun getAlignment(): Float = 0f

    override fun getClickConsumer(): Consumer<MouseEvent>? {
        return Consumer {
            // Open the tool window when clicked
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude Usage")
            toolWindow?.show()
        }
    }

    private fun startPeriodicUpdate() {
        updateTask = scheduler.scheduleAtFixedRate({
            updateWidget()
        }, 0, 5, TimeUnit.MINUTES)
    }

    private fun updateWidget() {
        usageService.refreshUsageAsync { usage ->
            currentText = if (usage.todayTokens > 0) {
                "${formatTokens(usage.todayTokens)} tokens"
            } else {
                ClaudeBundle.message("statusBar.noData")
            }

            tooltipText = ClaudeBundle.message("statusBar.tooltip", tokenFormat.format(usage.todayTokens))
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
}
