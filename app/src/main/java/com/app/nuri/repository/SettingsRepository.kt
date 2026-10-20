package com.app.nuri.repository

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.app.nuri.bluetooth.BtSlotManager
import com.app.nuri.config.NuriSettings
import com.app.nuri.repair.SettingsCorruption
import com.app.nuri.reports.android.CrashReports
import com.app.nuri.tier.PersonToggles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Self-repair (standing rule, 2026-10-17): if the settings file is ever
 * damaged, DataStore calls this handler instead of crashing every read
 * forever. It preserves a copy of the damaged file inside the app,
 * records the event, and substitutes safe defaults; the startup repair
 * runner then verifies the store reads again and logs the repair
 * (repaired / repair-failed) in the crash & error reports.
 */
private val Context.dataStore by preferencesDataStore(
    name = "nuri_settings",
    corruptionHandler = ReplaceFileCorruptionHandler {
        SettingsCorruption.onCorruption(CrashReports.contextOrNull()?.filesDir, it)
        emptyPreferences()
    }
)

/**
 * Settings storage — DataStore-backed, enum values stored by name (Scout
 * pattern). Phase D wires: tier, first-install mode, access mode, per-person
 * toggles, Always listen, Bluetooth slots, and updater notification state.
 * Guided clamps are NOT applied here — they are applied by
 * tier/EffectiveSettingsResolver so stored wishes stay visible to caregivers
 * while effective behaviour stays safe.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val EMOTION_LEVEL = stringPreferencesKey("emotion_level")
        val TIER = stringPreferencesKey("tier")
        val FIRST_INSTALL_MODE = stringPreferencesKey("first_install_mode")
        val ACCESS_MODE = stringPreferencesKey("access_mode")
        val GAMES_ALLOWED = booleanPreferencesKey("games_allowed")
        val SLOWER_SPEECH = booleanPreferencesKey("slower_speech")
        val LONGER_LISTEN = booleanPreferencesKey("longer_listen_window")
        val ALWAYS_LISTEN = booleanPreferencesKey("always_listen_enabled")
        val THERAPIST_CONSENT_SEEN = booleanPreferencesKey("therapist_consent_seen")
        val BT_SLOTS = stringPreferencesKey("bt_slots")
        val AUTO_UPDATE = booleanPreferencesKey("auto_update_enabled")
        val APPEARANCE_THEME = stringPreferencesKey("appearance_theme")
        val FONT_STEP = stringPreferencesKey("font_step")
        val LAST_NOTIFIED_VERSION = intPreferencesKey("last_notified_version")
        val DISMISSED_VERSION = intPreferencesKey("dismissed_update_version")
        val SINGLE_VOICE_ID = stringPreferencesKey("single_voice_id")
        // ── v0.3.0 Unstuck / safety additions (additive; vc2 keys untouched) ──
        val CAREGIVER_NAME = stringPreferencesKey("caregiver_name")
        val SAFE_WORD = stringPreferencesKey("family_safe_word")
        val SPOUSE_MODE = stringPreferencesKey("spouse_response_mode")
        val SPOUSE_SCRIPT = stringPreferencesKey("spouse_family_script")
        val EMERGENCY_NAME = stringPreferencesKey("emergency_contact_name")
        val EMERGENCY_NUMBER = stringPreferencesKey("emergency_contact_number")
        val FAMILY_CONTACTS = stringPreferencesKey("family_contacts")
        val MED_SCHEDULE = stringPreferencesKey("med_schedule")
        val MED_CONFIRMATIONS = stringPreferencesKey("med_confirmations")
        val DIARY = stringPreferencesKey("confusion_diary")
        val SAFETY_ALERTS = stringPreferencesKey("safety_alerts")
        val CHECK_INS = stringPreferencesKey("check_ins")
        val ANCHORS = stringPreferencesKey("routine_anchors")
        val NOTES = stringPreferencesKey("note_records")
        val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        val SEALED_PAPER_DONE = booleanPreferencesKey("sealed_paper_done")
        val RECOVERY_CHAIN_DONE = booleanPreferencesKey("recovery_chain_done")
        val EMERGENCY_INFO = stringPreferencesKey("emergency_info_text")
        val EMERGENCY_INFO_ON = booleanPreferencesKey("emergency_info_on")
        val LAST_INTERACTION_MS = androidx.datastore.preferences.core.longPreferencesKey("last_interaction_ms")
        val USUAL_PLACES = stringPreferencesKey("usual_places")
        // ── v0.3.2: Show Nuri read-aloud + cloud vision + safety card ──
        val SHOW_NURI_READ_ALOUD = booleanPreferencesKey("show_nuri_read_aloud")
        val CLOUD_VISION_KEY = stringPreferencesKey("cloud_vision_key")
        val CLOUD_HINT_PENDING = booleanPreferencesKey("cloud_hint_pending")
        val SAFETY_CARD_SEEN = booleanPreferencesKey("safety_card_seen")
        // ── v0.5.0: remote observation (design §13) — additive only ──
        val REMOTE_ROLE = stringPreferencesKey("remote_role")
        val REMOTE_HOUSEHOLD_ID = stringPreferencesKey("remote_household_id")
        val REMOTE_TEST_HOUSEHOLD_ID = stringPreferencesKey("remote_test_household_id")
        val REMOTE_TEST_ON = booleanPreferencesKey("remote_test_on")
        val REMOTE_CONFIG_JSON = stringPreferencesKey("remote_config_json")
        val REMOTE_FCM_TOKEN = stringPreferencesKey("remote_fcm_token")
        val REMOTE_CHECKIN_HOUR = intPreferencesKey("remote_checkin_hour")
        val REMOTE_NO_ANSWER_THRESHOLD = intPreferencesKey("remote_no_answer_threshold")
        val REMOTE_LAST_WELFARE_RAISED_AT = androidx.datastore.preferences.core.longPreferencesKey("remote_last_welfare_raised_at")
        val REMOTE_WATCH_ENABLED = booleanPreferencesKey("remote_watch_enabled")
        val REMOTE_LAST_SYNC_AT = androidx.datastore.preferences.core.longPreferencesKey("remote_last_sync_at")
        val CAREGIVER_CALL_NUMBER = stringPreferencesKey("caregiver_call_number")
        val PERSON_VOICE_ONLY_CALLS = booleanPreferencesKey("person_voice_only_calls")
    }

    private fun <T : Enum<T>> enumFlow(key: androidx.datastore.preferences.core.Preferences.Key<String>, default: T, values: Array<T>): Flow<T> =
        context.dataStore.data.map { prefs -> prefs[key]?.let { s -> runCatching { values.first { it.name == s } }.getOrNull() } ?: default }

    val emotionLevel: Flow<NuriSettings.EmotionLevel> = enumFlow(Keys.EMOTION_LEVEL, NuriSettings.DEFAULT_EMOTION_LEVEL, NuriSettings.EmotionLevel.values())
    suspend fun setEmotionLevel(level: NuriSettings.EmotionLevel) { context.dataStore.edit { it[Keys.EMOTION_LEVEL] = level.name } }

    val tier: Flow<NuriSettings.Tier> = enumFlow(Keys.TIER, NuriSettings.DEFAULT_TIER, NuriSettings.Tier.values())
    suspend fun setTier(tier: NuriSettings.Tier) { context.dataStore.edit { it[Keys.TIER] = tier.name } }

    val firstInstallMode: Flow<NuriSettings.FirstInstallMode?> = context.dataStore.data.map { p -> p[Keys.FIRST_INSTALL_MODE]?.let { s -> runCatching { NuriSettings.FirstInstallMode.valueOf(s) }.getOrNull() } }
    suspend fun setFirstInstallMode(mode: NuriSettings.FirstInstallMode) { context.dataStore.edit { it[Keys.FIRST_INSTALL_MODE] = mode.name } }

    val accessMode: Flow<NuriSettings.AccessMode> = enumFlow(Keys.ACCESS_MODE, NuriSettings.AccessMode.INDEPENDENT, NuriSettings.AccessMode.values())
    suspend fun setAccessMode(mode: NuriSettings.AccessMode) { context.dataStore.edit { it[Keys.ACCESS_MODE] = mode.name } }

    val personToggles: Flow<PersonToggles> = combine(
        context.dataStore.data.map { it[Keys.GAMES_ALLOWED] },
        context.dataStore.data.map { it[Keys.SLOWER_SPEECH] },
        context.dataStore.data.map { it[Keys.LONGER_LISTEN] },
        context.dataStore.data.map { it[Keys.ALWAYS_LISTEN] },
        emotionLevel
    ) { games, slower, longer, always, emotion ->
        PersonToggles(gamesAllowed = games, slowerSpeech = slower, longerListenWindow = longer, alwaysListenEnabled = always, emotionLevel = emotion)
    }
    suspend fun setGamesAllowed(v: Boolean) { context.dataStore.edit { it[Keys.GAMES_ALLOWED] = v } }
    suspend fun setSlowerSpeech(v: Boolean) { context.dataStore.edit { it[Keys.SLOWER_SPEECH] = v } }
    suspend fun setLongerListenWindow(v: Boolean) { context.dataStore.edit { it[Keys.LONGER_LISTEN] = v } }
    suspend fun setAlwaysListenEnabled(v: Boolean) { context.dataStore.edit { it[Keys.ALWAYS_LISTEN] = v } }
    /**
     * Therapist consent (brief SS5, bug-hunt NU-5): whether the plain-language
     * sharing list was read together and marked seen. The read side is what
     * the first-install therapist flow and the Settings "What Nuri shares"
     * card surface; before NU-5 this state was write-only and never shown.
     */
    val therapistConsentSeen: Flow<Boolean> = context.dataStore.data.map { it[Keys.THERAPIST_CONSENT_SEEN] ?: false }
    suspend fun setTherapistConsentSeen(v: Boolean) { context.dataStore.edit { it[Keys.THERAPIST_CONSENT_SEEN] = v } }

    val btSlots: Flow<BtSlotManager> = context.dataStore.data.map { BtSlotManager.decode(it[Keys.BT_SLOTS] ?: "") }
    suspend fun setBtSlots(manager: BtSlotManager) { context.dataStore.edit { it[Keys.BT_SLOTS] = manager.encode() } }

    /**
     * Appearance (brief SS3 readability law — adjustable within the standard).
     * Persisted choices survive updates; an update must never reset them.
     */
    val appearanceTheme: Flow<com.app.nuri.ui.theme.AppearanceTheme> =
        enumFlow(Keys.APPEARANCE_THEME, com.app.nuri.ui.theme.AppearanceTheme.CLASSIC, com.app.nuri.ui.theme.AppearanceTheme.values())
    suspend fun setAppearanceTheme(theme: com.app.nuri.ui.theme.AppearanceTheme) { context.dataStore.edit { it[Keys.APPEARANCE_THEME] = theme.name } }

    val fontStep: Flow<com.app.nuri.ui.theme.FontStep> =
        enumFlow(Keys.FONT_STEP, com.app.nuri.ui.theme.FontStep.EASY, com.app.nuri.ui.theme.FontStep.values())
    suspend fun setFontStep(step: com.app.nuri.ui.theme.FontStep) { context.dataStore.edit { it[Keys.FONT_STEP] = step.name } }

    /** Self-update opt-in toggle (brief SS10). Null = never chosen (policy default applies). */
    val autoUpdateToggle: Flow<Boolean?> = context.dataStore.data.map { it[Keys.AUTO_UPDATE] }
    suspend fun setAutoUpdateToggle(enabled: Boolean) { context.dataStore.edit { it[Keys.AUTO_UPDATE] = enabled } }

    val lastNotifiedVersion: Flow<Int> = context.dataStore.data.map { it[Keys.LAST_NOTIFIED_VERSION] ?: 0 }
    val dismissedVersion: Flow<Int> = context.dataStore.data.map { it[Keys.DISMISSED_VERSION] ?: 0 }
    suspend fun setLastNotifiedVersion(code: Int) { context.dataStore.edit { it[Keys.LAST_NOTIFIED_VERSION] = code } }
    suspend fun setDismissedVersion(code: Int) { context.dataStore.edit { it[Keys.DISMISSED_VERSION] = code } }

    /** The ONE voice (voice continuity). Set once; updates never change it. */
    val singleVoiceId: Flow<String> = context.dataStore.data.map { it[Keys.SINGLE_VOICE_ID] ?: "nuri-voice-default" }
    suspend fun setSingleVoiceId(id: String) { context.dataStore.edit { it[Keys.SINGLE_VOICE_ID] = id } }

    // ── v0.3.0: Unstuck setup + records ──────────────────────────────
    val caregiverName: Flow<String> = context.dataStore.data.map { it[Keys.CAREGIVER_NAME] ?: "" }
    suspend fun setCaregiverName(v: String) { context.dataStore.edit { it[Keys.CAREGIVER_NAME] = v } }

    /** The family safe word — stored locally, never logged, never written to the diary. */
    val safeWord: Flow<String> = context.dataStore.data.map { it[Keys.SAFE_WORD] ?: "" }
    suspend fun setSafeWord(v: String) { context.dataStore.edit { it[Keys.SAFE_WORD] = v } }

    val spouseMode: Flow<com.app.nuri.unstuck.SpouseResponseMode> =
        enumFlow(Keys.SPOUSE_MODE, com.app.nuri.unstuck.SpouseResponseMode.UNSET, com.app.nuri.unstuck.SpouseResponseMode.values())
    suspend fun setSpouseMode(v: com.app.nuri.unstuck.SpouseResponseMode) { context.dataStore.edit { it[Keys.SPOUSE_MODE] = v.name } }

    val spouseScript: Flow<String> = context.dataStore.data.map { it[Keys.SPOUSE_SCRIPT] ?: "" }
    suspend fun setSpouseScript(v: String) { context.dataStore.edit { it[Keys.SPOUSE_SCRIPT] = v } }

    val emergencyContactName: Flow<String> = context.dataStore.data.map { it[Keys.EMERGENCY_NAME] ?: "" }
    suspend fun setEmergencyContactName(v: String) { context.dataStore.edit { it[Keys.EMERGENCY_NAME] = v } }
    val emergencyContactNumber: Flow<String> = context.dataStore.data.map { it[Keys.EMERGENCY_NUMBER] ?: "" }
    suspend fun setEmergencyContactNumber(v: String) { context.dataStore.edit { it[Keys.EMERGENCY_NUMBER] = v } }

    val familyContacts: Flow<List<com.app.nuri.unstuck.FamilyContact>> =
        context.dataStore.data.map { com.app.nuri.unstuck.FamilyContacts.decode(it[Keys.FAMILY_CONTACTS] ?: "") }
    suspend fun setFamilyContacts(v: List<com.app.nuri.unstuck.FamilyContact>) { context.dataStore.edit { it[Keys.FAMILY_CONTACTS] = com.app.nuri.unstuck.FamilyContacts.encode(v) } }

    val medSchedule: Flow<List<com.app.nuri.unstuck.MedScheduleEntry>> =
        context.dataStore.data.map { com.app.nuri.unstuck.MedRecords.decodeSchedule(it[Keys.MED_SCHEDULE] ?: "") }
    suspend fun setMedSchedule(v: List<com.app.nuri.unstuck.MedScheduleEntry>) { context.dataStore.edit { it[Keys.MED_SCHEDULE] = com.app.nuri.unstuck.MedRecords.encodeSchedule(v) } }

    val medConfirmations: Flow<List<com.app.nuri.unstuck.MedConfirmation>> =
        context.dataStore.data.map { com.app.nuri.unstuck.MedRecords.decodeConfirmations(it[Keys.MED_CONFIRMATIONS] ?: "") }
    suspend fun addMedConfirmation(c: com.app.nuri.unstuck.MedConfirmation) {
        context.dataStore.edit { p ->
            val cur = com.app.nuri.unstuck.MedRecords.decodeConfirmations(p[Keys.MED_CONFIRMATIONS] ?: "")
            p[Keys.MED_CONFIRMATIONS] = com.app.nuri.unstuck.MedRecords.encodeConfirmations((cur + c).takeLast(400))
        }
    }

    val diary: Flow<List<com.app.nuri.unstuck.DiaryEntry>> =
        context.dataStore.data.map { com.app.nuri.unstuck.ConfusionDiary.decode(it[Keys.DIARY] ?: "") }
    suspend fun addDiaryEntry(e: com.app.nuri.unstuck.DiaryEntry) {
        context.dataStore.edit { p ->
            val cur = com.app.nuri.unstuck.ConfusionDiary.decode(p[Keys.DIARY] ?: "")
            p[Keys.DIARY] = com.app.nuri.unstuck.ConfusionDiary.encode(com.app.nuri.unstuck.ConfusionDiary.capped(cur + e))
        }
    }

    val safetyAlerts: Flow<List<com.app.nuri.unstuck.SafetyAlert>> =
        context.dataStore.data.map { com.app.nuri.unstuck.SafetyAlerts.decode(it[Keys.SAFETY_ALERTS] ?: "") }
    suspend fun addSafetyAlert(a: com.app.nuri.unstuck.SafetyAlert) {
        context.dataStore.edit { p ->
            val cur = com.app.nuri.unstuck.SafetyAlerts.decode(p[Keys.SAFETY_ALERTS] ?: "")
            p[Keys.SAFETY_ALERTS] = com.app.nuri.unstuck.SafetyAlerts.encode(com.app.nuri.unstuck.SafetyAlerts.capped(cur + a))
        }
    }

    val checkIns: Flow<List<com.app.nuri.unstuck.CheckInRecord>> =
        context.dataStore.data.map { com.app.nuri.unstuck.CheckIns.decode(it[Keys.CHECK_INS] ?: "") }
    suspend fun addCheckIn(r: com.app.nuri.unstuck.CheckInRecord) {
        context.dataStore.edit { p ->
            val cur = com.app.nuri.unstuck.CheckIns.decode(p[Keys.CHECK_INS] ?: "")
            p[Keys.CHECK_INS] = com.app.nuri.unstuck.CheckIns.encode((cur + r).takeLast(90))
        }
    }

    val anchors: Flow<List<com.app.nuri.unstuck.RoutineAnchor>> =
        context.dataStore.data.map { com.app.nuri.unstuck.RoutineAnchors.decode(it[Keys.ANCHORS] ?: "") }
    suspend fun setAnchors(v: List<com.app.nuri.unstuck.RoutineAnchor>) { context.dataStore.edit { it[Keys.ANCHORS] = com.app.nuri.unstuck.RoutineAnchors.encode(v) } }

    val notes: Flow<List<com.app.nuri.unstuck.NoteRecord>> =
        context.dataStore.data.map { com.app.nuri.unstuck.NoteRecords.decode(it[Keys.NOTES] ?: "") }
    suspend fun addNote(r: com.app.nuri.unstuck.NoteRecord) {
        context.dataStore.edit { p ->
            val cur = com.app.nuri.unstuck.NoteRecords.decode(p[Keys.NOTES] ?: "")
            p[Keys.NOTES] = com.app.nuri.unstuck.NoteRecords.encode((cur + r).takeLast(200))
        }
    }

    val setupCompleted: Flow<Boolean> = context.dataStore.data.map { it[Keys.SETUP_COMPLETED] ?: false }
    suspend fun setSetupCompleted(v: Boolean) { context.dataStore.edit { it[Keys.SETUP_COMPLETED] = v } }
    val sealedPaperDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.SEALED_PAPER_DONE] ?: false }
    suspend fun setSealedPaperDone(v: Boolean) { context.dataStore.edit { it[Keys.SEALED_PAPER_DONE] = v } }
    val recoveryChainDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.RECOVERY_CHAIN_DONE] ?: false }
    suspend fun setRecoveryChainDone(v: Boolean) { context.dataStore.edit { it[Keys.RECOVERY_CHAIN_DONE] = v } }

    val emergencyInfo: Flow<String> = context.dataStore.data.map { it[Keys.EMERGENCY_INFO] ?: "" }
    suspend fun setEmergencyInfo(v: String) { context.dataStore.edit { it[Keys.EMERGENCY_INFO] = v } }
    val emergencyInfoOn: Flow<Boolean> = context.dataStore.data.map { it[Keys.EMERGENCY_INFO_ON] ?: false }
    suspend fun setEmergencyInfoOn(v: Boolean) { context.dataStore.edit { it[Keys.EMERGENCY_INFO_ON] = v } }

    val lastInteractionMs: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_INTERACTION_MS] ?: 0L }
    suspend fun touchInteraction(nowMs: Long) { context.dataStore.edit { it[Keys.LAST_INTERACTION_MS] = nowMs } }

    /** "Usual places" for lost things (catalog #14): one place per line, "glasses — kitchen counter". */
    val usualPlaces: Flow<String> = context.dataStore.data.map { it[Keys.USUAL_PLACES] ?: "" }
    suspend fun setUsualPlaces(v: String) { context.dataStore.edit { it[Keys.USUAL_PLACES] = v } }

    // ── v0.3.2 ──────────────────────────────────────────────────────
    /**
     * Show Nuri read-aloud (interim platform voice). Default ON: the
     * Show Nuri screen promises "I'll read it aloud" — the promise is
     * only true if this defaults on. The toggle lives in Settings.
     */
    val showNuriReadAloud: Flow<Boolean> = context.dataStore.data.map { it[Keys.SHOW_NURI_READ_ALOUD] ?: true }
    suspend fun setShowNuriReadAloud(v: Boolean) { context.dataStore.edit { it[Keys.SHOW_NURI_READ_ALOUD] = v } }

    /**
     * The caregiver's OWN free Gemini API key (Part D, bring-your-own
     * key). Stored only in this app's private DataStore; never logged,
     * never written to the diary or any report; sent only as the
     * x-goog-api-key header to Google. Blank = cloud route off.
     */
    val cloudVisionKey: Flow<String> = context.dataStore.data.map { it[Keys.CLOUD_VISION_KEY] ?: "" }
    suspend fun setCloudVisionKey(v: String) { context.dataStore.edit { it[Keys.CLOUD_VISION_KEY] = v.trim() } }

    /** Set when a photo was answered on-device only because no key exists; the caregiver dashboard shows the gentle hint once. */
    val cloudHintPending: Flow<Boolean> = context.dataStore.data.map { it[Keys.CLOUD_HINT_PENDING] ?: false }
    suspend fun setCloudHintPending(v: Boolean) { context.dataStore.edit { it[Keys.CLOUD_HINT_PENDING] = v } }

    /** First-install safety card (legal checklist §5): shown once, acknowledgment stored; never blocks anything. */
    val safetyCardSeen: Flow<Boolean> = context.dataStore.data.map { it[Keys.SAFETY_CARD_SEEN] ?: false }
    suspend fun setSafetyCardSeen(v: Boolean) { context.dataStore.edit { it[Keys.SAFETY_CARD_SEEN] = v } }

    // ── v0.5.0: remote observation state (design §13.2) ─────────────
    /** This phone's role in its real household: NONE / SENIOR / CAREGIVER (remote/RemoteRole names). */
    val remoteRole: Flow<String> = context.dataStore.data.map { it[Keys.REMOTE_ROLE] ?: "NONE" }
    val remoteHouseholdId: Flow<String> = context.dataStore.data.map { it[Keys.REMOTE_HOUSEHOLD_ID] ?: "" }
    suspend fun setRemoteBinding(role: String, householdId: String) {
        context.dataStore.edit { it[Keys.REMOTE_ROLE] = role; it[Keys.REMOTE_HOUSEHOLD_ID] = householdId }
    }
    suspend fun clearRemoteBinding() {
        context.dataStore.edit { it.remove(Keys.REMOTE_ROLE); it.remove(Keys.REMOTE_HOUSEHOLD_ID) }
    }

    /** Single-phone test mode (design §11): a separate binding; never touches the real one. */
    val remoteTestOn: Flow<Boolean> = context.dataStore.data.map { it[Keys.REMOTE_TEST_ON] ?: false }
    val remoteTestHouseholdId: Flow<String> = context.dataStore.data.map { it[Keys.REMOTE_TEST_HOUSEHOLD_ID] ?: "" }
    suspend fun setRemoteTest(householdId: String) {
        context.dataStore.edit { it[Keys.REMOTE_TEST_HOUSEHOLD_ID] = householdId; it[Keys.REMOTE_TEST_ON] = true }
    }
    suspend fun clearRemoteTest() {
        context.dataStore.edit { it.remove(Keys.REMOTE_TEST_HOUSEHOLD_ID); it[Keys.REMOTE_TEST_ON] = false }
    }

    /** Public flavor's pasted project config (design §10.2), stored app-private as JSON. */
    val remoteConfigJson: Flow<String> = context.dataStore.data.map { it[Keys.REMOTE_CONFIG_JSON] ?: "" }
    suspend fun setRemoteConfigJson(v: String) { context.dataStore.edit { it[Keys.REMOTE_CONFIG_JSON] = v } }

    val remoteFcmToken: Flow<String> = context.dataStore.data.map { it[Keys.REMOTE_FCM_TOKEN] ?: "" }
    suspend fun setRemoteFcmToken(v: String) { context.dataStore.edit { it[Keys.REMOTE_FCM_TOKEN] = v } }

    /** Caregiver-set hour for the missing-check-in computation (§8.3); default 10 AM. */
    val remoteCheckinHour: Flow<Int> = context.dataStore.data.map { it[Keys.REMOTE_CHECKIN_HOUR] ?: 10 }
    suspend fun setRemoteCheckinHour(v: Int) { context.dataStore.edit { it[Keys.REMOTE_CHECKIN_HOUR] = v } }

    /** No-answer ladder threshold (design §17.4); default 2 within an hour. */
    val remoteNoAnswerThreshold: Flow<Int> = context.dataStore.data.map { it[Keys.REMOTE_NO_ANSWER_THRESHOLD] ?: 2 }
    suspend fun setRemoteNoAnswerThreshold(v: Int) { context.dataStore.edit { it[Keys.REMOTE_NO_ANSWER_THRESHOLD] = v.coerceIn(1, 5) } }

    val remoteLastWelfareRaisedAt: Flow<Long> = context.dataStore.data.map { it[Keys.REMOTE_LAST_WELFARE_RAISED_AT] ?: 0L }
    suspend fun setRemoteLastWelfareRaisedAt(v: Long) { context.dataStore.edit { it[Keys.REMOTE_LAST_WELFARE_RAISED_AT] = v } }

    /** Caregiver watch-service opt-in (default on once a caregiver binding exists). */
    val remoteWatchEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.REMOTE_WATCH_ENABLED] ?: true }
    suspend fun setRemoteWatchEnabled(v: Boolean) { context.dataStore.edit { it[Keys.REMOTE_WATCH_ENABLED] = v } }

    /** Last time the caregiver side successfully received anything (B1 link status). */
    val remoteLastSyncAt: Flow<Long> = context.dataStore.data.map { it[Keys.REMOTE_LAST_SYNC_AT] ?: 0L }
    suspend fun setRemoteLastSyncAt(v: Long) { context.dataStore.edit { it[Keys.REMOTE_LAST_SYNC_AT] = v } }

    /** The caregiver's own phone number, for Ilona's calls (design §17: number resolution). */
    val caregiverCallNumber: Flow<String> = context.dataStore.data.map { it[Keys.CAREGIVER_CALL_NUMBER] ?: "" }
    suspend fun setCaregiverCallNumber(v: String) { context.dataStore.edit { it[Keys.CAREGIVER_CALL_NUMBER] = v.trim() } }

    /** Per-person call media toggle (design §17.5): this person prefers voice-only calls. */
    val personVoiceOnlyCalls: Flow<Boolean> = context.dataStore.data.map { it[Keys.PERSON_VOICE_ONLY_CALLS] ?: false }
    suspend fun setPersonVoiceOnlyCalls(v: Boolean) { context.dataStore.edit { it[Keys.PERSON_VOICE_ONLY_CALLS] = v } }
}
