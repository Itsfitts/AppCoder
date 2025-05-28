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

    fun initiateWorkflow(appName: String, appDescription: String, apiKey: String, selectedModelId: String) {
        if (projectsBaseDir == null) {
            handleViewModelError("Projects base directory not set in ViewModel.", null)
            return
        }
        currentApiKeyForWorkflow = apiKey

        saveApiKey(apiKey)
        saveSelectedModel(selectedModelId)

        _logOutput.postValue(StringBuilder())
        _logOutputString.postValue("")
        _aiConclusion.postValue(null)
        updateViewModelState(AiWorkflowState.IDLE)

        appendToViewModelLog("Workflow initiated for App: $appName, Model: $selectedModelId\n")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val projectDir = File(projectsBaseDir!!, appName)
                bridge.currentProjectDirBridge = projectDir

                val actualProjectExistsAndIsDir = projectOperationsHandler.projectExists(appName)
                var effectiveIsModifying = _isModifyingProject.value ?: false

                if (effectiveIsModifying && !actualProjectExistsAndIsDir) {
                    effectiveIsModifying = false
                    _isModifyingProject.postValue(false)
                } else if (!effectiveIsModifying && actualProjectExistsAndIsDir) {
                    effectiveIsModifying = true
                    _isModifyingProject.postValue(true)
                }

                if (effectiveIsModifying) {
                    appendToViewModelLog("Preparing to modify existing project: $appName\n")
                    geminiWorkflowCoordinator.startModificationFlow(appName, appDescription, projectDir)
                } else {
                    if (projectDir.exists() && !projectDir.isDirectory) {
                        handleViewModelError("Cannot create project. A file already exists: '$appName'.", null)
                        return@launch
                    }
                    appendToViewModelLog("Starting new project creation: $appName\n")
                    projectOperationsHandler.createNewProjectFromTemplate(appName) { createdDir ->
                        Log.d(TAG, "ViewModel's onComplete for createNewProjectFromTemplate (background): ${createdDir.path}")
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