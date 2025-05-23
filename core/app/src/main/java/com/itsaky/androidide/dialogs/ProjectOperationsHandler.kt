package com.itsaky.androidide.dialogs

import android.util.Log
import com.itsaky.androidide.models.NewProjectDetails
import com.itsaky.androidide.templates.Language
import com.itsaky.androidide.templates.ProjectTemplateRecipeResult
import com.itsaky.androidide.templates.Sdk
import com.itsaky.androidide.templates.impl.basicActivity.basicActivityProject
import com.itsaky.androidide.utils.TemplateRecipeExecutor
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.concurrent.thread

class ProjectOperationsHandler(
    private val projectsBaseDir: File,
    private val logAppender: (String) -> Unit,
    private val errorHandler: (String, Exception?) -> Unit,
    private val dialogInterface: FileEditorDialog // To call back onTemplateProjectCreated
) {
    companion object {
        private const val TAG = "ProjectOpsHandler"
    }

    fun projectExists(appName: String): Boolean {
        if (appName.isBlank()) return false
        val projectDir = File(projectsBaseDir, appName)
        return projectDir.exists() && projectDir.isDirectory
    }

    fun createNewProjectFromTemplate(appName: String, onComplete: (projectDir: File) -> Unit) {
        logAppender("Starting new project template creation for: $appName\n")
        dialogInterface.setState(FileEditorDialog.WorkflowState.CREATING_PROJECT_TEMPLATE)

        thread { // Perform file operations off the main thread
            try {
                val packageName = createPackageName(appName)
                val projectDir = File(projectsBaseDir, appName) // Target directory

                if (projectDir.exists()) {
                    // This should ideally be caught before calling this method,
                    // but double-check here.
                    errorHandler("Project directory '$appName' already exists for new project.", null)
                    return@thread
                }

                logAppender("Package Name: $packageName\n")
                logAppender("Save Location: ${projectDir.absolutePath}\n")
                logAppender("Language: Kotlin, Min SDK: 21\n")

                val projectDetails = NewProjectDetails().apply {
                    this.name = appName
                    this.packageName = packageName
                    this.minSdk = 21 // Default Min SDK
                    this.targetSdk = 34 // Default Target SDK
                    this.language = "kotlin" // Default language
                    this.savePath = projectsBaseDir.absolutePath
                }

                val template = basicActivityProject() // Assuming this is accessible

                // Setup parameters (using the extension function from your original code)
                val iterator = template.parameters.iterator()
                try {
                    (iterator.next() as? com.itsaky.androidide.templates.StringParameter)?.setValue(projectDetails.name)
                    (iterator.next() as? com.itsaky.androidide.templates.StringParameter)?.setValue(projectDetails.packageName)
                    (iterator.next() as? com.itsaky.androidide.templates.StringParameter)?.setValue(projectDetails.savePath)
                    val langEnum = if (projectDetails.language == "kotlin") Language.Kotlin else Language.Java
                    (iterator.next() as? com.itsaky.androidide.templates.EnumParameter<Language>)?.setValue(langEnum)
                    val sdkEnum = Sdk.values().find { it.api == projectDetails.minSdk } ?: Sdk.Lollipop
                    (iterator.next() as? com.itsaky.androidide.templates.EnumParameter<Sdk>)?.setValue(sdkEnum)
                    val useKtsValue = (langEnum == Language.Kotlin)
                    if (iterator.hasNext()) {
                        (iterator.next() as? com.itsaky.androidide.templates.BooleanParameter)?.setValue(useKtsValue)
                    }
                } catch (e: Exception) {
                    throw IOException("Failed to set template parameters: ${e.message}", e)
                }


                val executor = TemplateRecipeExecutor()
                val result = template.recipe.execute(executor)

                if (result is ProjectTemplateRecipeResult) {
                    // Call back to dialog to inform it and proceed with next steps (like Gemini flow)
                    // The actual next step (Gemini file selection) will be triggered by the callback in FileEditorDialog
                    dialogInterface.activity?.runOnUiThread {
                        onComplete(result.data.projectDir)
                        // The dialog will then call its own onTemplateProjectCreated or directly proceed
                    }
                } else {
                    throw IOException("Template execution failed. Result: $result")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating project from template", e)
                errorHandler("Failed to create project from template: ${e.message}", e)
            }
        }
    }

    private fun createPackageName(appName: String): String {
        val sanitizedName = appName.filter { it.isLetterOrDigit() }.lowercase(Locale.getDefault())
        return "com.example.${sanitizedName.ifEmpty { "myapp" }}"
    }

    fun listExistingProjectNames(): List<String> {
        return projectsBaseDir.listFiles { file ->
            file.isDirectory // Basic check
        }?.map { it.name }?.sorted() ?: emptyList()
    }
}