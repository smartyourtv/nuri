package com.app.nuri.unstuck

/**
 * The universal help entry (Shawn, 2026-10-17) — both flavors.
 *
 * "Can you help me with this?" and its natural variants route straight
 * into the Unstuck / Show Nuri flow. The senior never has to know a
 * feature name, a helper name, or a catalog family: Nuri offers exactly
 * two paths in plain words —
 *   1) SAY what it is: he describes it in his own words; Nuri sorts out
 *      which helper it is and enters the solve ladder from there.
 *   2) TAKE A PICTURE: Show Nuri opens with the aiming coaching.
 * The big "I need help" button on the home screen lands on the same
 * two-path offer.
 */
object HelpEntry {
    /** Phrases that open the two-path help offer (matched as substrings, lower-cased). */
    val phrases: List<String> = listOf(
        "can you help me with this",
        "help me with this",
        "i need help with something",
        "i need help with this",
        "help me with something",
        "can you look at this",
        "will you look at this",
        "look at this for me",
        "can you help me",
        "help me please",
    )

    fun isHelpEntry(spokenText: String): Boolean {
        val t = spokenText.lowercase().trim().trimEnd('?', '!', '.')
        if (phrases.any { it in t }) return true
        // Tolerant path (Shawn 2026-10-17): intent, not the literal string.
        // "help" + a thing-word, or "look at" + a thing, fires the entry
        // even when the sentence comes out jumbled or half-finished.
        val tokens = t.split(Regex("[^a-z']+")).filter { it.isNotEmpty() }.toSet()
        val helpWord = "help" in tokens || "stuck" in tokens || "confused" in tokens
        val thingWord = tokens.any { it in setOf("this", "that", "something", "it", "thing") }
        val lookWord = "look" in tokens || "see" in tokens || "show" in tokens
        return (helpWord && thingWord) || (lookWord && thingWord && tokens.size <= 8)
    }

    /** The offer, in plain words — exactly two paths. */
    const val OFFER_LINE: String =
        "Of course. There are two ways we can do this. You can tell me what it is, in your own words — " +
            "or you can take a picture of it and show me. Which would you like?"

    const val SAY_PATH_LABEL: String = "I'll tell you"
    const val SAY_PATH_DETAIL: String = "Say it in your own words. I'll work out what it is."
    const val PHOTO_PATH_LABEL: String = "Take a picture"
    const val PHOTO_PATH_DETAIL: String = "Show me the thing, the letter, or the screen. I'll read it or tell you what it is."

    /** After he chooses SAY: the listening prompt. */
    const val DESCRIBE_PROMPT: String =
        "All right — tell me what's happening, in your own words. Take your time."

    /** After he describes it and no helper matches: the sort-it-out fallback. */
    const val NO_MATCH_LINE: String =
        "Thank you. I don't have a ready-made walk-through for that one, and I won't guess. " +
            "We can try a picture instead — or ask a person. Which shall we do?"
}
