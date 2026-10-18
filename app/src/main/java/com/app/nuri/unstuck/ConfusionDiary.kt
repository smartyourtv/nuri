package com.app.nuri.unstuck

import com.app.nuri.config.NuriSettings

/**
 * The confusion diary (catalog/spec: "usage logged for caregiver").
 * Every Unstuck helper session and safety event is recorded here.
 * Visibility follows the access mode — the senior's "Just me" choice is
 * real: in Independent mode nothing is caregiver-visible.
 */
data class DiaryEntry(
    /** Local day key, e.g. "2026-10-17". */
    val dayKey: String,
    /** Human time text, e.g. "8:05 AM". */
    val timeText: String,
    /** What happened, plain words: "Asked for help: no sound", "Took morning medicine". */
    val what: String,
    /** How it ended: "Fixed by Nuri", "Walked through", "Asked Shawn", "Still stuck". */
    val outcome: String,
)

object ConfusionDiary {
    /** Caregiver-visible only in Hybrid / Managed, per the access mode. */
    fun caregiverVisible(accessMode: NuriSettings.AccessMode): Boolean =
        accessMode != NuriSettings.AccessMode.INDEPENDENT

    fun encode(entries: List<DiaryEntry>): String =
        entries.joinToString(";") { e ->
            listOf(e.dayKey, e.timeText, e.what, e.outcome).joinToString("|") { it.replace("|", " ").replace(";", " ") }
        }

    /** Total decode: blank/garbage → empty, never throws (self-repair pattern). */
    fun decode(encoded: String): List<DiaryEntry> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split(";").mapNotNull { part ->
            val b = part.split("|"); if (b.size != 4) null else DiaryEntry(b[0], b[1], b[2], b[3])
        }
    }

    /** Keep the newest [max] entries (oldest dropped first). */
    fun capped(entries: List<DiaryEntry>, max: Int = 200): List<DiaryEntry> =
        if (entries.size <= max) entries else entries.takeLast(max)
}

/** Safety-layer alert records (missed meds, distress, where-am-I, SOS…). */
data class SafetyAlert(
    val dayKey: String,
    val timeText: String,
    /** Short title, e.g. "Medicine not confirmed". */
    val title: String,
    /** Plain detail line. */
    val detail: String,
)

object SafetyAlerts {
    fun encode(list: List<SafetyAlert>): String =
        list.joinToString(";") { a ->
            listOf(a.dayKey, a.timeText, a.title, a.detail).joinToString("|") { it.replace("|", " ").replace(";", " ") }
        }

    fun decode(encoded: String): List<SafetyAlert> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split(";").mapNotNull { part ->
            val b = part.split("|"); if (b.size != 4) null else SafetyAlert(b[0], b[1], b[2], b[3])
        }
    }

    fun capped(list: List<SafetyAlert>, max: Int = 100): List<SafetyAlert> =
        if (list.size <= max) list else list.takeLast(max)
}
