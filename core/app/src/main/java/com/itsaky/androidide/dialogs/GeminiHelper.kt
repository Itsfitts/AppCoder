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

    // Sie können hier einen Standard-Thinking-Budget-Wert definieren
    // oder ihn als Parameter zur sendGeminiRequest Methode hinzufügen,
    // wenn Sie ihn dynamisch steuern möchten.
    private val defaultThinkingBudget = 0 // Oder 1024, je nach Bedarf

    fun sendGeminiRequest(contents: List<JSONObject>, callback: (JSONObject) -> Unit) {
        val apiKey = apiKeyProvider()

        if (apiKey.isBlank()) {
            errorHandler("Gemini API Key is not set.")
            return
        }

        val requestJson = JSONObject().apply {
            put("contents", JSONArray(contents))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.5)
                put("maxOutputTokens", 8192)
                // NEU: thinkingConfig und thinkingBudget hinzufügen
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingBudget", defaultThinkingBudget) // Verwenden Sie hier den gewünschten Wert
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

        // Korrigierter Modellname
        val modelIdentifier = "gemini-2.5-flash-preview-04-17"
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${modelIdentifier}:generateContent?key=$apiKey")
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
                        var errorBodyDetails = responseBody ?: "No error body"
                        try {
                            // Versuche, den Fehlerbody als JSON zu parsen, für mehr Details
                            val errorJson = JSONObject(responseBody)
                            errorBodyDetails = errorJson.getJSONObject("error").getString("message")
                        } catch (jsonEx: Exception) {
                            // Ignoriere, wenn der Fehlerbody kein JSON ist
                        }
                        errorHandler("API Error: ${response.code} ${response.message} - $errorBodyDetails")
                        return
                    }

                    val jsonResponse = JSONObject(responseBody)

                    if (jsonResponse.has("promptFeedback") &&
                        jsonResponse.getJSONObject("promptFeedback").optString("blockReason", null) != null) {
                        val reason = jsonResponse.getJSONObject("promptFeedback").getString("blockReason")
                        val safetyRatings = jsonResponse.getJSONObject("promptFeedback").optJSONArray("safetyRatings")?.toString(2) ?: "N/A"
                        errorHandler("API Response Blocked: $reason. Safety Ratings: $safetyRatings")
                    } else if (!jsonResponse.has("candidates") || jsonResponse.getJSONArray("candidates").length() == 0) {
                        val finishReason = jsonResponse.optJSONObject("promptFeedback")?.optString("blockReason") // Manchmal ist der Block-Grund hier
                            ?: jsonResponse.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason")

                        // Überprüfen, ob es überhaupt einen Text-Teil gibt, bevor man von "leer" spricht
                        val textExistsInResponse = responseBody.contains("\"text\"")

                        if (finishReason != null && finishReason != "STOP" && finishReason != "MAX_TOKENS" && !textExistsInResponse) {
                            errorHandler("API Error: No valid candidates or text found. Finish Reason: $finishReason. Response: ${jsonResponse.toString(2)}")
                        } else if (!textExistsInResponse) {
                            errorHandler("API Error: Empty response or no text part found. Finish Reason: $finishReason. Response: ${jsonResponse.toString(2)}")
                        }
                        else {
                            // Wenn der FinishReason STOP oder MAX_TOKENS ist, aber kein Text da ist,
                            // könnte das auch ein Problem sein, aber wir lassen es vorerst durch,
                            // wenn die obigen Bedingungen nicht zutreffen.
                            // Die Logik hier könnte je nach API-Verhalten noch verfeinert werden.
                            uiCallback { callback(jsonResponse) }
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

    // --- extractTextFromGeminiResponse, extractJsonArrayFromText, parseFileChanges bleiben unverändert ---
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
            // Nicht errorHandler hier aufrufen, da die Antwort an sich gültig sein kann, nur ohne Text
            return ""
        } catch (e: Exception) {
            Log.e("GeminiHelper", "Error extracting text from response", e)
            errorHandler("Error extracting text from response: ${e.message}") // Hier ist es ein Verarbeitungsfehler
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
        // Nicht unbedingt ein Fehler, den man hier an den errorHandler geben muss,
        // es sei denn, ein JSON-Array wird IMMER erwartet.
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
            // Diese Logik könnte zu viele False Positives erzeugen, wenn nicht immer File-Blöcke erwartet werden.
            // logAppender("⚠️ AI response did not contain recognizable file modification blocks.\nResponse:\n$text\n")
        }
        return result
    }
}

// --- GeminiConversation bleibt unverändert ---
class GeminiConversation {
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
            Log.w("GeminiHelper", "Attempted to add empty model message to conversation.")
        }
    }

    fun getContents(): List<JSONObject> = messages.toList()

    fun clear() {
        messages.clear()
    }
}