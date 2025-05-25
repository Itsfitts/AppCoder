package com.itsaky.androidide.dialogs // Assuming it's in the same package, adjust if it's in 'utils'

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException

object ProjectFileUtils {

    private const val TAG = "ProjectFileUtils"

    fun scanProjectFiles(projectRoot: File): List<String> {
        val result = mutableListOf<String>()
        if (!projectRoot.exists() || !projectRoot.isDirectory) {
            Log.w(TAG, "scanProjectFiles: Project root directory does not exist or is not a directory: ${projectRoot.absolutePath}")
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

    fun processFileChangesAndDeletions(
        projectDir: File,
        filesToWrite: Map<String, String>,
        filesToDelete: List<String>,
        logAppender: (String) -> Unit,
        onComplete: (writeSuccessCount: Int, writeErrorCount: Int, deleteSuccessCount: Int, deleteErrorCount: Int) -> Unit
    ) {
        var writeSuccess = 0
        var writeError = 0
        var deleteSuccess = 0
        var deleteError = 0

        logAppender("--- Starting File System Operations ---\n")

        // --- Deletion Phase ---
        if (filesToDelete.isNotEmpty()) {
            logAppender("Deletion Phase: Attempting to delete ${filesToDelete.size} file(s).\n")
            filesToDelete.forEach { relativePath ->
                if (relativePath.isBlank()) {
                    logAppender("⚠️ Attempted to delete a file with a blank path. Skipping.\n")
                    return@forEach
                }
                val file = File(projectDir, relativePath)
                if (file.exists()) {
                    try {
                        if (file.isFile) {
                            if (file.delete()) {
                                logAppender("✅ Deleted file: $relativePath\n")
                                deleteSuccess++
                            } else {
                                logAppender("⚠️ Failed to delete file (unknown reason, check permissions): $relativePath\n")
                                deleteError++
                            }
                        } else if (file.isDirectory) {
                            logAppender("⚠️ Path for deletion is a directory. Manual deletion required or update logic for recursive delete: $relativePath\n")
                            deleteError++
                        }
                    } catch (e: SecurityException) {
                        logAppender("⚠️ Security error deleting $relativePath: ${e.message}\n")
                        deleteError++
                    } catch (e: Exception) {
                        logAppender("⚠️ Unexpected error deleting $relativePath: ${e.message}\n")
                        deleteError++
                    }
                } else {
                    logAppender("ℹ️ File for deletion not found (already deleted or wrong path?): $relativePath\n")
                }
            }
            logAppender("Deletion Phase complete. Success: $deleteSuccess, Errors: $deleteError.\n")
        } else {
            logAppender("Deletion Phase: No files specified for deletion.\n")
        }

        // --- Writing Phase ---
        if (filesToWrite.isNotEmpty()) {
            logAppender("Writing Phase: Attempting to write/update ${filesToWrite.size} file(s).\n")
            filesToWrite.forEach { (relativePath, content) ->
                if (relativePath.isBlank()) {
                    logAppender("⚠️ Attempted to write a file with a blank path. Skipping.\n")
                    return@forEach
                }
                val file = File(projectDir, relativePath)
                try {
                    file.parentFile?.mkdirs()
                    FileWriter(file).use { it.write(content) }
                    logAppender("✅ Updated/Created file: $relativePath\n")
                    writeSuccess++
                } catch (e: IOException) { // Catch specific IOException from FileWriter
                    // CORRECTED HERE:
                    logAppender("❌ IO Failed to write $relativePath: ${e.message}\n")
                    writeError++
                } catch (e: SecurityException) {
                    // CORRECTED HERE:
                    logAppender("❌ Security exception writing $relativePath: ${e.message}\n")
                    writeError++
                }
                catch (e: Exception) { // Broader catch for other unexpected issues
                    // CORRECTED HERE:
                    logAppender("❌ Failed to write $relativePath (Unexpected Error): ${e.message}\n")
                    writeError++
                }
            }
            logAppender("Writing Phase complete. Success: $writeSuccess, Errors: $writeError.\n")
        } else {
            logAppender("Writing Phase: No files specified for writing/updating.\n")
        }

        logAppender("--- File System Operations Complete ---\n")
        onComplete(writeSuccess, writeError, deleteSuccess, deleteError)
    }
}