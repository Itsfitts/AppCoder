package com.itsaky.androidide.dialogs

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Helper class for interacting with the Gemini API.
 */
class GeminiHelper(
    private val apiKeyProvider: () -> String, // Function to securely get the API key
    private val errorHandler: (String) -> Unit,
    private val uiCallback: (block: () -> Unit) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun sendGeminiRequest(contents: List<JSONObject>, callback: (JSONObject) -> Unit) {
        // --- Use the provider function ---
        val apiKey = apiKeyProvider()

        // --- Check ONLY if it's blank ---
        if (apiKey.isBlank()) {
            errorHandler("Gemini API Key is not set.") // Provide specific error
            return
        }
        // --- No comparison to a specific key here ---

        // --- The rest of the function remains the same ---
        val requestJson = JSONObject().apply {
            put("contents", JSONArray(contents))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.5)
                put("maxOutputTokens", 8192)
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

        // Use the apiKey variable fetched from the provider
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro-latest:generateContent?key=$apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                errorHandler("API Network Error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                try {
                    if (!response.isSuccessful || responseBody == null) {
                        errorHandler("API Error: ${response.code} ${response.message} - ${responseBody ?: "No body"}")
                        return
                    }

                    val jsonResponse = JSONObject(responseBody)

                    if (jsonResponse.has("promptFeedback") &&
                        jsonResponse.getJSONObject("promptFeedback").optString("blockReason", null) != null) {
                        val reason = jsonResponse.getJSONObject("promptFeedback").getString("blockReason")
                        val safetyRatings = jsonResponse.getJSONObject("promptFeedback").optJSONArray("safetyRatings")?.toString(2) ?: "N/A"
                        errorHandler("API Response Blocked: $reason. Safety Ratings: $safetyRatings")
                    } else if (!jsonResponse.has("candidates") || jsonResponse.getJSONArray("candidates").length() == 0) {
                        val finishReason = jsonResponse.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason")
                        if (finishReason != "STOP" && finishReason != "MAX_TOKENS") {
                            errorHandler("API Error: No valid candidates found in response. Finish Reason: $finishReason. Response: ${jsonResponse.toString(2)}")
                        } else {
                            val textExists = jsonResponse.toString().contains("\"text\":")
                            if (!textExists) {
                                errorHandler("API Error: Empty response received. Finish Reason: $finishReason.")
                            } else {
                                uiCallback { callback(jsonResponse) }
                            }
                        }
                    } else {
                        uiCallback { callback(jsonResponse) }
                    }
                } catch (e: Exception) {
                    errorHandler("Error processing API response: ${e.message}. Response Body: $responseBody")
                } finally {
                    response.body?.close()
                }
            }
        })
    }

    // --- extractTextFromGeminiResponse, extractJsonArrayFromText, parseFileChanges remain the same ---
    fun extractTextFromGeminiResponse(response: JSONObject): String {
        try {
            val candidates = response.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val content = candidates.getJSONObject(0).optJSONObject("content")
                if (content != null) {
                    val parts = content.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("text")) {
                                return part.getString("text")
                            }
                        }
                    }
                }
            }
            Log.w(FileEditorDialog.TAG, "Could not extract text from Gemini response structure: ${response.toString(2)}")
            return ""
        } catch (e: Exception) {
            Log.e(FileEditorDialog.TAG, "Error extracting text from response", e)
            errorHandler("Error extracting text from response: ${e.message}") // Also notify handler
            return ""
        }
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
        throw IllegalArgumentException("No JSON array found in response text.")
    }

    fun parseFileChanges(text: String, logAppender: (String) -> Unit): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val filePattern = """FILE:\s*([^\n]+)\s*```(?:.*\n)?([\s\S]*?)```""".toRegex()
        val matches = filePattern.findAll(text)

        for (match in matches) {
            val filePath = match.groupValues[1].trim()
            val content = match.groupValues[2].trim()

            if (filePath.isNotEmpty()) {
                result[filePath] = content
            } else {
                logAppender("⚠️ Found code block without valid file path marker.\n")
            }
        }
        if (matches.count() == 0 && text.contains("```")) {
            logAppender("⚠️ AI response contained code blocks but couldn't parse valid FILE: markers.\n")
        } else if (matches.count() == 0 && !text.isBlank()) {
            logAppender("⚠️ AI response did not contain recognizable file modification blocks.\nResponse:\n$text\n")
        }
        return result
    }
}

// --- GeminiConversation remains the same ---
class GeminiConversation {
    // ... (keep existing implementation)
    private val messages = mutableListOf<JSONObject>()

    fun addUserMessage(content: String) {
        messages.add(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", content) })
            })
        })
    }

    fun addModelMessage(rawResponseText: String) {
        if (rawResponseText.isNotBlank()) {
            messages.add(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", rawResponseText) })
                })
            })
        } else {
            Log.w(FileEditorDialog.TAG, "Attempted to add empty model message to conversation.")
        }
    }

    fun getContents(): List<JSONObject> = messages.toList()

    fun clear() {
        messages.clear()
    }
}