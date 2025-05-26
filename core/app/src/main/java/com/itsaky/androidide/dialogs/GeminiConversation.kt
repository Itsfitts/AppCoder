package com.itsaky.androidide.dialogs // Adjust package if needed

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

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
                    // Assuming the model's response text is the primary content part
                    put(JSONObject().apply { put("text", rawResponseText) })
                })
            })
        } else {
            Log.w("GeminiConversation", "Attempted to add empty model message to conversation.")
        }
    }

    // Renamed from getContents to match usage in GeminiWorkflowCoordinator
    fun getContentsForApi(): List<JSONObject> = messages.toList()

    fun clear() {
        messages.clear()
    }
}