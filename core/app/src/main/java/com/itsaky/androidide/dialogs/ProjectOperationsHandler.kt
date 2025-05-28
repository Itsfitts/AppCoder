package com.itsaky.androidide.dialogs

import android.util.Log
import com.itsaky.androidide.models.NewProjectDetails
import com.itsaky.androidide.templates.Language
import com.itsaky.androidide.templates.ProjectTemplate
import com.itsaky.androidide.templates.ProjectTemplateRecipeResult
import com.itsaky.androidide.templates.RecipeExecutor
import com.itsaky.androidide.templates.Sdk
import com.itsaky.androidide.templates.Parameter
import com.itsaky.androidide.templates.impl.basicActivity.basicActivityProject
import com.itsaky.androidide.utils.TemplateRecipeExecutor
import com.itsaky.androidide.templates.StringParameter
import com.itsaky.androidide.templates.EnumParameter
import com.itsaky.androidide.templates.BooleanParameter
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.concurrent.thread
import com.itsaky.androidide.dialogs.ViewModelFileEditorBridge // Ensure this import is present
import com.itsaky.androidide.dialogs.AiWorkflowState // Ensure this import is present

class ProjectOperationsHandler(
    private val projectsBaseDir: File,
    private val directLogAppender: (String) -> Unit,
    private val directErrorHandler: (String, Exception?) -> Unit,
    private val bridge: ViewModelFileEditorBridge // Correct: uses ViewModelFileEditorBridge
) {
    companion object {
        private const val TAG = "ProjectOpsHandler"
    }

    // logViaInterface and errorViaInterface now use the bridge
    private fun logViaBridge(message: String) = bridge.appendToLogBridge(message)
    private fun errorViaBridge(message: String, e: Exception?) = bridge.handleErrorBridge(message, e)

    fun projectExists(appName: String): Boolean {
        if (appName.isBlank()) return false
        val projectDir = File(projectsBaseDir, appName)
        return projectDir.exists() && projectDir.isDirectory
    }

    fun createNewProjectFromTemplate(appName: String, onCompleteBackground: (projectDir: File) -> Unit) { // onComplete can be used by ViewModel for background tasks if needed
        logViaBridge("Starting new project template creation for: $appName\n")
        bridge.updateStateBridge(AiWorkflowState.CREATING_PROJECT_TEMPLATE)

        thread { // Perform template creation on a background thread
            try {
                val packageName = createPackageName(appName)
                val projectDir = File(projectsBaseDir, appName)

                if (projectDir.exists()) {
                    errorViaBridge("Project directory '$appName' already exists.", null)
                    // State update will be handled by errorViaBridge -> bridge.updateStateBridge
                    return@thread
                }

                logViaBridge("Package Name: $packageName\n")
                logViaBridge("Save Location: ${projectDir.absolutePath}\n")

                val projectDetails = NewProjectDetails().apply {
                    this.name = appName
                    this.packageName = packageName
                    this.minSdk = 21 // Example, ensure these are correct
                    this.targetSdk = 34 // Example
                    this.language = "kotlin" // Example
                    this.savePath = projectsBaseDir.absolutePath
                }

                val template: ProjectTemplate = basicActivityProject() // Ensure this is valid
                // ... (Parameter setting logic remains the same)
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


                val executor: RecipeExecutor = TemplateRecipeExecutor() // Ensure this is correctly imported and works
                logViaBridge("Executing project template recipe (ID: ${template.templateId})...\n")
                val result: ProjectTemplateRecipeResult? = template.recipe.execute(executor)

                if (result?.data?.projectDir != null) {
                    val createdProjectDir = result.data.projectDir
                    // Use the bridge to notify completion, which runs on UI thread via ViewModel
                    bridge.onTemplateProjectCreatedBridge(createdProjectDir, appName, "N/A" /*appDescription if available*/)
                    onCompleteBackground(createdProjectDir) // Call the background completion too
                } else {
                    val failureMessage = "Template execution failed or did not return project directory. Result: $result."
                    Log.e(TAG, failureMessage) // Internal log
                    throw IOException(failureMessage)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating project from template", e) // Internal log
                errorViaBridge("Failed to create project template: ${e.message}", e)
                // State update to ERROR is handled by errorViaBridge
            }
        }
    }

    private fun createPackageName(appName: String): String {
        val sanitizedName = appName.filter { it.isLetterOrDigit() }.lowercase(Locale.getDefault())
        return "com.example.${sanitizedName.ifEmpty { "myapp" }}"
    }

    fun listExistingProjectNames(): List<String> {
        // This can run on a background thread if called from ViewModel's IO scope
        return projectsBaseDir.listFiles { file -> file.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
    }
}