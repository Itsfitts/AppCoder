package com.itsaky.androidide.activities

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.isVisible
import androidx.transition.TransitionManager
import androidx.transition.doOnEnd
import com.google.android.material.transition.MaterialSharedAxis
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.editor.EditorActivityKt
import com.itsaky.androidide.app.EdgeToEdgeIDEActivity
import com.itsaky.androidide.databinding.ActivityMainBinding
import com.itsaky.androidide.dialogs.AutoFixStateManager
import com.itsaky.androidide.dialogs.FileEditorActivity
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.templates.ITemplateProvider
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.viewmodel.MainViewModel
import java.io.File

class MainActivity : EdgeToEdgeIDEActivity() {

    private val viewModel by viewModels<MainViewModel>()
    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!
    private val STORAGE_PERMISSION_REQUEST_CODE = 101

    private lateinit var fileEditorResultLauncher: ActivityResultLauncher<Intent>

    // Payload for launching AI editor routed from EditorHandlerActivity
    private data class AiLaunchPayload(
        val projectsRootOverride: String?,
        val prefillName: String?,
        val prefillDescription: String?,
        val isAutoRetry: Boolean
    )
    private var pendingAiLaunchPayload: AiLaunchPayload? = null

    // Keys used for routing Editor -> Main -> FileEditorActivity
    companion object {
        private const val TAG = "MainActivity"
        private const val EXTRA_REQUEST_AI_EDITOR = "com.itsaky.androidide.REQUEST_AI_EDITOR"
        private const val EXTRA_AI_PROJECTS_ROOT_OVERRIDE = "com.itsaky.androidide.AI_PROJECTS_ROOT_OVERRIDE"
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            viewModel.apply {
                if (creatingProject.value == true) return@apply
                val newScreen = when (currentScreen.value) {
                    MainViewModel.SCREEN_TEMPLATE_DETAILS -> MainViewModel.SCREEN_TEMPLATE_LIST
                    MainViewModel.SCREEN_TEMPLATE_LIST -> MainViewModel.SCREEN_MAIN
                    else -> MainViewModel.SCREEN_MAIN
                }
                if (currentScreen.value != newScreen) {
                    setScreen(newScreen)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // _binding is initialized by super.onCreate -> bindLayout()

        fileEditorResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d(TAG, "FileEditorActivity result received. ResultCode: ${result.resultCode}")

            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                if (data == null) {
                    Log.e(TAG, "FileEditorActivity returned OK but the result Intent was null.")
                    Toast.makeText(this, "Error: Received an empty result.", Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }

                val projectPath = data.getStringExtra(FileEditorActivity.RESULT_EXTRA_PROJECT_PATH)
                val wasAutoFixEnabled = data.getBooleanExtra(FileEditorActivity.EXTRA_ENABLE_AUTO_FIX, false)

                if (projectPath != null) {
                    Log.i(TAG, "FileEditorActivity finished successfully. Project path: $projectPath, Auto-Fix was Active: $wasAutoFixEnabled")

                    if (wasAutoFixEnabled && AutoFixStateManager.isAutoFixModeGloballyActive) {
                        Toast.makeText(this, "AI finished. Starting build for '${File(projectPath).name}'...", Toast.LENGTH_LONG).show()
                        openAndBuildProject(File(projectPath))
                    } else {
                        Toast.makeText(this, "Project '${File(projectPath).name}' is ready. Opening...", Toast.LENGTH_LONG).show()
                        openProject(File(projectPath))
                    }
                } else {
                    Log.w(TAG, "FileEditorActivity returned OK but project path was null.")
                    if (wasAutoFixEnabled) {
                        AutoFixStateManager.disableAutoFixMode()
                        Toast.makeText(this, "Auto-fix cycle stopped: Project path was lost.", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Log.i(TAG, "FileEditorActivity was cancelled or returned an error. Disabling auto-fix if it was active.")
                AutoFixStateManager.disableAutoFixMode()
            }
        }

        setupViewModelObservers()
        setupNavigation()
        setupLaunchAiEditorButton()

        // Handle possible Editor->Main routing request on cold start
        handleIntentToMaybeLaunchAi(intent)

        openLastProject()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntentToMaybeLaunchAi(intent)
    }

    override fun bindLayout(): View {
        _binding = ActivityMainBinding.inflate(layoutInflater)
        return binding.root
    }

    private fun handleIntentToMaybeLaunchAi(incoming: Intent?) {
        incoming ?: return
        if (!incoming.getBooleanExtra(EXTRA_REQUEST_AI_EDITOR, false)) return

        val payload = AiLaunchPayload(
            projectsRootOverride = incoming.getStringExtra(EXTRA_AI_PROJECTS_ROOT_OVERRIDE),
            prefillName = incoming.getStringExtra(FileEditorActivity.EXTRA_PREFILL_APP_NAME),
            prefillDescription = incoming.getStringExtra(FileEditorActivity.EXTRA_PREFILL_APP_DESCRIPTION),
            isAutoRetry = incoming.getBooleanExtra(FileEditorActivity.EXTRA_IS_AUTO_RETRY_ATTEMPT, false)
        )
        // Clear flags to avoid re-triggering if this intent lingers
        incoming.removeExtra(EXTRA_REQUEST_AI_EDITOR)
        incoming.removeExtra(EXTRA_AI_PROJECTS_ROOT_OVERRIDE)

        if (!checkAndRequestStoragePermission()) {
            pendingAiLaunchPayload = payload
            return
        }
        launchFileEditorActivityInternal(payload)
    }

    private fun setupViewModelObservers() {
        viewModel.currentScreen.observe(this) { screen ->
            if (screen == -1) return@observe
            onScreenChanged(screen)
            onBackPressedCallback.isEnabled = screen != MainViewModel.SCREEN_MAIN
        }
    }

    private fun setupNavigation() {
        if (viewModel.currentScreen.value == -1 && viewModel.previousScreen == -1) {
            viewModel.setScreen(MainViewModel.SCREEN_MAIN)
        } else {
            updateFragmentVisibility(viewModel.currentScreen.value)
            onBackPressedCallback.isEnabled = viewModel.currentScreen.value != MainViewModel.SCREEN_MAIN
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    private fun setupLaunchAiEditorButton() {
        binding.buttonLaunchAiEditor.setOnClickListener {
            launchFileEditorActivity()
        }
    }

    override fun onApplySystemBarInsets(insets: Insets) {
        binding.fragmentContainersParent.setPadding(
            insets.left, 0, insets.right, insets.bottom
        )
    }

    private fun onScreenChanged(screen: Int?) {
        val previous = viewModel.previousScreen
        if (previous != -1 && previous != screen) {
            val setAxisToX = (previous == MainViewModel.SCREEN_TEMPLATE_LIST || previous == MainViewModel.SCREEN_TEMPLATE_DETAILS) &&
                    (screen == MainViewModel.SCREEN_TEMPLATE_LIST || screen == MainViewModel.SCREEN_TEMPLATE_DETAILS)
            val axis = if (setAxisToX) MaterialSharedAxis.X else MaterialSharedAxis.Y
            val isForward = (screen ?: 0) > previous

            val transition = MaterialSharedAxis(axis, isForward)
            transition.doOnEnd {
                viewModel.isTransitionInProgress = false
                onBackPressedCallback.isEnabled = viewModel.currentScreen.value != MainViewModel.SCREEN_MAIN
            }
            viewModel.isTransitionInProgress = true
            TransitionManager.beginDelayedTransition(binding.root, transition)
        }
        updateFragmentVisibility(screen)
    }

    private fun updateFragmentVisibility(screen: Int?) {
        binding.main.isVisible = screen == MainViewModel.SCREEN_MAIN
        binding.templateList.isVisible = screen == MainViewModel.SCREEN_TEMPLATE_LIST
        binding.templateDetails.isVisible = screen == MainViewModel.SCREEN_TEMPLATE_DETAILS
        if (screen !in listOf(MainViewModel.SCREEN_MAIN, MainViewModel.SCREEN_TEMPLATE_LIST, MainViewModel.SCREEN_TEMPLATE_DETAILS)) {
            binding.main.isVisible = true
        }
    }

    private fun openLastProject() {
        binding.root.post { tryOpenLastProject() }
    }

    private fun tryOpenLastProject() {
        if (!GeneralPreferences.autoOpenProjects) return
        val openedProject = GeneralPreferences.lastOpenedProject
        if (GeneralPreferences.NO_OPENED_PROJECT == openedProject || openedProject.isNullOrEmpty()) return

        val project = File(openedProject)
        if (!project.exists() || !project.isDirectory) {
            Toast.makeText(this, R.string.msg_opened_project_does_not_exist, Toast.LENGTH_SHORT).show()
            GeneralPreferences.lastOpenedProject = GeneralPreferences.NO_OPENED_PROJECT
            return
        }
        if (GeneralPreferences.confirmProjectOpen) {
            askProjectOpenPermission(project)
            return
        }
        openProject(project)
    }

    private fun askProjectOpenPermission(root: File) {
        DialogUtils.newMaterialDialogBuilder(this)
            .setTitle(R.string.title_confirm_open_project)
            .setMessage(getString(R.string.msg_confirm_open_project, root.absolutePath))
            .setCancelable(false)
            .setPositiveButton(R.string.yes) { _, _ -> openProject(root) }
            .setNegativeButton(R.string.no) { _, _ -> }
            .show()
    }

    internal fun openProject(root: File) {
        IProjectManager.getInstance().openProject(root)
        GeneralPreferences.lastOpenedProject = root.absolutePath
        startActivity(Intent(this, EditorActivityKt::class.java))
    }

    internal fun openAndBuildProject(root: File) {
        IProjectManager.getInstance().openProject(root)
        GeneralPreferences.lastOpenedProject = root.absolutePath
        val intent = Intent(this, EditorActivityKt::class.java).apply {
            putExtra(EditorActivityKt.EXTRA_AUTO_BUILD_PROJECT, true)
        }
        startActivity(intent)
    }

    private fun checkAndRequestStoragePermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST_CODE
            )
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // If we were routing from Editor, use the pending payload
                val payload = pendingAiLaunchPayload
                pendingAiLaunchPayload = null
                launchFileEditorActivityInternal(payload)
            } else {
                Toast.makeText(this, R.string.msg_storage_permission_required, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getAndroidIDEProjectsRootPath(): String? {
        val projectsBase = getExternalFilesDir(null)
        if (projectsBase == null) {
            Toast.makeText(this, "External storage unavailable.", Toast.LENGTH_SHORT).show();
            return null;
        }
        val projectsDir = File(projectsBase, "AndroidIDEProjects")
        if (!projectsDir.exists()) {
            if (!projectsDir.mkdirs()) {
                Toast.makeText(this, "Could not create projects directory.", Toast.LENGTH_LONG).show()
                return null
            }
        } else if (!projectsDir.isDirectory) {
            Toast.makeText(this, R.string.msg_project_path_not_directory, Toast.LENGTH_LONG).show()
            return null
        }
        return projectsDir.absolutePath
    }

    private fun launchFileEditorActivity() {
        if (!checkAndRequestStoragePermission()) return
        launchFileEditorActivityInternal(null)
    }

    private fun launchFileEditorActivityInternal(payload: AiLaunchPayload?) {
        val projectsRootPath = payload?.projectsRootOverride ?: getAndroidIDEProjectsRootPath() ?: return
        Log.d(TAG, "Launching FileEditorActivity with root: $projectsRootPath")

        val intent = FileEditorActivity.newIntent(this, projectsRootPath).apply {
            payload?.prefillName?.let { putExtra(FileEditorActivity.EXTRA_PREFILL_APP_NAME, it) }
            payload?.prefillDescription?.let { putExtra(FileEditorActivity.EXTRA_PREFILL_APP_DESCRIPTION, it) }
            if (payload?.isAutoRetry == true) {
                putExtra(FileEditorActivity.EXTRA_IS_AUTO_RETRY_ATTEMPT, true)
            }
        }
        fileEditorResultLauncher.launch(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        ITemplateProvider.getInstance().release()
        _binding = null
    }
}