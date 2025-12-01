package com.github.luxusio.claudecodeusageintellij.parser

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Locates Claude session files in the file system.
 * This class is responsible for finding session JSONL files.
 */
class SessionFileLocator(
    private val projectsDir: Path = DEFAULT_PROJECTS_DIR
) {

    companion object {
        val DEFAULT_CLAUDE_DIR: Path = Path.of(System.getProperty("user.home"), ".claude")
        val DEFAULT_PROJECTS_DIR: Path = DEFAULT_CLAUDE_DIR.resolve("projects")

        /**
         * Get the project-specific claude directory name
         */
        fun getProjectDirName(projectPath: String): String {
            return projectPath.replace("/", "-").replace("\\", "-")
        }
    }

    /**
     * Check if the projects directory exists
     */
    fun projectsDirExists(): Boolean {
        return Files.exists(projectsDir)
    }

    /**
     * Get the project directory for a given project path
     */
    fun getProjectDir(projectPath: String): Path {
        val projectDirName = getProjectDirName(projectPath)
        return projectsDir.resolve(projectDirName)
    }

    /**
     * Find session files in a specific project directory
     */
    fun findSessionFilesInProject(projectPath: String): List<File> {
        val projectDir = getProjectDir(projectPath)

        if (!Files.exists(projectDir)) {
            return emptyList()
        }

        return projectDir.toFile().listFiles { file ->
            isSessionFile(file)
        }?.toList() ?: emptyList()
    }

    /**
     * Find the latest session file in a specific project
     */
    fun findLatestSessionFileInProject(projectPath: String): File? {
        return findSessionFilesInProject(projectPath).maxByOrNull { it.lastModified() }
    }

    /**
     * Find all session files across all projects
     */
    fun findAllSessionFiles(): List<File> {
        if (!projectsDirExists()) {
            return emptyList()
        }

        val allSessionFiles = mutableListOf<File>()

        projectsDir.toFile().listFiles { file -> file.isDirectory }?.forEach { projectDir ->
            projectDir.listFiles { file ->
                isSessionFile(file)
            }?.let { allSessionFiles.addAll(it) }
        }

        return allSessionFiles
    }

    /**
     * Find the latest session file across all projects
     */
    fun findLatestSessionFile(): File? {
        return findAllSessionFiles().maxByOrNull { it.lastModified() }
    }

    /**
     * Find all project directories
     */
    fun findAllProjectDirs(): List<File> {
        if (!projectsDirExists()) {
            return emptyList()
        }

        return projectsDir.toFile().listFiles { file -> file.isDirectory }?.toList() ?: emptyList()
    }

    /**
     * Check if a file is a valid session file
     */
    private fun isSessionFile(file: File): Boolean {
        return file.isFile && file.extension == "jsonl" && !file.name.startsWith("agent-")
    }
}
