package com.itsaky.androidide.dialogs

import java.io.File

/**
 * The real implementation of ProjectFileScanner that the production app will use.
 * It delegates the call to the original static ProjectFileUtils method.
 */
class DefaultProjectFileScanner : ProjectFileScanner {
    override fun scanProjectFiles(projectRoot: File): List<String> {
        return ProjectFileUtils.scanProjectFiles(projectRoot)
    }
}