package com.itsaky.androidide.dialogs

import android.content.Context

/**
 * An interface to abstract away the static dependency on AiForegroundService,
 * making the coordinator easier to test.
 */
interface AiServiceManager {
    fun startService(context: Context, message: String)

    // --- THIS LINE WAS MISSING ---
    fun stopService(context: Context)
}