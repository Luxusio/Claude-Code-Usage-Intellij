package com.github.luxusio.claudecodeusageintellij.parser

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for ClaudeCommandParser.
 * Tests the parsing logic without actually executing the claude command.
 */
class ClaudeCommandParserTest {

    private val parser = ClaudeCommandParser()

    // ========== ANSI Code Removal Tests ==========

    @Test
    fun `removeAnsiCodes - removes simple color codes`() {
        val input = "\u001b[32mGreen text\u001b[0m"
        val result = parser.removeAnsiCodes(input)
        assertEquals("Green text", result)
    }

    @Test
    fun `removeAnsiCodes - removes multiple color codes`() {
        val input = "\u001b[31mRed\u001b[0m \u001b[32mGreen\u001b[0m \u001b[34mBlue\u001b[0m"
        val result = parser.removeAnsiCodes(input)
        assertEquals("Red Green Blue", result)
    }

    @Test
    fun `removeAnsiCodes - removes complex codes with multiple parameters`() {
        val input = "\u001b[1;31;40mBold red on black\u001b[0m"
        val result = parser.removeAnsiCodes(input)
        assertEquals("Bold red on black", result)
    }

    @Test
    fun `removeAnsiCodes - handles text without ANSI codes`() {
        val input = "Plain text without any codes"
        val result = parser.removeAnsiCodes(input)
        assertEquals("Plain text without any codes", result)
    }

    @Test
    fun `removeAnsiCodes - handles empty string`() {
        val result = parser.removeAnsiCodes("")
        assertEquals("", result)
    }

    // ========== Output Parsing Tests ==========

    @Test
    fun `parseOutput - extracts all three percentage values`() {
        val output = """
            Current session
            ████████████░░░░░░░░░░░░░░░░░░ 42%

            Current week (all models)
            ██████░░░░░░░░░░░░░░░░░░░░░░░░ 25%

            Current week (Sonnet only)
            ████░░░░░░░░░░░░░░░░░░░░░░░░░░ 15%
        """.trimIndent()

        val result = parser.parseOutput(output)

        assertEquals(42, result.sessionPercentage)
        assertEquals(25, result.weekAllModelsPercentage)
        assertEquals(15, result.weekSonnetOnlyPercentage)
    }

    @Test
    fun `parseOutput - handles ANSI codes in output`() {
        val output = """
            \u001b[32mCurrent session\u001b[0m
            \u001b[33m████████████░░░░░░░░░░░░░░░░░░\u001b[0m \u001b[1m42%\u001b[0m

            \u001b[32mCurrent week (all models)\u001b[0m
            \u001b[33m██████░░░░░░░░░░░░░░░░░░░░░░░░\u001b[0m \u001b[1m25%\u001b[0m

            \u001b[32mCurrent week (Sonnet only)\u001b[0m
            \u001b[33m████░░░░░░░░░░░░░░░░░░░░░░░░░░\u001b[0m \u001b[1m15%\u001b[0m
        """.trimIndent()

        val result = parser.parseOutput(output)

        assertEquals(42, result.sessionPercentage)
        assertEquals(25, result.weekAllModelsPercentage)
        assertEquals(15, result.weekSonnetOnlyPercentage)
    }

    @Test
    fun `parseOutput - returns 0 for missing session percentage`() {
        val output = """
            Current week (all models)
            ██████░░░░░░░░░░░░░░░░░░░░░░░░ 25%

            Current week (Sonnet only)
            ████░░░░░░░░░░░░░░░░░░░░░░░░░░ 15%
        """.trimIndent()

        val result = parser.parseOutput(output)

        assertEquals(0, result.sessionPercentage)
        assertEquals(25, result.weekAllModelsPercentage)
        assertEquals(15, result.weekSonnetOnlyPercentage)
    }

    @Test
    fun `parseOutput - returns 0 for missing week all models percentage`() {
        val output = """
            Current session
            ████████████░░░░░░░░░░░░░░░░░░ 42%

            Current week (Sonnet only)
            ████░░░░░░░░░░░░░░░░░░░░░░░░░░ 15%
        """.trimIndent()

        val result = parser.parseOutput(output)

        assertEquals(42, result.sessionPercentage)
        assertEquals(0, result.weekAllModelsPercentage)
        assertEquals(15, result.weekSonnetOnlyPercentage)
    }

    @Test
    fun `parseOutput - returns 0 for missing sonnet only percentage`() {
        val output = """
            Current session
            ████████████░░░░░░░░░░░░░░░░░░ 42%

            Current week (all models)
            ██████░░░░░░░░░░░░░░░░░░░░░░░░ 25%
        """.trimIndent()

        val result = parser.parseOutput(output)

        assertEquals(42, result.sessionPercentage)
        assertEquals(25, result.weekAllModelsPercentage)
        assertEquals(0, result.weekSonnetOnlyPercentage)
    }

    @Test
    fun `parseOutput - handles empty output`() {
        val result = parser.parseOutput("")

        assertEquals(0, result.sessionPercentage)
        assertEquals(0, result.weekAllModelsPercentage)
        assertEquals(0, result.weekSonnetOnlyPercentage)
    }

    @Test
    fun `parseOutput - handles 0 percent values`() {
        val output = """
            Current session
            ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ 0%

            Current week (all models)
            ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ 0%

            Current week (Sonnet only)
            ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ 0%
        """.trimIndent()

        val result = parser.parseOutput(output)

        assertEquals(0, result.sessionPercentage)
        assertEquals(0, result.weekAllModelsPercentage)
        assertEquals(0, result.weekSonnetOnlyPercentage)
    }

    @Test
    fun `parseOutput - handles 100 percent values`() {
        val output = """
            Current session
            ██████████████████████████████ 100%

            Current week (all models)
            ██████████████████████████████ 100%

            Current week (Sonnet only)
            ██████████████████████████████ 100%
        """.trimIndent()

        val result = parser.parseOutput(output)

        assertEquals(100, result.sessionPercentage)
        assertEquals(100, result.weekAllModelsPercentage)
        assertEquals(100, result.weekSonnetOnlyPercentage)
    }

    @Test
    fun `parseOutput - handles percentage on same line as label`() {
        val output = """
            Current session 42%
            Current week (all models) 25%
            Current week (Sonnet only) 15%
        """.trimIndent()

        val result = parser.parseOutput(output)

        assertEquals(42, result.sessionPercentage)
        assertEquals(25, result.weekAllModelsPercentage)
        assertEquals(15, result.weekSonnetOnlyPercentage)
    }

    @Test
    fun `parseOutput - handles various whitespace patterns`() {
        val output = """
            Current session


            ████████████░░░░░░░░░░░░░░░░░░   42%


            Current week (all models)

            ██████░░░░░░░░░░░░░░░░░░░░░░░░	25%

            Current week (Sonnet only)
            ████░░░░░░░░░░░░░░░░░░░░░░░░░░
            15%
        """.trimIndent()

        val result = parser.parseOutput(output)

        assertEquals(42, result.sessionPercentage)
        assertEquals(25, result.weekAllModelsPercentage)
        assertEquals(15, result.weekSonnetOnlyPercentage)
    }

    @Test
    fun `parseOutput - real world output simulation`() {
        // Simulate actual output with real ANSI codes
        val output = "\u001b[1mClaude Usage\u001b[0m\n\n" +
                "\u001b[32mCurrent session\u001b[0m\n" +
                "\u001b[33m████████████░░░░░░░░░░░░░░░░░░\u001b[0m 42%\n\n" +
                "\u001b[32mCurrent week (all models)\u001b[0m\n" +
                "\u001b[33m██████████████████████████████\u001b[0m 85%\n\n" +
                "\u001b[32mCurrent week (Sonnet only)\u001b[0m\n" +
                "\u001b[33m██████████████████████████░░░░\u001b[0m 67%\n"

        val result = parser.parseOutput(output)

        assertEquals(42, result.sessionPercentage)
        assertEquals(85, result.weekAllModelsPercentage)
        assertEquals(67, result.weekSonnetOnlyPercentage)
    }

    // ========== Data Model Tests ==========

    @Test
    fun `ClaudeCommandUsageData EMPTY has all zero values`() {
        val empty = com.github.luxusio.claudecodeusageintellij.model.ClaudeCommandUsageData.EMPTY

        assertEquals(0, empty.sessionPercentage)
        assertEquals(0, empty.weekAllModelsPercentage)
        assertEquals(0, empty.weekSonnetOnlyPercentage)
    }
}
