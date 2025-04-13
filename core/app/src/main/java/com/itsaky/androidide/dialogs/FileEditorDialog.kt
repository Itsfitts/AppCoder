package com.itsaky.androidide.dialogs

import android.app.Dialog
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller.SessionCallback
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.activities.editor.BaseEditorActivity
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.models.NewProjectDetails
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.services.ToolingServerNotStartedException
import com.itsaky.androidide.services.builder.GradleBuildService
import com.itsaky.androidide.services.builder.gradleDistributionParams
import com.itsaky.androidide.templates.BooleanParameter
import com.itsaky.androidide.templates.EnumParameter
import com.itsaky.androidide.templates.Language
import com.itsaky.androidide.templates.ProjectTemplateRecipeResult
import com.itsaky.androidide.templates.Sdk
import com.itsaky.androidide.templates.StringParameter
import com.itsaky.androidide.templates.impl.basicActivity.basicActivityProject
import com.itsaky.androidide.tooling.api.messages.InitializeProjectParams
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import com.itsaky.androidide.tooling.api.messages.result.BuildInfo
import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.events.ProgressEvent
import com.itsaky.androidide.utils.ApkInstaller
import com.itsaky.androidide.utils.ILogger
import com.itsaky.androidide.utils.TemplateRecipeExecutor
// Removed OkHttp/JSON imports - handled by GeminiHelper
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class FileEditorDialog : DialogFragment() {

    private val log = LoggerFactory.getLogger(javaClass.simpleName)
    private lateinit var appNameInput: TextInputEditText
    private lateinit var appDescriptionInput: TextInputEditText
    private lateinit var generateButton: Button
    private lateinit var statusText: TextView
    private lateinit var logOutput: TextInputEditText
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var actionButtonsLayout: LinearLayout
    // Renamed installButton to continueButton
    private lateinit var continueButton: Button
    private lateinit var modifyFurtherButton: Button
    private lateinit var projectsBaseDir: File
    private var currentProjectDir: File? = null

    // --- LLM Specific Properties ---
    // Use GeminiHelper instance
    private val geminiHelper: GeminiHelper by lazy {
        GeminiHelper(
            apiKeyProvider = ::getApiKeyFromSecureSource, // Pass function reference
            errorHandler = ::handleError,
            uiCallback = { block -> activity?.runOnUiThread(block) } // Changed: Pass the block directly to runOnUiThread
        )
    }
    private val conversation = GeminiConversation() // Use standalone conversation class
    private val selectedFilesForModification = mutableListOf<String>()
    // --- End LLM Properties ---

    // State Management
    internal enum class WorkflowState {
        IDLE, CREATING_PROJECT, CONFIGURING_PROJECT, SELECTING_FILES,
        GENERATING_CODE, READY_FOR_ACTION, INSTALLING, ERROR // INSTALLING state might become unused
    }
    internal var currentState = WorkflowState.IDLE

    // Build Service instance (still needed for potential future use or if other parts rely on it)
    internal val buildService: GradleBuildService? by lazy {
        Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) as? GradleBuildService
    }

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
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.layout_file_editor_dialog, null)

        appNameInput = view.findViewById(R.id.app_name_input)
        appDescriptionInput = view.findViewById(R.id.app_description_input)
        generateButton = view.findViewById(R.id.generate_app_button)
        statusText = view.findViewById(R.id.status_text)
        logOutput = view.findViewById(R.id.log_output)
        loadingIndicator = view.findViewById(R.id.loading_indicator)
        actionButtonsLayout = view.findViewById(R.id.action_buttons_layout)
        // Update findViewById to use the new ID
        continueButton = view.findViewById(R.id.continue_button)
        modifyFurtherButton = view.findViewById(R.id.modify_further_button)

        logOutput.isEnabled = false
        logOutput.movementMethod = ScrollingMovementMethod()

        generateButton.setOnClickListener {
            if (currentState == WorkflowState.IDLE || currentState == WorkflowState.ERROR || currentState == WorkflowState.READY_FOR_ACTION) {
                startProjectGenerationWorkflow()
            }
        }

        // Modify the click listener for the continueButton
        continueButton.setOnClickListener {
            if (currentState == WorkflowState.READY_FOR_ACTION && currentProjectDir != null) {
                // Get MainActivity instance and open the project
                val mainActivity = activity as? MainActivity
                if (mainActivity != null) {
                    appendToLog("Opening project in editor...\n")
                    mainActivity.openProject(currentProjectDir!!) // Call MainActivity's openProject
                    dismiss() // Close the dialog
                } else {
                    handleError("Could not get MainActivity reference to open project.")
                }
            } else {
                 appendToLog("Project not ready or directory not set. Cannot continue.\n")
            }
        }
        modifyFurtherButton.setOnClickListener { dismiss() } // Keep this as is

        builder.setView(view)
            .setTitle("AI App Generator")
            .setPositiveButton("Close") { _, _ -> dismiss() }

        updateUiForState()
        return builder.create()
    }

    // --- Workflow Orchestration ---
    private fun startProjectGenerationWorkflow() {
        val appName = appNameInput.text.toString().trim()
        val appDescription = appDescriptionInput.text.toString().trim()
        if (appName.isEmpty()) { appNameInput.error = "App name is required"; return }
        if (appDescription.isEmpty()) { appDescriptionInput.error = "App description is required"; return }
        currentProjectDir = File(projectsBaseDir, appName)
        if (currentProjectDir!!.exists()) { appNameInput.error = "Project directory already exists"; return }

        logOutput.text?.clear()
        conversation.clear()
        selectedFilesForModification.clear()
        setState(WorkflowState.CREATING_PROJECT)
        appendToLog("Starting workflow for: $appName\nDescription: $appDescription\n")

        thread {
            try {
                val createdProjectDir = createProjectFromTemplate(appName)
                currentProjectDir = createdProjectDir
                // Removed automatic configuration start
                // appendToLog("Step 2: Project created. Configuration (Gradle Sync) will start when 'Install' is pressed.\n")
                appendToLog("Step 2: Project created. Proceeding to AI modification...\n")
                startFileSelectionForModification(appName, appDescription, createdProjectDir)
            } catch (e: Exception) {
                Log.e(TAG, "Error during project creation/initial configuration", e)
                handleError("Workflow failed: ${e.message}")
            }
        }
    }

    // --- Step 1: Create Project ---
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

    // --- Step 2 & 4: Configuration and Installation Workflow (REMOVED/COMMENTED OUT) ---
    // These methods are no longer triggered by the 'Continue' button.
    // They might be needed if you add separate build/install functionality later.
    /*
    internal fun startConfigurationAndInstallationWorkflow(projectDir: File) {
        val service = buildService
        if (service == null) { handleError("Build Service is not available."); return }
        if (!service.isToolingServerStarted()) { handleError("Tooling server is not running."); return }
        setState(WorkflowState.CONFIGURING_PROJECT)
        appendToLog("Step 4: Starting project configuration (Gradle Sync)...\nThis might take several minutes...\n")
        val params = InitializeProjectParams(projectDir.absolutePath, gradleDistributionParams)
        service.initializeProject(params).whenCompleteAsync { result, error ->
            activity?.runOnUiThread {
                if (error != null) { handleError("Configuration failed: ${error.message ?: "Unknown error"}") }
                else if (result != null && result.isSuccessful) {
                    appendToLog("✅ Project configuration complete.\n")
                    configurationSuccessful(projectDir)
                } else { handleError("Configuration failed. Reason: ${result?.failure?.name ?: "Unknown error"}") }
            }
        }
    }

    internal fun configurationSuccessful(projectDir: File) {
        if (currentState == WorkflowState.CONFIGURING_PROJECT) { startInstallation(projectDir) }
        else { Log.w(TAG, "Configuration finished but state was not CONFIGURING_PROJECT ($currentState). Ignoring.") }
    }

    internal fun startInstallation(projectDir: File) {
        val service = buildService
        if (service == null) { handleError("Build Service is not available."); return }
        if (!service.isToolingServerStarted()) { handleError("Tooling server is not running."); return }
        setState(WorkflowState.INSTALLING)
        appendToLog("Step 5: Starting build and installation process (assembleDebug)...\n")
        val taskName = "assembleDebug" // Define the task name as a String
        // val taskMessage = TaskExecutionMessage(tasks) // Removed
        val buildListener = object : GradleBuildService.EventListener {
            override fun prepareBuild(buildInfo: BuildInfo) {}
            override fun onBuildSuccessful(executedTasks: List<String?>) {
                activity?.runOnUiThread {
                    appendToLog("✅ Build successful ($executedTasks).\n")
                    findAndInstallApk(projectDir)
                }
            }
            override fun onBuildFailed(executedTasks: List<String?>) { activity?.runOnUiThread { handleError("Build failed for tasks: $executedTasks") } }
            override fun onOutput(line: String?) { if (line != null) { appendToLog(line + "\n") } }
            override fun onProgressEvent(event: ProgressEvent) {}
        }
        service.setEventListener(buildListener)
        // Pass the task name string directly
        service.executeTasks(projectDir.absolutePath, taskName).whenCompleteAsync { result, error -> // Changed: Pass taskName (String)
            activity?.runOnUiThread {
                service.setEventListener(null) // Unset listener
                if (error != null) {
                    if (error !is CompletionException || error.cause !is ToolingServerNotStartedException) {
                         handleError("Error executing build task: ${error.message ?: "Unknown error"}")
                    }
                } else if (result != null && !result.isSuccessful) {
                    handleError("Task execution reported failure. Type: ${result.failure?.name}")
                }
            }
        }
    }

    internal fun findAndInstallApk(projectDir: File) {
        val apkName = "${projectDir.name}-debug.apk"
        val apkPath = "app/build/outputs/apk/debug/$apkName"
        val apkFile = File(projectDir, apkPath)
        if (!apkFile.exists()) {
            handleError("Build successful, but APK not found at: ${apkFile.absolutePath}")
            setState(WorkflowState.ERROR); return
        }
        appendToLog("Found APK: ${apkFile.absolutePath}\nStarting installation...\n")
        val installCallback = (activity as? BaseEditorActivity)?.installationSessionCallback()
        if (installCallback == null) { handleError("Could not get installation callback from activity."); setState(WorkflowState.ERROR); return }
        val intent = Intent()
        val pendingIntent = PendingIntent.getBroadcast(requireContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val sender: IntentSender = pendingIntent.intentSender
        try {
            ApkInstaller.installApk(requireContext(), sender, apkFile, installCallback)
            appendToLog("Installation session initiated...\n")
        } catch (e: Exception) {
            handleError("Failed to initiate APK installation: ${e.message}")
            setState(WorkflowState.ERROR)
        }
    }
    */

    // --- Step 3: LLM Modification ---
    private fun startFileSelectionForModification(appName: String, appDescription: String, projectDir: File) {
        setState(WorkflowState.SELECTING_FILES)
        appendToLog("Step 3a: Identifying files to modify based on description...\n")
        val files = ProjectFileUtils.scanProjectFiles(projectDir) // Use helper
        activity?.runOnUiThread {
            if (files.isEmpty()) { handleError("No code files found in the newly created project"); return@runOnUiThread }
            appendToLog("Found ${files.size} code files in the project.\n")
            sendFileSelectionPrompt(appName, appDescription, files, projectDir)
        }
    }

    // Removed scanProjectFiles, collectFilePaths, isCodeFile - Moved to ProjectFileUtils

    private fun sendFileSelectionPrompt(appName: String, appDescription: String, files: List<String>, projectDir: File) {
        val fileList = files.joinToString("\n")
        val prompt = """
            I have just created a basic Android app called "$appName". App goal: "$appDescription"
            Files:
            $fileList
            Based ONLY on the description, which files likely need CREATION or MODIFICATION?
            Focus on essentials (Activities, Layouts, ViewModels, Gradle files if dependencies implied).
            Respond ONLY with a JSON array of relative paths. Example: ["app/src/main/java/com/example/myapp/MainActivity.kt", "app/src/main/res/layout/activity_main.xml"]
            No explanation.
        """.trimIndent() // Shortened prompt
        conversation.addUserMessage(prompt)
        appendToLog("Asking AI to select relevant files...\n")
        geminiHelper.sendGeminiRequest(conversation.getContents()) { response -> // Use helper
            try {
                val responseText = geminiHelper.extractTextFromGeminiResponse(response) // Use helper
                appendToLog("AI responded with file selections.\n")
                val jsonArrayText = geminiHelper.extractJsonArrayFromText(responseText) // Use helper
                val jsonArray = JSONArray(jsonArrayText)
                selectedFilesForModification.clear()
                for (i in 0 until jsonArray.length()) { selectedFilesForModification.add(jsonArray.getString(i)) }
                conversation.addModelMessage(responseText)
                appendToLog("Selected ${selectedFilesForModification.size} files for modification:\n${selectedFilesForModification.joinToString("") { "- $it\n" }}")
                loadSelectedFileContentsAndGenerate(appName, appDescription, projectDir)
            } catch (e: Exception) { handleError("Failed to parse file selection: ${e.message}") }
        }
    }

     private fun loadSelectedFileContentsAndGenerate(appName: String, appDescription: String, projectDir: File) {
        setState(WorkflowState.GENERATING_CODE)
        appendToLog("Step 3b: Loading selected files and generating code...\n")
        val fileContents = mutableMapOf<String, String>()
        val missingFiles = mutableListOf<String>()
        for (filePath in selectedFilesForModification) {
            val file = File(projectDir, filePath)
            if (!file.exists() || !file.isFile) {
                appendToLog("Note: File '$filePath' does not exist. Will ask AI to create it.\n")
                missingFiles.add(filePath)
                fileContents[filePath] = "// File does not exist. Please create it."
            } else {
                try {
                    fileContents[filePath] = FileReader(file).use { it.readText() }
                    appendToLog("Loaded content for: $filePath\n")
                } catch (e: Exception) { appendToLog("⚠️ Error reading file $filePath: ${e.message}. Skipping.\n") }
            }
        }
        if (fileContents.isEmpty() && missingFiles.isEmpty()) { handleError("Could not read or identify any files for modification."); return }
        sendCodeGenerationPrompt(appName, appDescription, fileContents, missingFiles, projectDir)
    }

     private fun sendCodeGenerationPrompt(appName: String, appDescription: String, fileContents: Map<String, String>, missingFiles: List<String>, projectDir: File) {
        val filesContentText = buildString {
            fileContents.forEach { (path, content) -> append("FILE: $path\n```\n$content\n```\n\n") }
        }
        val missingFilesText = if (missingFiles.isNotEmpty()) { "Note: Create content for missing files:\n${missingFiles.joinToString("\n") { "- $it" }}\n" } else ""
        val prompt = """
            Modifying Android app "$appName". Description: "$appDescription"
            $missingFilesText
            Current file contents (or placeholders):
            $filesContentText
            Provide COMPLETE, updated content for each file needing change/creation for the description. Use valid Kotlin/XML. Apply necessary changes (layouts, logic, dependencies in build.gradle.kts if implied).
            Format STRICTLY:
            FILE: path/to/file.ext
            ```[optional lang hint]
            // Complete file content with modifications
            ```
            Only include changed/created files.
        """.trimIndent() // Shortened prompt
        conversation.addUserMessage(prompt)
        appendToLog("Sending file contents to AI for code generation...\n")
        geminiHelper.sendGeminiRequest(conversation.getContents()) { response -> // Use helper
            try {
                val responseText = geminiHelper.extractTextFromGeminiResponse(response) // Use helper
                conversation.addModelMessage(responseText)
                appendToLog("AI responded with code modifications.\n")
                processCodeChanges(responseText, projectDir) // Renamed for clarity
            } catch (e: Exception) { handleError("Failed during code generation response: ${e.message}") }
        }
    }

    private fun processCodeChanges(responseText: String, projectDir: File) { // Renamed
        appendToLog("Step 3c: Applying code changes...\n")
        val fileChanges = geminiHelper.parseFileChanges(responseText, ::appendToLog) // Use helper
        if (fileChanges.isEmpty()) {
            appendToLog("⚠️ AI did not provide any file changes. Project remains as generated.\n")
            setState(WorkflowState.READY_FOR_ACTION)
            return
        }
        // Use ProjectFileUtils helper
        ProjectFileUtils.processFileChanges(projectDir, fileChanges, ::appendToLog) { _, _ ->
             // Completion logic (state update)
             setState(WorkflowState.READY_FOR_ACTION)
        }
    }
    // Removed processFileChanges - Moved to ProjectFileUtils

    // --- UI State Management ---
    internal fun setState(newState: WorkflowState) {
        if (currentState == newState) return
        currentState = newState
        Log.d(TAG, "State changed to: $newState")
        activity?.runOnUiThread { updateUiForState() }
    }

    private fun updateUiForState() {
        // Simplified state updates
        val isLoading = currentState in listOf(WorkflowState.CREATING_PROJECT, WorkflowState.CONFIGURING_PROJECT, WorkflowState.SELECTING_FILES, WorkflowState.GENERATING_CODE, WorkflowState.INSTALLING)
        loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        generateButton.isEnabled = currentState in listOf(WorkflowState.IDLE, WorkflowState.READY_FOR_ACTION, WorkflowState.ERROR)
        // Show action buttons only when ready
        actionButtonsLayout.visibility = if (currentState == WorkflowState.READY_FOR_ACTION) View.VISIBLE else View.GONE
        // Update logic for the continueButton
        continueButton.isEnabled = currentState == WorkflowState.READY_FOR_ACTION
        continueButton.text = getString(R.string.action_continue_to_build) // Set text explicitly
        modifyFurtherButton.isEnabled = currentState == WorkflowState.READY_FOR_ACTION
        appNameInput.isEnabled = currentState in listOf(WorkflowState.IDLE, WorkflowState.READY_FOR_ACTION, WorkflowState.ERROR)
        appDescriptionInput.isEnabled = currentState in listOf(WorkflowState.IDLE, WorkflowState.READY_FOR_ACTION, WorkflowState.ERROR)

        statusText.text = when (currentState) {
            WorkflowState.IDLE -> "Ready"
            WorkflowState.CREATING_PROJECT -> "Creating project..."
            WorkflowState.CONFIGURING_PROJECT -> "Configuring project (Gradle Sync)..." // May become unused
            WorkflowState.SELECTING_FILES -> "AI selecting files..."
            WorkflowState.GENERATING_CODE -> "AI generating code..."
            WorkflowState.READY_FOR_ACTION -> "Project generated. Ready to open." // Updated text
            WorkflowState.INSTALLING -> "Building and Installing..." // May become unused
            WorkflowState.ERROR -> "Error occurred (see log)"
        }
        generateButton.text = when (currentState) {
             WorkflowState.READY_FOR_ACTION -> "Regenerate App"
             WorkflowState.ERROR -> "Retry Generation"
             else -> "Generate & Modify App"
        }
    }

    // --- Logging and Error Handling ---
    internal fun appendToLog(text: String) {
        activity?.runOnUiThread {
            logOutput.append(text)
            try {
                val layout = logOutput.layout
                if (layout != null) {
                    val scrollAmount = layout.getLineTop(logOutput.lineCount) - logOutput.height
                    logOutput.scrollTo(0, if (scrollAmount > 0) scrollAmount else 0)
                } else { logOutput.scrollTo(0, 0) }
            } catch (e: Exception) { Log.e(TAG, "Error auto-scrolling log output", e) }
        }
    }

    internal fun handleError(message: String) {
        Log.e(TAG, "Error: $message")
        activity?.runOnUiThread {
            setState(WorkflowState.ERROR)
            appendToLog("❌ ERROR: $message\n")
        }
    }

    // --- Helper Functions ---
    private fun createPackageName(appName: String): String {
        val sanitizedName = appName.filter { it.isLetterOrDigit() }.lowercase()
        return "com.example.${sanitizedName.ifEmpty { "myapp" }}"
    }

    private fun com.itsaky.androidide.templates.Template<*>.setupParametersFromDetails(details: NewProjectDetails) {
        // (Content unchanged - kept for brevity)
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
            } else { log.warn("Template does not seem to have a 6th (useKts) parameter.") }
        } catch (e: NoSuchElementException) {
             log.error("Error setting template parameters: Not enough parameters in the template.", e)
             handleError("Internal error: Template parameter mismatch.")
        } catch (e: Exception) {
            log.error("Error setting template parameters", e)
            handleError("Internal error setting template parameters: ${e.message}")
        }
    }

    // --- Gemini API Communication ---
    // Removed sendGeminiRequest, extractTextFromGeminiResponse, parseFileChanges, extractJsonArrayFromText - Moved to GeminiHelper
    // Removed GeminiConversation inner class - Moved to separate class

    // Example placeholder function - replace with your actual implementation
    private fun getApiKeyFromSecureSource(): String {
        // TODO: Implement secure loading (e.g., from BuildConfig, local.properties, etc.)
        // Fallback placeholder - REMOVE THIS IN PRODUCTION
        return "API_KEY_HERE" // Replace with actual secure loading
    }

    // --- Companion Object ---
    companion object {
        const val TAG = "FileEditorDialog"
        private const val ARG_PROJECTS_BASE_DIR = "projects_base_dir"
        fun newInstance(projectsBaseDir: String): FileEditorDialog {
            return FileEditorDialog().apply { arguments = bundleOf(ARG_PROJECTS_BASE_DIR to projectsBaseDir) }
        }
    }
}