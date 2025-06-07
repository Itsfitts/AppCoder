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

import android.util.Log
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
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference

class EditorBuildEventListener : GradleBuildService.EventListener {

  private val slf4jLogger = LoggerFactory.getLogger(EditorBuildEventListener::class.java)
  private val DEBUG_TAG = "AutoFixDebug"

  private var enabled = true
  private var activityReference: WeakReference<EditorHandlerActivity> = WeakReference(null)

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
    currentActivity.updateAutoFixModeIndicator()

    if (buildInfo.tasks.isNotEmpty()) {
      val tasksLine = currentActivity.getString(R.string.title_run_tasks) + " : " + buildInfo.tasks.joinToString()
      currentActivity.appendBuildOutput(tasksLine)
      currentActivity.captureBuildLogLine(tasksLine)
    }
  }

  override fun onBuildSuccessful(tasks: List<String?>) {
    Log.d(DEBUG_TAG, "EditorBuildEventListener: onBuildSuccessful called for tasks: ${tasks?.joinToString()}")
    val currentActivity = checkActivity("onBuildSuccessful") ?: return
    analyzeCurrentFile()
    GeneralPreferences.isFirstBuild = false
    currentActivity.editorViewModel.isBuildInProgress = false

    Log.d(DEBUG_TAG, "EditorBuildEventListener: onBuildSuccessful - isAutoFixModeActivePublic=${currentActivity.isAutoFixModeActivePublic}")
    if (currentActivity.isAutoFixModeActivePublic) {
      Log.i(DEBUG_TAG, "EditorBuildEventListener: onBuildSuccessful - Auto-fix was active. Calling handleAutoFixBuildSuccess.")
      currentActivity.handleAutoFixBuildSuccess(tasks)
    } else {
      currentActivity.flashSuccess(R.string.build_status_sucess)
    }
  }

  override fun onProgressEvent(event: ProgressEvent) {
    val currentActivity = checkActivity("onProgressEvent") ?: return
    if (event is ProjectConfigurationStartEvent || event is TaskStartEvent) {
      currentActivity.setStatus(event.descriptor.displayName)
    }
  }

  override fun onBuildFailed(tasks: List<String?>) {
    Log.e(DEBUG_TAG, "EditorBuildEventListener: onBuildFailed called for tasks: ${tasks?.joinToString()}")
    val currentActivity = checkActivity("onBuildFailed") ?: run {
      Log.e(DEBUG_TAG, "EditorBuildEventListener: onBuildFailed - Activity is NULL, cannot proceed.")
      return
    }
    analyzeCurrentFile()
    GeneralPreferences.isFirstBuild = false
    currentActivity.editorViewModel.isBuildInProgress = false
    Log.i(DEBUG_TAG, "EditorBuildEventListener: onBuildFailed - Calling currentActivity.handleBuildFailed()")

    // Pass the failing tasks to the handler
    currentActivity.handleBuildFailed(tasks)
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
    _activity?.getCurrentEditor()?.editor?.analyze()
  }

  private fun checkActivity(action: String): EditorHandlerActivity? {
    if (!enabled) {
      Log.w(DEBUG_TAG, "EditorBuildEventListener: checkActivity($action) - Listener NOT enabled.")
      return null
    }
    val current = _activity
    if (current == null) {
      if (enabled && activityReference.get() == null) {
        slf4jLogger.warn("[{}] Activity reference has been destroyed!", action)
        Log.e(DEBUG_TAG, "EditorBuildEventListener: checkActivity($action) - Activity reference is NULL and listener is enabled (potential issue).")
      }
    }
    return current
  }
}