// AutoFixStateManager.kt
package com.itsaky.androidide.dialogs // Or your chosen package

import android.util.Log

object AutoFixStateManager {
    private const val DEBUG_TAG = "AutoFixDebug"

    var isAutoFixModeGloballyActive: Boolean = false
        private set
    var initialAppDescriptionForGlobalAutoFix: String? = null
        private set
    var autoFixAttemptsRemainingGlobal: Int = 0
        private set

    const val MAX_GLOBAL_AUTO_FIX_ATTEMPTS = 2

    fun enableAutoFixMode(initialDescription: String) {
        if (initialDescription.isBlank()) {
            Log.w(DEBUG_TAG, "AutoFixStateManager: Attempted to enable with blank description. Not enabling.")
            // Optionally disable if it was somehow active with a null/blank desc
            if (isAutoFixModeGloballyActive) disableAutoFixMode()
            return
        }
        // If it's already active with the same description, maybe don't reset attempts?
        // For now, enabling always resets attempts for a "fresh" auto-fix session.
        Log.i(DEBUG_TAG, "AutoFixStateManager: Enabling auto-fix globally. Attempts set to $MAX_GLOBAL_AUTO_FIX_ATTEMPTS.")
        isAutoFixModeGloballyActive = true
        initialAppDescriptionForGlobalAutoFix = initialDescription
        autoFixAttemptsRemainingGlobal = MAX_GLOBAL_AUTO_FIX_ATTEMPTS
    }

    fun disableAutoFixMode() {
        if (!isAutoFixModeGloballyActive && initialAppDescriptionForGlobalAutoFix == null && autoFixAttemptsRemainingGlobal == 0) {
            // Already in a fully disabled state, no need to log again if called redundantly
            return
        }
        Log.i(DEBUG_TAG, "AutoFixStateManager: Disabling auto-fix globally. Resetting attempts and description.")
        isAutoFixModeGloballyActive = false
        initialAppDescriptionForGlobalAutoFix = null
        autoFixAttemptsRemainingGlobal = 0
    }

    /**
     * Consumes an attempt if auto-fix is active and attempts are left.
     * @return true if an attempt was successfully consumed, false otherwise.
     */
    fun consumeAttempt(): Boolean {
        if (isAutoFixModeGloballyActive && autoFixAttemptsRemainingGlobal > 0) {
            autoFixAttemptsRemainingGlobal--
            Log.i(DEBUG_TAG, "AutoFixStateManager: Consumed an auto-fix attempt. Remaining: $autoFixAttemptsRemainingGlobal")
            return true
        }
        Log.w(DEBUG_TAG, "AutoFixStateManager: Could NOT consume attempt. Active: $isAutoFixModeGloballyActive, Remaining: $autoFixAttemptsRemainingGlobal")
        return false
    }

    fun canAttemptAutoFix(): Boolean {
        val canAttempt = isAutoFixModeGloballyActive &&
                autoFixAttemptsRemainingGlobal > 0 &&
                !initialAppDescriptionForGlobalAutoFix.isNullOrBlank()
        if (!canAttempt) {
            Log.d(DEBUG_TAG, "AutoFixStateManager: canAttemptAutoFix is FALSE. Active: $isAutoFixModeGloballyActive, Remaining: $autoFixAttemptsRemainingGlobal, DescSet: ${!initialAppDescriptionForGlobalAutoFix.isNullOrBlank()}")
        }
        return canAttempt
    }
}