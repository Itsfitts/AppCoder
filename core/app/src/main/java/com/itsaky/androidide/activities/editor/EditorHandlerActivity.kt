package com.itsaky.androidide.activities.editor

import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.Toast
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
import com.itsaky.androidide.dialogs.FileEditorActivity // Your AI input Activity
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

/**
 * Base class for EditorActivity. Handles logic for working with file editors.
 *
 * @author Akash Yadav
 */
open class EditorHandlerActivity : ProjectHandlerActivity(), IEditorHandler {

  protected val isOpenedFilesSaved = AtomicBoolean(false)
  private val buildLogBuilder = StringBuilder()

  companion object {
    private const val REQUEST_CODE_RETRY_WITH_AI = 77
    private const val AI_FIX_RUN_DELAY_MS = 1200L
    private const val MAX_AUTO_FIX_ATTEMPTS = 2 // User gets initial attempt + 2 retries
  }

  private var syncAndRunAfterAiFix: Boolean = false

  // Variables for Automatic Error Fixing
  private var isAutoFixModeActive: Boolean = false
  private var initialAppDescriptionForAutoFix: String? = null
  private var autoFixAttemptsRemaining: Int = 0

  // Public accessor for EditorBuildEventListener to check the mode
  val isAutoFixModeActivePublic: Boolean
    get() = isAutoFixModeActive


  override fun doOpenFile(file: File, selection: Range?) {
    openFileAndSelect(file, selection)
  }

  override fun doCloseAll(runAfter: () -> Unit) {
    closeAll(runAfter)
  }

  override fun provideCurrentEditor(): CodeEditorView? {
    return getCurrentEditor()
  }

  override fun provideEditorAt(index: Int): CodeEditorView? {
    return getEditorAtIndex(index)
  }

  override fun preDestroy() {
    super.preDestroy()
    TSLanguageRegistry.instance.destroy()
    editorViewModel.removeAllFiles()
    resetAutoFixState() // Ensure state is reset if activity is destroyed
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    mBuildEventListener.setActivity(this) // Ensure mBuildEventListener is initialized before calling this
    super.onCreate(savedInstanceState)

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
  }

  override fun onPause() {
    super.onPause()
    if (!isOpenedFilesSaved.get()) {
      saveOpenedFiles()
    }
  }

  override fun onResume() {
    super.onResume()
    isOpenedFilesSaved.set(false)
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

  override fun onStart() {
    super.onStart()
    try {
      editorViewModel.getOrReadOpenedFilesCache(this::onReadOpenedFilesCache)
    } catch (err: Throwable) {
      log.error("Failed to reopen recently opened files", err)
    }
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
        item.icon = action.icon?.mutate()?.apply { // Use mutate() to avoid sharing drawable state
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
      withContext(Dispatchers.IO) { // Ensure I/O is off main
        ProjectManagerImpl.getInstance().generateSources()
      }
    }
    return result.gradleSaved
  }

  override suspend fun saveAllResult(progressConsumer: ((Int, Int) -> Unit)?): SaveResult {
    return performFileSave {
      val result = SaveResult()
      for (i in 0 until editorViewModel.getOpenedFileCount()) {
        saveResultInternal(i, result) // Now calling a suspend function
        withContext(Dispatchers.Main.immediate) {
          progressConsumer?.invoke(i + 1, editorViewModel.getOpenedFileCount())
        }
      }
      result
    }
  }

  override suspend fun saveResult(index: Int, result: SaveResult) {
    performFileSave {
      saveResultInternal(index, result) // Now calling a suspend function
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
    if (!editorView.save()) { // Assuming editorView.save() is a suspend fun that returns Boolean
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
      if (unsavedEditors.any { it.isModified }) { // Check if any abstract editor is modified
        log.warn("notifyFilesUnsaved: Some modified editors have no file path but are marked modified.")
        unsavedEditors.forEach { it.markAsSaved() } // Assume discard
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
    buildLogBuilder.clear()
  }

  fun captureBuildLogLine(line: String) {
    buildLogBuilder.append(line).append("\n")
  }

  private fun getCompleteCapturedBuildOutput(): String {
    return buildLogBuilder.toString().trim()
  }

  private fun resetAutoFixState() {
    isAutoFixModeActive = false
    initialAppDescriptionForAutoFix = null
    autoFixAttemptsRemaining = 0
    log.info("--- Automatic Error Fixing state has been RESET. ---")
  }

  private fun handleSyncFailureAfterAi(errorMessage: String, throwable: Throwable? = null) {
    log.warn("Sync failure after AI: $errorMessage", throwable)
    Toast.makeText(this, "$errorMessage Cannot sync and run.", Toast.LENGTH_LONG).show()
    if (isAutoFixModeActive) {
      log.warn("Auto-fix mode was active, but sync failed. Disabling auto-fix.")
      Toast.makeText(this, "Sync failed. Automatic fixing stopped.", Toast.LENGTH_LONG).show()
    }
    resetAutoFixState()
    syncAndRunAfterAiFix = false // Prevent run attempt
  }


  fun handleBuildFailedAndShowDialog() {
    val capturedLog = getCompleteCapturedBuildOutput()
    val projectManager = ProjectManagerImpl.getInstance()
    val currentProjectFile: File? = try { projectManager.projectDir } catch (e: IllegalStateException) { null }
    val currentProjectName: String? = currentProjectFile?.name
    val projectsBaseDirPath: String? = currentProjectFile?.parentFile?.absolutePath

    log.info("handleBuildFailedAndShowDialog: CHECKING CONDITIONS: isAutoFixModeActive=$isAutoFixModeActive, autoFixAttemptsRemaining=$autoFixAttemptsRemaining, initialAppDescriptionForAutoFix IS ${if(initialAppDescriptionForAutoFix.isNullOrBlank()) "BLANK" else "PRESENT"}")

    if (isAutoFixModeActive && autoFixAttemptsRemaining > 0 && !initialAppDescriptionForAutoFix.isNullOrBlank()) {
      val attemptNumberToLog = MAX_AUTO_FIX_ATTEMPTS - autoFixAttemptsRemaining + 1
      log.info("AUTOMATED FIX (Attempt $attemptNumberToLog/$MAX_AUTO_FIX_ATTEMPTS): Conditions MET. Initiating AI fix.")
      Toast.makeText(this, "Auto-fixing build error: Attempt $attemptNumberToLog of $MAX_AUTO_FIX_ATTEMPTS", Toast.LENGTH_SHORT).show()

      autoFixAttemptsRemaining-- // Decrement for the attempt we are about to make

      val combinedDescriptionForAi = """
                Original App Description:
                $initialAppDescriptionForAutoFix

                ---
                Please analyze the original app description and the following build failure. Modify the code to fix the build error while adhering to the original app goals:
                ${getString(R.string.ai_fix_failure_prefix)}${capturedLog}
            """.trimIndent()

      if (currentProjectName != null && projectsBaseDirPath != null) {
        val intent = FileEditorActivity.newIntent(this, projectsBaseDirPath)
        intent.putExtra(FileEditorActivity.EXTRA_PREFILL_APP_NAME, currentProjectName)
        intent.putExtra(FileEditorActivity.EXTRA_PREFILL_APP_DESCRIPTION, combinedDescriptionForAi)
        intent.putExtra(FileEditorActivity.EXTRA_IS_AUTO_RETRY_ATTEMPT, true)
        intent.putExtra(FileEditorActivity.EXTRA_ENABLE_AUTO_FIX, true)
        intent.putExtra(FileEditorActivity.EXTRA_INITIAL_APP_DESCRIPTION_FOR_AUTO_FIX, initialAppDescriptionForAutoFix)

        log.info("Launching FileEditorActivity for AUTOMATED AI fix. Attempts now remaining: $autoFixAttemptsRemaining")
        startActivityForResult(intent, REQUEST_CODE_RETRY_WITH_AI)
      } else {
        log.error("AUTOMATED FIX: Cannot automatically retry with AI: Project details missing. Name: $currentProjectName, BaseDir: $projectsBaseDirPath. Switching to manual dialog.")
        Toast.makeText(this, "Auto-fix: Project details missing. Stopping automated attempts.", Toast.LENGTH_LONG).show()
        resetAutoFixState()
        showManualBuildFailedDialog(capturedLog, currentProjectName, projectsBaseDirPath)
      }
      return // Automated attempt launched, IMPORTANT to skip manual dialog.
    }

    // Fall through to manual dialog if conditions for auto-retry are not met
    if (isAutoFixModeActive && autoFixAttemptsRemaining <= 0) {
      log.info("AUTOMATED FIX: Max attempts reached ($autoFixAttemptsRemaining). Switching to manual build failed dialog.")
      Toast.makeText(this, "Auto-fix attempts finished. Please fix manually or retry.", Toast.LENGTH_LONG).show()
      resetAutoFixState() // Reset as the auto-fix cycle for this initial error is over.
    } else if (isAutoFixModeActive && initialAppDescriptionForAutoFix.isNullOrBlank()){
      log.warn("AUTOMATED FIX: Mode is active but initial description is missing. Cannot proceed with auto-fix. Switching to manual dialog.")
      Toast.makeText(this, "Auto-fix context missing. Please retry manually.", Toast.LENGTH_LONG).show()
      resetAutoFixState()
    } else {
      log.info("Conditions for automated fix not met, or auto-fix not active. Showing manual dialog. isAutoFixModeActive=$isAutoFixModeActive, attemptsRemaining=$autoFixAttemptsRemaining")
    }
    showManualBuildFailedDialog(capturedLog, currentProjectName, projectsBaseDirPath)
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
      DialogUtils.newCustomMessageDialog(
        context = this,
        title = getString(R.string.title_build_failed),
        message = fullDialogMessage,
        positiveButtonText = getString(R.string.action_retry_build_with_ai),
        positiveClickListener = { dialog, _ ->
          dialog.dismiss()
          resetAutoFixState() // Manual retry from dialog always resets any prior auto-fix state.

          if (currentProjectName != null && projectsBaseDirPath != null) {
            val intent = FileEditorActivity.newIntent(this, projectsBaseDirPath)
            intent.putExtra(FileEditorActivity.EXTRA_PREFILL_APP_NAME, currentProjectName)
            intent.putExtra(FileEditorActivity.EXTRA_PREFILL_APP_DESCRIPTION, fullDialogMessage)
            startActivityForResult(intent, REQUEST_CODE_RETRY_WITH_AI)
          } else {
            log.error("Cannot open AI editor (manual): Project details missing. Name: $currentProjectName, BaseDir: $projectsBaseDirPath")
            Toast.makeText(this, getString(R.string.msg_cannot_open_ai_editor_project_details_missing), Toast.LENGTH_LONG).show()
          }
        },
        negativeButtonText = getString(android.R.string.cancel),
        negativeClickListener = { dialog, _ -> dialog.dismiss() },
        cancelable = true
      ).show()
    }
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQUEST_CODE_RETRY_WITH_AI) {
      if (resultCode == Activity.RESULT_OK && data != null) {
        log.info("Returned from AI Editor with RESULT_OK.")

        val appNameFromResult = data.getStringExtra(FileEditorActivity.EXTRA_APP_NAME)
        val projectPathFromResult = data.getStringExtra(FileEditorActivity.RESULT_EXTRA_PROJECT_PATH)

        if (projectPathFromResult != null) {
          val newProjectDir = File(projectPathFromResult)
          val projectManager = ProjectManagerImpl.getInstance()
          val currentManagerDir = try { projectManager.projectDir } catch (e: IllegalStateException) { null }

          if (newProjectDir.exists() && newProjectDir.isDirectory) {
            if (currentManagerDir == null || currentManagerDir.absolutePath != newProjectDir.absolutePath) {
              log.info("Project context appears to have changed or was not set. Old: '${currentManagerDir?.absolutePath ?: "None"}', New: '${newProjectDir.absolutePath}'. Attempting to open the new project context.")
              saveOpenedFiles()
              doCloseAll {
                log.info("Files from old project (if any) closed. Opening new project: ${newProjectDir.absolutePath}")
                projectManager.openProject(newProjectDir.absolutePath)
                lifecycleScope.launch(Dispatchers.Main) {
                  handlePostProjectSwitchContinuation(data)
                }
              }
              return // Async operation, continuation happens in doCloseAll callback
            } else {
              log.info("AI operation was on the currently open project: '${newProjectDir.absolutePath}'. No project switch needed.")
              handlePostProjectSwitchContinuation(data) // Call directly
            }
          } else {
            log.error("Project path from AI Editor is invalid or non-existent: '$projectPathFromResult'. Cannot proceed with build.")
            flashError("Invalid project path from AI for '${appNameFromResult ?: "Unknown"}'. Build aborted.")
            resetAutoFixState()
            syncAndRunAfterAiFix = false
          }
        } else { // projectPathFromResult is null
          log.warn("No project path returned from AI Editor. Assuming current project context for app: '$appNameFromResult'.")
          val currentProjectIsOpen = try { ProjectManagerImpl.getInstance().projectDir != null } catch (e: IllegalStateException) { false }
          if (!currentProjectIsOpen) {
            log.error("No project path returned and no current project open. Cannot proceed with build.")
            flashError("No project context. Build aborted.")
            resetAutoFixState()
            syncAndRunAfterAiFix = false
            return
          }
          handlePostProjectSwitchContinuation(data) // Call directly, assume current project
        }
      } else { // RESULT_CANCELLED or other, or data is null
        log.info("Returned from AI Editor with result: $resultCode (Not RESULT_OK) or data was null.")
        if (isAutoFixModeActive) {
          log.warn("AI Edit was cancelled or failed during an auto-fix attempt. Stopping auto-fix mode.")
          Toast.makeText(this, "AI edit cancelled/failed. Automatic fixing stopped.", Toast.LENGTH_SHORT).show()
        }
        resetAutoFixState()
        syncAndRunAfterAiFix = false
      }
    }
  }

  private fun handlePostProjectSwitchContinuation(data: Intent) {
    val autoFixEnabledFromReturningEditor = data.getBooleanExtra(FileEditorActivity.EXTRA_ENABLE_AUTO_FIX, false)
    val initialDescriptionFromReturningEditor = data.getStringExtra(FileEditorActivity.EXTRA_INITIAL_APP_DESCRIPTION_FOR_AUTO_FIX)
    val isThisAnAutoRetryResponse = data.getBooleanExtra(FileEditorActivity.EXTRA_IS_AUTO_RETRY_ATTEMPT, false)

    log.info("handlePostProjectSwitchContinuation: isThisAnAutoRetryResponse=$isThisAnAutoRetryResponse, autoFixEnabledFromReturningEditor=$autoFixEnabledFromReturningEditor, initialDescFromEditorIsBlank=${initialDescriptionFromReturningEditor.isNullOrBlank()}")
    log.info("Current state BEFORE update: isAutoFixModeActive=$isAutoFixModeActive, autoFixAttemptsRemaining=$autoFixAttemptsRemaining, initialAppDescForAutoFixIsBlank=${initialAppDescriptionForAutoFix.isNullOrBlank()}")

    if (isThisAnAutoRetryResponse) {
      // This means FileEditorActivity was launched by handleBuildFailedAndShowDialog for an automated fix.
      // isAutoFixModeActive should already be true.
      // autoFixAttemptsRemaining was already decremented before launching FileEditorActivity.
      // We primarily ensure that if FileEditorActivity had a catastrophic internal failure and turned off its own auto-mode, we respect that.
      if (autoFixEnabledFromReturningEditor) {
        isAutoFixModeActive = true // Confirm it's still active
        // initialAppDescriptionForAutoFix should already be set from the very first user-initiated session.
        // If it's somehow blank here, it's a problem, but we try to recover if FileEditorActivity sent it back.
        if (initialAppDescriptionForAutoFix.isNullOrBlank() && !initialDescriptionFromReturningEditor.isNullOrBlank()) {
          initialAppDescriptionForAutoFix = initialDescriptionFromReturningEditor
          log.warn("Recovered missing initialAppDescriptionForAutoFix during auto-retry response.")
        }
      } else {
        log.warn("AUTO-RETRY RESPONSE received, but FileEditorActivity returned with auto-fix disabled. This is unexpected. Resetting auto-fix mode.")
        resetAutoFixState()
      }
      log.info("Continued AUTOMATED FIX flow. State after processing response: isAutoFixModeActive=$isAutoFixModeActive, attemptsRemaining=$autoFixAttemptsRemaining (reflects count before this just-completed AI fix).")
    } else {
      // This is a response from an initial user-triggered AI session in FileEditorActivity,
      // or a manual "Retry with AI" from the build failed dialog.
      if (autoFixEnabledFromReturningEditor && !initialDescriptionFromReturningEditor.isNullOrBlank()) {
        // User explicitly checked "Fully Automated..." in FileEditorActivity UI.
        isAutoFixModeActive = true
        this.initialAppDescriptionForAutoFix = initialDescriptionFromReturningEditor
        this.autoFixAttemptsRemaining = MAX_AUTO_FIX_ATTEMPTS // Start fresh attempts for this new sequence
        log.info("AUTOMATION INITIATED by user via FileEditorActivity. isAutoFixModeActive=true, autoFixAttemptsRemaining=$autoFixAttemptsRemaining, initialAppDescription captured.")
      } else {
        // User did NOT enable "Fully Automated..." in FileEditorActivity UI.
        resetAutoFixState()
        log.info("Auto-fix NOT enabled by user in FileEditorActivity, or manual retry from dialog without enabling. Resetting auto-fix state.")
      }
    }

    log.info("Final state for this cycle in handlePostProjectSwitchContinuation: isAutoFixModeActive=$isAutoFixModeActive, autoFixAttemptsRemaining=$autoFixAttemptsRemaining, initialDescIsBlank=${initialAppDescriptionForAutoFix.isNullOrBlank()}")

    if (isAutoFixModeActive || !isThisAnAutoRetryResponse) { // Proceed to build if auto-fix is on, OR if it was a successful manual first run
      syncAndRunAfterAiFix = true
    } else {
      syncAndRunAfterAiFix = false
      log.warn("Not setting syncAndRunAfterAiFix. isAutoFixModeActive=$isAutoFixModeActive, isThisAnAutoRetryResponse=$isThisAnAutoRetryResponse")
    }

    saveAllAsync(notify = false, requestSync = false, processResources = false) {
      val projectManager = ProjectManagerImpl.getInstance()
      try {
        if (projectManager.projectDir != null && (projectManager.getWorkspace() != null || projectManager.projectDir!!.exists())) {
          log.info("Local save complete. Performing project sync (initializeProject) for: ${projectManager.projectDir!!.absolutePath}")
          super.initializeProject()
        } else {
          val projName = projectManager.projectDir?.name ?: "Unknown"
          handleSyncFailureAfterAi("Project '$projName' not accessible or workspace unavailable after AI op.")
        }
      } catch (e: IllegalStateException) {
        handleSyncFailureAfterAi("Error accessing project for sync after AI op: ${e.message}", e)
      }
    }
  }


  override fun onProjectInitialized(result: InitializeResult) {
    super.onProjectInitialized(result)
    log.debug("EditorHandlerActivity: onProjectInitialized invoked. Result successful: ${result.isSuccessful}, syncAndRunAfterAiFix = $syncAndRunAfterAiFix, isAutoFixModeActive = $isAutoFixModeActive")

    if (syncAndRunAfterAiFix) {
      if (!result.isSuccessful) {
        log.error("Project Initialization failed after AI edit. Cannot run. Auto-fix active: $isAutoFixModeActive")
        syncAndRunAfterAiFix = false
        // postProjectInit will handle resetting auto-fix if it was active and sync failed.
        return
      }

      log.info("Project sync successful after AI edit. Now attempting to auto-run the project.")
      lifecycleScope.launch(Dispatchers.Main) {
        delay(AI_FIX_RUN_DELAY_MS)
        if (isDestroyed || isFinishing) {
          log.warn("Activity destroyed/finishing before auto-run after AI sync.")
          syncAndRunAfterAiFix = false
          return@launch
        }
        try {
          val toolbar = content.editorToolbar
          var runActionFound = false
          toolbar.menu.forEach { item ->
            val title = item.title?.toString() ?: ""
            val contentDesc = item.contentDescription?.toString() ?: ""
            if (title.contains("run", ignoreCase = true) || contentDesc.contains("run", ignoreCase = true)) {
              if (item.isEnabled && item.isVisible) {
                log.info("Found 'Run' action: '${item.title}'. Attempting to perform action.")
                if (toolbar.menu.performIdentifierAction(item.itemId, 0)) {
                  log.info("'Run' action performed successfully via menu item.")
                  runActionFound = true
                } else {
                  log.warn("'Run' action performIdentifierAction returned false for item: ${item.title}")
                }
                if (runActionFound) return@forEach
              } else {
                log.info("Found 'Run' action: '${item.title}', but it's not enabled or visible.")
              }
            }
          }
          if (!runActionFound) {
            log.error("Could not find or trigger an enabled 'Run' menu item to auto-run project after AI sync.")
            Toast.makeText(this@EditorHandlerActivity, "Auto-run: Could not find 'Run' action.", Toast.LENGTH_LONG).show()
          }
        } catch (e: Exception) {
          log.error("Failed to trigger runProject via menu after successful sync (AI flow)", e)
          Toast.makeText(this@EditorHandlerActivity, "Auto-run failed: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
          syncAndRunAfterAiFix = false
        }
      }
    } else {
      log.debug("onProjectInitialized: syncAndRunAfterAiFix is false, no auto-run attempt scheduled from AI flow.")
    }
  }

  override fun postProjectInit(isSuccessful: Boolean, failure: TaskExecutionResult.Failure?) {
    super.postProjectInit(isSuccessful, failure)
    log.debug("EditorHandlerActivity: postProjectInit. Project sync successful: $isSuccessful, isAutoFixModeActive: $isAutoFixModeActive")

    if (!isSuccessful) {
      if (isAutoFixModeActive) {
        log.warn("Project sync failed during an auto-fix attempt. Stopping auto-fix for this failure. Failure: ${failure?.name}")
        Toast.makeText(this, "Auto-fix: Sync failed. Automatic fixing stopped for this attempt.", Toast.LENGTH_LONG).show()
        resetAutoFixState()
      }
    }
  }

  fun handleAutoFixBuildSuccess() {
    if (isAutoFixModeActive) {
      log.info("AUTOMATED FIX: Build successful during auto-fix mode. Resetting auto-fix state.")
      Toast.makeText(this, "Auto-fix: Build successful!", Toast.LENGTH_SHORT).show()
      resetAutoFixState()
    }
  }
}