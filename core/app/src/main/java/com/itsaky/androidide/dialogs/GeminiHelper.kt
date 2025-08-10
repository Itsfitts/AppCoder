// GeminiHelper.kt
package com.itsaky.androidide.dialogs

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class AiFileInstruction(val filePath: String, val fileContent: String)
data class AiStructuredResponse(
    val filesToWrite: List<AiFileInstruction>? = null,
    val filesToDelete: List<String>? = null,
    val conclusion: String? = null
)
data class AiMinimalFilesResponse(val filesToWrite: List<AiFileInstruction>? = null)
data class AiSummaryResponse(val summary: String?)
data class FileModifications(
    val filesToWrite: Map<String, String>,
    val filesToDelete: List<String>,
    val conclusion: String?
)

data class MinimalBatchResult(
    val filesToWrite: Map<String, String>,
    val unchanged: List<String>
)

private fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(action)
}

class GeminiHelper(
    private val apiKeyProvider: () -> String,
    private val errorHandlerCallback: (String, Exception?) -> Unit,
    private val uiThreadExecutor: (block: () -> Unit) -> Unit
) {
    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-pro"
        private const val OPENAI_DEFAULT_MAX_COMPLETION_TOKENS = 8192
        private const val RAW_LOG_TAG = "GemHelper_RAW"

        // Max thinking budgets (per public docs; subject to provider changes)
        private const val GEMINI_25_PRO_THINK_MAX = 32768
        private const val GEMINI_25_FLASH_MAX = 24576
        private const val GEMINI_25_FLASH_LITE_MAX = 24576
    }

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val defaultThinkingBudget = 24576

    var currentModelIdentifier: String = DEFAULT_GEMINI_MODEL
        private set

    fun setModel(modelId: String) {
        currentModelIdentifier = if (modelId.isNotBlank()) modelId else DEFAULT_GEMINI_MODEL
        Log.i("GeminiHelper", "API model set to: $currentModelIdentifier")
    }

    internal fun getFileModificationsSchema(): String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("filesToWrite", JSONObject().apply {
                put("type", "array"); put("nullable", true)
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("filePath", JSONObject().apply { put("type", "string") })
                        put("fileContent", JSONObject().apply { put("type", "string") })
                    })
                    put("required", JSONArray().put("filePath").put("fileContent"))
                    put("additionalProperties", false)
                })
            })
            put("filesToDelete", JSONObject().apply {
                put("type", "array"); put("nullable", true)
                put("items", JSONObject().apply { put("type", "string") })
            })
            put("conclusion", JSONObject().apply { put("type", "string"); put("nullable", true) })
        })
        put("required", JSONArray().put("filesToWrite").put("filesToDelete").put("conclusion"))
        put("additionalProperties", false)
    }.toString()

    internal fun getMinimalFilesSchema(): String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("filesToWrite", JSONObject().apply {
                put("type", "array"); put("nullable", true)
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("filePath", JSONObject().apply { put("type", "string") })
                        put("fileContent", JSONObject().apply { put("type", "string") })
                    })
                    put("required", JSONArray().put("filePath").put("fileContent"))
                    put("additionalProperties", false)
                })
            })
        })
        put("required", JSONArray().put("filesToWrite"))
        put("additionalProperties", false)
    }.toString()

    internal fun getMinimalFilesWithUnchangedSchema(): String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("filesToWrite", JSONObject().apply {
                put("type", "array"); put("nullable", true)
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("filePath", JSONObject().apply { put("type", "string") })
                        put("fileContent", JSONObject().apply { put("type", "string") })
                    })
                    put("required", JSONArray().put("filePath").put("fileContent"))
                    put("additionalProperties", false)
                })
            })
            put("unchanged", JSONObject().apply {
                put("type", "array"); put("nullable", true)
                put("items", JSONObject().apply { put("type", "string") })
            })
        })
        put("required", JSONArray().put("filesToWrite").put("unchanged"))
        put("additionalProperties", false)
    }.toString()

    internal fun getSummaryOnlySchema(): String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("summary", JSONObject().apply {
                put("type", "string")
                put("description", "A concise summary of the provided code changes.")
            })
        })
        put("required", JSONArray().put("summary"))
        put("additionalProperties", false)
    }.toString()

    fun sendApiRequest(
        contents: List<JSONObject>,
        callback: (JSONObject) -> Unit,
        modelIdentifierOverride: String? = null,
        responseSchemaJson: String? = null,
        responseMimeTypeOverride: String? = "application/json"
    ) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) { errorHandlerCallback("API Key is not set.", null); return }

        val effectiveModelIdentifier = modelIdentifierOverride ?: currentModelIdentifier
        val isGptModel = effectiveModelIdentifier.startsWith("gpt-", ignoreCase = true)

        val requestJson = if (isGptModel) {
            buildOpenAiRequest(
                modelId = effectiveModelIdentifier,
                geminiContents = contents,
                responseSchemaJson = responseSchemaJson
            )
        } else {
            buildGeminiRequest(contents, responseSchemaJson, responseMimeTypeOverride, effectiveModelIdentifier)
        } ?: return

        val url = if (isGptModel)
            "https://api.openai.com/v1/chat/completions"
        else
            "https://generativelanguage.googleapis.com/v1beta/models/${effectiveModelIdentifier}:generateContent?key=$apiKey"

        val req = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { if (isGptModel) addHeader("Authorization", "Bearer $apiKey") }
            .build()

        val http = clientForModel(effectiveModelIdentifier)
        fun enqueueWithRetry(attempt: Int) {
            http.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val retryable = (e is SocketTimeoutException) || (e is UnknownHostException)
                    if (retryable && attempt < 2) {
                        Log.w("GeminiHelper", "Network error on $effectiveModelIdentifier: ${e.javaClass.simpleName}. Retrying (attempt ${attempt + 1})...")
                        enqueueWithRetry(attempt + 1); return
                    }
                    errorHandlerCallback("API Network Error ($effectiveModelIdentifier): ${e.message}", e)
                }
                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string()
                    try {
                        if (!response.isSuccessful || responseBody == null) {
                            var detail = responseBody ?: "No error body"
                            try {
                                val j = JSONObject(responseBody)
                                detail = j.optJSONObject("error")?.optString("message", detail) ?: j.optString("message", detail)
                            } catch (_: Exception) {}
                            errorHandlerCallback("API Error ($effectiveModelIdentifier - Code: ${response.code}): $detail", null)
                            return
                        }
                        Log.d(RAW_LOG_TAG, "Raw JSON from ${if (isGptModel) "OpenAI" else "Gemini"}: $responseBody")
                        val jsonResponse = JSONObject(responseBody)
                        uiThreadExecutor { callback(jsonResponse) }
                    } catch (e: Exception) {
                        errorHandlerCallback("Error processing API response ($effectiveModelIdentifier): ${e.message}", e)
                        Log.e("GeminiHelper", "Response Body on error: $responseBody", e)
                    } finally { response.body?.close() }
                }
            })
        }
        enqueueWithRetry(0)
    }

    private fun clientForModel(modelId: String): OkHttpClient {
        val id = modelId.lowercase()
        val b = baseClient.newBuilder()
        if (id.startsWith("gpt-5")) {
            // Longer timeouts for GPT-5 reasoning responses
            b.readTimeout(300, TimeUnit.SECONDS)
            b.writeTimeout(300, TimeUnit.SECONDS)
            b.connectTimeout(60, TimeUnit.SECONDS)
            b.pingInterval(30, TimeUnit.SECONDS)
        }
        if (id.startsWith("gemini-2.5")) {
            // Longer timeouts for Gemini 2.5 (thinking can increase latency)
            b.readTimeout(300, TimeUnit.SECONDS)
            b.writeTimeout(300, TimeUnit.SECONDS)
            b.connectTimeout(60, TimeUnit.SECONDS)
            b.pingInterval(30, TimeUnit.SECONDS)
        }
        return b.build()
    }

    private fun openAiTokenParamName(modelId: String): String {
        val id = modelId.lowercase()
        return if (id.startsWith("gpt-5") || id.startsWith("gpt-4.1"))
            "max_completion_tokens" else "max_tokens"
    }

    private fun openAiMaxCompletionTokens(modelId: String): Int {
        val id = modelId.lowercase()
        return when {
            id.startsWith("gpt-5-nano") -> 20_000
            id.startsWith("gpt-5-mini") -> 20_000
            id.startsWith("gpt-5")      -> 40_000
            id.startsWith("gpt-4.1")    -> 4_096
            else -> OPENAI_DEFAULT_MAX_COMPLETION_TOKENS
        }
    }

    private fun removeUnsupportedKeys(json: Any): Any {
        when (json) {
            is JSONObject -> {
                val keysToRemove = mutableListOf<String>()
                val iterator = json.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    if (key == "additionalProperties") {
                        keysToRemove.add(key)
                    } else {
                        removeUnsupportedKeys(json.get(key))
                    }
                }
                for (key in keysToRemove) {
                    json.remove(key)
                }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) {
                    removeUnsupportedKeys(json.get(i))
                }
            }
        }
        return json
    }

    // OpenAI Chat Completions payload (request high reasoning effort for GPT-5; no unsupported fields)
    private fun buildOpenAiRequest(
        modelId: String,
        geminiContents: List<JSONObject>,
        responseSchemaJson: String?
    ): JSONObject {
        val userContent = StringBuilder().apply {
            geminiContents.forEach { content ->
                content.optJSONArray("parts")?.let { parts ->
                    for (i in 0 until parts.length())
                        parts.optJSONObject(i)?.optString("text")?.let { append(it).append("\n") }
                }
            }
        }.toString()

        val messages = JSONArray()
        if (!responseSchemaJson.isNullOrBlank()) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", "You are an expert Android developer. You must respond using the provided JSON schema.")
            })
        }
        messages.put(JSONObject().apply { put("role", "user"); put("content", userContent) })

        val body = JSONObject().apply {
            put("model", modelId)
            put("messages", messages)
            // Token limits: let the API use defaults; do not set max_(completion_)tokens explicitly
        }

        val supportsJsonSchemaFormat = run {
            val idLower = modelId.lowercase()
            idLower.startsWith("gpt-5") &&
                    !idLower.contains("nano") &&
                    !idLower.contains("mini")
        }
        if (!responseSchemaJson.isNullOrBlank()) {
            if (supportsJsonSchemaFormat) {
                Log.d(RAW_LOG_TAG, "Using 'json_schema' response_format for model: $modelId")
                body.put("response_format", JSONObject().apply {
                    put("type", "json_schema")
                    put("json_schema", JSONObject().apply {
                        put("name", "android_ide_schema")
                        put("strict", true)
                        put("schema", JSONObject(responseSchemaJson))
                    })
                })
            } else {
                Log.d(RAW_LOG_TAG, "Falling back to 'json_object' (JSON Mode) for model: $modelId")
                body.put("response_format", JSONObject().put("type", "json_object"))
            }
        }

        val idLower = modelId.lowercase()
        if (idLower.startsWith("gpt-5")) {
            body.put("reasoning_effort", "high")
        }

        return body
    }

    // Gemini request payload (mediaResolution MEDIUM for all 2.5 models currently)
    private fun buildGeminiRequest(
        contents: List<JSONObject>,
        responseSchemaJson: String?,
        responseMimeTypeOverride: String?,
        effectiveModelIdentifier: String
    ): JSONObject? {
        val idLower = effectiveModelIdentifier.lowercase()
        val isGemini25 = idLower.startsWith("gemini-2.5")
        val isPro = idLower.startsWith("gemini-2.5-pro")
        val isFlash = idLower.startsWith("gemini-2.5-flash")
        val isFlashLite = idLower.contains("flash-lite")

        val generationConfig = JSONObject().apply {
            put("temperature", 1)
            put("maxOutputTokens", 200000)
            put("topP", 0.95)
            put("topK", 40)

            if (!responseSchemaJson.isNullOrBlank()) {
                put("response_mime_type", responseMimeTypeOverride ?: "application/json")
                try {
                    val originalSchema = JSONObject(responseSchemaJson)
                    val cleanedSchema = removeUnsupportedKeys(originalSchema) as JSONObject
                    put("response_schema", cleanedSchema)
                } catch (e: JSONException) {
                    errorHandlerCallback("Invalid response_schema JSON: ${e.message}", e)
                    return null
                }
            }

            if (isGemini25) {
                val budget = when {
                    isPro -> GEMINI_25_PRO_THINK_MAX
                    isFlash -> GEMINI_25_FLASH_MAX
                    isFlashLite -> GEMINI_25_FLASH_LITE_MAX
                    else -> defaultThinkingBudget
                }
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingBudget", budget)   // maximize thinking tokens
                    put("includeThoughts", true)    // include thought summaries
                })

                // All 2.5 models (Pro/Flash/Flash-Lite) use MEDIA_RESOLUTION_MEDIUM currently
                put("mediaResolution", "MEDIA_RESOLUTION_MEDIUM")
            }
        }

        // Keep for compatibility with earlier previews that expected thinkingConfig presence
        if (isGemini25) {
            generationConfig.put("thinkingConfig", generationConfig.optJSONObject("thinkingConfig") ?: JSONObject().apply {
                put("thinkingBudget", defaultThinkingBudget)
                put("includeThoughts", true)
            })
        }

        return JSONObject().apply {
            put("contents", JSONArray(contents))
            put("generationConfig", generationConfig)
            put("safetySettings", JSONArray().apply {
                put(JSONObject().apply { put("category", "HARM_CATEGORY_HARASSMENT"); put("threshold", "BLOCK_MEDIUM_AND_ABOVE") })
                put(JSONObject().apply { put("category", "HARM_CATEGORY_HATE_SPEECH"); put("threshold", "BLOCK_MEDIUM_AND_ABOVE") })
                put(JSONObject().apply { put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT"); put("threshold", "BLOCK_MEDIUM_AND_ABOVE") })
                put(JSONObject().apply { put("category", "HARM_CATEGORY_DANGEROUS_CONTENT"); put("threshold", "BLOCK_MEDIUM_AND_ABOVE") })
            })
        }
    }

    // When includeThoughts=true, Gemini may put a "thought" part first.
    // Skip thought parts and prefer the first JSON-like non-thought text part; otherwise first non-thought text.
    fun extractTextFromApiResponse(response: JSONObject): String {
        return try {
            if (response.has("candidates")) {
                val parts = response.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts") ?: return ""

                var firstNonThought: String? = null
                var jsonLike: String? = null

                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.optBoolean("thought", false)) continue

                    val raw = part.optString("text", null) ?: continue
                    if (raw.isBlank()) continue

                    val text = unwrapCodeFences(raw).trim()
                    if (firstNonThought == null) firstNonThought = text

                    if ((text.startsWith("{") && text.endsWith("}")) ||
                        (text.startsWith("[") && text.endsWith("]"))
                    ) {
                        jsonLike = text
                        break
                    }
                }

                jsonLike ?: firstNonThought ?: ""
            } else if (response.has("choices")) {
                val firstChoice = response.optJSONArray("choices")?.optJSONObject(0) ?: return ""
                val message = firstChoice.optJSONObject("message") ?: return ""
                message.optString("content", null)?.let { if (it.isNotBlank()) return it }
                message.optJSONArray("tool_calls")?.let { toolCalls ->
                    if (toolCalls.length() > 0) {
                        toolCalls.optJSONObject(0)
                            ?.optJSONObject("function")
                            ?.optString("arguments", "")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { return it }
                    }
                }
                ""
            } else {
                Log.w("GeminiHelper", "Could not find 'candidates' or 'choices' in API response.")
                ""
            }
        } catch (e: Exception) {
            Log.e("GeminiHelper", "Error extracting content string from response", e)
            ""
        }
    }

    private fun unwrapCodeFences(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```")
            val newlineIdx = t.indexOf('\n')
            if (newlineIdx != -1) {
                t = t.substring(newlineIdx + 1)
            }
            if (t.endsWith("```")) t = t.removeSuffix("```")
        }
        return t.trim()
    }

    private fun JSONObject.optJSONArrayByKeys(vararg keys: String): JSONArray? {
        for (k in keys) optJSONArray(k)?.let { return it }
        return null
    }
    private fun JSONObject.optStringByKeys(vararg keys: String): String? {
        for (k in keys) {
            val v = optString(k, null)
            if (!v.isNullOrBlank()) return v
        }
        return null
    }
    private fun JSONObject.unwrapDataIfPresent(): JSONObject {
        return optJSONObject("data") ?: this
    }

    private fun parseAiStructuredResponse(jsonText: String): AiStructuredResponse {
        val filesToWrite = mutableListOf<AiFileInstruction>()
        val filesToDelete = mutableListOf<String>()
        var conclusion: String? = null
        try {
            val root = JSONObject(jsonText).unwrapDataIfPresent()
            val writeArray = root.optJSONArrayByKeys("filesToWrite", "files_to_write")
            writeArray?.forEachObject { obj ->
                val path = obj.optStringByKeys("filePath", "file_path") ?: ""
                val content = obj.optStringByKeys("fileContent", "file_content") ?: ""
                if (path.isNotBlank()) filesToWrite.add(AiFileInstruction(path, content))
            }
            val deleteArray = root.optJSONArrayByKeys("filesToDelete", "files_to_delete")
            deleteArray?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i)?.takeIf { it.isNotBlank() }?.let { filesToDelete.add(it) }
                }
            }
            conclusion = root.optStringByKeys("conclusion", "summary")
        } catch (e: JSONException) {
            Log.e("GeminiHelper", "Error parsing AiStructuredResponse JSON: '$jsonText'. Error: ${e.message}", e)
        }
        return AiStructuredResponse(
            filesToWrite = filesToWrite.takeIf { it.isNotEmpty() },
            filesToDelete = filesToDelete.takeIf { it.isNotEmpty() },
            conclusion = conclusion
        )
    }

    fun parseMinimalFilesResponse(jsonText: String): Map<String, String>? {
        val filesMap = mutableMapOf<String, String>()
        try {
            val root = JSONObject(jsonText).unwrapDataIfPresent()
            val arr = root.optJSONArrayByKeys("filesToWrite", "files_to_write")
            arr?.forEachObject { obj ->
                val path = obj.optStringByKeys("filePath", "file_path") ?: ""
                val content = obj.optStringByKeys("fileContent", "file_content") ?: ""
                if (path.isNotBlank()) filesMap[path] = content
            }
        } catch (e: JSONException) {
            Log.e("GeminiHelper", "Error parsing AiMinimalFilesResponse JSON: '$jsonText'. Error: ${e.message}", e)
            return null
        }
        return filesMap.takeIf { it.isNotEmpty() }
    }

    fun parseMinimalFilesAndUnchanged(jsonText: String): MinimalBatchResult? {
        return try {
            val root = JSONObject(jsonText).unwrapDataIfPresent()

            val filesMap = mutableMapOf<String, String>()
            root.optJSONArrayByKeys("filesToWrite", "files_to_write")?.forEachObject { obj ->
                val path = obj.optStringByKeys("filePath", "file_path") ?: ""
                val content = obj.optStringByKeys("fileContent", "file_content") ?: ""
                if (path.isNotBlank()) filesMap[path] = content
            }

            val unchanged = mutableListOf<String>()
            root.optJSONArrayByKeys("unchanged")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i)?.takeIf { it.isNotBlank() }?.let { unchanged.add(it) }
                }
            }

            MinimalBatchResult(
                filesToWrite = filesMap,
                unchanged = unchanged
            )
        } catch (e: JSONException) {
            Log.e("GeminiHelper", "Error parsing MinimalBatchResult JSON: '$jsonText'. Error: ${e.message}", e)
            null
        }
    }

    fun parseSummaryResponse(jsonText: String): String? {
        return try {
            val root = JSONObject(jsonText).unwrapDataIfPresent()
            root.optStringByKeys("summary", "conclusion")
        } catch (e: JSONException) {
            Log.e("GeminiHelper", "Error parsing AiSummaryResponse JSON: '$jsonText'. Error: ${e.message}", e)
            null
        }
    }

    fun convertAiResponseToFileModifications(aiResponse: AiStructuredResponse): FileModifications {
        val filesToWriteMap = aiResponse.filesToWrite?.associate { it.filePath to it.fileContent } ?: emptyMap()
        return FileModifications(filesToWriteMap, aiResponse.filesToDelete ?: emptyList(), aiResponse.conclusion)
    }

    fun parseAndConvertStructuredResponse(jsonText: String): FileModifications {
        val aiResponse = parseAiStructuredResponse(jsonText)
        return convertAiResponseToFileModifications(aiResponse)
    }

    fun extractJsonArrayFromText(text: String): String {
        val startIndex = text.indexOf('[')
        val endIndex = text.lastIndexOf(']')
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) return text.substring(startIndex, endIndex + 1)
        val cleanedText = text.replace("```json", "").replace("```", "").trim()
        return if (cleanedText.startsWith("[") && cleanedText.endsWith("]")) cleanedText else "[]"
    }
}