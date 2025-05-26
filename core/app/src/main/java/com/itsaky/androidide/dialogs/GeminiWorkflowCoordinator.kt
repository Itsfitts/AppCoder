package com.itsaky.androidide.dialogs // Adjust package if needed

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.Locale
// Ensure ProjectFileUtils is correctly imported
import com.itsaky.androidide.dialogs.ProjectFileUtils // If in this package
// import com.itsaky.androidide.utils.ProjectFileUtils // If in a utils package

class GeminiWorkflowCoordinator(
    private val geminiHelper: GeminiHelper,
    private val directLogAppender: (String) -> Unit,
    private val directErrorHandler: (String, Exception?) -> Unit,
    private val fileEditorInterface: FileEditorInterface
) {
    companion object {
        private const val TAG = "GeminiWorkflow"
    }

    private val conversation = GeminiConversation()
    private val selectedFilesForModification = mutableListOf<String>()

    private fun logViaInterface(message: String) = fileEditorInterface.appendToLog(message)

    fun startModificationFlow(appName: String, appDescription: String, projectDir: File) {
        logViaInterface("Gemini Workflow: Starting for project '$appName'\n")
        conversation.clear()
        selectedFilesForModification.clear()
        fileEditorInterface.currentProjectDir = projectDir
        fileEditorInterface.displayAiConclusion(null) // Clear previous conclusion at the start

        fileEditorInterface.setState(FileEditorActivity.WorkflowState.SELECTING_FILES)
        identifyFilesToModify(appName, appDescription, projectDir)
    }

    private fun identifyFilesToModify(appName: String, appDescription: String, projectDir: File) {
        logViaInterface("Gemini Workflow Step: Identifying files to modify/create...\n")
        val files = ProjectFileUtils.scanProjectFiles(projectDir)

        fileEditorInterface.runOnUiThread {
            if (files.isEmpty() && !fileEditorInterface.isModifyingExistingProjectInternal) {
                directErrorHandler("No code files found in the newly created project template. Cannot proceed.", null)
                fileEditorInterface.setState(FileEditorActivity.WorkflowState.ERROR)
                return@runOnUiThread
            } else if (files.isEmpty() && fileEditorInterface.isModifyingExistingProjectInternal) {
                logViaInterface("No existing code files found by scanner in '$appName'. AI will be prompted to create necessary files.\n")
            } else {
                logViaInterface("Found ${files.size} potentially relevant files in the project.\n")
            }
            sendGeminiFileSelectionPrompt(appName, appDescription, files)
        }
    }

    private fun sendGeminiFileSelectionPrompt(appName: String, appDescription: String, files: List<String>) {
        val fileListText = if (files.isNotEmpty()) {
            files.joinToString("\n") { "- $it" }
        } else {
            "No existing editable files were found in this project. You might need to create all necessary files from scratch."
        }
        val isExisting = fileEditorInterface.isModifyingExistingProjectInternal
        val promptContext = if (isExisting) "modifying an existing Android app" else "working with a basic Android app template I just created"
        val actionVerb = if (isExisting) "MODIFY or CREATE" else "CREATED or significantly MODIFIED"

        val prompt = """
            I am $promptContext called "$appName".
            The main goal for this app is: "$appDescription"
            Here is a list of potentially relevant files from the project (or a note if empty):
            $fileListText
            Based ONLY on the app's main goal and the file list (if any), which of these files would MOST LIKELY need to be $actionVerb?
            If the file list is empty or the existing files are not relevant to the goal, list the NEW files that would be essential to create to achieve the goal.
            Focus on the core essentials.
            Respond ONLY with a JSON array of the relative file paths. Example: ["app/src/main/java/com/example/myapp/MainActivity.kt"]
        """.trimIndent()

        conversation.addUserMessage(prompt)
        logViaInterface("Asking AI to select relevant files...\n")

        geminiHelper.sendGeminiRequest(
            contents = conversation.getContentsForApi(),
            callback = { response ->
                fileEditorInterface.runOnUiThread {
                    try {
                        val responseText = geminiHelper.extractTextFromGeminiResponse(response)
                        logViaInterface("AI file selection response received.\n")
                        val jsonArrayText = geminiHelper.extractJsonArrayFromText(responseText)
                        val jsonArray = JSONArray(jsonArrayText)
                        selectedFilesForModification.clear()
                        for (i in 0 until jsonArray.length()) {
                            selectedFilesForModification.add(jsonArray.getString(i))
                        }
                        conversation.addModelMessage(responseText)

                        if (selectedFilesForModification.isEmpty()) {
                            logViaInterface("⚠️ AI did not select/suggest any files. Project remains as is.\n")
                            fileEditorInterface.setState(FileEditorActivity.WorkflowState.READY_FOR_ACTION)
                        } else {
                            logViaInterface("AI selected/suggested ${selectedFilesForModification.size} files:\n${selectedFilesForModification.joinToString("\n") { "  - $it" }}\n")
                            loadSelectedFilesAndAskForCode(appName, appDescription)
                        }
                    } catch (e: IllegalArgumentException) {
                        directErrorHandler("AI did not provide a valid list of files: ${e.message}", e)
                    } catch (e: JSONException) {
                        directErrorHandler("Failed to parse AI's file selection as JSON: ${e.message}", e)
                    } catch (e: Exception) {
                        directErrorHandler("Unexpected error during file selection: ${e.message}", e)
                    }
                }
            }
        )
    }

    private fun loadSelectedFilesAndAskForCode(appName: String, appDescription: String) {
        val projectDir = fileEditorInterface.currentProjectDir ?: run {
            directErrorHandler("Project directory is null in loadSelectedFilesAndAskForCode.", null)
            return
        }

        fileEditorInterface.setState(FileEditorActivity.WorkflowState.GENERATING_CODE)
        logViaInterface("Gemini Workflow Step: Loading selected files for code generation...\n")
        val fileContents = mutableMapOf<String, String>()
        val missingOrUnreadableFiles = mutableListOf<String>()

        for (filePath in selectedFilesForModification) {
            val file = File(projectDir, filePath)
            if (!file.exists() || !file.isFile) {
                logViaInterface("Note: File '$filePath' not found. Will ask AI to create it.\n")
                missingOrUnreadableFiles.add(filePath)
                fileContents[filePath] = "// File: $filePath (This file does not exist yet. Please provide its complete content.)"
            } else {
                try {
                    fileContents[filePath] = FileReader(file).use { it.readText() }
                } catch (e: IOException) {
                    logViaInterface("⚠️ Error reading file $filePath: ${e.message}. Will ask AI to provide content.\n")
                    missingOrUnreadableFiles.add(filePath)
                    fileContents[filePath] = "// File: $filePath (Error reading existing content. Will ask AI to regenerate.)"
                }
            }
        }
        if (fileContents.isEmpty() && selectedFilesForModification.isNotEmpty()) {
            logViaInterface("All selected files are new or were unreadable. AI will generate content from scratch.\n")
        } else if (fileContents.isEmpty() && selectedFilesForModification.isEmpty()) {
            directErrorHandler("No files selected or loaded for code generation.", null)
            return
        }
        logViaInterface("Loaded/identified ${fileContents.size} files for AI processing.\n")
        sendGeminiCodeGenerationPrompt(appName, appDescription, fileContents, missingOrUnreadableFiles)
    }

    private fun sendGeminiCodeGenerationPrompt(
        appName: String, appDescription: String,
        fileContentsMap: Map<String, String>, missingFilesList: List<String>
    ) {
        val filesContentText = buildString {
            fileContentsMap.forEach { (path, content) -> append("FILE: $path\n```\n$content\n```\n\n") }
        }
        val creationNote = if (missingFilesList.isNotEmpty()) {
            "The following files were identified as needing creation or were unreadable; please provide their full content:\n${missingFilesList.joinToString("\n") { "- $it" }}\n\n"
        } else ""
        val isExisting = fileEditorInterface.isModifyingExistingProjectInternal
        val packageNameInstruction: String = if (isExisting) {
            val inferredPackageName = fileContentsMap.entries
                .firstOrNull { (path, _) -> path.endsWith(".kt") && !missingFilesList.contains(path) }
                ?.value?.let { Regex("""^\s*package\s+([a-zA-Z0-9_.]+)""").find(it)?.groupValues?.get(1) } ?: "com.example.unknown"
            "If creating new Kotlin/Java files, use an appropriate existing package (e.g., derived from '$inferredPackageName') or a new sub-package."
        } else {
            val sanitizedAppName = appName.filter { it.isLetterOrDigit() }.lowercase(Locale.getDefault()).ifEmpty { "myapp" }
            "Pay attention to package names if creating new Kotlin/Java files (e.g., `com.example.$sanitizedAppName`)."
        }
        val prompt = """
            You are an expert Android App Developer. Your task is to modify or create files for an Android app named "$appName".
            The primary goal of this app is: "$appDescription"
            $creationNote Important: For any file you provide content for, ensure it is the *complete and valid* content for that file.
            Current file contents (or placeholders for new/unreadable files):
            $filesContentText
            Instructions:
            1. Review the app description and the provided file contents/placeholders.
            2. For each file listed (or new files you deem necessary), provide its FULL, UPDATED, and VALID content.
            3. If new dependencies are implied, add them to `app/build.gradle.kts` (or `app/build.gradle`).
            4. Ensure Kotlin code is idiomatic and XML layouts are well-formed.
            5. $packageNameInstruction
            6. Only output content for files that need changes or creation.
            7. If your changes make any existing files (especially XML layouts) obsolete, list their full project-relative paths for DELETION.
            8. **After all file and deletion instructions, provide a concise summary of the changes you made as a bullet list, formatted for easy reading. This summary will be shown to the user.**

            Format your response STRICTLY as follows:
            Each modified/created file block must start with `FILE: path/to/file.ext`, followed by a fenced code block.
            ```
            FILE: path/to/file.ext
            `[optional language hint]`
            // Complete file content
            `
            ```
            (Repeat for each file to modify/create)

            If there are files to delete, after all FILE blocks, add `DELETE_FILES:` followed by a JSON array of strings.
            Example: DELETE_FILES: ["app/src/main/res/layout/old.xml"]
            If no files to delete, OMIT DELETE_FILES or use `DELETE_FILES: []`.

            Finally, after all FILE and DELETE_FILES blocks, add a `CONCLUSION:` block followed by your summary in a fenced code block (markdown with bullets is good).
            Example of CONCLUSION block:
            ```
            CONCLUSION:
            `
            - Refactored user authentication to use a new service.
            - Added a settings screen with theme selection.
            - Updated dependencies for Material Design 3.
            - Deleted obsolete `OldAuthManager.kt`.
            `
            ```
        """.trimIndent()

        conversation.addUserMessage(prompt)
        logViaInterface("Sending file contents to AI for code generation...\n")
        geminiHelper.sendGeminiRequest(
            contents = conversation.getContentsForApi(),
            callback = { response ->
                fileEditorInterface.runOnUiThread {
                    try {
                        val responseText = geminiHelper.extractTextFromGeminiResponse(response)
                        conversation.addModelMessage(responseText)
                        logViaInterface("AI responded with code modifications.\n")
                        applyCodeChanges(responseText)
                    } catch (e: Exception) {
                        directErrorHandler("Failed during AI code generation response: ${e.message}", e)
                    }
                }
            }
        )
    }

    private fun applyCodeChanges(responseText: String) {
        val projectDir = fileEditorInterface.currentProjectDir ?: run {
            directErrorHandler("Project directory is null in applyCodeChanges.", null)
            return
        }
        logViaInterface("Gemini Workflow Step: Applying code changes, deletions, and displaying conclusion...\n")
        val modifications = geminiHelper.parseFileChanges(responseText, directLogAppender)

        // Display conclusion first using the interface method
        fileEditorInterface.displayAiConclusion(modifications.conclusion)

        if (modifications.filesToWrite.isEmpty() && modifications.filesToDelete.isEmpty()) {
            if (modifications.conclusion == null) {
                logViaInterface("⚠️ AI did not provide any recognizable file changes, deletions, or conclusion.\n")
            } else {
                logViaInterface("AI provided a conclusion, but no file changes or deletions were processed.\n")
            }
            fileEditorInterface.setState(FileEditorActivity.WorkflowState.READY_FOR_ACTION)
            return
        }

        ProjectFileUtils.processFileChangesAndDeletions(
            projectDir,
            modifications.filesToWrite,
            modifications.filesToDelete,
            directLogAppender
        ) { writeSuccessCount, writeErrorCount, deleteSuccessCount, deleteErrorCount ->
            fileEditorInterface.runOnUiThread {
                if (writeErrorCount > 0 || deleteErrorCount > 0) {
                    logViaInterface("⚠️ Some file operations failed. Writes (Success: $writeSuccessCount, Error: $writeErrorCount), Deletes (Success: $deleteSuccessCount, Error: $deleteErrorCount).\n")
                }
                var changesApplied = false
                if (writeSuccessCount > 0) {
                    logViaInterface("✅ Successfully applied $writeSuccessCount file content changes.\n")
                    changesApplied = true
                }
                if (deleteSuccessCount > 0) {
                    logViaInterface("✅ Successfully deleted $deleteSuccessCount files.\n")
                    changesApplied = true
                }
                if (!changesApplied && writeErrorCount == 0 && deleteErrorCount == 0 && modifications.conclusion == null) {
                    logViaInterface("No specific file changes or deletions were processed or provided.\n")
                } else if (!changesApplied && writeErrorCount == 0 && deleteErrorCount == 0 && modifications.conclusion != null) {
                    // This case means only a conclusion was provided. The log for it is already handled by displayAiConclusion.
                }
                fileEditorInterface.setState(FileEditorActivity.WorkflowState.READY_FOR_ACTION)
            }
        }
    }
}