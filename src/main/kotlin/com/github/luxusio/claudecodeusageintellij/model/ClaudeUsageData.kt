package com.github.luxusio.claudecodeusageintellij.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Represents a single message entry from Claude's JSONL session files
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SessionMessage(
    @param:JsonProperty("type")
    val type: String = "",
    @param:JsonProperty("sessionId")
    val sessionId: String = "",
    @param:JsonProperty("timestamp")
    val timestamp: String = "",
    @param:JsonProperty("message")
    val message: MessageContent? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MessageContent(
    @param:JsonProperty("role")
    val role: String = "",
    @param:JsonProperty("model")
    val model: String? = null,
    @param:JsonProperty("usage")
    val usage: TokenUsage? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TokenUsage(
    @param:JsonProperty("input_tokens")
    val inputTokens: Long = 0,
    @param:JsonProperty("output_tokens")
    val outputTokens: Long = 0,
    @param:JsonProperty("cache_creation_input_tokens")
    val cacheCreationInputTokens: Long = 0,
    @param:JsonProperty("cache_read_input_tokens")
    val cacheReadInputTokens: Long = 0
) {
    val totalTokens: Long
        get() = inputTokens + outputTokens + cacheCreationInputTokens + cacheReadInputTokens
}

/**
 * Aggregated usage data for display
 */
data class UsageSummary(
    val sessionId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationTokens: Long,
    val cacheReadTokens: Long,
    val totalTokens: Long,
    val tokenLimit: Long,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    val usagePercentage: Double
        get() = if (tokenLimit > 0) (totalTokens.toDouble() / tokenLimit * 100).coerceIn(0.0, 100.0) else 0.0

    companion object {
        const val DEFAULT_TOKEN_LIMIT = 200_000L // Default context window size

        val EMPTY = UsageSummary(
            sessionId = "",
            inputTokens = 0,
            outputTokens = 0,
            cacheCreationTokens = 0,
            cacheReadTokens = 0,
            totalTokens = 0,
            tokenLimit = DEFAULT_TOKEN_LIMIT
        )
    }
}

/**
 * Daily usage entry for historical tracking
 */
data class DailyUsageEntry(
    val date: LocalDate,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationTokens: Long,
    val cacheReadTokens: Long,
    val totalTokens: Long,
    val sessionCount: Int
)

/**
 * Extension to parse timestamp string to LocalDate
 */
fun String.toLocalDate(): LocalDate? {
    return try {
        Instant.parse(this).atZone(ZoneId.systemDefault()).toLocalDate()
    } catch (e: Exception) {
        null
    }
}

/**
 * Usage data from `claude /usage` command output.
 * Contains percentage values for session and weekly usage.
 */
data class ClaudeCommandUsageData(
    val sessionPercentage: Int,
    val weekAllModelsPercentage: Int,
    val weekSonnetOnlyPercentage: Int,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    companion object {
        val EMPTY = ClaudeCommandUsageData(
            sessionPercentage = 0,
            weekAllModelsPercentage = 0,
            weekSonnetOnlyPercentage = 0
        )
    }
}
