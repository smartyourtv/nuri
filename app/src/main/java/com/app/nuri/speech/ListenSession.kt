package com.app.nuri.speech

/**
 * The conversation session pattern (phone Scout → laptop Scout →
 * here; lessons LL6 + V1 in files/nuri-voice-lessons-applied.md):
 *
 *  - Wake once ("Hey Ilona"), then talk naturally — no wake word
 *    per sentence while the session is open.
 *  - The follow-up window is 90 seconds from the last activity.
 *  - "Keep listening" / "stay with me" holds the session for
 *    10 minutes.
 *  - "That's all" (or a goodbye) ends the session politely.
 *  - A WAKE HANGOVER of 800 ms follows every wake hit: the machine
 *    reports the session as freshly woken during it, and the
 *    capture layer must not endpoint inside it (the laptop lesson:
 *    a hangover that chops the wake phrase chops the first word).
 *
 * Pure Kotlin with explicit time parameters — unit-tested without
 * clocks, threads, or Android.
 */
object SessionPhrases {
    private fun norm(text: String): String =
        text.lowercase().trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")

    fun isHoldPhrase(text: String): Boolean {
        val t = norm(text)
        return t in setOf("keep listening", "stay with me", "keep listening please", "stay with me please") ||
            t.contains("keep listening") || t.contains("stay with me")
    }

    fun isEndPhrase(text: String): Boolean {
        val t = norm(text)
        return t in setOf(
            "that's all", "that is all", "thats all", "goodbye ilona", "good bye ilona",
            "good night ilona", "stop listening", "that's everything", "that is everything"
        )
    }
}

class ListenSessionMachine(
    private val followUpMs: Long = FOLLOWUP_MS,
    private val holdMs: Long = HOLD_MS
) {
    enum class State { IDLE, SESSION, HOLD }

    var state: State = State.IDLE
        private set

    /** When the current wake happened (hangover is measured from here). */
    var wokenAtMs: Long = -1L
        private set

    /** The session closes at this time unless activity extends it. */
    var closesAtMs: Long = -1L
        private set

    fun onWake(nowMs: Long) {
        state = State.SESSION
        wokenAtMs = nowMs
        closesAtMs = nowMs + followUpMs
    }

    /** Any understood activity (an utterance, a reply spoken) extends the window. */
    fun onActivity(nowMs: Long) {
        if (state == State.SESSION) closesAtMs = nowMs + followUpMs
        // In HOLD the hold deadline stands — activity doesn't shorten or lengthen it.
    }

    /** Returns true when [text] was a session-control phrase (and was consumed). */
    fun onPhrase(text: String, nowMs: Long): Boolean {
        if (state == State.IDLE) return false
        return when {
            SessionPhrases.isHoldPhrase(text) -> {
                state = State.HOLD
                closesAtMs = nowMs + holdMs
                true
            }
            SessionPhrases.isEndPhrase(text) -> {
                end()
                true
            }
            else -> false
        }
    }

    /** Time passing. Returns true if this tick closed the session. */
    fun tick(nowMs: Long): Boolean {
        if (state != State.IDLE && closesAtMs >= 0 && nowMs >= closesAtMs) {
            end()
            return true
        }
        return false
    }

    fun end() {
        state = State.IDLE
        closesAtMs = -1L
    }

    fun isOpen(): Boolean = state != State.IDLE

    /** Inside the post-wake hangover: capture must not endpoint yet (V1). */
    fun inWakeHangover(nowMs: Long): Boolean =
        wokenAtMs >= 0 && nowMs - wokenAtMs < WAKE_HANGOVER_MS

    companion object {
        const val FOLLOWUP_MS: Long = 90_000L
        const val HOLD_MS: Long = 600_000L
        const val WAKE_HANGOVER_MS: Long = 800L
    }
}
