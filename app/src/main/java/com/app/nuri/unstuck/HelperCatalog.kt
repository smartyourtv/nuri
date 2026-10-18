package com.app.nuri.unstuck

/** How a catalog situation is served in this build — honest classification. */
enum class HelperMode {
    /** Nuri checks and, where a safe fix exists, applies it; otherwise guides. */
    AUTO_CHECK,
    /** A one-step-at-a-time walk-through with an "I'm stuck" escape. */
    GUIDED,
    /** The right answer is a person: Nuri comforts and offers the ASK rung. */
    ASK_ONLY,
    /** Safety script (emergency / fraud in progress): handled live per standing alert rules. */
    ESCALATE,
    /** Needs a capability this build does not have; the helper says so and offers ASK. */
    DEFERRED,
}

/** Which check-first read a helper performs before anything else. */
enum class CheckKey {
    NONE, SOUND, DND, CONNECTIVITY, BATTERY, STORAGE, TALKBACK, TEXT_SIZE,
    FLASHLIGHT, MED_LOG, SAFE_WORD, ALARM, SPOUSE, SCAM_TEXT, LOCATION,
}

/**
 * One catalog situation (spec of record:
 * files/nuri-unstuck-expanded-catalog.md, 98 situations / 23 families).
 * [number] is the catalog entry number (1–98).
 */
data class HelperDef(
    val number: Int,
    val family: String,
    val title: String,
    val mode: HelperMode,
    val checkKey: CheckKey = CheckKey.NONE,
    val triggers: List<String> = emptyList(),
    /** Walk-through steps, one at a time. First step is spoken first. */
    val steps: List<String> = emptyList(),
    /** For DEFERRED: the plain reason, spoken honestly. */
    val deferredReason: String? = null,
    /** Extra opening line for ASK_ONLY helpers (comfort/context first). */
    val openingLine: String? = null,
)
