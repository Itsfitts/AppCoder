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

package com.itsaky.androidide.actions.build

import android.content.Context
import androidx.core.content.ContextCompat
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.BaseBuildAction
import com.itsaky.androidide.fragments.RunTasksDialogFragment
import com.itsaky.androidide.resources.R
import java.lang.ref.WeakReference

/** @author Akash Yadav */
class RunTasksAction(context: Context, override val order: Int) : BaseBuildAction() {
  override val id: String = "ide.editor.build.runTasks"

  // Use a WeakReference to avoid memory leaks if the dialog is dismissed by the user.
  private var dialogRef: WeakReference<RunTasksDialogFragment>? = null

  init {
    label = context.getString(R.string.title_run_tasks)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_run_tasks)
  }

  // A helper function to safely dismiss the dialog
  private fun safeDismiss() {
    try {
      val currentDialog = dialogRef?.get()
      // Only dismiss if the dialog exists, is added to a FragmentManager, and is currently visible.
      // This check prevents the "not associated with a fragment manager" crash.
      if (currentDialog != null && currentDialog.isAdded && currentDialog.isVisible) {
        currentDialog.dismissAllowingStateLoss()
      }
    } catch (e: Exception) {
      // Ignored: Catches any other rare edge cases during dismissal.
    } finally {
      dialogRef?.clear()
      dialogRef = null
    }
  }

  override suspend fun execAction(data: ActionData): Any {
    // Safely dismiss any previous dialog before creating a new one.
    safeDismiss()

    val newDialog = RunTasksDialogFragment()
    dialogRef = WeakReference(newDialog)
    return newDialog
  }

  override fun postExec(data: ActionData, result: Any) {
    if (result !is RunTasksDialogFragment) {
      return
    }

    val activity = data.getActivity()
    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
      result.show(activity.supportFragmentManager, this.id)
    }
  }

  override fun destroy() {
    super.destroy()
    // When the action is destroyed, ensure its dialog is also safely dismissed.
    safeDismiss()
  }
}