package com.itsaky.androidide.activities.editor

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.view.menu.MenuBuilder
import androidx.collection.MutableIntObjectMap
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.GravityCompat
import androidx.core.view.forEach
import androidx.lifecycle.lifecycleScope
import com.blankj.utilcode.util.ImageUtils
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem.Location
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.FillMenuParams
import com.itsaky.androidide.dialogs.AiWorkflowState
import com.itsaky.androidide.dialogs.AutoFixStateManager // Corrected import if needed
import com.itsaky.androidide.dialogs.FileEditorActivity
import com.itsaky.androidide.dialogs.ProjectOperationsHandler
import com.itsaky.androidide.dialogs.ViewModelFileEditorBridge
import com.itsaky.androidide.editor.language.treesitter.JavaLanguage
import com.itsaky.androidide.editor.language.treesitter.JsonLanguage
import com.itsaky.androidide.editor.language.treesitter.KotlinLanguage
import com.itsaky.androidide.editor.language.treesitter.LogLanguage
import com.itsaky.androidide.editor.language.treesitter.TSLanguageRegistry
import com.itsaky.androidide.editor.language.treesitter.XMLLanguage
import com.itsaky.androidide.editor.schemes.IDEColorSchemeProvider
import com.itsaky.androidide.editor.ui.IDEEditor
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import com.itsaky.androidide.interfaces.IEditorHandler
import com.itsaky.androidide.models.FileExtension
import com.itsaky.androidide.models.OpenedFile
import com.itsaky.androidide.models.OpenedFilesCache
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.models.SaveResult
import com.itsaky.androidide.projects.internal.ProjectManagerImpl
import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.ui.CodeEditorView
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.IntentUtils.openImage
import com.itsaky.androidide.utils.UniqueNameBuilder
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import com.itsaky.androidide.projects.android.AndroidModule


open class EditorHandlerActivity : ProjectHandlerActivity(), IEditorHandler {

  protected val isOpenedFilesSaved = AtomicBoolean(false)
  private val buildLogBuilder = StringBuilder()

  companion object {
    private const val DEBUG_TAG = "AutoFixDebug"
    private const val TAG = "EditorHandlerActivity"
    private const val AI_FIX_RUN_DELAY_MS = 2500L // Increased delay before auto-run
  }

  val isAutoFixModeActivePublic: Boolean
    get() = AutoFixStateManager.isAutoFixModeGloballyActive

  private val aiActivityLauncher: ActivityResultLauncher<Intent> =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      Log.d(DEBUG_TAG, "EditorHandlerActivity: ActivityResultLauncher - Result received. ResultCode: ${result.resultCode}")
      val data: Intent? = result.data

      if (result.resultCode == Activity.RESULT_OK && data != null) {
        Log.i(DEBUG_TAG, "EditorHandlerActivity: ActivityResultLauncher - RESULT_OK from FileEditorActivity.")
        val projectPathFromResult = data.getStringExtra(FileEditorActivity.RESULT_EXTRA_PROJECT_PATH)

        if (projectPathFromResult.isNullOrBlank()) {
          Log.e(DEBUG_TAG, "ActivityResultLauncher - Project path missing. Disabling global auto-fix.")
          Toast.makeText(this, "AI Editor Error: Project path missing.", Toast.LENGTH_LONG).show()
          AutoFixStateManager.disableAutoFixMode()
          updateAutoFixModeIndicator()
          return@registerForActivityResult
        }
        updateAutoFixModeIndicator() // Update based on state set by FileEditorActivity

        val newProjectDir = File(projectPathFromResult)
        val projectManager = ProjectManagerImpl.getInstance()
        val currentManagerDir = try { projectManager.projectDir } catch (e: IllegalStateException) { null }
        val projectSwitchNeeded = currentManagerDir == null || currentManagerDir.absolutePath != newProjectDir.absolutePath

        lifecycleScope.launch {
          Log.d(DEBUG_TAG, "ActivityResultLauncher: Starting 1.5 second delay before project sync/init.")
          delay(1500)

          if (isDestroyed || isFinishing) {
            Log.w(DEBUG_TAG, "ActivityResultLauncher: Activity destroyed during delay, aborting project sync.")
            return@launch
          }

          Log.d(DEBUG_TAG, "ActivityResultLauncher: Delay finished. Proceeding with project sync/init.")
          if (projectSwitchNeeded) {
            Log.i(DEBUG_TAG, "ActivityResultLauncher (after delay) - Project context changed. Switching project to: ${newProjectDir.absolutePath}")
            saveOpenedFiles()
            doCloseAll {
              projectManager.openProject(newProjectDir.absolutePath)
              super.initializeProject()
            }
          } else {
            Log.i(DEBUG_TAG, "ActivityResultLauncher (after delay) - AI op on current project: ${newProjectDir.absolutePath}")
            saveAllAsync(notify = false, requestSync = true, processResources = true) {
              super.initializeProject()
            }
          }
        }
      } else {
        Log.w(DEBUG_TAG, "ActivityResultLauncher - Result NOT OK (resultCode=${result.resultCode}) or data is null. Disabling global auto-fix.")
        AutoFixStateManager.disableAutoFixMode()
        updateAutoFixModeIndicator()
      }
    }

  override fun doOpenFile(file: File, selection: Range?) { openFileAndSelect(file, selection) }
  override fun doCloseAll(runAfter: () -> Unit) { closeAll(runAfter) }
  override fun provideCurrentEditor(): CodeEditorView? { return getCurrentEditor() }
  override fun provideEditorAt(index: Int): CodeEditorView? { return getEditorAtIndex(index) }

  override fun preDestroy() {
    Log.d(DEBUG_TAG, "EditorHandlerActivity: preDestroy called")
    super.preDestroy()
    TSLanguageRegistry.instance.destroy()
    editorViewModel.removeAllFiles()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    Log.d(DEBUG_TAG, "EditorHandlerActivity: onCreate START. savedInstanceState isNull: ${savedInstanceState == null}")
    mBuildEventListener.setActivity(this)
    super.onCreate(savedInstanceState)
    Log.d(DEBUG_TAG, "EditorHandlerActivity: onCreate - Global AutoFix Active on entry: ${AutoFixStateManager.isAutoFixModeGloballyActive}")
    editorViewModel._displayedFile.observe(this) { this.content.editorContainer.displayedChild = it }
    editorViewModel._startDrawerOpened.observe(this) { opened ->
      this.binding.editorDrawerLayout.apply {
        if (opened) openDrawer(GravityCompat.START) else closeDrawer(GravityCompat.START)
      }
    }
    editorViewModel._filesModified.observe(this) { invalidateOptionsMenu() }
    editorViewModel._filesSaving.observe(this) { invalidateOptionsMenu() }
    editorViewModel.observeFiles(this) {
      val currentFile = getCurrentEditor()?.editor?.file?.absolutePath ?: run {
        editorViewModel.writeOpenedFiles(null)
        editorViewModel.openedFilesCache = null
        return@observeFiles
      }
      getOpenedFiles().also {
        val cache = OpenedFilesCache(currentFile, it)
        editorViewModel.writeOpenedFiles(cache)
        editorViewModel.openedFilesCache = cache
      }
    }
    lifecycleScope.launch {
      TSLanguageRegistry.instance.register(JavaLanguage.TS_TYPE, JavaLanguage.FACTORY)
      TSLanguageRegistry.instance.register(KotlinLanguage.TS_TYPE_KT, KotlinLanguage.FACTORY)
      TSLanguageRegistry.instance.register(KotlinLanguage.TS_TYPE_KTS, KotlinLanguage.FACTORY)
      TSLanguageRegistry.instance.register(LogLanguage.TS_TYPE, LogLanguage.FACTORY)
      TSLanguageRegistry.instance.register(JsonLanguage.TS_TYPE, JsonLanguage.FACTORY)
      TSLanguageRegistry.instance.register(XMLLanguage.TS_TYPE, XMLLanguage.FACTORY)
      IDEColorSchemeProvider.initIfNeeded()
    }
    updateAutoFixModeIndicator()
    Log.d(DEBUG_TAG, "EditorHandlerActivity: onCreate END. Global AutoFix Active: ${AutoFixStateManager.isAutoFixModeGloballyActive}")
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    Log.i(DEBUG_TAG, "EditorHandlerActivity: onSaveInstanceState called. Global auto-fix state is managed by AutoFixStateManager.")
  }

  override fun onPause() {
    super.onPause()
    Log.d(DEBUG_TAG, "EditorHandlerActivity: onPause. isFinishing=$isFinishing")
    if (!isOpenedFilesSaved.get()) { saveOpenedFiles() }
  }

  override fun onResume() {
    super.onResume()
    Log.d(DEBUG_TAG, "EditorHandlerActivity: onResume. Global AutoFix Active: ${AutoFixStateManager.isAutoFixModeGloballyActive}")
    isOpenedFilesSaved.set(false)
    updateAutoFixModeIndicator()
  }

  override fun onStart() {
    super.onStart()
    Log.d(DEBUG_TAG, "EditorHandlerActivity: onStart. Global AutoFix Active: ${AutoFixStateManager.isAutoFixModeGloballyActive}")
    try { editorViewModel.getOrReadOpenedFilesCache(this::onReadOpenedFilesCache) }
    catch (err: Throwable) { log.error("Failed to reopen recently opened files", err) }
    updateAutoFixModeIndicator()
  }

  private fun launchAiEditorInteraction(intent: Intent) {
    Log.d(DEBUG_TAG, "EditorHandlerActivity: launchAiEditorInteraction called")
    aiActivityLauncher.launch(intent)
  }

  private fun resetAutoFixState() {
    Log.i(DEBUG_TAG, "EditorHandlerActivity: resetAutoFixState called. Disabling global auto-fix.")
    AutoFixStateManager.disableAutoFixMode()
    updateAutoFixModeIndicator()
  }

  fun updateAutoFixModeIndicator() {
    val isActive = AutoFixStateManager.isAutoFixModeGloballyActive
    Log.d(DEBUG_TAG, "EditorHandlerActivity: updateAutoFixModeIndicator called. Global isActive = $isActive")
    try {
      content.bottomSheet?.setAutoFixIndicatorVisibility(isActive)
    } catch (e: Exception) {
      Log.e(DEBUG_TAG, "EditorHandlerActivity: updateAutoFixModeIndicator - FAILED. Error: ${e.message}", e)
    }
  }

  fun handleBuildFailed(tasks: List<String?>) {
    val capturedLog = getCompleteCapturedBuildOutput()
    val projectManager = ProjectManagerImpl.getInstance()
    val currentProjectFile: File? = try { projectManager.projectDir } catch (e: IllegalStateException) { null }
    val currentProjectName: String? = currentProjectFile?.name
    val projectsBaseDirPath: String? = currentProjectFile?.parentFile?.absolutePath

    val wasAssemblyTask = tasks?.any { it?.contains("assemble", ignoreCase = true) == true } ?: false
    if (!wasAssemblyTask) {
      Log.w(DEBUG_TAG, "handleBuildFailed: Build failure was not an assembly task (tasks: ${tasks?.joinToString()}). Showing manual dialog without triggering auto-fix cycle.")
      showManualBuildFailedDialog(capturedLog, currentProjectName, projectsBaseDirPath)
      return
    }

    Log.d(DEBUG_TAG, "handleBuildFailed: Entered for assembly task. Checking AutoFixStateManager.canAttemptAutoFix(). Result: ${AutoFixStateManager.canAttemptAutoFix()}")

    if (AutoFixStateManager.canAttemptAutoFix()) {
      Log.d(DEBUG_TAG, "handleBuildFailed: Auto-fix conditions MET via Global State.")
      if (currentProjectName != null && projectsBaseDirPath != null) {
        if (AutoFixStateManager.consumeAttempt()) {
          val attemptsMade = AutoFixStateManager.MAX_GLOBAL_AUTO_FIX_ATTEMPTS - AutoFixStateManager.autoFixAttemptsRemainingGlobal
          val toastMessage = "Auto-fixing build error (Attempt $attemptsMade/${AutoFixStateManager.MAX_GLOBAL_AUTO_FIX_ATTEMPTS})..."
          Log.i(DEBUG_TAG, "handleBuildFailed: Triggering AUTOMATED AI fix. $toastMessage Remaining: ${AutoFixStateManager.autoFixAttemptsRemainingGlobal}")
          Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()

          val combinedDescriptionForAi = """
                        Original App Description:
                        ${AutoFixStateManager.initialAppDescriptionForGlobalAutoFix}

                        ---
                        Please analyze the original app description and the following build failure. Modify the code to fix the build error while adhering to the original app goals:
                        ${getString(R.string.ai_fix_failure_prefix)}${capturedLog}
                    """.trimIndent()

          val intent = FileEditorActivity.newIntent(this, projectsBaseDirPath).apply {
            putExtra(FileEditorActivity.EXTRA_PREFILL_APP_NAME, currentProjectName)
            putExtra(FileEditorActivity.EXTRA_PREFILL_APP_DESCRIPTION, combinedDescriptionForAi)
            putExtra(FileEditorActivity.EXTRA_IS_AUTO_RETRY_ATTEMPT, true)
          }
          launchAiEditorInteraction(intent)
          return
        } else {
          Log.w(DEBUG_TAG, "handleBuildFailed: canAttemptAutoFix was true, but consumeAttempt failed (no attempts left or issue). Showing manual dialog.")
          AutoFixStateManager.disableAutoFixMode()
          updateAutoFixModeIndicator()
          showManualBuildFailedDialog(capturedLog, currentProjectName, projectsBaseDirPath)
        }
      } else {
        Log.e(DEBUG_TAG, "handleBuildFailed: Auto-fix: Cannot retry - Project details missing. Disabling global auto-fix.")
        Toast.makeText(this, "Auto-fix: Project details missing. Stopping.", Toast.LENGTH_LONG).show()
        AutoFixStateManager.disableAutoFixMode()
        updateAutoFixModeIndicator()
        showManualBuildFailedDialog(capturedLog, currentProjectName, projectsBaseDirPath)
      }
    } else {
      Log.d(DEBUG_TAG, "handleBuildFailed: Global Auto-fix conditions NOT MET as per canAttemptAutoFix().")
      if (AutoFixStateManager.isAutoFixModeGloballyActive && AutoFixStateManager.autoFixAttemptsRemainingGlobal <= 0) {
        Log.i(DEBUG_TAG, "handleBuildFailed: Global Auto-fix: Max attempts were reached.")
        Toast.makeText(this, "Auto-fix attempts finished.", Toast.LENGTH_LONG).show()
      }
      AutoFixStateManager.disableAutoFixMode()
      updateAutoFixModeIndicator()
      showManualBuildFailedDialog(capturedLog, currentProjectName, projectsBaseDirPath)
    }
  }

  private fun showManualBuildFailedDialog(
    capturedLog: String,
    currentProjectName: String?,
    projectsBaseDirPath: String?
  ) {
    Log.d(DEBUG_TAG, "showManualBuildFailedDialog: Displaying manual dialog.")
    val errorMessagePrefix = getString(R.string.ai_fix_failure_prefix)
    val fullDialogMessage = if (capturedLog.isBlank()) {
      errorMessagePrefix + getString(R.string.build_status_failed) + "\n\n" + getString(R.string.build_log_empty)
    } else {
      errorMessagePrefix + capturedLog
    }

    runOnUiThread {
      val dialogBuilder = DialogUtils.newCustomMessageDialog(this, getString(R.string.title_build_failed), fullDialogMessage,
        getString(R.string.action_retry_build_with_ai),
        { dialog, _ ->
          dialog.dismiss()
          Log.d(DEBUG_TAG, "showManualBuildFailedDialog: User clicked 'Retry with AI'. Disabling any active global auto-fix first.")
          AutoFixStateManager.disableAutoFixMode() // User is taking manual control
          updateAutoFixModeIndicator()

          if (currentProjectName != null && projectsBaseDirPath != null) {
            val intent = FileEditorActivity.newIntent(this, projectsBaseDirPath).apply{
              putExtra(FileEditorActivity.EXTRA_PREFILL_APP_NAME, currentProjectName)
              putExtra(FileEditorActivity.EXTRA_PREFILL_APP_DESCRIPTION, fullDialogMessage)
            }
            Log.d(DEBUG_TAG, "showManualBuildFailedDialog: Launching FileEditorActivity for manual AI retry.")
            launchAiEditorInteraction(intent)
          } else {
            Log.e(DEBUG_TAG, "showManualBuildFailedDialog: Cannot open AI editor (manual): Project details missing.")
            Toast.makeText(this, getString(R.string.msg_cannot_open_ai_editor_project_details_missing), Toast.LENGTH_LONG).show()
          }
        },
        getString(android.R.string.cancel),
        { dialog, _ ->
          Log.d(DEBUG_TAG, "showManualBuildFailedDialog: User clicked 'Cancel'. Disabling global auto-fix.")
          dialog.dismiss()
          AutoFixStateManager.disableAutoFixMode()
          updateAutoFixModeIndicator()
        }
      )

      dialogBuilder.setCancelable(true)
      dialogBuilder.show()
    }
  }

  private suspend fun findApk(): File? = withContext(Dispatchers.IO) {
    val projectManager = ProjectManagerImpl.getInstance()
    val projectDir = try { projectManager.projectDir } catch (e: Exception) { null }
    if (projectDir == null) {
      log.error("Cannot find APK, project directory is not available.")
      return@withContext null
    }

    val workspace = projectManager.getWorkspace()
    val appModules = workspace?.getSubProjects()
      ?.filterIsInstance<AndroidModule>()
      ?.filter { it.isApplication }

    if (appModules.isNullOrEmpty()) {
      log.warn("No application modules found. Falling back to generic search in common directories.")
      val searchBase = File(projectDir, "app/build/outputs/apk")
      return@withContext searchInDirectory(searchBase)
    }

    for (module in appModules) {
      val searchDir = File(module.projectDir, "build/outputs/apk")
      val foundApk = searchInDirectory(searchDir)
      if (foundApk != null) {
        log.info("Found APK in module '${module.name}' at path: ${foundApk.path}")
        return@withContext foundApk
      }
    }

    log.error("Could not find any APK in any application module's output directory.")
    return@withContext null
  }

  private fun searchInDirectory(directory: File): File? {
    if (!directory.exists() || !directory.isDirectory) return null

    val apks = directory.walk()
      .filter { it.isFile && it.name.endsWith(".apk") }
      .toList()

    return apks.filter { it.name.contains("debug", ignoreCase = true) }
      .maxByOrNull { it.lastModified() }
      ?: apks.maxByOrNull { it.lastModified() }
  }

  fun handleAutoFixBuildSuccess(tasks: List<String?>) {
    val wasAssemblyTask = tasks.any { it?.contains("assemble", ignoreCase = true) == true }

    if (wasAssemblyTask) {
      try {
        Log.i(TAG, "Full assembly build successful. Creating versioned snapshot.")
        val projectManager = ProjectManagerImpl.getInstance()
        val currentProjectDir = projectManager.projectDir
        val baseProjectName = currentProjectDir.name.substringBeforeLast("_v")
        val projectsBaseDir = currentProjectDir.parentFile

        if (projectsBaseDir != null) {
          val dummyBridge = object : ViewModelFileEditorBridge {
            override var currentProjectDirBridge: File? = null
            override val isModifyingExistingProjectBridge: Boolean = false
            override fun updateStateBridge(newState: AiWorkflowState) {}
            override fun appendToLogBridge(text: String) { Log.i("VersionSnapshot", text) }
            override fun displayAiConclusionBridge(conclusion: String?) {}
            override fun handleErrorBridge(message: String, e: Exception?) { Log.e("VersionSnapshot", message, e) }
            override fun runOnUiThreadBridge(block: () -> Unit) {}
            override fun getContextBridge(): Context = this@EditorHandlerActivity
            override fun onTemplateProjectCreatedBridge(projectDir: File, appName: String, appDescription: String) {}
          }

          val opsHandler = ProjectOperationsHandler(
            projectsBaseDir = projectsBaseDir,
            directLogAppender = { msg -> Log.i("VersionSnapshot", msg) },
            directErrorHandler = { msg, ex -> Log.e("VersionSnapshot", msg, ex) },
            bridge = dummyBridge
          )

          opsHandler.createVersionedCopy(currentProjectDir, baseProjectName)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to create versioned snapshot. This will not block app installation.", e)
        Toast.makeText(this, "Warning: Could not save project snapshot.", Toast.LENGTH_SHORT).show()
      }

      if (!AutoFixStateManager.isAutoFixModeGloballyActive) {
        Log.d(DEBUG_TAG, "Build successful, but auto-fix mode is not active. No automatic installation.")
        return
      }

      Log.i(DEBUG_TAG, "Full assembly build successful. Searching for APK to install.")
      Toast.makeText(this, "Build successful! Installing app...", Toast.LENGTH_LONG).show()

      lifecycleScope.launch {
        val apkFile = findApk()
        if (apkFile != null) {
          Log.i(DEBUG_TAG, "handleAutoFixBuildSuccess: Found APK at ${apkFile.absolutePath}. Triggering installation.")
          installApk(apkFile)
          Log.i(DEBUG_TAG, "handleAutoFixBuildSuccess: Disabling auto-fix mode after successful build and run.")
          AutoFixStateManager.disableAutoFixMode()
          updateAutoFixModeIndicator()
        } else {
          Log.e(DEBUG_TAG, "handleAutoFixBuildSuccess: Build was successful, but could not find the output APK. Stopping auto-fix.")
          Toast.makeText(this@EditorHandlerActivity, "Build OK, but couldn't find APK to install.", Toast.LENGTH_LONG).show()
          AutoFixStateManager.disableAutoFixMode()
          updateAutoFixModeIndicator()
        }
      }
    } else if (AutoFixStateManager.isAutoFixModeGloballyActive) {
      Log.w(DEBUG_TAG, "Partial build successful (tasks: ${tasks.joinToString()}). Re-triggering build process to continue.")

      lifecycleScope.launch {
        delay(1500)
        if (isFinishing || isDestroyed) return@launch

        Log.i(DEBUG_TAG, "handleAutoFixBuildSuccess: Programmatically 'pressing' the run button again.")
        initiateAutoQuickRun()
      }
    }
  }

  override fun onProjectInitialized(result: InitializeResult) {
    super.onProjectInitialized(result)
    Log.i(DEBUG_TAG, "onProjectInitialized: Result successful: ${result.isSuccessful}. Global AutoFix Active: ${AutoFixStateManager.isAutoFixModeGloballyActive}")

    if (AutoFixStateManager.isAutoFixModeGloballyActive) {
      if (result.isSuccessful) {
        Log.i(DEBUG_TAG, "onProjectInitialized: Project sync successful & Auto-fix IS globally active. Scheduling auto-run.")
      } else {
        Log.w(DEBUG_TAG, "onProjectInitialized: Project sync FAILED (success=${result.isSuccessful}) but Auto-fix IS globally active. STILL Scheduling auto-run to let the build decide.")
        Toast.makeText(this, "Notice: Project sync had issues after AI fix, attempting build anyway...", Toast.LENGTH_LONG).show()
      }
      lifecycleScope.launch(Dispatchers.Main) {
        Log.d(DEBUG_TAG, "onProjectInitialized: Starting ${AI_FIX_RUN_DELAY_MS}ms delay before auto-run.")
        delay(AI_FIX_RUN_DELAY_MS)
        if (isDestroyed || isFinishing) {
          Log.w(DEBUG_TAG, "onProjectInitialized: Activity destroyed/finishing during auto-run delay.")
          return@launch
        }
        Log.i(DEBUG_TAG, "onProjectInitialized: Attempting to auto-run project (after AI fix flow / or just because auto-fix is on and sync finished).")
        initiateAutoQuickRun()
      }
    } else if (!result.isSuccessful) {
      Log.w(DEBUG_TAG, "onProjectInitialized: Project sync failed, and global auto-fix was NOT active. No special action.")
    }
  }

  override fun postProjectInit(isSuccessful: Boolean, failure: TaskExecutionResult.Failure?) {
    super.postProjectInit(isSuccessful, failure)
    Log.i(DEBUG_TAG, "postProjectInit: Project sync finished. Successful: $isSuccessful. Global AutoFix Active: ${AutoFixStateManager.isAutoFixModeGloballyActive}. Failure: ${failure?.name}")
    if (!isSuccessful && AutoFixStateManager.isAutoFixModeGloballyActive) {
      Log.w(DEBUG_TAG, "postProjectInit: Project sync FAILED (isSuccessful=false) while AutoFixStateManager was active. Global auto-fix REMAINS ACTIVE. Build from auto-run will determine next steps.")
    }
  }

  override fun saveOpenedFiles() {
    writeOpenedFilesCache(getOpenedFiles(), getCurrentEditor()?.editor?.file)
  }

  private fun writeOpenedFilesCache(openedFiles: List<OpenedFile>, selectedFile: File?) {
    if (selectedFile == null || openedFiles.isEmpty()) {
      editorViewModel.writeOpenedFiles(null)
      editorViewModel.openedFilesCache = null
      log.debug("[onPause] No opened files. Opened files cache reset to null.")
      isOpenedFilesSaved.set(true)
      return
    }
    val cache = OpenedFilesCache(selectedFile = selectedFile.absolutePath, allFiles = openedFiles)
    editorViewModel.writeOpenedFiles(cache)
    editorViewModel.openedFilesCache = if (!isDestroying) cache else null
    log.debug("[onPause] Opened files cache reset to {}", editorViewModel.openedFilesCache)
    isOpenedFilesSaved.set(true)
  }

  private fun onReadOpenedFilesCache(cache: OpenedFilesCache?) {
    cache ?: return
    lifecycleScope.launch(Dispatchers.Main) {
      cache.allFiles.forEach { file ->
        openFile(File(file.filePath), file.selection)
      }
      openFile(File(cache.selectedFile))
    }
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    prepareOptionsMenu(menu)
    return true
  }

  @SuppressLint("RestrictedApi")
  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    if (menu is MenuBuilder) {
      menu.setOptionalIconsVisible(true)
    }
    val data = createToolbarActionData()
    ActionsRegistry.getInstance().fillMenu(FillMenuParams(data, Location.EDITOR_TOOLBAR, menu))
    return true
  }

  open fun prepareOptionsMenu(menu: Menu) {
    val data = createToolbarActionData()
    val actions = ActionsRegistry.getInstance().getActions(Location.EDITOR_TOOLBAR)
    actions.forEach { (_, action) ->
      menu.findItem(action.itemId)?.let { item ->
        action.prepare(data)
        item.isVisible = action.visible
        item.isEnabled = action.enabled
        item.title = action.label
        item.icon = action.icon?.mutate()?.apply {
          colorFilter = action.createColorFilter(data)
          alpha = if (action.enabled) 255 else 76
        }
        var showAsAction = action.getShowAsActionFlags(data)
        if (showAsAction == -1) {
          showAsAction = if (action.icon != null) {
            MenuItem.SHOW_AS_ACTION_IF_ROOM
          } else {
            MenuItem.SHOW_AS_ACTION_NEVER
          }
        }
        if (!action.enabled) {
          showAsAction = MenuItem.SHOW_AS_ACTION_NEVER
        }
        item.setShowAsAction(showAsAction)
        action.createActionView(data)?.let { item.actionView = it }
      }
    }
    content.editorToolbar.updateMenuDisplay()
  }

  private fun createToolbarActionData(): ActionData {
    val data = ActionData()
    val currentEditor = getCurrentEditor()
    data.put(Context::class.java, this)
    data.put(CodeEditorView::class.java, currentEditor)
    if (currentEditor != null) {
      data.put(IDEEditor::class.java, currentEditor.editor)
      data.put(File::class.java, currentEditor.file)
    }
    return data
  }

  override fun getCurrentEditor(): CodeEditorView? {
    return if (editorViewModel.getCurrentFileIndex() != -1) {
      getEditorAtIndex(editorViewModel.getCurrentFileIndex())
    } else null
  }

  override fun getEditorAtIndex(index: Int): CodeEditorView? {
    return if (index >= 0 && index < content.editorContainer.childCount) {
      content.editorContainer.getChildAt(index) as? CodeEditorView
    } else {
      log.warn("getEditorAtIndex: Invalid index {} or child not a CodeEditorView. Child count: {}", index, content.editorContainer.childCount)
      null
    }
  }

  override fun openFileAndSelect(file: File, selection: Range?) {
    openFile(file, selection)
    getEditorForFile(file)?.editor?.also { editor ->
      editor.postInLifecycle {
        if (selection == null) {
          editor.setSelection(0, 0)
          return@postInLifecycle
        }
        editor.validateRange(selection)
        editor.setSelection(selection)
      }
    }
  }

  override fun openFile(file: File, selection: Range?): CodeEditorView? {
    val range = selection ?: Range.NONE
    if (ImageUtils.isImage(file)) {
      openImage(this, file)
      return null
    }
    val index = openFileAndGetIndex(file, range)
    if (index < 0) {
      log.warn("Failed to open file or get index for: {}", file.absolutePath)
      return null
    }
    val tab = content.tabs.getTabAt(index)
    if (tab != null && !tab.isSelected) {
      tab.select()
    }
    editorViewModel.startDrawerOpened = false
    editorViewModel.displayedFileIndex = index
    return try {
      getEditorAtIndex(index)
    } catch (th: Throwable) {
      log.error("Unable to get editor fragment at opened file index {}", index, th)
      null
    }
  }

  override fun openFileAndGetIndex(file: File, selection: Range?): Int {
    val openedFileIndex = findIndexOfEditorByFile(file)
    if (openedFileIndex != -1) {
      editorViewModel.displayedFileIndex = openedFileIndex
      return openedFileIndex
    }
    if (!file.exists()) {
      log.error("File does not exist, cannot open: {}", file.absolutePath)
      flashError("Error: File not found ${file.name}")
      return -1
    }
    if (!file.canRead()) {
      log.error("File cannot be read, cannot open: {}", file.absolutePath)
      flashError("Error: Cannot read file ${file.name}")
      return -1
    }

    val position = editorViewModel.getOpenedFileCount()
    log.info("Opening file at index {} file:{}", position, file)
    val editor = CodeEditorView(this, file, selection ?: Range.NONE)
    editor.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    content.editorContainer.addView(editor)
    content.tabs.addTab(content.tabs.newTab())
    editorViewModel.addFile(file)
    updateTabs()
    return position
  }

  override fun getEditorForFile(file: File): CodeEditorView? {
    for (i in 0 until content.editorContainer.childCount) {
      val editor = content.editorContainer.getChildAt(i) as? CodeEditorView
      if (file == editor?.file) return editor
    }
    return null
  }

  override fun findIndexOfEditorByFile(file: File?): Int {
    if (file == null) {
      log.warn("Cannot find index of a null file.")
      return -1
    }
    for (i in 0 until editorViewModel.getOpenedFileCount()) {
      val openedEditorFile: File = editorViewModel.getOpenedFile(i)
      if (openedEditorFile == file) {
        return i
      }
    }
    return -1
  }

  override fun saveAllAsync(
    notify: Boolean,
    requestSync: Boolean,
    processResources: Boolean,
    progressConsumer: ((Int, Int) -> Unit)?,
    runAfter: (() -> Unit)?
  ) {
    lifecycleScope.launch {
      saveAll(notify, requestSync, processResources, progressConsumer)
      runAfter?.let { nonNullRunAfter ->
        withContext(Dispatchers.Main) {
          nonNullRunAfter.invoke()
        }
      }
    }
  }

  override suspend fun saveAll(
    notify: Boolean,
    requestSync: Boolean,
    processResources: Boolean,
    progressConsumer: ((Int, Int) -> Unit)?
  ): Boolean {
    val result = saveAllResult(progressConsumer)
    withContext(Dispatchers.Main) {
      if (notify) {
        flashSuccess(R.string.all_saved)
      }
      if (result.gradleSaved && requestSync) {
        log.info("Gradle files saved, setting isSyncNeeded to true.")
        editorViewModel.isSyncNeeded = true
      }
    }
    if (processResources) {
      withContext(Dispatchers.IO) {
        ProjectManagerImpl.getInstance().generateSources()
      }
    }
    return result.gradleSaved
  }

  override suspend fun saveAllResult(progressConsumer: ((Int, Int) -> Unit)?): SaveResult {
    return performFileSave {
      val result = SaveResult()
      for (i in 0 until editorViewModel.getOpenedFileCount()) {
        saveResultInternal(i, result)
        withContext(Dispatchers.Main.immediate) {
          progressConsumer?.invoke(i + 1, editorViewModel.getOpenedFileCount())
        }
      }
      result
    }
  }

  override suspend fun saveResult(index: Int, result: SaveResult) {
    performFileSave {
      saveResultInternal(index, result)
    }
  }

  private suspend fun saveResultInternal(index: Int, result: SaveResult) : Boolean {
    if (index < 0 || index >= editorViewModel.getOpenedFileCount()) {
      log.warn("saveResultInternal: Invalid index {}", index)
      return false
    }
    val editorView = getEditorAtIndex(index) ?: run {
      log.warn("saveResultInternal: No editor view at index {}", index)
      return false
    }
    val fileName = editorView.file?.name ?: run {
      log.warn("saveResultInternal: Editor at index {} has null file or name", index)
      return false
    }

    val modified = editorView.isModified
    if (!editorView.save()) {
      log.warn("saveResultInternal: Failed to save file {}", fileName)
      return false
    }

    val isGradle = fileName.endsWith(".gradle", ignoreCase = true) || fileName.endsWith(".gradle.kts", ignoreCase = true)
    val isXml: Boolean = fileName.endsWith(".xml", ignoreCase = true)
    if (modified && isGradle) {
      result.gradleSaved = true
    }
    if (modified && isXml) {
      result.xmlSaved = true
    }

    withContext(Dispatchers.Main.immediate) {
      editorViewModel.areFilesModified = hasUnsavedFiles()
      val tab = content.tabs.getTabAt(index)
      if (tab?.text?.startsWith('*') == true) {
        tab.text = tab.text!!.substring(startIndex = 1)
      }
    }
    return true
  }

  private fun hasUnsavedFiles() = (0 until editorViewModel.getOpenedFileCount()).any { i ->
    getEditorAtIndex(i)?.isModified == true
  }

  private suspend inline fun <T : Any?> performFileSave(crossinline action: suspend () -> T) : T {
    setFilesSaving(true)
    try {
      return action()
    } finally {
      setFilesSaving(false)
    }
  }

  private suspend fun setFilesSaving(saving: Boolean) {
    withContext(Dispatchers.Main.immediate) {
      editorViewModel.areFilesSaving = saving
    }
  }

  override fun areFilesModified(): Boolean {
    return editorViewModel.areFilesModified
  }

  override fun areFilesSaving(): Boolean {
    return editorViewModel.areFilesSaving
  }

  override fun closeFile(index: Int, runAfter: () -> Unit) {
    if (index < 0 || index >= editorViewModel.getOpenedFileCount()) {
      log.warn("Invalid file index {}. Cannot close.", index)
      runOnUiThread(runAfter)
      return
    }
    val openedFile = editorViewModel.getOpenedFile(index)
    log.info("Closing file: {}", openedFile)
    val editor = getEditorAtIndex(index)

    if (editor?.isModified == true) {
      log.info("File has been modified: {}", openedFile)
      notifyFilesUnsaved(listOfNotNull(editor)) {
        closeFile(index, runAfter)
      }
      return
    }

    editor?.close()
    editorViewModel.removeFile(index)
    content.tabs.removeTabAt(index)
    if (index < content.editorContainer.childCount) {
      content.editorContainer.removeViewAt(index)
    } else {
      log.warn("Attempted to remove view at index {} but container child count is {}", index, content.editorContainer.childCount)
    }

    editorViewModel.areFilesModified = hasUnsavedFiles()
    updateTabs()
    runOnUiThread(runAfter)
  }

  override fun closeOthers() {
    if (editorViewModel.getOpenedFileCount() <= 1) {
      return
    }
    val currentEditorIndex = editorViewModel.getCurrentFileIndex()
    if (currentEditorIndex == -1 && editorViewModel.getOpenedFileCount() > 0) {
      log.warn("closeOthers called with no current file selected, but multiple files open.")
    }

    val unsavedEditors = mutableListOf<CodeEditorView>()
    for (i in 0 until editorViewModel.getOpenedFileCount()) {
      if (i == currentEditorIndex) continue
      getEditorAtIndex(i)?.let { editor ->
        if (editor.isModified) {
          unsavedEditors.add(editor)
        }
      }
    }

    if (unsavedEditors.isNotEmpty()) {
      notifyFilesUnsaved(unsavedEditors) { closeOthers() }
      return
    }

    for (i in editorViewModel.getOpenedFileCount() - 1 downTo 0) {
      if (i == currentEditorIndex) continue
      getEditorAtIndex(i)?.close()
      editorViewModel.removeFile(i)
      content.tabs.removeTabAt(i)
      if (i < content.editorContainer.childCount) {
        content.editorContainer.removeViewAt(i)
      }
    }
    editorViewModel.areFilesModified = hasUnsavedFiles()
    updateTabs()
  }

  override fun closeAll(runAfter: () -> Unit) {
    val unsavedEditors = (0 until editorViewModel.getOpenedFileCount())
      .mapNotNull { getEditorAtIndex(it) }
      .filter { it.isModified }

    if (unsavedEditors.isNotEmpty()) {
      notifyFilesUnsaved(unsavedEditors) { closeAll(runAfter) }
      return
    }

    for (i in 0 until editorViewModel.getOpenedFileCount()) {
      getEditorAtIndex(i)?.close()
    }

    editorViewModel.removeAllFiles()
    content.tabs.removeAllTabs()
    content.editorContainer.removeAllViews()
    editorViewModel.areFilesModified = false
    runOnUiThread(runAfter)
  }

  override fun getOpenedFiles() =
    editorViewModel.getOpenedFiles().mapNotNull { file ->
      val editor = getEditorForFile(file)?.editor ?: return@mapNotNull null
      OpenedFile(file.absolutePath, editor.cursorLSPRange)
    }

  private fun notifyFilesUnsaved(unsavedEditors: List<CodeEditorView>, invokeAfter: () -> Unit) {
    if (isDestroying) {
      unsavedEditors.forEach { editor -> editor.markAsSaved() }
      runOnUiThread(invokeAfter)
      return
    }

    val mappedFilePaths = unsavedEditors.mapNotNull { it.file?.absolutePath }
    if (mappedFilePaths.isEmpty()) {
      if (unsavedEditors.any { it.isModified }) {
        log.warn("notifyFilesUnsaved: Some modified editors have no file path but are marked modified.")
        unsavedEditors.forEach { it.markAsSaved() }
      }
      runOnUiThread(invokeAfter)
      return
    }

    val builder =
      DialogUtils.newYesNoDialog(
        context = this,
        title = getString(R.string.title_files_unsaved),
        message = getString(R.string.msg_files_unsaved, TextUtils.join("\n", mappedFilePaths)),
        positiveClickListener = { dialog, _ ->
          dialog.dismiss()
          saveAllAsync(notify = true, requestSync = false, processResources = false, runAfter = invokeAfter)
        },
        negativeClickListener = { dialog, _ ->
          dialog.dismiss()
          unsavedEditors.forEach { editor -> editor.markAsSaved() }
          runOnUiThread(invokeAfter)
        }
      )
    builder.setCancelable(false)
    builder.show()
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  fun onFileRenamed(event: FileRenameEvent) {
    val index = findIndexOfEditorByFile(event.file)
    if (index < 0 || index >= content.tabs.tabCount) {
      return
    }
    val editor = getEditorAtIndex(index) ?: return
    editorViewModel.updateFile(index, event.newFile)
    editor.updateFile(event.newFile)
    updateTabs()
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  fun onDocumentChange(event: DocumentChangeEvent) {
    editorViewModel.areFilesModified = true
    val changedFile = event.file.toFile()
    val index = findIndexOfEditorByFile(changedFile)
    if (index == -1) {
      return
    }
    val tab = content.tabs.getTabAt(index) ?: return
    if (tab.text?.startsWith('*') == true) {
      return
    }
    tab.text = "*${tab.text}"
  }

  private fun updateTabs() {
    lifecycleScope.launch(Dispatchers.Default) {
      val files = editorViewModel.getOpenedFiles()
      if (files.isEmpty() && content.tabs.tabCount == 0) {
        return@launch
      }

      val dupliCount = mutableMapOf<String, Int>()
      val names = MutableIntObjectMap<Pair<String, @DrawableRes Int>>()
      val nameBuilder = UniqueNameBuilder<File>("", File.separator)

      files.forEach { file ->
        val currentCount = dupliCount.getOrPut(file.name) { 0 }
        dupliCount[file.name] = currentCount + 1
        nameBuilder.addPath(file, file.path)
      }

      for (index in files.indices) {
        val file = files.getOrNull(index) ?: continue
        val editorView = getEditorForFile(file)
        val isModified = editorView?.isModified ?: false

        var name = if ((dupliCount[file.name] ?: 0) > 1) {
          nameBuilder.getShortPath(file)
        } else {
          file.name
        }
        if (isModified) {
          name = "*$name"
        }
        names.put(index, name to FileExtension.Factory.forFile(file).icon)
      }

      withContext(Dispatchers.Main) {
        if (content.tabs.tabCount != files.size) {
          log.warn("updateTabs: Mismatch between tab count (${content.tabs.tabCount}) and file count (${files.size}).")
        }
        val countToUpdate = min(content.tabs.tabCount, files.size)
        for (i in 0 until countToUpdate) {
          val tab = content.tabs.getTabAt(i) ?: continue
          val nameAndIcon = names.get(i)
          if (nameAndIcon != null) {
            tab.icon = ResourcesCompat.getDrawable(resources, nameAndIcon.second, theme)
            tab.text = nameAndIcon.first
          } else {
            log.warn("updateTabs: No name/icon info for tab at index $i")
          }
        }
      }
    }
  }

  fun clearCapturedBuildLog() {
    Log.d(DEBUG_TAG, "clearCapturedBuildLog called")
    buildLogBuilder.clear()
  }

  fun captureBuildLogLine(line: String) {
    buildLogBuilder.append(line).append("\n")
  }

  private fun getCompleteCapturedBuildOutput(): String {
    return buildLogBuilder.toString().trim()
  }
}