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

package com.itsaky.androidide.dialogs

import android.content.Context
import java.io.File

interface ViewModelFileEditorBridge {
    var currentProjectDirBridge: File? // Property for current project directory
    val isModifyingExistingProjectBridge: Boolean // Property for modification status

    fun updateStateBridge(newState: AiWorkflowState)
    fun appendToLogBridge(text: String)
    fun displayAiConclusionBridge(conclusion: String?)
    fun handleErrorBridge(message: String, e: Exception? = null)
    fun runOnUiThreadBridge(block: () -> Unit) // ViewModel will handle thread switching
    fun getContextBridge(): Context // For SharedPreferences or other context needs in helpers
    fun onTemplateProjectCreatedBridge(projectDir: File, appName: String, appDescription: String) // Callback for template creation

    // Trigger build/install (and optionally run). Default no-op so old implementers don't break.
    fun triggerBuildBridge(projectDir: File, runAfterBuild: Boolean) { /* no-op */ }
}