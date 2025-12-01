package com.github.luxusio.claudecodeusageintellij.services

import com.github.luxusio.claudecodeusageintellij.model.ClaudeCommandUsageData
import com.github.luxusio.claudecodeusageintellij.model.DailyUsageEntry
import com.github.luxusio.claudecodeusageintellij.model.UsageSummary
import com.github.luxusio.claudecodeusageintellij.parser.ClaudeCommandParser
import com.github.luxusio.claudecodeusageintellij.parser.SessionFileLocator
import com.github.luxusio.claudecodeusageintellij.parser.SessionFileParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.File
import java.time.LocalDate

@Service(Service.Level.APP)
class ClaudeUsageService {

    private val sessionFileParser = SessionFileParser()
    private val sessionFileLocator = SessionFileLocator()
    private val commandParser = ClaudeCommandParser()

    private var cachedUsage: UsageSummary = UsageSummary.EMPTY
    private var cachedCommandUsage: ClaudeCommandUsageData = ClaudeCommandUsageData.EMPTY
    private var lastFetchTime: Long = 0
    private var lastCommandFetchTime: Long = 0
    private val cacheDurationMs: Long = 30_000 // 30 second cache

    // Token limit can be configured
    var tokenLimit: Long = UsageSummary.DEFAULT_TOKEN_LIMIT

    companion object {
        fun getInstance(): ClaudeUsageService {
            return ApplicationManager.getApplication().getService(ClaudeUsageService::class.java)
        }

        /**
         * Get the project-specific claude directory name
         */
        fun getProjectDirName(projectPath: String): String {
            return SessionFileLocator.getProjectDirName(projectPath)
        }
    }

    /**
     * Get usage for the current IDE project
     */
    fun getUsageForProject(project: Project?, forceRefresh: Boolean = false): UsageSummary {
        val projectPath = project?.basePath ?: return UsageSummary.EMPTY
        return getUsage(projectPath, forceRefresh)
    }

    /**
     * Get usage for a specific project path
     */
    fun getUsage(projectPath: String? = null, forceRefresh: Boolean = false): UsageSummary {
        val now = System.currentTimeMillis()
        if (!forceRefresh && (now - lastFetchTime) < cacheDurationMs && cachedUsage != UsageSummary.EMPTY) {
            return cachedUsage
        }

        return try {
            val usage = if (projectPath != null) {
                fetchUsageForProject(projectPath)
            } else {
                fetchLatestSessionUsage()
            }
            cachedUsage = usage
            lastFetchTime = now
            usage
        } catch (e: Exception) {
            thisLogger().warn("Failed to fetch Claude usage: ${e.message}", e)
            cachedUsage
        }
    }

    /**
     * Fetch usage for a specific project
     */
    private fun fetchUsageForProject(projectPath: String): UsageSummary {
        val latestSession = sessionFileLocator.findLatestSessionFileInProject(projectPath)

        if (latestSession == null) {
            thisLogger().info("No session file found for project: $projectPath")
            return UsageSummary.EMPTY.copy(tokenLimit = tokenLimit)
        }

        return parseSessionFile(latestSession)
    }

    /**
     * Fetch usage from the latest session across all projects
     */
    private fun fetchLatestSessionUsage(): UsageSummary {
        val latestSession = sessionFileLocator.findLatestSessionFile()

        if (latestSession == null) {
            thisLogger().info("No session files found")
            return UsageSummary.EMPTY.copy(tokenLimit = tokenLimit)
        }

        return parseSessionFile(latestSession)
    }

    /**
     * Parse a session JSONL file and extract usage data
     */
    private fun parseSessionFile(file: File): UsageSummary {
        return sessionFileParser.parseSessionFile(file, tokenLimit)
    }

    /**
     * Get daily usage statistics for historical view
     */
    fun getDailyUsage(days: Int = 30): List<DailyUsageEntry> {
        if (!sessionFileLocator.projectsDirExists()) {
            return emptyList()
        }

        val cutoffDate = LocalDate.now().minusDays(days.toLong())
        val dailyUsageMap = mutableMapOf<LocalDate, MutableList<Pair<String, UsageSummary>>>()

        sessionFileLocator.findAllSessionFiles().forEach { sessionFile ->
            try {
                val summary = parseSessionFile(sessionFile)
                val sessionDate = getSessionDate(sessionFile)

                if (sessionDate != null && !sessionDate.isBefore(cutoffDate)) {
                    dailyUsageMap.getOrPut(sessionDate) { mutableListOf() }
                        .add(summary.sessionId to summary)
                }
            } catch (e: Exception) {
                thisLogger().debug("Failed to parse session file: ${e.message}")
            }
        }

        return dailyUsageMap.map { (date, sessions) ->
            val uniqueSessions = sessions.distinctBy { it.first }
            DailyUsageEntry(
                date = date,
                inputTokens = uniqueSessions.sumOf { it.second.inputTokens },
                outputTokens = uniqueSessions.sumOf { it.second.outputTokens },
                cacheCreationTokens = uniqueSessions.sumOf { it.second.cacheCreationTokens },
                cacheReadTokens = uniqueSessions.sumOf { it.second.cacheReadTokens },
                totalTokens = uniqueSessions.sumOf { it.second.totalTokens },
                sessionCount = uniqueSessions.size
            )
        }.sortedByDescending { it.date }
    }

    /**
     * Get the date of a session from the first timestamp in the file
     */
    private fun getSessionDate(file: File): LocalDate? {
        return sessionFileParser.getSessionDate(file)
    }

    /**
     * Refresh usage asynchronously
     */
    fun refreshUsageAsync(project: Project?, callback: (UsageSummary) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val usage = getUsageForProject(project, forceRefresh = true)
            ApplicationManager.getApplication().invokeLater {
                callback(usage)
            }
        }
    }

    // ========== Command-based Usage Methods ==========

    /**
     * Get usage data from `claude /usage` command.
     * This provides session and weekly usage percentages.
     */
    fun getCommandUsage(forceRefresh: Boolean = false): ClaudeCommandUsageData {
        val now = System.currentTimeMillis()
        if (!forceRefresh && (now - lastCommandFetchTime) < cacheDurationMs && cachedCommandUsage != ClaudeCommandUsageData.EMPTY) {
            return cachedCommandUsage
        }

        return try {
            val usage = commandParser.fetchUsage()
            cachedCommandUsage = usage
            lastCommandFetchTime = now
            usage
        } catch (e: Exception) {
            thisLogger().warn("Failed to fetch Claude command usage: ${e.message}", e)
            cachedCommandUsage
        }
    }

    /**
     * Refresh command-based usage asynchronously
     */
    fun refreshCommandUsageAsync(callback: (ClaudeCommandUsageData) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val usage = getCommandUsage(forceRefresh = true)
            ApplicationManager.getApplication().invokeLater {
                callback(usage)
            }
        }
    }
}
