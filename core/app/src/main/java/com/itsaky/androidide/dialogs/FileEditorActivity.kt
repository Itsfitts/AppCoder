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
import android.view.MenuItem
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
import java.io.File
// DEFAULT_GEMINI_MODEL is defined in GeminiHelper.kt

class FileEditorActivity : AppCompatActivity() {

    companion object {
        const val TAG_ACTIVITY = "FileEditorActivity"
        const val EXTRA_PROJECTS_BASE_DIR = "projects_base_dir"
        const val RESULT_EXTRA_PROJECT_PATH = "project_path_result" // Used by EditorHandlerActivity
        const val EXTRA_APP_NAME = "app_name_result"
        const val EXTRA_PREFILL_APP_NAME = "prefill_app_name"
        const val EXTRA_PREFILL_APP_DESCRIPTION = "prefill_app_description"
        const val EXTRA_ENABLE_AUTO_FIX = "com.itsaky.androidide.ENABLE_AUTO_FIX"
        const val EXTRA_INITIAL_APP_DESCRIPTION_FOR_AUTO_FIX = "com.itsaky.androidide.INITIAL_APP_DESCRIPTION_FOR_AUTO_FIX"
        const val EXTRA_IS_AUTO_RETRY_ATTEMPT = "com.itsaky.androidide.IS_AUTO_RETRY_ATTEMPT"

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

    private lateinit var viewModel: FileEditorViewModel
    private var thisInstanceIsOperatingInFullAuto: Boolean = false
    private var autoProceedAfterVmWorkIsPending: Boolean = false

    private val predefinedModels by lazy {
        listOf(
            DEFAULT_GEMINI_MODEL to "Gemini 2.5 Flash (Default - Preview 05-20)",
            "gemini-1.5-flash-latest" to "Gemini 1.5 Flash (Stable)",
            "gemini-1.5-pro-latest" to "Gemini 1.5 Pro (Stable)",
            "gemini-2.5-pro-preview-05-06" to "Gemini 2.5 Pro (Preview 05-06)"
        ).distinctBy { it.first }
    }
    private val customModelOptionText = "Enter Custom Model ID..."

    private fun logIntentExtras(tag: String, intent: Intent?) {
        if (intent == null) { Log.d(tag, "Intent is null for logging extras."); return }
        val extras = intent.extras
        if (extras == null || extras.isEmpty) { Log.d(tag, "Intent has no extras for logging."); return }
        val stringBuilder = StringBuilder("Intent Extras (logging):\n")
        for (key in extras.keySet()) {
            stringBuilder.append("  Key: ").append(key).append(", Value: ").append(extras.get(key)).append("\n")
        }
        Log.d(tag, stringBuilder.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_editor)

        supportActionBar?.title = "AI App Generator / Modifier"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        viewModel = ViewModelProvider(this)[FileEditorViewModel::class.java]
        viewModel.projectsBaseDir = File(intent.getStringExtra(EXTRA_PROJECTS_BASE_DIR) ?: filesDir.absolutePath)

        bindViews()
        setupInitialUIValues()

        val prefillAppName = intent.getStringExtra(EXTRA_PREFILL_APP_NAME)
        val prefillAppDescription = intent.getStringExtra(EXTRA_PREFILL_APP_DESCRIPTION)
        val isThisAnIncomingAutoRetry = intent.getBooleanExtra(EXTRA_IS_AUTO_RETRY_ATTEMPT, false)

        if (prefillAppName != null) { appNameAutocomplete.setText(prefillAppName) }
        if (prefillAppDescription != null) { appDescriptionInput.setText(prefillAppDescription) }

        if (isThisAnIncomingAutoRetry) {
            Log.i(TAG_ACTIVITY, "onCreate: Instance IS an INCOMING AUTO-RETRY.")
            thisInstanceIsOperatingInFullAuto = true // This session will be fully automated
            autoFixCheckbox.isChecked = true
            autoFixCheckbox.isEnabled = false // User cannot change this during an auto-retry

            // Delay to allow UI to settle and ViewModel to be ready
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    val appName = appNameAutocomplete.text.toString().trim()
                    val descriptionWithFailure = appDescriptionInput.text.toString().trim() // This has the error
                    val apiKey = apiKeyInput.text.toString().trim() // Should be pre-filled and valid
                    val modelId = getSelectedModelIdFromUi()        // Should be pre-filled

                    if (appName.isNotBlank() && descriptionWithFailure.isNotBlank() && apiKey.isNotBlank()) {
                        Log.d(TAG_ACTIVITY, "Auto-retry (onCreate): Triggering ViewModel AI workflow to attempt fix for app: '$appName'.")
                        // The descriptionWithFailure contains the original prompt + the build error.
                        viewModel.initiateWorkflow(appName, descriptionWithFailure, apiKey, modelId)
                        // The ViewModel's observers (for isLoading/workflowState) combined with
                        // `thisInstanceIsOperatingInFullAuto = true` will then automatically call
                        // `performGenerateOrFinalizeAction(isTriggeredForImmediateReturn = true)`
                        // once the AI work is done and the ViewModel state is READY_FOR_ACTION.
                    } else {
                        Log.e(TAG_ACTIVITY, "Auto-retry (onCreate): Cannot trigger AI workflow due to missing critical fields. AppName: '$appName', Desc provided: ${descriptionWithFailure.isNotBlank()}, APIKey provided: ${apiKey.isNotBlank()}. Aborting auto-retry.")
                        Toast.makeText(this@FileEditorActivity, "Auto-retry failed: missing inputs for AI.", Toast.LENGTH_LONG).show()
                        setResult(Activity.RESULT_CANCELED) // Signal failure of this attempt
                        finish()
                    }
                }
            }, 750) // 750ms delay
        } else {
            Log.i(TAG_ACTIVITY, "onCreate: Instance is NOT an incoming auto-retry.")
            // thisInstanceIsOperatingInFullAuto will be set if user ticks the box and clicks "Generate"
        }

        setupListeners()
        observeViewModel()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.isLoading.value == true) { Toast.makeText(this@FileEditorActivity, "Processing... Please wait.", Toast.LENGTH_SHORT).show() }
                else {
                    Log.d(TAG_ACTIVITY, "Back pressed, setting RESULT_CANCELED.")
                    thisInstanceIsOperatingInFullAuto = false
                    setResult(Activity.RESULT_CANCELED)
                    isEnabled = false; onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (viewModel.isLoading.value == true) { Toast.makeText(this@FileEditorActivity, "Processing... Please wait.", Toast.LENGTH_SHORT).show(); return true }
            Log.d(TAG_ACTIVITY, "Home button, setting RESULT_CANCELED.")
            thisInstanceIsOperatingInFullAuto = false
            setResult(Activity.RESULT_CANCELED)
            finish()
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
        autoFixCheckbox = findViewById(R.id.auto_fix_checkbox)

        appDescriptionInput.movementMethod = ScrollingMovementMethod.getInstance()
        logOutput.movementMethod = ScrollingMovementMethod.getInstance()
        logOutput.keyListener = null // Make it non-editable
        aiConclusionOutput.movementMethod = ScrollingMovementMethod.getInstance()
        aiConclusionOutput.keyListener = null // Make it non-editable
    }

    private fun setupInitialUIValues() {
        apiKeyInput.setText(viewModel.storedApiKey)
        setupModelSpinnerInternal(viewModel.storedModelId)
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

    private fun performGenerateOrFinalizeAction(isTriggeredForImmediateReturn: Boolean = false) {
        val appName = appNameAutocomplete.text.toString().trim()
        val currentDescription = appDescriptionInput.text.toString().trim()
        val apiKey = apiKeyInput.text.toString().trim()
        val selectedModelId = getSelectedModelIdFromUi()
        val isThisAnIncomingAutoRetryIntent = intent.getBooleanExtra(EXTRA_IS_AUTO_RETRY_ATTEMPT, false)

        Log.d(TAG_ACTIVITY, "performGenerateOrFinalizeAction: isTriggeredForImmediateReturn=$isTriggeredForImmediateReturn, isThisAnIncomingAutoRetryIntent=$isThisAnIncomingAutoRetryIntent, thisInstanceIsOperatingInFullAuto=$thisInstanceIsOperatingInFullAuto")

        if (!isTriggeredForImmediateReturn) {
            var isValid = true
            if (appName.isBlank()) { (appNameAutocomplete.parent.parent as? TextInputLayout)?.error = "App name required"; isValid = false }
            else { (appNameAutocomplete.parent.parent as? TextInputLayout)?.error = null }

            if (apiKey.isBlank()) { apiKeyInput.error = "API Key required"; isValid = false }
            else { apiKeyInput.error = null }

            if (currentDescription.isBlank() && (autoFixCheckbox.isChecked || viewModel.isModifyingProject.value == false )) {
                (appDescriptionInput.parent.parent as? TextInputLayout)?.error = "App description required."
                isValid = false
            } else { (appDescriptionInput.parent.parent as? TextInputLayout)?.error = null }

            if (!isValid) {
                Toast.makeText(this, "Please fill all required fields.", Toast.LENGTH_SHORT).show()
                thisInstanceIsOperatingInFullAuto = false // Stop auto mode if validation fails
                return
            }
        }

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(generateButton.windowToken, 0)

        viewModel.saveApiKey(apiKey)
        viewModel.saveSelectedModel(selectedModelId)

        // If this is NOT for immediate return AND it's NOT an incoming auto-retry intent,
        // it means the user clicked "Generate App / Modify App" to start the ViewModel's AI work.
        if (!isTriggeredForImmediateReturn && !isThisAnIncomingAutoRetryIntent) {
            Log.i(TAG_ACTIVITY, "User click is initiating AI workflow via ViewModel for app: $appName")
            if (autoFixCheckbox.isChecked) { // User wants this whole session to be automated
                thisInstanceIsOperatingInFullAuto = true
                Log.i(TAG_ACTIVITY, "thisInstanceIsOperatingInFullAuto SET to TRUE (user initiated full auto).")
            } else {
                thisInstanceIsOperatingInFullAuto = false
            }
            viewModel.initiateWorkflow(appName, currentDescription, apiKey, selectedModelId)
            // Activity STAYS OPEN. The observers will handle auto-proceeding if thisInstanceIsOperatingInFullAuto is true.
        }
        // Otherwise (it IS for immediate return OR it IS an incoming auto-retry which implies immediate return after prefill),
        // finalize and return to EditorHandlerActivity.
        else {
            Log.i(TAG_ACTIVITY, "Action is to finalize and return RESULT_OK to EditorHandlerActivity. isTriggeredForImmediateReturn: $isTriggeredForImmediateReturn")
            val resultIntent = Intent()
            resultIntent.putExtra(EXTRA_APP_NAME, appName)
            resultIntent.putExtra(EXTRA_ENABLE_AUTO_FIX, autoFixCheckbox.isChecked) // This reflects user's overall intent

            // *** ADDED FIX: Include project path ***
            if (viewModel.currentProjectDirVM.value != null) {
                resultIntent.putExtra(RESULT_EXTRA_PROJECT_PATH, viewModel.currentProjectDirVM.value!!.absolutePath)
                Log.d(TAG_ACTIVITY, "Added project path for auto-finalize: ${viewModel.currentProjectDirVM.value!!.absolutePath}")
            } else {
                Log.w(TAG_ACTIVITY, "Project path is null in ViewModel during auto-finalize for app: $appName. Build might fail to locate project if it's new.")
                // If it's an incoming auto retry, project path MUST exist. If not, something is very wrong.
                if (isThisAnIncomingAutoRetryIntent) {
                    Toast.makeText(this, "Error: Project path lost during auto-retry.", Toast.LENGTH_LONG).show()
                    setResult(Activity.RESULT_CANCELED) // Critical failure
                    finish()
                    return
                }
            }
            // *** END OF ADDED FIX ***

            if (autoFixCheckbox.isChecked && !isThisAnIncomingAutoRetryIntent) { // For the very first automated run initiated by user
                resultIntent.putExtra(EXTRA_INITIAL_APP_DESCRIPTION_FOR_AUTO_FIX, currentDescription)
            }
            if (isThisAnIncomingAutoRetryIntent) { // For subsequent automated retries
                resultIntent.putExtra(EXTRA_IS_AUTO_RETRY_ATTEMPT, true)
            }

            logIntentExtras(TAG_ACTIVITY, resultIntent)
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun setupListeners() {
        generateButton.setOnClickListener {
            performGenerateOrFinalizeAction()
        }

        continueButton.setOnClickListener {
            Log.d(TAG_ACTIVITY, "'Continue to Build' clicked by user (manual intervention).")
            thisInstanceIsOperatingInFullAuto = false // User is manually clicking "Continue"

            if (viewModel.isLoading.value == false && viewModel.actionButtonsVisible.value == true && viewModel.currentProjectDirVM.value != null) {
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_APP_NAME, appNameAutocomplete.text.toString().trim())
                    putExtra(RESULT_EXTRA_PROJECT_PATH, viewModel.currentProjectDirVM.value!!.absolutePath)
                }
                if (intent.getBooleanExtra(EXTRA_IS_AUTO_RETRY_ATTEMPT, false)) {
                    resultIntent.putExtra(EXTRA_IS_AUTO_RETRY_ATTEMPT, true)
                }
                // Even if user clicks continue, pass the checkbox state. EditorHandler will decide if auto-fix loop continues.
                resultIntent.putExtra(EXTRA_ENABLE_AUTO_FIX, autoFixCheckbox.isChecked)

                Log.i(TAG_ACTIVITY, "Continue button: Setting RESULT_OK and finishing.")
                logIntentExtras(TAG_ACTIVITY, resultIntent)
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            } else {
                Toast.makeText(this, "Not ready to continue or project path missing.", Toast.LENGTH_SHORT).show()
            }
        }

        modifyFurtherButton.setOnClickListener {
            Log.d(TAG_ACTIVITY, "'Modify Further (Dismiss)' clicked by user.")
            thisInstanceIsOperatingInFullAuto = false // Manual intervention
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
            }
        })

        appNameAutocomplete.setOnItemClickListener { _, _, _, _ ->
            viewModel.updateIsModifyingProjectFlag(true) // When selected from dropdown, it's an existing project
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
                }
                updateGenerateButtonTextAndRole() // Update button text based on model selection or other states
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

        if (actionButtonsLayout.visibility == View.VISIBLE) { // AI work done, action buttons visible
            generateButton.text = if (isModifying) getString(R.string.button_modify_again) else getString(R.string.button_generate_new_app)
        } else { // AI work not done or in error state
            when (vmState) {
                AiWorkflowState.IDLE, AiWorkflowState.ERROR -> {
                    generateButton.text = if (isModifying) getString(R.string.button_modify_app_with_ai) else getString(R.string.button_generate_app_with_ai)
                }
                AiWorkflowState.READY_FOR_ACTION -> { // This case might be covered by actionButtonsLayout.visibility, but as a fallback
                    generateButton.text = if (isModifying) getString(R.string.button_apply_modifications_and_build) else getString(R.string.button_build_generated_app)
                }
                else -> generateButton.text = "Processing..." // Default for other loading states
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
        viewModel.workflowState.observe(this) { state ->
            Log.d(TAG_ACTIVITY, "VM State observed: $state. thisInstanceIsOperatingInFullAuto: $thisInstanceIsOperatingInFullAuto")
            updateUiBasedOnState(state)

            // Auto-proceed after INITIAL generation if full-auto was selected
            if (thisInstanceIsOperatingInFullAuto &&
                state == AiWorkflowState.READY_FOR_ACTION &&
                viewModel.isLoading.value == false &&
                !intent.getBooleanExtra(EXTRA_IS_AUTO_RETRY_ATTEMPT, false) // Only for initial user-triggered generation in full-auto
            ) {
                Log.i(TAG_ACTIVITY, "Fully automated session: VM is READY & not loading (Initial Gen Complete). Auto-triggering finalize.")
                if (!autoProceedAfterVmWorkIsPending) {
                    autoProceedAfterVmWorkIsPending = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isFinishing && !isDestroyed && thisInstanceIsOperatingInFullAuto) { // Re-check flag
                            performGenerateOrFinalizeAction(isTriggeredForImmediateReturn = true)
                        }
                        autoProceedAfterVmWorkIsPending = false
                    }, 300)
                }
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
            customModelInputLayout.isEnabled = enableInputs && customModelInputLayout.visibility == View.VISIBLE
            customModelInput.isEnabled = enableInputs && customModelInputLayout.visibility == View.VISIBLE

            // Auto-fix checkbox should be disabled if an auto-retry is in progress OR if loading
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
                // Auto-proceed if loading just finished, state is ready, and it's an initial full-auto generation
                // This also covers cases where it's an auto-retry that just finished its AI work.
                if (thisInstanceIsOperatingInFullAuto &&
                    isReadyForActionFromVm && // VM says AI work is done
                    !autoProceedAfterVmWorkIsPending // Prevent re-trigger from workflowState observer
                ) {
                    Log.i(TAG_ACTIVITY, "Fully automated session: isLoading became false, state is READY. Auto-triggering finalize. IsAutoRetry: ${intent.getBooleanExtra(EXTRA_IS_AUTO_RETRY_ATTEMPT, false)}")
                    autoProceedAfterVmWorkIsPending = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isFinishing && !isDestroyed && thisInstanceIsOperatingInFullAuto) {
                            performGenerateOrFinalizeAction(isTriggeredForImmediateReturn = true)
                        }
                        autoProceedAfterVmWorkIsPending = false
                    }, 300)
                }
            }
        }

        viewModel.logOutputText.observe(this) { text ->
            logOutput.setText(text)
            // Auto-scroll log
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
            val isLoading = viewModel.isLoading.value ?: false
            val effectiveVisibility = if (visible && !isLoading) View.VISIBLE else View.GONE
            if (actionButtonsLayout.visibility != effectiveVisibility) {
                actionButtonsLayout.visibility = effectiveVisibility
            }
            continueButton.isEnabled = visible && !isLoading
            modifyFurtherButton.isEnabled = visible && !isLoading
            updateGenerateButtonTextAndRole() // Ensure main button text is also updated
        }

        viewModel.isModifyingProject.observe(this) { isModifying ->
            updateGenerateButtonTextAndRole()
        }

        viewModel.existingProjectNames.observe(this) { names ->
            val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names ?: emptyList())
            appNameAutocomplete.setAdapter(adapter)
        }

        viewModel.uiErrorEvent.observe(this) { errorMessage ->
            errorMessage?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                thisInstanceIsOperatingInFullAuto = false // Stop full automation on error from VM
                viewModel.consumedUiErrorEvent() // ViewModel should have a method to clear the event
            }
        }
    }

    private fun updateUiBasedOnState(state: AiWorkflowState) {
        val isLoading = viewModel.isLoading.value ?: false
        // Update the main "Generate/Modify" button text and enabled state
        if (!isLoading) { // Only update text if not loading, as isLoading observer handles "Processing..."
            updateGenerateButtonTextAndRole()
        }
        // This ensures action buttons ("Continue to Build", "Modify Further") are shown
        // ONLY when VM says it's ready AND not loading.
        val showActionButtons = state == AiWorkflowState.READY_FOR_ACTION && !isLoading
        if (actionButtonsLayout.visibility != if(showActionButtons) View.VISIBLE else View.GONE) {
            actionButtonsLayout.visibility = if (showActionButtons) View.VISIBLE else View.GONE
        }
        continueButton.isEnabled = showActionButtons
        modifyFurtherButton.isEnabled = showActionButtons
    }
}