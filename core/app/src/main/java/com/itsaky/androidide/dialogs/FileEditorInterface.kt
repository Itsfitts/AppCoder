package com.itsaky.androidide.dialogs // Your current package for this interface

import android.content.Context
import java.io.File

interface FileEditorInterface {
    var currentProjectDir: File?
    val isModifyingExistingProjectInternal: Boolean

    fun setState(newState: FileEditorActivity.WorkflowState) // Assumes FileEditorActivity.WorkflowState is accessible
    fun appendToLog(text: String)
    fun displayAiConclusion(conclusion: String?) // <<< ADDED THIS METHOD DECLARATION
    fun handleError(message: String, e: Exception? = null)
    fun runOnUiThread(block: () -> Unit)
    fun getContextForHelpers(): Context
}