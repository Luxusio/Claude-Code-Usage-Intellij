package com.github.luxusio.claudecodeusageintellij.settings

import com.github.luxusio.claudecodeusageintellij.ClaudeBundle
import com.github.luxusio.claudecodeusageintellij.services.ClaudeUsageService
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.text.NumberFormat
import javax.swing.JComponent
import javax.swing.JFormattedTextField
import javax.swing.JPanel
import javax.swing.text.NumberFormatter

class ClaudeUsageConfigurable : Configurable {

    private var mainPanel: JPanel? = null
    private var tokenLimitField: JFormattedTextField? = null
    private var refreshIntervalField: JBTextField? = null
    private var showInStatusBarCheckBox: JBCheckBox? = null

    override fun getDisplayName(): String = ClaudeBundle.message("settings.displayName")

    override fun createComponent(): JComponent {
        val numberFormat = NumberFormat.getIntegerInstance().apply {
            isGroupingUsed = true
        }
        val formatter = NumberFormatter(numberFormat).apply {
            minimum = 1000L
            maximum = 10_000_000L
            allowsInvalid = false
        }

        tokenLimitField = JFormattedTextField(formatter).apply {
            columns = 15
        }
        refreshIntervalField = JBTextField(5)
        showInStatusBarCheckBox = JBCheckBox(ClaudeBundle.message("settings.showInStatusBar"))

        mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                JBLabel(ClaudeBundle.message("settings.tokenLimit")),
                tokenLimitField!!,
                1,
                false
            )
            .addLabeledComponent(
                JBLabel(ClaudeBundle.message("settings.refreshInterval")),
                refreshIntervalField!!,
                1,
                false
            )
            .addComponent(showInStatusBarCheckBox!!, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return mainPanel!!
    }

    override fun isModified(): Boolean {
        val settings = ClaudeUsageSettings.getInstance()
        return tokenLimitField?.value != settings.tokenLimit ||
                refreshIntervalField?.text?.toIntOrNull() != settings.refreshIntervalSeconds ||
                showInStatusBarCheckBox?.isSelected != settings.showInStatusBar
    }

    override fun apply() {
        val settings = ClaudeUsageSettings.getInstance()
        (tokenLimitField?.value as? Number)?.let { settings.tokenLimit = it.toLong() }
        refreshIntervalField?.text?.toIntOrNull()?.let { settings.refreshIntervalSeconds = it }
        showInStatusBarCheckBox?.isSelected?.let { settings.showInStatusBar = it }

        // Update the service with new settings
        ClaudeUsageService.getInstance().tokenLimit = settings.tokenLimit
    }

    override fun reset() {
        val settings = ClaudeUsageSettings.getInstance()
        tokenLimitField?.value = settings.tokenLimit
        refreshIntervalField?.text = settings.refreshIntervalSeconds.toString()
        showInStatusBarCheckBox?.isSelected = settings.showInStatusBar
    }

    override fun disposeUIResources() {
        mainPanel = null
        tokenLimitField = null
        refreshIntervalField = null
        showInStatusBarCheckBox = null
    }
}
