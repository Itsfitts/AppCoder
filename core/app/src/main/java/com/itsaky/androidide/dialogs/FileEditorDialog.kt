package com.itsaky.androidide.dialogs

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.itsaky.androidide.R
import java.io.File
import java.io.FileWriter
import java.io.IOException

class FileEditorDialog : DialogFragment() {

    private lateinit var projectNameInput: TextInputEditText
    private lateinit var fileNameInput: TextInputEditText
    private lateinit var fileContentInput: TextInputEditText
    private lateinit var projectsBaseDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val basePath = requireArguments().getString(ARG_PROJECTS_BASE_DIR)
        if (basePath == null) {
            Log.e(TAG, "Projects base directory argument is missing!")
            Toast.makeText(requireContext(), "Error: Projects base directory not specified.", Toast.LENGTH_LONG).show()
            dismiss()
            return
        }
        projectsBaseDir = File(basePath)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.layout_file_editor_dialog, null)

        projectNameInput = view.findViewById(R.id.project_name_input)
        fileNameInput = view.findViewById(R.id.file_name_input)
        fileContentInput = view.findViewById(R.id.file_content_input)

        builder.setView(view)
            .setTitle("Edit File Content")
            .setPositiveButton("Replace Content", null)
            .setNegativeButton("Cancel") { _, _ -> dismiss() }

        val dialog = builder.create()

        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                if (validateInputs()) {
                    replaceFileContent()
                }
            }
        }
        return dialog
    }

    private fun validateInputs(): Boolean {
        val projectName = projectNameInput.text.toString().trim()
        val relativeFilePath = fileNameInput.text.toString().trim()

        if (!::projectsBaseDir.isInitialized || !projectsBaseDir.exists() || !projectsBaseDir.isDirectory) {
             Log.e(TAG, "Validation failed: Invalid projects base directory.")
             Toast.makeText(requireContext(), "Error: Invalid projects base directory.", Toast.LENGTH_LONG).show()
             return false
        }

        if (projectName.isEmpty()) {
            projectNameInput.error = "Project name is required"
            return false
        }
        projectNameInput.error = null

        if (relativeFilePath.isEmpty()) {
            fileNameInput.error = "File path is required"
            return false
        }
        if (relativeFilePath.startsWith("/") || relativeFilePath.startsWith("\\")) {
            fileNameInput.error = "Path should be relative (no leading slash)"
            return false
        }
        fileNameInput.error = null
        return true
    }

    private fun replaceFileContent() {
        val projectName = projectNameInput.text.toString().trim()
        val relativeFilePath = fileNameInput.text.toString().trim()
        val fileContent = fileContentInput.text.toString()

        val projectDir = File(projectsBaseDir, projectName)
        val file = File(projectDir, relativeFilePath)
        Log.d(TAG, "Attempting to write to file: ${file.absolutePath}")

        try {
            val parentDir = file.parentFile
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    Log.e(TAG, "Failed to create parent directories: ${parentDir.absolutePath}")
                    Toast.makeText(requireContext(), "Error creating directories: ${parentDir.path}", Toast.LENGTH_LONG).show()
                    return
                }
            }

            FileWriter(file, false).use { writer ->
                writer.write(fileContent)
                Toast.makeText(requireContext(), "File updated: ${file.name}", Toast.LENGTH_LONG).show()
                dismiss()
            }
        } catch (e: IOException) {
            Log.e(TAG, "IOException writing file: ${file.absolutePath}", e)
            Toast.makeText(requireContext(), "Error updating file: ${e.message}", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException writing file: ${file.absolutePath}", e)
            Toast.makeText(requireContext(), "Permission denied writing file.", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val TAG = "FileEditorDialog"
        private const val ARG_PROJECTS_BASE_DIR = "projects_base_dir"

        fun newInstance(projectsBaseDir: String): FileEditorDialog {
            val fragment = FileEditorDialog()
            fragment.arguments = bundleOf(
                ARG_PROJECTS_BASE_DIR to projectsBaseDir
            )
            return fragment
        }
    }
}