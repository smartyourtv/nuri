package com.app.nuri.update.android

import android.content.Context
import com.app.nuri.update.PendingUpdate
import com.app.nuri.update.PendingUpdateStore

/**
 * Runtime state for the self-update executor (brief SS10), SharedPreferences
 * backed because the executor runs from a JobService and a BroadcastReceiver
 * where the DataStore settings flow is not the natural fit. Holds:
 *  - the pending-update marker (implements [PendingUpdateStore]),
 *  - the last run's plain-language result (shown in Settings),
 *  - the quiet after-note once an update lands and passes its self-check,
 *  - caregiver alert records (kept whether or not a notification posted).
 * Nothing here is a secret; it is bookkeeping the Settings screen reads.
 */
class UpdateRuntimeStore(context: Context) : PendingUpdateStore {

    private val prefs = context.applicationContext
        .getSharedPreferences("nuri_update_runtime", Context.MODE_PRIVATE)

    private companion object {
        const val KEY_PENDING_CODE = "pending_version_code"
        const val KEY_PENDING_NAME = "pending_version_name"
        const val KEY_PENDING_WRITTEN_AT = "pending_written_at"
        const val KEY_LAST_RESULT = "last_run_result"
        const val KEY_AFTER_NOTE = "after_note"
        const val KEY_ALERTS = "caregiver_alerts"
        // v0.3.1: report dedupe — a known failure is reported once per
        // distinct signature, not on every launch.
        const val KEY_SCHEDULE_FAILURE_SIG = "schedule_failure_signature"
        const val KEY_REPAIR_FAILURE_SIG = "repair_failure_signature"
        const val KEY_LAST_CHECK_ATTEMPT = "last_check_attempt_millis"
        const val MAX_ALERTS = 10
    }

    override fun read(): PendingUpdate? {
        val code = prefs.getInt(KEY_PENDING_CODE, 0)
        if (code <= 0) return null
        return PendingUpdate(code, prefs.getString(KEY_PENDING_NAME, "") ?: "")
    }

    override fun write(pending: PendingUpdate) {
        prefs.edit()
            .putInt(KEY_PENDING_CODE, pending.versionCode)
            .putString(KEY_PENDING_NAME, pending.versionName)
            // The startup self-repair reconcile needs the marker's age to
            // prove staleness; records written before this key existed
            // read as 0 = unknown age, and are never cleared as stale.
            .putLong(KEY_PENDING_WRITTEN_AT, System.currentTimeMillis())
            .apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_PENDING_CODE).remove(KEY_PENDING_NAME)
            .remove(KEY_PENDING_WRITTEN_AT).apply()
    }

    /** When the pending marker was written (0 = unknown / pre-existing record). */
    fun pendingWrittenAtMillis(): Long = prefs.getLong(KEY_PENDING_WRITTEN_AT, 0L)

    fun recordRunResult(detail: String) {
        prefs.edit().putString(KEY_LAST_RESULT, detail).apply()
    }

    fun lastRunResult(): String? = prefs.getString(KEY_LAST_RESULT, null)

    fun recordAfterNote(text: String) {
        prefs.edit().putString(KEY_AFTER_NOTE, text).apply()
    }

    fun afterNote(): String? = prefs.getString(KEY_AFTER_NOTE, null)

    // --- v0.3.1 dedupe + fallback bookkeeping --------------------------

    fun scheduleFailureSignature(): String? = prefs.getString(KEY_SCHEDULE_FAILURE_SIG, null)

    fun setScheduleFailureSignature(signature: String) {
        prefs.edit().putString(KEY_SCHEDULE_FAILURE_SIG, signature).apply()
    }

    fun clearScheduleFailureSignature() {
        prefs.edit().remove(KEY_SCHEDULE_FAILURE_SIG).apply()
    }

    fun repairFailureSignature(): String? = prefs.getString(KEY_REPAIR_FAILURE_SIG, null)

    fun setRepairFailureSignature(signature: String) {
        prefs.edit().putString(KEY_REPAIR_FAILURE_SIG, signature).apply()
    }

    fun clearRepairFailureSignature() {
        prefs.edit().remove(KEY_REPAIR_FAILURE_SIG).apply()
    }

    fun lastCheckAttemptMillis(): Long = prefs.getLong(KEY_LAST_CHECK_ATTEMPT, 0L)

    fun markCheckAttempt(nowMillis: Long) {
        prefs.edit().putLong(KEY_LAST_CHECK_ATTEMPT, nowMillis).apply()
    }

    fun recordCaregiverAlert(title: String, detail: String) {
        val kept = (caregiverAlerts() + "$title — $detail").takeLast(MAX_ALERTS)
        prefs.edit().putString(KEY_ALERTS, kept.joinToString("\n")).apply()
    }

    fun caregiverAlerts(): List<String> =
        prefs.getString(KEY_ALERTS, null)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
}
