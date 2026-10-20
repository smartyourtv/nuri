package com.app.nuri.remote

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The §7 honesty rules as pure functions: the caregiver view always
 * shows when information actually arrived, and after 24 hours of
 * silence it says so in plain words. There is no "live" claim
 * anywhere — freshness is only ever stated as a time.
 */
object RemoteFreshness {

    const val NO_WORD_THRESHOLD_MS: Long = 24L * 60 * 60 * 1000

    data class Lines(
        val lastUpdatedLine: String,
        val lastSeenLine: String,
        /** True when the §7 24-hour state is showing. */
        val noWord: Boolean,
        /** The plain-words explanation, present only when [noWord]. */
        val noWordLine: String?,
    )

    fun timeText(ms: Long): String =
        SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(ms))

    fun lines(status: RemoteStatus?, nowMs: Long): Lines {
        if (status == null || (status.updatedAtMs == null && status.lastSeenAtMs == null)) {
            return Lines(
                lastUpdatedLine = "No information has arrived from his phone yet.",
                lastSeenLine = "His phone has not been in touch yet.",
                noWord = false,
                noWordLine = null,
            )
        }
        val updated = status.updatedAtMs
        val seen = status.lastSeenAtMs
        val lastContact = maxOf(updated ?: 0L, seen ?: 0L)
        val noWord = lastContact > 0 && nowMs - lastContact >= NO_WORD_THRESHOLD_MS
        return Lines(
            lastUpdatedLine = updated?.let { "Last updated ${timeText(it)}" }
                ?: "No information has arrived from his phone yet.",
            lastSeenLine = seen?.let { "His phone was last in touch ${timeText(it)}" }
                ?: "His phone has not been in touch yet.",
            noWord = noWord,
            noWordLine = if (noWord) {
                "No word from his phone since ${timeText(lastContact)}. " +
                    "His phone may be off or without internet. " +
                    "This is the last information Ilona received."
            } else {
                null
            },
        )
    }

    /** The B1 "no word" guidance steps, shown only in the 24-hour state. Guidance only — no remote actions. */
    val NO_WORD_STEPS: List<String> = listOf(
        "Is his phone on? If you can, ask someone nearby to check that it is charged and switched on.",
        "Is it connected? A phone with no signal and no Wi-Fi cannot send anything until it connects again.",
        "Open Ilona on his phone once — that alone tells this phone he is alright, and anything waiting will arrive.",
    )
}

/** His phone's version + update state (order §B1), from the status/today metadata. */
enum class HisPhoneUpdateState { UP_TO_DATE, UPDATE_PENDING, UPDATE_FAILED, UNKNOWN }

object HisPhoneUpdate {

    fun state(status: RemoteStatus?): HisPhoneUpdateState {
        val text = status?.lastUpdateCheckText?.lowercase() ?: return HisPhoneUpdateState.UNKNOWN
        if (text.isBlank()) return HisPhoneUpdateState.UNKNOWN
        return when {
            "did not pass" in text || "needs a look" in text || "did not install" in text ->
                HisPhoneUpdateState.UPDATE_FAILED
            "being installed" in text || "has not finished" in text || "was started" in text ||
                "ready when you are" in text || "a newer ilona is ready" in text ->
                HisPhoneUpdateState.UPDATE_PENDING
            else -> HisPhoneUpdateState.UP_TO_DATE
        }
    }

    /** "His phone: Ilona 0.5.0 — up to date" style line for the panel. */
    fun line(status: RemoteStatus?): String {
        val name = status?.appVersionName
        val versionPart = when {
            name != null && status.appVersionCode != null -> "Ilona $name"
            name != null -> "Ilona $name"
            else -> return "His phone's version hasn't been reported yet."
        }
        return when (state(status)) {
            HisPhoneUpdateState.UP_TO_DATE -> "His phone: $versionPart — up to date"
            HisPhoneUpdateState.UPDATE_PENDING -> "His phone: $versionPart — update pending"
            HisPhoneUpdateState.UPDATE_FAILED -> "His phone: $versionPart — update failed (self-test result)"
            HisPhoneUpdateState.UNKNOWN -> "His phone: $versionPart"
        }
    }

    /** The self-test/update detail behind the state, when the senior phone reported one. */
    fun detail(status: RemoteStatus?): String? = status?.lastUpdateCheckText?.takeIf { it.isNotBlank() }
}

/**
 * The no-answer ladder (design §17.4), computed on the caregiver
 * phone from CALL events — the same local-computation pattern as
 * the missing check-in (§8.3). The ladder's ONLY actions are
 * notifying the caregiver and offering human steps; it can never
 * place a call itself, and it never dials emergency services.
 */
object NoAnswerLadder {

    const val DEFAULT_THRESHOLD = 2
    const val WINDOW_MS: Long = 60L * 60 * 1000

    /** Should the welfare-class alert fire, given all check-in-call events and the current raise state? */
    fun shouldRaiseWelfare(
        callEvents: List<RemoteEvent>,
        nowMs: Long,
        threshold: Int = DEFAULT_THRESHOLD,
        alreadyRaisedAtMs: Long? = null,
    ): Boolean {
        val noAnswers = callEvents.filter {
            it.type == RemoteEventType.CALL &&
                it.call?.kind == CallKind.CHECKIN &&
                it.call?.outcome == CallOutcome.NO_ANSWER &&
                it.createdAtMs > nowMs - WINDOW_MS &&
                (alreadyRaisedAtMs == null || it.createdAtMs > alreadyRaisedAtMs)
        }
        return noAnswers.size >= threshold.coerceAtLeast(1)
    }

    /** The human steps the panel offers when the ladder fires. Guidance on the caregiver's phone only. */
    val HUMAN_STEPS: List<String> = listOf(
        "Call his regular number yourself — sometimes the simplest check is the best one.",
        "Ask a family member or a neighbor to look in on him.",
        "If you are still worried, ask for a wellness check: call your local police non-emergency line, or 911 if you believe it is urgent. That call is always yours to make — Ilona never makes it for you.",
    )
}
