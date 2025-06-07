package com.itsaky.androidide.dialogs

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.itsaky.androidide.dialogs.GeminiHelper.Companion.DEFAULT_GEMINI_MODEL

class FileEditorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "FileEditorViewModel"
        private const val PREFS_NAME = "GeminiPrefs_AppCoder_VM"
        private const val KEY_API_KEY = "gemini_api_key_vm"
        private const val KEY_GEMINI_MODEL = "gemini_selected_model_vm"
    }

    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- LiveData for UI State ---
    private val _workflowState = MutableLiveData(AiWorkflowState.IDLE)
    val workflowState: LiveData<AiWorkflowState> = _workflowState

    private val _logOutput = MutableLiveData(StringBuilder()) // Internal StringBuilder
    private val _logOutputString = MutableLiveData<String>()    // Exposed String for Activity
    val logOutputText: LiveData<String> = _logOutputString

    private val _aiConclusion = MutableLiveData<String?>()
    val aiConclusion: LiveData<String?> = _aiConclusion

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _isModifyingProject = MutableLiveData(false)
    val isModifyingProject: LiveData<Boolean> = _isModifyingProject

    private val _currentProjectDirVM = MutableLiveData<File?>()
    val currentProjectDirVM: LiveData<File?> = _currentProjectDirVM

    private val _statusText = MutableLiveData("Ready")
    val statusText: LiveData<String> = _statusText

    private val _actionButtonsVisible = MutableLiveData(false)
    val actionButtonsVisible: LiveData<Boolean> = _actionButtonsVisible

    private val _uiErrorEvent = MutableLiveData<String?>()
    val uiErrorEvent: LiveData<String?> = _uiErrorEvent

    private val _existingProjectNames = MutableLiveData<List<String>>(emptyList())
    val existingProjectNames: LiveData<List<String>> = _existingProjectNames

    private var _storedApiKey: String = prefs.getString(KEY_API_KEY, "") ?: ""
    val storedApiKey: String get() = _storedApiKey

    private var _storedModelId: String = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL
    val storedModelId: String get() = _storedModelId

    var projectsBaseDir: File? = null
        set(value) {
            field = value
            if (value != null) {
                loadExistingProjectNames()
            }
        }

    // --- Bridge Implementation for Helpers ---
    // Explicit type for the bridge object itself
    private val bridge: ViewModelFileEditorBridge = object : ViewModelFileEditorBridge {
        override var currentProjectDirBridge: File?
            get() = _currentProjectDirVM.value
            set(value) { _currentProjectDirVM.postValue(value) }

        override val isModifyingExistingProjectBridge: Boolean
            get() = _isModifyingProject.value ?: false

        override fun updateStateBridge(newState: AiWorkflowState) {
            updateViewModelState(newState)
        }

        override fun appendToLogBridge(text: String) {
            appendToViewModelLog(text)
        }

        override fun displayAiConclusionBridge(conclusion: String?) {
            _aiConclusion.postValue(conclusion)
        }

        override fun handleErrorBridge(message: String, e: Exception?) {
            handleViewModelError(message, e)
        }

        override fun runOnUiThreadBridge(block: () -> Unit) {
            runOnMainThread(block)
        }

        override fun getContextBridge(): Context {
            return application.applicationContext
        }

        override fun onTemplateProjectCreatedBridge(projectDir: File, appName: String, appDescription: String) {
            runOnMainThread {
                _currentProjectDirVM.value = projectDir
                appendToViewModelLog("✅ Base project template created (via bridge): ${projectDir.absolutePath}\n")
                if (currentApiKeyForWorkflow.isNotBlank()) {
                    geminiWorkflowCoordinator.startModificationFlow(appName, appDescription, projectDir)
                } else {
                    handleViewModelError("API Key not available when trying to start modification flow after template creation.", null)
                }
            }
        }
    }

    // --- Helper Instances (with explicit types) ---
    private val geminiHelper: GeminiHelper by lazy {
        GeminiHelper(
            apiKeyProvider = { _storedApiKey },
            errorHandlerCallback = { message, e -> bridge.handleErrorBridge(message, e) },
            uiThreadExecutor = { block -> bridge.runOnUiThreadBridge(block) }
        )
    }

    private val projectOperationsHandler: ProjectOperationsHandler by lazy {
        ProjectOperationsHandler(
            projectsBaseDir = projectsBaseDir ?: getApplication<Application>().filesDir,
            directLogAppender = { msg -> bridge.appendToLogBridge(msg) },
            directErrorHandler = { msg, ex -> bridge.handleErrorBridge(msg, ex) },
            bridge = bridge
        )
    }

    private val geminiWorkflowCoordinator: GeminiWorkflowCoordinator by lazy {
        GeminiWorkflowCoordinator(
            geminiHelper = geminiHelper,
            directLogAppender = { msg -> bridge.appendToLogBridge(msg) },
            directErrorHandler = { msg, ex -> bridge.handleErrorBridge(msg, ex) },
            bridge = bridge
        )
    }
    private var currentApiKeyForWorkflow: String = ""

    init {
        geminiHelper.setModel(_storedModelId)
    }

    private fun loadExistingProjectNames() {
        viewModelScope.launch(Dispatchers.IO) {
            val names = projectOperationsHandler.listExistingProjectNames()
            _existingProjectNames.postValue(names)
        }
    }

    fun refreshExistingProjectNames() {
        loadExistingProjectNames()
    }

    fun saveApiKey(apiKey: String) {
        val trimmedApiKey = apiKey.trim()
        prefs.edit().putString(KEY_API_KEY, trimmedApiKey).apply()
        this._storedApiKey = trimmedApiKey
    }

    fun saveSelectedModel(modelId: String) {
        prefs.edit().putString(KEY_GEMINI_MODEL, modelId).apply()
        this._storedModelId = modelId
        geminiHelper.setModel(modelId)
    }

    fun updateIsModifyingProjectFlag(isModifying: Boolean) {
        if (_isModifyingProject.value != isModifying) {
            _isModifyingProject.postValue(isModifying)
        }
    }

    fun postUiFeedbackLog(message: String) {
        appendToViewModelLog(message)
    }


    private fun buildGenerationPrompt(appName: String, userDescription: String): String {
        // This template provides a role, critical instructions, and clear structure for the AI.
        return """
    **AI GOAL: Full Android App Generation**

    You are an expert Android developer specializing in creating complete, functional, and well-structured applications in Kotlin using modern practices. Your task is to generate all the necessary code for a new Android application based on the user's request.

    **CRITICAL INSTRUCTIONS:**
    1.  **Full Implementation Required:** You MUST generate all required files for a complete application. This includes XML layouts, all necessary Kotlin classes (Activities, Adapters, etc.), and resource definitions in `strings.xml`.
    2.  **No Placeholders:** Do NOT use placeholder comments like `// TODO: Implement...` or `// Add logic here`. The generated code must be fully functional.
    3.  **Resource Definition:** For every string resource used in an XML layout (e.g., `@string/welcome_message`), you MUST provide the corresponding `<string name="welcome_message">...</string>` definition in the `strings.xml` file. This is crucial to prevent resource-not-found build errors.
    4.  **View Binding:** The project template uses View Binding. You MUST use the generated binding class to access all views (e.g., `binding.myButton.text = ...`). Do not use `findViewById`.
    5.  **Logical Cohesion:** Ensure the generated code is logical and cohesive. If the user asks for a quiz app, implement the question-handling, answer-checking, and score-updating logic.

    ---
    
    **## USER'S APP REQUEST ##**

    **App Name:**
    $appName

    **App Description:**
    $userDescription
    """.trimIndent()
    }

    fun initiateWorkflow(appName: String, appDescription: String, apiKey: String, selectedModelId: String) {
        if (projectsBaseDir == null) {
            handleViewModelError("Projects base directory not set in ViewModel.", null)
            return
        }
        currentApiKeyForWorkflow = apiKey

        saveApiKey(apiKey)
        saveSelectedModel(selectedModelId)

        // --- Reset all states for a new run ---
        _logOutput.postValue(StringBuilder())
        _logOutputString.postValue("")
        _aiConclusion.postValue(null)
        updateViewModelState(AiWorkflowState.IDLE)

        appendToViewModelLog("Workflow initiated for App: $appName, Model: $selectedModelId\n")

        // --- CENTRALIZED PROMPT ENGINEERING ---
        // This is now the single source of truth for the prompt.
        // We check if the description is a retry-prompt from a build failure.
        // If not, we wrap the user's simple description in our powerful template.
        val isRetryPrompt = appDescription.contains("Please analyze the original app description and the following build failure", ignoreCase = true)
        val finalPrompt = if (isRetryPrompt) {
            appendToViewModelLog("This is a retry-on-failure workflow. Using the description as-is.\n")
            appDescription
        } else {
            appendToViewModelLog("This is a new generation/modification workflow. Building enhanced prompt.\n")
            buildGenerationPrompt(appName, appDescription)
        }
        // --- END OF PROMPT ENGINEERING ---

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val projectDir = File(projectsBaseDir!!, appName)
                bridge.currentProjectDirBridge = projectDir

                val isExistingProject = projectOperationsHandler.projectExists(appName)

                if (isExistingProject) {
                    // --- PATH FOR MODIFYING AN EXISTING PROJECT ---
                    appendToViewModelLog("Project exists. Starting modification flow...\n")
                    // Directly call the coordinator with the final prompt.
                    geminiWorkflowCoordinator.startModificationFlow(appName, finalPrompt, projectDir)
                } else {
                    // --- PATH FOR CREATING A NEW PROJECT ---
                    appendToViewModelLog("Project does not exist. Creating new project template...\n")
                    updateViewModelState(AiWorkflowState.CREATING_PROJECT_TEMPLATE)

                    // Create the template, and in the completion callback, start the AI flow.
                    projectOperationsHandler.createNewProjectFromTemplate(appName) { createdDir ->
                        if (createdDir != null) {
                            appendToViewModelLog("✅ Base project template created at: ${createdDir.absolutePath}\n")
                            // Now, use the created directory to start the modification flow with our powerful prompt.
                            geminiWorkflowCoordinator.startModificationFlow(appName, finalPrompt, createdDir)
                        } else {
                            // This else block will be hit if createNewProjectFromTemplate itself fails.
                            // The handler should have already set the error state via the bridge.
                            Log.e(TAG, "createNewProjectFromTemplate failed and did not return a directory.")
                        }
                    }
                }
            } catch (e: Exception) {
                handleViewModelError("Error initiating workflow: ${e.message}", e)
            }
        }
    }

    private fun runOnMainThread(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main) {
            block()
        }
    }

    private fun appendToViewModelLog(text: String) {
        viewModelScope.launch(Dispatchers.Main) {
            _logOutput.value?.append(text)
            _logOutputString.value = _logOutput.value.toString()
        }
    }

    private fun handleViewModelError(message: String, e: Exception?) {
        viewModelScope.launch(Dispatchers.Main) {
            if (e != null) { Log.e(TAG, message, e) } else { Log.e(TAG, message) }
            _logOutput.value?.append("❌ ERROR: $message\n")
            _logOutputString.value = _logOutput.value.toString()
            updateViewModelState(AiWorkflowState.ERROR)
            _uiErrorEvent.value = message
        }
    }

    fun consumedUiErrorEvent() {
        _uiErrorEvent.value = null
    }

    private fun updateViewModelState(newState: AiWorkflowState) {
        runOnMainThread {
            if (_workflowState.value == newState && newState != AiWorkflowState.READY_FOR_ACTION) return@runOnMainThread
            _workflowState.value = newState
            Log.d(TAG, "State changed to (VM): $newState")

            val currentIsLoading = newState !in listOf(
                AiWorkflowState.IDLE,
                AiWorkflowState.READY_FOR_ACTION,
                AiWorkflowState.ERROR
            )
            if (_isLoading.value != currentIsLoading) _isLoading.value = currentIsLoading

            val currentActionButtonsVisible = newState == AiWorkflowState.READY_FOR_ACTION
            if (_actionButtonsVisible.value != currentActionButtonsVisible) _actionButtonsVisible.value = currentActionButtonsVisible

            val newStatusText = when (newState) {
                AiWorkflowState.IDLE -> "Ready"
                AiWorkflowState.CREATING_PROJECT_TEMPLATE -> "Creating base project..."
                AiWorkflowState.PREPARING_EXISTING_PROJECT -> "Preparing existing project..."
                AiWorkflowState.SELECTING_FILES -> "AI is selecting files..."
                AiWorkflowState.GENERATING_CODE -> "AI is generating code..."
                AiWorkflowState.GENERATING_SUMMARY -> "AI is generating summary..."
                AiWorkflowState.READY_FOR_ACTION -> if (_isModifyingProject.value == true) "Project modified. Ready." else "New project generated. Ready."
                AiWorkflowState.ERROR -> "Error occurred (see log)"
            }
            if (_statusText.value != newStatusText) _statusText.value = newStatusText
        }
    }
}