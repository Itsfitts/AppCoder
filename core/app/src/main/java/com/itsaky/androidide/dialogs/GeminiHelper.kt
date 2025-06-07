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

// DATA CLASSES (AiFileInstruction, AiStructuredResponse, AiMinimalFilesResponse, AiSummaryResponse)
data class AiFileInstruction(
    val filePath: String,
    val fileContent: String
)

data class AiStructuredResponse(
    val filesToWrite: List<AiFileInstruction>? = null,
    val filesToDelete: List<String>? = null,
    val conclusion: String? = null
)

data class AiMinimalFilesResponse(
    val filesToWrite: List<AiFileInstruction>? = null
)

data class AiSummaryResponse(
    val summary: String?
)
// --- END OF DATA CLASSES ---

// --- ORIGINAL DATA CLASS (used as a common structure after parsing) ---
data class FileModifications(
    val filesToWrite: Map<String, String>,
    val filesToDelete: List<String>,
    val conclusion: String?
)



class GeminiHelper(
    private val apiKeyProvider: () -> String,
    private val errorHandlerCallback: (String, Exception?) -> Unit, // For ViewModel
    private val uiThreadExecutor: (block: () -> Unit) -> Unit      // For ViewModel
) {

    // --- TOP-LEVEL CONSTANT DEFINED ONCE ---
    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash-preview-05-20"
    }

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

    // --- Schema Generation Methods ---
    private fun generateResponseSchemaForFileModifications(): String {
        return JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("filesToWrite", JSONObject().apply {
                    put("type", "ARRAY")
                    put("nullable", true)
                    put("items", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("filePath", JSONObject().apply { put("type", "STRING") })
                            put("fileContent", JSONObject().apply { put("type", "STRING") })
                        })
                        put("required", JSONArray().put("filePath").put("fileContent"))
                    })
                })
                put("filesToDelete", JSONObject().apply {
                    put("type", "ARRAY")
                    put("nullable", true)
                    put("items", JSONObject().apply { put("type", "STRING") })
                })
                put("conclusion", JSONObject().apply {
                    put("type", "STRING")
                    put("nullable", true)
                })
            })
        }.toString()
    }

    internal fun getFileModificationsSchema(): String = generateResponseSchemaForFileModifications()

    internal fun getMinimalFilesSchema(): String {
        return JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("filesToWrite", JSONObject().apply {
                    put("type", "ARRAY")
                    put("nullable", true)
                    put("items", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("filePath", JSONObject().apply { put("type", "STRING") })
                            put("fileContent", JSONObject().apply { put("type", "STRING") })
                        })
                        put("required", JSONArray().put("filePath").put("fileContent"))
                    })
                })
            })
        }.toString()
    }

    internal fun getSummaryOnlySchema(): String {
        return JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("summary", JSONObject().apply {
                    put("type", "STRING")
                    put("description", "A concise summary of the provided code changes.")
                })
            })
            put("required", JSONArray().put("summary"))
        }.toString()
    }
    // --- End of Schema Generation Methods ---


    fun sendGeminiRequest(
        contents: List<JSONObject>,
        callback: (JSONObject) -> Unit,
        modelIdentifierOverride: String? = null,
        responseSchemaJson: String? = null,
        responseMimeTypeOverride: String? = "application/json"
    ) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            errorHandlerCallback("Gemini API Key is not set.", null)
            return
        }
        val effectiveModelIdentifier = modelIdentifierOverride ?: currentModelIdentifier
        if (effectiveModelIdentifier.isBlank()){
            errorHandlerCallback("Gemini Model Identifier is critically not set.", null)
            return
        }

        val generationConfigObject = JSONObject().apply {
            put("temperature", 1)
            put("maxOutputTokens", 200000)
            put("topP", 0.95)
            put("topK", 40)

            if (responseSchemaJson != null) {
                put("response_mime_type", responseMimeTypeOverride ?: "application/json")
                try {
                    put("response_schema", JSONObject(responseSchemaJson))
                } catch (e: JSONException) {
                    errorHandlerCallback("Invalid response_schema JSON: ${e.message}", e)
                    return
                }
            }
        }
        if (effectiveModelIdentifier.startsWith("gemini-2")) {
            generationConfigObject.put("thinkingConfig", JSONObject().apply {
                put("thinkingBudget", defaultThinkingBudget)
            })
        }

        val requestJson = JSONObject().apply {
            put("contents", JSONArray(contents))
            put("generationConfig", generationConfigObject)
            put("safetySettings", JSONArray().apply {
                put(JSONObject().apply { put("category", "HARM_CATEGORY_HARASSMENT"); put("threshold", "BLOCK_MEDIUM_AND_ABOVE") })
                put(JSONObject().apply { put("category", "HARM_CATEGORY_HATE_SPEECH"); put("threshold", "BLOCK_MEDIUM_AND_ABOVE") })
                put(JSONObject().apply { put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT"); put("threshold", "BLOCK_MEDIUM_AND_ABOVE") })
                put(JSONObject().apply { put("category", "HARM_CATEGORY_DANGEROUS_CONTENT"); put("threshold", "BLOCK_MEDIUM_AND_ABOVE") })
            })
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = requestJson.toString().toRequestBody(mediaType)
        Log.d("GeminiHelper", "Sending request to model: $effectiveModelIdentifier with schema: ${responseSchemaJson != null}")

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${effectiveModelIdentifier}:generateContent?key=$apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                errorHandlerCallback("API Network Error (${effectiveModelIdentifier}): ${e.message}", e)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                try {
                    if (!response.isSuccessful || responseBody == null) {
                        var errorBodyDetails = responseBody ?: "No error body"
                        try {
                            val errorJson = JSONObject(responseBody); errorBodyDetails = errorJson.getJSONObject("error").getString("message")
                        } catch (jsonEx: Exception) { /* Ignore parsing error of error body */ }
                        errorHandlerCallback("API Error (${effectiveModelIdentifier} - Code: ${response.code}): $errorBodyDetails\nResponse Body: $responseBody", null)
                        return
                    }
                    val jsonResponse = JSONObject(responseBody)
                    if (jsonResponse.has("promptFeedback") && jsonResponse.getJSONObject("promptFeedback").optString("blockReason", null) != null) {
                        val reason = jsonResponse.getJSONObject("promptFeedback").getString("blockReason")
                        errorHandlerCallback("API Response Blocked (${effectiveModelIdentifier}): $reason.", null)
                        return
                    }
                    if (!jsonResponse.has("candidates") || jsonResponse.getJSONArray("candidates").length() == 0) {
                        val finishReason = jsonResponse.optJSONObject("promptFeedback")?.optString("blockReason")
                            ?: jsonResponse.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason")
                            ?: "UNKNOWN_REASON_NO_CANDIDATES"
                        errorHandlerCallback("API Error (${effectiveModelIdentifier}): No candidates in response. Finish reason: $finishReason.", null)
                        Log.w("GeminiHelper", "Empty/Problematic Response (No Candidates): ${jsonResponse.toString(2)}")
                        return
                    }
                    val firstCandidate = jsonResponse.getJSONArray("candidates").optJSONObject(0)
                    if (firstCandidate == null || !firstCandidate.has("content")) {
                        errorHandlerCallback("API Error (${effectiveModelIdentifier}): First candidate has no content.", null)
                        Log.w("GeminiHelper", "Problematic Response (No Content in Candidate): ${jsonResponse.toString(2)}")
                        return
                    }
                    val content = firstCandidate.getJSONObject("content")
                    if (!content.has("parts") || content.getJSONArray("parts").length() == 0) {
                        errorHandlerCallback("API Error (${effectiveModelIdentifier}): No parts in content.", null)
                        Log.w("GeminiHelper", "Problematic Response (No Parts in Content): ${jsonResponse.toString(2)}")
                        return
                    }
                    if (responseSchemaJson == null && extractTextFromGeminiResponse(jsonResponse).isBlank()) {
                        val finishReasonFromCandidate = firstCandidate.optString("finishReason", "UNKNOWN_FINISH_REASON_IN_CANDIDATE")
                        errorHandlerCallback("API Error (${effectiveModelIdentifier}): No text in response and no schema used. Finish Reason: $finishReasonFromCandidate", null)
                        Log.w("GeminiHelper", "Empty Text Response (No Schema): ${jsonResponse.toString(2)}")
                        return
                    }

                    uiThreadExecutor { callback(jsonResponse) }
                } catch (e: Exception) {
                    errorHandlerCallback("Error processing API response (${effectiveModelIdentifier}): ${e.message}", e)
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
                        val firstPart = parts.optJSONObject(0)
                        if (firstPart != null) {
                            return if (firstPart.has("text")) firstPart.getString("text") else firstPart.toString()
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.e("GeminiHelper", "Error extracting content string from response", e) }
        Log.w("GeminiHelper", "Could not extract primary content string from Gemini response: ${response.toString(2)}")
        return ""
    }

    // --- JSON Parsers ---
    private fun parseAiStructuredResponse(jsonText: String): AiStructuredResponse {
        val filesToWrite = mutableListOf<AiFileInstruction>()
        val filesToDelete = mutableListOf<String>()
        var conclusion: String? = null
        try {
            val root = JSONObject(jsonText)
            root.optJSONArray("filesToWrite")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { obj ->
                        val path = obj.optString("filePath", null)
                        val content = obj.optString("fileContent", null)
                        if (path != null && content != null && path.isNotBlank()) {
                            filesToWrite.add(AiFileInstruction(path, content))
                        }
                    }
                }
            }
            root.optJSONArray("filesToDelete")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i)?.takeIf { it.isNotBlank() }?.let { filesToDelete.add(it) }
                }
            }
            conclusion = root.optString("conclusion", null)?.takeIf { it.isNotBlank() }
        } catch (e: JSONException) {
            Log.e("GeminiHelper", "Error parsing AiStructuredResponse JSON: '$jsonText'. Error: ${e.message}", e)
        }
        return AiStructuredResponse(
            filesToWrite = if (filesToWrite.isEmpty()) null else filesToWrite,
            filesToDelete = if (filesToDelete.isEmpty()) null else filesToDelete,
            conclusion = conclusion
        )
    }

    fun parseMinimalFilesResponse(jsonText: String): Map<String, String>? {
        val filesMap = mutableMapOf<String, String>()
        try {
            val root = JSONObject(jsonText)
            root.optJSONArray("filesToWrite")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { obj ->
                        val path = obj.optString("filePath", null)
                        val content = obj.optString("fileContent", null)
                        if (path != null && content != null && path.isNotBlank()) { filesMap[path] = content }
                    }
                }
            }
        } catch (e: JSONException) {
            Log.e("GeminiHelper", "Error parsing AiMinimalFilesResponse JSON: '$jsonText'. Error: ${e.message}", e)
            return null
        }
        return if (filesMap.isEmpty()) null else filesMap
    }

    fun parseSummaryResponse(jsonText: String): String? {
        try {
            val root = JSONObject(jsonText)
            return root.optString("summary", null)?.takeIf { it.isNotBlank() }
        } catch (e: JSONException) {
            Log.e("GeminiHelper", "Error parsing AiSummaryResponse JSON: '$jsonText'. Error: ${e.message}", e)
            return null
        }
    }
    // --- End of JSON Parsers ---

    // --- Converters ---
    fun convertAiResponseToFileModifications(aiResponse: AiStructuredResponse): FileModifications {
        val filesToWriteMap = aiResponse.filesToWrite?.associate { it.filePath to it.fileContent } ?: emptyMap()
        return FileModifications(
            filesToWrite = filesToWriteMap,
            filesToDelete = aiResponse.filesToDelete ?: emptyList(),
            conclusion = aiResponse.conclusion
        )
    }

    fun parseAndConvertStructuredResponse(jsonText: String) : FileModifications {
        val aiResponse = parseAiStructuredResponse(jsonText)
        return convertAiResponseToFileModifications(aiResponse)
    }
    // --- End of Converters ---

    fun extractJsonArrayFromText(text: String): String {
        val startIndex = text.indexOf('[')
        val endIndex = text.lastIndexOf(']')
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return text.substring(startIndex, endIndex + 1)
        }
        val cleanedText = text.replace("```json", "").replace("```", "").trim()
        return if (cleanedText.startsWith("[") && cleanedText.endsWith("]")) cleanedText else "[]"
    }
}