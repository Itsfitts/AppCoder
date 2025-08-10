// GeminiWorkflowCoordinator.kt
package com.itsaky.androidide.dialogs

import android.util.Log
import com.itsaky.androidide.services.AiForegroundService
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.max

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

        // Batch sizing defaults (approx char budget; ~4 chars ≈ 1 token)
        private const val BUDGET_MINI_CHARS = 90_000
        private const val BUDGET_BIG_CHARS = 100_000

        // We will dynamically choose max files per batch by mode
        private const val FILES_PER_BATCH_ALL = Int.MAX_VALUE // we enforce single "ALL" batch
        private const val FILES_PER_BATCH_10 = 10
        private const val FILES_PER_BATCH_5 = 5
        private const val FILES_PER_BATCH_1 = 1

        // Selection previews
        private const val SELECTION_TOTAL_PREVIEW_BUDGET = 80_000
        private const val SELECTION_PER_FILE_PREVIEW = 800

        // Allow limited out-of-batch writes
        private const val MAX_EXTRA_WRITES_ACCEPTED = 24

        // If ALL fails this many times in a row, we disable singles (avoid spam)
        private const val MAX_CONSECUTIVE_ALL_FAILURES = 3
    }

    private enum class BatchMode { ALL, TEN, FIVE, ONE }

    private val conversation = GeminiConversation()
    private val selectedFilesForModification = mutableListOf<String>()
    private var lastAppDescriptionForFallback: String = ""
    private var lastAppNameForFallback: String = ""
    private var lastFileContextForFallback: String = ""

    private var autoBuildAfterApply = false
    private var autoRunAfterBuild = false
    private var hasTriggeredAutoBuild = false
    private var encounteredError = false
    private var anyChangesApplied = false

    private var extraWritesAcceptedCount = 0

    private fun logViaBridge(message: String) = bridge.appendToLogBridge(message)

    // Prefer faster model for structured steps to reduce latency/timeouts
    private fun modelForStructuredSteps(): String? {
        val current = geminiHelper.currentModelIdentifier
        return when {
            current.equals("gpt-5", ignoreCase = true) -> "gpt-5-mini"
            current.startsWith("gemini-2.5-pro", ignoreCase = true) -> "gemini-2.5-flash"
            else -> null
        }
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

        conversation.clear()
        selectedFilesForModification.clear()
        bridge.currentProjectDirBridge = projectDir
        bridge.displayAiConclusionBridge(null)

        autoBuildAfterApply = autoBuild
        autoRunAfterBuild = autoRun
        hasTriggeredAutoBuild = false
        encounteredError = false
        anyChangesApplied = false
        extraWritesAcceptedCount = 0

        lastAppNameForFallback = appName
        lastAppDescriptionForFallback = appDescription

        AiForegroundService.start(bridge.getContextBridge(), "Generating code for $appName")

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
                AiForegroundService.stop(bridge.getContextBridge())
                return@runOnUiThreadBridge
            } else if (files.isEmpty() && bridge.isModifyingExistingProjectBridge) {
                logViaBridge("No existing code files found by scanner in '$appName'. AI will be prompted to create necessary files.\n")
            } else {
                logViaBridge("Found ${files.size} potentially relevant files in the project.\n")
            }
            sendGeminiFileSelectionPrompt(appName, appDescription, files)
        }
    }

    private fun buildFileListPreview(files: List<String>, projectDir: File): String {
        if (files.isEmpty()) return "No existing editable files were found in this project. You might need to create all necessary files from scratch."

        val textLikeExt = setOf("kt", "java", "kts", "gradle", "xml", "txt", "md", "pro", "properties")
        var remaining = SELECTION_TOTAL_PREVIEW_BUDGET
        val sb = StringBuilder()
        for (path in files) {
            if (remaining <= 0) break
            sb.append("- ").append(path).append('\n')
            try {
                val f = File(projectDir, path)
                val ext = path.substringAfterLast('.', "").lowercase()
                if (f.exists() && f.isFile && ext in textLikeExt) {
                    val preview = readPreview(f, max(SELECTION_PER_FILE_PREVIEW, 200))
                    if (preview.isNotBlank()) {
                        val block = "```$ext\n$preview\n```\n"
                        val take = block.take(remaining)
                        sb.append(take)
                        remaining -= take.length
                    } else {
                        sb.append("```").append(ext).append("\n").append("(empty or unreadable)\n```\n")
                    }
                } else {
                    sb.append("```").append(ext).append("\n").append("(binary or unsupported; preview omitted)\n```\n")
                }
            } catch (_: Throwable) {
                sb.append("```txt\n").append("(error reading preview)\n```\n")
            }
        }
        return sb.toString()
    }

    private fun readPreview(file: File, limit: Int): String {
        return try {
            FileReader(file).use { fr ->
                val buf = CharArray(limit)
                val read = fr.read(buf, 0, limit)
                if (read > 0) String(buf, 0, read) else ""
            }
        } catch (_: Throwable) { "" }
    }

    private fun sendGeminiFileSelectionPrompt(appName: String, appDescription: String, files: List<String>) {
        val projectDir = bridge.currentProjectDirBridge
        val fileListText = if (files.isNotEmpty() && projectDir != null) {
            buildFileListPreview(files, projectDir)
        } else if (files.isNotEmpty()) {
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

            Below is a file index with small content previews from the project (trimmed for brevity):
            $fileListText

            Based on the app's goal and these previews, which files would MOST LIKELY need to be $actionVerb to achieve the goal?
            - If previews show hardcoded strings or outdated text, include those files.
            - If the list is incomplete or a file clearly must be created, include its relative path.

            Respond ONLY with a JSON array of the relative file paths. Example:
            ["app/src/main/java/com/example/myapp/MainActivity.kt", "app/src/main/res/layout/activity_main.xml"]
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
                    val jsonArray = JSONArray(jsonArrayText)
                    selectedFilesForModification.clear()
                    for (i in 0 until jsonArray.length()) {
                        jsonArray.getString(i).takeIf { it.isNotBlank() }?.let { selectedFilesForModification.add(it) }
                    }
                    conversation.addModelMessage(responseText)

                    if (selectedFilesForModification.isEmpty()) {
                        logViaBridge("⚠️ AI did not select/suggest any files. Will attempt initial generation directly from description.\n")
                    } else {
                        logViaBridge("AI selected/suggested ${selectedFilesForModification.size} files:\n${selectedFilesForModification.joinToString("\n") { "  - $it" }}\n")
                    }
                    loadSelectedFilesAndGenerateInBatches(appName, appDescription)
                } catch (e: Exception) {
                    encounteredError = true
                    val rawResponse = geminiHelper.extractTextFromApiResponse(response)
                    val errorMessage = when (e) {
                        is IllegalArgumentException -> "AI did not provide a valid list of files for selection: ${e.message}. Response: $rawResponse"
                        is JSONException -> "Failed to parse AI's file selection as JSON: ${e.message}. Response: $rawResponse"
                        else -> "Unexpected error during file selection: ${e.message}"
                    }
                    bridge.handleErrorBridge(errorMessage, e)
                    AiForegroundService.stop(bridge.getContextBridge())
                }
            },
            responseMimeTypeOverride = "application/json"
        )
    }

    private fun loadSelectedFilesAndGenerateInBatches(appName: String, appDescription: String) {
        val projectDir = bridge.currentProjectDirBridge ?: run {
            encounteredError = true
            bridge.handleErrorBridge("Project directory is null before batching generation.", null)
            AiForegroundService.stop(bridge.getContextBridge())
            return
        }

        bridge.updateStateBridge(AiWorkflowState.GENERATING_CODE)
        logViaBridge("AI Workflow Step: Loading selected files for batched code generation...\n")
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
            logViaBridge("No files were selected by AI (or project is empty). Asking AI to generate essential files from description...\n")
            generateInitialFilesFromDescription(appName, appDescription, attempt = 0)
            return
        } else {
            logViaBridge("Loaded/identified ${fileContentsMap.size} files for AI processing (batched).\n")
        }

        val id = geminiHelper.currentModelIdentifier.lowercase()
        val batchCharBudget =
            if (id.contains("gpt-5-mini") || id.contains("gpt-5-nano")) BUDGET_MINI_CHARS else BUDGET_BIG_CHARS

        generateInBatches(appName, appDescription, fileContentsMap, batchCharBudget)
    }

    private fun generateInitialFilesFromDescription(appName: String, appDescription: String, attempt: Int) {
        val projectDir = bridge.currentProjectDirBridge ?: run {
            encounteredError = true
            bridge.handleErrorBridge("Project directory is null before initial generation.", null)
            AiForegroundService.stop(bridge.getContextBridge())
            return
        }
        val existingFiles = ProjectFileUtils.scanProjectFiles(projectDir)
        val existingListText = if (existingFiles.isNotEmpty()) {
            existingFiles.joinToString("\n") { "- $it" }
        } else {
            "(no existing files)"
        }

        val prompt = """
            You are creating/updating an Android application named "$appName".
            Goal: "$appDescription"

            The project may be minimal or empty. Here is the current file list (if any):
            $existingListText

            Produce the essential set of files to implement the goal. Update existing files when appropriate and create missing ones.
            Respond ONLY as JSON with key "filesToWrite": an array of objects, each having:
              - "filePath": relative path under project root (e.g., "app/src/main/AndroidManifest.xml")
              - "fileContent": the full content of that file

            Constraints:
            - Return at least 1 file.
            - Keep this response to a practical subset (up to 8 files). Prioritize: Gradle/build files, AndroidManifest.xml, entry Activity/Compose file, layout(s), values/strings.xml.
            - Use Kotlin if source code is required.
        """.trimIndent()

        val conv = GeminiConversation().apply { addUserMessage(prompt) }
        val overrideModel = modelForStructuredSteps()

        geminiHelper.sendApiRequest(
            contents = conv.getContentsForApi(),
            callback = { response ->
                try {
                    val txt = geminiHelper.extractTextFromApiResponse(response)
                    Log.i(TAG, "Initial generation JSON (first 512 chars): ${txt.take(512)}")
                    val filesMap = geminiHelper.parseMinimalFilesResponse(txt)
                    if (filesMap != null && filesMap.isNotEmpty()) {
                        logViaBridge("AI proposed ${filesMap.size} initial file(s) from description.\n")
                        applyCodeChangesAndOrGetSummary(FileModifications(filesMap, emptyList(), null))
                    } else {
                        if (attempt < MAX_FALLBACK_RETRIES) {
                            logViaBridge("⚠️ AI returned no files for initial generation. Retrying...\n")
                            generateInitialFilesFromDescription(appName, appDescription, attempt + 1)
                        } else {
                            encounteredError = true
                            bridge.handleErrorBridge("AI did not produce any files to write after retries.", null)
                            AiForegroundService.stop(bridge.getContextBridge())
                        }
                    }
                } catch (e: Exception) {
                    encounteredError = true
                    bridge.handleErrorBridge("Error during initial file generation: ${e.message}", e)
                    AiForegroundService.stop(bridge.getContextBridge())
                }
            },
            responseSchemaJson = geminiHelper.getMinimalFilesSchema(),
            responseMimeTypeOverride = "application/json",
            modelIdentifierOverride = overrideModel
        )
    }

    private fun estimateCharsForFile(path: String, content: String): Int {
        return path.length + content.length + 128
    }

    private fun buildBatchesBySize(
        paths: List<String>,
        files: Map<String, String>,
        maxCharsPerBatch: Int,
        maxFilesPerBatch: Int
    ): ArrayDeque<List<String>> {
        val queue = ArrayDeque<List<String>>()
        var current = mutableListOf<String>()
        var size = 0
        for (p in paths) {
            val c = files[p] ?: ""
            val add = estimateCharsForFile(p, c)
            val wouldOverflow = (size + add > maxCharsPerBatch) || (current.size + 1 > maxFilesPerBatch)
            if (wouldOverflow && current.isNotEmpty()) {
                queue.addLast(current.toList())
                current = mutableListOf()
                size = 0
            }
            current.add(p)
            size += add
        }
        if (current.isNotEmpty()) queue.addLast(current.toList())
        return queue
    }

    private fun makePendingForMode(
        remaining: List<String>,
        mode: BatchMode,
        filesMap: Map<String, String>,
        maxCharsPerBatch: Int
    ): ArrayDeque<List<String>> {
        return when (mode) {
            BatchMode.ALL -> ArrayDeque<List<String>>().apply {
                if (remaining.isNotEmpty()) addLast(remaining.toList())
            }
            BatchMode.TEN -> buildBatchesBySize(remaining, filesMap, maxCharsPerBatch, FILES_PER_BATCH_10)
            BatchMode.FIVE -> buildBatchesBySize(remaining, filesMap, maxCharsPerBatch, FILES_PER_BATCH_5)
            BatchMode.ONE -> buildBatchesBySize(remaining, filesMap, maxCharsPerBatch, FILES_PER_BATCH_1)
        }
    }

    private fun generateInBatches(
        appName: String,
        appDescription: String,
        fileContentsMap: Map<String, String>,
        maxCharsPerBatch: Int
    ) {
        val overrideModel = modelForStructuredSteps()
        val aggregatedFiles = linkedMapOf<String, String>()
        val remainingPaths = fileContentsMap.keys.toMutableList()

        var currentMode = BatchMode.ALL
        var consecutiveAllFailures = 0
        var singlesAllowed = true

        var pendingBatches = makePendingForMode(remainingPaths, currentMode, fileContentsMap, maxCharsPerBatch)

        fun promptForBatch(paths: List<String>): String {
            val filesContentText = buildString {
                paths.forEach { path ->
                    val content = fileContentsMap[path] ?: ""
                    append("FILE: $path\n```\n$content\n```\n\n")
                }
            }
            lastFileContextForFallback = filesContentText
            return """
                You are updating an Android app called "$appName".
                Goal: "$appDescription"

                Primary scope for THIS batch is the files listed below:
                $filesContentText

                Respond with a single JSON object:
                {
                  "filesToWrite": [
                    { "filePath": "<one of the listed paths OR a small number of additional necessary files>", "fileContent": "<full content>" }
                  ],
                  "unchanged": [
                    "<every listed path you did NOT change>"
                  ]
                }

                Rules:
                - Every file from the listed batch MUST appear exactly once: either in filesToWrite (if you changed it) or in unchanged (if you didn't).
                - If you discover that a small number of additional existing files MUST be modified to make the change actually take effect (e.g., a layout referencing a string), you MAY include them in filesToWrite too.
                - Keep any additional files minimal and relevant. Avoid unrelated or large refactors.
                - Do NOT include prose or extra keys.
            """.trimIndent()
        }

        fun applyAndAccount(
            requested: List<String>,
            responseText: String
        ): Pair<Boolean, Set<String>> {
            val result = geminiHelper.parseMinimalFilesAndUnchanged(responseText) ?: return false to emptySet()

            // Accept in-scope writes
            val inScopeWrites = result.filesToWrite.filterKeys { it in requested }
            if (inScopeWrites.isNotEmpty()) aggregatedFiles.putAll(inScopeWrites)

            // Accept limited out-of-scope writes (to make changes effective)
            val outOfScopeWrites = result.filesToWrite.filterKeys { it !in requested }
            if (outOfScopeWrites.isNotEmpty()) {
                val remainingAllowance = MAX_EXTRA_WRITES_ACCEPTED - extraWritesAcceptedCount
                if (remainingAllowance > 0) {
                    val accepted = outOfScopeWrites.entries.take(remainingAllowance)
                    accepted.forEach { (k, v) -> aggregatedFiles[k] = v }
                    extraWritesAcceptedCount += accepted.size
                    val dropped = outOfScopeWrites.size - accepted.size
                    if (accepted.isNotEmpty()) {
                        logViaBridge("ℹ️ Accepted ${accepted.size} additional out-of-batch file(s) to complete the change.\n")
                    }
                    if (dropped > 0) {
                        logViaBridge("⚠️ Dropped $dropped extra out-of-batch file(s) due to allowance limit ($MAX_EXTRA_WRITES_ACCEPTED).\n")
                    }
                } else {
                    logViaBridge("⚠️ Skipped ${outOfScopeWrites.size} extra out-of-batch file(s) (allowance exhausted).\n")
                }
            }

            val accountedSet = (result.filesToWrite.keys + result.unchanged.toSet()).toSet()
            val accountedInRequested = requested.filter { it in accountedSet }.toSet()
            val fullSuccess = accountedInRequested.size == requested.size
            return fullSuccess to accountedInRequested
        }

        fun rebuildPending() {
            pendingBatches = makePendingForMode(remainingPaths, currentMode, fileContentsMap, maxCharsPerBatch)
        }

        fun processNextBatch() {
            if (remainingPaths.isEmpty()) {
                val modifications = FileModifications(aggregatedFiles, emptyList(), null)
                if (aggregatedFiles.isEmpty()) {
                    logViaBridge("No files were generated/changed in batched flow.\n")
                } else {
                    logViaBridge("Batched generation produced ${aggregatedFiles.size} file(s).\n")
                }
                applyCodeChangesAndOrGetSummary(modifications)
                return
            }

            if (pendingBatches.isEmpty()) {
                rebuildPending()
                if (pendingBatches.isEmpty()) {
                    // Nothing more to schedule
                    val modifications = FileModifications(aggregatedFiles, emptyList(), null)
                    applyCodeChangesAndOrGetSummary(modifications)
                    return
                }
            }

            val paths = pendingBatches.removeFirst()
            conversation.addUserMessage(promptForBatch(paths))
            logViaBridge("Generating batch (${paths.size} file(s)) [mode=$currentMode, remain=${remainingPaths.size}]...\n")

            geminiHelper.sendApiRequest(
                contents = conversation.getContentsForApi(),
                callback = { response ->
                    try {
                        val responseText = geminiHelper.extractTextFromApiResponse(response)
                        if (responseText.isBlank()) {
                            // Treat as failure of current mode
                            if (currentMode == BatchMode.ALL) {
                                consecutiveAllFailures++
                                if (consecutiveAllFailures >= MAX_CONSECUTIVE_ALL_FAILURES) {
                                    singlesAllowed = false // don't go to singles anymore
                                }
                                // drop to 10
                                currentMode = BatchMode.TEN
                                rebuildPending()
                            } else if (currentMode == BatchMode.TEN) {
                                currentMode = BatchMode.FIVE
                                rebuildPending()
                            } else if (currentMode == BatchMode.FIVE) {
                                currentMode = if (singlesAllowed) BatchMode.ONE else BatchMode.ALL
                                rebuildPending()
                            } else {
                                // ONE failed; try ALL again for remaining
                                currentMode = BatchMode.ALL
                                rebuildPending()
                            }
                            processNextBatch()
                            return@sendApiRequest
                        }

                        val (fullSuccess, accounted) = applyAndAccount(paths, responseText)

                        // Mark accounted as done
                        if (accounted.isNotEmpty()) remainingPaths.removeAll(accounted)

                        // If batch fully accounted, success
                        if (fullSuccess) {
                            // Success resets ALL failure counter
                            if (currentMode == BatchMode.ALL) {
                                consecutiveAllFailures = 0
                            } else {
                                // Climb back to ALL after any successful smaller batch
                                currentMode = BatchMode.ALL
                                rebuildPending()
                            }
                        } else {
                            // Partial or no success for this batch -> adjust mode
                            if (currentMode == BatchMode.ALL) {
                                consecutiveAllFailures++
                                if (consecutiveAllFailures >= MAX_CONSECUTIVE_ALL_FAILURES) {
                                    singlesAllowed = false
                                }
                                currentMode = BatchMode.TEN
                                rebuildPending()
                            } else if (currentMode == BatchMode.TEN) {
                                currentMode = BatchMode.FIVE
                                rebuildPending()
                            } else if (currentMode == BatchMode.FIVE) {
                                currentMode = if (singlesAllowed) BatchMode.ONE else BatchMode.ALL
                                rebuildPending()
                            } else {
                                // ONE failed/partial -> try ALL again for remaining
                                currentMode = BatchMode.ALL
                                rebuildPending()
                            }
                        }

                        processNextBatch()
                    } catch (e: Exception) {
                        encounteredError = true
                        logViaBridge("⚠️ Error processing batch response: ${e.message}. Switching mode and continuing.\n")
                        // Adjust mode on exception similar to blank case
                        if (currentMode == BatchMode.ALL) {
                            consecutiveAllFailures++
                            if (consecutiveAllFailures >= MAX_CONSECUTIVE_ALL_FAILURES) {
                                singlesAllowed = false
                            }
                            currentMode = BatchMode.TEN
                        } else if (currentMode == BatchMode.TEN) {
                            currentMode = BatchMode.FIVE
                        } else if (currentMode == BatchMode.FIVE) {
                            currentMode = if (singlesAllowed) BatchMode.ONE else BatchMode.ALL
                        } else {
                            currentMode = BatchMode.ALL
                        }
                        rebuildPending()
                        processNextBatch()
                    }
                },
                responseSchemaJson = geminiHelper.getMinimalFilesWithUnchangedSchema(),
                responseMimeTypeOverride = "application/json",
                modelIdentifierOverride = overrideModel
            )
        }

        processNextBatch()
    }

    private fun applyCodeChangesAndOrGetSummary(modifications: FileModifications) {
        val projectDir = bridge.currentProjectDirBridge ?: run {
            encounteredError = true
            bridge.handleErrorBridge("Project directory is null before applying changes.", null)
            AiForegroundService.stop(bridge.getContextBridge())
            return
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
                    var changesApplied = false
                    if (writeErrorCount > 0 || deleteErrorCount > 0) {
                        summary += "⚠️ Some file operations failed. Writes (Success: $writeSuccessCount, Error: $writeErrorCount), Deletes (Success: $deleteSuccessCount, Error: $deleteErrorCount).\n"
                    }
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
                    Log.i(TAG, "Raw summary generation response from AI: ${summaryResponseJsonText.take(512)}")
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

    private fun finishAndMaybeBuild(finalSummary: String?) {
        bridge.displayAiConclusionBridge(finalSummary)
        bridge.updateStateBridge(AiWorkflowState.READY_FOR_ACTION)

        val projectDir = bridge.currentProjectDirBridge
        val okToAutoBuild = !encounteredError && anyChangesApplied && projectDir != null

        AiForegroundService.stop(bridge.getContextBridge())

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