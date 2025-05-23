package com.itsaky.androidide.dialogs

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject // Ensure JSONObject is imported
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.Locale

class GeminiWorkflowCoordinator(
    private val geminiHelper: GeminiHelper,
    private val logAppender: (String) -> Unit,
    private val errorHandler: (String, Exception?) -> Unit,
    private val dialogInterface: FileEditorDialog // To update state
) {
    companion object {
        private const val TAG = "GeminiWorkflow"
    }

    private val conversation = GeminiConversation() // Each workflow gets its own conversation
    private val selectedFilesForModification = mutableListOf<String>()

    fun startModificationFlow(appName: String, appDescription: String, projectDir: File) {
        logAppender("Gemini Workflow: Starting for project '$appName'\n")
        conversation.clear()
        selectedFilesForModification.clear()
        dialogInterface.currentProjectDir = projectDir // Ensure dialog has the correct dir

        dialogInterface.setState(FileEditorDialog.WorkflowState.SELECTING_FILES)
        identifyFilesToModify(appName, appDescription, projectDir)
    }

    private fun identifyFilesToModify(appName: String, appDescription: String, projectDir: File) {
        logAppender("Gemini Workflow Step: Identifying files to modify/create...\n")
        val files = ProjectFileUtils.scanProjectFiles(projectDir)

        dialogInterface.activity?.runOnUiThread {
            if (files.isEmpty() && !dialogInterface.isModifyingExistingProjectInternal) {
                errorHandler("No code files found in the newly created project template. Cannot proceed.", null)
                return@runOnUiThread
            } else if (files.isEmpty() && dialogInterface.isModifyingExistingProjectInternal) {
                logAppender("No existing code files found by scanner in '$appName'. AI will be prompted to create necessary files.\n")
            } else {
                logAppender("Found ${files.size} potentially relevant files in the project.\n")
            }
            sendGeminiFileSelectionPrompt(appName, appDescription, files, projectDir)
        }
    }

    private fun sendGeminiFileSelectionPrompt(appName: String, appDescription: String, files: List<String>, projectDir: File) {
        val fileListText = if (files.isNotEmpty()) {
            files.joinToString("\n") { "- $it" }
        } else {
            "No existing editable files were found in this project. You might need to create all necessary files from scratch."
        }
        val isExisting = dialogInterface.isModifyingExistingProjectInternal
        val promptContext = if (isExisting) "modifying an existing Android app" else "working with a basic Android app template I just created"
        val actionVerb = if (isExisting) "MODIFY or CREATE" else "CREATED or significantly MODIFIED"

        val prompt = """
            I am $promptContext called "$appName".
            The main goal for this app is: "$appDescription"

            Here is a list of potentially relevant files from the project (or a note if empty):
            $fileListText

            Based ONLY on the app's main goal and the file list (if any), which of these files would MOST LIKELY need to be $actionVerb?
            If the file list is empty or the existing files are not relevant to the goal, list the NEW files that would be essential to create to achieve the goal.
            Focus on the core essentials (e.g., main Activities, primary Layout XMLs, key ViewModel/Logic classes if implied by the description, and build.gradle.kts files if new dependencies are clearly needed for the core goal).

            Respond ONLY with a JSON array of the relative file paths.
            Example: ["app/src/main/java/com/example/myapp/MainActivity.kt", "app/src/main/res/layout/activity_main.xml"]
            Provide no explanation, just the JSON array.
        """.trimIndent()

        conversation.addUserMessage(prompt)
        logAppender("Asking AI to select relevant files...\n")

        // *** FIXED CALL ***
        geminiHelper.sendGeminiRequest(
            contents = conversation.getContents(),
            callback = { response -> // Explicitly name the callback parameter
                try {
                    val responseText = geminiHelper.extractTextFromGeminiResponse(response)
                    logAppender("AI file selection response received.\n")
                    val jsonArrayText = geminiHelper.extractJsonArrayFromText(responseText)
                    val jsonArray = JSONArray(jsonArrayText)
                    selectedFilesForModification.clear()
                    for (i in 0 until jsonArray.length()) {
                        selectedFilesForModification.add(jsonArray.getString(i))
                    }
                    conversation.addModelMessage(responseText)

                    if (selectedFilesForModification.isEmpty()) {
                        logAppender("⚠️ AI did not select/suggest any files. Project remains as is.\n")
                        dialogInterface.setState(FileEditorDialog.WorkflowState.READY_FOR_ACTION)
                    } else {
                        logAppender("AI selected/suggested ${selectedFilesForModification.size} files:\n${selectedFilesForModification.joinToString("") { "  - $it\n" }}")
                        loadSelectedFilesAndAskForCode(appName, appDescription, projectDir)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse file selection response from Gemini.", e)
                    errorHandler("Failed to parse AI's file selection: ${e.message}. Check Logcat for Gemini's raw response.", e)
                }
            }
        )
    }

    private fun loadSelectedFilesAndAskForCode(appName: String, appDescription: String, projectDir: File) {
        dialogInterface.setState(FileEditorDialog.WorkflowState.GENERATING_CODE)
        logAppender("Gemini Workflow Step: Loading selected files for code generation...\n")
        val fileContents = mutableMapOf<String, String>()
        val missingOrUnreadableFiles = mutableListOf<String>()

        for (filePath in selectedFilesForModification) {
            val file = File(projectDir, filePath)
            if (!file.exists() || !file.isFile) {
                logAppender("Note: File '$filePath' not found. Will ask AI to create it.\n")
                missingOrUnreadableFiles.add(filePath)
                fileContents[filePath] = "// File: $filePath (This file does not exist yet. Please provide its complete content.)"
            } else {
                try {
                    fileContents[filePath] = FileReader(file).use { it.readText() }
                } catch (e: IOException) {
                    Log.e(TAG, "Error reading file $filePath", e)
                    logAppender("⚠️ Error reading file $filePath: ${e.message}. Will ask AI to provide content.\n")
                    missingOrUnreadableFiles.add(filePath)
                    fileContents[filePath] = "// File: $filePath (Error reading existing content. Please provide its complete content.)"
                }
            }
        }

        if (fileContents.isEmpty() && selectedFilesForModification.isNotEmpty()) {
            logAppender("All selected files are new or were unreadable. AI will generate content from scratch for them.\n")
        } else if (fileContents.isEmpty() && selectedFilesForModification.isEmpty()) {
            errorHandler("No files selected or loaded for code generation.", null)
            return
        }

        logAppender("Loaded/identified ${fileContents.size} files for AI processing.\n")
        sendGeminiCodeGenerationPrompt(appName, appDescription, fileContents, missingOrUnreadableFiles, projectDir)
    }

    private fun sendGeminiCodeGenerationPrompt(
        appName: String,
        appDescription: String,
        fileContentsMap: Map<String, String>,
        missingFilesList: List<String>,
        projectDir: File
    ) {
        val filesContentText = buildString {
            fileContentsMap.forEach { (path, content) ->
                append("FILE: $path\n```\n$content\n```\n\n")
            }
        }
        val creationNote = if (missingFilesList.isNotEmpty()) {
            "The following files were identified as needing creation or were unreadable; please provide their full content:\n" +
                    missingFilesList.joinToString("\n") { "- $it" } + "\n\n"
        } else ""

        val isExisting = dialogInterface.isModifyingExistingProjectInternal
        val packageNameInstruction: String
        if (isExisting) {
            var inferredPackageName = "com.example.unknown"
            val firstExistingKtFile = fileContentsMap.entries
                .firstOrNull { (path, _) -> path.endsWith(".kt") && !missingFilesList.contains(path) }

            if (firstExistingKtFile != null) {
                try {
                    val content = firstExistingKtFile.value
                    val packageRegex = Regex("""^\s*package\s+([a-zA-Z0-9_.]+)""")
                    packageRegex.find(content)?.groupValues?.get(1)?.let { inferredPackageName = it }
                } catch (e: Exception) {
                    Log.w(TAG, "Minor issue inferring package for prompt (existing project). Error: ${e.message}")
                }
            } else {
                Log.w(TAG, "Could not find an existing Kotlin file to infer package name for existing project.")
            }
            packageNameInstruction = "If creating new Kotlin/Java files, try to place them in an appropriate existing package (e.g., derived from '$inferredPackageName') or a new sub-package."
        } else {
            val localSanitizedAppName = appName.filter { it.isLetterOrDigit() }.lowercase(Locale.getDefault()).ifEmpty { "myapp" }
            packageNameInstruction = "Pay attention to package names if creating new Kotlin/Java files (e.g., `com.example.$localSanitizedAppName`)."
        }

        val prompt = """
            You are an expert Android App Developer. Your task is to modify or create files for an Android app named "$appName".
            The primary goal of this app is: "$appDescription"

            $creationNote Important: For any file you provide content for, ensure it is the *complete and valid* content for that file.

            Current file contents (or placeholders for new/unreadable files):
            $filesContentText

            Instructions:
            1. Review the app description and the provided file contents/placeholders.
            2. For each file listed, provide its FULL, UPDATED, and VALID content to achieve the app's goal.
            3. If new dependencies are implied by the app description, add them to the `app/build.gradle.kts` file in the `dependencies { ... }` block.
            4. Ensure Kotlin code is idiomatic and XML layouts are well-formed.
            5. $packageNameInstruction
            6. Only output content for files that actually need changes or creation. If a file from the input list is fine as-is or not relevant to the core goal, do not include it in your response.

            Format your response STRICTLY as follows, with each modified/created file block:
            FILE: path/to/file.ext
            ```[optional language hint like kotlin, xml, groovy]
            // Complete file content
            ```
            (Repeat for each file)
        """.trimIndent()

        conversation.addUserMessage(prompt)
        logAppender("Sending file contents to AI for code generation...\n")

        // *** FIXED CALL ***
        geminiHelper.sendGeminiRequest(
            contents = conversation.getContents(),
            callback = { response -> // Explicitly name the callback parameter
                try {
                    val responseText = geminiHelper.extractTextFromGeminiResponse(response)
                    conversation.addModelMessage(responseText)
                    logAppender("AI responded with code modifications.\n")
                    applyCodeChanges(responseText, projectDir)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed during Gemini code generation response processing.", e)
                    errorHandler("Failed during AI code generation response: ${e.message}. Check Logcat.", e)
                }
            }
        )
    }

    private fun applyCodeChanges(responseText: String, projectDir: File) {
        logAppender("Gemini Workflow Step: Applying code changes...\n")
        val fileChanges = geminiHelper.parseFileChanges(responseText, logAppender)

        if (fileChanges.isEmpty()) {
            logAppender("⚠️ AI did not provide any recognizable file changes in the correct format. Project may not be fully modified as intended.\n")
            dialogInterface.setState(FileEditorDialog.WorkflowState.READY_FOR_ACTION)
            return
        }

        ProjectFileUtils.processFileChanges(projectDir, fileChanges, logAppender) { successCount, errorCount ->
            if (errorCount > 0) {
                logAppender("⚠️ Some files failed to update during the process.\n")
            }
            if (successCount > 0) {
                logAppender("✅ Successfully applied $successCount file changes.\n")
            } else if (errorCount == 0) {
                logAppender("No specific file changes were applied (perhaps AI deemed no changes necessary or format was not matched), but the process completed based on AI response.\n")
            }
            dialogInterface.setState(FileEditorDialog.WorkflowState.READY_FOR_ACTION)
        }
    }

    private val FileEditorDialog.isModifyingExistingProjectInternal: Boolean
        get() = this.isModifyingExistingProject // Assumes isModifyingExistingProject is internal in FileEditorDialog
}