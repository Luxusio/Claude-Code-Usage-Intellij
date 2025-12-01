package com.github.luxusio.claudecodeusageintellij.parser

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class SessionFileLocatorTest {

    private lateinit var tempDir: Path
    private lateinit var projectsDir: Path
    private lateinit var locator: SessionFileLocator

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("claude-locator-test")
        projectsDir = tempDir.resolve("projects")
        Files.createDirectories(projectsDir)
        locator = SessionFileLocator(projectsDir)
    }

    @After
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    // ========== getProjectDirName Tests ==========

    @Test
    fun `getProjectDirName converts unix path`() {
        val result = SessionFileLocator.getProjectDirName("/Users/test/my-project")
        assertEquals("-Users-test-my-project", result)
    }

    @Test
    fun `getProjectDirName converts windows path`() {
        // In Kotlin string, \\ is a single backslash
        val result = SessionFileLocator.getProjectDirName("C:\\Users\\test\\my-project")
        assertEquals("C:-Users-test-my-project", result)
    }

    @Test
    fun `getProjectDirName handles mixed separators`() {
        val result = SessionFileLocator.getProjectDirName("/Users/test\\mixed/path")
        assertEquals("-Users-test-mixed-path", result)
    }

    @Test
    fun `getProjectDirName handles path without separators`() {
        val result = SessionFileLocator.getProjectDirName("simple-name")
        assertEquals("simple-name", result)
    }

    // ========== projectsDirExists Tests ==========

    @Test
    fun `projectsDirExists returns true when directory exists`() {
        assertTrue(locator.projectsDirExists())
    }

    @Test
    fun `projectsDirExists returns false when directory does not exist`() {
        val nonExistentLocator = SessionFileLocator(tempDir.resolve("nonexistent"))
        assertFalse(nonExistentLocator.projectsDirExists())
    }

    // ========== getProjectDir Tests ==========

    @Test
    fun `getProjectDir returns correct path`() {
        val projectPath = "/Users/test/my-app"
        val result = locator.getProjectDir(projectPath)

        assertEquals(projectsDir.resolve("-Users-test-my-app"), result)
    }

    // ========== findSessionFilesInProject Tests ==========

    @Test
    fun `findSessionFilesInProject returns empty for non-existent project`() {
        val result = locator.findSessionFilesInProject("/nonexistent/project")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `findSessionFilesInProject returns empty for empty project`() {
        val projectDir = createProjectDir("/Users/test/empty")

        val result = locator.findSessionFilesInProject("/Users/test/empty")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `findSessionFilesInProject finds jsonl files`() {
        val projectDir = createProjectDir("/Users/test/with-sessions")
        createFile(projectDir, "session1.jsonl", "content")
        createFile(projectDir, "session2.jsonl", "content")

        val result = locator.findSessionFilesInProject("/Users/test/with-sessions")

        assertEquals(2, result.size)
    }

    @Test
    fun `findSessionFilesInProject excludes agent files`() {
        val projectDir = createProjectDir("/Users/test/with-agent")
        createFile(projectDir, "session.jsonl", "content")
        createFile(projectDir, "agent-task.jsonl", "content")
        createFile(projectDir, "agent-sub.jsonl", "content")

        val result = locator.findSessionFilesInProject("/Users/test/with-agent")

        assertEquals(1, result.size)
        assertEquals("session.jsonl", result[0].name)
    }

    @Test
    fun `findSessionFilesInProject excludes non-jsonl files`() {
        val projectDir = createProjectDir("/Users/test/mixed-files")
        createFile(projectDir, "session.jsonl", "content")
        createFile(projectDir, "readme.txt", "content")
        createFile(projectDir, "config.json", "content")

        val result = locator.findSessionFilesInProject("/Users/test/mixed-files")

        assertEquals(1, result.size)
        assertEquals("session.jsonl", result[0].name)
    }

    @Test
    fun `findSessionFilesInProject excludes directories`() {
        val projectDir = createProjectDir("/Users/test/with-subdir")
        createFile(projectDir, "session.jsonl", "content")
        File(projectDir, "subdir.jsonl").mkdir()

        val result = locator.findSessionFilesInProject("/Users/test/with-subdir")

        assertEquals(1, result.size)
        assertTrue(result[0].isFile)
    }

    // ========== findLatestSessionFileInProject Tests ==========

    @Test
    fun `findLatestSessionFileInProject returns null for empty project`() {
        createProjectDir("/Users/test/empty")

        val result = locator.findLatestSessionFileInProject("/Users/test/empty")

        assertNull(result)
    }

    @Test
    fun `findLatestSessionFileInProject returns latest by modification time`() {
        val projectDir = createProjectDir("/Users/test/multi-session")

        val older = createFile(projectDir, "older.jsonl", "old content")
        older.setLastModified(System.currentTimeMillis() - 100000)

        val newer = createFile(projectDir, "newer.jsonl", "new content")
        newer.setLastModified(System.currentTimeMillis())

        val result = locator.findLatestSessionFileInProject("/Users/test/multi-session")

        assertNotNull(result)
        assertEquals("newer.jsonl", result?.name)
    }

    // ========== findAllSessionFiles Tests ==========

    @Test
    fun `findAllSessionFiles returns empty when no projects`() {
        // Projects dir exists but is empty
        val result = locator.findAllSessionFiles()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `findAllSessionFiles returns empty when projects dir does not exist`() {
        val nonExistentLocator = SessionFileLocator(tempDir.resolve("nonexistent"))

        val result = nonExistentLocator.findAllSessionFiles()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findAllSessionFiles finds files across multiple projects`() {
        val project1 = createProjectDir("/proj/one")
        val project2 = createProjectDir("/proj/two")

        createFile(project1, "session1a.jsonl", "")
        createFile(project1, "session1b.jsonl", "")
        createFile(project2, "session2a.jsonl", "")

        val result = locator.findAllSessionFiles()

        assertEquals(3, result.size)
    }

    @Test
    fun `findAllSessionFiles excludes agent files from all projects`() {
        val project1 = createProjectDir("/proj/one")
        val project2 = createProjectDir("/proj/two")

        createFile(project1, "session.jsonl", "")
        createFile(project1, "agent-task.jsonl", "")
        createFile(project2, "agent-sub.jsonl", "")
        createFile(project2, "regular.jsonl", "")

        val result = locator.findAllSessionFiles()

        assertEquals(2, result.size)
        assertTrue(result.all { !it.name.startsWith("agent-") })
    }

    // ========== findLatestSessionFile Tests ==========

    @Test
    fun `findLatestSessionFile returns null when no sessions exist`() {
        val result = locator.findLatestSessionFile()
        assertNull(result)
    }

    @Test
    fun `findLatestSessionFile returns latest across all projects`() {
        val project1 = createProjectDir("/proj/one")
        val project2 = createProjectDir("/proj/two")

        val older = createFile(project1, "older.jsonl", "")
        older.setLastModified(System.currentTimeMillis() - 100000)

        val newest = createFile(project2, "newest.jsonl", "")
        newest.setLastModified(System.currentTimeMillis())

        val result = locator.findLatestSessionFile()

        assertNotNull(result)
        assertEquals("newest.jsonl", result?.name)
    }

    // ========== findAllProjectDirs Tests ==========

    @Test
    fun `findAllProjectDirs returns empty when no projects`() {
        val result = locator.findAllProjectDirs()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `findAllProjectDirs returns empty when projects dir does not exist`() {
        val nonExistentLocator = SessionFileLocator(tempDir.resolve("nonexistent"))

        val result = nonExistentLocator.findAllProjectDirs()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findAllProjectDirs finds all project directories`() {
        createProjectDir("/proj/one")
        createProjectDir("/proj/two")
        createProjectDir("/proj/three")

        val result = locator.findAllProjectDirs()

        assertEquals(3, result.size)
        assertTrue(result.all { it.isDirectory })
    }

    @Test
    fun `findAllProjectDirs excludes files`() {
        createProjectDir("/proj/real")
        createFile(projectsDir.toFile(), "not-a-dir", "content")

        val result = locator.findAllProjectDirs()

        assertEquals(1, result.size)
        assertEquals("-proj-real", result[0].name)
    }

    // ========== Helper Methods ==========

    private fun createProjectDir(projectPath: String): File {
        val dirName = SessionFileLocator.getProjectDirName(projectPath)
        val projectDir = projectsDir.resolve(dirName).toFile()
        projectDir.mkdirs()
        return projectDir
    }

    private fun createFile(parent: File, name: String, content: String): File {
        val file = File(parent, name)
        file.writeText(content)
        return file
    }
}
