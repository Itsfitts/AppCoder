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
// import com.itsaky.androidide.utils.flashError // Replaced by dialog
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
  private val activity: EditorHandlerActivity
    get() = checkNotNull(activityReference.get()) { "Activity reference has been destroyed!" }

  fun setActivity(activity: EditorHandlerActivity) {
    this.activityReference = WeakReference(activity)
    this.enabled = true
  }

  fun release() {
    activityReference.clear()
    this.enabled = false
  }

  override fun prepareBuild(buildInfo: BuildInfo) {
    checkActivity("prepareBuild") ?: return

    // Clear the activity's captured log for the new build
    activity.clearCapturedBuildLog()
    // Also ensure the visual output (bottom sheet) is cleared.
    // This might be done by activity.appendBuildOutput (from superclass) if it starts fresh,
    // or you might need activity.content.bottomSheet.clearBuildOutput() here
    // if clearCapturedBuildLog() doesn't handle the UI view.
    // For safety, if appendBuildOutput in super doesn't clear, explicitly clear UI:
    activity.content.bottomSheet.clearBuildOutput()


    val isFirstBuild = GeneralPreferences.isFirstBuild
    activity.setStatus(
      activity.getString(if (isFirstBuild) string.preparing_first else string.preparing)
    )

    if (isFirstBuild) {
      activity.showFirstBuildNotice()
    }

    activity.editorViewModel.isBuildInProgress = true

    if (buildInfo.tasks.isNotEmpty()) {
      val tasksLine = activity.getString(R.string.title_run_tasks) + " : " + buildInfo.tasks.joinToString()
      // Let the superclass handle displaying this line (e.g., in bottom sheet)
      activity.appendBuildOutput(tasksLine)
      // Also capture it for our dialog
      activity.captureBuildLogLine(tasksLine)
    }
  }

  override fun onBuildSuccessful(tasks: List<String?>) {
    checkActivity("onBuildSuccessful") ?: return
    analyzeCurrentFile()
    GeneralPreferences.isFirstBuild = false
    activity.editorViewModel.isBuildInProgress = false
    activity.flashSuccess(R.string.build_status_sucess)
  }

  override fun onProgressEvent(event: ProgressEvent) {
    checkActivity("onProgressEvent") ?: return
    if (event is ProjectConfigurationStartEvent || event is TaskStartEvent) {
      activity.setStatus(event.descriptor.displayName)
    }
  }

  override fun onBuildFailed(tasks: List<String?>) {
    checkActivity("onBuildFailed") ?: return
    analyzeCurrentFile()
    GeneralPreferences.isFirstBuild = false
    activity.editorViewModel.isBuildInProgress = false
    activity.handleBuildFailedAndShowDialog() // Show dialog with captured log
  }

  override fun onOutput(line: String?) {
    checkActivity("onOutput") ?: return
    line?.let {
      // Let the superclass method (ProjectHandlerActivity.appendBuildOutput)
      // handle its standard UI update (e.g., to the bottom sheet).
      activity.appendBuildOutput(it)

      // Also, capture the line for our internal log in EditorHandlerActivity
      // which will be used for the dialog on failure.
      activity.captureBuildLogLine(it)
    }

    if (line != null && (line.contains("BUILD SUCCESSFUL") || line.contains("BUILD FAILED"))) {
      activity.setStatus(line)
    }
  }

  private fun analyzeCurrentFile() {
    checkActivity("analyzeCurrentFile") ?: return
    val editorView = _activity?.getCurrentEditor()
    if (editorView != null) {
      val editor = editorView.editor
      editor?.analyze()
    }
  }

  private fun checkActivity(action: String): EditorHandlerActivity? {
    if (!enabled) return null
    return _activity.also {
      if (it == null) {
        log.warn("[{}] Activity reference has been destroyed!", action)
        enabled = false
      }
    }
  }
}