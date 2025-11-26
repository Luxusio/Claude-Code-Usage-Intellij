package com.github.luxusio.claudecodeusageintellij.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.luxusio.claudecodeusageintellij.model.ClaudeUsageResponse
import com.github.luxusio.claudecodeusageintellij.model.UsageSummary
import com.github.luxusio.claudecodeusageintellij.model.toUsageSummary
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
class ClaudeUsageService {

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private var cachedUsage: UsageSummary = UsageSummary.EMPTY
    private var lastFetchTime: Long = 0
    private val cacheDurationMs: Long = 60_000 // 1 minute cache

    companion object {
        fun getInstance(): ClaudeUsageService {
            return ApplicationManager.getApplication().getService(ClaudeUsageService::class.java)
        }
    }

    fun getUsage(forceRefresh: Boolean = false): UsageSummary {
        val now = System.currentTimeMillis()
        if (!forceRefresh && (now - lastFetchTime) < cacheDurationMs && cachedUsage != UsageSummary.EMPTY) {
            return cachedUsage
        }

        return try {
            val usage = fetchUsageFromCli()
            cachedUsage = usage
            lastFetchTime = now
            usage
        } catch (e: Exception) {
            thisLogger().warn("Failed to fetch Claude usage: ${e.message}")
            cachedUsage
        }
    }

    private fun fetchUsageFromCli(): UsageSummary {
        val command = if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf("cmd", "/c", "claude", "usage", "--output", "json")
        } else {
            listOf("claude", "usage", "--output", "json")
        }

        val processBuilder = ProcessBuilder(command)
        processBuilder.redirectErrorStream(true)

        val process = processBuilder.start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = StringBuilder()

        reader.use {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line)
            }
        }

        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("Claude CLI command timed out")
        }

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            thisLogger().warn("Claude CLI returned exit code $exitCode: $output")
            throw RuntimeException("Claude CLI failed with exit code $exitCode")
        }

        val jsonOutput = output.toString().trim()
        if (jsonOutput.isEmpty()) {
            return UsageSummary.EMPTY
        }

        return parseUsageJson(jsonOutput)
    }

    private fun parseUsageJson(json: String): UsageSummary {
        return try {
            val response = objectMapper.readValue(json, ClaudeUsageResponse::class.java)
            response.dailyUsage?.daily?.toUsageSummary() ?: UsageSummary.EMPTY
        } catch (e: Exception) {
            thisLogger().warn("Failed to parse usage JSON: ${e.message}")
            // Try alternative parsing for different JSON structures
            parseAlternativeJson(json)
        }
    }

    private fun parseAlternativeJson(json: String): UsageSummary {
        return try {
            // Try parsing as a simple usage object
            val node = objectMapper.readTree(json)

            val tokensUsed = node.path("tokensUsed").asLong(0)
            val costUsd = node.path("costUsd").asDouble(0.0)

            UsageSummary(
                todayTokens = tokensUsed,
                todayCost = costUsd,
                monthlyTokens = tokensUsed,
                monthlyCost = costUsd,
                dailyEntries = emptyList()
            )
        } catch (e: Exception) {
            thisLogger().warn("Failed to parse alternative usage JSON: ${e.message}")
            UsageSummary.EMPTY
        }
    }

    fun refreshUsageAsync(callback: (UsageSummary) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val usage = getUsage(forceRefresh = true)
            ApplicationManager.getApplication().invokeLater {
                callback(usage)
            }
        }
    }
}
