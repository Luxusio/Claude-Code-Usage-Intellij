package com.github.luxusio.claudecodeusageintellij

import com.github.luxusio.claudecodeusageintellij.model.DailyEntry
import com.github.luxusio.claudecodeusageintellij.model.UsageSummary
import com.github.luxusio.claudecodeusageintellij.model.toUsageSummary
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ClaudeUsagePluginTest : BasePlatformTestCase() {

    fun testUsageSummaryEmpty() {
        val summary = UsageSummary.EMPTY

        assertEquals(0L, summary.todayTokens)
        assertEquals(0.0, summary.todayCost, 0.001)
        assertEquals(0L, summary.monthlyTokens)
        assertEquals(0.0, summary.monthlyCost, 0.001)
        assertTrue(summary.dailyEntries.isEmpty())
    }

    fun testDailyEntriesToUsageSummary() {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

        val entries = listOf(
            DailyEntry(date = today, tokensUsed = 1000L, costUsd = 0.05),
            DailyEntry(date = yesterday, tokensUsed = 2000L, costUsd = 0.10)
        )

        val summary = entries.toUsageSummary()

        assertEquals(1000L, summary.todayTokens)
        assertEquals(0.05, summary.todayCost, 0.001)
        assertEquals(3000L, summary.monthlyTokens)
        assertEquals(0.15, summary.monthlyCost, 0.001)
        assertEquals(2, summary.dailyEntries.size)
    }

    fun testDailyEntriesToUsageSummaryNoToday() {
        val yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

        val entries = listOf(
            DailyEntry(date = yesterday, tokensUsed = 2000L, costUsd = 0.10)
        )

        val summary = entries.toUsageSummary()

        assertEquals(0L, summary.todayTokens)
        assertEquals(0.0, summary.todayCost, 0.001)
        assertEquals(2000L, summary.monthlyTokens)
        assertEquals(0.10, summary.monthlyCost, 0.001)
    }

    fun testDailyEntriesToUsageSummaryDifferentMonth() {
        val lastMonth = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

        val entries = listOf(
            DailyEntry(date = lastMonth, tokensUsed = 5000L, costUsd = 0.25)
        )

        val summary = entries.toUsageSummary()

        assertEquals(0L, summary.todayTokens)
        assertEquals(0.0, summary.todayCost, 0.001)
        // Last month's data should not be included in monthly totals
        assertEquals(0L, summary.monthlyTokens)
        assertEquals(0.0, summary.monthlyCost, 0.001)
    }

    fun testDailyEntry() {
        val entry = DailyEntry(
            date = "2025-01-15",
            tokensUsed = 12345L,
            costUsd = 0.62
        )

        assertEquals("2025-01-15", entry.date)
        assertEquals(12345L, entry.tokensUsed)
        assertEquals(0.62, entry.costUsd, 0.001)
    }

    fun testClaudeBundleMessages() {
        // Test that bundle messages are accessible
        val title = ClaudeBundle.message("usage.title")
        assertNotNull(title)
        assertTrue(title.isNotEmpty())

        val loading = ClaudeBundle.message("usage.loading")
        assertNotNull(loading)
        assertTrue(loading.isNotEmpty())
    }
}
