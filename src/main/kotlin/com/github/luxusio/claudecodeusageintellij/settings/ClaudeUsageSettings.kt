package com.github.luxusio.claudecodeusageintellij.settings

import com.github.luxusio.claudecodeusageintellij.model.UsageSummary
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "ClaudeUsageSettings",
    storages = [Storage("ClaudeUsageSettings.xml")]
)
class ClaudeUsageSettings : PersistentStateComponent<ClaudeUsageSettings.State> {

    data class State(
        var tokenLimit: Long = UsageSummary.DEFAULT_TOKEN_LIMIT,
        var refreshIntervalSeconds: Int = 60,
        var showInStatusBar: Boolean = true
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var tokenLimit: Long
        get() = myState.tokenLimit
        set(value) {
            myState.tokenLimit = value
        }

    var refreshIntervalSeconds: Int
        get() = myState.refreshIntervalSeconds
        set(value) {
            myState.refreshIntervalSeconds = value
        }

    var showInStatusBar: Boolean
        get() = myState.showInStatusBar
        set(value) {
            myState.showInStatusBar = value
        }

    companion object {
        fun getInstance(): ClaudeUsageSettings {
            return ApplicationManager.getApplication().getService(ClaudeUsageSettings::class.java)
        }
    }
}
