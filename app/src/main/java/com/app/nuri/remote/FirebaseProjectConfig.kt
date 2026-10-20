package com.app.nuri.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Firebase project's identifying config (design §10.2). These
 * values identify a project; they are NOT a password — access is
 * governed by the security rules and anonymous-auth identity. Even
 * so, the private project's config is treated as private-repo
 * material by standing law and never ships in the public build.
 */
@kotlinx.serialization.Serializable
data class FirebaseProjectConfig(
    val projectId: String,
    val apiKey: String,
    val appId: String,
    val senderId: String,
) {
    /** Shape check used by the paste flow: "That looks right" vs not. */
    fun isPlausible(): Boolean =
        projectId.isNotBlank() && apiKey.isNotBlank() && appId.contains(":android:") && senderId.all { it.isDigit() } && senderId.isNotBlank()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parse a google-services.json document (the private flavor's bundled config). */
        fun fromGoogleServicesJson(text: String): FirebaseProjectConfig? {
            return try {
                val root = json.parseToJsonElement(text).jsonObject
                val project = root["project_info"]?.jsonObject ?: return null
                val client = root["client"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
                val clientInfo = client["client_info"]?.jsonObject ?: return null
                val apiKey = client["api_key"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("current_key")?.jsonPrimitive?.content ?: return null
                FirebaseProjectConfig(
                    projectId = project["project_id"]?.jsonPrimitive?.content ?: return null,
                    apiKey = apiKey,
                    appId = clientInfo["mobilesdk_app_id"]?.jsonPrimitive?.content ?: return null,
                    senderId = project["project_number"]?.jsonPrimitive?.content ?: return null,
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Parse the console's "firebaseConfig" block as a caregiver
         * pastes it (§10.2): either strict JSON or the JavaScript
         * object shape the console shows (unquoted keys, single or
         * double quotes). Extraction is field-wise and tolerant;
         * [isPlausible] makes the final call.
         */
        fun fromPastedConfig(text: String): FirebaseProjectConfig? {
            fun field(name: String): String? {
                val pattern = Regex("""["']?$name["']?\s*:\s*["']([^"']+)["']""")
                return pattern.find(text)?.groupValues?.get(1)?.trim()
            }
            val projectId = field("projectId") ?: return null
            val apiKey = field("apiKey") ?: return null
            val appId = field("appId") ?: return null
            val senderId = field("messagingSenderId") ?: return null
            val config = FirebaseProjectConfig(projectId, apiKey, appId, senderId)
            return if (config.isPlausible()) config else null
        }
    }
}
