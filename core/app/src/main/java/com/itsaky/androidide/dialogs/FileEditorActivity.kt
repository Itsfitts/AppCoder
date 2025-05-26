package com.itsaky.androidide.dialogs // Assuming this package

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.R
// IMPORT the top-level constant from GeminiHelper.kt (assuming it's in the same package)
// If GeminiHelper.kt is in a different package (e.g., com.itsaky.androidide.utils),
// change this import to: import com.itsaky.androidide.utils.DEFAULT_GEMINI_MODEL
import com.itsaky.androidide.dialogs.DEFAULT_GEMINI_MODEL // This line assumes GeminiHelper is in 'dialogs'

import java.io.File

class FileEditorActivity : AppCompatActivity(), FileEditorInterface {

    // ... (UI lateinit vars as before, ensure generateButton is Button) ...
    private lateinit var appNameAutocomplete: AutoCompleteTextView
    private lateinit var appDescriptionInput: TextInputEditText
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var modelSpinner: Spinner
    private lateinit var customModelInputLayout: TextInputLayout
    private lateinit var customModelInput: TextInputEditText
    private lateinit var generateButton: Button // Now a standard Button
    private lateinit var statusText: TextView
    private lateinit var logOutput: TextInputEditText
    private lateinit var aiConclusionOutput: TextInputEditText
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var actionButtonsLayout: LinearLayout
    private lateinit var continueButton: Button
    private lateinit var modifyFurtherButton: Button


    private lateinit var projectsBaseDir: File
    override var currentProjectDir: File? = null
    internal var isModifyingExistingProject = false
    override val isModifyingExistingProjectInternal: Boolean
        get() = isModifyingExistingProject

    private var existingProjectNames: List<String> = emptyList()
    private lateinit var prefs: SharedPreferences

    private lateinit var projectOperationsHandler: ProjectOperationsHandler
    private lateinit var geminiWorkflowCoordinator: GeminiWorkflowCoordinator
    private lateinit var geminiHelper: GeminiHelper

    enum class WorkflowState {
        IDLE, CREATING_PROJECT_TEMPLATE, PREPARING_EXISTING_PROJECT,
        SELECTING_FILES, GENERATING_CODE, READY_FOR_ACTION, ERROR
    }
    internal var currentState = WorkflowState.IDLE
        private set

    private val predefinedModels by lazy {
        listOf(
            DEFAULT_GEMINI_MODEL to "Gemini 2.5 Flash (Default - Preview 05-20)", // Uses imported constant
            "gemini-1.5-flash-latest" to "Gemini 1.5 Flash (Stable)",
            "gemini-1.5-pro-latest" to "Gemini 1.5 Pro (Stable)",
            "gemini-2.5-pro-preview-05-06" to "Gemini 2.5 Pro (Preview 05-06)"
        ).distinctBy { it.first }
    }
    private val customModelOptionText = "Enter Custom Model ID..."
    private var currentSelectedModelIdInternal: String = DEFAULT_GEMINI_MODEL // Uses imported constant


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_editor)

        supportActionBar?.title = "AI App Generator / Modifier"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val basePath = intent.getStringExtra(EXTRA_PROJECTS_BASE_DIR)
            ?: run {
                Log.e(TAG, "Projects base directory argument is missing!")
                Toast.makeText(this, "Error: Projects base directory not specified.", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        projectsBaseDir = File(basePath)
        if (!projectsBaseDir.isDirectory) {
            Log.e(TAG, "Projects base directory does not exist or is not a directory: $basePath")
            Toast.makeText(this, "Error: Invalid projects base directory.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        bindViews()

        geminiHelper = GeminiHelper(
            apiKeyProvider = { apiKeyInput.text.toString().trim() },
            errorHandler = ::handleError,
            uiCallback = { block -> runOnUiThread(block) }
        )

        // Uses imported constant as fallback
        val savedModel = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL
        geminiHelper.setModel(savedModel)
        currentSelectedModelIdInternal = savedModel

        projectOperationsHandler = ProjectOperationsHandler(projectsBaseDir, ::appendToLog, ::handleError, this)
        geminiWorkflowCoordinator = GeminiWorkflowCoordinator(geminiHelper, ::appendToLog, ::handleError, this)

        existingProjectNames = projectOperationsHandler.listExistingProjectNames()
        Log.d(TAG, "Loaded existing project names in onCreate: $existingProjectNames")

        setupAppNameAutocomplete()
        setupListeners()
        loadApiKey()
        setupModelSpinner()
        updateUiForState()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun bindViews() {
        appNameAutocomplete = findViewById(R.id.app_name_autocomplete)
        appDescriptionInput = findViewById(R.id.app_description_input)
        apiKeyInput = findViewById(R.id.api_key_input)
        modelSpinner = findViewById(R.id.model_spinner)
        customModelInputLayout = findViewById(R.id.custom_model_input_layout)
        customModelInput = findViewById(R.id.custom_model_input)
        generateButton = findViewById(R.id.generate_app_button)
        statusText = findViewById(R.id.status_text)
        logOutput = findViewById(R.id.log_output)
        aiConclusionOutput = findViewById(R.id.ai_conclusion_output)
        loadingIndicator = findViewById(R.id.loading_indicator)
        actionButtonsLayout = findViewById(R.id.action_buttons_layout)
        continueButton = findViewById(R.id.continue_button)
        modifyFurtherButton = findViewById(R.id.modify_further_button)

        appDescriptionInput.movementMethod = ScrollingMovementMethod.getInstance()
        logOutput.movementMethod = ScrollingMovementMethod.getInstance()
        logOutput.keyListener = null
        aiConclusionOutput.movementMethod = ScrollingMovementMethod.getInstance()
        aiConclusionOutput.keyListener = null
    }

    private fun setupAppNameAutocomplete() {
        if (!::appNameAutocomplete.isInitialized) {
            Log.e(TAG, "appNameAutocomplete is not initialized.")
            return
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, existingProjectNames)
        appNameAutocomplete.setAdapter(adapter)
        appNameAutocomplete.threshold = 1
    }

    private fun setupListeners() {
        generateButton.setOnClickListener {
            if (currentState == WorkflowState.IDLE || currentState == WorkflowState.ERROR || currentState == WorkflowState.READY_FOR_ACTION) {
                saveApiKey()
                saveAndSetSelectedModel()
                initiateWorkflow()
            }
        }
        continueButton.setOnClickListener {
            if (currentState == WorkflowState.READY_FOR_ACTION && currentProjectDir != null) {
                val resultIntent = Intent().apply { putExtra(RESULT_EXTRA_PROJECT_PATH, currentProjectDir!!.absolutePath) }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            } else {
                appendToLog("Project not ready or directory not set.\n")
            }
        }
        modifyFurtherButton.setOnClickListener { finish() }

        appNameAutocomplete.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val enteredName = s.toString().trim()
                isModifyingExistingProject = enteredName.isNotEmpty() && existingProjectNames.contains(enteredName)
                runOnUiThread { updateUiForState() }
            }
        })
        appNameAutocomplete.setOnItemClickListener { _, _, _, _ ->
            isModifyingExistingProject = true
            runOnUiThread { updateUiForState() }
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(appNameAutocomplete.windowToken, 0)
            appNameAutocomplete.clearFocus()
        }
    }

    private fun loadApiKey() {
        if (::apiKeyInput.isInitialized) apiKeyInput.setText(prefs.getString(KEY_API_KEY, ""))
    }

    private fun saveApiKey() {
        if (::apiKeyInput.isInitialized) prefs.edit().putString(KEY_API_KEY, apiKeyInput.text.toString().trim()).apply()
    }

    private fun setupModelSpinner() {
        val displayNames = predefinedModels.map { it.second }.toMutableList().also { it.add(customModelOptionText) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        modelSpinner.adapter = adapter
        val initialModelId = currentSelectedModelIdInternal
        val predefinedIndex = predefinedModels.indexOfFirst { it.first == initialModelId }

        if (predefinedIndex != -1) {
            modelSpinner.setSelection(predefinedIndex, false)
            customModelInputLayout.visibility = View.GONE
            customModelInput.text = null
        } else {
            val isPotentiallyCustom = initialModelId.isNotEmpty() && predefinedModels.none { it.first == initialModelId }
            if (isPotentiallyCustom) {
                val customIndex = displayNames.indexOf(customModelOptionText)
                modelSpinner.setSelection(if (customIndex != -1) customIndex else 0, false)
                customModelInputLayout.visibility = View.VISIBLE
                customModelInput.setText(initialModelId)
            } else {
                val defaultIndexInPredefined = predefinedModels.indexOfFirst { it.first == DEFAULT_GEMINI_MODEL }
                val selection = if (defaultIndexInPredefined != -1) defaultIndexInPredefined else 0
                modelSpinner.setSelection(selection, false)
                customModelInputLayout.visibility = View.GONE
                currentSelectedModelIdInternal = predefinedModels.getOrNull(selection)?.first ?: DEFAULT_GEMINI_MODEL
                geminiHelper.setModel(currentSelectedModelIdInternal)
            }
        }
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedDisplayName = displayNames.getOrElse(position) { "" }
                if (selectedDisplayName == customModelOptionText) {
                    customModelInputLayout.visibility = View.VISIBLE
                } else {
                    customModelInputLayout.visibility = View.GONE
                    customModelInput.text = null
                    if (position < predefinedModels.size) {
                        val newModelId = predefinedModels[position].first
                        if (newModelId != currentSelectedModelIdInternal) {
                            currentSelectedModelIdInternal = newModelId
                            geminiHelper.setModel(newModelId)
                            prefs.edit().putString(KEY_GEMINI_MODEL, newModelId).apply()
                        }
                    }
                }
                updateUiForState()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun saveAndSetSelectedModel() {
        val selectedSpinnerPosition = modelSpinner.selectedItemPosition
        val displayNames = predefinedModels.map { it.second }.toMutableList().also { it.add(customModelOptionText) }
        val selectedDisplayName = displayNames.getOrElse(selectedSpinnerPosition) { "" }

        val finalModelId = if (selectedDisplayName == customModelOptionText) {
            customModelInput.text.toString().trim().ifBlank {
                Toast.makeText(this, "Custom model ID blank. Using default.", Toast.LENGTH_SHORT).show()
                val defaultIndex = predefinedModels.indexOfFirst { it.first == DEFAULT_GEMINI_MODEL }
                modelSpinner.setSelection(if (defaultIndex != -1) defaultIndex else 0)
                customModelInputLayout.visibility = View.GONE
                DEFAULT_GEMINI_MODEL
            }
        } else {
            predefinedModels.getOrNull(selectedSpinnerPosition)?.first ?: DEFAULT_GEMINI_MODEL
        }
        currentSelectedModelIdInternal = finalModelId
        geminiHelper.setModel(finalModelId)
        prefs.edit().putString(KEY_GEMINI_MODEL, finalModelId).apply()
        Log.i(TAG, "Final model saved for generation: $finalModelId")
    }

    private fun initiateWorkflow() {
        val appName = appNameAutocomplete.text.toString().trim()
        val appDescription = appDescriptionInput.text.toString().trim()
        val apiKey = apiKeyInput.text.toString().trim()
        var valid = true
        if (apiKey.isBlank()) { apiKeyInput.error = "API Key required"; valid = false } else { apiKeyInput.error = null }
        if (appName.isEmpty()) { (appNameAutocomplete.parent.parent as? TextInputLayout)?.error = "App name required"; valid = false } else { (appNameAutocomplete.parent.parent as? TextInputLayout)?.error = null }
        if (appDescription.isEmpty()) { appDescriptionInput.error = "App description required"; valid = false } else { appDescriptionInput.error = null }
        if (!valid) {
            Toast.makeText(this, "Please fill all required fields.", Toast.LENGTH_SHORT).show()
            return
        }
        logOutput.text = null
        aiConclusionOutput.text = null // Clear conclusion
        currentProjectDir = File(projectsBaseDir, appName)
        val actualProjectExistsAndIsDir = projectOperationsHandler.projectExists(appName)
        if (isModifyingExistingProject && !actualProjectExistsAndIsDir) {
            isModifyingExistingProject = false
        } else if (!isModifyingExistingProject && actualProjectExistsAndIsDir) {
            isModifyingExistingProject = true
        }
        updateUiForState()
        if (isModifyingExistingProject) {
            appendToLog("Preparing to modify existing project: $appName\n")
            setState(WorkflowState.PREPARING_EXISTING_PROJECT)
            geminiWorkflowCoordinator.startModificationFlow(appName, appDescription, currentProjectDir!!)
        } else {
            if (currentProjectDir!!.exists() && !currentProjectDir!!.isDirectory) {
                (appNameAutocomplete.parent.parent as? TextInputLayout)?.error = "A file (not project) exists with this name."
                handleError("Cannot create project. A file already exists: '$appName'.", null)
                return
            }
            appendToLog("Starting new project creation: $appName\n")
            setState(WorkflowState.CREATING_PROJECT_TEMPLATE)
            projectOperationsHandler.createNewProjectFromTemplate(appName) { createdDir ->
                currentProjectDir = createdDir
                geminiWorkflowCoordinator.startModificationFlow(appName, appDescription, createdDir)
            }
        }
    }

    internal fun onTemplateProjectCreated(projectDir: File, appName: String, appDescription: String) {
        runOnUiThread {
            currentProjectDir = projectDir
            appendToLog("✅ Base project template created: ${projectDir.absolutePath}\n")
        }
    }

    override fun setState(newState: WorkflowState) {
        if (currentState == newState && newState != WorkflowState.READY_FOR_ACTION) return
        currentState = newState
        Log.d(TAG, "State changed to: $newState")
        runOnUiThread { updateUiForState() }
    }

    private fun updateUiForState() {
        val isLoading = currentState !in listOf(WorkflowState.IDLE, WorkflowState.READY_FOR_ACTION, WorkflowState.ERROR)
        loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        generateButton.isEnabled = !isLoading
        actionButtonsLayout.visibility = if (currentState == WorkflowState.READY_FOR_ACTION) View.VISIBLE else View.GONE
        continueButton.isEnabled = currentState == WorkflowState.READY_FOR_ACTION
        modifyFurtherButton.isEnabled = currentState == WorkflowState.READY_FOR_ACTION
        val editableInputs = currentState == WorkflowState.IDLE || currentState == WorkflowState.ERROR || currentState == WorkflowState.READY_FOR_ACTION
        appNameAutocomplete.isEnabled = editableInputs
        appDescriptionInput.isEnabled = editableInputs
        apiKeyInput.isEnabled = editableInputs
        modelSpinner.isEnabled = editableInputs
        customModelInputLayout.isEnabled = editableInputs && modelSpinner.selectedItemPosition == modelSpinner.adapter.count - 1
        customModelInput.isEnabled = customModelInputLayout.isEnabled

        if (::generateButton.isInitialized) {
            val buttonText = when (currentState) {
                WorkflowState.IDLE -> {
                    val currentAppNameText = if (::appNameAutocomplete.isInitialized) appNameAutocomplete.text.toString().trim() else ""
                    if (currentAppNameText.isNotEmpty() && existingProjectNames.contains(currentAppNameText)) "Modify App with AI" else "Generate App with AI"
                }
                WorkflowState.ERROR -> if (isModifyingExistingProject) "Retry Modification" else "Retry Generation"
                WorkflowState.READY_FOR_ACTION -> if (isModifyingExistingProject) "Modify Again" else "Generate New App"
                else -> generateButton.text.toString()
            }
            generateButton.text = buttonText
        }
        statusText.text = when (currentState) {
            WorkflowState.IDLE -> "Ready"
            WorkflowState.CREATING_PROJECT_TEMPLATE -> "Creating base project..."
            WorkflowState.PREPARING_EXISTING_PROJECT -> "Preparing existing project..."
            WorkflowState.SELECTING_FILES -> "AI is selecting files..."
            WorkflowState.GENERATING_CODE -> "AI is generating code..."
            WorkflowState.READY_FOR_ACTION -> if (isModifyingExistingProject) "Project modified. Ready." else "New project generated. Ready."
            WorkflowState.ERROR -> "Error occurred (see log)"
        }
    }

    override fun appendToLog(text: String) {
        runOnUiThread {
            if(::logOutput.isInitialized) {
                logOutput.append(text)
                if (logOutput.lineCount > 0 && logOutput.layout != null) {
                    val scrollY = logOutput.layout.getLineTop(logOutput.lineCount) - logOutput.height
                    if (scrollY > 0) logOutput.scrollTo(0, scrollY) else logOutput.scrollTo(0, 0)
                }
            }
        }
    }

    internal fun appendToAiConclusion(text: String) {
        runOnUiThread {
            if(::aiConclusionOutput.isInitialized) {
                aiConclusionOutput.append(text)
                if (aiConclusionOutput.lineCount > 0 && aiConclusionOutput.layout != null) {
                    val scrollY = aiConclusionOutput.layout.getLineTop(aiConclusionOutput.lineCount) - aiConclusionOutput.height
                    if (scrollY > 0) aiConclusionOutput.scrollTo(0, scrollY) else aiConclusionOutput.scrollTo(0, 0)
                }
            }
        }
    }

    override fun handleError(message: String, e: Exception?) {
        if (e != null) { Log.e(TAG, message, e) } else { Log.e(TAG, message) }
        setState(WorkflowState.ERROR)
        appendToLog("❌ ERROR: $message\n")
    }

    override fun runOnUiThread(block: () -> Unit) { super.runOnUiThread(block) }
    override fun getContextForHelpers(): Context { return this }
    override fun displayAiConclusion(conclusion: String?) {
        runOnUiThread {
            if (::aiConclusionOutput.isInitialized) {
                aiConclusionOutput.setText(if (conclusion != null) "AI Conclusion:\n$conclusion\n" else "")
            }
        }
    }

    companion object {
        const val TAG = "FileEditorActivity"
        const val EXTRA_PROJECTS_BASE_DIR = "projects_base_dir"
        const val RESULT_EXTRA_PROJECT_PATH = "project_path_result"
        private const val PREFS_NAME = "GeminiPrefs_AppCoder"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_selected_model"

        fun newIntent(context: Context, projectsBaseDir: String): Intent {
            return Intent(context, FileEditorActivity::class.java).apply {
                putExtra(EXTRA_PROJECTS_BASE_DIR, projectsBaseDir)
            }
        }
    }
}