package com.itsaky.androidide.dialogs

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.R // Main R
import java.io.File

class FileEditorActivity : AppCompatActivity() {

    companion object {
        const val TAG_ACTIVITY = "FileEditorActivity"
        const val EXTRA_PROJECTS_BASE_DIR = "projects_base_dir"
        const val RESULT_EXTRA_PROJECT_PATH = "project_path_result"

        // New extras for pre-filling fields
        const val EXTRA_PREFILL_APP_NAME = "prefill_app_name"
        const val EXTRA_PREFILL_APP_DESCRIPTION = "prefill_app_description"


        fun newIntent(context: Context, projectsBaseDir: String): Intent {
            return Intent(context, FileEditorActivity::class.java).apply {
                putExtra(EXTRA_PROJECTS_BASE_DIR, projectsBaseDir)
            }
        }
    }

    private lateinit var appNameAutocomplete: AutoCompleteTextView
    private lateinit var appDescriptionInput: TextInputEditText
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var modelSpinner: Spinner
    private lateinit var customModelInputLayout: TextInputLayout
    private lateinit var customModelInput: TextInputEditText
    private lateinit var generateButton: Button
    private lateinit var statusText: TextView
    private lateinit var logOutput: TextInputEditText
    private lateinit var aiConclusionOutput: TextInputEditText
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var actionButtonsLayout: LinearLayout
    private lateinit var continueButton: Button
    private lateinit var modifyFurtherButton: Button

    private lateinit var viewModel: FileEditorViewModel

    private val predefinedModels by lazy {
        listOf(
            DEFAULT_GEMINI_MODEL to "Gemini 2.5 Flash (Default - Preview 05-20)",
            "gemini-1.5-flash-latest" to "Gemini 1.5 Flash (Stable)",
            "gemini-1.5-pro-latest" to "Gemini 1.5 Pro (Stable)",
            "gemini-2.5-pro-preview-05-06" to "Gemini 2.5 Pro (Preview 05-06)"
        ).distinctBy { it.first }
    }
    private val customModelOptionText = "Enter Custom Model ID..."
    private var currentSelectedModelIdInUI: String = DEFAULT_GEMINI_MODEL


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_editor)

        supportActionBar?.title = "AI App Generator / Modifier"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        viewModel = ViewModelProvider(this)[FileEditorViewModel::class.java]

        val basePath = intent.getStringExtra(EXTRA_PROJECTS_BASE_DIR)
        if (basePath == null) {
            Log.e(TAG_ACTIVITY, "Projects base directory argument is missing!")
            Toast.makeText(this, "Error: Projects base directory not specified.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val projectsBaseDirFile = File(basePath)
        if (!projectsBaseDirFile.isDirectory) {
            Log.e(TAG_ACTIVITY, "Projects base directory does not exist or is not a directory: $basePath")
            Toast.makeText(this, "Error: Invalid projects base directory.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        viewModel.projectsBaseDir = projectsBaseDirFile

        bindViews()
        setupInitialUIValues() // For API key and model from ViewModel's stored prefs

        // --- Handle pre-filled data from Intent ---
        val prefillAppName = intent.getStringExtra(EXTRA_PREFILL_APP_NAME)
        val prefillAppDescription = intent.getStringExtra(EXTRA_PREFILL_APP_DESCRIPTION)

        if (prefillAppName != null) {
            appNameAutocomplete.setText(prefillAppName)
            // Trigger check for existing project if name is pre-filled
            viewModel.updateIsModifyingProjectFlag(
                prefillAppName.isNotEmpty() && (viewModel.existingProjectNames.value?.contains(prefillAppName) == true)
            )
        }
        if (prefillAppDescription != null) {
            appDescriptionInput.setText(prefillAppDescription)
        }
        // --- End of handling pre-filled data ---

        setupListeners()
        observeViewModel()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.isLoading.value == true) {
                    Toast.makeText(this@FileEditorActivity, "Processing... Please wait.", Toast.LENGTH_SHORT).show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (viewModel.isLoading.value == true) {
                Toast.makeText(this@FileEditorActivity, "Processing... Please wait.", Toast.LENGTH_SHORT).show()
                return true
            }
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

    private fun setupInitialUIValues() {
        apiKeyInput.setText(viewModel.storedApiKey)
        currentSelectedModelIdInUI = viewModel.storedModelId
        setupModelSpinnerInternal(viewModel.storedModelId)
    }
    private fun setupModelSpinnerInternal(initialModelId: String) {
        val displayNames = predefinedModels.map { it.second }.toMutableList().also { it.add(customModelOptionText) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        modelSpinner.adapter = adapter

        val predefinedIndex = predefinedModels.indexOfFirst { it.first == initialModelId }
        if (predefinedIndex != -1) {
            modelSpinner.setSelection(predefinedIndex, false)
            customModelInputLayout.visibility = View.GONE
            customModelInput.text = null
        } else {
            if (initialModelId.isNotEmpty() && predefinedModels.none { it.first == initialModelId }) {
                val customIndex = displayNames.indexOf(customModelOptionText)
                modelSpinner.setSelection(if (customIndex != -1) customIndex else 0, false)
                customModelInputLayout.visibility = View.VISIBLE
                customModelInput.setText(initialModelId)
            } else {
                val defaultIndexInPredefined = predefinedModels.indexOfFirst { it.first == DEFAULT_GEMINI_MODEL }
                val selection = if (defaultIndexInPredefined != -1) defaultIndexInPredefined else 0
                modelSpinner.setSelection(selection, false)
                customModelInputLayout.visibility = View.GONE
                currentSelectedModelIdInUI = predefinedModels.getOrNull(selection)?.first ?: DEFAULT_GEMINI_MODEL
            }
        }
    }


    private fun setupListeners() {
        generateButton.setOnClickListener {
            val currentState = viewModel.workflowState.value
            if (currentState == AiWorkflowState.IDLE || currentState == AiWorkflowState.ERROR || currentState == AiWorkflowState.READY_FOR_ACTION) {
                val appName = appNameAutocomplete.text.toString().trim()
                val appDescription = appDescriptionInput.text.toString().trim()
                val apiKey = apiKeyInput.text.toString().trim()
                val selectedModelId = getSelectedModelIdFromUi()

                var valid = true
                if (apiKey.isBlank()) { apiKeyInput.error = "API Key required"; valid = false } else { apiKeyInput.error = null }
                (appNameAutocomplete.parent.parent as? TextInputLayout)?.error = if (appName.isEmpty()) "App name required" else null
                if (appName.isEmpty()) valid = false
                appDescriptionInput.error = if (appDescription.isEmpty()) "App description required" else null // Corrected: Was appDescriptionInput.error, should be (appDescriptionInput.parent.parent as? TextInputLayout)?.error
                (appDescriptionInput.parent.parent as? TextInputLayout)?.error = if (appDescription.isEmpty()) "App description required" else null
                if (appDescription.isEmpty()) valid = false

                if (selectedModelId.isBlank() && customModelInputLayout.visibility == View.VISIBLE) {
                    customModelInput.error = "Custom Model ID required if selected"; valid = false;
                } else { customModelInput.error = null }


                if (!valid) {
                    Toast.makeText(this, "Please fill all required fields.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(it.windowToken, 0)

                viewModel.initiateWorkflow(appName, appDescription, apiKey, selectedModelId)
            }
        }

        continueButton.setOnClickListener {
            if (viewModel.workflowState.value == AiWorkflowState.READY_FOR_ACTION && viewModel.currentProjectDirVM.value != null) {
                val resultIntent = Intent().apply { putExtra(RESULT_EXTRA_PROJECT_PATH, viewModel.currentProjectDirVM.value!!.absolutePath) }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            } else {
                Toast.makeText(this, "Project not ready or directory not set.", Toast.LENGTH_SHORT).show();
            }
        }
        modifyFurtherButton.setOnClickListener { finish() } // Simplified: just finishes to go back

        appNameAutocomplete.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val enteredName = s.toString().trim()
                val isExisting = enteredName.isNotEmpty() && (viewModel.existingProjectNames.value?.contains(enteredName) == true)
                viewModel.updateIsModifyingProjectFlag(isExisting)
            }
        })
        appNameAutocomplete.setOnItemClickListener { _, _, _, _ ->
            viewModel.updateIsModifyingProjectFlag(true)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(appNameAutocomplete.windowToken, 0)
            appNameAutocomplete.clearFocus()
        }
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val displayNames = predefinedModels.map { it.second }.toMutableList().also { it.add(customModelOptionText) }
                val selectedDisplayName = displayNames.getOrElse(position) { "" }

                if (selectedDisplayName == customModelOptionText) {
                    customModelInputLayout.visibility = View.VISIBLE
                } else {
                    customModelInputLayout.visibility = View.GONE
                    customModelInput.text = null
                    if (position < predefinedModels.size) {
                        currentSelectedModelIdInUI = predefinedModels[position].first
                    }
                }
                updateGenerateButtonTextBasedOnInputs()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    private fun updateGenerateButtonTextBasedOnInputs() {
        val currentState = viewModel.workflowState.value
        if (currentState == AiWorkflowState.IDLE || currentState == AiWorkflowState.ERROR || currentState == AiWorkflowState.READY_FOR_ACTION) {
            val isModifying = viewModel.isModifyingProject.value ?: false
            generateButton.text = if (isModifying) getString(R.string.button_modify_app_with_ai) else getString(R.string.button_generate_app_with_ai)
        }
    }


    private fun getSelectedModelIdFromUi(): String {
        val selectedSpinnerPosition = modelSpinner.selectedItemPosition
        val displayNames = predefinedModels.map { it.second }.toMutableList().also { it.add(customModelOptionText) }
        val selectedDisplayName = displayNames.getOrElse(selectedSpinnerPosition) { "" }

        return if (selectedDisplayName == customModelOptionText) {
            customModelInput.text.toString().trim().ifBlank { DEFAULT_GEMINI_MODEL }
        } else {
            predefinedModels.getOrNull(selectedSpinnerPosition)?.first ?: DEFAULT_GEMINI_MODEL
        }
    }

    private fun observeViewModel() {
        viewModel.workflowState.observe(this) { state ->
            updateUiForState(state)
        }
        viewModel.logOutputText.observe(this) { text ->
            logOutput.setText(text)
            if (logOutput.layout != null && logOutput.lineCount > 0) {
                val scrollAmount = logOutput.layout.getLineTop(logOutput.lineCount) - logOutput.height
                logOutput.scrollTo(0, if (scrollAmount > 0) scrollAmount else 0)
            }
        }
        viewModel.aiConclusion.observe(this) { conclusion ->
            aiConclusionOutput.setText(if (conclusion != null && conclusion.isNotBlank()) "AI Conclusion:\n$conclusion\n" else "")
        }
        viewModel.isLoading.observe(this) { isLoading ->
            loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            generateButton.isEnabled = !isLoading
        }
        viewModel.statusText.observe(this) { text ->
            statusText.text = text
        }
        viewModel.actionButtonsVisible.observe(this) { visible ->
            actionButtonsLayout.visibility = if (visible) View.VISIBLE else View.GONE
            continueButton.isEnabled = visible
            modifyFurtherButton.isEnabled = visible
        }
        viewModel.isModifyingProject.observe(this) { isModifying ->
            updateGenerateButtonTextBasedOnInputs()
        }
        viewModel.existingProjectNames.observe(this) { names ->
            val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names ?: emptyList())
            appNameAutocomplete.setAdapter(adapter)
        }
        viewModel.uiErrorEvent.observe(this) { errorMessage ->
            errorMessage?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                viewModel.consumedUiErrorEvent()
            }
        }
    }

    private fun updateUiForState(state: AiWorkflowState) {
        val isLoading = viewModel.isLoading.value ?: false
        generateButton.isEnabled = !isLoading

        val editableInputs = state == AiWorkflowState.IDLE || state == AiWorkflowState.ERROR || state == AiWorkflowState.READY_FOR_ACTION
        appNameAutocomplete.isEnabled = editableInputs
        appDescriptionInput.isEnabled = editableInputs
        apiKeyInput.isEnabled = editableInputs
        modelSpinner.isEnabled = editableInputs
        customModelInput.isEnabled = editableInputs && customModelInputLayout.visibility == View.VISIBLE
        customModelInputLayout.isEnabled = editableInputs

        val buttonTextKey = when (state) {
            AiWorkflowState.IDLE -> if (viewModel.isModifyingProject.value == true) R.string.button_modify_app_with_ai else R.string.button_generate_app_with_ai
            AiWorkflowState.ERROR -> if (viewModel.isModifyingProject.value == true) R.string.button_retry_modification else R.string.button_retry_generation
            AiWorkflowState.READY_FOR_ACTION -> if (viewModel.isModifyingProject.value == true) R.string.button_modify_again else R.string.button_generate_new_app
            else -> 0 // Will keep current text
        }
        if (buttonTextKey != 0) generateButton.text = getString(buttonTextKey)
    }
}