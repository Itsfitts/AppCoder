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
import java.util.Comparator
import java.util.Locale
import kotlin.concurrent.thread
import com.itsaky.androidide.dialogs.ViewModelFileEditorBridge
import com.itsaky.androidide.dialogs.AiWorkflowState

class ProjectOperationsHandler(
    private val projectsBaseDir: File,
    private val directLogAppender: (String) -> Unit,
    private val directErrorHandler: (String, Exception?) -> Unit,
    private val bridge: ViewModelFileEditorBridge
) {
    companion object {
        private const val TAG = "ProjectOpsHandler"
        private const val VERSION_SOURCE_FILE = ".version_source"
    }

    private fun logViaBridge(message: String) = bridge.appendToLogBridge(message)
    private fun errorViaBridge(message: String, e: Exception?) = bridge.handleErrorBridge(message, e)

    private fun getVersionComparator(): Comparator<String> {
        return Comparator { v1, v2 ->
            val parts1 = v1.split('.').mapNotNull { it.toIntOrNull() }
            val parts2 = v2.split('.').mapNotNull { it.toIntOrNull() }
            val maxParts = maxOf(parts1.size, parts2.size)
            for (i in 0 until maxParts) {
                val p1 = parts1.getOrElse(i) { 0 }
                val p2 = parts2.getOrElse(i) { 0 }
                if (p1 != p2) {
                    return@Comparator p1.compareTo(p2)
                }
            }
            0
        }
    }

    fun projectExists(appName: String): Boolean {
        if (appName.isBlank()) return false
        val projectDir = File(projectsBaseDir, appName)
        return projectDir.exists() && projectDir.isDirectory
    }

    fun createNewProjectFromTemplate(appName: String, appDescription: String) {
        Log.i(TAG, "createNewProjectFromTemplate called for app: $appName")
        logViaBridge("Starting new project template creation for: $appName\n")
        bridge.updateStateBridge(AiWorkflowState.CREATING_PROJECT_TEMPLATE)

        thread {
            try {
                val packageName = createPackageName(appName)
                val projectDir = File(projectsBaseDir, appName)

                if (projectDir.exists()) {
                    errorViaBridge("Project directory '$appName' already exists.", null)
                    return@thread
                }

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
                    if (iterator.hasNext()) { (iterator.next() as? BooleanParameter)?.setValue(langEnum == Language.Kotlin) }
                } catch (e: Exception) {
                    throw IOException("Failed to set template parameters using iterator: ${e.message}", e)
                }


                val executor: RecipeExecutor = TemplateRecipeExecutor()
                val result: ProjectTemplateRecipeResult? = template.recipe.execute(executor)

                if (result?.data?.projectDir != null) {
                    val createdProjectDir = result.data.projectDir
                    Log.i(TAG, "Template created successfully. Calling bridge with description.")
                    bridge.onTemplateProjectCreatedBridge(createdProjectDir, appName, appDescription)
                } else {
                    throw IOException("Template execution failed or did not return project directory.")
                }
            } catch (e: Exception) {
                errorViaBridge("Failed to create project template: ${e.message}", e)
            }
        }
    }

    private fun createPackageName(appName: String): String {
        val sanitizedName = appName.filter { it.isLetterOrDigit() }.lowercase(Locale.getDefault())
        return "com.example.${sanitizedName.ifEmpty { "myapp" }}"
    }

    fun listExistingProjectNames(): List<String> {
        return projectsBaseDir.listFiles { file -> file.isDirectory }
            ?.map { it.name.substringBeforeLast("_v") }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
    }

    fun findProjectVersions(baseAppName: String): List<String> {
        val fullPatternRegex = "^${Regex.escape(baseAppName)}_v(\\d+(?:\\.\\d+)*)$".toRegex()
        val versions = projectsBaseDir.listFiles { dir ->
            dir.isDirectory && fullPatternRegex.matches(dir.name)
        }?.mapNotNull { dir ->
            fullPatternRegex.find(dir.name)?.groupValues?.get(1)
        } ?: emptyList()
        return versions.sortedWith(getVersionComparator())
    }

    fun overwriteProjectWithVersion(baseProjectName: String, versionProjectName: String, shouldBranch: Boolean): Boolean {
        Log.i(TAG, "overwriteProjectWithVersion called. Base: '$baseProjectName', Version: '$versionProjectName', shouldBranch: $shouldBranch")
        val baseProjectDir = File(projectsBaseDir, baseProjectName)
        val versionProjectDir = File(projectsBaseDir, versionProjectName)

        if (!versionProjectDir.exists()) {
            errorViaBridge("Cannot overwrite: Version directory '$versionProjectName' does not exist.", null)
            return false
        }

        try {
            baseProjectDir.deleteRecursively()
            baseProjectDir.mkdirs()
            versionProjectDir.copyRecursively(baseProjectDir, overwrite = true)

            if (shouldBranch) {
                val sourceVersionFile = File(baseProjectDir, VERSION_SOURCE_FILE)
                sourceVersionFile.writeText(versionProjectName)
                Log.i(TAG, "Bookmark file created at '${sourceVersionFile.absolutePath}' to signal a branch.")
            } else {
                Log.i(TAG, "Bookmark file NOT created because shouldBranch is false (this is a reset).")
            }
            return true
        } catch (e: Exception) {
            errorViaBridge("Failed to overwrite project with version: ${e.message}", e)
            return false
        }
    }

    fun createVersionedCopy(sourceProjectDir: File, baseProjectName: String): File? {
        Log.i(TAG, "createVersionedCopy called for source: '${sourceProjectDir.name}'")
        if (!sourceProjectDir.exists()) {
            errorViaBridge("Source project directory does not exist for versioning.", null)
            return null
        }

        try {
            val sourceVersionFile = File(sourceProjectDir, VERSION_SOURCE_FILE)
            val sourceVersionProjectName = if (sourceVersionFile.exists()) sourceVersionFile.readText().trim() else null
            Log.d(TAG, "Bookmark file check in WORKBENCH ('${sourceProjectDir.name}'): Found '${sourceVersionProjectName ?: "null"}'")

            val newVersionName: String

            if (sourceVersionProjectName != null) {
                // BRANCHING LOGIC
                Log.i(TAG, "Entering BRANCHING logic path.")
                val sourceVersionNumber = sourceVersionProjectName.substringAfterLast("_v")
                val allVersions = findProjectVersions(baseProjectName)
                val childVersions = allVersions.filter { v -> v.startsWith("$sourceVersionNumber.") && v.split('.').size == sourceVersionNumber.split('.').size + 1 }
                Log.d(TAG, "Source: '$sourceVersionNumber', Found child versions: $childVersions")

                val newVersionNumber = if (childVersions.isEmpty()) {
                    "$sourceVersionNumber.1"
                } else {
                    val latestChild = childVersions.sortedWith(getVersionComparator()).last()
                    val parts = latestChild.split('.').toMutableList()
                    parts[parts.size - 1] = ((parts.last().toIntOrNull() ?: 0) + 1).toString()
                    parts.joinToString(".")
                }
                newVersionName = "${baseProjectName}_v${newVersionNumber}"
                Log.i(TAG, "Calculated new branch version name: '$newVersionName'")
            } else {
                // LINEAR PROGRESSION LOGIC
                Log.i(TAG, "Entering LINEAR logic path.")
                val allVersions = findProjectVersions(baseProjectName)
                val latestMajorVersion = allVersions.filter { !it.contains('.') }.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0
                val newVersionNumber = latestMajorVersion + 1
                newVersionName = "${baseProjectName}_v$newVersionNumber"
                Log.i(TAG, "Calculated new linear version name: '$newVersionName'")
            }

            val newVersionDir = File(projectsBaseDir, newVersionName)
            if (newVersionDir.exists()) {
                errorViaBridge("Version directory '$newVersionName' already exists. Aborting snapshot.", null)
                return null
            }

            sourceProjectDir.copyRecursively(newVersionDir, overwrite = true)
            Log.d(TAG, "Copied source to new snapshot directory: '${newVersionDir.name}'")

            // --- CRITICAL FIX: Part 1 ---
            // The bookmark should be deleted from the NEW SNAPSHOT as it represents a clean state.
            File(newVersionDir, VERSION_SOURCE_FILE).delete()
            Log.d(TAG, "Deleted bookmark (if any) from NEW snapshot ('${newVersionDir.name}').")


            // --- CRITICAL FIX: Part 2 ---
            // The bookmark MUST also be deleted from the WORKBENCH (sourceProjectDir)
            // after a successful snapshot. This resets the workbench for the next linear progression.
            if (sourceVersionFile.exists()) {
                sourceVersionFile.delete()
                Log.i(TAG, "CRITICAL_CLEANUP: Deleted bookmark from WORKBENCH ('${sourceProjectDir.name}') to reset state.")
            } else {
                Log.d(TAG, "No bookmark file found in WORKBENCH ('${sourceProjectDir.name}') to delete (normal for linear progression).")
            }

            logViaBridge("Successfully created snapshot at '${newVersionDir.absolutePath}'")
            return newVersionDir

        } catch (e: Exception) {
            errorViaBridge("Failed to create versioned copy: ${e.message}", e)
            return null
        }
    }
}