package com.itsaky.androidide.dialogs

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.LayoutInflater
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
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.R // Make sure this R is correct for your module
import com.itsaky.androidide.activities.MainActivity
import java.io.File

class FileEditorDialog : DialogFragment() {

    private val logger = org.slf4j.LoggerFactory.getLogger(javaClass.simpleName)

    // UI Views
    private lateinit var appNameAutocomplete: AutoCompleteTextView
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

    // Properties
    private lateinit var projectsBaseDir: File
    internal var currentProjectDir: File? = null
    internal var isModifyingExistingProject = false // Flag for current workflow intent
    private var existingProjectNames = listOf<String>()

    private lateinit var prefs: SharedPreferences

    // Helper Classes
    private lateinit var projectOperationsHandler: ProjectOperationsHandler
    private lateinit var geminiWorkflowCoordinator: GeminiWorkflowCoordinator
    private lateinit var geminiHelper: GeminiHelper // For model management primarily

    internal enum class WorkflowState {
        IDLE,
        CREATING_PROJECT_TEMPLATE,
        PREPARING_EXISTING_PROJECT,
        SELECTING_FILES,
        GENERATING_CODE,
        READY_FOR_ACTION,
        ERROR
    }
    internal var currentState = WorkflowState.IDLE
        private set

    private val predefinedModels by lazy {
        listOf(
            DEFAULT_GEMINI_MODEL to "Gemini 2.5 Flash (Default - Preview 05-20)", // Using const from GeminiHelper
            "gemini-1.5-flash-latest" to "Gemini 1.5 Flash (Stable)",
            "gemini-1.5-pro-latest" to "Gemini 1.5 Pro (Stable)",
            "gemini-2.5-pro-preview-05-06" to "Gemini 2.5 Pro (Preview 05-06)"
        ).distinctBy { it.first }
    }
    private val customModelOptionText = "Enter Custom Model ID..."
    private var currentSelectedModelIdInternal: String = DEFAULT_GEMINI_MODEL // Using const from GeminiHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val basePath = requireArguments().getString(ARG_PROJECTS_BASE_DIR)
            ?: run {
                Log.e(TAG, "Projects base directory argument is missing!")
                // Consider using a string resource for user-facing messages
                Toast.makeText(requireContext(), "Error: Projects base directory not specified.", Toast.LENGTH_LONG).show()
                dismiss()
                return
            }
        projectsBaseDir = File(basePath)
        if (!projectsBaseDir.isDirectory) {
            Log.e(TAG, "Projects base directory does not exist or is not a directory: $basePath")
            Toast.makeText(requireContext(), "Error: Invalid projects base directory.", Toast.LENGTH_LONG).show()
            dismiss()
            return
        }

        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        geminiHelper = GeminiHelper(
            apiKeyProvider = { apiKeyInput.text.toString().trim() },
            errorHandler = ::handleError,
            uiCallback = { block -> activity?.runOnUiThread(block) }
        )
        val savedModel = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL
        geminiHelper.setModel(savedModel)
        currentSelectedModelIdInternal = savedModel

        projectOperationsHandler = ProjectOperationsHandler(projectsBaseDir, ::appendToLog, ::handleError, this)
        geminiWorkflowCoordinator = GeminiWorkflowCoordinator(geminiHelper, ::appendToLog, ::handleError, this)

        existingProjectNames = projectOperationsHandler.listExistingProjectNames()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.layout_file_editor_dialog, null)

        bindViews(view)
        setupAppNameAutocomplete()
        setupListeners()
        loadApiKey()
        setupModelSpinner()

        builder.setView(view)
            .setTitle("AI App Generator / Modifier")
            .setPositiveButton("Close", null) // We handle dismiss explicitly or with other buttons

        updateUiForState()
        return builder.create()
    }

    private fun bindViews(view: View) {
        appNameAutocomplete = view.findViewById(R.id.app_name_autocomplete)
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

        logOutput.movementMethod = ScrollingMovementMethod()
        logOutput.isFocusable = false
    }

    private fun setupAppNameAutocomplete() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, existingProjectNames)
        appNameAutocomplete.setAdapter(adapter)
        appNameAutocomplete.threshold = 1

        appNameAutocomplete.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val enteredName = s.toString().trim()
                isModifyingExistingProject = enteredName.isNotEmpty() && existingProjectNames.contains(enteredName)
                activity?.runOnUiThread { updateUiForState() }
            }
        })

        appNameAutocomplete.setOnItemClickListener { parent, _, position, _ ->
            val selectedName = parent.getItemAtPosition(position) as String
            Log.d(TAG, "Existing project selected from dropdown: $selectedName")
            isModifyingExistingProject = true
            activity?.runOnUiThread { updateUiForState() }
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(appNameAutocomplete.windowToken, 0)
            appNameAutocomplete.clearFocus()
        }
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
                (activity as? MainActivity)?.openProject(currentProjectDir!!)
                dismiss()
            } else {
                appendToLog("Project not ready or directory not set. Cannot continue.\n")
            }
        }
        modifyFurtherButton.setOnClickListener { dismiss() }
    }

    private fun loadApiKey() {
        apiKeyInput.setText(prefs.getString(KEY_API_KEY, ""))
    }

    private fun saveApiKey() {
        prefs.edit().putString(KEY_API_KEY, apiKeyInput.text.toString().trim()).apply()
    }

    private fun setupModelSpinner() {
        val displayNames = predefinedModels.map { it.second }.toMutableList().also {
            it.add(customModelOptionText)
        }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, displayNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        modelSpinner.adapter = adapter

        val initialModelId = currentSelectedModelIdInternal
        val predefinedIndex = predefinedModels.indexOfFirst { it.first == initialModelId }

        if (predefinedIndex != -1) {
            modelSpinner.setSelection(predefinedIndex, false)
            customModelInputLayout.visibility = View.GONE
            customModelInput.text = null
        } else if (initialModelId.isNotEmpty() && initialModelId != DEFAULT_GEMINI_MODEL) {
            modelSpinner.setSelection(displayNames.indexOf(customModelOptionText), false)
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
                            Log.d(TAG, "Spinner selected predefined: $newModelId. Saved.")
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
        val selectedDisplayName = displayNames.getOrElse(selectedSpinnerPosition) { predefinedModels.firstOrNull()?.second ?: "" }

        val finalModelId = if (selectedDisplayName == customModelOptionText) {
            customModelInput.text.toString().trim().ifBlank {
                Toast.makeText(requireContext(), "Custom model ID blank. Using default.", Toast.LENGTH_SHORT).show()
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
        Log.i(TAG, "Final model saved and set for generation: $finalModelId")
    }

    private fun initiateWorkflow() {
        val appName = appNameAutocomplete.text.toString().trim()
        val appDescription = appDescriptionInput.text.toString().trim()
        val apiKey = apiKeyInput.text.toString().trim()

        var valid = true
        if (apiKey.isBlank()) {
            activity?.runOnUiThread { apiKeyInput.error = "API Key required" }
            valid = false
        } else {
            activity?.runOnUiThread { apiKeyInput.error = null }
        }
        if (appName.isEmpty()) {
            activity?.runOnUiThread { (appNameAutocomplete.parent.parent as? TextInputLayout)?.error = "App name is required" }
            valid = false
        } else {
            activity?.runOnUiThread { (appNameAutocomplete.parent.parent as? TextInputLayout)?.error = null }
        }
        if (appDescription.isEmpty()) {
            activity?.runOnUiThread { appDescriptionInput.error = "App description is required" }
            valid = false
        } else {
            activity?.runOnUiThread { appDescriptionInput.error = null }
        }
        if (!valid) {
            // Do not call handleError, as it sets state to ERROR.
            // Just return and let the field errors guide the user.
            Toast.makeText(context, "Please fill all required fields.", Toast.LENGTH_SHORT).show()
            return
        }

        logOutput.text = null
        currentProjectDir = File(projectsBaseDir, appName)

        // The isModifyingExistingProject flag should be accurately set by the AutoCompleteTextView's listeners
        // We do a final check here based on disk state to be robust
        val actualProjectExistsAndIsDir = projectOperationsHandler.projectExists(appName)

        if (isModifyingExistingProject && !actualProjectExistsAndIsDir) {
            Log.w(TAG, "UI flag indicated existing project, but '$appName' not found on disk or not a directory. Treating as new project attempt.")
            isModifyingExistingProject = false // Correct the flag
            // Potentially show a toast or log that the selected existing project was not found, and it will try to create new
        } else if (!isModifyingExistingProject && actualProjectExistsAndIsDir) {
            Log.w(TAG, "UI flag indicated new project, but '$appName' already exists on disk. Treating as modification attempt.")
            isModifyingExistingProject = true // Correct the flag
        }
        // If isModifyingExistingProject is true, currentProjectDir is the existing one.
        // If isModifyingExistingProject is false, currentProjectDir is the target for the new one.

        updateUiForState() // Refresh button text based on final decision

        if (isModifyingExistingProject) {
            appendToLog("Preparing to modify existing project: $appName\n")
            setState(WorkflowState.PREPARING_EXISTING_PROJECT)
            geminiWorkflowCoordinator.startModificationFlow(appName, appDescription, currentProjectDir!!)
        } else { // New project
            // We need to ensure it doesn't exist as a file if it's a new project name.
            if (currentProjectDir!!.exists() && !currentProjectDir!!.isDirectory) {
                activity?.runOnUiThread { (appNameAutocomplete.parent.parent as? TextInputLayout)?.error = "A file (not project) exists with this name." }
                handleError("Cannot create project. A file already exists with the name '$appName'.", null)
                return
            }
            // If currentProjectDir exists and IS a directory, it means isModifyingExistingProject should have been true.
            // This case is handled above by correcting isModifyingExistingProject.
            // If it doesn't exist, we are good to create.

            appendToLog("Starting new project creation: $appName\n")
            setState(WorkflowState.CREATING_PROJECT_TEMPLATE)
            projectOperationsHandler.createNewProjectFromTemplate(appName) { createdDir ->
                currentProjectDir = createdDir
                geminiWorkflowCoordinator.startModificationFlow(appName, appDescription, createdDir)
            }
        }
    }

    // Callback from ProjectOperationsHandler
    internal fun onTemplateProjectCreated(projectDir: File, appName: String, appDescription: String) {
        currentProjectDir = projectDir // Should already be set by the callback from createNewProject...
        appendToLog("✅ Base project created at ${projectDir.absolutePath}\n")
        // GeminiWorkflowCoordinator.startModificationFlow was already called in the callback of createNewProjectFromTemplate
    }

    internal fun setState(newState: WorkflowState) {
        if (currentState == newState && newState != WorkflowState.READY_FOR_ACTION) return
        currentState = newState
        Log.d(TAG, "State changed to: $newState")
        activity?.runOnUiThread { updateUiForState() }
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
        customModelInputLayout.isEnabled = editableInputs && modelSpinner.selectedItemPosition == modelSpinner.adapter.count -1
        customModelInput.isEnabled = customModelInputLayout.isEnabled

        statusText.text = when (currentState) {
            WorkflowState.IDLE -> "Ready to generate or modify"
            WorkflowState.CREATING_PROJECT_TEMPLATE -> "Creating base project template..."
            WorkflowState.PREPARING_EXISTING_PROJECT -> "Preparing to modify existing project..."
            WorkflowState.SELECTING_FILES -> "AI is selecting files..."
            WorkflowState.GENERATING_CODE -> "AI is generating code..."
            WorkflowState.READY_FOR_ACTION -> {
                // This uses the isModifyingExistingProject flag that was set at the *start* of the successful workflow
                if (isModifyingExistingProject) "Project modified. Ready to open or re-modify."
                else "New project generated. Ready to open or regenerate."
            }
            WorkflowState.ERROR -> "Error occurred (see log)"
        }

        // For button text, check the current value of the AutoCompleteTextView
        // to reflect intent for the *next* operation when IDLE/ERROR/READY.
        val currentAppNameText = appNameAutocomplete.text.toString().trim()
        val currentInputSuggestsExisting = currentAppNameText.isNotEmpty() && existingProjectNames.contains(currentAppNameText)

        generateButton.text = when {
            currentState == WorkflowState.ERROR -> "Retry"
            currentState == WorkflowState.READY_FOR_ACTION -> {
                // After a workflow, use the flag that determined that workflow
                if (isModifyingExistingProject) "Re-Modify Existing" else "Regenerate New"
            }
            // When IDLE, base button text on current input
            currentInputSuggestsExisting -> "Modify Existing App"
            else -> "Generate New App"
        }
    }

    internal fun appendToLog(text: String) {
        activity?.runOnUiThread {
            logOutput.append(text)
            logOutput.layout?.let { layout ->
                val scrollAmount = layout.getLineTop(logOutput.lineCount) - logOutput.height
                if (scrollAmount > 0) logOutput.scrollTo(0, scrollAmount)
                else logOutput.scrollTo(0, 0)
            }
        }
    }

    internal fun handleError(message: String, e: Exception? = null) {
        if (e != null) {
            Log.e(TAG, message, e)
        } else {
            Log.e(TAG, message)
        }
        // Don't reset isModifyingExistingProject here, let the UI reflect the state
        // that led to the error if it was mid-workflow for an existing project.
        // It will be re-evaluated on the next initiateWorkflow.
        setState(WorkflowState.ERROR)
        appendToLog("❌ ERROR: $message\n")
    }

    companion object {
        const val TAG = "FileEditorDialog"
        private const val ARG_PROJECTS_BASE_DIR = "projects_base_dir"
        private const val PREFS_NAME = "GeminiPrefs_AppCoder" // Consistent name
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_selected_model"

        fun newInstance(projectsBaseDir: String): FileEditorDialog {
            return FileEditorDialog().apply {
                arguments = bundleOf(ARG_PROJECTS_BASE_DIR to projectsBaseDir)
            }
        }
    }
}