package com.github.luxusio.claudecodeusageintellij.parser

import com.github.luxusio.claudecodeusageintellij.model.UsageSummary
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

class SessionFileParserTest {

    private lateinit var parser: SessionFileParser

    @Before
    fun setUp() {
        parser = SessionFileParser()
    }

    // ========== parseLine Tests ==========

    @Test
    fun `parseLine should parse valid assistant message with usage`() {
        val line = """{"type":"assistant","sessionId":"sess-123","timestamp":"2025-01-15T10:30:00.000Z","message":{"role":"assistant","model":"claude-3","usage":{"input_tokens":100,"output_tokens":50,"cache_creation_input_tokens":20,"cache_read_input_tokens":10}}}"""

        val result = parser.parseLine(line)

        assertNotNull(result)
        assertEquals("assistant", result?.type)
        assertEquals("sess-123", result?.sessionId)
        assertEquals("2025-01-15T10:30:00.000Z", result?.timestamp)
        assertNotNull(result?.message)
        assertEquals("assistant", result?.message?.role)
        assertEquals("claude-3", result?.message?.model)
        assertNotNull(result?.message?.usage)
        assertEquals(100L, result?.message?.usage?.inputTokens)
        assertEquals(50L, result?.message?.usage?.outputTokens)
        assertEquals(20L, result?.message?.usage?.cacheCreationInputTokens)
        assertEquals(10L, result?.message?.usage?.cacheReadInputTokens)
    }

    @Test
    fun `parseLine should parse user message without usage`() {
        val line = """{"type":"user","sessionId":"sess-123","timestamp":"2025-01-15T10:29:00.000Z","message":{"role":"user"}}"""

        val result = parser.parseLine(line)

        assertNotNull(result)
        assertEquals("user", result?.type)
        assertEquals("sess-123", result?.sessionId)
        assertNull(result?.message?.usage)
    }

    @Test
    fun `parseLine should return null for blank line`() {
        val result = parser.parseLine("")
        assertNull(result)

        val result2 = parser.parseLine("   ")
        assertNull(result2)
    }

    @Test
    fun `parseLine should return null for malformed JSON`() {
        val result = parser.parseLine("not valid json")
        assertNull(result)

        val result2 = parser.parseLine("{incomplete json")
        assertNull(result2)
    }

    @Test
    fun `parseLine should handle unknown fields gracefully`() {
        val line = """{"type":"assistant","sessionId":"sess-123","unknownField":"value","message":{"role":"assistant"}}"""

        val result = parser.parseLine(line)

        assertNotNull(result)
        assertEquals("assistant", result?.type)
        assertEquals("sess-123", result?.sessionId)
    }

    // ========== parseLines Tests ==========

    @Test
    fun `parseLines should aggregate usage from multiple assistant messages`() {
        val lines = listOf(
            """{"type":"user","sessionId":"sess-123","timestamp":"2025-01-15T10:00:00.000Z","message":{"role":"user"}}""",
            """{"type":"assistant","sessionId":"sess-123","timestamp":"2025-01-15T10:01:00.000Z","message":{"role":"assistant","usage":{"input_tokens":100,"output_tokens":50,"cache_creation_input_tokens":20,"cache_read_input_tokens":10}}}""",
            """{"type":"user","sessionId":"sess-123","timestamp":"2025-01-15T10:02:00.000Z","message":{"role":"user"}}""",
            """{"type":"assistant","sessionId":"sess-123","timestamp":"2025-01-15T10:03:00.000Z","message":{"role":"assistant","usage":{"input_tokens":200,"output_tokens":100,"cache_creation_input_tokens":30,"cache_read_input_tokens":20}}}"""
        )

        val result = parser.parseLines(lines)

        assertEquals("sess-123", result.sessionId)
        assertEquals(300L, result.inputTokens)  // 100 + 200
        assertEquals(150L, result.outputTokens)  // 50 + 100
        assertEquals(50L, result.cacheCreationTokens)  // 20 + 30
        assertEquals(30L, result.cacheReadTokens)  // 10 + 20
        assertEquals(530L, result.totalTokens)
    }

    @Test
    fun `parseLines should skip user messages for token counting`() {
        val lines = listOf(
            """{"type":"user","sessionId":"sess-123","timestamp":"2025-01-15T10:00:00.000Z","message":{"role":"user"}}""",
            """{"type":"assistant","sessionId":"sess-123","timestamp":"2025-01-15T10:01:00.000Z","message":{"role":"assistant","usage":{"input_tokens":100,"output_tokens":50,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}"""
        )

        val result = parser.parseLines(lines)

        assertEquals(100L, result.inputTokens)
        assertEquals(50L, result.outputTokens)
        assertEquals(150L, result.totalTokens)
    }

    @Test
    fun `parseLines should skip assistant messages without usage data`() {
        val lines = listOf(
            """{"type":"assistant","sessionId":"sess-123","timestamp":"2025-01-15T10:01:00.000Z","message":{"role":"assistant"}}""",
            """{"type":"assistant","sessionId":"sess-123","timestamp":"2025-01-15T10:02:00.000Z","message":{"role":"assistant","usage":{"input_tokens":100,"output_tokens":50,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}"""
        )

        val result = parser.parseLines(lines)

        assertEquals(100L, result.inputTokens)
        assertEquals(50L, result.outputTokens)
        assertEquals(150L, result.totalTokens)
    }

    @Test
    fun `parseLines should handle empty list`() {
        val result = parser.parseLines(emptyList())

        assertEquals("", result.sessionId)
        assertEquals(0L, result.inputTokens)
        assertEquals(0L, result.outputTokens)
        assertEquals(0L, result.totalTokens)
    }

    @Test
    fun `parseLines should skip blank lines`() {
        val lines = listOf(
            """{"type":"assistant","sessionId":"sess-123","message":{"role":"assistant","usage":{"input_tokens":100,"output_tokens":50,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}""",
            "",
            "   ",
            """{"type":"assistant","sessionId":"sess-123","message":{"role":"assistant","usage":{"input_tokens":100,"output_tokens":50,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}"""
        )

        val result = parser.parseLines(lines)

        assertEquals(200L, result.inputTokens)
        assertEquals(100L, result.outputTokens)
    }

    @Test
    fun `parseLines should skip malformed lines`() {
        val lines = listOf(
            """{"type":"assistant","sessionId":"sess-123","message":{"role":"assistant","usage":{"input_tokens":100,"output_tokens":50,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}""",
            "not valid json",
            """{"type":"assistant","sessionId":"sess-123","message":{"role":"assistant","usage":{"input_tokens":100,"output_tokens":50,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}"""
        )

        val result = parser.parseLines(lines)

        assertEquals(200L, result.inputTokens)
        assertEquals(100L, result.outputTokens)
    }

    @Test
    fun `parseLines should extract sessionId from first message with sessionId`() {
        val lines = listOf(
            """{"type":"user","sessionId":"","message":{"role":"user"}}""",
            """{"type":"user","sessionId":"sess-456","message":{"role":"user"}}""",
            """{"type":"assistant","sessionId":"sess-789","message":{"role":"assistant","usage":{"input_tokens":100,"output_tokens":50,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}"""
        )

        val result = parser.parseLines(lines)

        assertEquals("sess-456", result.sessionId)
    }

    @Test
    fun `parseLines should use custom token limit`() {
        val lines = listOf(
            """{"type":"assistant","sessionId":"sess-123","message":{"role":"assistant","usage":{"input_tokens":100,"output_tokens":50,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}"""
        )

        val customLimit = 500_000L
        val result = parser.parseLines(lines, customLimit)

        assertEquals(customLimit, result.tokenLimit)
    }

    @Test
    fun `parseLines should use default token limit when not specified`() {
        val lines = listOf(
            """{"type":"assistant","sessionId":"sess-123","message":{"role":"assistant","usage":{"input_tokens":100,"output_tokens":50,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}"""
        )

        val result = parser.parseLines(lines)

        assertEquals(UsageSummary.DEFAULT_TOKEN_LIMIT, result.tokenLimit)
    }

    // ========== getSessionDate Tests ==========

    @Test
    fun `getSessionDate should return date from first valid timestamp`() {
        val lines = listOf(
            """{"type":"user","sessionId":"sess-123","timestamp":"2025-01-15T10:30:00.000Z","message":{"role":"user"}}""",
            """{"type":"assistant","sessionId":"sess-123","timestamp":"2025-01-16T10:30:00.000Z","message":{"role":"assistant"}}"""
        )

        val result = parser.getSessionDate(lines)

        assertNotNull(result)
        assertEquals(2025, result?.year)
        assertEquals(1, result?.monthValue)
        assertEquals(15, result?.dayOfMonth)
    }

    @Test
    fun `getSessionDate should skip lines without timestamp`() {
        val lines = listOf(
            """{"type":"user","sessionId":"sess-123","timestamp":"","message":{"role":"user"}}""",
            """{"type":"assistant","sessionId":"sess-123","timestamp":"2025-02-20T12:00:00.000Z","message":{"role":"assistant"}}"""
        )

        val result = parser.getSessionDate(lines)

        assertNotNull(result)
        assertEquals(2025, result?.year)
        assertEquals(2, result?.monthValue)
        // Note: day may vary based on timezone, just verify it's valid
        assertTrue(result?.dayOfMonth in 19..21)
    }

    @Test
    fun `getSessionDate should return null for empty list`() {
        val result = parser.getSessionDate(emptyList())
        assertNull(result)
    }

    @Test
    fun `getSessionDate should return null when no valid timestamps`() {
        val lines = listOf(
            """{"type":"user","sessionId":"sess-123","timestamp":"","message":{"role":"user"}}""",
            """{"type":"assistant","sessionId":"sess-123","timestamp":"invalid-timestamp","message":{"role":"assistant"}}"""
        )

        val result = parser.getSessionDate(lines)

        assertNull(result)
    }

    @Test
    fun `getSessionDate should skip malformed lines`() {
        val lines = listOf(
            "not valid json",
            """{"type":"user","sessionId":"sess-123","timestamp":"2025-03-10T08:00:00.000Z","message":{"role":"user"}}"""
        )

        val result = parser.getSessionDate(lines)

        assertNotNull(result)
        assertEquals(2025, result?.year)
        assertEquals(3, result?.monthValue)
        assertEquals(10, result?.dayOfMonth)
    }

    // ========== parseSessionFile Tests ==========

    @Test
    fun `parseSessionFile should parse file content`() {
        val tempFile = File.createTempFile("test-session", ".jsonl")
        tempFile.deleteOnExit()

        tempFile.writeText(
            """
            {"type":"user","sessionId":"sess-file","timestamp":"2025-01-15T10:00:00.000Z","message":{"role":"user"}}
            {"type":"assistant","sessionId":"sess-file","timestamp":"2025-01-15T10:01:00.000Z","message":{"role":"assistant","usage":{"input_tokens":500,"output_tokens":250,"cache_creation_input_tokens":100,"cache_read_input_tokens":50}}}
            """.trimIndent()
        )

        val result = parser.parseSessionFile(tempFile)

        assertEquals("sess-file", result.sessionId)
        assertEquals(500L, result.inputTokens)
        assertEquals(250L, result.outputTokens)
        assertEquals(100L, result.cacheCreationTokens)
        assertEquals(50L, result.cacheReadTokens)
        assertEquals(900L, result.totalTokens)
    }

    @Test
    fun `parseSessionFile should use custom token limit`() {
        val tempFile = File.createTempFile("test-session", ".jsonl")
        tempFile.deleteOnExit()

        tempFile.writeText(
            """{"type":"assistant","sessionId":"sess-file","message":{"role":"assistant","usage":{"input_tokens":100,"output_tokens":50,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}"""
        )

        val customLimit = 1_000_000L
        val result = parser.parseSessionFile(tempFile, customLimit)

        assertEquals(customLimit, result.tokenLimit)
    }

    @Test
    fun `getSessionDate from file should work correctly`() {
        val tempFile = File.createTempFile("test-session", ".jsonl")
        tempFile.deleteOnExit()

        tempFile.writeText(
            """{"type":"user","sessionId":"sess-file","timestamp":"2025-05-25T14:30:00.000Z","message":{"role":"user"}}"""
        )

        val result = parser.getSessionDate(tempFile)

        assertNotNull(result)
        assertEquals(2025, result?.year)
        assertEquals(5, result?.monthValue)
        assertEquals(25, result?.dayOfMonth)
    }

    // ========== Edge Cases ==========

    @Test
    fun `parseLines should handle zero token values`() {
        val lines = listOf(
            """{"type":"assistant","sessionId":"sess-123","message":{"role":"assistant","usage":{"input_tokens":0,"output_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}"""
        )

        val result = parser.parseLines(lines)

        assertEquals(0L, result.inputTokens)
        assertEquals(0L, result.outputTokens)
        assertEquals(0L, result.cacheCreationTokens)
        assertEquals(0L, result.cacheReadTokens)
        assertEquals(0L, result.totalTokens)
    }

    @Test
    fun `parseLines should handle large token values`() {
        val lines = listOf(
            """{"type":"assistant","sessionId":"sess-123","message":{"role":"assistant","usage":{"input_tokens":999999999,"output_tokens":888888888,"cache_creation_input_tokens":777777777,"cache_read_input_tokens":666666666}}}"""
        )

        val result = parser.parseLines(lines)

        assertEquals(999999999L, result.inputTokens)
        assertEquals(888888888L, result.outputTokens)
        assertEquals(777777777L, result.cacheCreationTokens)
        assertEquals(666666666L, result.cacheReadTokens)
    }

    @Test
    fun `parseLines should handle message types other than user and assistant`() {
        val lines = listOf(
            """{"type":"system","sessionId":"sess-123","message":{"role":"system"}}""",
            """{"type":"tool","sessionId":"sess-123","message":{"role":"tool"}}""",
            """{"type":"assistant","sessionId":"sess-123","message":{"role":"assistant","usage":{"input_tokens":100,"output_tokens":50,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}"""
        )

        val result = parser.parseLines(lines)

        // Only assistant message should be counted
        assertEquals(100L, result.inputTokens)
        assertEquals(50L, result.outputTokens)
    }

    @Test
    fun `parseLine should handle missing optional fields`() {
        val line = """{"type":"assistant"}"""

        val result = parser.parseLine(line)

        assertNotNull(result)
        assertEquals("assistant", result?.type)
        assertEquals("", result?.sessionId)
        assertEquals("", result?.timestamp)
        assertNull(result?.message)
    }
}
