package com.github.luxusio.claudecodeusageintellij.parser

import com.github.luxusio.claudecodeusageintellij.model.ClaudeCommandUsageData
import com.intellij.openapi.diagnostic.thisLogger
import com.pty4j.PtyProcessBuilder
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Parser for `claude /usage` command output.
 * Executes the command and extracts usage percentages from the output.
 * Uses pty4j for PTY support (bundled with IntelliJ Platform).
 */
class ClaudeCommandParser(
    private val claudeCommandPath: String = DEFAULT_CLAUDE_COMMAND
) {
    companion object {
        const val DEFAULT_CLAUDE_COMMAND = "claude"
        private const val COMMAND_TIMEOUT_MS = 15_000L

        // Regex patterns matching Python implementation
        private val SESSION_PATTERN = Regex("""Current session.*?(\d+)%""", RegexOption.DOT_MATCHES_ALL)
        private val WEEK_ALL_PATTERN = Regex("""Current week \(all models\).*?(\d+)%""", RegexOption.DOT_MATCHES_ALL)
        private val WEEK_SONNET_PATTERN = Regex("""Current week \(Sonnet only\).*?(\d+)%""", RegexOption.DOT_MATCHES_ALL)

        // ANSI escape code pattern for cleaning output
        private val ANSI_ESCAPE_PATTERN = Regex("""\u001b\[[0-9;]*m""")
    }

    /**
     * Execute the `claude /usage` command and parse the output.
     * @return ClaudeCommandUsageData with extracted percentages, or EMPTY on failure
     */
    fun fetchUsage(): ClaudeCommandUsageData {
        return try {
            val rawOutput = executeCommand()
            if (rawOutput.isBlank()) {
                thisLogger().warn("Claude command returned empty output")
                return ClaudeCommandUsageData.EMPTY
            }
            parseOutput(rawOutput)
        } catch (e: Exception) {
            thisLogger().warn("Failed to fetch Claude usage: ${e.message}", e)
            ClaudeCommandUsageData.EMPTY
        }
    }

    /**
     * Execute the claude /usage command and capture output.
     * Uses pty4j to create a PTY (pseudo-terminal) since claude requires interactive terminal.
     */
    internal fun executeCommand(): String {
        val cmd = arrayOf(claudeCommandPath, "/usage")
        val env = System.getenv().toMutableMap().apply {
            put("TERM", "xterm-256color")
        }

        val process = PtyProcessBuilder()
            .setCommand(cmd)
            .setEnvironment(env)
            .setDirectory(System.getProperty("user.home"))
            .setConsole(false)
            .start()

        val output = StringBuilder()
        val inputStream = process.inputStream

        val startTime = System.currentTimeMillis()
        var foundUsageData = false

        // Read output until we find "% used" or timeout
        while (System.currentTimeMillis() - startTime < COMMAND_TIMEOUT_MS) {
            val available = inputStream.available()
            if (available > 0) {
                val buffer = ByteArray(available)
                val read = inputStream.read(buffer)
                if (read > 0) {
                    output.append(String(buffer, 0, read))

                    // Check if we found usage data
                    if (output.contains("% used")) {
                        foundUsageData = true
                        // Wait a bit more for complete data
                        Thread.sleep(2000)

                        // Read remaining available data
                        val remaining = inputStream.available()
                        if (remaining > 0) {
                            val remainingBuffer = ByteArray(remaining)
                            val remainingRead = inputStream.read(remainingBuffer)
                            if (remainingRead > 0) {
                                output.append(String(remainingBuffer, 0, remainingRead))
                            }
                        }
                        break
                    }
                }
            } else {
                Thread.sleep(100)
            }
        }

        // Send ESC to close the dialog
        try {
            process.outputStream.write(27) // ESC key
            process.outputStream.flush()
        } catch (e: Exception) {
            // Ignore write errors
        }

        // Clean up
        process.destroyForcibly()

        // Return whatever output we collected (may be empty or partial)
        return output.toString()
    }

    /**
     * Parse the raw command output and extract usage percentages.
     * @param rawOutput Raw output from the claude command (may contain ANSI codes)
     * @return ClaudeCommandUsageData with extracted percentages
     */
    fun parseOutput(rawOutput: String): ClaudeCommandUsageData {
        // Remove ANSI escape codes
        val cleanText = removeAnsiCodes(rawOutput)

        // Extract percentages using regex patterns
        val sessionPercentage = extractPercentage(cleanText, SESSION_PATTERN)
        val weekAllPercentage = extractPercentage(cleanText, WEEK_ALL_PATTERN)
        val weekSonnetPercentage = extractPercentage(cleanText, WEEK_SONNET_PATTERN)

        return ClaudeCommandUsageData(
            sessionPercentage = sessionPercentage,
            weekAllModelsPercentage = weekAllPercentage,
            weekSonnetOnlyPercentage = weekSonnetPercentage
        )
    }

    /**
     * Remove ANSI escape codes from text.
     */
    internal fun removeAnsiCodes(text: String): String {
        return ANSI_ESCAPE_PATTERN.replace(text, "")
    }

    /**
     * Extract percentage value from text using the given pattern.
     * @return The percentage value (0-100), or 0 if not found
     */
    private fun extractPercentage(text: String, pattern: Regex): Int {
        val match = pattern.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
