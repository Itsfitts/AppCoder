package com.itsaky.androidide.dialogs

import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

@RunWith(MockitoJUnitRunner::class)
class GeminiWorkflowCoordinatorTest {

    @Mock
    private lateinit var mockGeminiHelper: GeminiHelper

    @Mock
    private lateinit var mockBridge: ViewModelFileEditorBridge

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockServiceManager: AiServiceManager

    @Mock
    private lateinit var mockFileScanner: ProjectFileScanner

    private lateinit var coordinator: GeminiWorkflowCoordinator

    @Before
    fun setUp() {
        coordinator = GeminiWorkflowCoordinator(
            geminiHelper = mockGeminiHelper,
            directLogAppender = { /* Do nothing */ },
            bridge = mockBridge,
            serviceManager = mockServiceManager,
            fileScanner = mockFileScanner
        )
    }

    @Test
    fun `startModificationFlow should update state and trigger file selection`() {
        // --- ARRANGE ---
        val appName = "TestApp"
        val appDescription = "A simple test app"
        val projectDir = File("/fake/path/to/TestApp")
        val expectedServiceMessage = "Generating code for $appName"
        val fakeFileList = listOf("src/main/App.kt", "build.gradle.kts")

        // Stubbing for our dependencies
        whenever(mockGeminiHelper.currentModelIdentifier).thenReturn(GeminiHelper.DEFAULT_GEMINI_MODEL)
        whenever(mockBridge.getContextBridge()).thenReturn(mockContext)
        whenever(mockFileScanner.scanProjectFiles(projectDir)).thenReturn(fakeFileList)
        // By default, the isModifying... property will be false, which is what we want for this test.
        whenever(mockBridge.isModifyingExistingProjectBridge).thenReturn(false)

        // --- THIS IS THE CRITICAL NEW STUB ---
        // "Whenever runOnUiThreadBridge is called with any block of code,
        //  get that block of code and execute it immediately."
        whenever(mockBridge.runOnUiThreadBridge(any())).doAnswer {
            val block = it.getArgument<() -> Unit>(0)
            block()
        }

        // --- ACT ---
        coordinator.startModificationFlow(
            appName = appName,
            appDescription = appDescription,
            projectDir = projectDir,
            autoBuild = false,
            autoRun = false
        )

        // --- ASSERT ---
        verify(mockServiceManager).startService(eq(mockContext), eq(expectedServiceMessage))
        verify(mockBridge).updateStateBridge(AiWorkflowState.SELECTING_FILES)
        verify(mockGeminiHelper).sendApiRequest(
            contents = any(),
            callback = any(),
            modelIdentifierOverride = anyOrNull(),
            responseSchemaJson = anyOrNull(),
            responseMimeTypeOverride = anyOrNull()
        )
    }
}