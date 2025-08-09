package com.itsaky.androidide.dialogs

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
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
import com.itsaky.androidide.R
import com.itsaky.androidide.dialogs.GeminiHelper.Companion.DEFAULT_GEMINI_MODEL
import java.io.File

class FileEditorActivity : AppCompatActivity() {

    companion object {
        private const val DEBUG_TAG = "AutoFixDebug"
        const val TAG_ACTIVITY = "FileEditorActivity"
        const val EXTRA_PROJECTS_BASE_DIR = "projects_base_dir"
        const val RESULT_EXTRA_PROJECT_PATH = "project_path_result"
        const val EXTRA_APP_NAME = "app_name_result"
        const val EXTRA_PREFILL_APP_NAME = "prefill_app_name"
        const val EXTRA_PREFILL_APP_DESCRIPTION = "prefill_app_description"
        const val EXTRA_IS_AUTO_RETRY_ATTEMPT = "com.itsaky.androidide.IS_AUTO_RETRY_ATTEMPT"
        const val EXTRA_ENABLE_AUTO_FIX = "com.itsaky.androidide.ENABLE_AUTO_FIX"
        const val EXTRA_INITIAL_APP_DESCRIPTION_FOR_AUTO_FIX = "com.itsaky.androidide.INITIAL_APP_DESCRIPTION_FOR_AUTO_FIX"

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
    private lateinit var autoFixCheckbox: CheckBox
    private lateinit var appVersionLayout: LinearLayout
    private lateinit var appVersionSpinner: Spinner

    private lateinit var viewModel: FileEditorViewModel
    private var thisInstanceIsOperatingInFullAuto: Boolean = false
    private var autoProceedAfterVmWorkIsPending: Boolean = false

    private val predefinedModels by lazy {
        listOf(
            "gemini-2.5-flash" to "Gemini 2.5 Flash",
            "gemini-2.5-pro" to "Gemini 2.5 Pro",
            "gpt-5" to "GPT-5",
            "gpt-5-mini" to "GPT-5 Mini",
            "gpt-5-nano" to "GPT-5 Nano"
        ).distinctBy { it.first }
    }
    private val customModelOptionText = "Enter Custom Model ID..."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_editor)
        Log.d(DEBUG_TAG, "$TAG_ACTIVITY: onCreate START")

        supportActionBar?.title = "AI App Generator / Modifier"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        viewModel = ViewModelProvider(this)[FileEditorViewModel::class.java]
        viewModel.projectsBaseDir = File(intent.getStringExtra(EXTRA_PROJECTS_BASE_DIR) ?: filesDir.absolutePath)

        bindViews()
        setupInitialUIValues()

        val prefillAppName = intent.getStringExtra(EXTRA_PREFILL_APP_NAME)
        val prefillAppDescription = intent.getStringExtra(EXTRA_PREFILL_APP_DESCRIPTION)
        val isThisAnIncomingAutoRetry = intent.getBooleanExtra(EXTRA_IS_AUTO_RETRY_ATTEMPT, false)

        if (prefillAppName != null) appNameAutocomplete.setText(prefillAppName)
        if (prefillAppDescription != null) appDescriptionInput.setText(prefillAppDescription)

        if (isThisAnIncomingAutoRetry) {
            autoFixCheckbox.isChecked = true
            autoFixCheckbox.isEnabled = false
            thisInstanceIsOperatingInFullAuto = true
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) performGenerateOrFinalizeAction()
            }, 750)
        } else {
            autoFixCheckbox.isChecked = AutoFixStateManager.isAutoFixModeGloballyActive
            autoFixCheckbox.isEnabled = true
            thisInstanceIsOperatingInFullAuto = false
        }

        setupListeners()
        observeViewModel()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.isLoading.value == true) {
                    Toast.makeText(this@FileEditorActivity, "Processing... Please wait.", Toast.LENGTH_SHORT).show()
                } else {
                    val isCurrentlyAnAutoRetry = intent.getBooleanExtra(EXTRA_IS_AUTO_RETRY_ATTEMPT, false)
                    if (!isCurrentlyAnAutoRetry && AutoFixStateManager.isAutoFixModeGloballyActive) {
                        AutoFixStateManager.disableAutoFixMode()
                    }
                    thisInstanceIsOperatingInFullAuto = false
                    setResult(Activity.RESULT_CANCELED)
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        Log.d(DEBUG_TAG, "$TAG_ACTIVITY: onCreate END")
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
        autoFixCheckbox = findViewById(R.id.auto_fix_checkbox)
        appVersionLayout = findViewById(R.id.app_version_layout)
        appVersionSpinner = findViewById(R.id.app_version_spinner)

        appDescriptionInput.movementMethod = ScrollingMovementMethod.getInstance()
        logOutput.movementMethod = ScrollingMovementMethod.getInstance()
        logOutput.keyListener = null
        aiConclusionOutput.movementMethod = ScrollingMovementMethod.getInstance()
        aiConclusionOutput.keyListener = null
    }

    private fun setupInitialUIValues() {
        apiKeyInput.setText(viewModel.storedApiKey)
        setupModelSpinnerInternal(viewModel.storedModelId)
        val initialModelId = getSelectedModelIdFromUi()
        updateModelBrandingForSelection(initialModelId, selectedDisplayName = null)
    }

    private fun setupModelSpinnerInternal(initialModelId: String) {
        val displayNames = predefinedModels.map { it.second }.toMutableList().also { it.add(customModelOptionText) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        modelSpinner.adapter = adapter
        val effectiveInitialModelId = initialModelId.ifBlank { DEFAULT_GEMINI_MODEL }
        val predefinedIndex = predefinedModels.indexOfFirst { it.first == effectiveInitialModelId }
        if (predefinedIndex != -1) {
            modelSpinner.setSelection(predefinedIndex, false)
            customModelInputLayout.visibility = View.GONE
            customModelInput.text = null
        } else {
            val customIndex = displayNames.indexOf(customModelOptionText)
            modelSpinner.setSelection(if (customIndex != -1) customIndex else 0, false)
            customModelInputLayout.visibility = View.VISIBLE
            customModelInput.setText(effectiveInitialModelId)
        }
    }

    private fun updateModelBrandingForSelection(modelId: String, selectedDisplayName: String?) {
        val isGpt = selectedDisplayName?.startsWith("GPT", ignoreCase = true)
            ?: modelId.startsWith("gpt-", ignoreCase = true)

        findViewById<TextInputLayout>(R.id.api_key_layout)?.hint =
            if (isGpt) "OpenAI API Key" else "Gemini API Key"

        findViewById<TextView?>(R.id.model_label)?.text =
            if (isGpt) "OpenAI Model" else "Gemini Model"

        customModelInputLayout.hint =
            if (isGpt) "Custom OpenAI Model ID" else "Custom Gemini Model ID"
    }

    private fun performGenerateOrFinalizeAction(isTriggeredForImmediateReturn: Boolean = false) {
        val appName = appNameAutocomplete.text.toString().trim()
        val currentDescription = appDescriptionInput.text.toString().trim()
        val apiKey = apiKeyInput.text.toString().trim()
        val selectedModelId = getSelectedModelIdFromUi()
        val isThisAnIncomingAutoRetryIntent = intent.getBooleanExtra(EXTRA_IS_AUTO_RETRY_ATTEMPT, false)

        if (!isTriggeredForImmediateReturn) {
            var isValid = true
            if (appName.isBlank()) { (appNameAutocomplete.parent.parent as? TextInputLayout)?.error = "App name required"; isValid = false }
            else { (appNameAutocomplete.parent.parent as? TextInputLayout)?.error = null }

            if (apiKey.isBlank()) { apiKeyInput.error = "API Key required"; isValid = false }
            else { apiKeyInput.error = null }

            if (currentDescription.isBlank()) {
                (appDescriptionInput.parent.parent as? TextInputLayout)?.error = "App description required."
                isValid = false
            } else { (appDescriptionInput.parent.parent as? TextInputLayout)?.error = null }

            if (!isValid) {
                Toast.makeText(this, "Please fill all required fields.", Toast.LENGTH_SHORT).show()
                if (autoFixCheckbox.isChecked) AutoFixStateManager.disableAutoFixMode()
                thisInstanceIsOperatingInFullAuto = false
                return
            }

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(generateButton.windowToken, 0)
        }

        viewModel.saveApiKey(apiKey)
        viewModel.saveSelectedModel(selectedModelId)

        if (!isTriggeredForImmediateReturn) {
            if (!isThisAnIncomingAutoRetryIntent) {
                if (autoFixCheckbox.isChecked) {
                    if (currentDescription.isNotBlank()) {
                        AutoFixStateManager.enableAutoFixMode(currentDescription)
                        thisInstanceIsOperatingInFullAuto = true
                    } else {
                        Toast.makeText(this, "App description needed to enable auto-fix.", Toast.LENGTH_LONG).show()
                        AutoFixStateManager.disableAutoFixMode()
                        thisInstanceIsOperatingInFullAuto = false
                        return
                    }
                } else {
                    AutoFixStateManager.disableAutoFixMode()
                    thisInstanceIsOperatingInFullAuto = false
                }
            }
            val selectedVersion = if (appVersionLayout.visibility == View.VISIBLE && appVersionSpinner.selectedItem != null) {
                val selectedItemStr = appVersionSpinner.selectedItem.toString()
                if (selectedItemStr.startsWith("Latest")) null else selectedItemStr
            } else null

            viewModel.initiateWorkflow(appName, currentDescription, apiKey, selectedModelId, selectedVersion)

        } else {
            val resultIntent = Intent()
            resultIntent.putExtra(EXTRA_APP_NAME, appName)
            viewModel.currentProjectDirVM.value?.let {
                resultIntent.putExtra(RESULT_EXTRA_PROJECT_PATH, it.absolutePath)
            }
            if (isThisAnIncomingAutoRetryIntent) {
                resultIntent.putExtra(EXTRA_IS_AUTO_RETRY_ATTEMPT, true)
            }
            resultIntent.putExtra(EXTRA_ENABLE_AUTO_FIX, AutoFixStateManager.isAutoFixModeGloballyActive)
            AutoFixStateManager.initialAppDescriptionForGlobalAutoFix?.let { desc ->
                if (AutoFixStateManager.isAutoFixModeGloballyActive) {
                    resultIntent.putExtra(EXTRA_INITIAL_APP_DESCRIPTION_FOR_AUTO_FIX, desc)
                }
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun setupListeners() {
        generateButton.setOnClickListener {
            performGenerateOrFinalizeAction()
        }
        autoFixCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (autoFixCheckbox.isEnabled) {
                val currentDesc = appDescriptionInput.text.toString().trim()
                if (isChecked) {
                    if (currentDesc.isNotBlank()) {
                        AutoFixStateManager.enableAutoFixMode(currentDesc)
                    } else {
                        Toast.makeText(this, "Please enter an app description to enable auto-fix.", Toast.LENGTH_SHORT).show()
                        autoFixCheckbox.isChecked = false
                        AutoFixStateManager.disableAutoFixMode()
                    }
                } else {
                    AutoFixStateManager.disableAutoFixMode()
                }
            }
        }
        continueButton.setOnClickListener {
            if (AutoFixStateManager.isAutoFixModeGloballyActive) {
                AutoFixStateManager.disableAutoFixMode()
            }
            thisInstanceIsOperatingInFullAuto = false
            if (viewModel.isLoading.value == false && viewModel.actionButtonsVisible.value == true && viewModel.currentProjectDirVM.value != null) {
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_APP_NAME, appNameAutocomplete.text.toString().trim())
                    putExtra(RESULT_EXTRA_PROJECT_PATH, viewModel.currentProjectDirVM.value!!.absolutePath)
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            } else {
                Toast.makeText(this, "Not ready to continue or project path missing.", Toast.LENGTH_SHORT).show()
            }
        }
        modifyFurtherButton.setOnClickListener {
            if (AutoFixStateManager.isAutoFixModeGloballyActive) {
                AutoFixStateManager.disableAutoFixMode()
            }
            thisInstanceIsOperatingInFullAuto = false
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        appNameAutocomplete.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val enteredName = s.toString().trim()
                val isExisting = enteredName.isNotEmpty() && (viewModel.existingProjectNames.value?.contains(enteredName) == true)
                viewModel.updateIsModifyingProjectFlag(isExisting)
                if (!isExisting) viewModel.onProjectNameClearedOrNew()
            }
        })
        appNameAutocomplete.setOnItemClickListener { _, _, _, _ ->
            val selectedAppName = appNameAutocomplete.text.toString()
            viewModel.updateIsModifyingProjectFlag(true)
            viewModel.onProjectNameSelected(selectedAppName)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(appNameAutocomplete.windowToken, 0)
            appNameAutocomplete.clearFocus()
        }
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val displayNames = predefinedModels.map { it.second }.toMutableList().also { it.add(customModelOptionText) }
                val selectedDisplayName = displayNames.getOrElse(position) { "" }
                val selectedModelIdNow = getSelectedModelIdFromUi()
                updateModelBrandingForSelection(selectedModelIdNow, selectedDisplayName)

                if (selectedDisplayName == customModelOptionText) {
                    customModelInputLayout.visibility = View.VISIBLE
                } else {
                    customModelInputLayout.visibility = View.GONE
                    customModelInput.text = null
                }
                updateGenerateButtonTextAndRole()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateGenerateButtonTextAndRole() {
        val isModifying = viewModel.isModifyingProject.value ?: false
        val isLoading = viewModel.isLoading.value ?: false
        val vmState = viewModel.workflowState.value ?: AiWorkflowState.IDLE

        if (isLoading) {
            generateButton.text = "Processing..."
            return
        }
        if (actionButtonsLayout.visibility == View.VISIBLE) {
            generateButton.text = if (isModifying) getString(R.string.button_modify_again) else getString(R.string.button_generate_new_app)
        } else {
            when (vmState) {
                AiWorkflowState.IDLE, AiWorkflowState.ERROR -> {
                    generateButton.text = if (isModifying) getString(R.string.button_modify_app_with_ai) else getString(R.string.button_generate_app_with_ai)
                }
                AiWorkflowState.READY_FOR_ACTION -> {
                    generateButton.text = if (isModifying) getString(R.string.button_apply_modifications_and_build) else getString(R.string.button_build_generated_app)
                }
                else -> generateButton.text = "Processing..."
            }
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
        viewModel.versionsUiVisible.observe(this) { isVisible ->
            appVersionLayout.visibility = if (isVisible) View.VISIBLE else View.GONE
            val isLoading = viewModel.isLoading.value ?: false
            appVersionSpinner.isEnabled = !isLoading && isVisible
        }

        viewModel.projectVersions.observe(this) { versions ->
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, versions ?: emptyList())
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            appVersionSpinner.adapter = adapter
        }

        viewModel.workflowState.observe(this) { state ->
            updateUiBasedOnState(state)
            val isReady = state == AiWorkflowState.READY_FOR_ACTION
            val notLoading = viewModel.isLoading.value == false
            if (isReady && notLoading && thisInstanceIsOperatingInFullAuto && AutoFixStateManager.isAutoFixModeGloballyActive && !autoProceedAfterVmWorkIsPending) {
                autoProceedAfterVmWorkIsPending = true
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isFinishing && !isDestroyed && thisInstanceIsOperatingInFullAuto && AutoFixStateManager.isAutoFixModeGloballyActive) {
                        performGenerateOrFinalizeAction(isTriggeredForImmediateReturn = true)
                    }
                    autoProceedAfterVmWorkIsPending = false
                }, 300)
            }
        }

        viewModel.isLoading.observe(this) { isLoading ->
            loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            generateButton.isEnabled = !isLoading
            val enableInputs = !isLoading
            appNameAutocomplete.isEnabled = enableInputs
            appDescriptionInput.isEnabled = enableInputs
            apiKeyInput.isEnabled = enableInputs
            modelSpinner.isEnabled = enableInputs
            appVersionSpinner.isEnabled = enableInputs && appVersionLayout.visibility == View.VISIBLE
            customModelInputLayout.isEnabled = enableInputs && customModelInputLayout.visibility == View.VISIBLE
            customModelInput.isEnabled = enableInputs && customModelInputLayout.visibility == View.VISIBLE

            autoFixCheckbox.isEnabled = enableInputs && !intent.getBooleanExtra(EXTRA_IS_AUTO_RETRY_ATTEMPT, false)

            val vmState = viewModel.workflowState.value
            val isReadyForActionFromVm = vmState == AiWorkflowState.READY_FOR_ACTION
            val showActionButtons = isReadyForActionFromVm && !isLoading
            actionButtonsLayout.visibility = if (showActionButtons) View.VISIBLE else View.GONE
            continueButton.isEnabled = showActionButtons
            modifyFurtherButton.isEnabled = showActionButtons

            if (isLoading) {
                generateButton.text = "Processing..."
            } else {
                updateGenerateButtonTextAndRole()
            }
        }

        viewModel.logOutputText.observe(this) { text ->
            logOutput.setText(text)
            if (logOutput.layout != null && logOutput.lineCount > 0) {
                val scrollAmount = logOutput.layout.getLineTop(logOutput.lineCount) - logOutput.height
                if (scrollAmount > 0) logOutput.scrollTo(0, scrollAmount) else logOutput.scrollTo(0, 0)
            }
        }
        viewModel.aiConclusion.observe(this) { conclusion ->
            val fullConclusionText = if (conclusion != null && conclusion.isNotBlank()) "AI Conclusion:\n$conclusion\n" else ""
            aiConclusionOutput.setText(fullConclusionText)
            (aiConclusionOutput.parent.parent as? View)?.visibility = if (fullConclusionText.isBlank()) View.GONE else View.VISIBLE
        }
        viewModel.statusText.observe(this) { text -> statusText.text = text }
        viewModel.actionButtonsVisible.observe(this) { visible ->
            val isLoadingVal = viewModel.isLoading.value ?: false
            val effectiveVisibility = if (visible && !isLoadingVal) View.VISIBLE else View.GONE
            if (actionButtonsLayout.visibility != effectiveVisibility) {
                actionButtonsLayout.visibility = effectiveVisibility
            }
            continueButton.isEnabled = visible && !isLoadingVal
            modifyFurtherButton.isEnabled = visible && !isLoadingVal
            updateGenerateButtonTextAndRole()
        }
        viewModel.isModifyingProject.observe(this) { updateGenerateButtonTextAndRole() }
        viewModel.existingProjectNames.observe(this) { names ->
            val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names ?: emptyList())
            appNameAutocomplete.setAdapter(adapter)
        }
        viewModel.uiErrorEvent.observe(this) { errorMessage ->
            errorMessage?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                thisInstanceIsOperatingInFullAuto = false
                AutoFixStateManager.disableAutoFixMode()
                viewModel.consumedUiErrorEvent()
            }
        }
    }

    private fun updateUiBasedOnState(state: AiWorkflowState) {
        val isLoadingVal = viewModel.isLoading.value ?: false
        if (!isLoadingVal) updateGenerateButtonTextAndRole()
        val showActionButtons = state == AiWorkflowState.READY_FOR_ACTION && !isLoadingVal
        if (actionButtonsLayout.visibility != if (showActionButtons) View.VISIBLE else View.GONE) {
            actionButtonsLayout.visibility = if (showActionButtons) View.VISIBLE else View.GONE
        }
        continueButton.isEnabled = showActionButtons
        modifyFurtherButton.isEnabled = showActionButtons
    }
}