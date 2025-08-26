package com.itsaky.androidide.dialogs

import android.content.Context
import com.itsaky.androidide.services.AiForegroundService

/**
 * The real implementation of AiServiceManager that the production app will use.
 * It contains the actual calls to the static AiForegroundService.
 */
class DefaultAiServiceManager : AiServiceManager {
    override fun startService(context: Context, message: String) {
        AiForegroundService.start(context, message)
    }

    override fun stopService(context: Context) {
        AiForegroundService.stop(context)
    }
}