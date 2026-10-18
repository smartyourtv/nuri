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
}
