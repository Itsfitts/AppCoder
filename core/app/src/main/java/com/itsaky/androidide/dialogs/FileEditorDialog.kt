package com.itsaky.androidide.dialogs

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.R // Ensure this import is correct for your project structure
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.lookup.Lookup // Assuming this is correctly available
import com.itsaky.androidide.models.NewProjectDetails // Assuming this is correctly available
import com.itsaky.androidide.projects.builder.BuildService // Assuming this is correctly available
import com.itsaky.androidide.services.builder.GradleBuildService // Assuming this is correctly available
import com.itsaky.androidide.templates.BooleanParameter
import com.itsaky.androidide.templates.EnumParameter
import com.itsaky.androidide.templates.Language
import com.itsaky.androidide.templates.ProjectTemplateRecipeResult
import com.itsaky.androidide.templates.Sdk
import com.itsaky.androidide.templates.StringParameter
import com.itsaky.androidide.templates.impl.basicActivity.basicActivityProject
import com.itsaky.androidide.utils.TemplateRecipeExecutor
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.util.*
import kotlin.concurrent.thread

// Assumes GeminiHelper.kt defines:
// package com.itsaky.androidide.dialogs
// const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash-preview-05-20" // Or your actual default
// class GeminiHelper(...) { ... }

// Assumes GeminiConversation.kt defines:
// package com.itsaky.androidide.dialogs
// class GeminiConversation { ... }

// Assumes ProjectFileUtils.kt defines:
// package com.itsaky.androidide.dialogs (or appropriate package)
// object ProjectFileUtils { ... }


class FileEditorDialog : DialogFragment() {

    private val log = LoggerFactory.getLogger(javaClass.simpleName)
    private lateinit var appNameInput: TextInputEditText
    private lateinit var appDescriptionInput: TextInputEditText
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var modelSpinner: Spinner
    private lateinit var customModelInputLayout: TextInputLayout
    private lateinit var customModelInput: TextInputEditText
    private lateinit var generateButton: Button
    private lateinit var statusText: TextView
    private lateinit var logOutput: TextInputEditText
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var actionButtonsLayout: LinearLayout
    private lateinit var continueButton: Button
    private lateinit var modifyFurtherButton: Button

    private lateinit var projectsBaseDir: File
    private var currentProjectDir: File? = null

    private lateinit var geminiHelper: GeminiHelper
    private val conversation = GeminiConversation() // Correctly instantiate top-level class
    private val selectedFilesForModification = mutableListOf<String>()

    private lateinit var prefs: SharedPreferences

    internal enum class WorkflowState {
        IDLE, CREATING_PROJECT, SELECTING_FILES,
        GENERATING_CODE, READY_FOR_ACTION, ERROR
    }
    internal var currentState = WorkflowState.IDLE

    internal val buildService: GradleBuildService? by lazy {
        Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) as? GradleBuildService
    }

    // Define your models. Ensure DEFAULT_GEMINI_MODEL's ID is first if you want it as the "visual default"
    // if no other preference is saved. The actual default is set from GeminiHelper.
    private val predefinedModels by lazy { // Use lazy to ensure DEFAULT_GEMINI_MODEL is initialized
        listOf(
            DEFAULT_GEMINI_MODEL to "Gemini 2.5 Flash (Default - Preview 05-20)", // Display name for your default
            "gemini-1.5-flash-latest" to "Gemini 1.5 Flash (Stable)",
            "gemini-1.5-pro-latest" to "Gemini 1.5 Pro (Stable)",
            "gemini-2.5-pro-preview-05-06" to "Gemini 2.5 Pro (Preview 05-06)"
        ).distinctBy { it.first } // Handles if DEFAULT_GEMINI_MODEL is accidentally listed twice
    }


    private val customModelOptionText = "Enter Custom Model ID..."
    private var currentSelectedModelIdInternal: String = DEFAULT_GEMINI_MODEL // Initialize with the actual default

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val basePath = requireArguments().getString(ARG_PROJECTS_BASE_DIR)
        if (basePath == null) {
            Log.e(TAG, "Projects base directory argument is missing!")
            Toast.makeText(requireContext(), "Error: Projects base directory not specified.", Toast.LENGTH_LONG).show()
            dismiss(); return
        }
        projectsBaseDir = File(basePath)
        if (!projectsBaseDir.isDirectory) {
            Log.e(TAG, "Projects base directory does not exist or is not a directory: $basePath")
            Toast.makeText(requireContext(), "Error: Invalid projects base directory.", Toast.LENGTH_LONG).show()
            dismiss(); return
        }

        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        geminiHelper = GeminiHelper(
            apiKeyProvider = { apiKeyInput.text.toString().trim() },
            errorHandler = ::handleError,
            uiCallback = { block -> activity?.runOnUiThread(block) }
        )

        // Load saved model or use the hardcoded default from GeminiHelper's file.
        // This ensures GeminiHelper starts with the correct model.
        val savedModel = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL
        geminiHelper.setModel(savedModel) // Set it in the helper
        currentSelectedModelIdInternal = savedModel // Sync local tracking variable
        Log.d(TAG, "onCreate: Initial model set in GeminiHelper to: $savedModel")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.layout_file_editor_dialog, null)

        // Initialize all views
        appNameInput = view.findViewById(R.id.app_name_input)
        appDescriptionInput = view.findViewById(R.id.app_description_input)
        apiKeyInput = view.findViewById(R.id.api_key_input)
        modelSpinner = view.findViewById(R.id.model_spinner)
        customModelInputLayout = view.findViewById(R.id.custom_model_input_layout)
        customModelInput = view.findViewById(R.id.custom_model_input)
        generateButton = view.findViewById(R.id.generate_app_button)
        statusText = view.findViewById(R.id.status_text)
        logOutput = view.findViewById(R.id.log_output)
        loadingIndicator = view.findViewById(R.id.loading_indicator)
        actionButtonsLayout = view.findViewById(R.id.action_buttons_layout)
        continueButton = view.findViewById(R.id.continue_button)
        modifyFurtherButton = view.findViewById(R.id.modify_further_button)

        logOutput.isEnabled = false
        logOutput.movementMethod = ScrollingMovementMethod()

        loadApiKey()
        setupModelSpinner() // This will load saved model and set spinner

        generateButton.setOnClickListener {
            if (currentState == WorkflowState.IDLE || currentState == WorkflowState.ERROR || currentState == WorkflowState.READY_FOR_ACTION) {
                saveApiKey()
                saveAndSetSelectedModel() // Ensures the model from UI is set in GeminiHelper and saved
                startProjectGenerationWorkflow()
            }
        }

        continueButton.setOnClickListener {
            if (currentState == WorkflowState.READY_FOR_ACTION && currentProjectDir != null) {
                val mainActivity = activity as? MainActivity
                if (mainActivity != null) {
                    appendToLog("Opening project in editor...\n")
                    mainActivity.openProject(currentProjectDir!!)
                    dismiss()
                } else {
                    handleError("Could not get MainActivity reference to open project.")
                }
            } else {
                appendToLog("Project not ready or directory not set. Cannot continue.\n")
            }
        }
        modifyFurtherButton.setOnClickListener { dismiss() }

        builder.setView(view)
            .setTitle("AI App Generator")
            .setPositiveButton("Close") { _, _ -> dismiss() }

        updateUiForState()
        return builder.create()
    }

    private fun loadApiKey() {
        val savedKey = prefs.getString(KEY_API_KEY, "") ?: ""
        apiKeyInput.setText(savedKey)
    }

    private fun saveApiKey() {
        val currentKey = apiKeyInput.text.toString().trim()
        prefs.edit().putString(KEY_API_KEY, currentKey).apply()
    }

    private fun setupModelSpinner() {
        val displayNames = predefinedModels.map { it.second }.toMutableList()
        displayNames.add(customModelOptionText)

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, displayNames).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        modelSpinner.adapter = adapter

        // currentSelectedModelIdInternal is already initialized in onCreate with saved or default value
        val initialModelId = currentSelectedModelIdInternal
        val predefinedModelIndex = predefinedModels.indexOfFirst { it.first == initialModelId }

        Log.d(TAG, "setupModelSpinner: Initial model to select: $initialModelId")

        if (predefinedModelIndex != -1) {
            modelSpinner.setSelection(predefinedModelIndex, false) // false to prevent immediate onItemSelected call if view isn't fully ready
            customModelInputLayout.visibility = View.GONE
            customModelInput.setText("" as CharSequence)
            Log.d(TAG, "setupModelSpinner: Selected predefined model: ${predefinedModels[predefinedModelIndex].second}")
        } else {
            // This means initialModelId is not in predefinedModels, so it's either custom or an old/invalid one
            // If it's a non-empty custom one, select "Enter Custom..." and fill the field
            if (initialModelId.isNotEmpty() && initialModelId != DEFAULT_GEMINI_MODEL) { // Check against DEFAULT_GEMINI_MODEL explicitly
                modelSpinner.setSelection(displayNames.indexOf(customModelOptionText), false)
                customModelInputLayout.visibility = View.VISIBLE
                customModelInput.setText(initialModelId)
                Log.d(TAG, "setupModelSpinner: Selected custom model option, input: $initialModelId")
            } else {
                // Fallback if initialModelId was somehow blank or is the default (which should be in predefined)
                val defaultIndex = predefinedModels.indexOfFirst { it.first == DEFAULT_GEMINI_MODEL }
                modelSpinner.setSelection(if (defaultIndex != -1) defaultIndex else 0, false)
                customModelInputLayout.visibility = View.GONE
                // Ensure currentSelectedModelIdInternal reflects the actual default if we fell back here
                currentSelectedModelIdInternal = if (defaultIndex != -1) predefinedModels[defaultIndex].first else predefinedModels.firstOrNull()?.first ?: DEFAULT_GEMINI_MODEL
                Log.d(TAG, "setupModelSpinner: Fallback or default selected: $currentSelectedModelIdInternal")
            }
        }
        // Ensure GeminiHelper is also synced with this initial state
        geminiHelper.setModel(currentSelectedModelIdInternal)


        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedDisplayName = displayNames.getOrElse(position) { "" }
                Log.d(TAG, "Spinner onItemSelected: $selectedDisplayName at pos $position")
                if (selectedDisplayName == customModelOptionText) {
                    customModelInputLayout.visibility = View.VISIBLE
                } else {
                    customModelInputLayout.visibility = View.GONE
                    customModelInput.setText("" as CharSequence)
                    if (position < predefinedModels.size) { // Ensure it's a predefined model
                        val newModelId = predefinedModels[position].first
                        // Only update and save if it actually changed
                        if (newModelId != currentSelectedModelIdInternal) {
                            currentSelectedModelIdInternal = newModelId
                            geminiHelper.setModel(currentSelectedModelIdInternal)
                            prefs.edit().putString(KEY_GEMINI_MODEL, currentSelectedModelIdInternal).apply()
                            Log.d(TAG, "Spinner selected predefined: $currentSelectedModelIdInternal. Saved.")
                        }
                    }
                }
                updateUiForState() // To correctly enable/disable customModelInput
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun saveAndSetSelectedModel() {
        val selectedSpinnerPosition = modelSpinner.selectedItemPosition
        val displayNames = predefinedModels.map { it.second }.toMutableList() // Rebuild for safety
        displayNames.add(customModelOptionText)
        val selectedDisplayName = displayNames.getOrElse(selectedSpinnerPosition) {
            // Fallback if position is out of bounds, though should not happen
            if (predefinedModels.isNotEmpty()) predefinedModels.first().second else ""
        }

        val finalModelIdToUse: String
        if (selectedDisplayName == customModelOptionText) {
            val customEnteredId = customModelInput.text.toString().trim()
            if (customEnteredId.isBlank()) {
                Toast.makeText(requireContext(), "Custom model ID is blank. Using default.", Toast.LENGTH_SHORT).show()
                finalModelIdToUse = DEFAULT_GEMINI_MODEL // Fallback to the true default
                // Visually reset spinner to the default
                val defaultIndexInPredefined = predefinedModels.indexOfFirst { it.first == DEFAULT_GEMINI_MODEL }
                if (defaultIndexInPredefined != -1) {
                    modelSpinner.setSelection(defaultIndexInPredefined)
                } else {
                    modelSpinner.setSelection(0) // Fallback to first item
                }
                customModelInputLayout.visibility = View.GONE
            } else {
                finalModelIdToUse = customEnteredId
            }
        } else {
            finalModelIdToUse = if (selectedSpinnerPosition < predefinedModels.size) {
                predefinedModels[selectedSpinnerPosition].first
            } else {
                Log.w(TAG, "Spinner position out of bounds for predefined models, using default.")
                DEFAULT_GEMINI_MODEL // Fallback
            }
        }

        currentSelectedModelIdInternal = finalModelIdToUse // Update local tracking
        geminiHelper.setModel(finalModelIdToUse) // Set it in the helper
        prefs.edit().putString(KEY_GEMINI_MODEL, finalModelIdToUse).apply() // Save it
        Log.i(TAG, "Final model saved and set for generation: $finalModelIdToUse")
    }


    private fun startProjectGenerationWorkflow() {
        val appName = appNameInput.text.toString().trim()
        val appDescription = appDescriptionInput.text.toString().trim()
        val apiKey = apiKeyInput.text.toString().trim()

        if (apiKey.isBlank()) {
            handleError("Gemini API Key cannot be empty.")
            activity?.runOnUiThread { apiKeyInput.error = "API Key required" }
            return
        }
        if (appName.isEmpty()) { appNameInput.error = "App name is required"; return }
        if (appDescription.isEmpty()) { appDescriptionInput.error = "App description is required"; return }

        currentProjectDir = File(projectsBaseDir, appName)
        if (currentProjectDir!!.exists()) {
            appNameInput.error = "Project directory already exists. Choose a different app name."
            return
        }
        appNameInput.error = null
        appDescriptionInput.error = null
        apiKeyInput.error = null

        logOutput.text?.clear()
        conversation.clear()
        selectedFilesForModification.clear()
        setState(WorkflowState.CREATING_PROJECT)
        appendToLog("Starting workflow for: $appName\nUsing Model: ${geminiHelper.currentModelIdentifier}\nDescription: $appDescription\n")

        thread {
            try {
                val createdProjectDir = createProjectFromTemplate(appName)
                currentProjectDir = createdProjectDir
                startFileSelectionForModification(appName, appDescription, createdProjectDir)
            } catch (e: Exception) {
                Log.e(TAG, "Error during project creation/initial configuration", e)
                handleError("Workflow failed during project creation: ${e.message}")
            }
        }
    }

    private fun createProjectFromTemplate(appName: String): File {
        val packageName = createPackageName(appName)
        val projectDir = File(projectsBaseDir, appName)
        appendToLog("Step 1: Creating project '$appName' using Basic Activity template...\n")
        appendToLog("Package Name: $packageName\nSave Location: ${projectDir.absolutePath}\nLanguage: Kotlin, Min SDK: 21\n")
        val projectDetails = NewProjectDetails().apply {
            this.name = appName; this.packageName = packageName; this.minSdk = 21
            this.targetSdk = 34; this.language = "kotlin"; this.savePath = projectsBaseDir.absolutePath
        }
        val template = basicActivityProject()
        template.setupParametersFromDetails(projectDetails)
        val executor = TemplateRecipeExecutor()
        val result = template.recipe.execute(executor)
        if (result is ProjectTemplateRecipeResult) {
            appendToLog("✅ Project template created successfully at ${result.data.projectDir.absolutePath}\n")
            return result.data.projectDir
        } else {
            throw IOException("Template execution failed. Result: $result")
        }
    }

    private fun startFileSelectionForModification(appName: String, appDescription: String, projectDir: File) {
        setState(WorkflowState.SELECTING_FILES)
        appendToLog("Step 2: Identifying files to modify based on description...\n")
        val files = ProjectFileUtils.scanProjectFiles(projectDir) // Assuming ProjectFileUtils is available
        activity?.runOnUiThread {
            if (files.isEmpty()) {
                handleError("No code files found in the newly created project. Cannot proceed with AI modification.")
                return@runOnUiThread
            }
            appendToLog("Found ${files.size} potentially relevant files in the project.\n")
            sendFileSelectionPrompt(appName, appDescription, files, projectDir)
        }
    }

    private fun sendFileSelectionPrompt(appName: String, appDescription: String, files: List<String>, projectDir: File) {
        val fileList = files.joinToString("\n") { "- $it" }
        val prompt = """
            I have just created a basic Android app called "$appName".
            The main goal for this app is: "$appDescription"

            Here is a list of potentially relevant files from the new project template:
            $fileList

            Based ONLY on the app's main goal and the file list, which of these files would MOST LIKELY need to be CREATED or significantly MODIFIED?
            Focus on the core essentials (e.g., main Activities, primary Layout XMLs, key ViewModel/Logic classes if implied by the description, and build.gradle.kts files if new dependencies are clearly needed for the core goal).
            Do not select files that are unlikely to change for a basic implementation of the goal.

            Respond ONLY with a JSON array of the relative file paths.
            Example: ["app/src/main/java/com/example/myapp/MainActivity.kt", "app/src/main/res/layout/activity_main.xml"]
            Provide no explanation, just the JSON array.
        """.trimIndent()
        conversation.addUserMessage(prompt)
        appendToLog("Asking AI to select relevant files...\n")
        geminiHelper.sendGeminiRequest(conversation.getContents(),
            callback = { response ->
                try {
                    val responseText = geminiHelper.extractTextFromGeminiResponse(response)
                    appendToLog("AI file selection response received.\n")
                    val jsonArrayText = geminiHelper.extractJsonArrayFromText(responseText)
                    val jsonArray = JSONArray(jsonArrayText)
                    selectedFilesForModification.clear()
                    for (i in 0 until jsonArray.length()) {
                        selectedFilesForModification.add(jsonArray.getString(i))
                    }
                    conversation.addModelMessage(responseText)
                    if (selectedFilesForModification.isEmpty()) {
                        appendToLog("⚠️ AI did not select any files for modification. Project will remain as template.\n")
                        setState(WorkflowState.READY_FOR_ACTION)
                    } else {
                        appendToLog("Selected ${selectedFilesForModification.size} files for modification:\n${selectedFilesForModification.joinToString("") { "  - $it\n" }}")
                        loadSelectedFileContentsAndGenerate(appName, appDescription, projectDir)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse file selection response.", e) // Removed response from log for brevity
                    handleError("Failed to parse AI's file selection: ${e.message}. See device log for details.")
                }
            }
        )
    }

    private fun loadSelectedFileContentsAndGenerate(appName: String, appDescription: String, projectDir: File) {
        setState(WorkflowState.GENERATING_CODE)
        appendToLog("Step 3: Loading selected files and preparing for code generation...\n")
        val fileContents = mutableMapOf<String, String>()
        val missingFiles = mutableListOf<String>()

        for (filePath in selectedFilesForModification) {
            val file = File(projectDir, filePath)
            if (!file.exists() || !file.isFile) {
                appendToLog("Note: File '$filePath' does not exist. Will request AI to create it.\n")
                missingFiles.add(filePath)
                fileContents[filePath] = "// File: $filePath (This file does not exist yet. Please provide its complete content based on the app description.)"
            } else {
                try {
                    val content = FileReader(file).use { it.readText() }
                    fileContents[filePath] = content
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading file $filePath", e)
                    appendToLog("⚠️ Error reading file $filePath: ${e.message}. Will request AI to recreate or modify.\n")
                    missingFiles.add(filePath)
                    fileContents[filePath] = "// File: $filePath (Error reading existing content. Please provide its complete content based on the app description.)"
                }
            }
        }

        if (fileContents.isEmpty() && missingFiles.isEmpty() && selectedFilesForModification.isNotEmpty()) {
            handleError("Could not read or identify any of the selected files for modification.")
            return
        }
        appendToLog("Loaded/identified ${fileContents.size} files for AI processing.\n")
        sendCodeGenerationPrompt(appName, appDescription, fileContents, missingFiles, projectDir)
    }


    private fun sendCodeGenerationPrompt(
        appName: String,
        appDescription: String,
        fileContents: Map<String, String>,
        missingFiles: List<String>,
        projectDir: File
    ) {
        val filesContentText = buildString {
            fileContents.forEach { (path, content) ->
                append("FILE: $path\n```\n$content\n```\n\n")
            }
        }

        val creationNote = if (missingFiles.isNotEmpty()) {
            "The following files were identified as needing creation or were unreadable; please provide their full content:\n" +
                    missingFiles.joinToString("\n") { "- $it" } + "\n\n"
        } else ""

        val localSanitizedAppName = appName.filter { it.isLetterOrDigit() }.lowercase(Locale.getDefault()).ifEmpty { "myapp" }

        val prompt = """
            You are an expert Android App Developer. Your task is to modify or create files for an Android app named "$appName".
            The primary goal of this app is: "$appDescription"

            $creationNote Important: For any file you provide content for, ensure it is the *complete and valid* content for that file (e.g., full Kotlin class, complete XML layout, entire Gradle script section).

            Current file contents (or placeholders for new/unreadable files):
            $filesContentText

            Instructions:
            1. Review the app description and the provided file contents/placeholders.
            2. For each file listed (whether existing or to be created), provide its FULL, UPDATED, and VALID content to achieve the app's goal.
            3. If new dependencies are clearly implied by the app description (e.g., "needs to show images from the internet" might imply Coil or Glide), add them to the appropriate `build.gradle.kts` file (usually `app/build.gradle.kts`). Ensure to add them in the `dependencies { ... }` block.
            4. Ensure all Kotlin code is idiomatic and XML layouts are well-formed.
            5. Pay attention to package names if creating new Kotlin/Java files (e.g., `com.example.$localSanitizedAppName`).
            6. Only output content for files that actually need changes or creation. If a file from the input list is fine as-is or not relevant to the core goal, do not include it in your response.

            Format your response STRICTLY as follows, with each modified/created file block:
            FILE: path/to/file.ext
            ```[optional language hint like kotlin, xml, groovy]
            // Complete file content with all necessary modifications or new content
            ```
            (Repeat for each file that needs to be changed or created)
        """.trimIndent()

        conversation.addUserMessage(prompt)
        appendToLog("Sending file contents to AI for code generation...\n")
        geminiHelper.sendGeminiRequest(conversation.getContents(),
            callback = { response ->
                try {
                    val responseText = geminiHelper.extractTextFromGeminiResponse(response)
                    conversation.addModelMessage(responseText)
                    appendToLog("AI responded with code modifications.\n")
                    processCodeChanges(responseText, projectDir)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed during code generation response.", e) // Removed response from log
                    handleError("Failed during AI code generation response: ${e.message}. See device log for details.")
                }
            }
        )
    }

    private fun processCodeChanges(responseText: String, projectDir: File) {
        appendToLog("Step 4: Applying code changes...\n")
        val fileChanges = geminiHelper.parseFileChanges(responseText, ::appendToLog)

        if (fileChanges.isEmpty()) {
            appendToLog("⚠️ AI did not provide any recognizable file changes in the correct format. Project remains largely as generated by template.\n")
            setState(WorkflowState.READY_FOR_ACTION)
            return
        }

        ProjectFileUtils.processFileChanges(projectDir, fileChanges, ::appendToLog) { successCount, errorCount ->
            if (errorCount > 0) {
                appendToLog("⚠️ Some files failed to update during the process.\n")
            }
            if (successCount > 0) {
                appendToLog("✅ Successfully applied $successCount file changes.\n")
            }
            setState(WorkflowState.READY_FOR_ACTION)
        }
    }


    internal fun setState(newState: WorkflowState) {
        if (currentState == newState) return
        currentState = newState
        Log.d(TAG, "State changed to: $newState")
        activity?.runOnUiThread { updateUiForState() }
    }

    private fun updateUiForState() {
        val isLoading = currentState in listOf(
            WorkflowState.CREATING_PROJECT,
            WorkflowState.SELECTING_FILES,
            WorkflowState.GENERATING_CODE
        )
        loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        generateButton.isEnabled = currentState in listOf(WorkflowState.IDLE, WorkflowState.READY_FOR_ACTION, WorkflowState.ERROR)
        actionButtonsLayout.visibility = if (currentState == WorkflowState.READY_FOR_ACTION) View.VISIBLE else View.GONE
        continueButton.isEnabled = currentState == WorkflowState.READY_FOR_ACTION
        continueButton.text = getString(R.string.action_continue_to_build)
        modifyFurtherButton.isEnabled = currentState == WorkflowState.READY_FOR_ACTION

        val editableState = currentState in listOf(WorkflowState.IDLE, WorkflowState.READY_FOR_ACTION, WorkflowState.ERROR)
        appNameInput.isEnabled = editableState
        appDescriptionInput.isEnabled = editableState
        apiKeyInput.isEnabled = editableState
        modelSpinner.isEnabled = editableState

        val isCustomModelSelected = if (modelSpinner.adapter != null && modelSpinner.adapter.count > 0) {
            modelSpinner.selectedItemPosition == modelSpinner.adapter.count - 1 // Last item is "custom"
        } else {
            false // Adapter not ready, assume not custom
        }
        customModelInput.isEnabled = isCustomModelSelected && editableState
        customModelInputLayout.isEnabled = customModelInput.isEnabled // Match the TextInputEditText's enabled state


        statusText.text = when (currentState) {
            WorkflowState.IDLE -> "Ready to generate"
            WorkflowState.CREATING_PROJECT -> "Creating base project..."
            WorkflowState.SELECTING_FILES -> "AI is selecting files..."
            WorkflowState.GENERATING_CODE -> "AI is generating code..."
            WorkflowState.READY_FOR_ACTION -> "Project modified. Ready to open or regenerate."
            WorkflowState.ERROR -> "Error occurred (see log above)"
        }
        generateButton.text = when (currentState) {
            WorkflowState.READY_FOR_ACTION -> "Regenerate App"
            WorkflowState.ERROR -> "Retry Generation"
            else -> "Generate & Modify App"
        }
    }

    internal fun appendToLog(text: String) {
        activity?.runOnUiThread {
            logOutput.append(text)
            try {
                val layout = logOutput.layout
                if (layout != null) {
                    val scrollAmount = layout.getLineTop(logOutput.lineCount) - logOutput.height
                    logOutput.scrollTo(0, if (scrollAmount > 0) scrollAmount else 0)
                } else {
                    logOutput.scrollTo(0, 0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error auto-scrolling log output", e)
            }
        }
    }

    internal fun handleError(message: String) {
        Log.e(TAG, "Error: $message")
        activity?.runOnUiThread {
            setState(WorkflowState.ERROR)
            appendToLog("❌ ERROR: $message\n")
        }
    }

    private fun createPackageName(appName: String): String {
        val sanitizedName = appName.filter { it.isLetterOrDigit() }.lowercase(Locale.getDefault())
        return "com.example.${sanitizedName.ifEmpty { "myapp" }}"
    }

    private fun com.itsaky.androidide.templates.Template<*>.setupParametersFromDetails(details: NewProjectDetails) {
        val iterator = parameters.iterator()
        try {
            log.debug("Setting template parameter: name = ${details.name}")
            (iterator.next() as? StringParameter)?.setValue(details.name)
            log.debug("Setting template parameter: packageName = ${details.packageName}")
            (iterator.next() as? StringParameter)?.setValue(details.packageName)
            log.debug("Setting template parameter: savePath = ${details.savePath}")
            (iterator.next() as? StringParameter)?.setValue(details.savePath)
            val langEnum = if (details.language == "kotlin") Language.Kotlin else Language.Java
            log.debug("Setting template parameter: language = $langEnum")
            (iterator.next() as? EnumParameter<Language>)?.setValue(langEnum)
            val sdkEnum = Sdk.values().find { it.api == details.minSdk } ?: Sdk.Lollipop
            log.debug("Setting template parameter: minSdk = $sdkEnum (API ${details.minSdk})")
            (iterator.next() as? EnumParameter<Sdk>)?.setValue(sdkEnum)
            val useKtsValue = (langEnum == Language.Kotlin)
            log.debug("Setting template parameter: useKts = $useKtsValue")
            if (iterator.hasNext()) {
                (iterator.next() as? BooleanParameter)?.setValue(useKtsValue)
            } else {
                log.warn("Template does not seem to have a 6th (useKts) parameter.")
            }
        } catch (e: NoSuchElementException) {
            log.error("Error setting template parameters: Not enough parameters in the template.", e)
            handleError("Internal error: Template parameter mismatch.")
        } catch (e: Exception) {
            log.error("Error setting template parameters", e)
            handleError("Internal error setting template parameters: ${e.message}")
        }
    }

    companion object {
        const val TAG = "FileEditorDialog"
        private const val ARG_PROJECTS_BASE_DIR = "projects_base_dir"
        private const val PREFS_NAME = "GeminiPrefs_AppCoder"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_selected_model"

        fun newInstance(projectsBaseDir: String): FileEditorDialog {
            return FileEditorDialog().apply { arguments = bundleOf(ARG_PROJECTS_BASE_DIR to projectsBaseDir) }
        }
    }
}