package com.app.nuri.config

import android.content.Context
import androidx.compose.runtime.Composable
import com.app.nuri.repository.SettingsRepository

/**
 * Public flavor hook: this edition has no family-override layer at all.
 * Every value the senior sees is the value that applies; there are no
 * conversational suggestions pending; the caregiver dashboard shows its
 * ordinary sections only.
 */
object FlavorHooks {
    /** No name override in this edition: the ASK rung uses the setup caregiver name. */
    fun askNameOverride(): String? = null

    /**
     * v0.5.0: this edition has no shadow store and registers NO
     * remote shadow handler — the sync engine's bridge stays empty
     * and remote shadow documents are never applied here (design
     * §13.1). The function exists so the shared call site compiles.
     */
    fun registerRemoteHandlers(context: Context) { /* no shadow layer in this edition */ }

    fun effectiveTierName(context: Context, seenTierName: String): String = seenTierName
    fun effectiveEmergencyNumber(context: Context, seenNumber: String): String = seenNumber
    fun effectiveEmergencyName(context: Context, seenName: String): String = seenName
    fun effectiveMedScheduleEncoded(context: Context, seenEncoded: String): String = seenEncoded

    fun pendingSuggestion(context: Context, seenFontStepName: String, seenEmotionLevelName: String, seenGamesAllowed: Boolean): PendingSuggestion? = null

    fun recordSuggestionDeclined(context: Context, suggestion: PendingSuggestion) { /* nothing to record in this edition */ }

    @Composable
    fun CaregiverFamilySection(repository: SettingsRepository) {
        // No family-override layer in this edition — nothing rendered.
    }

    @Composable
    fun CaregiverRemoteShadowSection(repository: SettingsRepository) {
        // No shadow layer exists in this edition, so there is no
        // remote shadow editor either (design §13.1).
    }

    @Composable
    fun CaregiverTestModeSection(repository: SettingsRepository) {
        // Test mode ships only in the private edition (design §11).
    }
}
