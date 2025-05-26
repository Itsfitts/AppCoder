package com.itsaky.androidide.dialogs // Your package for this handler

import android.util.Log
import com.itsaky.androidide.models.NewProjectDetails
import com.itsaky.androidide.templates.Language
import com.itsaky.androidide.templates.ProjectTemplate // For type of 'template'
import com.itsaky.androidide.templates.ProjectTemplateRecipeResult
import com.itsaky.androidide.templates.RecipeExecutor // The interface for the executor type
import com.itsaky.androidide.templates.Sdk
import com.itsaky.androidide.templates.Parameter // Base class for parameters
// TemplateRecipe is implicitly used via template.recipe
import com.itsaky.androidide.templates.impl.basicActivity.basicActivityProject
// CORRECTED IMPORT for your production TemplateRecipeExecutor
import com.itsaky.androidide.utils.TemplateRecipeExecutor

// Specific parameter types
import com.itsaky.androidide.templates.StringParameter
import com.itsaky.androidide.templates.EnumParameter
import com.itsaky.androidide.templates.BooleanParameter

import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.concurrent.thread

class ProjectOperationsHandler(
    private val projectsBaseDir: File,
    private val directLogAppender: (String) -> Unit,
    private val directErrorHandler: (String, Exception?) -> Unit,
    private val fileEditorInterface: FileEditorInterface
) {
    companion object {
        private const val TAG = "ProjectOpsHandler"
    }

    private fun logViaInterface(message: String) = fileEditorInterface.appendToLog(message)
    private fun errorViaInterface(message: String, e: Exception?) = fileEditorInterface.handleError(message, e)

    fun projectExists(appName: String): Boolean {
        if (appName.isBlank()) return false
        val projectDir = File(projectsBaseDir, appName)
        return projectDir.exists() && projectDir.isDirectory
    }

    fun createNewProjectFromTemplate(appName: String, onComplete: (projectDir: File) -> Unit) {
        logViaInterface("Starting new project template creation for: $appName\n")
        fileEditorInterface.setState(FileEditorActivity.WorkflowState.CREATING_PROJECT_TEMPLATE)

        thread {
            try {
                val packageName = createPackageName(appName)
                val projectDir = File(projectsBaseDir, appName)
                if (projectDir.exists()) {
                    errorViaInterface("Project directory '$appName' already exists.", null)
                    fileEditorInterface.setState(FileEditorActivity.WorkflowState.IDLE)
                    return@thread
                }

                logViaInterface("Package Name: $packageName\n")
                logViaInterface("Save Location: ${projectDir.absolutePath}\n")

                val projectDetails = NewProjectDetails().apply {
                    this.name = appName
                    this.packageName = packageName
                    this.minSdk = 21
                    this.targetSdk = 34
                    this.language = "kotlin"
                    this.savePath = projectsBaseDir.absolutePath
                }

                val template: ProjectTemplate = basicActivityProject()

                val iterator = template.parameters.iterator()
                try {
                    (iterator.next() as? StringParameter)?.setValue(projectDetails.name)
                    (iterator.next() as? StringParameter)?.setValue(projectDetails.packageName)
                    (iterator.next() as? StringParameter)?.setValue(projectDetails.savePath)
                    val langEnum = if (projectDetails.language == "kotlin") Language.Kotlin else Language.Java
                    (iterator.next() as? EnumParameter<Language>)?.setValue(langEnum)
                    val sdkEnum = Sdk.values().find { it.api == projectDetails.minSdk } ?: Sdk.Lollipop
                    (iterator.next() as? EnumParameter<Sdk>)?.setValue(sdkEnum)
                    val useKtsValue = (langEnum == Language.Kotlin)
                    if (iterator.hasNext()) {
                        (iterator.next() as? BooleanParameter)?.setValue(useKtsValue)
                    }
                } catch (e: Exception) {
                    throw IOException("Failed to set template parameters using iterator: ${e.message}", e)
                }

                // CORRECTED: Instantiating TemplateRecipeExecutor from com.itsaky.androidide.utils
                val executor: RecipeExecutor = TemplateRecipeExecutor()

                logViaInterface("Executing project template recipe (ID: ${template.templateId})...\n")

                val result: ProjectTemplateRecipeResult? = template.recipe.execute(executor)

                if (result?.data?.projectDir != null) {
                    val createdProjectDir = result.data.projectDir

                    (fileEditorInterface as? FileEditorActivity)?.onTemplateProjectCreated(createdProjectDir, appName, "N/A")
                    fileEditorInterface.runOnUiThread {
                        onComplete(createdProjectDir)
                    }
                } else {
                    // The TemplateRecipeExecutor you provided doesn't have an 'output' property.
                    // We can log a generic message.
                    val failureMessage = "Template execution failed or did not return project directory. Result: $result."
                    Log.e(TAG, failureMessage)
                    throw IOException(failureMessage)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating project from template", e)
                errorViaInterface("Failed to create project template: ${e.message}", e)
                fileEditorInterface.setState(FileEditorActivity.WorkflowState.ERROR)
            }
        }
    }

    private fun createPackageName(appName: String): String {
        val sanitizedName = appName.filter { it.isLetterOrDigit() }.lowercase(Locale.getDefault())
        return "com.example.${sanitizedName.ifEmpty { "myapp" }}"
    }

    fun listExistingProjectNames(): List<String> {
        return projectsBaseDir.listFiles { file -> file.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
    }
}