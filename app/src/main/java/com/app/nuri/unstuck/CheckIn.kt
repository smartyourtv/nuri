package com.app.nuri.unstuck

/**
 * Morning check-in (sweep #11) and welfare-check logic (sweep #16).
 * The check-in actually runs and records: sleep / mood / appetite answers
 * are stored, and the daily brief is composed from Nuri's own records.
 */
data class CheckInRecord(
    val dayKey: String,
    val timeText: String,
    /** "Well" / "Okay" / "Not well" */
    val sleep: String,
    /** "Good" / "Okay" / "Low" */
    val mood: String,
    /** "Yes" / "A little" / "Not yet" */
    val appetite: String,
)

object CheckIns {
    fun encode(list: List<CheckInRecord>): String =
        list.joinToString(";") { r ->
            listOf(r.dayKey, r.timeText, r.sleep, r.mood, r.appetite).joinToString("|") { it.replace("|", " ").replace(";", " ") }
        }

    fun decode(encoded: String): List<CheckInRecord> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split(";").mapNotNull { part ->
            val b = part.split("|"); if (b.size != 5) null else CheckInRecord(b[0], b[1], b[2], b[3], b[4])
        }
    }

    /**
     * The morning brief, from records: today's check-in state, medicines
     * due/confirmed, and anything the caregiver should hear about.
     */
    fun composeBrief(
        todayCheckIn: CheckInRecord?,
        medsDueToday: List<String>,
        medsConfirmedToday: List<String>,
        greetingName: String?,
    ): String {
        val parts = mutableListOf<String>()
        parts += if (greetingName.isNullOrBlank()) "Good morning." else "Good morning."
        if (todayCheckIn == null) {
            parts += "Let's do your morning check-in — it only takes a moment, and it helps your family know you're doing well."
        } else {
            parts += "You've already checked in today — thank you."
        }
        val unconfirmed = medsDueToday.filter { due -> medsConfirmedToday.none { it.equals(due, ignoreCase = true) } }
        when {
            medsDueToday.isEmpty() -> parts += "No medicines are on your list for this morning."
            unconfirmed.isEmpty() -> parts += "Your medicines for today are all confirmed. Well done."
            else -> parts += "Still to take today: ${unconfirmed.joinToString(", ")}. When you've taken one, tap 'I took my medicine' and I'll write it down."
        }
        return parts.joinToString(" ")
    }
}

/**
 * Welfare check (protection subset, 2026-10-15): if the phone has been
 * quiet far longer than usual during waking hours, Nuri prompts; no
 * response within the window → a caregiver alert record. This logic is
 * pure; the background prompting service is a separate gated piece.
 */
object WelfareCheck {
    const val QUIET_HOURS_THRESHOLD: Int = 6
    const val RESPONSE_WINDOW_MINUTES: Int = 30

    fun shouldPrompt(hoursSinceLastInteraction: Int, isWakingHour: Boolean, alreadyPromptedToday: Boolean): Boolean =
        isWakingHour && !alreadyPromptedToday && hoursSinceLastInteraction >= QUIET_HOURS_THRESHOLD

    fun shouldAlert(promptSentMinutesAgo: Int, responded: Boolean): Boolean =
        !responded && promptSentMinutesAgo >= RESPONSE_WINDOW_MINUTES

    fun promptLine(): String =
        "Hello — I haven't heard from you in a while, and I wanted to check you're alright. Tap 'I'm okay' or just talk to me."

    fun alertDetail(): String =
        "Nuri checked in after a long quiet stretch and got no answer within $RESPONSE_WINDOW_MINUTES minutes. This is a safety alert, not a medical judgment — a call or visit is the right next step."
}

/** Routine anchors (sweep #36): "after breakfast → a short walk" style
 *  scaffolding the family sets up; Nuri surfaces them at their time. */
data class RoutineAnchor(val timeLabel: String, val text: String)

object RoutineAnchors {
    fun encode(list: List<RoutineAnchor>): String =
        list.joinToString(";") { "${it.timeLabel.replace("|", " ").replace(";", " ")}|${it.text.replace("|", " ").replace(";", " ")}" }

    fun decode(encoded: String): List<RoutineAnchor> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split(";").mapNotNull { part ->
            val b = part.split("|"); if (b.size != 2) null else RoutineAnchor(b[0], b[1])
        }
    }
}

/** Voice/text notes & story records (sweep #34/#35) — captured locally,
 *  retrievable, shareable by the family. Capture is by the spoken-text
 *  seam until STT lands; the store and retrieval are real. */
data class NoteRecord(val dayKey: String, val timeText: String, val kind: String, val text: String)

object NoteRecords {
    fun encode(list: List<NoteRecord>): String =
        list.joinToString(";") { r ->
            listOf(r.dayKey, r.timeText, r.kind, r.text).joinToString("|") { it.replace("|", " ").replace(";", " ") }
        }

    fun decode(encoded: String): List<NoteRecord> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split(";").mapNotNull { part ->
            val b = part.split("|"); if (b.size != 4) null else NoteRecord(b[0], b[1], b[2], b[3])
        }
    }
}
