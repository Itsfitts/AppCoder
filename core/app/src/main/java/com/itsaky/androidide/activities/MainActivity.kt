package com.itsaky.androidide.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.util.Log
import android.view.View // Import View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.isVisible
import androidx.transition.TransitionManager
import androidx.transition.doOnEnd
import com.google.android.material.floatingactionbutton.FloatingActionButton // Ensure this import is present if used directly, though binding covers it
import com.google.android.material.transition.MaterialSharedAxis
import com.itsaky.androidide.R // General R import
import com.itsaky.androidide.activities.editor.EditorActivityKt
import com.itsaky.androidide.app.EdgeToEdgeIDEActivity
import com.itsaky.androidide.databinding.ActivityMainBinding // Assuming ViewBinding is used
import com.itsaky.androidide.dialogs.FileEditorDialog // Your custom dialog
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.projects.IProjectManager
// Removed specific R.string import, use R.string directly or context.getString()
import com.itsaky.androidide.templates.ITemplateProvider
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.flashInfo // If flashInfo is still used
import com.itsaky.androidide.viewmodel.MainViewModel
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_MAIN
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_TEMPLATE_DETAILS
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_TEMPLATE_LIST
import java.io.File


class MainActivity : EdgeToEdgeIDEActivity() {

    private val viewModel by viewModels<MainViewModel>()
    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!! // Not-null assertion for safe access after inflation
    private val STORAGE_PERMISSION_REQUEST_CODE = 101

    // Full implementation of onBackPressedCallback
    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            viewModel.apply {
                // Ignore back press if project creating is in progress
                if (creatingProject.value == true) {
                    return@apply
                }

                val newScreen = when (currentScreen.value) {
                    SCREEN_TEMPLATE_DETAILS -> SCREEN_TEMPLATE_LIST
                    SCREEN_TEMPLATE_LIST -> SCREEN_MAIN
                    else -> SCREEN_MAIN // Default case, including SCREEN_MAIN itself
                }

                if (currentScreen.value != newScreen) {
                    setScreen(newScreen) // Trigger screen change if applicable
                } else {
                     // If already on the main screen, disable this callback and let the system handle it (exit app)
                     isEnabled = false
                     onBackPressedDispatcher.onBackPressed() // Re-trigger the back press
                     isEnabled = true // Re-enable for future navigation
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Call super.onCreate FIRST. This will trigger bindLayout() via BaseIDEActivity's onCreate.
        super.onCreate(savedInstanceState)
        // BaseIDEActivity.onCreate has now called bindLayout() and likely setContentView.
        // _binding is now initialized.

        // Setup components that rely on the binding or ViewModel
        setupViewModelObservers()
        setupNavigation()
        setupFab()

        // Other initial setup
        openLastProject() // Safe to call now as it posts to the view's handler
    }

    // This method inflates the view and is called by BaseIDEActivity's onCreate
    override fun bindLayout(): View {
        Log.d("MainActivity", "bindLayout() called - Inflating view.")
        _binding = ActivityMainBinding.inflate(layoutInflater)
        return binding.root
    }

    // Helper methods to organize onCreate logic
    private fun setupViewModelObservers() {
         viewModel.currentScreen.observe(this) { screen ->
            // Check for -1 explicitly to avoid issues during initial setup or config changes
            if (screen == -1) {
                 Log.d("MainActivity", "Current screen observed as -1, ignoring.")
                return@observe
            }
            Log.d("MainActivity", "Current screen observed: $screen")
            onScreenChanged(screen) // Update UI based on screen
            onBackPressedCallback.isEnabled = screen != SCREEN_MAIN // Enable/disable back callback
        }
        // Add other ViewModel observers here if needed
    }

     private fun setupNavigation() {
         // Determine initial screen only if it hasn't been set (e.g., first launch)
        if (viewModel.currentScreen.value == -1 && viewModel.previousScreen == -1) {
             Log.d("MainActivity", "Setting initial screen to SCREEN_MAIN")
            viewModel.setScreen(SCREEN_MAIN)
        } else {
             // If activity is restarting (e.g., rotation), ensure UI matches current screen
             Log.d("MainActivity", "Restoring screen UI for: ${viewModel.currentScreen.value}")
             // Directly call onScreenChanged to set correct fragment visibility without transition
             updateFragmentVisibility(viewModel.currentScreen.value)
             onBackPressedCallback.isEnabled = viewModel.currentScreen.value != SCREEN_MAIN
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
     }

     private fun setupFab() {
         binding.fabFileEditor.setOnClickListener {
             Log.d("MainActivity", "FAB clicked.")
             showFileEditorDialog() // Initiate showing the dialog (checks permissions first)
         }
     }

    override fun onApplySystemBarInsets(insets: Insets) {
        // Apply padding based on system insets
        binding.fragmentContainersParent.setPadding(
            insets.left,
            0, // No top padding managed here
            insets.right,
            insets.bottom
        )
    }

    // Handles transitions and fragment visibility when screen changes
    private fun onScreenChanged(screen: Int?) {
        val previous = viewModel.previousScreen
        // Only run transition if there was a valid previous screen and it's different from the new one
        if (previous != -1 && previous != screen) {
            Log.d("MainActivity", "onScreenChanged: Transitioning from $previous to $screen")
            val setAxisToX =
                (previous == SCREEN_TEMPLATE_LIST || previous == SCREEN_TEMPLATE_DETAILS) && (screen == SCREEN_TEMPLATE_LIST || screen == SCREEN_TEMPLATE_DETAILS)
            val axis = if (setAxisToX) MaterialSharedAxis.X else MaterialSharedAxis.Y
            // Determine direction: positive difference means forward
            val isForward = (screen ?: 0) > previous // Check if new screen ID is greater than previous

            val transition = MaterialSharedAxis(axis, isForward)
            transition.doOnEnd {
                viewModel.isTransitionInProgress = false
                // Re-evaluate back button state after transition ends
                onBackPressedCallback.isEnabled = viewModel.currentScreen.value != SCREEN_MAIN
                Log.d("MainActivity", "Transition ended. Back press enabled: ${onBackPressedCallback.isEnabled}")
            }
            viewModel.isTransitionInProgress = true
            TransitionManager.beginDelayedTransition(binding.root, transition)
        } else {
             Log.d("MainActivity", "onScreenChanged: No transition needed (previous: $previous, current: $screen)")
        }

        // Update fragment visibility regardless of transition
        updateFragmentVisibility(screen)
    }

    // Helper to set fragment visibility based on the current screen ID
    private fun updateFragmentVisibility(screen: Int?) {
         Log.d("MainActivity", "Updating fragment visibility for screen: $screen")
         val currentFragmentContainer = when (screen) {
            SCREEN_MAIN -> binding.main
            SCREEN_TEMPLATE_LIST -> binding.templateList
            SCREEN_TEMPLATE_DETAILS -> binding.templateDetails
            else -> {
                 // Log an error or handle invalid state, maybe default to main screen
                 Log.e("MainActivity", "Invalid screen id for visibility update: '$screen'")
                 binding.main // Default to main fragment visibility
            }
        }
        // Ensure all fragment containers exist before trying to access them
        binding.main.isVisible = binding.main == currentFragmentContainer
        binding.templateList.isVisible = binding.templateList == currentFragmentContainer
        binding.templateDetails.isVisible = binding.templateDetails == currentFragmentContainer
    }


    // --- Project Opening Logic ---
    private fun openLastProject() {
        // Post to ensure view hierarchy is ready
        binding.root.post {
             Log.d("MainActivity", "Posted tryOpenLastProject to handler.")
             tryOpenLastProject()
        }
    }

     private fun tryOpenLastProject() {
         Log.d("MainActivity", "Executing tryOpenLastProject.")
        if (!GeneralPreferences.autoOpenProjects) {
             Log.d("MainActivity", "Auto open projects disabled.")
             return
        }
        val openedProject = GeneralPreferences.lastOpenedProject
        if (GeneralPreferences.NO_OPENED_PROJECT == openedProject) {
             Log.d("MainActivity", "No last opened project recorded.")
             return
        }

        if (TextUtils.isEmpty(openedProject)) {
           Log.w("MainActivity", "Last opened project path is empty.")
           // Consider using flashInfo or Toast if context is readily available and appropriate
           // flashInfo(com.itsaky.androidide.R.string.msg_opened_project_does_not_exist)
           Toast.makeText(this, R.string.msg_opened_project_does_not_exist, Toast.LENGTH_SHORT).show()
           return
        }

        val project = File(openedProject)
        if (!project.exists() || !project.isDirectory) { // Also check if it's a directory
           Log.w("MainActivity", "Last opened project path does not exist or is not a directory: $openedProject")
           // flashInfo(com.itsaky.androidide.R.string.msg_opened_project_does_not_exist)
           Toast.makeText(this, R.string.msg_opened_project_does_not_exist, Toast.LENGTH_SHORT).show()
           // Clear the invalid preference
           GeneralPreferences.lastOpenedProject = GeneralPreferences.NO_OPENED_PROJECT
           return
        }

        if (GeneralPreferences.confirmProjectOpen) {
             Log.d("MainActivity", "Confirmation required to open project: $openedProject")
            askProjectOpenPermission(project)
            return
        }

         Log.i("MainActivity", "Auto-opening project: $openedProject")
        openProject(project)
    }

    private fun askProjectOpenPermission(root: File) {
        val builder = DialogUtils.newMaterialDialogBuilder(this)
        // Use R.string resources correctly
        builder.setTitle(R.string.title_confirm_open_project)
        builder.setMessage(getString(R.string.msg_confirm_open_project, root.absolutePath))
        builder.setCancelable(false) // Prevent dismissing by tapping outside
        builder.setPositiveButton(R.string.yes) { _, _ ->
            Log.d("MainActivity", "User confirmed opening project: ${root.absolutePath}")
            openProject(root)
        }
        builder.setNegativeButton(R.string.no) { _, _ ->
            Log.d("MainActivity", "User declined opening project: ${root.absolutePath}")
             // Optionally clear the last opened project preference if user says no
             // GeneralPreferences.lastOpenedProject = GeneralPreferences.NO_OPENED_PROJECT
        }
        builder.show()
    }

    // Marked internal as per original code
    internal fun openProject(root: File) {
         Log.i("MainActivity", "Opening project: ${root.absolutePath}")
        // Assuming IProjectManager handles setting the project context globally
        IProjectManager.getInstance().openProject(root)
        // Update the preference to this project since it's being opened
        GeneralPreferences.lastOpenedProject = root.absolutePath
        // Start the Editor activity
        startActivity(Intent(this, EditorActivityKt::class.java))
        // Optionally finish MainActivity if you don't want it in the backstack
        // finish()
    }


    // --- Permission and Dialog Logic ---

    private fun checkAndRequestStoragePermission(): Boolean {
        val writePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (writePermission != PackageManager.PERMISSION_GRANTED) {
             Log.d("MainActivity", "Storage permission not granted. Requesting...")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), // Request only write for now
                STORAGE_PERMISSION_REQUEST_CODE
            )
            return false // Indicate permission is not yet granted
        }
         Log.d("MainActivity", "Storage permission already granted.")
        return true // Permission is granted
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d("MainActivity", "onRequestPermissionsResult received for code: $requestCode")
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            // Check if the first permission (WRITE_EXTERNAL_STORAGE) was granted
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i("MainActivity", "Storage permission granted by user.")
                // Permission granted, proceed to show the dialog
                showFileEditorDialogInternal()
            } else {
                Log.w("MainActivity", "Storage permission denied by user.")
                Toast.makeText(this, R.string.msg_storage_permission_required, Toast.LENGTH_LONG).show() // Use a string resource
            }
        }
    }

    // Determines the root path for projects - replace hardcoded path if possible
    private fun getAndroidIDEProjectsRootPath(): String? {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            Log.e("MainActivity", "External storage not mounted.")
            Toast.makeText(this, R.string.msg_external_storage_unavailable, Toast.LENGTH_SHORT).show() // Use string resource
            return null
        }
        // !!! CRITICAL: This assumes a fixed path. Find AndroidIDE's actual preference/setting for this if possible !!!
        val projectsDir = File(Environment.getExternalStorageDirectory(), "AndroidIDEProjects")

        // Check if the directory exists, attempt to create if not
        if (!projectsDir.exists()) {
             Log.w("MainActivity", "Projects root directory does not exist: ${projectsDir.absolutePath}. Attempting creation.")
             if (!projectsDir.mkdirs()) {
                  // Log failure but continue, as maybe only subdirs need creating later
                  Log.e("MainActivity", "Failed to create projects root directory: ${projectsDir.absolutePath}. Check permissions and storage.")
             } else {
                 Log.i("MainActivity", "Created projects root directory: ${projectsDir.absolutePath}")
             }
        } else if (!projectsDir.isDirectory) {
             Log.e("MainActivity", "Projects root path exists but is not a directory: ${projectsDir.absolutePath}")
             Toast.makeText(this, R.string.msg_project_path_not_directory, Toast.LENGTH_LONG).show() // Use string resource
             return null // Path is invalid
        }

        val path = projectsDir.absolutePath
        Log.d("MainActivity", "Using projects root path: $path")
        return path
    }

    // Public trigger for showing the dialog (checks permission first)
    private fun showFileEditorDialog() {
        if (!checkAndRequestStoragePermission()) {
            // Request initiated, wait for onRequestPermissionsResult callback
             Log.d("MainActivity", "Permission check failed, waiting for result.")
            return
        }
        // Permissions are already granted, proceed directly
         Log.d("MainActivity", "Permissions granted, proceeding to show dialog internally.")
        showFileEditorDialogInternal()
    }

    // Internal method that assumes permissions are granted
    private fun showFileEditorDialogInternal() {
        val projectsRootPath = getAndroidIDEProjectsRootPath()
        if (projectsRootPath == null) {
             Log.e("MainActivity", "Cannot show dialog: Failed to get project root path.")
             // Toast was likely shown in getAndroidIDEProjectsRootPath already
            return
        }

        // Create and show the dialog using the factory method
        Log.d("MainActivity", "Creating and showing FileEditorDialog with root: $projectsRootPath")
        val dialog = FileEditorDialog.newInstance(projectsRootPath)
        // Ensure TAG is accessible - using the one defined in FileEditorDialog.Companion
        dialog.show(supportFragmentManager, FileEditorDialog.TAG)
    }

    override fun onDestroy() {
        // Release resources
        ITemplateProvider.getInstance().release()
        super.onDestroy()
        // Clean up binding reference to prevent memory leaks
        _binding = null
        Log.d("MainActivity", "onDestroy called, binding set to null.")
    }
}