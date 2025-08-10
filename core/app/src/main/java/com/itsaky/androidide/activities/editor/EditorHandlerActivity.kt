// EditorHandlerActivity.kt (full file, with guards to prevent "project dir not set" crash)

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
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.dialogs.AutoFixStateManager
import com.itsaky.androidide.dialogs.FileEditorActivity
import com.itsaky.androidide.editor.language.treesitter.JavaLanguage
import com.itsaky.androidide.editor.language.treesitter.JsonLanguage
import com.itsaky.androidide.editor.language.treesitter.KotlinLanguage
import com.itsaky.androidide.editor.language.treesitter.LogLanguage
import com.itsaky.androidide.editor.language.treesitter.TSLanguageRegistry
import com.itsaky.androidide.editor.language.treesitter.TreeSitterLanguage
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
import com.itsaky.androidide.projects.android.AndroidModule
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

open class EditorHandlerActivity : ProjectHandlerActivity(), IEditorHandler {

  protected val isOpenedFilesSaved = AtomicBoolean(false)
  private val buildLogBuilder = StringBuilder()

  // NEW: gate writes to "opened files" cache to avoid crashes when projectDir is not set
  private val allowOpenedFilesCacheWrites = AtomicBoolean(true)

  companion object {
    private const val DEBUG_TAG = "AutoFixDebug"
    private const val TAG = "EditorHandlerActivity"
    private const val AI_FIX_RUN_DELAY_MS = 2500L
    const val EXTRA_AUTO_BUILD_PROJECT = "com.itsaky.androidide.AUTO_BUILD_PROJECT"

    // Routing flags to deliver an AI editor launch request to MainActivity
    private const val EXTRA_REQUEST_AI_EDITOR = "com.itsaky.androidide.REQUEST_AI_EDITOR"
    private const val EXTRA_AI_PROJECTS_ROOT_OVERRIDE = "com.itsaky.androidide.AI_PROJECTS_ROOT_OVERRIDE"
  }

  val isAutoFixModeActivePublic: Boolean
    get() = AutoFixStateManager.isAutoFixModeGloballyActive

  // Kept for compatibility (legacy path); not used by the new routing.
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
        updateAutoFixModeIndicator()

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
    // Stop any further cache writes during teardown
    allowOpenedFilesCacheWrites.set(false)
    // Do NOT destroy the global TS registry here; other activities may depend on it.
    // TSLanguageRegistry.instance.destroy()
    editorViewModel.removeAllFiles()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    Log.d(DEBUG_TAG, "EditorHandlerActivity: onCreate START. savedInstanceState isNull: ${savedInstanceState == null}")
    mBuildEventListener.setActivity(this)
    super.onCreate(savedInstanceState)

    handleIntentExtras(intent)

    Log.d(DEBUG_TAG, "EditorHandlerActivity: onCreate - Global AutoFix Active on entry: ${AutoFixStateManager.isAutoFixModeGloballyActive}")
    editorViewModel._displayedFile.observe(this) { this.content.editorContainer.displayedChild = it }
    editorViewModel._startDrawerOpened.observe(this) { opened ->
      this.binding.editorDrawerLayout.apply {
        if (opened) openDrawer(GravityCompat.START) else closeDrawer(GravityCompat.START)
      }
    }
    editorViewModel._filesModified.observe(this) { invalidateOptionsMenu() }
    editorViewModel._filesSaving.observe(this) { invalidateOptionsMenu() }

    // IMPORTANT: Only write opened-files cache when projectDir is set and writes are allowed.
    editorViewModel.observeFiles(this) {
      if (!allowOpenedFilesCacheWrites.get()) {
        Log.d(TAG, "observeFiles: Cache writes disabled; skipping.")
        return@observeFiles
      }
      val projectIsOpen = try {
        ProjectManagerImpl.getInstance().projectDir
        true
      } catch (_: IllegalStateException) {
        false
      }
      if (!projectIsOpen) {
        Log.d(TAG, "observeFiles: Project dir not set; skipping cache write.")
        editorViewModel.openedFilesCache = null
        return@observeFiles
      }

      val currentFile = getCurrentEditor()?.editor?.file?.absolutePath ?: run {
        // Avoid calling writeOpenedFiles(null) here to prevent background coroutine using missing project dir
        editorViewModel.openedFilesCache = null
        return@observeFiles
      }

      getOpenedFiles().also { opened ->
        if (!allowOpenedFilesCacheWrites.get()) return@observeFiles
        val cache = OpenedFilesCache(currentFile, opened)
        // Project is open; safe to enqueue write
        editorViewModel.writeOpenedFiles(cache)
        editorViewModel.openedFilesCache = cache
      }
    }

    // Idempotent TS language registration (prevents AlreadyRegisteredException)
    lifecycleScope.launch {
      safeRegister<JavaLanguage>(JavaLanguage.TS_TYPE, JavaLanguage.FACTORY)
      safeRegister<KotlinLanguage>(KotlinLanguage.TS_TYPE_KT, KotlinLanguage.FACTORY)
      safeRegister<KotlinLanguage>(KotlinLanguage.TS_TYPE_KTS, KotlinLanguage.FACTORY)
      safeRegister<LogLanguage>(LogLanguage.TS_TYPE, LogLanguage.FACTORY)
      safeRegister<JsonLanguage>(JsonLanguage.TS_TYPE, JsonLanguage.FACTORY)
      safeRegister<XMLLanguage>(XMLLanguage.TS_TYPE, XMLLanguage.FACTORY)
      IDEColorSchemeProvider.initIfNeeded()
    }

    updateAutoFixModeIndicator()
    Log.d(DEBUG_TAG, "EditorHandlerActivity: onCreate END. Global AutoFix Active: ${AutoFixStateManager.isAutoFixModeGloballyActive}")
  }

  // Generic, typed, idempotent registration helper
  private fun <T : TreeSitterLanguage> safeRegister(
    type: String,
    factory: TreeSitterLanguage.Factory<T>
  ) {
    try {
      TSLanguageRegistry.instance.register(type, factory)
    } catch (e: TSLanguageRegistry.AlreadyRegisteredException) {
      Log.d(TAG, "TS language already registered: $type — skipping")
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to register TS language: $type", t)
    }
  }

  private fun handleIntentExtras(intent: Intent?) {
    if (intent == null) return

    val shouldAutoBuild = intent.getBooleanExtra(EXTRA_AUTO_BUILD_PROJECT, false)
    if (shouldAutoBuild) {
      Log.i(TAG, "Received EXTRA_AUTO_BUILD_PROJECT. Triggering build automatically.")
      binding.root.postDelayed({ initiateAutoQuickRun() }, 500)
    }
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

  // Route to MainActivity (singleTop) to launch AI editor and finish this Editor.
  private fun requestAiEditorViaMainActivity(
    projectsBaseDirPath: String?,
    prefillName: String?,
    prefillDescription: String?,
    isAutoRetryAttempt: Boolean
  ) {
    Log.d(DEBUG_TAG, "Routing to MainActivity to launch AI Editor. Finishing current Editor.")
    // Stop any future cache writes before we finish
    allowOpenedFilesCacheWrites.set(false)

    val intent = Intent(this, MainActivity::class.java).apply {
      putExtra(EXTRA_REQUEST_AI_EDITOR, true)
      putExtra(EXTRA_AI_PROJECTS_ROOT_OVERRIDE, projectsBaseDirPath)
      if (!prefillName.isNullOrBlank()) {
        putExtra(FileEditorActivity.EXTRA_PREFILL_APP_NAME, prefillName)
      }
      if (!prefillDescription.isNullOrBlank()) {
        putExtra(FileEditorActivity.EXTRA_PREFILL_APP_DESCRIPTION, prefillDescription)
      }
      putExtra(FileEditorActivity.EXTRA_IS_AUTO_RETRY_ATTEMPT, isAutoRetryAttempt)
      // Reuse existing MainActivity if available
      addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    startActivity(intent)
    // Ensure this Editor finishes so a fresh Editor can start later
    finish()
  }

  private fun launchAiEditorInteraction(intent: Intent) {
    // Legacy path (kept for compatibility, but not used in our new routing)
    Log.d(DEBUG_TAG, "EditorHandlerActivity: launchAiEditorInteraction called (legacy)")
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
      content.bottomSheet.setAutoFixIndicatorVisibility(isActive)
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

    val wasAssemblyTask = tasks.orEmpty().any { it?.contains("assemble", ignoreCase = true) == true }
    if (!wasAssemblyTask) {
      showManualBuildFailedDialog(capturedLog, currentProjectName, projectsBaseDirPath)
      return
    }

    if (AutoFixStateManager.canAttemptAutoFix()) {
      if (currentProjectName != null && projectsBaseDirPath != null) {
        if (AutoFixStateManager.consumeAttempt()) {
          val attemptsMade = AutoFixStateManager.MAX_GLOBAL_AUTO_FIX_ATTEMPTS - AutoFixStateManager.autoFixAttemptsRemainingGlobal
          val toastMessage = "Auto-fixing build error (Attempt $attemptsMade/${AutoFixStateManager.MAX_GLOBAL_AUTO_FIX_ATTEMPTS})..."
          Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()

          val combinedDescriptionForAi = """
                        Original App Description:
                        ${AutoFixStateManager.initialAppDescriptionForGlobalAutoFix}

                        ---
                        Please analyze the original app description and the following build failure. Modify the code to fix the build error while adhering to the original app goals:
                        ${getString(R.string.ai_fix_failure_prefix)}${capturedLog}
                    """.trimIndent()

          // ROUTE via MainActivity and FINISH this Editor first
          requestAiEditorViaMainActivity(
            projectsBaseDirPath,
            currentProjectName,
            combinedDescriptionForAi,
            isAutoRetryAttempt = true
          )
          return
        } else {
          AutoFixStateManager.disableAutoFixMode()
          updateAutoFixModeIndicator()
          showManualBuildFailedDialog(capturedLog, currentProjectName, projectsBaseDirPath)
        }
      } else {
        Toast.makeText(this, "Auto-fix: Project details missing. Stopping.", Toast.LENGTH_LONG).show()
        AutoFixStateManager.disableAutoFixMode()
        updateAutoFixModeIndicator()
        showManualBuildFailedDialog(capturedLog, currentProjectName, projectsBaseDirPath)
      }
    } else {
      if (AutoFixStateManager.isAutoFixModeGloballyActive && AutoFixStateManager.autoFixAttemptsRemainingGlobal <= 0) {
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
          // User is taking manual control; disable global auto-fix
          AutoFixStateManager.disableAutoFixMode()
          updateAutoFixModeIndicator()

          if (currentProjectName != null && projectsBaseDirPath != null) {
            // ROUTE via MainActivity and FINISH this Editor
            requestAiEditorViaMainActivity(
              projectsBaseDirPath,
              currentProjectName,
              fullDialogMessage,
              isAutoRetryAttempt = false
            )
          } else {
            Toast.makeText(this, getString(R.string.msg_cannot_open_ai_editor_project_details_missing), Toast.LENGTH_LONG).show()
          }
        },
        getString(android.R.string.cancel),
        { dialog, _ ->
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
    if (projectDir == null) return@withContext null

    val workspace = projectManager.getWorkspace()
    val appModules = workspace?.getSubProjects()
      ?.filterIsInstance<AndroidModule>()
      ?.filter { it.isApplication }

    if (appModules.isNullOrEmpty()) {
      val searchBase = File(projectDir, "app/build/outputs/apk")
      return@withContext searchInDirectory(searchBase)
    }

    for (module in appModules) {
      val searchDir = File(module.projectDir, "build/outputs/apk")
      val foundApk = searchInDirectory(searchDir)
      if (foundApk != null) return@withContext foundApk
    }
    null
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
    Log.d(DEBUG_TAG, "handleAutoFixBuildSuccess: Entered for tasks: ${tasks.joinToString()}. Global AutoFix Active=${AutoFixStateManager.isAutoFixModeGloballyActive}")
    if (!AutoFixStateManager.isAutoFixModeGloballyActive) return

    val wasAssemblyTask = tasks.any { it?.contains("assemble", ignoreCase = true) == true }

    if (wasAssemblyTask) {
      Toast.makeText(this, "Build successful! Installing app...", Toast.LENGTH_LONG).show()

      lifecycleScope.launch {
        val apkFile = findApk()
        if (apkFile != null) {
          installApk(apkFile)
          AutoFixStateManager.disableAutoFixMode()
          updateAutoFixModeIndicator()
        } else {
          Toast.makeText(this@EditorHandlerActivity, "Build OK, but couldn't find APK to install.", Toast.LENGTH_LONG).show()
          AutoFixStateManager.disableAutoFixMode()
          updateAutoFixModeIndicator()
        }
      }
    } else {
      lifecycleScope.launch {
        delay(1500)
        if (isFinishing || isDestroyed) return@launch
        initiateAutoQuickRun()
      }
    }
  }

  override fun onProjectInitialized(result: InitializeResult) {
    super.onProjectInitialized(result)
    if (AutoFixStateManager.isAutoFixModeGloballyActive) {
      lifecycleScope.launch(Dispatchers.Main) {
        delay(AI_FIX_RUN_DELAY_MS)
        if (isDestroyed || isFinishing) return@launch
        initiateAutoQuickRun()
      }
    }
  }

  override fun postProjectInit(isSuccessful: Boolean, failure: TaskExecutionResult.Failure?) {
    super.postProjectInit(isSuccessful, failure)
    if (!isSuccessful && AutoFixStateManager.isAutoFixModeGloballyActive) {
      Log.w(DEBUG_TAG, "postProjectInit: Project sync FAILED while AutoFix active.")
    }
  }

  override fun saveOpenedFiles() {
    if (!allowOpenedFilesCacheWrites.get()) {
      isOpenedFilesSaved.set(true)
      return
    }
    val projectIsOpen = try {
      ProjectManagerImpl.getInstance().projectDir
      true
    } catch (_: IllegalStateException) {
      false
    }
    if (!projectIsOpen) {
      Log.d(TAG, "saveOpenedFiles: Project dir not set; skipping cache write.")
      isOpenedFilesSaved.set(true)
      return
    }
    writeOpenedFilesCache(getOpenedFiles(), getCurrentEditor()?.editor?.file)
  }

  private fun writeOpenedFilesCache(openedFiles: List<OpenedFile>, selectedFile: File?) {
    if (!allowOpenedFilesCacheWrites.get()) {
      isOpenedFilesSaved.set(true)
      return
    }
    val projectIsOpen = try {
      ProjectManagerImpl.getInstance().projectDir
      true
    } catch (_: IllegalStateException) {
      false
    }
    if (!projectIsOpen) {
      Log.d(TAG, "writeOpenedFilesCache: Project dir not set; skipping cache write.")
      isOpenedFilesSaved.set(true)
      return
    }

    if (selectedFile == null || openedFiles.isEmpty()) {
      // Avoid enqueuing a background write with null; just clear local cache reference.
      editorViewModel.writeOpenedFiles(null) // project is open; safe to clear persisted cache
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
        val countToUpdate = min(content.tabs.tabCount, files.size)
        for (i in 0 until countToUpdate) {
          val tab = content.tabs.getTabAt(i) ?: continue
          val nameAndIcon = names.get(i)
          if (nameAndIcon != null) {
            tab.icon = ResourcesCompat.getDrawable(resources, nameAndIcon.second, theme)
            tab.text = nameAndIcon.first
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