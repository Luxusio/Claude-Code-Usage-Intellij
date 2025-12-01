package com.github.luxusio.claudecodeusageintellij.parser

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.luxusio.claudecodeusageintellij.model.SessionMessage
import com.github.luxusio.claudecodeusageintellij.model.UsageSummary
import com.github.luxusio.claudecodeusageintellij.model.toLocalDate
import java.io.File
import java.time.LocalDate

/**
 * Parser for Claude session JSONL files.
 * Extracts usage data from session files.
 */
class SessionFileParser(
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule()
) {

    /**
     * Parse a session JSONL file and extract usage data
     */
    fun parseSessionFile(file: File, tokenLimit: Long = UsageSummary.DEFAULT_TOKEN_LIMIT): UsageSummary {
        return parseLines(file.readLines(), tokenLimit)
    }

    /**
     * Parse lines from a session JSONL content and extract usage data
     */
    fun parseLines(lines: List<String>, tokenLimit: Long = UsageSummary.DEFAULT_TOKEN_LIMIT): UsageSummary {
        var inputTokens = 0L
        var outputTokens = 0L
        var cacheCreationTokens = 0L
        var cacheReadTokens = 0L
        var sessionId = ""

        lines.forEach { line ->
            if (line.isBlank()) return@forEach

            try {
                val message = objectMapper.readValue(line, SessionMessage::class.java)

                if (sessionId.isEmpty() && message.sessionId.isNotEmpty()) {
                    sessionId = message.sessionId
                }

                // Only count assistant messages with usage data
                if (message.type == "assistant" && message.message?.usage != null) {
                    val usage = message.message.usage
                    inputTokens += usage.inputTokens
                    outputTokens += usage.outputTokens
                    cacheCreationTokens += usage.cacheCreationInputTokens
                    cacheReadTokens += usage.cacheReadInputTokens
                }
            } catch (e: Exception) {
                // Skip malformed lines
            }
        }

        val totalTokens = inputTokens + outputTokens + cacheCreationTokens + cacheReadTokens

        return UsageSummary(
            sessionId = sessionId,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheCreationTokens = cacheCreationTokens,
            cacheReadTokens = cacheReadTokens,
            totalTokens = totalTokens,
            tokenLimit = tokenLimit
        )
    }

    /**
     * Parse a single line from JSONL content
     */
    fun parseLine(line: String): SessionMessage? {
        if (line.isBlank()) return null
        return try {
            objectMapper.readValue(line, SessionMessage::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the date of a session from the first timestamp in the lines
     */
    fun getSessionDate(lines: List<String>): LocalDate? {
        lines.forEach { line ->
            if (line.isBlank()) return@forEach
            try {
                val message = objectMapper.readValue(line, SessionMessage::class.java)
                if (message.timestamp.isNotEmpty()) {
                    return message.timestamp.toLocalDate()
                }
            } catch (e: Exception) {
                // Skip malformed lines
            }
        }
        return null
    }

    /**
     * Get the date of a session from the first timestamp in the file
     */
    fun getSessionDate(file: File): LocalDate? {
        return getSessionDate(file.readLines())
    }
}