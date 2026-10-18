package com.app.nuri.shownuri

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Show Nuri guardrails, quality coaching, composers, QR safety (spec E). */
class ShowNuriGuardTest {
    @Test fun loosePillIsRefusedAndRouted() {
        val r = ShowNuriGuard.decide(PhotoSubject.LOOSE_PILL, "the pharmacist")
        assertEquals(GuardrailDecision.REFUSE_AND_ROUTE, r.decision)
        assertTrue(r.line.contains("can't be named safely"))
    }

    @Test fun personIsDescribeOnlyNeverIdentified() {
        val r = ShowNuriGuard.decide(PhotoSubject.PERSON, "Maria")
        assertEquals(GuardrailDecision.DESCRIBE_ONLY, r.decision)
        assertTrue(r.line.contains("never identify people by their faces"))
    }

    @Test fun woundIsDescribeOnlyWithCareRouting() {
        val r = ShowNuriGuard.decide(PhotoSubject.BODY_OR_WOUND, "Maria")
        assertEquals(GuardrailDecision.DESCRIBE_ONLY, r.decision)
        assertTrue(r.line.contains("can't tell you what it is"))
    }

    @Test fun foodIsReadButNeverDeclaredSafe() {
        val r = ShowNuriGuard.decide(PhotoSubject.FOOD, "Maria")
        assertTrue(r.line.contains("never declare food safe"))
    }

    @Test fun qualityVerdicts() {
        assertEquals(ImageQuality.Verdict.TOO_DARK, ImageQuality.assess(0.05, 0.5))
        assertEquals(ImageQuality.Verdict.BLURRY, ImageQuality.assess(0.5, 0.02))
        assertEquals(ImageQuality.Verdict.GOOD, ImageQuality.assess(0.5, 0.5))
        assertEquals(ImageQuality.Verdict.UNKNOWN, ImageQuality.assess(null, null))
        assertTrue(ImageQuality.coachingLine(ImageQuality.Verdict.BLURRY)!!.contains("elbows"))
    }

    @Test fun letterSummaryFindsNotABill() {
        val text = "Medicare Summary Notice\nThis is not a bill\nYou were charged \$250.00 on October 3, 2026"
        val summary = ReadAloudComposer.letterSummary(text)
        assertTrue(summary.contains("not a bill"))
        assertTrue(summary.contains("Medicare"))
    }

    @Test fun expiryStatesWhichKindOfDate() {
        val useBy = ReadAloudComposer.expiryLine("Greek Yogurt USE BY 10/20/2026 keep refrigerated")!!
        assertTrue(useBy.contains("safety line"))
        assertTrue(useBy.contains("never declare food safe"))
        val bestBy = ReadAloudComposer.expiryLine("Crackers BEST BY 11/01/2026")!!
        assertTrue(bestBy.contains("taste"))
        assertEquals(null, ReadAloudComposer.expiryLine("no dates here at all"))
    }

    @Test fun identificationStatesConfidence() {
        val sure = ReadAloudComposer.identifyLine(listOf(VisionLabel("Television remote", 0.95f)), "Maria")
        assertTrue(sure.contains("looks like"))
        val unsure = ReadAloudComposer.identifyLine(listOf(VisionLabel("Plant", 0.3f)), "Maria")
        assertTrue(unsure.contains("won't guess"))
        assertTrue(unsure.contains("Maria"))
        val none = ReadAloudComposer.identifyLine(emptyList(), "Maria")
        assertTrue(none.contains("can't see that clearly"))
    }

    @Test fun qrPreviewReadsDestinationAndStopsPayments() {
        val menu = QrSafety.previewLine("https://cafe.example.com/menu")
        assertTrue(menu.contains("cafe.example.com"))
        val pay = QrSafety.previewLine("https://venmo.com/pay?x=1")
        assertTrue(pay.contains("Stop"))
        val wifi = QrSafety.previewLine("WIFI:T:WPA;S:HomeNet;;")
        assertTrue(wifi.contains("Wi-Fi"))
    }

    @Test fun subjectGuessIsConservative() {
        assertEquals(PhotoSubject.LOOSE_PILL, ShowNuriGuard.guessSubject("what is this pill", ""))
        assertEquals(PhotoSubject.BODY_OR_WOUND, ShowNuriGuard.guessSubject("", "photo of a rash on my arm"))
        assertEquals(PhotoSubject.TEXT_DOCUMENT, ShowNuriGuard.guessSubject("", "Dear Sir, your account…"))
    }
}
