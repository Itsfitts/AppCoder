package com.itsaky.androidide.dialogs

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.FileListAdapter
import com.itsaky.androidide.utils.FileUtil
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class FileEditorDialog : DialogFragment() {

    private lateinit var projectNameInput: TextInputEditText
    private lateinit var fileNameInput: TextInputEditText
    private lateinit var fileContentInput: TextInputEditText
    private lateinit var searchInput: TextInputEditText
    private lateinit var filesList: RecyclerView
    private lateinit var projectsBaseDir: File
    
    private lateinit var fileAdapter: FileListAdapter
    private var allFiles = CopyOnWriteArrayList<File>()

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

        // Initialize the original input fields
        projectNameInput = view.findViewById(R.id.project_name_input)
        fileNameInput = view.findViewById(R.id.file_name_input)
        fileContentInput = view.findViewById(R.id.file_content_input)
        
        // Initialize the new search and file list components
        searchInput = view.findViewById(R.id.search_input)
        filesList = view.findViewById(R.id.files_list)
        
        // Setup RecyclerView with adapter
        setupFileList()
        
        // Setup search functionality
        setupSearchInput()

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
    
    private fun setupFileList() {
        fileAdapter = FileListAdapter(emptyList()) { file ->
            onFileSelected(file)
        }
        
        filesList.layoutManager = LinearLayoutManager(requireContext())
        filesList.adapter = fileAdapter
        
        // Load all files in the project directory
        loadFilesFromDirectory(projectsBaseDir)
    }
    
    private fun setupSearchInput() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterFiles(s.toString())
            }
        })
    }
    
    private fun loadFilesFromDirectory(directory: File) {
        if (!directory.exists() || !directory.isDirectory) {
            Log.e(TAG, "Invalid directory: ${directory.absolutePath}")
            return
        }
        
        thread {
            val files = mutableListOf<File>()
            collectAllFiles(directory, files)
            
            activity?.runOnUiThread {
                allFiles.clear()
                allFiles.addAll(files)
                fileAdapter.updateFiles(allFiles)
            }
        }
    }
    
    private fun collectAllFiles(dir: File, result: MutableList<File>) {
        if (!dir.exists() || !dir.isDirectory) return
        
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                collectAllFiles(file, result)
            } else if (!file.name.startsWith(".")) { // Skip hidden files
                result.add(file)
            }
        }
    }
    
    private fun filterFiles(query: String) {
        if (query.isEmpty()) {
            fileAdapter.updateFiles(allFiles)
            return
        }
        
        val lowerCaseQuery = query.lowercase()
        val filteredFiles = allFiles.filter {
            it.name.lowercase().contains(lowerCaseQuery) ||
            it.absolutePath.lowercase().contains(lowerCaseQuery)
        }
        
        fileAdapter.updateFiles(filteredFiles)
    }
    
    private fun onFileSelected(file: File) {
        try {
            // Get the relative path of the file within the project
            val absolutePath = file.absolutePath
            val relativePath = if (absolutePath.startsWith(projectsBaseDir.absolutePath)) {
                val baseDirPath = projectsBaseDir.absolutePath
                val pathAfterBaseDir = absolutePath.substring(baseDirPath.length)
                
                // Extract project name (first directory after base dir)
                val pathParts = pathAfterBaseDir.trim('/').split('/')
                if (pathParts.isNotEmpty()) {
                    val projectName = pathParts[0]
                    projectNameInput.setText(projectName)
                    
                    // Set file path relative to project
                    if (pathParts.size > 1) {
                        val relativeToProject = pathAfterBaseDir.substring(projectName.length + 1)
                        fileNameInput.setText(relativeToProject.trimStart('/'))
                    } else {
                        fileNameInput.setText("")
                    }
                }
                
                pathAfterBaseDir.trimStart('/')
            } else {
                // If the file is outside the projects base directory, just use filename
                projectNameInput.setText("")
                fileNameInput.setText(file.name)
                file.name
            }
            
            // Load the file content
            try {
                val content = FileReader(file).use { it.readText() }
                fileContentInput.setText(content)
            } catch (e: IOException) {
                Log.e(TAG, "Error reading file content", e)
                Toast.makeText(requireContext(), "Error reading file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing selected file", e)
        }
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