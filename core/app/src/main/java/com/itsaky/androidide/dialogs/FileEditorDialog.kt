package com.itsaky.androidide.dialogs

// Existing imports
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

// Add these imports for OkHttp
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType  // Added import
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody  // Added import
import okhttp3.Response
import org.json.JSONObject

// Other imports
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern
import kotlin.concurrent.thread

class FileEditorDialog : DialogFragment() {

    private lateinit var projectNameInput: TextInputEditText
    private lateinit var fileNameInput: TextInputEditText
    private lateinit var fileContentInput: TextInputEditText
    private lateinit var searchInput: TextInputEditText
    private lateinit var geminiPromptInput: TextInputEditText
    private lateinit var applyGeminiButton: Button
    private lateinit var filesList: RecyclerView
    private lateinit var projectsBaseDir: File
    
    private lateinit var fileAdapter: FileListAdapter
    private var allFiles = CopyOnWriteArrayList<File>()
    
    // Gemini API key - In production, store this securely, e.g. in BuildConfig
    // TODO: You need to add your API here.
    private val GEMINI_API_KEY = "Your_GEMINI_API_Here"
    private val client = OkHttpClient()

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
        
        // Initialize Gemini components
        geminiPromptInput = view.findViewById(R.id.gemini_prompt_input)
        applyGeminiButton = view.findViewById(R.id.apply_gemini_button)
        
        // Setup RecyclerView with adapter
        setupFileList()
        
        // Setup search functionality
        setupSearchInput()
        
        // Setup Gemini button click listener
        setupGeminiButton()

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
    
    private fun setupGeminiButton() {
        applyGeminiButton.setOnClickListener {
            val currentContent = fileContentInput.text.toString()
            val prompt = geminiPromptInput.text.toString()
            
            if (currentContent.isEmpty()) {
                Toast.makeText(requireContext(), "No file content to modify", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            if (prompt.isEmpty()) {
                Toast.makeText(requireContext(), "Please provide instructions for Gemini", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            callGeminiAPI(currentContent, prompt)
        }
    }
    
    private fun callGeminiAPI(fileContent: String, instructions: String) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$GEMINI_API_KEY"
        
        val prompt = """
            I have the following code: $fileContent
            
            I want you to $instructions
            
            Place your modified code between <Modefied_Code_Start> and <Modefied_Code_End> tags, and do not include any explanations.
        """.trimIndent()
        
        val requestJson = JSONObject().apply {
            put("contents", JSONObject().apply {
                put("parts", JSONObject().apply {
                    put("text", prompt)
                })
            })
        }
        
        val mediaType = "application/json".toMediaType()
        val requestBody = requestJson.toString().toRequestBody(mediaType)
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        
        // Show loading indicator
        activity?.runOnUiThread {
            applyGeminiButton.isEnabled = false
            applyGeminiButton.text = "Processing..."
        }
            
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "API Error: ${e.message}", Toast.LENGTH_LONG).show()
                    applyGeminiButton.isEnabled = true
                    applyGeminiButton.text = "Apply Gemini Changes"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val responseBody = response.body?.string()
                    val jsonResponse = JSONObject(responseBody ?: "")
                    
                    if (jsonResponse.has("candidates")) {
                        val textResponse = jsonResponse.getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                        
                        // Extract code between the tags
                        val extractedCode = extractCodeFromResponse(textResponse)
                        
                        activity?.runOnUiThread {
                            fileContentInput.setText(extractedCode)
                            Toast.makeText(requireContext(), "Gemini changes applied!", Toast.LENGTH_SHORT).show()
                            applyGeminiButton.isEnabled = true
                            applyGeminiButton.text = "Apply Gemini Changes"
                        }
                    } else if (jsonResponse.has("promptFeedback") && 
                              jsonResponse.getJSONObject("promptFeedback").has("blockReason")) {
                        val blockReason = jsonResponse.getJSONObject("promptFeedback").getString("blockReason")
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), "Response blocked: $blockReason", Toast.LENGTH_LONG).show()
                            applyGeminiButton.isEnabled = true
                            applyGeminiButton.text = "Apply Gemini Changes"
                        }
                    } else {
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), "Empty or unexpected response from API", Toast.LENGTH_LONG).show()
                            applyGeminiButton.isEnabled = true
                            applyGeminiButton.text = "Apply Gemini Changes"
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing API response", e)
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Error processing response: ${e.message}", Toast.LENGTH_LONG).show()
                        applyGeminiButton.isEnabled = true
                        applyGeminiButton.text = "Apply Gemini Changes"
                    }
                }
            }
        })
    }
    
    private fun extractCodeFromResponse(response: String): String {
        val pattern = Pattern.compile("<Modefied_Code_Start>(.*?)<Modefied_Code_End>", Pattern.DOTALL)
        val matcher = pattern.matcher(response)
        
        return if (matcher.find()) {
            // Get the content between tags
            val codeWithMarkdown = matcher.group(1)?.trim() ?: response
            
            // Remove markdown code block markers if present (```language and ```)
            codeWithMarkdown.replace(Regex("^```\\w*\\s*", RegexOption.MULTILINE), "")
                            .replace(Regex("```\\s*$", RegexOption.MULTILINE), "")
                            .trim()
        } else {
            // If tags aren't found, return the whole response as a fallback
            response
        }
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