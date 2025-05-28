package com.itsaky.androidide.dialogs // Adjust package if needed

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.Locale
import com.itsaky.androidide.dialogs.ProjectFileUtils

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
    private var lastAppDescriptionForFallback: String = ""
    private var lastAppNameForFallback: String = ""
    private var lastFileContextForFallback: String = "" // Changed from lastFileContentsForFallback for clarity

    private fun logViaInterface(message: String) = fileEditorInterface.appendToLog(message)

    fun startModificationFlow(appName: String, appDescription: String, projectDir: File) {
        logViaInterface("Gemini Workflow: Starting for project '$appName'\n")
        conversation.clear()
        selectedFilesForModification.clear()
        fileEditorInterface.currentProjectDir = projectDir
        fileEditorInterface.displayAiConclusion(null)

        lastAppNameForFallback = appName
        lastAppDescriptionForFallback = appDescription

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
        val actionVerb = if (isExisting) "MODIFY or CREATE" else "CREATE or significantly MODIFY"

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
        logViaInterface("Asking AI to select relevant files (expecting JSON array)...\n")

        geminiHelper.sendGeminiRequest(
            contents = conversation.getContentsForApi(),
            callback = { response ->
                fileEditorInterface.runOnUiThread {
                    try {
                        val responseText = geminiHelper.extractTextFromGeminiResponse(response)
                        Log.d(TAG, "Raw AI file selection response: $responseText")
                        logViaInterface("AI file selection response received.\n")
                        val jsonArrayText = geminiHelper.extractJsonArrayFromText(responseText)
                        val jsonArray = JSONArray(jsonArrayText)
                        selectedFilesForModification.clear()
                        for (i in 0 until jsonArray.length()) {
                            jsonArray.getString(i).takeIf { it.isNotBlank() }?.let {
                                selectedFilesForModification.add(it)
                            }
                        }
                        conversation.addModelMessage(responseText) // Add AI's raw JSON array string

                        if (selectedFilesForModification.isEmpty()) {
                            logViaInterface("⚠️ AI did not select/suggest any files. Will proceed and ask AI to generate necessary files based on description.\n")
                        } else {
                            logViaInterface("AI selected/suggested ${selectedFilesForModification.size} files:\n${selectedFilesForModification.joinToString("\n") { "  - $it" }}\n")
                        }
                        loadSelectedFilesAndAskForCode(appName, appDescription)
                    } catch (e: IllegalArgumentException) {
                        directErrorHandler("AI did not provide a valid list of files for selection: ${e.message}. Response: ${geminiHelper.extractTextFromGeminiResponse(response)}", e)
                    } catch (e: JSONException) {
                        directErrorHandler("Failed to parse AI's file selection as JSON: ${e.message}. Response: ${geminiHelper.extractTextFromGeminiResponse(response)}", e)
                    } catch (e: Exception) {
                        directErrorHandler("Unexpected error during file selection: ${e.message}", e)
                    }
                }
            },
            responseMimeTypeOverride = "application/json"
        )
    }

    private fun loadSelectedFilesAndAskForCode(appName: String, appDescription: String) {
        val projectDir = fileEditorInterface.currentProjectDir ?: run {
            directErrorHandler("Project directory is null in loadSelectedFilesAndAskForCode.", null)
            return
        }

        fileEditorInterface.setState(FileEditorActivity.WorkflowState.GENERATING_CODE)
        logViaInterface("Gemini Workflow Step: Loading selected files for code generation...\n")
        val fileContentsMap = mutableMapOf<String, String>()
        val missingOrUnreadableFiles = mutableListOf<String>()

        for (filePath in selectedFilesForModification) {
            val file = File(projectDir, filePath)
            if (!file.exists() || !file.isFile) {
                logViaInterface("Note: File '$filePath' not found. Will ask AI to create it.\n")
                missingOrUnreadableFiles.add(filePath)
                fileContentsMap[filePath] = "// File: $filePath (This file is new or was not found. Please provide its complete content based on the app description.)"
            } else {
                try {
                    fileContentsMap[filePath] = FileReader(file).use { it.readText() }
                } catch (e: IOException) {
                    logViaInterface("⚠️ Error reading file $filePath: ${e.message}. Will ask AI to provide content.\n")
                    missingOrUnreadableFiles.add(filePath)
                    fileContentsMap[filePath] = "// File: $filePath (Error reading existing content. Please regenerate based on the app description and its intended role.)"
                }
            }
        }

        if (fileContentsMap.isEmpty() && selectedFilesForModification.isNotEmpty()) {
            logViaInterface("All files selected by AI are new or were unreadable. AI will generate their content.\n")
        } else if (fileContentsMap.isEmpty() && selectedFilesForModification.isEmpty()){
            logViaInterface("No files were selected by AI (or project is empty). AI will be prompted to generate necessary files from scratch based on description.\n")
        } else {
            logViaInterface("Loaded/identified ${fileContentsMap.size} files for AI processing.\n")
        }
        sendGeminiCodeGenerationPrompt(appName, appDescription, fileContentsMap, missingOrUnreadableFiles)
    }

    private fun sendGeminiCodeGenerationPrompt(
        appName: String, appDescription: String,
        fileContentsMap: Map<String, String>, missingFilesList: List<String>
    ) {
        val filesContentText = buildString {
            if (fileContentsMap.isNotEmpty()) {
                fileContentsMap.forEach { (path, content) -> append("FILE: $path\n```\n$content\n```\n\n") }
            } else {
                append("No existing file content is provided. You will need to generate all necessary files from scratch based on the app description.\n")
            }
        }
        lastFileContextForFallback = filesContentText

        val creationNote = if (missingFilesList.isNotEmpty()) {
            "The following files were explicitly identified as needing creation or were unreadable; please provide their full content:\n${missingFilesList.joinToString("\n") { "- $it" }}\n\n"
        } else if (fileContentsMap.isEmpty()) {
            "Since no specific files were pre-selected or their content provided, determine the essential files and their complete content to realize the app's goal.\n"
        } else ""

        val isExisting = fileEditorInterface.isModifyingExistingProjectInternal
        val currentPackageName = ProjectFileUtils.findCommonPackageName(fileEditorInterface.currentProjectDir, appName, fileContentsMap.values.toList())

        val packageNameInstruction = "If creating new Kotlin/Java files, use an appropriate package name (e.g., '$currentPackageName' or a sub-package like '$currentPackageName.feature'). Ensure `AndroidManifest.xml` uses '$currentPackageName'."

        val prompt = """
            You are an expert Android App Developer. Your task is to modify or create files for an Android app named "$appName".
            The primary goal of this app is: "$appDescription"

            $creationNote
            Current file context (content of files selected for modification, or placeholders/notes for new files):
            $filesContentText

            Instructions:
            1. Review the app description and the provided file context.
            2. Determine the necessary file modifications:
                - For existing files, provide their FULL, UPDATED, and VALID content.
                - For new files essential to the app's goal, provide their FULL and VALID content including the correct relative path (e.g., "app/src/main/java/$currentPackageName/MyActivity.kt").
            3. If new dependencies are implied (e.g., new libraries), add them to `app/build.gradle.kts` (or `app/build.gradle`).
            4. Ensure Kotlin/Java code is idiomatic and XML layouts are well-formed.
            5. $packageNameInstruction
            6. If your changes make any existing files obsolete, identify them for deletion.
            7. **Provide a concise summary of the changes you made. This summary will be shown to the user. It is important to include this.**

            Your response MUST be a single JSON object matching the schema provided by the API.
            The JSON object should have three optional top-level keys:
            - "filesToWrite": An array of objects, where each object has "filePath" (string) and "fileContent" (string).
            - "filesToDelete": An array of strings, where each string is a relative file path to delete.
            - "conclusion": A string containing your summary of changes. THIS IS IMPORTANT.

            Example of a file to write entry within "filesToWrite":
            { "filePath": "app/src/main/java/$currentPackageName/MainActivity.kt", "fileContent": "package $currentPackageName;\n\n// ... rest of the Kotlin code ..." }

            Ensure all file content is complete and does not use placeholders like "// ... rest of the code ...".
            Ensure the "conclusion" field is populated with a meaningful summary. If no other changes, a summary like "No code changes were made." is acceptable.
        """.trimIndent()

        conversation.addUserMessage(prompt)
        logViaInterface("Sending file context to AI for structured code generation...\n")

        geminiHelper.sendGeminiRequest(
            contents = conversation.getContentsForApi(),
            callback = { response ->
                fileEditorInterface.runOnUiThread {
                    try {
                        val responseJsonText = geminiHelper.extractTextFromGeminiResponse(response)
                        if (responseJsonText.isBlank()) {
                            directErrorHandler("AI returned an empty response for structured code generation.", null)
                            // Potentially trigger fallback here too if desired, or straight to error
                            fileEditorInterface.setState(FileEditorActivity.WorkflowState.ERROR)
                            return@runOnUiThread
                        }
                        Log.i(TAG, "Raw structured JSON response from AI (Main Generation): \n$responseJsonText")
                        conversation.addModelMessage(responseJsonText)
                        logViaInterface("AI responded with structured data.\n")

                        val modifications = geminiHelper.parseAndConvertStructuredResponse(responseJsonText)

                        if (modifications.filesToWrite.isEmpty()) {
                            logViaInterface("⚠️ AI's structured response did not include any files to write. Attempting fallback for files...\n")
                            sendGeminiFallbackFileRequest(modifications) // Pass full initial modifications
                        } else {
                            applyCodeChanges(modifications)
                        }

                    } catch (e: Exception) {
                        directErrorHandler("Failed during structured AI code generation response processing: ${e.message}", e)
                        fileEditorInterface.setState(FileEditorActivity.WorkflowState.ERROR)
                    }
                }
            },
            responseSchemaJson = geminiHelper.getFileModificationsSchema(),
            responseMimeTypeOverride = "application/json"
        )
    }

    private fun sendGeminiFallbackFileRequest(originalModifications: FileModifications) {
        logViaInterface("Attempting fallback: Asking AI specifically for file content...\n")

        val fallbackPrompt = """
        The previous attempt to generate code modifications for app '$lastAppNameForFallback' (Goal: "$lastAppDescriptionForFallback") using a comprehensive structured JSON output did not yield any files to write.

        Original file context provided to AI:
        $lastFileContextForFallback

        Please re-evaluate and provide ONLY the necessary file content.
        Your response MUST be a single JSON object with one key: "filesToWrite".
        "filesToWrite" should be an array of objects, where each object has "filePath" (string) and "fileContent" (string).
        Provide complete and valid content for each file. Do not use placeholders.
        If, after re-evaluation, you still believe no files need to be written or modified, return an empty "filesToWrite" array or null for it.
        You DO NOT need to provide "filesToDelete" or "conclusion" in this fallback response.
        """.trimIndent()

        val fallbackConversation = GeminiConversation().apply {
            // Provide some context from the main conversation if desired, or keep it minimal
            // For instance, the initial user request could be useful:
            // conversation.getContentsForApi().firstOrNull()?.let { addUserMessage(it.getJSONArray("parts").getJSONObject(0).getString("text"))}
            addUserMessage(fallbackPrompt)
        }

        geminiHelper.sendGeminiRequest(
            contents = fallbackConversation.getContentsForApi(),
            callback = { response ->
                fileEditorInterface.runOnUiThread {
                    try {
                        val fallbackResponseJsonText = geminiHelper.extractTextFromGeminiResponse(response)
                        if (fallbackResponseJsonText.isBlank()) {
                            directErrorHandler("AI returned an empty response for fallback file request.", null)
                            applyCodeChanges(originalModifications) // Proceed with original (empty filesToWrite)
                            return@runOnUiThread
                        }
                        Log.i(TAG, "Raw fallback file response from AI: $fallbackResponseJsonText")
                        // Don't add to main conversation, this was a side-quest

                        val fallbackFilesMap = geminiHelper.parseMinimalFilesResponse(fallbackResponseJsonText)

                        val finalModifications = if (fallbackFilesMap != null && fallbackFilesMap.isNotEmpty()) {
                            logViaInterface("✅ Fallback for files successful. AI provided files to write.\n")
                            originalModifications.copy(filesToWrite = fallbackFilesMap)
                        } else {
                            logViaInterface("⚠️ Fallback for files also yielded no files to write.\n")
                            originalModifications // Stick with original
                        }
                        applyCodeChanges(finalModifications)
                    } catch (e: Exception) {
                        directErrorHandler("Failed during AI fallback file request processing: ${e.message}", e)
                        applyCodeChanges(originalModifications) // Proceed with original on error
                        fileEditorInterface.setState(FileEditorActivity.WorkflowState.ERROR)
                    }
                }
            },
            responseSchemaJson = geminiHelper.getMinimalFilesSchema(),
            responseMimeTypeOverride = "application/json"
        )
    }

    private fun requestSummaryFromAI(
        currentModifications: FileModifications,
        callback: (finalConclusion: String?) -> Unit
    ) {
        val generatedFiles = currentModifications.filesToWrite
        val deletedFiles = currentModifications.filesToDelete
        val originalConclusion = currentModifications.conclusion

        // If there's already a valid conclusion, or no changes to summarize, return early.
        if (originalConclusion != null && originalConclusion.isNotBlank()) {
            callback(originalConclusion)
            return
        }
        if (generatedFiles.isEmpty() && deletedFiles.isEmpty()) {
            logViaInterface("No code changes were made, so providing a default summary.\n")
            callback(originalConclusion ?: "No specific code changes or deletions were performed by the AI.")
            return
        }

        logViaInterface("Attempting to generate a summary for the applied changes via dedicated AI call...\n")

        val changesDescription = buildString {
            if (generatedFiles.isNotEmpty()) {
                append("The following files were written or updated:\n")
                generatedFiles.keys.forEach { path -> append("- ${path.takeLast(50)}\n") } // Show only part of path for brevity
                append("\nBrief snippets of generated/updated files (first ~100 chars each):\n")
                generatedFiles.forEach { (path, content) ->
                    append("File: ${path.takeLast(50)}\n```\n${content.trim().take(100)}${if (content.trim().length > 100) "..." else ""}\n```\n\n")
                }
            }
            if (deletedFiles.isNotEmpty()) {
                append("The following files were deleted:\n")
                deletedFiles.forEach { path -> append("- ${path.takeLast(50)}\n") }
            }
        }

        val summaryPrompt = """
            Based on the following code modifications for the app "$lastAppNameForFallback" (Goal: "$lastAppDescriptionForFallback"), please provide a concise, user-friendly summary.
            Focus on what was achieved or changed from a user or developer perspective.

            Modifications Overview:
            $changesDescription

            Your response MUST be a single JSON object with one REQUIRED key: "summary" (string).
            Example: { "summary": "Refactored the main activity for clarity and updated the user profile layout to include an email field." }
        """.trimIndent()

        val summaryConversation = GeminiConversation() // Fresh conversation for this specific task
        summaryConversation.addUserMessage(summaryPrompt)

        geminiHelper.sendGeminiRequest(
            contents = summaryConversation.getContentsForApi(),
            callback = { response ->
                fileEditorInterface.runOnUiThread {
                    try {
                        val summaryResponseJsonText = geminiHelper.extractTextFromGeminiResponse(response)
                        Log.i(TAG, "Raw summary generation response from AI: $summaryResponseJsonText")
                        if (summaryResponseJsonText.isBlank()) {
                            directErrorHandler("AI returned an empty response for summary generation.", null)
                            callback(originalConclusion ?: "Summary generation failed.")
                            return@runOnUiThread
                        }
                        val newSummary = geminiHelper.parseSummaryResponse(summaryResponseJsonText)
                        if (newSummary != null && newSummary.isNotBlank()) {
                            logViaInterface("✅ AI generated a summary successfully.\n")
                            callback(newSummary)
                        } else {
                            logViaInterface("⚠️ AI failed to generate a valid summary string, using original/default.\n")
                            callback(originalConclusion ?: "AI could not provide a summary for the changes.")
                        }
                    } catch (e: Exception) {
                        directErrorHandler("Failed during AI summary generation processing: ${e.message}", e)
                        callback(originalConclusion ?: "Error during summary generation.")
                        fileEditorInterface.setState(FileEditorActivity.WorkflowState.ERROR)
                    }
                }
            },
            responseSchemaJson = geminiHelper.getSummaryOnlySchema(),
            responseMimeTypeOverride = "application/json"
        )
    }

    private fun applyCodeChanges(modifications: FileModifications) {
        val projectDir = fileEditorInterface.currentProjectDir ?: run {
            directErrorHandler("Project directory is null in applyCodeChanges.", null)
            fileEditorInterface.setState(FileEditorActivity.WorkflowState.ERROR)
            return
        }
        logViaInterface("Gemini Workflow Step: Applying code changes and deletions...\n")

        // Display initial conclusion if present, otherwise clear it for now.
        if (modifications.conclusion != null && modifications.conclusion.isNotBlank()) {
            fileEditorInterface.displayAiConclusion(modifications.conclusion)
        } else {
            fileEditorInterface.displayAiConclusion(null)
            logViaInterface("Initial conclusion from AI is missing or empty.\n")
        }

        if (modifications.filesToWrite.isEmpty() && modifications.filesToDelete.isEmpty()) {
            logViaInterface("AI did not provide any file changes or deletions.\n")
            // Even if no file changes, if conclusion was also empty, try to get a summary
            if (modifications.conclusion == null || modifications.conclusion.isBlank()) {
                logViaInterface("Attempting to generate a summary as no changes and no initial conclusion.\n")
                requestSummaryFromAI(modifications) { generatedSummary ->
                    fileEditorInterface.displayAiConclusion(generatedSummary)
                    fileEditorInterface.setState(FileEditorActivity.WorkflowState.READY_FOR_ACTION)
                }
            } else {
                // No file changes, but there was an initial conclusion.
                fileEditorInterface.displayAiConclusion(modifications.conclusion)
                fileEditorInterface.setState(FileEditorActivity.WorkflowState.READY_FOR_ACTION)
            }
            return
        }

        ProjectFileUtils.processFileChangesAndDeletions(
            projectDir,
            modifications.filesToWrite,
            modifications.filesToDelete,
            directLogAppender
        ) { writeSuccessCount, writeErrorCount, deleteSuccessCount, deleteErrorCount ->
            fileEditorInterface.runOnUiThread {
                var operationSummary = ""
                if (writeErrorCount > 0 || deleteErrorCount > 0) {
                    operationSummary += "⚠️ Some file operations failed. Writes (Success: $writeSuccessCount, Error: $writeErrorCount), Deletes (Success: $deleteSuccessCount, Error: $deleteErrorCount).\n"
                }
                var changesApplied = false
                if (writeSuccessCount > 0) {
                    operationSummary += "✅ Successfully applied $writeSuccessCount file content changes.\n"
                    changesApplied = true
                }
                if (deleteSuccessCount > 0) {
                    operationSummary += "✅ Successfully deleted $deleteSuccessCount files.\n"
                    changesApplied = true
                }
                logViaInterface(operationSummary.ifBlank { "No specific file operations were logged as successful.\n" })


                if (modifications.conclusion == null || modifications.conclusion.isBlank()) {
                    if (changesApplied || modifications.filesToWrite.isNotEmpty() || modifications.filesToDelete.isNotEmpty()) {
                        logViaInterface("Initial conclusion missing. Requesting summary generation from AI...\n")
                        requestSummaryFromAI(modifications) { generatedSummary ->
                            fileEditorInterface.displayAiConclusion(generatedSummary)
                            fileEditorInterface.setState(FileEditorActivity.WorkflowState.READY_FOR_ACTION)
                        }
                    } else { // No changes applied AND no initial conclusion
                        fileEditorInterface.displayAiConclusion("No specific code changes were made, and no summary was provided by the AI.")
                        fileEditorInterface.setState(FileEditorActivity.WorkflowState.READY_FOR_ACTION)
                    }
                } else { // Conclusion was present initially
                    fileEditorInterface.displayAiConclusion(modifications.conclusion)
                    fileEditorInterface.setState(FileEditorActivity.WorkflowState.READY_FOR_ACTION)
                }
            }
        }
    }
}