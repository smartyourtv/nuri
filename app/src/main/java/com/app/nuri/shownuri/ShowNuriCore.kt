package com.app.nuri.shownuri

/**
 * Show Nuri — pure logic (catalog Part C + the SHOW NURI preamble).
 *
 * Vision route (Shawn, 2026-10-17 22:13 PDT): NO on-device-first
 * requirement — Nuri identifies in any way necessary, best available
 * route, freely combined. This build's working route is on-device ML Kit
 * OCR + image labeling; the [VisionGateway] seam is where a cloud route
 * plugs in when the app gains one (none is configured in this build —
 * stated plainly, never faked).
 *
 * Guardrails are HARD and live here, not in UI text:
 *  - never diagnose from a photo (describe/read words only, route to a person);
 *  - never declare food, plants, or medication safe (doubt rule);
 *  - NO loose-pill identification or advice, ever;
 *  - NO face identification (describe + read captions only);
 *  - confidence honesty: unsure → say so → ASK rung, with the photo.
 */
enum class PhotoSubject { TEXT_DOCUMENT, PILL_BOTTLE_LABEL, LOOSE_PILL, FOOD, PERSON, BODY_OR_WOUND, OBJECT, UNKNOWN }

enum class GuardrailDecision { READ_ALOUD, DESCRIBE_ONLY, IDENTIFY_WITH_CONFIDENCE, REFUSE_AND_ROUTE }

data class GuardrailResult(val decision: GuardrailDecision, val line: String)

object ShowNuriGuard {
    fun decide(subject: PhotoSubject, askTarget: String): GuardrailResult = when (subject) {
        PhotoSubject.LOOSE_PILL -> GuardrailResult(
            GuardrailDecision.REFUSE_AND_ROUTE,
            "A loose pill can't be named safely from a picture, and guessing with medicine is how people get hurt. " +
                "It goes back in the drawer until we ask the pharmacy — or ask $askTarget. I won't guess at this one.",
        )
        PhotoSubject.BODY_OR_WOUND -> GuardrailResult(
            GuardrailDecision.DESCRIBE_ONLY,
            "I can describe what I can see in plain words, but I can't tell you what it is or what to do about it — " +
                "that's for a clinician or $askTarget. If it's bleeding heavily, you're in pain, or it looks worse fast, that's a call-now moment, not a photo moment.",
        )
        PhotoSubject.PERSON -> GuardrailResult(
            GuardrailDecision.DESCRIBE_ONLY,
            "I can describe the photo — the people, the place, the moment — and read any writing on it, " +
                "but I never identify people by their faces. Names come from captions or from family, never from a face.",
        )
        PhotoSubject.FOOD -> GuardrailResult(
            GuardrailDecision.READ_ALOUD,
            "I'll read the date and the storage words aloud. I never declare food safe to eat — " +
                "a passed 'use by' date, a broken seal, or a doubtful smell from you means: when in doubt, throw it out.",
        )
        PhotoSubject.PILL_BOTTLE_LABEL -> GuardrailResult(
            GuardrailDecision.READ_ALOUD,
            "I'll read the label exactly as written — the name, the directions on the label, the refill line. " +
                "Reading a label isn't advice: I never change doses or say whether to take something. That's the pharmacist's call.",
        )
        PhotoSubject.TEXT_DOCUMENT -> GuardrailResult(GuardrailDecision.READ_ALOUD, "I'll read it aloud, then explain it in plain words.")
        PhotoSubject.OBJECT -> GuardrailResult(GuardrailDecision.IDENTIFY_WITH_CONFIDENCE, "I'll tell you what it looks like and what it's for — and how sure I am.")
        PhotoSubject.UNKNOWN -> GuardrailResult(GuardrailDecision.IDENTIFY_WITH_CONFIDENCE, "I'll do my best, and if I'm not sure, I'll say so plainly.")
    }

    /** Heuristic subject guess from what he said + OCR text. Conservative: doubt → stricter subject. */
    fun guessSubject(spokenText: String, ocrText: String): PhotoSubject {
        val t = (spokenText + " " + ocrText).lowercase()
        return when {
            "loose pill" in t || "what pill is this" in t || "what is this pill" in t -> PhotoSubject.LOOSE_PILL
            "wound" in t || "rash" in t || "cut on" in t || "bruise" in t || "skin" in t -> PhotoSubject.BODY_OR_WOUND
            "who is" in t && ("photo" in t || "picture" in t) -> PhotoSubject.PERSON
            "rx" in t || "pharmacy" in t && "refill" in t || "take one" in t && "mouth" in t -> PhotoSubject.PILL_BOTTLE_LABEL
            "use by" in t || "best by" in t || "expires" in t || "exp " in t -> PhotoSubject.FOOD
            ocrText.isNotBlank() -> PhotoSubject.TEXT_DOCUMENT
            else -> PhotoSubject.OBJECT
        }
    }
}

/** Aiming coaching + retake rules (preamble: one fix named, kindly, never a failure of his). */
object ImageQuality {
    enum class Verdict { GOOD, TOO_DARK, BLURRY, UNKNOWN }

    /** meanLuminance 0..1; sharpnessScore = relative edge energy 0..1 (platform-measured). */
    fun assess(meanLuminance: Double?, sharpnessScore: Double?): Verdict = when {
        meanLuminance != null && meanLuminance < 0.12 -> Verdict.TOO_DARK
        sharpnessScore != null && sharpnessScore < 0.08 -> Verdict.BLURRY
        meanLuminance == null && sharpnessScore == null -> Verdict.UNKNOWN
        else -> Verdict.GOOD
    }

    fun coachingLine(verdict: Verdict): String? = when (verdict) {
        Verdict.GOOD -> null
        Verdict.TOO_DARK -> "That one's too dark for me to read. Move it closer to a lamp or a window — and hold it about a foot away. Let's try once more."
        Verdict.BLURRY -> "That one came out blurry. Rest your elbows on the table to steady the phone, hold it about a foot away, and hold still for one breath after you tap. Once more."
        Verdict.UNKNOWN -> "I can't quite make that out. A little more light, hold it flat and steady — and if it still won't come clear, we can ask a person with the photo."
    }

    val AIMING_INTRO: String =
        "Hold the phone about a foot away from it. More light helps — face a window or a lamp. " +
            "Lay papers flat. Hold steady… that's it. Tap the big button when you're ready."
}

/** A label candidate from the vision route, with its confidence 0..1. */
data class VisionLabel(val text: String, val confidence: Float)

data class VisionResult(
    val ocrText: String,
    val labels: List<VisionLabel>,
    /** QR/barcode payload if one was found in the frame, else null. */
    val qrPayload: String? = null,
    /** Where the answer came from, for the honest route note. */
    val routeName: String,
)

/**
 * The vision seam. Implementations may combine routes freely (Shawn's
 * 22:13 decision). A route that isn't configured returns null capability
 * via [isAvailable] — the UI then says so, never fakes a result.
 */
interface VisionGateway {
    val routeName: String
    fun isAvailable(): Boolean
    /** Blocking call — invoke off the main thread. Null = the route could not read this image. */
    fun analyze(imageBytes: ByteArray): VisionResult?
}

/** Composes what Nuri says about a photo: ID with stated confidence, read-aloud, plain summary. */
object ReadAloudComposer {
    const val CONFIDENT: Float = 0.75f
    const val UNSURE: Float = 0.5f

    /**
     * Scene/category labels (v0.3.2 — Shawn's remote test, 2026-10-18).
     * ML Kit's labeler happily returns scene categories — "Space",
     * "Room", "Building" — for ordinary objects (an LG TV remote was
     * answered "a space"). A scene word is NOT an object identification
     * and must never be presented as one, at ANY confidence the
     * labeler reports. These are filtered out before identification.
     */
    val NON_OBJECT_LABELS: Set<String> = setOf(
        "space", "room", "building", "buildings", "structure", "architecture",
        "sky", "cloud", "clouds", "landscape", "scenery", "nature",
        "interior", "exterior", "indoors", "outdoors", "darkness", "light",
        "shadow", "pattern", "texture", "text", "font", "illustration",
        "art", "design", "event", "recreation", "leisure", "screenshot",
        "photograph", "photo", "image", "selfie", "smile", "people",
        "person", "human", "crowd", "night", "daytime", "sunset", "sunrise",
    )

    /** Labels that could actually name the OBJECT in the photo. */
    fun objectLabels(labels: List<VisionLabel>): List<VisionLabel> =
        labels.filter { it.text.trim().lowercase() !in NON_OBJECT_LABELS }

    /**
     * Identification from labels — v0.3.2 contract: an object label is
     * only ever STATED at high confidence. Below that, Nuri says she
     * can't tell yet and offers a closer photo; she never hedges a
     * guess aloud ("might be a building" is how trust dies).
     */
    fun identifyLine(labels: List<VisionLabel>, askTarget: String): String =
        confidentObjectLine(labels) ?: cantTellLine(askTarget)

    /** The confident object statement, or null when no object label earns one. */
    fun confidentObjectLine(labels: List<VisionLabel>): String? {
        val top = objectLabels(labels).maxByOrNull { it.confidence } ?: return null
        return if (top.confidence >= CONFIDENT) {
            "That looks like ${article(top.text)} ${top.text.lowercase()}."
        } else {
            null
        }
    }

    fun cantTellLine(askTarget: String): String =
        "I can't tell what this is yet — a closer photo may help. Or shall I ask $askTarget with this photo?"

    fun unsureLine(askTarget: String): String =
        "I can't see that clearly enough to be sure, and I won't guess. Try holding it closer to the light — or shall I ask $askTarget with this photo?"

    /**
     * Identification from the WORDS in the photo (v0.3.2). When the
     * labeler offers only scene categories but the photo carries
     * readable product words, the honest answer is built from that
     * text evidence — a remote's buttons name it better than any
     * scene label. Returns null when the words don't name anything
     * this table knows; callers fall back honestly, never to a scene
     * word. The table is deliberately small and evidence-based: it
     * fires only on several strong product words together.
     */
    fun identifyFromOcr(ocrText: String): String? {
        val upper = ocrText.uppercase()
        val words = upper.split(Regex("[^A-Z0-9]+")).filter { it.isNotBlank() }.toSet()
        fun anyOf(vararg w: String) = w.any { it in words }
        fun countOf(vararg w: String) = w.count { it in words }
        val brands = listOf("LG", "SAMSUNG", "SONY", "VIZIO", "TCL", "PHILIPS", "PANASONIC", "SHARP", "TOSHIBA", "HISENSE", "ROKU", "FIRE TV", "APPLE")
        val brand = brands.firstOrNull { it in upper }
        // TV remote: streaming-service buttons and/or VOL+CH clusters.
        val remoteSignals = countOf("NETFLIX", "HULU", "AMAZON", "VOL", "CH", "GUIDE", "INPUT", "SOURCE", "MUTE", "REC", "EXIT", "BACK")
        if ((anyOf("NETFLIX", "HULU") && remoteSignals >= 3) || (countOf("VOL", "CH") == 2 && remoteSignals >= 3)) {
            val brandPart = brand?.let { " It's an $it remote." } ?: ""
            return "The words on it — the buttons say VOL, CH, and Guide — are the words on a television remote control.$brandPart"
        }
        // Microwave oven: cooking-function words together.
        if (countOf("POPCORN", "DEFROST", "POTATO", "BEVERAGE", "REHEAT", "COOK") >= 2) {
            return "The words on it are cooking words — popcorn, defrost, cook. This is the front of a microwave oven."
        }
        return null
    }

    private fun article(word: String): String =
        if (word.firstOrNull()?.lowercaseChar() in listOf('a', 'e', 'i', 'o', 'u')) "an" else "a"

    /**
     * The letter flow: read the sender line first, then the three
     * questions (sender / ask / date), and the not-a-bill find.
     */
    fun letterSummary(ocrText: String): String {
        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return "I couldn't read any words in that photo. Let's try again with more light, holding it flat and steady."
        val parts = mutableListOf<String>()
        if (ocrText.contains("this is not a bill", ignoreCase = true)) {
            parts += "Good news first: this letter says right on it — 'this is not a bill.' It's a report, not a charge."
        }
        parts += "It's from ${lines.first().take(80)}."
        val total = Regex("""\$\s?[\d,]+\.?\d{0,2}""").find(ocrText)?.value
        if (total != null) parts += "The amount shown is $total — remember, a shown amount on a report isn't always something you owe."
        val date = Regex("""\b(?:January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{1,2}(?:st|nd|rd|th)?,?\s+\d{4}\b""").find(ocrText)?.value
            ?: Regex("""\b\d{1,2}/\d{1,2}/\d{2,4}\b""").find(ocrText)?.value
        if (date != null) parts += "A date that matters on it: $date."
        parts += "I'll read the whole letter aloud now, slowly. Say 'stop' any time."
        return parts.joinToString(" ")
    }

    /** Expiry reading — states which kind of date it is; never declares food safe. */
    fun expiryLine(ocrText: String): String? {
        val t = ocrText.lowercase()
        val dateMatch = Regex("""\b\d{1,2}/\d{1,2}/\d{2,4}\b""").find(ocrText)?.value
            ?: Regex("""\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2},?\s+\d{4}\b""").find(ocrText)?.value
            ?: return null
        val kind = when {
            "use by" in t || "use-by" in t -> "a 'use by' date — that's the safety line"
            "best by" in t || "best if used by" in t -> "a 'best by' date — that's about taste and quality, not the safety line"
            "sell by" in t -> "a 'sell by' date — that's for the store, not for you"
            "exp" in t -> "an expiration date"
            else -> "a date — the package doesn't say which kind near it"
        }
        return "The date printed is $dateMatch, and it's $kind. If a 'use by' date has passed, or the seal is broken — when in doubt, throw it out. I read dates; I never declare food safe."
    }
}

/**
 * What the interim platform voice actually says (v0.3.2). Pure logic:
 * the display lines minus the route-attribution parenthetical, packed
 * into utterances short enough for the platform TTS engine, split on
 * sentence boundaries so a stop tap never lands mid-thought more than
 * one sentence deep. The read-aloud toggle lives in Settings; when it
 * is off, nothing auto-speaks and "Listen again" still works.
 */
object SpeechPlan {
    const val MAX_UTTERANCE_CHARS: Int = 800

    fun utterances(displayLines: List<String>): List<String> {
        val speakable = displayLines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("(") }
        if (speakable.isEmpty()) return emptyList()
        val sentences = speakable.flatMap { line ->
            line.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        }
        val out = mutableListOf<String>()
        var current = StringBuilder()
        fun flush() { if (current.isNotEmpty()) { out += current.toString(); current = StringBuilder() } }
        for (sentence in sentences) {
            if (sentence.length > MAX_UTTERANCE_CHARS) {
                flush()
                var rest = sentence
                while (rest.length > MAX_UTTERANCE_CHARS) {
                    val cut = rest.lastIndexOf(' ', MAX_UTTERANCE_CHARS).takeIf { it > 0 } ?: MAX_UTTERANCE_CHARS
                    out += rest.substring(0, cut).trim()
                    rest = rest.substring(cut).trim()
                }
                if (rest.isNotEmpty()) current = StringBuilder(rest)
                continue
            }
            if (current.length + 1 + sentence.length > MAX_UTTERANCE_CHARS) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        flush()
        return out
    }

    fun shouldAutoSpeak(toggleOn: Boolean, displayLines: List<String>): Boolean =
        toggleOn && utterances(displayLines).isNotEmpty()
}

/** QR safety (catalog #82): read the destination aloud BEFORE anything is tapped. */
object QrSafety {
    fun previewLine(payload: String): String {
        val p = payload.trim()
        return when {
            p.startsWith("http", ignoreCase = true) -> {
                val host = p.substringAfter("://").substringBefore("/").substringBefore("?")
                when {
                    host.contains("paypal") || host.contains("venmo") || host.contains("cash.app") || "pay" in host ->
                        "Stop — this code leads to a payment page at $host. Money never moves by a code like this. We don't tap further; let's ask a person instead."
                    else -> "This code leads to $host. Read that name carefully — if it isn't the place you expected, we stop here. If it matches, you can tap to open it."
                }
            }
            p.startsWith("WIFI:", ignoreCase = true) -> "This code joins a Wi-Fi network. That's fine at a café or family home — the name it joins should match the place you're in."
            p.startsWith("tel:", ignoreCase = true) -> "This code wants to call a number: ${p.removePrefix("tel:")}. Only continue if that's a number you know."
            else -> "This code contains text, not a website. I'll read it aloud — and we don't act on anything in it that asks for money, codes, or secrecy."
        }
    }
}
