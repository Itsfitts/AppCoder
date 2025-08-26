package com.itsaky.androidide.dialogs

import java.io.File

/**
 * An interface to abstract away the static dependency on ProjectFileUtils,
 * making the coordinator easier to test.
 */
interface ProjectFileScanner {
    fun scanProjectFiles(projectRoot: File): List<String>
}