package com.app.nuri.speech

/**
 * KwsKeywords — the wake phrase for the sherpa-onnx KWS spotter.
 *
 * VERIFIED LINES (2026-10-19): the gigaspeech pack's own bpe.model
 * (sentencepiece — the same algorithm as sherpa's text2token) encodes
 *   "HEY ILONA" → ▁HE Y ▁I LO N A      "ILONA" → ▁I LO N A
 * and the method reproduces the pack's shipped keywords.txt line for
 * "HEY SIRI" (▁HE Y ▁S I RI) exactly. These canonical lines are what
 * the service writes — a BPE keyword must match the segmentation the
 * MODEL emits, and greedy longest-match segmentation is NOT BPE
 * (it can pick ▁HEY-style merges the encoder never produces).
 *
 * The greedy [compose] below survives only as a last-resort fallback
 * for a hypothetical future pack whose vocabulary lacks the verified
 * tokens; the service checks the verified lines against the pack's
 * tokens.txt first and uses them whenever they fit.
 */
object KwsKeywords {

    /** The canonical BPE line for "hey ilona" in the gigaspeech pack. */
    const val HEY_ILONA_LINE = "▁HE Y ▁I LO N A"

    /** The canonical BPE line for "ilona" alone (fallback phrase). */
    const val ILONA_LINE = "▁I LO N A"

    /** Verified (phrase, line) pairs, best first. */
    val VERIFIED: List<Pair<String, String>> = listOf(
        "hey ilona" to HEY_ILONA_LINE,
        "ilona" to ILONA_LINE
    )

    /** True when every token of [line] exists in [vocab]. */
    fun lineFits(line: String, vocab: Set<String>): Boolean =
        line.split(" ").all { it in vocab }

    /** Parse tokens.txt ("<token> <id>" per line) into the vocabulary. */
    fun vocabulary(tokensTxt: String): Set<String> =
        tokensTxt.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringBeforeLast(' ') }
            .filter { it.isNotEmpty() && !it.startsWith("<") }
            .toSet()

    /**
     * Segment [phrase] (e.g. "hey ilona") into model tokens.
     * Returns the space-joined token line, or null if any part of
     * the phrase can't be spelled by [vocab].
     */
    fun compose(phrase: String, vocab: Set<String>): String? {
        // Model convention: each word starts with the ▁ marker; the
        // letter string to segment is "▁HEY▁ILONA" for "hey ilona".
        val target = phrase.uppercase().trim()
            .split(Regex("\\s+"))
            .joinToString("") { "▁$it" }
        if (target.isEmpty()) return null
        val byLengthDesc = vocab.filter { it.length <= target.length }.sortedByDescending { it.length }
        val out = mutableListOf<String>()
        var rest = target
        while (rest.isNotEmpty()) {
            val next = byLengthDesc.firstOrNull { rest.startsWith(it) } ?: return null
            out += next
            rest = rest.substring(next.length)
        }
        return out.joinToString(" ")
    }

    /** The phrases to try, best first: the full "hey ilona", then "ilona". */
    fun candidates(): List<String> = listOf("hey ilona", "ilona")
}
