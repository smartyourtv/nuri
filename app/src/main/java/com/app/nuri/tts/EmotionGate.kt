package com.app.nuri.tts

import com.app.nuri.config.NuriSettings
import kotlin.random.Random

/**
 * EmotionGate — decides WHETHER an emotion sound may play, and which
 * one. Pure Kotlin, unit-tested; the audio side (June's bank, the
 * shaped renderer) only ever plays what this gate returns.
 *
 * The laws it encodes (files/nuri-voice-lessons-applied.md):
 *  - V10: URGENT and SERIOUS situations return NONE before anything
 *    else is consulted — a code gate, not a prompt's good intentions.
 *    No emotion level and no random seed can produce a sound there.
 *  - Breath law (2026-10-17): breaths fit the situation — light and
 *    quick when upbeat, soft and slow for empathy or serious news,
 *    breathy after laughter, and NO decorative breath during urgent
 *    or alert speech.
 *  - The emotion LEVEL (already Guided-clamped by the caller via
 *    EmotionProsody/TierDefaults) scales how much feeling shows,
 *    matching the ProsodyHints contract: CALM/GENTLE never laugh;
 *    WARM chuckles and can be surprised; EXPRESSIVE+ laughs fully.
 *  - V7: picks are random with the recent picks excluded — never the
 *    same sound twice in a row.
 */
object EmotionGate {

    /** What sort of moment this is. The reply pipeline tags it explicitly. */
    enum class Situation { NORMAL, LIGHT, FUNNY, COMFORT, SURPRISE, SERIOUS, URGENT }

    enum class SoundKind { CHUCKLE, LAUGH, FULL_LAUGH, SIGH, SURPRISE, BREATH_LIGHT, BREATH_SOFT, NONE }

    /** One catalog entry: a sound the audio layer can produce for a voice. */
    data class Entry(val id: String, val kind: SoundKind)

    /**
     * Decide the sound for [situation] at [level], choosing from
     * [catalog] (the entries available for the CURRENT voice) and
     * avoiding [recentIds] (most-recent last). Returns NONE when the
     * moment or the level forbids sound, or the catalog has nothing
     * suitable — silence is always an acceptable answer.
     */
    fun decide(
        situation: Situation,
        level: NuriSettings.EmotionLevel,
        catalog: List<Entry>,
        recentIds: List<String>,
        random: Random
    ): Entry? {
        // THE GATE (V10): first, unconditional, before level or chance.
        if (situation == Situation.URGENT || situation == Situation.SERIOUS) return null

        val wanted: SoundKind = when (situation) {
            Situation.FUNNY -> when (level) {
                NuriSettings.EmotionLevel.CALM,
                NuriSettings.EmotionLevel.GENTLE -> SoundKind.NONE
                NuriSettings.EmotionLevel.WARM -> SoundKind.CHUCKLE
                NuriSettings.EmotionLevel.EXPRESSIVE -> SoundKind.LAUGH
                NuriSettings.EmotionLevel.FULL_HEARTED -> SoundKind.FULL_LAUGH
            }
            Situation.LIGHT -> when (level) {
                NuriSettings.EmotionLevel.CALM -> SoundKind.NONE
                else -> SoundKind.CHUCKLE
            }
            Situation.COMFORT -> when (level) {
                NuriSettings.EmotionLevel.CALM -> SoundKind.BREATH_SOFT
                else -> SoundKind.SIGH
            }
            Situation.SURPRISE -> when (level) {
                NuriSettings.EmotionLevel.CALM,
                NuriSettings.EmotionLevel.GENTLE -> SoundKind.NONE
                else -> SoundKind.SURPRISE
            }
            Situation.NORMAL -> when (level) {
                NuriSettings.EmotionLevel.CALM,
                NuriSettings.EmotionLevel.GENTLE -> SoundKind.NONE
                // A single soft breath before a longer explanation, at
                // the warmer levels only — seasoning, never a pattern.
                else -> if (random.nextFloat() < 0.25f) SoundKind.BREATH_LIGHT else SoundKind.NONE
            }
            Situation.URGENT, Situation.SERIOUS -> SoundKind.NONE // unreachable; the gate returned
        }
        if (wanted == SoundKind.NONE) return null

        val exact = catalog.filter { it.kind == wanted }
        val pool = if (exact.isNotEmpty()) exact else fallbackPool(wanted, catalog)
        if (pool.isEmpty()) return null
        val fresh = pool.filter { it.id !in recentIds }
        val choices = if (fresh.isNotEmpty()) fresh else pool
        return choices[random.nextInt(choices.size)]
    }

    /**
     * Near-kind fallbacks keep a moment graceful when the exact kind
     * isn't available for a voice (e.g. a voice with chuckles but no
     * full laugh). Fallbacks only ever step DOWN in intensity.
     */
    private fun fallbackPool(wanted: SoundKind, catalog: List<Entry>): List<Entry> {
        val chain: List<SoundKind> = when (wanted) {
            SoundKind.FULL_LAUGH -> listOf(SoundKind.LAUGH, SoundKind.CHUCKLE)
            SoundKind.LAUGH -> listOf(SoundKind.CHUCKLE)
            SoundKind.SURPRISE -> listOf(SoundKind.CHUCKLE)
            SoundKind.SIGH -> listOf(SoundKind.BREATH_SOFT)
            SoundKind.BREATH_SOFT -> listOf(SoundKind.BREATH_LIGHT)
            else -> emptyList()
        }
        return chain.flatMap { kind -> catalog.filter { it.kind == kind } }
    }

    /**
     * The breath that fits AFTER a laugh (breathy, light) vs before
     * a serious explanation (a deeper reset) — the situation-fit rule
     * for the breaths the speech pipeline inserts between sentences.
     * Urgent speech gets NO breath at all.
     */
    fun breathBetween(afterLaughter: Boolean, serious: Boolean, urgent: Boolean, random: Random): SoundKind {
        if (urgent) return SoundKind.NONE
        if (serious) return SoundKind.BREATH_SOFT
        if (afterLaughter) return SoundKind.BREATH_LIGHT
        return if (random.nextFloat() < 0.3f) SoundKind.BREATH_LIGHT else SoundKind.NONE
    }
}
