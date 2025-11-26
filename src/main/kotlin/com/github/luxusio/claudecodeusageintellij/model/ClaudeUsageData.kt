package com.github.luxusio.claudecodeusageintellij.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@JsonIgnoreProperties(ignoreUnknown = true)
data class ClaudeUsageResponse(
    @param:JsonProperty("dailyUsage")
    val dailyUsage: DailyUsage? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DailyUsage(
    @param:JsonProperty("daily")
    val daily: List<DailyEntry> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DailyEntry(
    @param:JsonProperty("date")
    val date: String = "",
    @param:JsonProperty("tokensUsed")
    val tokensUsed: Long = 0,
    @param:JsonProperty("costUsd")
    val costUsd: Double = 0.0
)

data class UsageSummary(
    val todayTokens: Long,
    val todayCost: Double,
    val monthlyTokens: Long,
    val monthlyCost: Double,
    val dailyEntries: List<DailyEntry>,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    companion object {
        val EMPTY = UsageSummary(
            todayTokens = 0,
            todayCost = 0.0,
            monthlyTokens = 0,
            monthlyCost = 0.0,
            dailyEntries = emptyList()
        )
    }
}

fun List<DailyEntry>.toUsageSummary(): UsageSummary {
    val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    val currentMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))

    val todayEntry = this.find { it.date == today }
    val monthlyEntries = this.filter { it.date.startsWith(currentMonth) }

    return UsageSummary(
        todayTokens = todayEntry?.tokensUsed ?: 0,
        todayCost = todayEntry?.costUsd ?: 0.0,
        monthlyTokens = monthlyEntries.sumOf { it.tokensUsed },
        monthlyCost = monthlyEntries.sumOf { it.costUsd },
        dailyEntries = this
    )
}
