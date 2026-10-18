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
}
