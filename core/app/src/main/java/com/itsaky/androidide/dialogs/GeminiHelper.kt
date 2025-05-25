package com.itsaky.androidide.dialogs

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException // Added for parsing DELETE_FILES
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

// --- Data Class for File Modifications ---
data class FileModifications(
    val filesToWrite: Map<String, String>, // Key: relative path, Value: content
    val filesToDelete: List<String>       // List of relative paths to delete
)

// Define a default model - THIS IS THE PRIMARY SOURCE FOR THE DEFAULT
// UPDATED TO MATCH YOUR INTENDED DEFAULT FROM THE DIALOG'S DISPLAY NAME
const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash-preview-05-20"

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

    var currentModelIdentifier: String = DEFAULT_GEMINI_MODEL
        private set

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

        Log.d("GeminiHelper", "Sending request to model: $effectiveModelIdentifier with body: ${requestJson.toString(2)}")

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
                            val errorJson = JSONObject(responseBody)
                            errorBodyDetails = errorJson.getJSONObject("error").getString("message")
                        } catch (jsonEx: Exception) { /* Ignore */ }
                        errorHandler("API Error (${effectiveModelIdentifier} - Code: ${response.code}): $errorBodyDetails", null)
                        return
                    }

                    val jsonResponse = JSONObject(responseBody)

                    if (jsonResponse.has("promptFeedback") &&
                        jsonResponse.getJSONObject("promptFeedback").optString("blockReason", null) != null) {
                        val reason = jsonResponse.getJSONObject("promptFeedback").getString("blockReason")
                        val safetyRatings = jsonResponse.getJSONObject("promptFeedback").optJSONArray("safetyRatings")?.toString(2) ?: "N/A"
                        errorHandler("API Response Blocked (${effectiveModelIdentifier}): $reason. Safety Ratings: $safetyRatings", null)
                        return
                    }

                    if (!jsonResponse.has("candidates") || jsonResponse.getJSONArray("candidates").length() == 0) {
                        val finishReasonFromPromptFeedback = jsonResponse.optJSONObject("promptFeedback")?.optString("blockReason")
                        val finishReasonFromCandidate = jsonResponse.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason")
                        val derivedFinishReason = finishReasonFromPromptFeedback ?: finishReasonFromCandidate ?: "UNKNOWN_REASON"
                        val textExistsInResponse = extractTextFromGeminiResponse(jsonResponse).isNotBlank()

                        if (derivedFinishReason != "STOP" && derivedFinishReason != "MAX_TOKENS" && !textExistsInResponse) {
                            errorHandler("API Error (${effectiveModelIdentifier}): No valid candidates or text. Finish Reason: $derivedFinishReason. See Logcat for full response.", null)
                            Log.w("GeminiHelper", "Problematic Response (No Candidates/Text, Finish Reason: $derivedFinishReason) (${effectiveModelIdentifier}): ${jsonResponse.toString(2)}")
                        } else if (!textExistsInResponse) {
                            errorHandler("API Error (${effectiveModelIdentifier}): Response has no text part. Finish Reason: $derivedFinishReason. See Logcat for full response.", null)
                            Log.w("GeminiHelper", "Empty Response Text (Finish Reason: $derivedFinishReason) (${effectiveModelIdentifier}): ${jsonResponse.toString(2)}")
                        } else {
                            uiCallback { callback(jsonResponse) }
                        }
                        return
                    }
                    uiCallback { callback(jsonResponse) }
                } catch (e: Exception) {
                    errorHandler("Error processing API response (${effectiveModelIdentifier}): ${e.message}. See Logcat for response body.", e)
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
                val firstCandidate = candidates.getJSONObject(0)
                val finishReason = firstCandidate.optString("finishReason")
                if (finishReason != null && finishReason != "STOP" && finishReason != "MAX_TOKENS") {
                    Log.w("GeminiHelper", "Candidate finishReason is '$finishReason'. Text might be incomplete or absent.")
                }

                val content = firstCandidate.optJSONObject("content")
                if (content != null) {
                    val parts = content.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val textBuilder = StringBuilder()
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("text")) {
                                textBuilder.append(part.getString("text"))
                            }
                        }
                        if (textBuilder.isNotEmpty()) {
                            return textBuilder.toString()
                        }
                    }
                }
            }
            Log.w("GeminiHelper", "Could not extract text from Gemini response structure. Response: ${response.toString(2)}")
            return ""
        } catch (e: Exception) {
            Log.e("GeminiHelper", "Error extracting text from response", e)
            errorHandler("Error extracting text from response: ${e.message}", e)
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

    fun parseFileChanges(responseText: String, logAppender: (String) -> Unit): FileModifications {
        val filesToWrite = mutableMapOf<String, String>()
        val filesToDelete = mutableListOf<String>()

        val fileBlockRegex = Regex(
            """^FILE:\s*(.+?)\s*\n```(?:[a-zA-Z0-9_.-]*\n)?([\s\S]*?)\n```""",
            setOf(RegexOption.MULTILINE)
        )
        fileBlockRegex.findAll(responseText).forEach { matchResult ->
            // CORRECTED: Access specific group values (index 1 for path, 2 for content)
            val path = matchResult.groupValues[1].trim()
            val content = matchResult.groupValues[2].trim()
            if (path.isNotEmpty()) {
                filesToWrite[path] = content
            } else {
                logAppender("⚠️ Found a FILE block with an empty path in AI response.\n")
            }
        }

        val deleteBlockRegex = Regex(
            """^DELETE_FILES:\s*(\[[\s\S]*?\])""",
            setOf(RegexOption.MULTILINE)
        )
        deleteBlockRegex.find(responseText)?.let { matchResult ->
            // CORRECTED: Access specific group value (index 1 for the JSON array string)
            val jsonArrayString = matchResult.groupValues[1].trim()
            try {
                // This should now work as jsonArrayString is a proper String
                val jsonArray = JSONArray(jsonArrayString)
                for (i in 0 until jsonArray.length()) {
                    val filePathToDelete = jsonArray.getString(i)
                    if (filePathToDelete.isNotBlank()) {
                        filesToDelete.add(filePathToDelete)
                    }
                }
            } catch (e: JSONException) {
                logAppender("⚠️ Failed to parse DELETE_FILES JSON array: \"$jsonArrayString\". Error: ${e.message}\n")
            }
        }

        val foundFileBlocks = filesToWrite.isNotEmpty()
        val foundDeleteDirective = responseText.contains("DELETE_FILES:")
        val successfullyParsedDeletions = filesToDelete.isNotEmpty()

        if (!foundFileBlocks && !foundDeleteDirective) {
            if (responseText.isNotBlank()) {
                logAppender("AI response did not contain 'FILE:' or 'DELETE_FILES:' keywords. Full response logged for review if issues persist.\n")
            }
        } else {
            if (responseText.contains("FILE:") && !foundFileBlocks) {
                logAppender("⚠️ AI response contained 'FILE:' keyword but no valid file blocks were parsed. Check AI response format and regex.\n")
            }
            if (foundDeleteDirective && !successfullyParsedDeletions) {
                logAppender("ℹ️ 'DELETE_FILES:' directive found, but no files were parsed for deletion (array might be empty or malformed).\n")
            }
        }
        return FileModifications(filesToWrite, filesToDelete)
    }
}