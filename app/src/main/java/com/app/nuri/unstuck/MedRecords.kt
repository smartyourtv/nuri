package com.app.nuri.unstuck

/**
 * Medication records Nuri keeps: the schedule (names exactly as written on
 * the bottle, entered by the caregiver) and the confirmation log (when the
 * senior confirmed taking a dose).
 *
 * HARD RULES (catalog #9, standing Nuri law):
 *  - "Did I take my pills?" is answered from the confirmation log only.
 *  - If there is NO record, Nuri says so plainly and NEVER advises taking
 *    another dose — it offers the pharmacist / caregiver instead.
 *  - Nuri never changes doses or schedules; differences between two lists
 *    are FLAGGED only — a pharmacist or prescriber decides.
 */
data class MedScheduleEntry(
    /** Medicine name exactly as written on the bottle. */
    val nameAsWritten: String,
    /** "Morning" / "Midday" / "Evening" / "Bedtime" or "8:00 AM" style label. */
    val timeLabel: String,
    /** Hour of day 0–23 the reminder is due, or -1 for label-only entries. */
    val dueHour: Int = -1,
    /** Refill-by text as written (e.g. "Refill by Nov 2"), or null. */
    val refillByText: String? = null,
    /** Days of supply left, if the caregiver tracks it; null = not tracked. */
    val daysLeft: Int? = null,
)

data class MedConfirmation(
    /** Local day key, e.g. "2026-10-17". */
    val dayKey: String,
    /** Human time text, e.g. "8:05 AM". */
    val timeText: String,
    /** Schedule entry name confirmed, or "medicine" for a general confirmation. */
    val entryName: String,
)

object MedRecords {
    const val NO_SECOND_DOSE_LINE: String =
        "I don't have a record of it, and I can't tell whether you took it. Please don't take another dose just in case — " +
            "taking it twice can be harmful. Let's ask the pharmacist, or your caregiver, what to do."

    /** AUTO answer to "did I take my pills?" from today's confirmations. */
    fun didITakeAnswer(today: List<MedConfirmation>, entryName: String? = null): String {
        val relevant = if (entryName == null) today else today.filter { it.entryName.equals(entryName, ignoreCase = true) }
        if (relevant.isEmpty()) return NO_SECOND_DOSE_LINE
        val last = relevant.last()
        return "Yes — you told me at ${last.timeText} that you took your ${last.entryName}. I wrote it down then, so you don't need to take it again."
    }

    /** True when a scheduled dose is due and has no confirmation yet. */
    fun isMissed(entry: MedScheduleEntry, today: List<MedConfirmation>, nowHour: Int): Boolean {
        if (entry.dueHour < 0 || nowHour <= entry.dueHour) return false
        return today.none { it.entryName.equals(entry.nameAsWritten, ignoreCase = true) }
    }

    /** Refill reminder line when supply runs low; null when nothing to say. */
    fun refillLine(entry: MedScheduleEntry): String? {
        val days = entry.daysLeft ?: return entry.refillByText?.let { "Your ${entry.nameAsWritten} bottle says: $it." }
        return when {
            days <= 0 -> "Your ${entry.nameAsWritten} may be out. Let's tell your caregiver or the pharmacy today — Ilona can't order refills."
            days <= 5 -> "You have about $days day${if (days == 1) "" else "s"} of ${entry.nameAsWritten} left. It's a good time to ask the pharmacy or your caregiver about a refill."
            else -> null
        }
    }

    fun encodeSchedule(entries: List<MedScheduleEntry>): String =
        entries.joinToString(";") { e ->
            listOf(e.nameAsWritten, e.timeLabel, e.dueHour.toString(), e.refillByText ?: "", e.daysLeft?.toString() ?: "")
                .joinToString("|") { it.replace("|", " ").replace(";", " ") }
        }

    fun decodeSchedule(encoded: String): List<MedScheduleEntry> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split(";").mapNotNull { part ->
            val b = part.split("|"); if (b.size != 5) null
            else MedScheduleEntry(b[0], b[1], b[2].toIntOrNull() ?: -1, b[3].ifBlank { null }, b[4].toIntOrNull())
        }
    }

    fun encodeConfirmations(list: List<MedConfirmation>): String =
        list.joinToString(";") { "${it.dayKey}|${it.timeText.replace("|", " ").replace(";", " ")}|${it.entryName.replace("|", " ").replace(";", " ")}" }

    fun decodeConfirmations(encoded: String): List<MedConfirmation> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split(";").mapNotNull { part ->
            val b = part.split("|"); if (b.size != 3) null else MedConfirmation(b[0], b[1], b[2])
        }
    }
}

/** Post-hospital list comparison (catalog #50): flag differences ONLY. */
object MedListDiff {
    data class Diff(val onlyOnOldList: List<String>, val onlyOnNewList: List<String>)

    fun compare(oldList: List<String>, newList: List<String>): Diff {
        val oldNorm = oldList.map { it.trim().lowercase() }
        val newNorm = newList.map { it.trim().lowercase() }
        return Diff(
            onlyOnOldList = oldList.filter { it.trim().lowercase() !in newNorm },
            onlyOnNewList = newList.filter { it.trim().lowercase() !in oldNorm },
        )
    }

    fun spokenSummary(diff: Diff): String {
        if (diff.onlyOnOldList.isEmpty() && diff.onlyOnNewList.isEmpty())
            return "The two lists match. If anything still looks different to you, the pharmacist can check."
        val parts = mutableListOf("I found differences between the two lists — I'm only pointing them out; I never decide which list is right.")
        if (diff.onlyOnNewList.isNotEmpty()) parts += "On the new list only: ${diff.onlyOnNewList.joinToString(", ")}."
        if (diff.onlyOnOldList.isNotEmpty()) parts += "On the old list only: ${diff.onlyOnOldList.joinToString(", ")}."
        parts += "Please ask the pharmacist or the doctor's office which list to follow before taking the next dose."
        return parts.joinToString(" ")
    }
}
