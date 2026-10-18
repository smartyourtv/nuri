package com.app.nuri.shownuri

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v0.3.2 honesty fixtures — built from Shawn's two on-device
 * Show Nuri failures of 2026-10-18:
 *  F1 (10:11 PDT): a photo of the ThinkPad's Scout install screen.
 *     v0.3.1 presented the OCR debris below as "everything it says,
 *     as written" and guessed "might be a building."
 *  F2 (10:15 PDT): a clear, centered photo of an LG TV remote
 *     (model AKB75375604; photo: ~/workspace/user/files/16692_35_xtqw.jpg).
 *     v0.3.1 answered "It looks like it might be a space" — ML Kit's
 *     scene category — while the remote's own buttons (LG, NETFLIX,
 *     VOL, CH) were readable in the same frame.
 */
class ShowNuriHonestyTest {

    companion object {
        /** F1: the exact failing OCR sample from Shawn's screenshot. */
        val INSTALL_SCREEN_OCR: String = listOf(
            "610 bytor)",
            "24149239 bytes)",
            "(27768 byten)",
            "4216) byte)",
            "bytes)",
            "bytes)",
            "arge)",
            "* 00",
            "VW",
            "S",
            "N",
            "Shitt",
        ).joinToString("\n")

        val REAL_LETTER: String = listOf(
            "Medicare Summary Notice",
            "This is not a bill",
            "Dear Mrs Alvarez,",
            "Your doctor visit on October 3 was covered by your plan.",
            "You were charged $250.00 for the visit.",
            "Please call us if you have questions.",
        ).joinToString("\n")

        val PILL_LABEL: String = listOf(
            "Lisinopril 10 mg tablets",
            "Take one tablet by mouth every morning",
            "Refills: 3",
            "Discard after June 2027",
            "Pharmacy: Main Street Pharmacy",
        ).joinToString("\n")

        /** F2: the words ML Kit OCR can read on the remote photo. */
        val REMOTE_OCR: String = listOf(
            "TV",
            "1 2 3",
            "LIST 0 Q.VIEW",
            "VOL CH",
            "NETFLIX GUIDE",
            "BACK EXIT OK",
            "LG",
            "AKB75375604",
        ).joinToString("\n")
    }

    // ── F1: the gibberish gate ──────────────────────────────────────

    @Test fun installScreenDebrisIsNotReadable() {
        val gate = ReadabilityGate.assess(INSTALL_SCREEN_OCR)
        assertEquals(ReadabilityGate.Verdict.NOT_READABLE, gate.verdict)
        assertTrue("debris must have no clean lines", gate.cleanLines.isEmpty())
        assertTrue("word ratio must be near zero, was ${gate.wordRatio}", gate.wordRatio < 0.2)
        assertTrue("junk ratio must dominate, was ${gate.junkRatio}", gate.junkRatio > 0.5)
    }

    @Test fun debrisFragmentsAreNeverPresentedAsAReading() {
        // The coaching line is the whole answer for F1 — and it must
        // not smuggle any fragment back in as content.
        val coaching = ReadabilityGate.notReadableLine(screenLike = true)
        listOf("bytor", "byten", "Shitt", "24149239", "arge").forEach { fragment ->
            assertFalse("coaching must not contain fragment '$fragment'", coaching.contains(fragment))
        }
        assertTrue(coaching.contains("couldn't read that clearly"))
        assertTrue("screen tip required for screen photos", coaching.contains("computer screen"))
    }

    @Test fun installScreenDebrisLooksLikeAScreen() {
        assertTrue(ReadabilityGate.looksLikeScreen(INSTALL_SCREEN_OCR, listOf("Building")))
        assertTrue(ReadabilityGate.looksLikeScreen("some words", listOf("Screenshot")))
        assertFalse(ReadabilityGate.looksLikeScreen("Dear Mrs Alvarez, thank you for your letter", emptyList()))
    }

    // ── Real texts must still read ──────────────────────────────────

    @Test fun realLetterIsReadable() {
        val gate = ReadabilityGate.assess(REAL_LETTER)
        assertEquals(ReadabilityGate.Verdict.READABLE, gate.verdict)
        assertTrue(gate.wordRatio > 0.7)
    }

    @Test fun pillLabelIsReadable() {
        val gate = ReadabilityGate.assess(PILL_LABEL)
        assertEquals(ReadabilityGate.Verdict.READABLE, gate.verdict)
    }

    @Test fun mixedTextKeepsCleanLinesAndDropsGarbage() {
        val mixed = listOf(
            "Your appointment is on Tuesday morning",
            "610 bytor) 24149239 bytes)",
            "Please bring your insurance card with you",
        ).joinToString("\n")
        val gate = ReadabilityGate.assess(mixed)
        assertEquals(ReadabilityGate.Verdict.PARTIAL, gate.verdict)
        assertEquals(2, gate.cleanLines.size)
        assertTrue(gate.cleanLines.any { it.contains("appointment") })
        assertTrue(gate.cleanLines.any { it.contains("insurance") })
        assertTrue(gate.cleanLines.none { it.contains("bytor") })
        assertEquals(1, gate.droppedLineCount)
    }

    @Test fun emptyTextIsNotReadable() {
        assertEquals(ReadabilityGate.Verdict.NOT_READABLE, ReadabilityGate.assess("").verdict)
        assertEquals(ReadabilityGate.Verdict.NOT_READABLE, ReadabilityGate.assess("   \n  ").verdict)
    }

    // ── F2: scene labels are not identifications ────────────────────

    @Test fun spaceLabelIsNeverAnIdentificationAtAnyConfidence() {
        val labels = listOf(VisionLabel("Space", 0.99f))
        assertTrue("scene labels must be filtered out entirely", ReadAloudComposer.objectLabels(labels).isEmpty())
        val line = ReadAloudComposer.identifyLine(labels, "Maria")
        assertTrue(line.contains("can't tell"))
        assertFalse(line.lowercase().contains("space"))
    }

    @Test fun sceneAndCategoryLabelsAreFiltered() {
        val labels = listOf(
            VisionLabel("Room", 0.9f), VisionLabel("Building", 0.95f),
            VisionLabel("Sky", 0.8f), VisionLabel("Television remote", 0.8f),
        )
        val objects = ReadAloudComposer.objectLabels(labels)
        assertEquals(listOf("Television remote"), objects.map { it.text })
    }

    @Test fun midConfidenceObjectLabelsAreSuppressedNotHedged() {
        val line = ReadAloudComposer.identifyLine(listOf(VisionLabel("Building", 0.99f), VisionLabel("Plant", 0.6f)), "Maria")
        assertTrue(line.contains("can't tell"))
        assertFalse(line.contains("might be"))
    }

    @Test fun remoteIsIdentifiedFromItsOwnButtons() {
        val line = ReadAloudComposer.identifyFromOcr(REMOTE_OCR)
        assertTrue("expected a remote identification, got: $line", line != null)
        assertTrue(line!!.contains("television remote control"))
        assertTrue(line.contains("LG"))
        // And the F2 answer as composed never reaches for the scene word:
        val labels = listOf(VisionLabel("Space", 0.92f))
        val fallback = ReadAloudComposer.identifyLine(labels, "Maria")
        assertFalse(fallback.lowercase().contains("space"))
    }

    @Test fun ocrEvidenceNeedsSeveralSignals() {
        // One stray word is not an identification.
        assertEquals(null, ReadAloudComposer.identifyFromOcr("NETFLIX"))
        assertEquals(null, ReadAloudComposer.identifyFromOcr("Dear Mrs Alvarez, your bill is ready"))
        // Microwave evidence fires on cooking words together.
        val micro = ReadAloudComposer.identifyFromOcr("POPCORN DEFROST COOK TIME")
        assertTrue(micro != null && micro.contains("microwave"))
    }

    // ── Speech plan (the interim voice) ─────────────────────────────

    @Test fun speechSkipsTheAttributionParenthetical() {
        val lines = listOf(
            "I'll read it aloud, then explain it in plain words.",
            "Here is everything it says, as written: Dear Mrs Alvarez.",
            "(Answered by: On this phone (ML Kit).)",
        )
        val utterances = SpeechPlan.utterances(lines)
        assertTrue(utterances.none { it.contains("Answered by") })
        assertTrue(utterances.any { it.contains("Dear Mrs Alvarez") })
        assertTrue(SpeechPlan.shouldAutoSpeak(true, lines))
        assertFalse(SpeechPlan.shouldAutoSpeak(false, lines))
        assertFalse(SpeechPlan.shouldAutoSpeak(true, listOf("(Answered by: On this phone (ML Kit).)")))
    }

    @Test fun speechChunksLongReadingsOnSentenceBoundaries() {
        val sentence = "This is a plain sentence about your letter and your visit. "
        val long = sentence.repeat(60) // ~3,400 chars
        val utterances = SpeechPlan.utterances(listOf(long))
        assertTrue(utterances.size >= 4)
        assertTrue(utterances.all { it.length <= SpeechPlan.MAX_UTTERANCE_CHARS })
        assertEquals(long.trim(), utterances.joinToString(" "))
    }
}
