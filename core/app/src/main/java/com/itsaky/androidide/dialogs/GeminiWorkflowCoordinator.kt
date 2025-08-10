package com.itsaky.androidide.dialogs

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import java.io.FileReader
import java.io.IOException

class GeminiWorkflowCoordinator(
    private val geminiHelper: GeminiHelper,
    private val directLogAppender: (String) -> Unit,
    private val bridge: ViewModelFileEditorBridge
) {
    companion object {
        private const val TAG = "AiWorkflow"
        private const val PROTECTED_VERSION_FILE = ".version_source"

        private const val MAX_STRUCTURED_RETRIES = 2
        private const val MAX_FALLBACK_RETRIES = 2
        private const val MAX_SUMMARY_RETRIES = 1
        private const val RAW_LOG_SNIPPET = 2048
    }

    private val conversation = GeminiConversation()
    private val selectedFilesForModification = mutableListOf<String>()
    private var lastAppDescriptionForFallback: String = ""
    private var lastAppNameForFallback: String = ""
    private var lastFileContextForFallback: String = ""

    // New: guardrails for auto-build
    private var autoBuildAfterApply = false
    private var autoRunAfterBuild = false
    private var hasTriggeredAutoBuild = false
    private var encounteredError = false
    private var anyChangesApplied = false

    private fun logViaBridge(message: String) = bridge.appendToLogBridge(message)

    private fun modelForStructuredSteps(): String? {
        val current = geminiHelper.currentModelIdentifier
        return if (current.equals("gpt-5", ignoreCase = true)) "gpt-5-mini" else null
    }

    fun startModificationFlow(
        appName: String,
        appDescription: String,
        projectDir: File,
        autoBuild: Boolean = false,
        autoRun: Boolean = false
    ) {
        val provider =
            if (geminiHelper.currentModelIdentifier.startsWith("gpt-", ignoreCase = true)) "OpenAI" else "Gemini"
        logViaBridge("AI Workflow ($provider): Starting for project '$appName'\n")

        // reset run-level flags
        conversation.clear()
        selectedFilesForModification.clear()
        bridge.currentProjectDirBridge = projectDir
        bridge.displayAiConclusionBridge(null)

        autoBuildAfterApply = autoBuild
        autoRunAfterBuild = autoRun
        hasTriggeredAutoBuild = false
        encounteredError = false
        anyChangesApplied = false

        lastAppNameForFallback = appName
        lastAppDescriptionForFallback = appDescription

        bridge.updateStateBridge(AiWorkflowState.SELECTING_FILES)
        identifyFilesToModify(appName, appDescription, projectDir)
    }

    private fun identifyFilesToModify(appName: String, appDescription: String, projectDir: File) {
        logViaBridge("AI Workflow Step: Identifying files to modify/create...\n")
        val files = ProjectFileUtils.scanProjectFiles(projectDir)

        bridge.runOnUiThreadBridge {
            if (files.isEmpty() && !bridge.isModifyingExistingProjectBridge) {
                encounteredError = true
                bridge.handleErrorBridge(
                    "No code files found in the newly created project template. Cannot proceed.",
                    null
                )
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
        val fileListText = if (files.isNotEmpty()) files.joinToString("\n") { "- $it" }
        else "No existing editable files were found in this project. You might need to create all necessary files from scratch."
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

        geminiHelper.sendApiRequest(
            contents = conversation.getContentsForApi(),
            callback = { response ->
                try {
                    val responseText = geminiHelper.extractTextFromApiResponse(response)
                    Log.d(TAG, "Raw AI file selection response: $responseText")
                    logViaBridge("AI file selection response received.\n")
                    val jsonArrayText = geminiHelper.extractJsonArrayFromText(responseText)
                    val jsonArray = org.json.JSONArray(jsonArrayText)
                    selectedFilesForModification.clear()
                    for (i in 0 until jsonArray.length()) {
                        jsonArray.getString(i).takeIf { it.isNotBlank() }?.let { selectedFilesForModification.add(it) }
                    }
                    conversation.addModelMessage(responseText)

                    if (selectedFilesForModification.isEmpty()) {
                        logViaBridge("⚠️ AI did not select/suggest any files. Will proceed to generate based on description.\n")
                    } else {
                        logViaBridge("AI selected/suggested ${selectedFilesForModification.size} files:\n${selectedFilesForModification.joinToString("\n") { "  - $it" }}\n")
                    }
                    loadSelectedFilesAndAskForCode(appName, appDescription)
                } catch (e: Exception) {
                    encounteredError = true
                    val rawResponse = geminiHelper.extractTextFromApiResponse(response)
                    val errorMessage = when (e) {
                        is IllegalArgumentException -> "AI did not provide a valid list of files for selection: ${e.message}. Response: $rawResponse"
                        is JSONException -> "Failed to parse AI's file selection as JSON: ${e.message}. Response: $rawResponse"
                        else -> "Unexpected error during file selection: ${e.message}"
                    }
                    bridge.handleErrorBridge(errorMessage, e)
                }
            },
            responseMimeTypeOverride = "application/json"
        )
    }

    private fun loadSelectedFilesAndAskForCode(appName: String, appDescription: String) {
        val projectDir = bridge.currentProjectDirBridge ?: run {
            encounteredError = true
            bridge.handleErrorBridge("Project directory is null in loadSelectedFilesAndAskForCode (Coordinator).", null)
            return
        }

        bridge.updateStateBridge(AiWorkflowState.GENERATING_CODE)
        logViaBridge("AI Workflow Step: Loading selected files for code generation...\n")
        val fileContentsMap = mutableMapOf<String, String>()

        for (filePath in selectedFilesForModification) {
            val file = File(projectDir, filePath)
            if (!file.exists() || !file.isFile) {
                logViaBridge("Note: File '$filePath' not found. Will ask AI to create it.\n")
                fileContentsMap[filePath] = "// File: $filePath (This file is new or was not found. Please provide its complete content based on the app description.)"
            } else {
                try {
                    fileContentsMap[filePath] = FileReader(file).use { it.readText() }
                } catch (e: IOException) {
                    logViaBridge("⚠️ Error reading file $filePath: ${e.message}. Will ask AI to provide content.\n")
                    fileContentsMap[filePath] = "// File: $filePath (Error reading existing content. Please regenerate based on the app description and its intended role.)"
                }
            }
        }

        if (fileContentsMap.isEmpty()) {
            if (selectedFilesForModification.isNotEmpty()) {
                logViaBridge("All files selected by AI are new or were unreadable. AI will generate their content.\n")
            } else {
                logViaBridge("No files were selected by AI (or project is empty). AI will be prompted to generate necessary files from scratch based on description.\n")
            }
        } else {
            logViaBridge("Loaded/identified ${fileContentsMap.size} files for AI processing.\n")
        }

        sendGeminiCodeGenerationPrompt(appName, appDescription, fileContentsMap)
    }

    private fun sendGeminiCodeGenerationPrompt(appName: String, appDescription: String, fileContentsMap: Map<String, String>) {
        val filesContentText = buildString {
            if (fileContentsMap.isNotEmpty()) {
                fileContentsMap.forEach { (path, content) -> append("FILE: $path\n```\n$content\n```\n\n") }
            } else {
                append("No existing file content is provided. You must generate all necessary files from scratch based on the app description.\n")
            }
        }
        lastFileContextForFallback = filesContentText

        val currentPackageName = ProjectFileUtils.findCommonPackageName(bridge.currentProjectDirBridge, appName, fileContentsMap.values.toList())

        val prompt = """
    **AI GOAL: Full Android App Implementation**

    You are an expert Android developer tasked with generating a complete and functional application based on the user's request.

    **## PRIMARY OBJECTIVE ##**
    App Name: "$appName"
    User's Goal: "$appDescription"

    **## PROJECT FILE CONTEXT ##**
    $filesContentText

    **## CRITICAL INSTRUCTIONS ##**
    1. Generate any NEW files required to meet the goal (Activities, data classes, adapters, XML).
    2. Provide FULL and VALID content for every file you output. No placeholders.
    3. If you reference resources, define them (e.g., strings.xml). Avoid build errors.
    4. Use View Binding, not findViewById.
    5. Base package name: $currentPackageName
    6. Include a concise developer-friendly "conclusion" explaining the changes.

    **## RESPONSE FORMAT ##**
    Return a single JSON object with keys: "filesToWrite", "filesToDelete", "conclusion".
    """.trimIndent()

        requestStructuredGeneration(prompt = prompt, attempt = 0)
    }

    private fun requestStructuredGeneration(prompt: String, attempt: Int) {
        if (attempt == 0) {
            conversation.addUserMessage(prompt)
        } else {
            conversation.addUserMessage("Retry ($attempt/$MAX_STRUCTURED_RETRIES): The previous response was empty or invalid. Reply with a SINGLE valid JSON object containing keys filesToWrite, filesToDelete, and conclusion. No prose.")
            logViaBridge("Retrying structured generation (attempt $attempt)...\n")
        }

        val overrideModel = modelForStructuredSteps()
        if (overrideModel != null && attempt == 0) {
            logViaBridge("Note: Using $overrideModel for structured generation to improve reliability (user selected ${geminiHelper.currentModelIdentifier}).\n")
        }

        logViaBridge("Sending file context to AI for structured code generation${if (attempt > 0) " (retry $attempt)" else ""}...\n")

        geminiHelper.sendApiRequest(
            contents = conversation.getContentsForApi(),
            callback = { response ->
                try {
                    val responseJsonText = geminiHelper.extractTextFromApiResponse(response)
                    if (responseJsonText.isBlank()) {
                        val raw = response.toString()
                        logViaBridge("⚠️ Empty structured response. Raw snippet:\n${raw.take(RAW_LOG_SNIPPET)}\n\n")
                        if (attempt < MAX_STRUCTURED_RETRIES) {
                            requestStructuredGeneration(prompt, attempt + 1)
                        } else {
                            logViaBridge("⚠️ AI returned empty structured response after $MAX_STRUCTURED_RETRIES retries. Attempting fallback...\n")
                            sendGeminiFallbackFileRequest(FileModifications(emptyMap(), emptyList(), null), attempt = 0)
                        }
                        return@sendApiRequest
                    }

                    Log.i(TAG, "Raw structured JSON response from AI: \n$responseJsonText")
                    conversation.addModelMessage(responseJsonText)
                    logViaBridge("AI responded with structured data.\n")

                    val modifications = geminiHelper.parseAndConvertStructuredResponse(responseJsonText)

                    if (modifications.filesToWrite.isEmpty()) {
                        logViaBridge("⚠️ AI's structured response did not include any files to write. Attempting fallback for files...\n")
                        sendGeminiFallbackFileRequest(modifications, attempt = 0)
                    } else {
                        applyCodeChangesAndOrGetSummary(modifications)
                    }
                } catch (e: Exception) {
                    encounteredError = true
                    logViaBridge("⚠️ Error processing structured response: ${e.message}\n")
                    if (attempt < MAX_STRUCTURED_RETRIES) {
                        requestStructuredGeneration(prompt, attempt + 1)
                    } else {
                        bridge.handleErrorBridge("Failed during structured AI code generation after retries: ${e.message}", e)
                    }
                }
            },
            responseSchemaJson = geminiHelper.getFileModificationsSchema(),
            responseMimeTypeOverride = "application/json",
            modelIdentifierOverride = overrideModel
        )
    }

    private fun sendGeminiFallbackFileRequest(originalModifications: FileModifications, attempt: Int = 0) {
        if (attempt == 0) {
            logViaBridge("Attempting fallback: Asking AI specifically for file content...\n")
        } else {
            logViaBridge("Retrying fallback file request (attempt $attempt)...\n")
        }

        val fallbackPrompt = """
        The previous attempt to generate code modifications for app '$lastAppNameForFallback' (Goal: "$lastAppDescriptionForFallback") using a comprehensive structured JSON output did not yield any files to write.

        Original file context provided to AI:
        $lastFileContextForFallback

        Please re-evaluate and provide ONLY the necessary file content.
        Your response MUST be a single JSON object with one key: "filesToWrite".
        "filesToWrite" should be an array of objects, where each object has "filePath" (string) and "fileContent" (string).
        Provide complete and valid content for each file. Do not use placeholders.
        You DO NOT need to provide "filesToDelete" or "conclusion" in this fallback response.
        """.trimIndent()

        val fallbackConversation = GeminiConversation().apply {
            addUserMessage(fallbackPrompt)
            if (attempt > 0) {
                addUserMessage("Retry ($attempt/$MAX_FALLBACK_RETRIES): The previous fallback response was empty/invalid. Return ONLY a valid JSON object with the 'filesToWrite' array. No prose.")
            }
        }

        val overrideModel = modelForStructuredSteps()

        geminiHelper.sendApiRequest(
            contents = fallbackConversation.getContentsForApi(),
            callback = { response ->
                try {
                    val fallbackResponseJsonText = geminiHelper.extractTextFromApiResponse(response)
                    if (fallbackResponseJsonText.isBlank()) {
                        val raw = response.toString()
                        logViaBridge("⚠️ Empty fallback response. Raw snippet:\n${raw.take(RAW_LOG_SNIPPET)}\n\n")
                        if (attempt < MAX_FALLBACK_RETRIES) {
                            sendGeminiFallbackFileRequest(originalModifications, attempt + 1)
                        } else {
                            logViaBridge("⚠️ Fallback for files also yielded no response after retries.\n")
                            applyCodeChangesAndOrGetSummary(originalModifications)
                        }
                        return@sendApiRequest
                    }
                    Log.i(TAG, "Raw fallback file response from AI: $fallbackResponseJsonText")

                    val fallbackFilesMap = geminiHelper.parseMinimalFilesResponse(fallbackResponseJsonText)
                    val finalModifications = if (!fallbackFilesMap.isNullOrEmpty()) {
                        logViaBridge("✅ Fallback for files successful. AI provided files to write.\n")
                        originalModifications.copy(filesToWrite = fallbackFilesMap)
                    } else {
                        if (attempt < MAX_FALLBACK_RETRIES) {
                            logViaBridge("⚠️ Fallback parsed to no files. Retrying fallback...\n")
                            sendGeminiFallbackFileRequest(originalModifications, attempt + 1)
                            return@sendApiRequest
                        } else {
                            logViaBridge("⚠️ Fallback for files yielded no files after retries.\n")
                            originalModifications
                        }
                    }
                    applyCodeChangesAndOrGetSummary(finalModifications)
                } catch (e: Exception) {
                    encounteredError = true
                    logViaBridge("⚠️ Error processing fallback file response: ${e.message}\n")
                    if (attempt < MAX_FALLBACK_RETRIES) {
                        sendGeminiFallbackFileRequest(originalModifications, attempt + 1)
                    } else {
                        bridge.handleErrorBridge("Failed during AI fallback file request after retries: ${e.message}", e)
                        applyCodeChangesAndOrGetSummary(originalModifications)
                    }
                }
            },
            responseSchemaJson = geminiHelper.getMinimalFilesSchema(),
            responseMimeTypeOverride = "application/json",
            modelIdentifierOverride = overrideModel
        )
    }

    private fun applyCodeChangesAndOrGetSummary(modifications: FileModifications) {
        val projectDir = bridge.currentProjectDirBridge ?: run {
            encounteredError = true
            bridge.handleErrorBridge("Project directory is null before applying changes.", null); return
        }

        val filteredFilesToDelete = modifications.filesToDelete.filterNot { filePath -> File(filePath).name == PROTECTED_VERSION_FILE }
        if (modifications.filesToDelete.size != filteredFilesToDelete.size) {
            logViaBridge("Note: Protected system file '$PROTECTED_VERSION_FILE' was excluded from deletion.\n")
        }

        if (modifications.filesToWrite.isNotEmpty() || filteredFilesToDelete.isNotEmpty()) {
            logViaBridge("AI Workflow Step: Applying code changes and deletions...\n")
            ProjectFileUtils.processFileChangesAndDeletions(
                projectDir, modifications.filesToWrite, filteredFilesToDelete, directLogAppender
            ) { writeSuccessCount, writeErrorCount, deleteSuccessCount, deleteErrorCount ->
                bridge.runOnUiThreadBridge {
                    var summary = ""
                    if (writeErrorCount > 0 || deleteErrorCount > 0) {
                        summary += "⚠️ Some file operations failed. Writes (Success: $writeSuccessCount, Error: $writeErrorCount), Deletes (Success: $deleteSuccessCount, Error: $deleteErrorCount).\n"
                    }
                    var changesApplied = false
                    if (writeSuccessCount > 0) { summary += "✅ Successfully applied $writeSuccessCount file content changes.\n"; changesApplied = true }
                    if (deleteSuccessCount > 0) { summary += "✅ Successfully deleted $deleteSuccessCount files.\n"; changesApplied = true }
                    anyChangesApplied = anyChangesApplied || changesApplied
                    logViaBridge(summary.ifBlank { "No specific file operations were logged as successful.\n" })

                    if (modifications.conclusion.isNullOrBlank()) {
                        if (changesApplied) {
                            logViaBridge("Initial conclusion missing. Requesting summary generation from AI...\n")
                            requestSummaryFromAI(modifications, attempt = 0)
                        } else {
                            finishAndMaybeBuild("No specific code changes were made, and no summary was provided by the AI.")
                        }
                    } else {
                        finishAndMaybeBuild(modifications.conclusion)
                    }
                }
            }
        } else {
            logViaBridge("AI did not provide any file changes or deletions.\n")
            if (modifications.conclusion.isNullOrBlank()) {
                logViaBridge("Attempting to generate a summary as no changes and no initial conclusion.\n")
                requestSummaryFromAI(modifications, attempt = 0)
            } else {
                finishAndMaybeBuild(modifications.conclusion)
            }
        }
    }

    private fun requestSummaryFromAI(currentModifications: FileModifications, attempt: Int) {
        val generatedFiles = currentModifications.filesToWrite
        val deletedFiles = currentModifications.filesToDelete
        val originalConclusion = currentModifications.conclusion

        if (generatedFiles.isEmpty() && deletedFiles.isEmpty() && originalConclusion.isNullOrBlank()) {
            logViaBridge("No code changes were made, providing a default summary for this specific case.\n")
            finishAndMaybeBuild("No specific code changes or deletions were performed by the AI.")
            return
        }
        if (!originalConclusion.isNullOrBlank()) {
            finishAndMaybeBuild(originalConclusion)
            return
        }

        if (attempt == 0) logViaBridge("Attempting to generate a summary for the applied changes via dedicated AI call...\n")
        else logViaBridge("Retrying summary generation (attempt $attempt)...\n")
        bridge.updateStateBridge(AiWorkflowState.GENERATING_SUMMARY)

        val changesDescription = buildString {
            if (generatedFiles.isNotEmpty()) {
                append("The following files were written or updated:\n")
                generatedFiles.keys.forEach { path -> append("- ${path.takeLast(50)}\n") }
            }
            if (deletedFiles.isNotEmpty()) {
                append("The following files were deleted:\n")
                deletedFiles.forEach { path -> append("- ${path.takeLast(50)}\n") }
            }
        }.ifEmpty { "No specific file content or deletion details to list." }

        val summaryPrompt = """
            Based on the following code modifications for the app "$lastAppNameForFallback" (Goal: "$lastAppDescriptionForFallback"), please provide a concise, user-friendly summary.

            Modifications Overview:
            $changesDescription

            Your response MUST be a single JSON object with one REQUIRED key: "summary" (string).
        """.trimIndent()

        val summaryConversation = GeminiConversation().apply {
            addUserMessage(summaryPrompt)
            if (attempt > 0) addUserMessage("Retry ($attempt/$MAX_SUMMARY_RETRIES): Return ONLY a JSON object with a 'summary' string. No prose.")
        }

        val overrideModel = modelForStructuredSteps()

        geminiHelper.sendApiRequest(
            contents = summaryConversation.getContentsForApi(),
            callback = { response ->
                var finalSummaryToDisplay: String? = originalConclusion
                try {
                    val summaryResponseJsonText = geminiHelper.extractTextFromApiResponse(response)
                    Log.i(TAG, "Raw summary generation response from AI: $summaryResponseJsonText")
                    if (summaryResponseJsonText.isBlank()) {
                        val raw = response.toString()
                        logViaBridge("⚠️ Empty summary response. Raw snippet:\n${raw.take(RAW_LOG_SNIPPET)}\n\n")
                        if (attempt < MAX_SUMMARY_RETRIES) {
                            requestSummaryFromAI(currentModifications, attempt + 1); return@sendApiRequest
                        }
                        finalSummaryToDisplay = originalConclusion ?: "Summary generation attempt failed (empty response)."
                    } else {
                        val newSummary = geminiHelper.parseSummaryResponse(summaryResponseJsonText)
                        if (!newSummary.isNullOrBlank()) {
                            logViaBridge("✅ AI generated a summary successfully.\n")
                            finalSummaryToDisplay = newSummary
                        } else {
                            if (attempt < MAX_SUMMARY_RETRIES) {
                                logViaBridge("⚠️ AI failed to generate a valid summary string. Retrying...\n")
                                requestSummaryFromAI(currentModifications, attempt + 1); return@sendApiRequest
                            }
                            finalSummaryToDisplay = originalConclusion ?: "AI could not provide a summary for the changes."
                        }
                    }
                } catch (e: Exception) {
                    encounteredError = true
                    logViaBridge("⚠️ Error processing summary response: ${e.message}\n")
                    if (attempt < MAX_SUMMARY_RETRIES) {
                        requestSummaryFromAI(currentModifications, attempt + 1); return@sendApiRequest
                    }
                    bridge.handleErrorBridge("Failed during AI summary generation after retries: ${e.message}", e)
                    finalSummaryToDisplay = originalConclusion ?: "Error during summary generation."
                } finally {
                    finishAndMaybeBuild(finalSummaryToDisplay)
                }
            },
            responseSchemaJson = geminiHelper.getSummaryOnlySchema(),
            responseMimeTypeOverride = "application/json",
            modelIdentifierOverride = overrideModel
        )
    }

    // Only auto-build when there was no error and some changes were applied
    private fun finishAndMaybeBuild(finalSummary: String?) {
        bridge.displayAiConclusionBridge(finalSummary)
        bridge.updateStateBridge(AiWorkflowState.READY_FOR_ACTION)

        val projectDir = bridge.currentProjectDirBridge
        val okToAutoBuild = !encounteredError && anyChangesApplied && projectDir != null

        if (autoBuildAfterApply && okToAutoBuild && !hasTriggeredAutoBuild) {
            hasTriggeredAutoBuild = true
            bridge.triggerBuildBridge(projectDir!!, runAfterBuild = autoRunAfterBuild)
        } else {
            if (!autoBuildAfterApply) logViaBridge("ℹ️ Auto-build disabled; waiting for user action.\n")
            if (encounteredError) logViaBridge("⛔ Skipping auto-build due to an earlier error in the AI flow.\n")
            if (!anyChangesApplied) logViaBridge("ℹ️ Skipping auto-build because no code changes were applied.\n")
            if (projectDir == null) logViaBridge("⛔ Skipping auto-build: projectDir is null.\n")
        }
    }
}