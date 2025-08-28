package com.itsaky.androidide.dialogs

import android.content.Context
import io.mockk.Ordering // <-- THIS IS THE MISSING IMPORT
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import io.mockk.just
import io.mockk.justRun
import io.mockk.slot
import io.mockk.verify
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class GeminiWorkflowCoordinatorTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK
    private lateinit var mockGeminiHelper: GeminiHelper
    @MockK(relaxUnitFun = true)
    private lateinit var mockBridge: ViewModelFileEditorBridge
    @MockK
    private lateinit var mockServiceManager: AiServiceManager
    @MockK
    private lateinit var mockFileScanner: ProjectFileScanner
    @MockK
    private lateinit var mockContext: Context

    private lateinit var coordinator: GeminiWorkflowCoordinator

    @Before
    fun setUp() {
        coordinator = GeminiWorkflowCoordinator(
            geminiHelper = mockGeminiHelper,
            directLogAppender = { },
            bridge = mockBridge,
            serviceManager = mockServiceManager,
            fileScanner = mockFileScanner
        )
    }

    @Test
    fun `startModificationFlow should correctly trigger the file selection process`() {
        // --- ARRANGE ---
        val appName = "TestApp"
        val appDescription = "A simple test app"
        val projectDir = File("/fake/path/to/TestApp")
        val fakeFileList = listOf("src/main/App.kt", "build.gradle.kts")

        every { mockGeminiHelper.currentModelIdentifier } returns "gemini-pro"
        every { mockBridge.getContextBridge() } returns mockContext
        every { mockBridge.isModifyingExistingProjectBridge } returns false
        every { mockFileScanner.scanProjectFiles(projectDir) } returns fakeFileList

        // Mock the property setter
        every { mockBridge.currentProjectDirBridge = any() } just Runs

        // Mock the property getter
        every { mockBridge.currentProjectDirBridge } returns projectDir

        justRun { mockServiceManager.startService(any(), any()) }

        val uiBlock = slot<() -> Unit>()
        every { mockBridge.runOnUiThreadBridge(capture(uiBlock)) } answers {
            uiBlock.captured.invoke()
        }

        justRun { mockGeminiHelper.sendApiRequest(any(), any(), any(), any(), any()) }


        // --- ACT ---
        coordinator.startModificationFlow(
            appName = appName,
            appDescription = appDescription,
            projectDir = projectDir
        )


        // --- ASSERT ---
        // Verify that all these calls happened, regardless of their order.
        verify(ordering = Ordering.UNORDERED) {
            mockServiceManager.startService(mockContext, "Generating code for $appName")
            mockBridge.updateStateBridge(AiWorkflowState.SELECTING_FILES)
            mockFileScanner.scanProjectFiles(projectDir)
            mockGeminiHelper.sendApiRequest(any(), any(), any(), any(), eq("application/json"))
            mockBridge.currentProjectDirBridge = projectDir
        }
    }
}