package com.itsaky.androidide.dialogs

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// A completely plain test class.
class GeminiHelperTest {

    private lateinit var geminiHelper: GeminiHelper

    @Before
    fun setUp() {
        geminiHelper = GeminiHelper(
            apiKeyProvider = { "" },
            errorHandlerCallback = { _, _ -> },
            uiThreadExecutor = { block -> block() }
        )
    }

    @Test
    fun `extractTextFromApiResponse handles standard Gemini response`() {
        // --- ARRANGE ---
        // Manually creating JSON objects, step-by-step.
        val part = JSONObject()
        part.put("text", "This is the expected text.")
        val partsArray = JSONArray()
        partsArray.put(part)
        val content = JSONObject()
        content.put("parts", partsArray)
        val candidate = JSONObject()
        candidate.put("content", content)
        val candidatesArray = JSONArray()
        candidatesArray.put(candidate)
        val jsonResponse = JSONObject()
        jsonResponse.put("candidates", candidatesArray)

        // --- ACT ---
        val extractedText = geminiHelper.extractTextFromApiResponse(jsonResponse)

        // --- ASSERT ---
        assertEquals("This is the expected text.", extractedText)
    }

    @Test
    fun `extractTextFromApiResponse handles Gemini response with code fences`() {
        // --- ARRANGE ---
        val part = JSONObject()
        part.put("text", "```json\n{\"key\": \"value\"}\n```")
        val partsArray = JSONArray()
        partsArray.put(part)
        val content = JSONObject()
        content.put("parts", partsArray)
        val candidate = JSONObject()
        candidate.put("content", content)
        val candidatesArray = JSONArray()
        candidatesArray.put(candidate)
        val jsonResponse = JSONObject()
        jsonResponse.put("candidates", candidatesArray)

        // --- ACT ---
        val extractedText = geminiHelper.extractTextFromApiResponse(jsonResponse)

        // --- ASSERT ---
        assertEquals("{\"key\": \"value\"}", extractedText)
    }

    @Test
    fun `extractTextFromApiResponse handles standard OpenAI response`() {
        // --- ARRANGE ---
        val message = JSONObject()
        message.put("role", "assistant")
        message.put("content", "This is the OpenAI response.")
        val choice = JSONObject()
        choice.put("message", message)
        val choicesArray = JSONArray()
        choicesArray.put(choice)
        val jsonResponse = JSONObject()
        jsonResponse.put("choices", choicesArray)

        // --- ACT ---
        val extractedText = geminiHelper.extractTextFromApiResponse(jsonResponse)

        // --- ASSERT ---
        assertEquals("This is the OpenAI response.", extractedText)
    }

    @Test
    fun `extractTextFromApiResponse handles OpenAI response with tool calls`() {
        // --- ARRANGE ---
        val function = JSONObject()
        function.put("name", "some_function")
        function.put("arguments", "{\"arg1\": \"value1\"}")
        val toolCall = JSONObject()
        toolCall.put("function", function)
        val toolCallsArray = JSONArray()
        toolCallsArray.put(toolCall)
        val message = JSONObject()
        message.put("role", "assistant")
        message.put("tool_calls", toolCallsArray)
        val choice = JSONObject()
        choice.put("message", message)
        val choicesArray = JSONArray()
        choicesArray.put(choice)
        val jsonResponse = JSONObject()
        jsonResponse.put("choices", choicesArray)

        // --- ACT ---
        val extractedText = geminiHelper.extractTextFromApiResponse(jsonResponse)

        // --- ASSERT ---
        assertEquals("{\"arg1\": \"value1\"}", extractedText)
    }
}