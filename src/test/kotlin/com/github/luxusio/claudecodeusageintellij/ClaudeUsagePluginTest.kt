package com.github.luxusio.claudecodeusageintellij

import com.github.luxusio.claudecodeusageintellij.model.DailyUsageEntry
import com.github.luxusio.claudecodeusageintellij.model.SessionMessage
import com.github.luxusio.claudecodeusageintellij.model.TokenUsage
import com.github.luxusio.claudecodeusageintellij.model.UsageSummary
import com.github.luxusio.claudecodeusageintellij.model.toLocalDate
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.time.LocalDate

class ClaudeUsagePluginTest : BasePlatformTestCase() {

    fun testUsageSummaryEmpty() {
        val summary = UsageSummary.EMPTY

        assertEquals("", summary.sessionId)
        assertEquals(0L, summary.inputTokens)
        assertEquals(0L, summary.outputTokens)
        assertEquals(0L, summary.cacheCreationTokens)
        assertEquals(0L, summary.cacheReadTokens)
        assertEquals(0L, summary.totalTokens)
        assertEquals(UsageSummary.DEFAULT_TOKEN_LIMIT, summary.tokenLimit)
        assertEquals(0.0, summary.usagePercentage, 0.001)
    }

    fun testUsageSummaryPercentageCalculation() {
        val summary = UsageSummary(
            sessionId = "test-session",
            inputTokens = 10000,
            outputTokens = 5000,
            cacheCreationTokens = 2000,
            cacheReadTokens = 3000,
            totalTokens = 20000,
            tokenLimit = 200000
        )

        assertEquals(10.0, summary.usagePercentage, 0.001)
    }

    fun testUsageSummaryPercentageCapped() {
        val summary = UsageSummary(
            sessionId = "test-session",
            inputTokens = 150000,
            outputTokens = 100000,
            cacheCreationTokens = 0,
            cacheReadTokens = 0,
            totalTokens = 250000,
            tokenLimit = 200000
        )

        // Should be capped at 100%
        assertEquals(100.0, summary.usagePercentage, 0.001)
    }

    fun testUsageSummaryZeroLimit() {
        val summary = UsageSummary(
            sessionId = "test-session",
            inputTokens = 1000,
            outputTokens = 500,
            cacheCreationTokens = 0,
            cacheReadTokens = 0,
            totalTokens = 1500,
            tokenLimit = 0
        )

        // With zero limit, percentage should be 0
        assertEquals(0.0, summary.usagePercentage, 0.001)
    }

    fun testTokenUsageTotalCalculation() {
        val usage = TokenUsage(
            inputTokens = 100,
            outputTokens = 50,
            cacheCreationInputTokens = 200,
            cacheReadInputTokens = 150
        )

        assertEquals(500, usage.totalTokens)
    }

    fun testDailyUsageEntry() {
        val entry = DailyUsageEntry(
            date = LocalDate.of(2025, 1, 15),
            inputTokens = 5000,
            outputTokens = 2500,
            cacheCreationTokens = 1000,
            cacheReadTokens = 500,
            totalTokens = 9000,
            sessionCount = 3
        )

        assertEquals(LocalDate.of(2025, 1, 15), entry.date)
        assertEquals(5000L, entry.inputTokens)
        assertEquals(2500L, entry.outputTokens)
        assertEquals(1000L, entry.cacheCreationTokens)
        assertEquals(500L, entry.cacheReadTokens)
        assertEquals(9000L, entry.totalTokens)
        assertEquals(3, entry.sessionCount)
    }

    fun testTimestampToLocalDate() {
        val timestamp = "2025-01-15T10:30:00.000Z"
        val date = timestamp.toLocalDate()

        assertNotNull(date)
        assertEquals(2025, date?.year)
        assertEquals(1, date?.monthValue)
        assertEquals(15, date?.dayOfMonth)
    }

    fun testInvalidTimestampToLocalDate() {
        val invalidTimestamp = "invalid-timestamp"
        val date = invalidTimestamp.toLocalDate()

        assertNull(date)
    }

    fun testClaudeBundleMessages() {
        // Test that bundle messages are accessible
        val title = ClaudeBundle.message("usage.title")
        assertNotNull(title)
        assertTrue(title.isNotEmpty())

        val loading = ClaudeBundle.message("usage.loading")
        assertNotNull(loading)
        assertTrue(loading.isNotEmpty())

        val sessionLabel = ClaudeBundle.message("usage.session")
        assertNotNull(sessionLabel)
        assertTrue(sessionLabel.isNotEmpty())
    }
}
