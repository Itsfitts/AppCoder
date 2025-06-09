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

    fun createNewProjectFromTemplate(appName: String, onCompleteBackground: (projectDir: File) -> Unit) {
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

                logViaBridge("Package Name: $packageName\n")
                logViaBridge("Save Location: ${projectDir.absolutePath}\n")

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

                val executor: RecipeExecutor = TemplateRecipeExecutor()
                logViaBridge("Executing project template recipe (ID: ${template.templateId})...\n")
                val result: ProjectTemplateRecipeResult? = template.recipe.execute(executor)

                if (result?.data?.projectDir != null) {
                    val createdProjectDir = result.data.projectDir
                    bridge.onTemplateProjectCreatedBridge(createdProjectDir, appName, "N/A")
                    onCompleteBackground(createdProjectDir)
                } else {
                    val failureMessage = "Template execution failed or did not return project directory. Result: $result."
                    Log.e(TAG, failureMessage)
                    throw IOException(failureMessage)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating project from template", e)
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

    fun overwriteProjectWithVersion(baseProjectName: String, versionProjectName: String): Boolean {
        logViaBridge("Setting up workbench: Copying '$versionProjectName' to '$baseProjectName'")
        val baseProjectDir = File(projectsBaseDir, baseProjectName)
        val versionProjectDir = File(projectsBaseDir, versionProjectName)

        if (!baseProjectDir.exists() || !versionProjectDir.exists()) {
            val missingDir = if (!baseProjectDir.exists()) baseProjectName else versionProjectName
            errorViaBridge("Cannot overwrite: Directory '$missingDir' does not exist.", null)
            return false
        }

        try {
            baseProjectDir.deleteRecursively()
            baseProjectDir.mkdirs()
            versionProjectDir.copyRecursively(baseProjectDir, overwrite = true)

            // This is correct: a bookmark MUST be created to signal the intent to branch.
            val sourceVersionFile = File(baseProjectDir, VERSION_SOURCE_FILE)
            sourceVersionFile.writeText(versionProjectName)

            logViaBridge("Workbench setup complete for branching.")
            return true
        } catch (e: Exception) {
            errorViaBridge("Failed to overwrite project with version: ${e.message}", e)
            return false
        }
    }

    fun createVersionedCopy(sourceProjectDir: File, baseProjectName: String): File? {
        logViaBridge("Attempting to create a versioned snapshot for '$baseProjectName'")
        if (!sourceProjectDir.exists()) {
            errorViaBridge("Source project directory does not exist for versioning.", null)
            return null
        }

        try {
            val sourceVersionFile = File(sourceProjectDir, VERSION_SOURCE_FILE)
            val sourceVersionProjectName = if (sourceVersionFile.exists()) sourceVersionFile.readText().trim() else null

            val newVersionName: String

            if (sourceVersionProjectName != null && sourceVersionProjectName.contains("_v")) {
                // BRANCHING LOGIC
                logViaBridge("Branching from source: $sourceVersionProjectName")
                val sourceVersionNumber = sourceVersionProjectName.substringAfterLast("_v")

                val allVersions = findProjectVersions(baseProjectName)

                val childVersions = allVersions.filter { version ->
                    version.startsWith("$sourceVersionNumber.") &&
                            version.split('.').size == sourceVersionNumber.split('.').size + 1
                }

                val newVersionNumber = if (childVersions.isEmpty()) {
                    "$sourceVersionNumber.1"
                } else {
                    val latestChild = childVersions.sortedWith(getVersionComparator()).last()
                    val parts = latestChild.split('.').toMutableList()
                    val lastPart = parts.last().toIntOrNull() ?: 0
                    parts[parts.size - 1] = (lastPart + 1).toString()
                    parts.joinToString(".")
                }

                newVersionName = "${baseProjectName}_v${newVersionNumber}"

            } else {
                // LINEAR PROGRESSION LOGIC
                logViaBridge("Creating next linear version.")
                val allVersions = findProjectVersions(baseProjectName)

                val latestMajorVersion = allVersions
                    .filter { !it.contains('.') }
                    .mapNotNull { it.toIntOrNull() }
                    .maxOrNull() ?: 0

                val newVersionNumber = latestMajorVersion + 1
                newVersionName = "${baseProjectName}_v${newVersionNumber}"
            }

            val newVersionDir = File(projectsBaseDir, newVersionName)
            if (newVersionDir.exists()) {
                errorViaBridge("Version directory '$newVersionName' already exists. Aborting snapshot.", null)
                return null
            }

            logViaBridge("Creating snapshot: '$newVersionName'")
            sourceProjectDir.copyRecursively(newVersionDir, overwrite = true)

            File(newVersionDir, VERSION_SOURCE_FILE).delete()

            // CRITICAL FIX: The bookmark must be cleaned from the workbench to reset its state.
            if (sourceVersionFile.exists()) {
                sourceVersionFile.delete()
                logViaBridge("Cleaned up version source file from workbench.")
            }

            logViaBridge("Successfully created snapshot at '${newVersionDir.absolutePath}'")
            return newVersionDir

        } catch (e: Exception) {
            errorViaBridge("Failed to create versioned copy: ${e.message}", e)
            return null
        }
    }
}