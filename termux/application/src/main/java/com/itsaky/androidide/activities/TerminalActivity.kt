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

package com.itsaky.androidide.activities

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log // Standard Android Log
import java.io.File
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.itsaky.androidide.terminal.IdeTerminalSessionClient
import com.itsaky.androidide.terminal.IdesetupSession
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.flashError
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.terminal.TermuxTerminalSessionActivityClient
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import org.slf4j.LoggerFactory // SLF4J Logger

/**
 * @author Akash Yadav
 */
class TerminalActivity : TermuxActivity() {

  // Use SLF4J logger consistently if it's set up
  companion object {
    private val log = LoggerFactory.getLogger(TerminalActivity::class.java) // Keep SLF4J
    private const val KEY_TERMINAL_CAN_ADD_SESSIONS = "ide.terminal.sessions.canAddSessions"

    const val EXTRA_ONBOARDING_RUN_IDESETUP = "ide.onboarding.terminal.runIdesetup"
    const val EXTRA_ONBOARDING_RUN_IDESETUP_ARGS = "ide.onboarding.terminal.runIdesetup.args"
  }

  override val navigationBarColor: Int
    get() = ContextCompat.getColor(this, android.R.color.black)
  override val statusBarColor: Int
    get() = ContextCompat.getColor(this, android.R.color.black)

  private var canAddNewSessions = true
    set(value) {
      field = value
      // Ensure the view lookup happens safely after layout inflation
      findViewById<View>(R.id.new_session_button)?.isEnabled = value
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    val controller = WindowCompat.getInsetsController(
      window, window.decorView)
    controller.isAppearanceLightNavigationBars = false
    controller.isAppearanceLightStatusBars = false
    super.onCreate(savedInstanceState)

    canAddNewSessions = savedInstanceState?.getBoolean(
      KEY_TERMINAL_CAN_ADD_SESSIONS, true) ?: true

    // Apply canAddNewSessions state after layout is available
    findViewById<View>(R.id.new_session_button)?.isEnabled = canAddNewSessions
  }

  override fun onCreateTerminalSessionClient(): TermuxTerminalSessionActivityClient {
    return IdeTerminalSessionClient(this)
  }

  override fun onSaveInstanceState(savedInstanceState: Bundle) {
    super.onSaveInstanceState(savedInstanceState)
    savedInstanceState.putBoolean(KEY_TERMINAL_CAN_ADD_SESSIONS, canAddNewSessions)
  }

  override fun onServiceConnected(componentName: ComponentName?, service: IBinder?) {
    super.onServiceConnected(componentName, service)
    Environment.mkdirIfNotExits(Environment.TMP_DIR)
  }

  override fun onCreateNewSession(
    isFailsafe: Boolean,
    sessionName: String?,
    workingDirectory: String?
  ) {
    if (canAddNewSessions) {
      super.onCreateNewSession(isFailsafe, sessionName, workingDirectory)
    } else {
      flashError(R.string.msg_terminal_new_sessions_disabled)
    }
  }

  override fun setupTermuxSessionOnServiceConnected(
  intent: Intent?,
  workingDir: String?,
  sessionName: String?,
  existingSession: TermuxSession?,
  launchFailsafe: Boolean
) {
  if (intent != null) {
    val runIdesetup = intent.getBooleanExtra(EXTRA_ONBOARDING_RUN_IDESETUP, false)

    if (runIdesetup) {
      val runIdesetupArgs = intent.getStringArrayExtra(EXTRA_ONBOARDING_RUN_IDESETUP_ARGS)

      if (runIdesetupArgs.isNullOrEmpty()) {
        // Rather than generate defaults, we should handle this error condition
        log.error("IDE setup requested but no arguments provided. Redirecting to configuration.")

        // Use component name instead of direct class reference
        val configIntent = Intent().apply {
          action = "com.itsaky.androidide.action.ONBOARDING"
          component = ComponentName(
            "com.itsaky.androidide",
            "com.itsaky.androidide.activities.OnboardingActivity"
          )
          putExtra("RETURN_TO_SETUP", true)
          addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        
        startActivity(configIntent)
        finish()
        return
      }

      // Arguments are present, proceed with adding the idesetup session
      addIdesetupSession(runIdesetupArgs)
      return
    }
  }

  // If not running idesetup or intent is null, proceed with normal/default setup
  super.setupTermuxSessionOnServiceConnected(
    intent,
    workingDir,
    sessionName,
    existingSession,
    launchFailsafe
  )
}


  private fun addIdesetupSession(args: Array<String>) {
    val script = IdesetupSession.createScript(this) ?: run {
      log.error("Failed to add idesetup session. Cannot create script.")
      flashError(R.string.msg_cannot_create_terminal_session)
      return
    }

    // --- Use the system shell as a fallback ---
    val systemShellPath = "/system/bin/sh"
    val scriptFile = File(script.absolutePath)

    // --- Check if script exists and has read permission ---
    if (!scriptFile.exists() || !scriptFile.canRead()) {
      log.error("Script file does not exist or cannot be read: ${script.absolutePath}")
      flashError(R.string.msg_cannot_create_terminal_session) // Or a more specific error
      return
    }
    // --- Optional: Ensure script is executable (though sh only needs read) ---
    // scriptFile.setExecutable(true) // May require permissions


    // --- Prepare arguments for the SHELL ---
    // The first argument to the shell is the script itself, followed by the original args.
    val shellArgs = arrayOf(script.absolutePath) + args

    log.info("Starting IDE setup session via system shell: $systemShellPath") // Updated log
    log.debug("  Script: ${script.absolutePath}")
    log.debug("  Arguments passed to script: {}", args.joinToString(separator = " "))
    log.debug("  Arguments passed to shell: {}", shellArgs.joinToString(separator = " "))


    // 'session' is the IdesetupSession object
    val session = IdesetupSession.wrap(termuxService.createTermuxSession(
      /* executablePath = */ systemShellPath, // <--- Execute /system/bin/sh
      /* arguments = */ shellArgs,
      /* stdin = */ null,
      /* workingDirectory = */ Environment.HOME.absolutePath,
      /* isFailSafe = */ false, // Keep as false unless specifically needed
      /* sessionName = */ "IDE setup"
    ), script)

    session ?: run {
      log.error("Failed to create TermuxSession for IDE setup using system shell.") // Updated log
      flashError(R.string.msg_cannot_create_terminal_session)
      return
    }

    log.info("IDE setup session created successfully using system shell. Session PID: {}", session.terminalSession.getPid()) // Updated log
    termuxTerminalSessionClient.setCurrentSession(session.terminalSession)
  }
}