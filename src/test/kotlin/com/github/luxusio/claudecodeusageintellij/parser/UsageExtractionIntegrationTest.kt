package com.github.luxusio.claudecodeusageintellij.parser

import com.github.luxusio.claudecodeusageintellij.model.UsageSummary
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration tests for the full usage extraction flow:
 * File location -> File reading -> Data parsing -> UsageSummary
 */
class UsageExtractionIntegrationTest {

    private lateinit var tempDir: Path
    private lateinit var projectsDir: Path
    private lateinit var locator: SessionFileLocator
    private lateinit var parser: SessionFileParser

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("claude-test")
        projectsDir = tempDir.resolve("projects")
        Files.createDirectories(projectsDir)

        locator = SessionFileLocator(projectsDir)
        parser = SessionFileParser()
    }

    @After
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    // ========== Full Flow Tests ==========

    @Test
    fun `full flow - single project with single session`() {
        // Setup: Create project directory with session file
        val projectPath = "/Users/test/my-project"
        val projectDir = createProjectDir(projectPath)
        val sessionFile = createSessionFile(
            projectDir,
            "session-001.jsonl",
            listOf(
                userMessage("sess-001", "2025-01-15T10:00:00.000Z"),
                assistantMessage("sess-001", "2025-01-15T10:01:00.000Z", 1000, 500, 200, 100)
            )
        )

        // Execute: Find and parse
        val foundFile = locator.findLatestSessionFileInProject(projectPath)
        assertNotNull(foundFile)

        val summary = parser.parseSessionFile(foundFile!!)

        // Verify
        assertEquals("sess-001", summary.sessionId)
        assertEquals(1000L, summary.inputTokens)
        assertEquals(500L, summary.outputTokens)
        assertEquals(200L, summary.cacheCreationTokens)
        assertEquals(100L, summary.cacheReadTokens)
        assertEquals(1800L, summary.totalTokens)
    }

    @Test
    fun `full flow - single project with multiple sessions picks latest`() {
        // Setup
        val projectPath = "/Users/test/my-project"
        val projectDir = createProjectDir(projectPath)

        // Create older session
        val olderSession = createSessionFile(
            projectDir,
            "session-old.jsonl",
            listOf(
                assistantMessage("sess-old", "2025-01-10T10:00:00.000Z", 100, 50, 0, 0)
            )
        )
        // Make it older
        olderSession.setLastModified(System.currentTimeMillis() - 100000)

        // Create newer session
        val newerSession = createSessionFile(
            projectDir,
            "session-new.jsonl",
            listOf(
                assistantMessage("sess-new", "2025-01-15T10:00:00.000Z", 2000, 1000, 500, 250)
            )
        )

        // Execute
        val foundFile = locator.findLatestSessionFileInProject(projectPath)
        assertNotNull(foundFile)

        val summary = parser.parseSessionFile(foundFile!!)

        // Verify: Should get newer session
        assertEquals("sess-new", summary.sessionId)
        assertEquals(2000L, summary.inputTokens)
        assertEquals(1000L, summary.outputTokens)
    }

    @Test
    fun `full flow - multiple projects finds latest across all`() {
        // Setup: Create multiple projects
        val project1Dir = createProjectDir("/Users/test/project1")
        val project2Dir = createProjectDir("/Users/test/project2")

        // Project 1 - older session
        val session1 = createSessionFile(
            project1Dir,
            "session-p1.jsonl",
            listOf(assistantMessage("sess-p1", "2025-01-10T10:00:00.000Z", 100, 50, 0, 0))
        )
        session1.setLastModified(System.currentTimeMillis() - 100000)

        // Project 2 - newer session
        val session2 = createSessionFile(
            project2Dir,
            "session-p2.jsonl",
            listOf(assistantMessage("sess-p2", "2025-01-15T10:00:00.000Z", 3000, 1500, 0, 0))
        )

        // Execute
        val latestFile = locator.findLatestSessionFile()
        assertNotNull(latestFile)

        val summary = parser.parseSessionFile(latestFile!!)

        // Verify: Should get project2's session
        assertEquals("sess-p2", summary.sessionId)
        assertEquals(3000L, summary.inputTokens)
    }

    @Test
    fun `full flow - session with multiple assistant messages aggregates tokens`() {
        // Setup
        val projectPath = "/Users/test/conversation-project"
        val projectDir = createProjectDir(projectPath)

        createSessionFile(
            projectDir,
            "long-conversation.jsonl",
            listOf(
                userMessage("sess-conv", "2025-01-15T10:00:00.000Z"),
                assistantMessage("sess-conv", "2025-01-15T10:01:00.000Z", 1000, 500, 100, 50),
                userMessage("sess-conv", "2025-01-15T10:02:00.000Z"),
                assistantMessage("sess-conv", "2025-01-15T10:03:00.000Z", 1500, 750, 150, 75),
                userMessage("sess-conv", "2025-01-15T10:04:00.000Z"),
                assistantMessage("sess-conv", "2025-01-15T10:05:00.000Z", 2000, 1000, 200, 100)
            )
        )

        // Execute
        val foundFile = locator.findLatestSessionFileInProject(projectPath)
        val summary = parser.parseSessionFile(foundFile!!)

        // Verify: Tokens should be aggregated
        assertEquals("sess-conv", summary.sessionId)
        assertEquals(4500L, summary.inputTokens)    // 1000 + 1500 + 2000
        assertEquals(2250L, summary.outputTokens)   // 500 + 750 + 1000
        assertEquals(450L, summary.cacheCreationTokens)  // 100 + 150 + 200
        assertEquals(225L, summary.cacheReadTokens)      // 50 + 75 + 100
        assertEquals(7425L, summary.totalTokens)
    }

    @Test
    fun `full flow - ignores agent files`() {
        // Setup
        val projectPath = "/Users/test/agent-project"
        val projectDir = createProjectDir(projectPath)

        // Agent file (should be ignored)
        createSessionFile(
            projectDir,
            "agent-task-001.jsonl",
            listOf(assistantMessage("agent-sess", "2025-01-15T10:00:00.000Z", 9999, 9999, 0, 0))
        )

        // Regular session file
        createSessionFile(
            projectDir,
            "session-regular.jsonl",
            listOf(assistantMessage("regular-sess", "2025-01-15T10:00:00.000Z", 500, 250, 0, 0))
        )

        // Execute
        val foundFile = locator.findLatestSessionFileInProject(projectPath)
        val summary = parser.parseSessionFile(foundFile!!)

        // Verify: Should get regular session, not agent
        assertEquals("regular-sess", summary.sessionId)
        assertEquals(500L, summary.inputTokens)
    }

    @Test
    fun `full flow - handles empty project directory`() {
        // Setup: Create project directory without session files
        val projectPath = "/Users/test/empty-project"
        createProjectDir(projectPath)

        // Execute
        val foundFile = locator.findLatestSessionFileInProject(projectPath)

        // Verify
        assertNull(foundFile)
    }

    @Test
    fun `full flow - handles non-existent project`() {
        // Execute
        val foundFile = locator.findLatestSessionFileInProject("/Users/test/nonexistent")

        // Verify
        assertNull(foundFile)
    }

    @Test
    fun `full flow - handles malformed lines in session file`() {
        // Setup
        val projectPath = "/Users/test/malformed-project"
        val projectDir = createProjectDir(projectPath)

        val content = """
            {"type":"assistant","sessionId":"sess-mal","message":{"role":"assistant","usage":{"input_tokens":1000,"output_tokens":500,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}
            not valid json at all
            {"type":"assistant","sessionId":"sess-mal","message":{"role":"assistant","usage":{"input_tokens":1000,"output_tokens":500,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}
        """.trimIndent()

        val sessionFile = projectDir.resolve("session-mal.jsonl")
        sessionFile.writeText(content)

        // Execute
        val foundFile = locator.findLatestSessionFileInProject(projectPath)
        val summary = parser.parseSessionFile(foundFile!!)

        // Verify: Should parse valid lines, skip malformed
        assertEquals("sess-mal", summary.sessionId)
        assertEquals(2000L, summary.inputTokens)  // Only 2 valid assistant messages
    }

    @Test
    fun `full flow - extracts session date correctly`() {
        // Setup
        val projectPath = "/Users/test/dated-project"
        val projectDir = createProjectDir(projectPath)

        createSessionFile(
            projectDir,
            "session-dated.jsonl",
            listOf(
                userMessage("sess-dated", "2025-03-15T08:30:00.000Z"),
                assistantMessage("sess-dated", "2025-03-15T08:31:00.000Z", 500, 250, 0, 0)
            )
        )

        // Execute
        val foundFile = locator.findLatestSessionFileInProject(projectPath)
        val sessionDate = parser.getSessionDate(foundFile!!)

        // Verify
        assertNotNull(sessionDate)
        assertEquals(2025, sessionDate?.year)
        assertEquals(3, sessionDate?.monthValue)
        // Day might vary by timezone, just check it's valid
        assertTrue(sessionDate?.dayOfMonth in 14..16)
    }

    @Test
    fun `full flow - find all session files across projects`() {
        // Setup: Create multiple projects with multiple sessions
        val project1Dir = createProjectDir("/Users/test/proj-a")
        val project2Dir = createProjectDir("/Users/test/proj-b")

        createSessionFile(project1Dir, "session-1a.jsonl", listOf(assistantMessage("1a", "", 100, 50, 0, 0)))
        createSessionFile(project1Dir, "session-1b.jsonl", listOf(assistantMessage("1b", "", 100, 50, 0, 0)))
        createSessionFile(project2Dir, "session-2a.jsonl", listOf(assistantMessage("2a", "", 100, 50, 0, 0)))

        // Execute
        val allFiles = locator.findAllSessionFiles()

        // Verify
        assertEquals(3, allFiles.size)
    }

    @Test
    fun `full flow - custom token limit is applied`() {
        // Setup
        val projectPath = "/Users/test/limit-project"
        val projectDir = createProjectDir(projectPath)

        createSessionFile(
            projectDir,
            "session-limit.jsonl",
            listOf(assistantMessage("sess-limit", "", 5000, 2500, 0, 0))
        )

        // Execute
        val foundFile = locator.findLatestSessionFileInProject(projectPath)
        val customLimit = 500_000L
        val summary = parser.parseSessionFile(foundFile!!, customLimit)

        // Verify
        assertEquals(customLimit, summary.tokenLimit)
        assertEquals(1.5, summary.usagePercentage, 0.01)  // 7500 / 500000 * 100
    }

    @Test
    fun `full flow - handles session with only user messages`() {
        // Setup
        val projectPath = "/Users/test/user-only-project"
        val projectDir = createProjectDir(projectPath)

        createSessionFile(
            projectDir,
            "session-user-only.jsonl",
            listOf(
                userMessage("sess-user", "2025-01-15T10:00:00.000Z"),
                userMessage("sess-user", "2025-01-15T10:01:00.000Z"),
                userMessage("sess-user", "2025-01-15T10:02:00.000Z")
            )
        )

        // Execute
        val foundFile = locator.findLatestSessionFileInProject(projectPath)
        val summary = parser.parseSessionFile(foundFile!!)

        // Verify: No tokens should be counted
        assertEquals("sess-user", summary.sessionId)
        assertEquals(0L, summary.inputTokens)
        assertEquals(0L, summary.outputTokens)
        assertEquals(0L, summary.totalTokens)
    }

    @Test
    fun `full flow - project directory name conversion`() {
        // Test various path formats
        assertEquals("-Users-test-project", SessionFileLocator.getProjectDirName("/Users/test/project"))
        // In Kotlin string, \\ is a single backslash
        assertEquals("C:-Users-test-project", SessionFileLocator.getProjectDirName("C:\\Users\\test\\project"))
        assertEquals("-home-user-workspace-app", SessionFileLocator.getProjectDirName("/home/user/workspace/app"))
    }

    // ========== Helper Methods ==========

    private fun createProjectDir(projectPath: String): File {
        val dirName = SessionFileLocator.getProjectDirName(projectPath)
        val projectDir = projectsDir.resolve(dirName).toFile()
        projectDir.mkdirs()
        return projectDir
    }

    private fun createSessionFile(projectDir: File, fileName: String, messages: List<String>): File {
        val file = File(projectDir, fileName)
        file.writeText(messages.joinToString("\n"))
        return file
    }

    private fun userMessage(sessionId: String, timestamp: String): String {
        return """{"type":"user","sessionId":"$sessionId","timestamp":"$timestamp","message":{"role":"user"}}"""
    }

    private fun assistantMessage(
        sessionId: String,
        timestamp: String,
        inputTokens: Long,
        outputTokens: Long,
        cacheCreation: Long,
        cacheRead: Long
    ): String {
        return """{"type":"assistant","sessionId":"$sessionId","timestamp":"$timestamp","message":{"role":"assistant","model":"claude-3","usage":{"input_tokens":$inputTokens,"output_tokens":$outputTokens,"cache_creation_input_tokens":$cacheCreation,"cache_read_input_tokens":$cacheRead}}}"""
    }
}
