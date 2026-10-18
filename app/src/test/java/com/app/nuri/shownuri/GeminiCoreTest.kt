package com.app.nuri.shownuri

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Part D cloud-route core (v0.3.2): endpoint shape verified against
 * Google's docs 2026-10-18, request/response JSON, route selection
 * (guardrail subjects NEVER go to the cloud), and the no-key-leak
 * invariant: every string this layer produces is key-free.
 */
class GeminiCoreTest {

    @Test fun endpointIsTheDocumentedGenerateContentUrl() {
        assertEquals("gemini-2.5-flash", GeminiCore.MODEL)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            GeminiCore.ENDPOINT,
        )
        // The key rides in a header, never in the URL.
        assertFalse(GeminiCore.ENDPOINT.contains("key"))
    }

    @Test fun requestJsonMatchesTheDocumentedShape() {
        val json = GeminiCore.buildRequestJson("PROMPT-TEXT", "QUJD")
        val root = Json.parseToJsonElement(json).jsonObject
        val parts = root["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals("PROMPT-TEXT", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        val inline = parts[1].jsonObject["inline_data"]!!.jsonObject
        assertEquals("image/jpeg", inline["mime_type"]!!.jsonPrimitive.content)
        assertEquals("QUJD", inline["data"]!!.jsonPrimitive.content)
        // No key material anywhere in the request body.
        assertFalse(json.contains("x-goog-api-key"))
        assertFalse(json.contains("apiKey"))
    }

    @Test fun promptCarriesTheHardGuardrails() {
        val prompt = GeminiCore.buildPrompt()
        assertTrue(prompt.contains("Never identify a loose pill"))
        assertTrue(prompt.contains("Never diagnose"))
        assertTrue(prompt.contains("safe to eat or use"))
        assertTrue(prompt.contains("identify a person by their face"))
        assertTrue(prompt.contains("UNSURE"))
    }

    @Test fun parsesAWellFormedReply() {
        val reply = """
            {"candidates":[{"content":{"parts":[{"text":"ID: A television remote control.\nREAD: LG NETFLIX VOL CH\nPLAIN: This is the remote for a television. Its buttons change the channel and the sound."}],"role":"model"},"finishReason":"STOP"}]}
        """.trimIndent()
        val answer = GeminiCore.parseResponse(reply)
        assertTrue(answer != null)
        assertEquals("A television remote control.", answer!!.identification)
        assertEquals("LG NETFLIX VOL CH", answer.readText)
        assertTrue(answer.plainSummary!!.contains("remote for a television"))
    }

    @Test fun unsureAndNoneBecomeNulls() {
        val answer = GeminiCore.parseAnswerText("ID: UNSURE\nREAD: NONE\nPLAIN: The photo is too dark to tell.")
        assertTrue(answer != null)
        assertNull(answer!!.identification)
        assertNull(answer.readText)
        assertEquals("The photo is too dark to tell.", answer.plainSummary)
    }

    @Test fun multilineReadKeepsItsLines() {
        val answer = GeminiCore.parseAnswerText("ID: A letter.\nREAD: Dear Mrs Alvarez,\nYour visit was covered.\nPLAIN: A letter about a doctor visit.")
        assertEquals("Dear Mrs Alvarez,\nYour visit was covered.", answer!!.readText)
    }

    @Test fun unusableRepliesAreNullSoTheCallerFallsBack() {
        assertNull(GeminiCore.parseResponse("not json at all"))
        assertNull(GeminiCore.parseResponse("{}"))
        // Safety-blocked replies carry promptFeedback and no candidates.
        assertNull(GeminiCore.parseResponse("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertNull(GeminiCore.parseAnswerText("I looked at the photo and it was nice."))
    }

    @Test fun failureLabelsNeverLeakTheKey() {
        val fakeKey = "TESTKEY-do-not-leak-123456789"
        val fromThrowable = GeminiCore.failureLabel(null, RuntimeException("auth failed for $fakeKey"))
        assertFalse(fromThrowable.contains(fakeKey))
        assertFalse(fromThrowable.lowercase().contains("key"))
        assertEquals("cloud look failed (HTTP 403)", GeminiCore.failureLabel(403, null))
    }

    // ── Route selection ─────────────────────────────────────────────

    @Test fun cloudFirstOnlyWithKeyNetworkAndEligibleSubject() {
        assertEquals(
            VisionRouteSelector.Route.CLOUD_FIRST,
            VisionRouteSelector.select(keyPresent = true, networkAvailable = true, subject = PhotoSubject.TEXT_DOCUMENT),
        )
        assertEquals(
            VisionRouteSelector.Route.ON_DEVICE_ONLY,
            VisionRouteSelector.select(keyPresent = false, networkAvailable = true, subject = PhotoSubject.TEXT_DOCUMENT),
        )
        assertEquals(
            VisionRouteSelector.Route.ON_DEVICE_ONLY,
            VisionRouteSelector.select(keyPresent = true, networkAvailable = false, subject = PhotoSubject.OBJECT),
        )
    }

    @Test fun guardrailSubjectsNeverGoToTheCloud() {
        // Even with a key and a network, these stay on the phone: the
        // refusal / describe-only paths are local by design.
        listOf(PhotoSubject.LOOSE_PILL, PhotoSubject.BODY_OR_WOUND, PhotoSubject.PERSON, PhotoSubject.FOOD).forEach { subject ->
            assertEquals(
                "subject $subject must never be cloud-eligible",
                VisionRouteSelector.Route.ON_DEVICE_ONLY,
                VisionRouteSelector.select(keyPresent = true, networkAvailable = true, subject = subject),
            )
        }
        assertEquals(
            "kept on this phone by the safety rules",
            VisionRouteSelector.onDeviceReason(keyPresent = true, networkAvailable = true, subject = PhotoSubject.LOOSE_PILL),
        )
        assertEquals(
            "no closer-look key set up",
            VisionRouteSelector.onDeviceReason(keyPresent = false, networkAvailable = true, subject = PhotoSubject.OBJECT),
        )
    }
}
