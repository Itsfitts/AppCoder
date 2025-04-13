package com.itsaky.androidide.dialogs

import java.io.File
import java.io.FileWriter

object ProjectFileUtils {

    fun scanProjectFiles(projectRoot: File): List<String> {
        val result = mutableListOf<String>()
        if (!projectRoot.exists() || !projectRoot.isDirectory) {
            return result
        }
        collectFilePaths(projectRoot, projectRoot, result)
        return result
    }

    private fun collectFilePaths(baseDir: File, currentDir: File, result: MutableList<String>) {
        val files = currentDir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                if (file.name in arrayOf("build", ".gradle", ".git", ".idea", "generated")) {
                    continue
                }
                collectFilePaths(baseDir, file, result)
            } else {
                if (isCodeFile(file.name)) {
                    val relativePath = file.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                    result.add(relativePath)
                }
            }
        }
    }

    private fun isCodeFile(filename: String): Boolean {
        val codeExtensions = arrayOf(".kt", ".java", ".xml", ".gradle", ".properties", ".json", ".md")
        return codeExtensions.any { filename.lowercase().endsWith(it) }
    }

    fun processFileChanges(
        projectDir: File,
        fileChanges: Map<String, String>,
        logAppender: (String) -> Unit,
        onComplete: (successCount: Int, errorCount: Int) -> Unit
    ) {
        logAppender("Applying changes to ${fileChanges.size} files...\n")
        var successCount = 0
        var errorCount = 0

        for ((filePath, content) in fileChanges) {
            try {
                val file = File(projectDir, filePath)
                file.parentFile?.mkdirs() // Ensure directories exist
                FileWriter(file).use { it.write(content) }
                logAppender("✅ Updated: $filePath\n")
                successCount++
            } catch (e: Exception) {
                logAppender("❌ Failed to write $filePath: ${e.message}\n")
                errorCount++
            }
        }

        if (errorCount > 0) {
            logAppender("⚠️ Some files failed to update.\n")
        }
        if (successCount > 0) {
             logAppender("✅ Code modification phase complete.\n")
        }
        onComplete(successCount, errorCount)
    }
}