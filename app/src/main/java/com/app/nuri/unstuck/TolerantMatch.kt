package com.app.nuri.unstuck

/**
 * Tolerant intent matching (Shawn, 2026-10-17): "Take into consideration
 * language may be off because of confusion — questions and phrases may
 * come out odd."
 *
 * Rules implemented here:
 *  1. NO exact-phrase dependence. Matching is token-based: keywords in
 *     any order, common wrong-word substitutions (TV/television,
 *     pills/medicine/tablets), half-finished sentences, and topic-only
 *     fragments ("the… the blue thing… the pills") all count.
 *  2. MIRROR before acting on a shaky guess: a fair-confidence match is
 *     read back in plain words ("It sounds like… is that the one?") and
 *     he confirms or corrects. Only strong matches — and trivially safe
 *     AUTO fixes — proceed without the mirror.
 *  3. NEVER dead-end. No flat "I don't understand", ever (see
 *     [NoMatchLadder]). Same question → same answer, every time (this
 *     matcher is deterministic). No grammar correction, no frustration,
 *     no repetition penalties anywhere in the ladder.
 */
object TolerantMatch {
    private val stopwords = setOf(
        "the", "a", "an", "is", "it", "i", "my", "me", "to", "of", "and", "or", "in", "on", "at",
        "this", "that", "with", "for", "was", "are", "be", "been", "do", "did", "does", "have",
        "has", "had", "am", "im", "i'm", "its", "it's", "so", "just", "now", "again", "um", "uh",
        "the\u2026", "…",
    )

    /** Common substitutions a confused speaker makes — both directions. */
    private val synonyms: Map<String, Set<String>> = mapOf(
        "tv" to setOf("television", "telly"),
        "television" to setOf("tv", "telly"),
        "pills" to setOf("medicine", "medication", "tablets", "meds", "pill"),
        "medicine" to setOf("pills", "medication", "tablets", "meds"),
        "medication" to setOf("pills", "medicine", "tablets", "meds"),
        "tablets" to setOf("pills", "medicine", "medication"),
        "pill" to setOf("pills", "medicine", "tablet"),
        "photo" to setOf("picture", "photograph"),
        "picture" to setOf("photo", "photograph"),
        "mail" to setOf("letter", "letters"),
        "letter" to setOf("mail", "letters"),
        "doc" to setOf("doctor"),
        "doctor" to setOf("doc", "clinic"),
        "loud" to setOf("volume", "sound"),
        "volume" to setOf("loud", "sound"),
        "sound" to setOf("volume", "hear", "audio"),
        "hear" to setOf("sound", "listen"),
        "ring" to setOf("ringing", "rings", "beep", "beeps"),
        "beep" to setOf("beeping", "ring", "chirp", "chirping"),
        "beeping" to setOf("beep", "chirping"),
        "lost" to setOf("gone", "missing", "can't find", "cannot find"),
        "gone" to setOf("lost", "missing"),
        "missing" to setOf("lost", "gone"),
        "dog" to setOf("pet"),
        "cat" to setOf("pet"),
        "pet" to setOf("dog", "cat"),
        "husband" to setOf("spouse", "wife"),
        "wife" to setOf("spouse", "husband"),
        "grandson" to setOf("grandchild", "granddaughter"),
        "granddaughter" to setOf("grandchild", "grandson"),
        "grandchild" to setOf("grandson", "granddaughter"),
        "money" to setOf("pay", "paid", "bill", "cash"),
        "bill" to setOf("money", "pay"),
        "dark" to setOf("black", "dim"),
        "black" to setOf("dark", "blank"),
        "words" to setOf("text", "letters", "writing"),
        "text" to setOf("words", "message"),
        "big" to setOf("huge", "large"),
        "tiny" to setOf("small", "little"),
        "small" to setOf("tiny", "little"),
    )

    fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9' ]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() && it !in stopwords }

    /** Input tokens expanded with synonyms (a set for membership checks). */
    fun expandedTokens(text: String): Set<String> {
        val base = tokenize(text)
        val out = base.toMutableSet()
        for (tok in base) synonyms[tok]?.let { out += it }
        // Two-word synonym values ("can't find") handled by the raw-substring pass in scoring.
        return out
    }

    /**
     * Expanded tokens WITH provenance: expanded token → the raw spoken
     * token it came from (itself for an exact word). Scoring uses this so
     * ONE spoken word cannot satisfy TWO different trigger words through
     * its synonym fan-out (saying "money" once must not fully match both
     * "pay" and "bill" in an unrelated trigger).
     */
    fun expandedTokenSources(text: String): Map<String, String> {
        val base = tokenize(text)
        val out = mutableMapOf<String, String>()
        for (tok in base) out.putIfAbsent(tok, tok)
        for (tok in base) synonyms[tok]?.forEach { syn -> out.putIfAbsent(syn, tok) }
        return out
    }

    enum class Confidence { STRONG, FAIR, NONE }

    data class Match(val helper: HelperDef, val score: Double, val confidence: Confidence) {
        /** The mirror line for FAIR matches — plain words, confirmable. */
        fun mirrorLine(): String =
            "It sounds like this might be it: ${helper.title}. Is that the one?"
    }

    private fun scoreTrigger(sources: Map<String, String>, rawTokenCount: Int, rawInput: String, trigger: String): Double {
        val triggerTokens = tokenize(trigger)
        if (triggerTokens.isEmpty()) return 0.0
        if (trigger.lowercase() in rawInput) return 1.0 // full phrase said — strongest signal
        var weighted = 0.0
        var anyHit = false
        var rareExactHit = false
        val usedSources = mutableSetOf<String>()
        for (tok in triggerTokens) {
            val src = sources[tok] ?: continue
            val exact = src == tok
            if (!exact && src in usedSources) continue // one spoken word, one synonym credit
            usedSources += src
            anyHit = true
            weighted += if (exact) 1.0 else 0.75 // exact words beat substituted words
            if (exact && tok.length >= 5) rareExactHit = true
        }
        var score = weighted / triggerTokens.size
        // Topic-only fragments: a short utterance that nails one of the
        // trigger's rare keywords exactly is worth more than the raw
        // fraction suggests. (Raw token count — not the expanded set.)
        if (rawTokenCount <= 4 && anyHit && triggerTokens.size >= 3 && rareExactHit) {
            score = maxOf(score, 0.55)
        }
        return score
    }

    /** Best match + runners-up (for the never-dead-end choice list). */
    fun matchAll(spokenText: String, helpers: List<HelperDef>): List<Match> {
        val raw = spokenText.lowercase()
        val sources = expandedTokenSources(spokenText)
        val rawTokenCount = tokenize(spokenText).size
        if (sources.isEmpty() && raw.isBlank()) return emptyList()
        return helpers.map { def ->
            val best = def.triggers.maxOfOrNull { scoreTrigger(sources, rawTokenCount, raw, it) } ?: 0.0
            Match(def, best, when {
                best >= 0.8 -> Confidence.STRONG
                best >= 0.45 -> Confidence.FAIR
                else -> Confidence.NONE
            })
        }.filter { it.score >= 0.25 }.sortedByDescending { it.score }.take(3)
    }

    fun match(spokenText: String, helpers: List<HelperDef>): Match? =
        matchAll(spokenText, helpers).firstOrNull { it.confidence != Confidence.NONE }
}

/**
 * The never-dead-end ladder, in order (Shawn, 2026-10-17): one narrowing
 * question → 2–3 likely choices in plain words → the Show Nuri picture
 * path → the ASK rung. Nuri never says a flat "I don't understand."
 */
object NoMatchLadder {
    const val NARROWING_QUESTION: String =
        "All right, let's narrow it down together. Is it about the phone, the television, a letter or bill, or something in the house?"

    fun choiceLine(titles: List<String>): String =
        "Here are the closest things I can help with: ${titles.joinToString("; ")}. Tap the one that's closest — or tap 'Take a picture' and show me, or we'll ask a person. All three are fine."

    const val PHOTO_PATH_LINE: String =
        "Or you can show me — tap 'Take a picture' and point the phone at the thing. I'll read it or tell you what it is."

    /** Most-likely helpers when nothing matched at all (the evergreen confusions). */
    fun evergreenChoices(): List<HelperDef> = listOf(
        HelperCatalogDataB.combined.first { it.number == 70 }, // TalkBack rescue
        HelperCatalogDataB.combined.first { it.number == 9 },  // Did I take my pills?
        HelperCatalogDataB.combined.first { it.number == 57 }, // Mid-call accidents
        HelperCatalogDataB.combined.first { it.number == 1 },  // TV black screen
    )
}
