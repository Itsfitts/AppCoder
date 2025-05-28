package com.itsaky.androidide.dialogs

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.Locale // Keep for findCommonPackageName if it uses it
import com.itsaky.androidide.dialogs.ViewModelFileEditorBridge // Ensure this import is present
import com.itsaky.androidide.dialogs.AiWorkflowState // Ensure this import is present


class GeminiWorkflowCoordinator(
    private val geminiHelper: GeminiHelper,
    private val directLogAppender: (String) -> Unit,
    private val directErrorHandler: (String, Exception?) -> Unit,
    private val bridge: ViewModelFileEditorBridge // Correct: uses ViewModelFileEditorBridge
) {
    companion object {
        private const val TAG = "GeminiWorkflow"
    }

    private val conversation = GeminiConversation()
    private val selectedFilesForModification = mutableListOf<String>()
    private var lastAppDescriptionForFallback: String = ""
    private var lastAppNameForFallback: String = ""
    private var lastFileContextForFallback: String = ""

    private fun logViaBridge(message: String) = bridge.appendToLogBridge(message)

    fun startModificationFlow(appName: String, appDescription: String, projectDir: File) {
        logViaBridge("Gemini Workflow: Starting for project '$appName'\n")
        conversation.clear()
        selectedFilesForModification.clear()
        bridge.currentProjectDirBridge = projectDir
        bridge.displayAiConclusionBridge(null) // Clear previous conclusion

        lastAppNameForFallback = appName
        lastAppDescriptionForFallback = appDescription

        bridge.updateStateBridge(AiWorkflowState.SELECTING_FILES)
        identifyFilesToModify(appName, appDescription, projectDir)
    }

    private fun identifyFilesToModify(appName: String, appDescription: String, projectDir: File) {
        logViaBridge("Gemini Workflow Step: Identifying files to modify/create...\n")
        // Assuming ProjectFileUtils is accessible or methods are static
        val files = ProjectFileUtils.scanProjectFiles(projectDir)

        bridge.runOnUiThreadBridge {
            if (files.isEmpty() && !bridge.isModifyingExistingProjectBridge) {
                // Use bridge for error handling that updates ViewModel state
                bridge.handleErrorBridge("No code files found in the newly created project template. Cannot proceed.", null)
                // The bridge.handleErrorBridge should also set the state to ERROR
                return@runOnUiThreadBridge
            } else if (files.isEmpty() && bridge.isModifyingExistingProjectBridge) {
                logViaBridge("No existing code files found by scanner in '$appName'. AI will be prompted to create necessary files.\n")
            } else {
                logViaBridge("Found ${files.size} potentially relevant files in the project.\n")
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
        val isExisting = bridge.isModifyingExistingProjectBridge
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
        logViaBridge("Asking AI to select relevant files (expecting JSON array)...\n")

        geminiHelper.sendGeminiRequest(
            contents = conversation.getContentsForApi(),
            callback = { response -> // This callback is from GeminiHelper, already on UI thread via bridge
                try {
                    val responseText = geminiHelper.extractTextFromGeminiResponse(response)
                    Log.d(TAG, "Raw AI file selection response: $responseText") // Internal Logcat
                    logViaBridge("AI file selection response received.\n")
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
                        logViaBridge("⚠️ AI did not select/suggest any files. Will proceed to generate based on description.\n")
                    } else {
                        logViaBridge("AI selected/suggested ${selectedFilesForModification.size} files:\n${selectedFilesForModification.joinToString("\n") { "  - $it" }}\n")
                    }
                    loadSelectedFilesAndAskForCode(appName, appDescription)
                } catch (e: IllegalArgumentException) {
                    bridge.handleErrorBridge("AI did not provide a valid list of files for selection: ${e.message}. Response: ${geminiHelper.extractTextFromGeminiResponse(response)}", e)
                } catch (e: JSONException) {
                    bridge.handleErrorBridge("Failed to parse AI's file selection as JSON: ${e.message}. Response: ${geminiHelper.extractTextFromGeminiResponse(response)}", e)
                } catch (e: Exception) { // Catch any other exception
                    bridge.handleErrorBridge("Unexpected error during file selection: ${e.message}", e)
                }
            },
            responseMimeTypeOverride = "application/json" // Expecting JSON text
        )
    }

    private fun loadSelectedFilesAndAskForCode(appName: String, appDescription: String) {
        val projectDir = bridge.currentProjectDirBridge ?: run {
            bridge.handleErrorBridge("Project directory is null in loadSelectedFilesAndAskForCode (Coordinator).", null)
            return
        }

        bridge.updateStateBridge(AiWorkflowState.GENERATING_CODE)
        logViaBridge("Gemini Workflow Step: Loading selected files for code generation...\n")
        val fileContentsMap = mutableMapOf<String, String>()
        val missingOrUnreadableFiles = mutableListOf<String>()

        for (filePath in selectedFilesForModification) {
            val file = File(projectDir, filePath)
            if (!file.exists() || !file.isFile) {
                logViaBridge("Note: File '$filePath' not found. Will ask AI to create it.\n")
                missingOrUnreadableFiles.add(filePath)
                fileContentsMap[filePath] = "// File: $filePath (This file is new or was not found. Please provide its complete content based on the app description.)"
            } else {
                try {
                    fileContentsMap[filePath] = FileReader(file).use { it.readText() }
                } catch (e: IOException) {
                    logViaBridge("⚠️ Error reading file $filePath: ${e.message}. Will ask AI to provide content.\n")
                    missingOrUnreadableFiles.add(filePath)
                    fileContentsMap[filePath] = "// File: $filePath (Error reading existing content. Please regenerate based on the app description and its intended role.)"
                }
            }
        }
        if (fileContentsMap.isEmpty() && selectedFilesForModification.isNotEmpty()) {
            logViaBridge("All files selected by AI are new or were unreadable. AI will generate their content.\n")
        } else if (fileContentsMap.isEmpty() && selectedFilesForModification.isEmpty()){
            logViaBridge("No files were selected by AI (or project is empty). AI will be prompted to generate necessary files from scratch based on description.\n")
        } else {
            logViaBridge("Loaded/identified ${fileContentsMap.size} files for AI processing.\n")
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

        val currentPackageName = ProjectFileUtils.findCommonPackageName(bridge.currentProjectDirBridge, appName, fileContentsMap.values.toList())
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
        logViaBridge("Sending file context to AI for structured code generation...\n")

        geminiHelper.sendGeminiRequest(
            contents = conversation.getContentsForApi(),
            callback = { response -> // Already on UI thread via bridge in GeminiHelper
                try {
                    val responseJsonText = geminiHelper.extractTextFromGeminiResponse(response)
                    if (responseJsonText.isBlank()) {
                        bridge.handleErrorBridge("AI returned an empty response for structured code generation.", null)
                        return@sendGeminiRequest
                    }
                    Log.i(TAG, "Raw structured JSON response from AI (Main Generation): \n$responseJsonText") // Internal log
                    conversation.addModelMessage(responseJsonText)
                    logViaBridge("AI responded with structured data.\n")

                    val modifications = geminiHelper.parseAndConvertStructuredResponse(responseJsonText)

                    if (modifications.filesToWrite.isEmpty()) {
                        logViaBridge("⚠️ AI's structured response did not include any files to write. Attempting fallback for files...\n")
                        sendGeminiFallbackFileRequest(modifications)
                    } else {
                        applyCodeChangesAndOrGetSummary(modifications)
                    }
                } catch (e: Exception) {
                    bridge.handleErrorBridge("Failed during structured AI code generation response processing: ${e.message}", e)
                }
            },
            responseSchemaJson = geminiHelper.getFileModificationsSchema(),
            responseMimeTypeOverride = "application/json"
        )
    }

    private fun sendGeminiFallbackFileRequest(originalModifications: FileModifications) {
        logViaBridge("Attempting fallback: Asking AI specifically for file content...\n")
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

        val fallbackConversation = GeminiConversation().apply { addUserMessage(fallbackPrompt) }

        geminiHelper.sendGeminiRequest(
            contents = fallbackConversation.getContentsForApi(),
            callback = { response -> // Already on UI thread
                try {
                    val fallbackResponseJsonText = geminiHelper.extractTextFromGeminiResponse(response)
                    if (fallbackResponseJsonText.isBlank()) {
                        // If fallback also fails to give text, proceed with original (empty files)
                        bridge.handleErrorBridge("AI returned an empty response for fallback file request.", null)
                        applyCodeChangesAndOrGetSummary(originalModifications)
                        return@sendGeminiRequest
                    }
                    Log.i(TAG, "Raw fallback file response from AI: $fallbackResponseJsonText")

                    val fallbackFilesMap = geminiHelper.parseMinimalFilesResponse(fallbackResponseJsonText)
                    val finalModifications = if (fallbackFilesMap != null && fallbackFilesMap.isNotEmpty()) {
                        logViaBridge("✅ Fallback for files successful. AI provided files to write.\n")
                        originalModifications.copy(filesToWrite = fallbackFilesMap)
                    } else {
                        logViaBridge("⚠️ Fallback for files also yielded no files to write.\n")
                        originalModifications // Stick with original if fallback yields nothing
                    }
                    applyCodeChangesAndOrGetSummary(finalModifications)
                } catch (e: Exception) {
                    bridge.handleErrorBridge("Failed during AI fallback file request processing: ${e.message}", e)
                    applyCodeChangesAndOrGetSummary(originalModifications) // Proceed with original on error
                }
            },
            responseSchemaJson = geminiHelper.getMinimalFilesSchema(),
            responseMimeTypeOverride = "application/json"
        )
    }

    private fun applyCodeChangesAndOrGetSummary(modifications: FileModifications) {
        val projectDir = bridge.currentProjectDirBridge ?: run {
            bridge.handleErrorBridge("Project directory is null before applying changes.", null)
            return
        }

        if (modifications.filesToWrite.isNotEmpty() || modifications.filesToDelete.isNotEmpty()) {
            logViaBridge("Gemini Workflow Step: Applying code changes and deletions...\n")
            ProjectFileUtils.processFileChangesAndDeletions(
                projectDir,
                modifications.filesToWrite,
                modifications.filesToDelete,
                directLogAppender // For detailed file ops logging not meant for UI log
            ) { writeSuccessCount, writeErrorCount, deleteSuccessCount, deleteErrorCount ->
                bridge.runOnUiThreadBridge { // Ensure bridge calls are on main thread if they update UI
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
                    logViaBridge(operationSummary.ifBlank { "No specific file operations were logged as successful.\n" })

                    if (modifications.conclusion.isNullOrBlank()) {
                        if (changesApplied || modifications.filesToWrite.isNotEmpty() || modifications.filesToDelete.isNotEmpty()) {
                            logViaBridge("Initial conclusion missing. Requesting summary generation from AI...\n")
                            requestSummaryFromAI(modifications) // This will set the final state
                        } else {
                            bridge.displayAiConclusionBridge("No specific code changes were made, and no summary was provided by the AI.")
                            bridge.updateStateBridge(AiWorkflowState.READY_FOR_ACTION)
                        }
                    } else {
                        bridge.displayAiConclusionBridge(modifications.conclusion)
                        bridge.updateStateBridge(AiWorkflowState.READY_FOR_ACTION)
                    }
                }
            }
        } else { // No files to write or delete
            logViaBridge("AI did not provide any file changes or deletions.\n")
            if (modifications.conclusion.isNullOrBlank()) {
                logViaBridge("Attempting to generate a summary as no changes and no initial conclusion.\n")
                requestSummaryFromAI(modifications) // This will set the final state
            } else {
                bridge.displayAiConclusionBridge(modifications.conclusion)
                bridge.updateStateBridge(AiWorkflowState.READY_FOR_ACTION)
            }
        }
    }

    private fun requestSummaryFromAI(currentModifications: FileModifications) {
        val generatedFiles = currentModifications.filesToWrite
        val deletedFiles = currentModifications.filesToDelete
        val originalConclusion = currentModifications.conclusion

        if (generatedFiles.isEmpty() && deletedFiles.isEmpty() && originalConclusion.isNullOrBlank()) {
            logViaBridge("No code changes were made, providing a default summary for this specific case.\n")
            bridge.displayAiConclusionBridge("No specific code changes or deletions were performed by the AI.")
            bridge.updateStateBridge(AiWorkflowState.READY_FOR_ACTION)
            return
        }
        // If we reach here and originalConclusion is NOT blank, it means applyCodeChangesAndOrGetSummary
        // decided a summary was needed despite an existing one. This path should ideally not be hit
        // if applyCodeChangesAndOrGetSummary has the correct logic, but as a safe-guard:
        if (!originalConclusion.isNullOrBlank()) {
            bridge.displayAiConclusionBridge(originalConclusion) // Use the existing one
            bridge.updateStateBridge(AiWorkflowState.READY_FOR_ACTION)
            return
        }

        logViaBridge("Attempting to generate a summary for the applied changes via dedicated AI call...\n")
        bridge.updateStateBridge(AiWorkflowState.GENERATING_SUMMARY)

        val changesDescription = buildString {
            if (generatedFiles.isNotEmpty()) {
                append("The following files were written or updated:\n")
                generatedFiles.keys.forEach { path -> append("- ${path.takeLast(50)}\n") }
                append("\nBrief snippets of generated/updated files (first ~100 chars each):\n")
                generatedFiles.forEach { (path, content) ->
                    append("File: ${path.takeLast(50)}\n```\n${content.trim().take(100)}${if (content.trim().length > 100) "..." else ""}\n```\n\n")
                }
            }
            if (deletedFiles.isNotEmpty()) {
                append("The following files were deleted:\n")
                deletedFiles.forEach { path -> append("- ${path.takeLast(50)}\n") }
            }
        }.ifEmpty { "No specific file content or deletion details to list." }


        val summaryPrompt = """
            Based on the following code modifications for the app "$lastAppNameForFallback" (Goal: "$lastAppDescriptionForFallback"), please provide a concise, user-friendly summary.
            Focus on what was achieved or changed from a user or developer perspective.

            Modifications Overview:
            $changesDescription

            Your response MUST be a single JSON object with one REQUIRED key: "summary" (string).
            Example: { "summary": "Refactored the main activity for clarity and updated the user profile layout to include an email field." }
        """.trimIndent()

        val summaryConversation = GeminiConversation().apply{ addUserMessage(summaryPrompt) }

        geminiHelper.sendGeminiRequest(
            contents = summaryConversation.getContentsForApi(),
            callback = { response -> // Already on UI thread
                var finalSummaryToDisplay: String? = originalConclusion // Default to original
                try {
                    val summaryResponseJsonText = geminiHelper.extractTextFromGeminiResponse(response)
                    Log.i(TAG, "Raw summary generation response from AI: $summaryResponseJsonText")
                    if (summaryResponseJsonText.isBlank()) {
                        // bridge.handleErrorBridge("AI returned an empty response for summary generation.", null) // Already logged by helper if empty
                        logViaBridge("⚠️ AI returned an empty response for summary generation, using default.")
                        finalSummaryToDisplay = originalConclusion ?: "Summary generation attempt failed (empty response)."
                    } else {
                        val newSummary = geminiHelper.parseSummaryResponse(summaryResponseJsonText)
                        if (newSummary != null && newSummary.isNotBlank()) {
                            logViaBridge("✅ AI generated a summary successfully.\n")
                            finalSummaryToDisplay = newSummary
                        } else {
                            logViaBridge("⚠️ AI failed to generate a valid summary string, using original/default.\n")
                            finalSummaryToDisplay = originalConclusion ?: "AI could not provide a summary for the changes."
                        }
                    }
                } catch (e: Exception) {
                    bridge.handleErrorBridge("Failed during AI summary generation processing: ${e.message}", e)
                    finalSummaryToDisplay = originalConclusion ?: "Error during summary generation."
                } finally {
                    bridge.displayAiConclusionBridge(finalSummaryToDisplay)
                    bridge.updateStateBridge(AiWorkflowState.READY_FOR_ACTION)
                }
            },
            responseSchemaJson = geminiHelper.getSummaryOnlySchema(),
            responseMimeTypeOverride = "application/json"
        )
    }
}