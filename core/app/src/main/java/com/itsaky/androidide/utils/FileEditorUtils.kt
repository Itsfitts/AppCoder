package com.itsaky.androidide.utils

import java.io.File
import java.io.FileWriter
import java.io.IOException

object FileEditorUtils {

    /**
     * Creates or updates file with the given content.
     * 
     * @param baseDir The base directory for projects
     * @param projectName The name of the project
     * @param fileName The name of the file
     * @param content The content to write
     * @return true if operation was successful, false otherwise
     */
    fun replaceFileContent(baseDir: File, projectName: String, fileName: String, content: String): Boolean {
        val projectDir = File(baseDir, projectName)
        if (!projectDir.exists()) {
            if (!projectDir.mkdirs()) {
                return false
            }
        }

        val file = File(projectDir, fileName)
        try {
            FileWriter(file).use { writer ->
                writer.write(content)
            }
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        }
    }
}