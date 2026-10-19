package com.app.nuri.shownuri

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Cloud vision core (v0.3.2, Part D — Shawn's "Make it so",
 * 2026-10-18 10:16 PDT). Provider: Google Gemini free tier, REST.
 *
 * Verified from Google's docs during this build (ai.google.dev,
 * Gemini API reference + models page, last updated 2026-10-13):
 *   POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent
 *   Header: x-goog-api-key: <the caregiver's own key>
 *   Body:   {"contents":[{"parts":[{"text": …},
 *           {"inline_data":{"mime_type":"image/jpeg","data":"<base64>"}}]}]}
 *   Reply text at candidates[0].content.parts[].text.
 * gemini-2.5-flash is the current stable price-performance Flash
 * model in the API reference examples and on the free tier.
 *
 * ZERO-BUDGET / NO-EMBEDDED-KEY RULE: no key exists in this code, in
 * BuildConfig, or in either APK. The key is the caregiver's own free
 * AI Studio key, entered once on a caregiver surface, stored only in
 * the app's private DataStore, sent only as the header above (never
 * in a URL), and never written to any log or report — the tests
 * assert every string produced here is key-free.
 *
 * Guardrails live in the prompt AND are enforced locally: subjects
 * classified locally as LOOSE_PILL / BODY_OR_WOUND / PERSON never
 * reach this gateway at all (see VisionRouteSelector.cloudEligible).
 */
object GeminiCore {
    const val MODEL: String = "gemini-2.5-flash"
    const val ENDPOINT: String =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    /** Senior-fit attribution, matching the existing "(Answered by: …)" pattern. */
    const val ROUTE_NAME: String = "A closer look online"

    data class CloudAnswer(
        /** One-sentence identification, or null when the model honestly said UNSURE. */
        val identification: String?,
        /** The words read from the photo, exactly as written, or null. */
        val readText: String?,
        /** Plain-language summary, or null. */
        val plainSummary: String?,
    )

    fun buildPrompt(): String =
        "You are helping an older adult understand a photo, through an assistant named Ilona. " +
            "Use plain, kind, short sentences. Rules you must never break:\n" +
            "- Never identify a loose pill or say what any medicine is from how it looks, and never advise taking or not taking medicine. " +
            "Reading a medicine bottle's printed label word for word is allowed.\n" +
            "- Never diagnose anything, and never say what a rash, wound, or body condition is.\n" +
            "- Never say food, a plant, or medicine is safe to eat or use.\n" +
            "- Never identify a person by their face or guess who someone is.\n" +
            "- If a letter or notice looks like a scam (urgent money demands, secrecy, gift cards, threats), say so plainly in PLAIN and advise checking with family first.\n" +
            "- If you cannot tell what the object is, write UNSURE. Do not guess wildly.\n" +
            "Reply in exactly this format — three lines, no extra text:\n" +
            "ID: <what the photo shows, one short sentence; or UNSURE>\n" +
            "READ: <every word you can read in the photo, exactly as written; or NONE>\n" +
            "PLAIN: <what it is, or what the writing means, in 1 to 3 short plain sentences>"

    fun buildRequestJson(prompt: String, jpegBase64: String): String {
        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", prompt) })
                        add(buildJsonObject {
                            put("inline_data", buildJsonObject {
                                put("mime_type", "image/jpeg")
                                put("data", jpegBase64)
                            })
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject { put("temperature", 0.2) })
        }
        return body.toString()
    }

    /** Parse a generateContent reply. Null = no usable answer (blocked, empty, malformed) → caller falls back. */
    fun parseResponse(responseJson: String): CloudAnswer? {
        val root = runCatching { Json.parseToJsonElement(responseJson).jsonObject }.getOrNull() ?: return null
        val candidates = root["candidates"]?.jsonArray ?: return null
        val first = candidates.firstOrNull()?.jsonObject ?: return null
        val parts = first["content"]?.jsonObject?.get("parts")?.jsonArray ?: return null
        val text = parts.mapNotNull { part ->
            runCatching { part.jsonObject["text"]?.jsonPrimitive?.content }.getOrNull()
        }.joinToString("\n").trim()
        if (text.isEmpty()) return null
        return parseAnswerText(text)
    }

    /** Parse the model's ID:/READ:/PLAIN: block. Lenient about extra blank lines; strict about markers. */
    fun parseAnswerText(text: String): CloudAnswer? {
        var id: String? = null
        var read: String? = null
        var plain: String? = null
        var section: String? = null
        val readLines = mutableListOf<String>()
        val plainLines = mutableListOf<String>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith("ID:", ignoreCase = true) -> { section = "ID"; id = line.substringAfter(":").trim() }
                line.startsWith("READ:", ignoreCase = true) -> { section = "READ"; line.substringAfter(":").trim().takeIf { it.isNotEmpty() }?.let { readLines += it } }
                line.startsWith("PLAIN:", ignoreCase = true) -> { section = "PLAIN"; line.substringAfter(":").trim().takeIf { it.isNotEmpty() }?.let { plainLines += it } }
                section == "READ" && line.isNotEmpty() -> readLines += line
                section == "PLAIN" && line.isNotEmpty() -> plainLines += line
            }
        }
        val identification = id?.takeIf { it.isNotEmpty() && !it.equals("UNSURE", ignoreCase = true) }
        val readText = readLines.joinToString("\n").takeIf { it.isNotEmpty() && !it.equals("NONE", ignoreCase = true) }
        val plainSummary = plainLines.joinToString(" ").takeIf { it.isNotEmpty() }
        if (identification == null && readText == null && plainSummary == null) return null
        return CloudAnswer(identification, readText, plainSummary)
    }

    /**
     * A failure description safe to write into the reports store:
     * status/exception class only — never a body, never a URL, never
     * the key.
     */
    fun failureLabel(httpStatus: Int?, throwable: Throwable?): String = when {
        httpStatus != null -> "cloud look failed (HTTP $httpStatus)"
        throwable != null -> "cloud look failed (${throwable.javaClass.simpleName})"
        else -> "cloud look failed"
    }
}

/**
 * Which route answers a photo (Shawn's standing rule, 2026-10-17
 * 22:13 PDT: "just identify in any way necessary").
 *
 * Cloud-first ONLY when all of these hold: a caregiver key is set,
 * the network is up, and the locally classified subject is one the
 * cloud is allowed to see. The hard guardrail subjects (loose pill,
 * body/wound, person) are NEVER sent off the phone — their refusal
 * and describe-only paths are local and do not depend on any model
 * behaving.
 */
object VisionRouteSelector {
    enum class Route { CLOUD_FIRST, ON_DEVICE_ONLY }

    fun cloudEligible(subject: PhotoSubject): Boolean = when (subject) {
        PhotoSubject.TEXT_DOCUMENT, PhotoSubject.OBJECT, PhotoSubject.UNKNOWN -> true
        PhotoSubject.PILL_BOTTLE_LABEL -> true // reading a label as written is the allowed job
        PhotoSubject.LOOSE_PILL, PhotoSubject.BODY_OR_WOUND, PhotoSubject.PERSON, PhotoSubject.FOOD -> false
    }

    fun select(keyPresent: Boolean, networkAvailable: Boolean, subject: PhotoSubject): Route =
        if (keyPresent && networkAvailable && cloudEligible(subject)) Route.CLOUD_FIRST else Route.ON_DEVICE_ONLY

    /** Why the on-device route answered, for the diary/reports — key-free by construction. */
    fun onDeviceReason(keyPresent: Boolean, networkAvailable: Boolean, subject: PhotoSubject): String = when {
        !cloudEligible(subject) -> "kept on this phone by the safety rules"
        !keyPresent -> "no closer-look key set up"
        !networkAvailable -> "no connection"
        else -> "on this phone"
    }
}
