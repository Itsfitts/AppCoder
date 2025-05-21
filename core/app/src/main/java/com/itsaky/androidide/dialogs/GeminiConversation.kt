/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.dialogs

import android.util.Log // Add if not already there
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
                    put(JSONObject().apply { put("text", rawResponseText) })
                })
            })
        } else {
            // Consider using a more specific TAG for Log if this class is widely used
            Log.w("GeminiConversation", "Attempted to add empty model message to conversation.")
        }
    }

    fun getContents(): List<JSONObject> = messages.toList()

    fun clear() {
        messages.clear()
    }
}