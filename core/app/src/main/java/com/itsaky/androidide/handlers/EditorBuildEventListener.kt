package com.itsaky.androidide.handlers

import com.itsaky.androidide.R // For R.string.build_status_sucess etc.
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.resources.R.string // For aliased R.string
import com.itsaky.androidide.services.builder.GradleBuildService
import com.itsaky.androidide.tooling.api.messages.result.BuildInfo
import com.itsaky.androidide.tooling.events.ProgressEvent
import com.itsaky.androidide.tooling.events.configuration.ProjectConfigurationStartEvent
import com.itsaky.androidide.tooling.events.task.TaskStartEvent
import com.itsaky.androidide.utils.flashSuccess
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference

/**
 * Handles events received from [GradleBuildService] updates [EditorHandlerActivity].
 * @author Akash Yadav
 */
class EditorBuildEventListener : GradleBuildService.EventListener {

  private var enabled = true
  private var activityReference: WeakReference<EditorHandlerActivity> = WeakReference(null)

  companion object {
    private val log = LoggerFactory.getLogger(EditorBuildEventListener::class.java)
  }

  private val _activity: EditorHandlerActivity?
    get() = activityReference.get()
  // Removed the non-null asserted 'activity' property to avoid potential NPEs
  // if accessed after release or if activity is destroyed unexpectedly.
  // Always use _activity and null-check or checkActivity().

  fun setActivity(activity: EditorHandlerActivity) {
    this.activityReference = WeakReference(activity)
    this.enabled = true
  }

  fun release() {
    activityReference.clear()
    this.enabled = false
  }

  override fun prepareBuild(buildInfo: BuildInfo) {
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

    if (buildInfo.tasks.isNotEmpty()) {
      val tasksLine = currentActivity.getString(R.string.title_run_tasks) + " : " + buildInfo.tasks.joinToString()
      currentActivity.appendBuildOutput(tasksLine)
      currentActivity.captureBuildLogLine(tasksLine)
    }
  }

  override fun onBuildSuccessful(tasks: List<String?>) {
    val currentActivity = checkActivity("onBuildSuccessful") ?: return
    analyzeCurrentFile()
    GeneralPreferences.isFirstBuild = false
    currentActivity.editorViewModel.isBuildInProgress = false
    currentActivity.flashSuccess(R.string.build_status_sucess)

    // Notify EditorHandlerActivity if auto-fix was active and build succeeded
    if (currentActivity.isAutoFixModeActivePublic) {
      currentActivity.handleAutoFixBuildSuccess()
    }
  }

  override fun onProgressEvent(event: ProgressEvent) {
    val currentActivity = checkActivity("onProgressEvent") ?: return
    if (event is ProjectConfigurationStartEvent || event is TaskStartEvent) {
      currentActivity.setStatus(event.descriptor.displayName)
    }
  }

  override fun onBuildFailed(tasks: List<String?>) {
    val currentActivity = checkActivity("onBuildFailed") ?: return
    analyzeCurrentFile()
    GeneralPreferences.isFirstBuild = false
    currentActivity.editorViewModel.isBuildInProgress = false
    // This will correctly trigger the automated retry if applicable, or show manual dialog
    currentActivity.handleBuildFailedAndShowDialog()
  }

  override fun onOutput(line: String?) {
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
    // Use _activity directly to avoid crash if activity is gone but this is called
    _activity?.getCurrentEditor()?.editor?.analyze()
  }

  private fun checkActivity(action: String): EditorHandlerActivity? {
    if (!enabled) return null
    val current = _activity // Cache the weak reference's get() result
    if (current == null) {
      // Only log if it was previously enabled to avoid spamming on release
      // and if the reference is actually null (meaning activity is gone)
      if (enabled && activityReference.get() == null) {
        log.warn("[{}] Activity reference has been destroyed!", action)
      }
      // Consider if 'enabled' should be set to false here.
      // If activity is gone, further events are likely useless.
      // However, release() is the explicit method for this.
      // For safety, let's keep it as is unless problems arise.
    }
    return current
  }
}