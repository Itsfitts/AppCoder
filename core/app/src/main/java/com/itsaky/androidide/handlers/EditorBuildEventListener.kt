package com.itsaky.androidide.handlers

import android.util.Log // Import Log
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.services.builder.GradleBuildService
import com.itsaky.androidide.tooling.api.messages.result.BuildInfo
import com.itsaky.androidide.tooling.events.ProgressEvent
import com.itsaky.androidide.tooling.events.configuration.ProjectConfigurationStartEvent
import com.itsaky.androidide.tooling.events.task.TaskStartEvent
import com.itsaky.androidide.utils.flashSuccess
import org.slf4j.LoggerFactory // Keep slf4j for existing logs
import java.lang.ref.WeakReference

class EditorBuildEventListener : GradleBuildService.EventListener {

  // Keep existing slf4j logger
  private val slf4jLogger = LoggerFactory.getLogger(EditorBuildEventListener::class.java)
  // Add Android Log Tag for new debugging
  private val DEBUG_TAG = "AutoFixDebug"

  private var enabled = true
  private var activityReference: WeakReference<EditorHandlerActivity> = WeakReference(null)

  // Removed companion object log, using instance logger

  private val _activity: EditorHandlerActivity?
    get() = activityReference.get()

  fun setActivity(activity: EditorHandlerActivity) {
    Log.d(DEBUG_TAG, "EditorBuildEventListener: setActivity called")
    this.activityReference = WeakReference(activity)
    this.enabled = true
  }

  fun release() {
    Log.d(DEBUG_TAG, "EditorBuildEventListener: release called")
    activityReference.clear()
    this.enabled = false
  }

  override fun prepareBuild(buildInfo: BuildInfo) {
    Log.d(DEBUG_TAG, "EditorBuildEventListener: prepareBuild called")
    val currentActivity = checkActivity("prepareBuild") ?: return

    currentActivity.clearCapturedBuildLog()
    currentActivity.content.bottomSheet.clearBuildOutput()

    val isFirstBuild = GeneralPreferences.isFirstBuild
    currentActivity.setStatus(
      currentActivity.getString(if (isFirstBuild) string.preparing_first else string.preparing)
    )

    if (isFirstBuild) {
      currentActivity.showFirstBuildNotice()
    }

    currentActivity.editorViewModel.isBuildInProgress = true
    Log.d(DEBUG_TAG, "EditorBuildEventListener: prepareBuild - Calling updateAutoFixModeIndicator")
    currentActivity.updateAutoFixModeIndicator() // Update indicator at build start

    if (buildInfo.tasks.isNotEmpty()) {
      val tasksLine = currentActivity.getString(R.string.title_run_tasks) + " : " + buildInfo.tasks.joinToString()
      currentActivity.appendBuildOutput(tasksLine)
      currentActivity.captureBuildLogLine(tasksLine)
    }
  }

  override fun onBuildSuccessful(tasks: List<String?>) {
    Log.d(DEBUG_TAG, "EditorBuildEventListener: onBuildSuccessful called")
    val currentActivity = checkActivity("onBuildSuccessful") ?: return
    analyzeCurrentFile()
    GeneralPreferences.isFirstBuild = false
    currentActivity.editorViewModel.isBuildInProgress = false

    Log.d(DEBUG_TAG, "EditorBuildEventListener: onBuildSuccessful - isAutoFixModeActivePublic=${currentActivity.isAutoFixModeActivePublic}")
    if (currentActivity.isAutoFixModeActivePublic) {
      Log.i(DEBUG_TAG, "EditorBuildEventListener: onBuildSuccessful - Auto-fix was active. Calling handleAutoFixBuildSuccess.")
      currentActivity.handleAutoFixBuildSuccess()
    } else {
      currentActivity.flashSuccess(R.string.build_status_sucess)
    }
  }

  override fun onProgressEvent(event: ProgressEvent) {
    // This can be very noisy, only log if needed for specific progress debug
    // Log.v(DEBUG_TAG, "EditorBuildEventListener: onProgressEvent: ${event.descriptor.displayName}")
    val currentActivity = checkActivity("onProgressEvent") ?: return
    if (event is ProjectConfigurationStartEvent || event is TaskStartEvent) {
      currentActivity.setStatus(event.descriptor.displayName)
    }
  }

  override fun onBuildFailed(tasks: List<String?>) {
    Log.e(DEBUG_TAG, "EditorBuildEventListener: onBuildFailed called. Tasks: ${tasks?.joinToString()}")
    val currentActivity = checkActivity("onBuildFailed") ?: run {
      Log.e(DEBUG_TAG, "EditorBuildEventListener: onBuildFailed - Activity is NULL, cannot proceed.")
      return
    }
    analyzeCurrentFile()
    GeneralPreferences.isFirstBuild = false
    currentActivity.editorViewModel.isBuildInProgress = false
    Log.i(DEBUG_TAG, "EditorBuildEventListener: onBuildFailed - Calling currentActivity.handleBuildFailed()")
    currentActivity.handleBuildFailed()
  }

  override fun onOutput(line: String?) {
    // This can be very noisy too
    // Log.v(DEBUG_TAG, "EditorBuildEventListener: onOutput: $line")
    val currentActivity = checkActivity("onOutput") ?: return
    line?.let {
      currentActivity.appendBuildOutput(it)
      currentActivity.captureBuildLogLine(it)
    }

    if (line != null && (line.contains("BUILD SUCCESSFUL") || line.contains("BUILD FAILED"))) {
      currentActivity.setStatus(line)
    }
  }

  private fun analyzeCurrentFile() {
    _activity?.getCurrentEditor()?.editor?.analyze()
  }

  private fun checkActivity(action: String): EditorHandlerActivity? {
    if (!enabled) {
      Log.w(DEBUG_TAG, "EditorBuildEventListener: checkActivity($action) - Listener NOT enabled.")
      return null
    }
    val current = _activity
    if (current == null) {
      // Use slf4jLogger for this specific existing warning to maintain its style
      if (enabled && activityReference.get() == null) {
        slf4jLogger.warn("[{}] Activity reference has been destroyed!", action)
        Log.e(DEBUG_TAG, "EditorBuildEventListener: checkActivity($action) - Activity reference is NULL and listener is enabled (potential issue).")
      }
    }
    return current
  }
}