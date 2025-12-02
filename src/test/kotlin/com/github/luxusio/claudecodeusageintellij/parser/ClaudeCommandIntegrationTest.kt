package com.github.luxusio.claudecodeusageintellij.parser

import org.junit.Assert.*
import org.junit.Test

/**
 * Integration test that actually executes `claude /usage` command
 * and verifies the parsing works correctly.
 * Uses pty4j for PTY support (bundled with IntelliJ Platform).
 */
class ClaudeCommandIntegrationTest {

    @Test
    fun `fetchUsage - executes command and returns parsed data`() {
        val parser = ClaudeCommandParser("/opt/homebrew/bin/claude")

        val result = parser.fetchUsage()

        println("=".repeat(40))
        println(" CLAUDE USAGE TEST RESULT")
        println("=".repeat(40))
        println(" Session:          ${result.sessionPercentage}%")
        println(" Week (All):       ${result.weekAllModelsPercentage}%")
        println(" Week (Sonnet):    ${result.weekSonnetOnlyPercentage}%")
        println("=".repeat(40))

        // Verify percentages are within valid range (0-100)
        assertTrue("Session percentage should be >= 0", result.sessionPercentage >= 0)
        assertTrue("Session percentage should be <= 100", result.sessionPercentage <= 100)
        assertTrue("Week all models percentage should be >= 0", result.weekAllModelsPercentage >= 0)
        assertTrue("Week all models percentage should be <= 100", result.weekAllModelsPercentage <= 100)
        assertTrue("Week Sonnet percentage should be >= 0", result.weekSonnetOnlyPercentage >= 0)
        assertTrue("Week Sonnet percentage should be <= 100", result.weekSonnetOnlyPercentage <= 100)
    }

    @Test
    fun `executeCommand - returns usage data`() {
        val parser = ClaudeCommandParser("/opt/homebrew/bin/claude")

        val output = parser.executeCommand()

        println("Raw command output length: ${output.length}")
        println("Raw command output (first 1000 chars):")
        println(output.take(1000))
        println("---")
        println("Cleaned output:")
        println(parser.removeAnsiCodes(output).take(500))

        assertTrue("Command should return non-empty output", output.isNotBlank())
        assertTrue("Output should contain usage data", output.contains("used") || output.contains("session"))
    }
}
