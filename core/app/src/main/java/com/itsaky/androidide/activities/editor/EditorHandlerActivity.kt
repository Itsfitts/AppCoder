/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.activities.editor

import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.LayoutParams
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.view.menu.MenuBuilder
import androidx.collection.MutableIntObjectMap
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.GravityCompat
import androidx.core.view.forEach /* Added for iterating menu items */
import androidx.lifecycle.lifecycleScope
import com.blankj.utilcode.util.ImageUtils
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem.Location.EDITOR_TOOLBAR
import com.itsaky.androidide.actions.ActionsRegistry.Companion.getInstance
import com.itsaky.androidide.actions.FillMenuParams
import com.itsaky.androidide.dialogs.FileEditorActivity // Assuming this is your AI input Activity
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
import com.itsaky.androidide.tasks.executeAsync
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
import kotlin.collections.set

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
    private const val AI_FIX_RUN_DELAY_MS = 1000L // Increased delay slightly, make it a const
  }

  private var syncAndRunAfterAiFix: Boolean = false

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
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    mBuildEventListener.setActivity(this) // Make sure mBuildEventListener is initialized before super.onCreate if it's used by it
    super.onCreate(savedInstanceState)

    editorViewModel._displayedFile.observe(
      this) { this.content.editorContainer.displayedChild = it }
    editorViewModel._startDrawerOpened.observe(this) { opened ->
      this.binding.editorDrawerLayout.apply { // Assuming 'binding' is the main activity binding from BaseEditorActivity
        if (opened) openDrawer(GravityCompat.START) else closeDrawer(GravityCompat.START)
      }
    }

    editorViewModel._filesModified.observe(this) { invalidateOptionsMenu() }
    editorViewModel._filesSaving.observe(this) { invalidateOptionsMenu() }

    editorViewModel.observeFiles(this) {
      val currentFile =
        getCurrentEditor()?.editor?.file?.absolutePath
          ?: run {
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

    // Use lifecycleScope for coroutines tied to the Activity's lifecycle
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
      // editorViewModel.openedFilesCache = null // This line seems to be immediately nullifying what might have been read.
      // If getOrReadOpenedFilesCache works asynchronously or sets it, this might be problematic.
      // Let's assume the ViewModel handles its cache correctly.
    } catch (err: Throwable) {
      log.error("Failed to reopen recently opened files", err)
    }
  }

  private fun onReadOpenedFilesCache(cache: OpenedFilesCache?) {
    cache ?: return
    // Ensure files are opened on the main thread if they involve UI updates
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
    getInstance().fillMenu(FillMenuParams(data, EDITOR_TOOLBAR, menu))
    return true
  }

  open fun prepareOptionsMenu(menu: Menu) {
    val data = createToolbarActionData()
    val actions = getInstance().getActions(EDITOR_TOOLBAR)
    actions.forEach { (_, action) ->
      menu.findItem(action.itemId)?.let { item ->
        action.prepare(data)
        item.isVisible = action.visible
        item.isEnabled = action.enabled
        item.title = action.label
        item.icon = action.icon?.apply {
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
    // Assuming 'content' is the direct binding object for the layout containing 'editorContainer'
    // and it's correctly initialized and non-null by this point.
    // Using as? for safe casting.
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
    if (index < 0) { // Check if file opening failed (e.g. does not exist)
      log.warn("Failed to open file or get index for: {}", file.absolutePath)
      return null
    }
    val tab = content.tabs.getTabAt(index)
    if (tab != null && !tab.isSelected) { // index >=0 is implicitly true if tab is not null
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
      editorViewModel.displayedFileIndex = openedFileIndex // Ensure displayed index is updated
      return openedFileIndex
    }
    if (!file.exists()) {
      log.error("File does not exist, cannot open: {}", file.absolutePath)
      flashError("Error: File not found ${file.name}") // User feedback
      return -1
    }
    if (!file.canRead()) {
      log.error("File cannot be read, cannot open: {}", file.absolutePath)
      flashError("Error: Cannot read file ${file.name}") // User feedback
      return -1
    }

    val position = editorViewModel.getOpenedFileCount()
    log.info("Opening file at index {} file:{}", position, file)
    // Pass the selection range to the CodeEditorView constructor
    val editor = CodeEditorView(this, file, selection ?: Range.NONE)
    editor.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    content.editorContainer.addView(editor)
    content.tabs.addTab(content.tabs.newTab())
    editorViewModel.addFile(file)
    // editorViewModel.setCurrentFile(position, file) // This is handled by addFile and displayedFileIndex
    updateTabs() // Call updateTabs after adding, so the new tab gets its name/icon
    return position
  }

  override fun getEditorForFile(file: File): CodeEditorView? {
    for (i in 0 until content.editorContainer.childCount) { // Iterate through actual children
      val editor = content.editorContainer.getChildAt(i) as? CodeEditorView
      if (file == editor?.file) return editor
    }
    return null
  }

  override fun findIndexOfEditorByFile(file: File?): Int {
    if (file == null) {
      log.warn("Cannot find index of a null file.") // Changed to warn from error
      return -1
    }
    for (i in 0 until editorViewModel.getOpenedFileCount()) { // Use ViewModel's count
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
    // Use lifecycleScope for coroutines tied to the Activity's lifecycle
    lifecycleScope.launch {
      saveAll(notify, requestSync, processResources, progressConsumer)
      // Ensure runAfter is also on the main thread if it interacts with UI
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
    // UI updates must be on the Main thread
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
      ProjectManagerImpl.getInstance().generateSources() // This might be I/O, ensure it's handled off main
    }
    return result.gradleSaved
  }

  override suspend fun saveAllResult(progressConsumer: ((Int, Int) -> Unit)?): SaveResult {
    return performFileSave {
      val result = SaveResult()
      for (i in 0 until editorViewModel.getOpenedFileCount()) {
        saveResultInternal(i, result) // Pass result to be modified
        withContext(Dispatchers.Main.immediate) { // Progress consumer might update UI
          progressConsumer?.invoke(i + 1, editorViewModel.getOpenedFileCount())
        }
      }
      result // Return the populated result
    }
  }

  override suspend fun saveResult(index: Int, result: SaveResult) {
    performFileSave {
      saveResultInternal(index, result)
    }
  }

  private suspend fun saveResultInternal(index: Int, result: SaveResult) : Boolean { // Make it return Boolean for success
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
    if (!editorView.save()) { // save() itself should handle I/O and return success
      log.warn("saveResultInternal: Failed to save file {}", fileName)
      return false // Indicate save failure
    }

    // File is saved, now update flags
    val isGradle = fileName.endsWith(".gradle") || fileName.endsWith(".gradle.kts")
    val isXml: Boolean = fileName.endsWith(".xml")
    if (modified && isGradle) { // Only set if it was modified and is a gradle file
      result.gradleSaved = true
    }
    if (modified && isXml) { // Only set if it was modified and is an xml file
      result.xmlSaved = true
    }

    // Update UI on the main thread
    withContext(Dispatchers.Main.immediate) {
      editorViewModel.areFilesModified = hasUnsavedFiles() // Re-check after save
      val tab = content.tabs.getTabAt(index)
      if (tab?.text?.startsWith('*') == true) {
        tab.text = tab.text!!.substring(startIndex = 1)
      }
    }
    return true // Indicate save success
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
      log.warn("Invalid file index {}. Cannot close.", index) // Changed to warn
      runOnUiThread(runAfter) // Still call runAfter, perhaps it's for UI cleanup
      return
    }
    val openedFile = editorViewModel.getOpenedFile(index)
    log.info("Closing file: {}", openedFile)
    val editor = getEditorAtIndex(index)

    if (editor?.isModified == true) {
      log.info("File has been modified: {}", openedFile)
      // Ensure unsavedEditors list is correctly populated
      notifyFilesUnsaved(listOfNotNull(editor)) { // Use listOfNotNull
        // This callback will be on the main thread from dialog
        closeFile(index, runAfter) // Retry closing
      }
      return
    }

    editor?.close() // Perform editor-specific cleanup if any (e.g., releasing resources)
    // If editor is null, we still proceed to remove from ViewModel and UI as it might be an inconsistent state

    editorViewModel.removeFile(index)
    content.tabs.removeTabAt(index)
    // Ensure view removal happens only if index is valid for the container
    if (index < content.editorContainer.childCount) {
      content.editorContainer.removeViewAt(index)
    } else {
      log.warn("Attempted to remove view at index {} but container child count is {}", index, content.editorContainer.childCount)
    }

    editorViewModel.areFilesModified = hasUnsavedFiles()
    updateTabs()
    runOnUiThread(runAfter) // Ensure UI-related cleanup in runAfter is on main thread
  }

  override fun closeOthers() {
    if (editorViewModel.getOpenedFileCount() <= 1) { // If 0 or 1 file, nothing to close
      return
    }
    val currentEditorIndex = editorViewModel.getCurrentFileIndex()
    if (currentEditorIndex == -1 && editorViewModel.getOpenedFileCount() > 0) {
      // If no current file selected but files are open, this logic might be tricky.
      // For now, assume if currentEditorIndex is -1, we might just close all but the first or last.
      // This case needs clearer definition if it occurs.
      log.warn("closeOthers called with no current file selected, but multiple files open.")
      // Fallback: close all if this state is problematic. Or pick one to keep.
      // For simplicity, let's proceed, but be mindful.
    }


    val unsavedEditors = mutableListOf<CodeEditorView>()
    for (i in 0 until editorViewModel.getOpenedFileCount()) {
      if (i == currentEditorIndex) continue // Don't check the current one for saving if we intend to keep it.
      getEditorAtIndex(i)?.let { editor ->
        if (editor.isModified) {
          unsavedEditors.add(editor)
        }
      }
    }

    if (unsavedEditors.isNotEmpty()) {
      notifyFilesUnsaved(unsavedEditors) { closeOthers() } // Retry after save/discard
      return
    }

    // Close files from highest index to lowest to avoid shifting indices issues
    // while keeping the current one.
    for (i in editorViewModel.getOpenedFileCount() - 1 downTo 0) {
      if (i == currentEditorIndex) continue

      val editorToClose = getEditorAtIndex(i)
      editorToClose?.close() // Editor-specific cleanup

      editorViewModel.removeFile(i) // This will shift indices in the ViewModel's list
      content.tabs.removeTabAt(i)
      if (i < content.editorContainer.childCount) { // Check bounds before removing view
        content.editorContainer.removeViewAt(i)
      }
    }
    // After removals, the current file might be at index 0 if it wasn't already.
    // The ViewModel should internally adjust currentFileIndex if removeFile handles it.
    // If not, we might need to explicitly find the current file again and set its index.
    // For now, assume editorViewModel.removeFile correctly updates its internal state.
    // And that getCurrentFileIndex() will still point to the correct editor.

    editorViewModel.areFilesModified = hasUnsavedFiles() // Should be false now
    updateTabs() // Refresh tab display
  }


  override fun closeAll(runAfter: () -> Unit) {
    val unsavedEditors = (0 until editorViewModel.getOpenedFileCount())
      .mapNotNull { getEditorAtIndex(it) }
      .filter { it.isModified }

    if (unsavedEditors.isNotEmpty()) {
      notifyFilesUnsaved(unsavedEditors) { closeAll(runAfter) } // Retry after save/discard
      return
    }

    for (i in 0 until editorViewModel.getOpenedFileCount()) {
      getEditorAtIndex(i)?.close() // Editor-specific cleanup
    }

    editorViewModel.removeAllFiles() // Clears ViewModel's list
    content.tabs.removeAllTabs()
    content.editorContainer.removeAllViews() // Remove all editor views

    // No need for content.tabs.requestLayout() usually, removeAllTabs should trigger it.
    editorViewModel.areFilesModified = false // All closed, so no modified files
    // updateTabs() // Not strictly necessary as all tabs are gone, but harmless
    runOnUiThread(runAfter)
  }

  override fun getOpenedFiles() =
    editorViewModel.getOpenedFiles().mapNotNull { file -> // Use the ViewModel's list of files
      val editor = getEditorForFile(file)?.editor ?: return@mapNotNull null
      OpenedFile(file.absolutePath, editor.cursorLSPRange)
    }

  private fun notifyFilesUnsaved(unsavedEditors: List<CodeEditorView>, invokeAfter: Runnable) {
    if (isDestroying) {
      // If destroying, don't bother saving, just mark as unmodified and proceed
      unsavedEditors.forEach { editor ->
        editor.markAsSaved() // or markUnmodified(), depending on desired behavior on destroy
      }
      runOnUiThread(invokeAfter) // Ensure invokeAfter is on main thread
      return
    }

    val mappedFilePaths = unsavedEditors.mapNotNull { it.file?.absolutePath }
    if (mappedFilePaths.isEmpty() && unsavedEditors.isNotEmpty()) {
      // This case means we have editors that are modified but don't have a file path.
      // This shouldn't happen for persisted files. Log and proceed.
      log.warn("notifyFilesUnsaved: Some modified editors have no file path.")
      // If no actual files to list, but we know some abstract editor is modified.
      // Fallback to a generic message or just run invokeAfter assuming user wants to discard.
      // For now, if no paths, just run invokeAfter as if "No" was pressed.
      unsavedEditors.forEach { it.markAsSaved() }
      runOnUiThread(invokeAfter)
      return
    }
    if (mappedFilePaths.isEmpty()) { // If truly no files to save
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
          // Save all and then run the invokeAfter
          saveAllAsync(notify = true, requestSync = false, processResources = false, runAfter = {
            runOnUiThread(invokeAfter)
          })
        },
        negativeClickListener = { dialog, _ -> // Changed to negativeClickListener
          dialog.dismiss()
          unsavedEditors.forEach { editor ->
            editor.markAsSaved() // Mark as saved (changes discarded)
          }
          runOnUiThread(invokeAfter) // Run after marking
        }
      )
    builder.setCancelable(false) // Prevent dismissing without choice
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
    editorViewModel.areFilesModified = true // This is a global flag, correct
    val changedFile = event.file.toFile()
    val index = findIndexOfEditorByFile(changedFile)
    if (index == -1) {
      return
    }
    val tab = content.tabs.getTabAt(index) ?: return // Tab might be null if index is out of bounds
    if (tab.text?.startsWith('*') == true) {
      return
    }
    tab.text = "*${tab.text}"
  }


  private fun updateTabs() {
    // Use lifecycleScope for coroutines tied to the Activity's lifecycle
    lifecycleScope.launch(Dispatchers.Default) { // Use Default for computation
      val files = editorViewModel.getOpenedFiles() // Get files from ViewModel
      if (files.isEmpty() && content.tabs.tabCount == 0) { // Optimization: if no files and no tabs, nothing to do
        return@launch
      }

      val dupliCount = mutableMapOf<String, Int>()
      val names = MutableIntObjectMap<Pair<String, @DrawableRes Int>>()
      val nameBuilder = UniqueNameBuilder<File>("", File.separator)

      files.forEach { file -> // Iterate over ViewModel's files
        val currentCount = dupliCount.getOrPut(file.name) { 0 }
        dupliCount[file.name] = currentCount + 1
        nameBuilder.addPath(file, file.path)
      }

      for (index in 0 until files.size) { // Iterate up to the number of files in ViewModel
        val file = files.getOrNull(index) ?: continue
        // isModified check should refer to the actual editor state
        val editorView = getEditorForFile(file) // Get the editor view for this file
        val isModified = editorView?.isModified ?: false // Check modification status

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

      withContext(Dispatchers.Main) { // Switch to Main thread for UI updates
        // Ensure tab count matches file count or handle discrepancies
        if (content.tabs.tabCount != files.size) {
          log.warn("updateTabs: Mismatch between tab count (${content.tabs.tabCount}) and file count (${files.size}). Rebuilding tabs might be needed if severe.")
          // Potentially, one could try to reconcile here, but it's complex.
          // For now, we'll try to update existing tabs up to the minimum of the two counts.
        }

        val countToUpdate = minOf(content.tabs.tabCount, files.size)
        for (i in 0 until countToUpdate) {
          val tab = content.tabs.getTabAt(i) ?: continue
          val nameAndIcon = names.get(i) // Get from our prepared map
          if (nameAndIcon != null) {
            tab.icon = ResourcesCompat.getDrawable(resources, nameAndIcon.second, theme)
            tab.text = nameAndIcon.first
          } else {
            // This case (null from 'names' map) should ideally not happen if logic is correct
            log.warn("updateTabs: No name/icon info for tab at index $i")
          }
        }
        // If files.size > tabs.tabCount, new tabs might need to be added.
        // If tabs.tabCount > files.size, extra tabs might need to be removed.
        // The current openFile/closeFile logic should handle tab addition/removal.
        // This updateTabs is more for names and icons.
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
    return buildLogBuilder.toString().trim() // Trim to remove trailing newline
  }

  fun handleBuildFailedAndShowDialog() {
    val capturedLog = getCompleteCapturedBuildOutput()
    val projectManager = ProjectManagerImpl.getInstance()
    val currentProjectFile: File? = try { projectManager.projectDir } catch (e: IllegalStateException) { null }
    val currentProjectName: String? = currentProjectFile?.name
    val projectsBaseDirPath: String? = currentProjectFile?.parentFile?.absolutePath

    val errorMessagePrefix = getString(R.string.ai_fix_failure_prefix)
    val fullDialogMessage = if (capturedLog.isBlank()) {
      errorMessagePrefix + getString(R.string.build_status_failed) + "\n\n" + getString(R.string.build_log_empty)
    } else {
      errorMessagePrefix + capturedLog
    }
    // The descriptionForAiEditor should be concise yet informative.
    // The fullDialogMessage already contains the prefix "Look through the code... to fix..."
    val descriptionForAiEditor = fullDialogMessage

    runOnUiThread {
      DialogUtils.newCustomMessageDialog(
        context = this,
        title = getString(R.string.title_build_failed),
        message = fullDialogMessage, // Show the full log with prefix in the dialog
        positiveButtonText = getString(R.string.action_retry_build_with_ai),
        positiveClickListener = { dialog, _ ->
          dialog.dismiss()
          if (currentProjectName != null && projectsBaseDirPath != null) {
            log.info("Opening AI editor for project: $currentProjectName in $projectsBaseDirPath")
            log.info("AI editor description (build error): $descriptionForAiEditor")
            val intent = FileEditorActivity.newIntent(this, projectsBaseDirPath)
            intent.putExtra(FileEditorActivity.EXTRA_PREFILL_APP_NAME, currentProjectName)
            intent.putExtra(FileEditorActivity.EXTRA_PREFILL_APP_DESCRIPTION, descriptionForAiEditor) // Prefill with the error
            startActivityForResult(intent, REQUEST_CODE_RETRY_WITH_AI)
          } else {
            log.error("Cannot open AI editor: Project details missing. Name: $currentProjectName, BaseDir: $projectsBaseDirPath")
            Toast.makeText(this, getString(R.string.msg_cannot_open_ai_editor_project_details_missing), Toast.LENGTH_LONG).show()
          }
        },
        negativeButtonText = getString(android.R.string.cancel), // Standard cancel
        negativeClickListener = { dialog, _ -> dialog.dismiss() },
        cancelable = true
      ).show()
    }
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQUEST_CODE_RETRY_WITH_AI) {
      if (resultCode == Activity.RESULT_OK) {
        log.info("Returned from AI Editor (Retry/Modify Flow) with RESULT_OK. Will save, sync, and attempt to run.")
        syncAndRunAfterAiFix = true // Set the flag

        // 1. Save all files
        saveAllAsync(notify = false, requestSync = false, processResources = false, runAfter = {
          // This runAfter is on the Main thread due to changes in saveAllAsync
          log.info("Save all complete after AI edit. Now proceeding to project sync.")
          // 2. Trigger project sync (initialization)
          // Ensure projectDir is accessible before calling initializeProject
          val projectManager = ProjectManagerImpl.getInstance()
          try {
            val projectDir = projectManager.projectDir // Accessing this can throw if project not open
            if (projectManager.getWorkspace() != null || projectDir.exists()) { // Check if project seems valid to initialize
              log.info("Project dir accessible. Calling super.initializeProject() for sync.")
              super.initializeProject() // This is ProjectHandlerActivity.initializeProject()
            } else {
              syncAndRunAfterAiFix = false // Cannot proceed
              Toast.makeText(this, "Project not properly opened or workspace unavailable. Cannot sync after AI edit.", Toast.LENGTH_LONG).show()
              log.warn("Project dir not accessible or workspace null after AI edit. Aborting sync and run.")
            }
          } catch (e: IllegalStateException) {
            syncAndRunAfterAiFix = false // Cannot proceed
            Toast.makeText(this, "Error accessing project: ${e.message}. Cannot sync after AI edit.", Toast.LENGTH_LONG).show()
            log.error("Error accessing project after AI edit. Aborting sync and run.", e)
          }
        })
      } else {
        log.info("Returned from AI Editor (Retry/Modify Flow) with result: $resultCode (Not RESULT_OK). No automatic sync/rebuild.")
        syncAndRunAfterAiFix = false // Ensure flag is false
        Toast.makeText(this, "AI edit cancelled or not completed. Please sync/rebuild manually if needed.", Toast.LENGTH_SHORT).show()
      }
    }
  }

  // This is called after ProjectHandlerActivity's initializeProject successfully completes
  // (via its whenCompleteAsync -> onProjectInitialized(result) path)
  override fun onProjectInitialized(result: InitializeResult) {
    super.onProjectInitialized(result) // Call super to let ProjectHandlerActivity do its normal setup
    log.debug("EditorHandlerActivity: onProjectInitialized invoked. syncAndRunAfterAiFix = $syncAndRunAfterAiFix")

    if (syncAndRunAfterAiFix) {
      // IMPORTANT: Reset the flag immediately to prevent re-triggering on configuration changes or other events.
      syncAndRunAfterAiFix = false
      log.info("Project sync successful after AI edit. Now attempting to auto-run the project.")

      // Use lifecycleScope, run on Main thread after a delay
      lifecycleScope.launch(Dispatchers.Main) {
        // The delay is a workaround. Ideally, we'd wait for a "build system ready" signal.
        delay(AI_FIX_RUN_DELAY_MS)

        if (isDestroyed || isFinishing) {
          log.warn("Activity was destroyed or finishing before auto-run could be triggered after AI sync.")
          return@launch
        }

        log.info("Attempting to trigger a run action via toolbar menu.")
        try {
          val toolbar = content.editorToolbar
          var runActionFound = false
          // Iterate toolbar menu items to find "run"
          toolbar.menu.forEach { item -> // Simpler iteration
            val title = item.title?.toString() ?: ""
            val contentDesc = item.contentDescription?.toString() ?: ""
            if (title.contains("run", ignoreCase = true) || contentDesc.contains("run", ignoreCase = true)) {
              if (item.isEnabled && item.isVisible) {
                log.info("Found 'Run' action: '${item.title}'. Attempting to perform action.")
                // Perform the action associated with the menu item ID
                if (toolbar.menu.performIdentifierAction(item.itemId, 0)) {
                  log.info("'Run' action performed successfully via menu item.")
                  runActionFound = true
                } else {
                  log.warn("'Run' action performIdentifierAction returned false for item: ${item.title}")
                }
                // break from forEach is not direct, use return@forEach if inside a loop that supports it
                // or just let it find the first one and proceed. If multiple "run" items, this takes the first.
                if (runActionFound) return@forEach // Exit forEach if found and performed
              } else {
                log.info("Found 'Run' action: '${item.title}', but it's not enabled or visible.")
              }
            }
          }

          if (!runActionFound) {
            log.error("Could not find or trigger an enabled 'Run' menu item to auto-run project after AI sync.")
            Toast.makeText(this@EditorHandlerActivity, "Auto-run: Could not find 'Run' action. Please run manually.", Toast.LENGTH_LONG).show()
          }
        } catch (e: Exception) {
          log.error("Failed to trigger runProject via menu after successful sync (AI flow)", e)
          Toast.makeText(this@EditorHandlerActivity, "Auto-run failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  // This is called after onProjectInitialized, regardless of success/failure of the steps within onProjectInitialized
  override fun postProjectInit(isSuccessful: Boolean, failure: TaskExecutionResult.Failure?) {
    super.postProjectInit(isSuccessful, failure) // Call super for its logic
    log.debug("EditorHandlerActivity: postProjectInit. isSuccessful: $isSuccessful, syncAndRunAfterAiFix: $syncAndRunAfterAiFix")

    if (!isSuccessful) { // Project sync itself failed
      if (syncAndRunAfterAiFix) {
        // If sync failed, we definitely can't run. Reset flag and inform user.
        syncAndRunAfterAiFix = false
        log.warn("Project sync failed after AI edit (detected via postProjectInit). Cannot automatically run project. Failure: ${failure?.name}")
        val failureMessage = failure?.name ?: getString(R.string.msg_tooling_server_unavailable) // More generic fallback
        // flashError is available from ProjectHandlerActivity or BaseEditorActivity
        flashError(getString(R.string.msg_project_sync_failed_after_ai, failureMessage))
      }
    }
    // If isSuccessful, the onProjectInitialized logic (if syncAndRunAfterAiFix was true) would have handled the run attempt.
    // No need to do anything further with syncAndRunAfterAiFix here if sync was successful.
  }
}