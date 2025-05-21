package com.itsaky.androidide.dialogs

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

// Define a default model - THIS IS THE PRIMARY SOURCE FOR THE DEFAULT
const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash-preview-05-20" // << YOUR DESIRED DEFAULT

class GeminiHelper(
    private val apiKeyProvider: () -> String,
    private val errorHandler: (String) -> Unit,
    private val uiCallback: (block: () -> Unit) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val defaultThinkingBudget = 0

    // This will be initialized with DEFAULT_GEMINI_MODEL by default
    var currentModelIdentifier: String = DEFAULT_GEMINI_MODEL
        private set // Keep setter private, use setModel()

    fun setModel(modelId: String) {
        if (modelId.isNotBlank()) {
            currentModelIdentifier = modelId
            Log.i("GeminiHelper", "Gemini model set to: $currentModelIdentifier")
        } else {
            Log.w("GeminiHelper", "Attempted to set a blank model ID. Using default: $DEFAULT_GEMINI_MODEL")
            currentModelIdentifier = DEFAULT_GEMINI_MODEL
        }
    }

    fun sendGeminiRequest(
        contents: List<JSONObject>,
        callback: (JSONObject) -> Unit,
        modelIdentifierOverride: String? = null
    ) {
        val apiKey = apiKeyProvider()

        if (apiKey.isBlank()) {
            errorHandler("Gemini API Key is not set.")
            return
        }

        val effectiveModelIdentifier = modelIdentifierOverride ?: currentModelIdentifier
        if (effectiveModelIdentifier.isBlank()){
            errorHandler("Gemini Model Identifier is critically not set.")
            return
        }

        val requestJson = JSONObject().apply {
            put("contents", JSONArray(contents))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.5)
                put("maxOutputTokens", 8192)
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
                errorHandler("API Network Error (${effectiveModelIdentifier}): ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                try {
                    if (!response.isSuccessful || responseBody == null) {
                        var errorBodyDetails = responseBody ?: "No error body"
                        try {
                            val errorJson = JSONObject(responseBody)
                            errorBodyDetails = errorJson.getJSONObject("error").getString("message")
                        } catch (jsonEx: Exception) { /* Ignore */ }
                        errorHandler("API Error (${effectiveModelIdentifier} - Code: ${response.code}): $errorBodyDetails")
                        return
                    }

                    val jsonResponse = JSONObject(responseBody)

                    if (jsonResponse.has("promptFeedback") &&
                        jsonResponse.getJSONObject("promptFeedback").optString("blockReason", null) != null) {
                        val reason = jsonResponse.getJSONObject("promptFeedback").getString("blockReason")
                        val safetyRatings = jsonResponse.getJSONObject("promptFeedback").optJSONArray("safetyRatings")?.toString(2) ?: "N/A"
                        errorHandler("API Response Blocked (${effectiveModelIdentifier}): $reason. Safety Ratings: $safetyRatings")
                    } else if (!jsonResponse.has("candidates") || jsonResponse.getJSONArray("candidates").length() == 0) {
                        val finishReasonFromPromptFeedback = jsonResponse.optJSONObject("promptFeedback")?.optString("blockReason")
                        val finishReasonFromCandidate = jsonResponse.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason")
                        val finishReason = finishReasonFromPromptFeedback ?: finishReasonFromCandidate
                        val textExistsInResponse = responseBody.contains("\"text\"")

                        if (finishReason != null && finishReason != "STOP" && finishReason != "MAX_TOKENS" && !textExistsInResponse) {
                            errorHandler("API Error (${effectiveModelIdentifier}): No valid candidates or text. Finish: $finishReason. See Logcat for full response.")
                            Log.w("GeminiHelper", "Problematic Response (${effectiveModelIdentifier}): ${jsonResponse.toString(2)}")
                        } else if (!textExistsInResponse) {
                            errorHandler("API Error (${effectiveModelIdentifier}): Empty response or no text part. Finish: $finishReason. See Logcat for full response.")
                            Log.w("GeminiHelper", "Empty Response Text (${effectiveModelIdentifier}): ${jsonResponse.toString(2)}")
                        } else {
                            uiCallback { callback(jsonResponse) }
                        }
                    } else {
                        uiCallback { callback(jsonResponse) }
                    }
                } catch (e: Exception) {
                    errorHandler("Error processing API response (${effectiveModelIdentifier}): ${e.message}. See Logcat for response body.")
                    Log.e("GeminiHelper", "Response Body on processing error: $responseBody", e)
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
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("text")) {
                                return part.getString("text")
                            }
                        }
                    }
                }
            }
            Log.w("GeminiHelper", "Could not extract text from Gemini response structure: ${response.toString(2)}")
            return ""
        } catch (e: Exception) {
            Log.e("GeminiHelper", "Error extracting text from response", e)
            errorHandler("Error extracting text from response: ${e.message}")
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
            // logAppender("⚠️ AI response did not contain recognizable file modification blocks.\nResponse:\n$text\n")
        }
        return result
    }
    // GeminiConversation class has been moved to its own file or outside this class
}