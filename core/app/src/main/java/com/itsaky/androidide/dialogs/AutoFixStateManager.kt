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

import android.util.Log

object AutoFixStateManager {
    private const val DEBUG_TAG = "AutoFixDebug"

    var isAutoFixModeGloballyActive: Boolean = false
        private set // Allow external read, but only internal set via methods

    var initialAppDescriptionForGlobalAutoFix: String? = null
        private set

    var autoFixAttemptsRemainingGlobal: Int = 0
        private set

    const val MAX_GLOBAL_AUTO_FIX_ATTEMPTS = 2 // Define max attempts here

    fun enableAutoFixMode(initialDescription: String) {
        Log.i(DEBUG_TAG, "AutoFixStateManager: Enabling auto-fix globally. Initial Description HAS been set.")
        isAutoFixModeGloballyActive = true
        initialAppDescriptionForGlobalAutoFix = initialDescription
        autoFixAttemptsRemainingGlobal = MAX_GLOBAL_AUTO_FIX_ATTEMPTS
    }

    fun disableAutoFixMode() {
        Log.i(DEBUG_TAG, "AutoFixStateManager: Disabling auto-fix globally.")
        isAutoFixModeGloballyActive = false
        initialAppDescriptionForGlobalAutoFix = null
        autoFixAttemptsRemainingGlobal = 0
    }

    fun consumeAttempt(): Boolean {
        if (isAutoFixModeGloballyActive && autoFixAttemptsRemainingGlobal > 0) {
            autoFixAttemptsRemainingGlobal--
            Log.i(DEBUG_TAG, "AutoFixStateManager: Consumed an auto-fix attempt. Remaining: $autoFixAttemptsRemainingGlobal")
            return true // Attempt consumed
        }
        Log.w(DEBUG_TAG, "AutoFixStateManager: Could not consume attempt. Active: $isAutoFixModeGloballyActive, Remaining: $autoFixAttemptsRemainingGlobal")
        return false // No attempt consumed
    }

    fun hasAttemptsLeft(): Boolean {
        return isAutoFixModeGloballyActive && autoFixAttemptsRemainingGlobal > 0
    }
}