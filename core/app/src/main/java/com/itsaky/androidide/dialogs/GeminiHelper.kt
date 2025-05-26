package com.itsaky.androidide.dialogs // Assuming this package

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

// --- DATA CLASS DEFINED ONCE, AT TOP-LEVEL ---
data class FileModifications(
    val filesToWrite: Map<String, String>,
    val filesToDelete: List<String>,
    val conclusion: String?
)

// --- TOP-LEVEL CONSTANT DEFINED ONCE ---
const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash-preview-05-20" // Your desired default

class GeminiHelper(
    private val apiKeyProvider: () -> String,
    private val errorHandler: (String, Exception?) -> Unit,
    private val uiCallback: (block: () -> Unit) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val defaultThinkingBudget = 24576

    var currentModelIdentifier: String = DEFAULT_GEMINI_MODEL // Uses top-level const
        private set

    fun setModel(modelId: String) {
        if (modelId.isNotBlank()) {
            currentModelIdentifier = modelId
            Log.i("GeminiHelper", "Gemini model set to: $currentModelIdentifier")
        } else {
            Log.w("GeminiHelper", "Attempted to set a blank model ID. Using default: $DEFAULT_GEMINI_MODEL")
            currentModelIdentifier = DEFAULT_GEMINI_MODEL // Uses top-level const
        }
    }

    fun sendGeminiRequest(
        contents: List<JSONObject>,
        callback: (JSONObject) -> Unit,
        modelIdentifierOverride: String? = null
    ) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            errorHandler("Gemini API Key is not set.", null)
            return
        }
        val effectiveModelIdentifier = modelIdentifierOverride ?: currentModelIdentifier
        if (effectiveModelIdentifier.isBlank()){
            errorHandler("Gemini Model Identifier is critically not set.", null)
            return
        }

        val requestJson = JSONObject().apply {
            put("contents", JSONArray(contents))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.5)
                put("maxOutputTokens", 10192)
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingBudget", defaultThinkingBudget)
                })
            })
            put("safetySettings", JSONArray().apply {
                put(JSONObject().apply { put("category", "HARM_CATEGORY_HARASSMENT"); put("threshold", "BLOCK_MEDIUM_AND_ABOVE") })
                put(JSONObject().apply { put("category", "HARM_CATEGORY_HATE_SPEECH"); put("threshold", "BLOCK_MEDIUM_AND_ABOVE") })
                put(JSONObject().apply { put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT"); put("threshold", "BLOCK_MEDIUM_AND_ABOVE") })
                put(JSONObject().apply { put("category", "HARM_CATEGORY_DANGEROUS_CONTENT"); put("threshold", "BLOCK_MEDIUM_AND_ABOVE") })
            })
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = requestJson.toString().toRequestBody(mediaType)
        Log.d("GeminiHelper", "Sending request to model: $effectiveModelIdentifier")

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${effectiveModelIdentifier}:generateContent?key=$apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                errorHandler("API Network Error (${effectiveModelIdentifier}): ${e.message}", e)
            }
            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                try {
                    if (!response.isSuccessful || responseBody == null) {
                        var errorBodyDetails = responseBody ?: "No error body"
                        try {
                            val errorJson = JSONObject(responseBody); errorBodyDetails = errorJson.getJSONObject("error").getString("message")
                        } catch (jsonEx: Exception) { /* Ignore */ }
                        errorHandler("API Error (${effectiveModelIdentifier} - Code: ${response.code}): $errorBodyDetails", null)
                        return
                    }
                    val jsonResponse = JSONObject(responseBody)
                    if (jsonResponse.has("promptFeedback") && jsonResponse.getJSONObject("promptFeedback").optString("blockReason", null) != null) {
                        val reason = jsonResponse.getJSONObject("promptFeedback").getString("blockReason")
                        errorHandler("API Response Blocked (${effectiveModelIdentifier}): $reason.", null)
                        return
                    }
                    if (!jsonResponse.has("candidates") || jsonResponse.getJSONArray("candidates").length() == 0) {
                        val finishReason = jsonResponse.optJSONObject("promptFeedback")?.optString("blockReason") ?: jsonResponse.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason") ?: "UNKNOWN"
                        if (extractTextFromGeminiResponse(jsonResponse).isBlank()) {
                            errorHandler("API Error (${effectiveModelIdentifier}): No text in response. Finish: $finishReason.", null)
                            Log.w("GeminiHelper", "Empty/Problematic Response: ${jsonResponse.toString(2)}")
                            return
                        }
                    }
                    uiCallback { callback(jsonResponse) }
                } catch (e: Exception) {
                    errorHandler("Error processing API response (${effectiveModelIdentifier}): ${e.message}", e)
                    Log.e("GeminiHelper", "Response Body on error: $responseBody", e)
                } finally {
                    response.body?.close()
                }
            }
        })
    }

    fun extractTextFromGeminiResponse(response: JSONObject): String {
        try {
            val candidates = response.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val content = candidates.getJSONObject(0).optJSONObject("content")
                if (content != null) {
                    val parts = content.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val textBuilder = StringBuilder()
                        for (i in 0 until parts.length()) {
                            parts.optJSONObject(i)?.optString("text")?.let { textBuilder.append(it) }
                        }
                        return textBuilder.toString()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GeminiHelper", "Error extracting text from response", e)
            errorHandler("Error extracting text: ${e.message}", e)
        }
        Log.w("GeminiHelper", "Could not extract text from Gemini response: ${response.toString(2)}")
        return ""
    }

    fun extractJsonArrayFromText(text: String): String {
        val startIndex = text.indexOf('[')
        val endIndex = text.lastIndexOf(']')
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return text.substring(startIndex, endIndex + 1)
        }
        val cleanedText = text.replace("```json", "").replace("```", "").trim()
        if (cleanedText.startsWith("[") && cleanedText.endsWith("]")) {
            return cleanedText
        }
        Log.w("GeminiHelper", "No JSON array structure found in text for file selection.")
        return "[]" // Return empty array string for JSONArray constructor to handle
    }

    fun parseFileChanges(responseText: String, logAppender: (String) -> Unit): FileModifications {
        val filesToWrite = mutableMapOf<String, String>()
        val filesToDelete = mutableListOf<String>()
        var conclusionText: String? = null

        val fileBlockRegex = Regex(
            """^FILE:\s*(.+?)\s*\n```(?:[a-zA-Z0-9_.-]*\n)?([\s\S]*?)\n```""",
            setOf(RegexOption.MULTILINE)
        )
        fileBlockRegex.findAll(responseText).forEach { matchResult ->
            if (matchResult.groupValues.size > 2) {
                val path = matchResult.groupValues[1].trim()
                val content = matchResult.groupValues[2].trim()
                if (path.isNotEmpty()) {
                    filesToWrite[path] = content
                } else {
                    logAppender("⚠️ Found a FILE block with an empty path in AI response.\n")
                }
            } else {
                logAppender("⚠️ Malformed FILE block in AI response: ${matchResult.value}\n")
            }
        }

        val deleteBlockRegex = Regex(
            """^DELETE_FILES:\s*(\[[\s\S]*?\])""",
            setOf(RegexOption.MULTILINE)
        )
        deleteBlockRegex.find(responseText)?.let { matchResult ->
            if (matchResult.groupValues.size > 1) {
                val jsonArrayString = matchResult.groupValues[1].trim()
                try {
                    val jsonArray = JSONArray(jsonArrayString)
                    for (i in 0 until jsonArray.length()) {
                        jsonArray.optString(i)?.takeIf { it.isNotBlank() }?.let { filesToDelete.add(it) }
                    }
                } catch (e: JSONException) {
                    logAppender("⚠️ Failed to parse DELETE_FILES JSON array: \"$jsonArrayString\". Error: ${e.message}\n")
                }
            } else {
                logAppender("⚠️ Malformed DELETE_FILES block: ${matchResult.value}\n")
            }
        }

        val conclusionBlockRegex = Regex(
            """^CONCLUSION:\s*\n```\n([\s\S]*?)\n```""",
            setOf(RegexOption.MULTILINE)
        )
        conclusionBlockRegex.find(responseText)?.let { matchResult ->
            if (matchResult.groupValues.size > 1) {
                conclusionText = matchResult.groupValues[1].trim()
            } else {
                logAppender("⚠️ Malformed CONCLUSION block: ${matchResult.value}\n")
            }
        }
        return FileModifications(filesToWrite, filesToDelete, conclusionText)
    }
}