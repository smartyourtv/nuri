package com.app.nuri.remote.android

import android.content.Context
import com.app.nuri.remote.FirebaseProjectConfig
import com.app.nuri.remote.TestHouseholdPlan
import com.app.nuri.repository.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Session resolution from settings state — the one place that
 * decides which binding (real senior / real caregiver / test
 * senior / test caregiver) a caller gets. Test mode, when on,
 * takes precedence for BOTH roles so a test can never half-touch
 * the real household.
 */
object RemoteSessions {

    suspend fun config(context: Context, repo: SettingsRepository): FirebaseProjectConfig? =
        FirebaseInit.bundledConfig(context) ?: FirebaseInit.decodeStoredConfig(repo.remoteConfigJson.first())

    suspend fun hasConfig(context: Context, repo: SettingsRepository): Boolean =
        config(context, repo) != null

    suspend fun seniorSession(context: Context, repo: SettingsRepository): RemoteSession? {
        val config = config(context, repo) ?: return null
        if (repo.remoteTestOn.first()) {
            val hid = repo.remoteTestHouseholdId.first()
            if (hid.isBlank()) return null
            return FirebaseInit.session(context, config, null, hid, "SENIOR", test = true)
        }
        val hid = repo.remoteHouseholdId.first()
        if (hid.isBlank() || repo.remoteRole.first() != "SENIOR") return null
        return FirebaseInit.session(context, config, null, hid, "SENIOR", test = false)
    }

    suspend fun caregiverSession(context: Context, repo: SettingsRepository): RemoteSession? {
        val config = config(context, repo) ?: return null
        if (repo.remoteTestOn.first()) {
            val hid = repo.remoteTestHouseholdId.first()
            if (hid.isBlank()) return null
            return FirebaseInit.session(
                context, config, TestHouseholdPlan.TEST_APP_NAME, hid, "CAREGIVER", test = true,
            )
        }
        val hid = repo.remoteHouseholdId.first()
        if (hid.isBlank() || repo.remoteRole.first() != "CAREGIVER") return null
        return FirebaseInit.session(context, config, null, hid, "CAREGIVER", test = false)
    }

    /** The active household id + test flag, for display-free plumbing. */
    suspend fun activeHousehold(repo: SettingsRepository): Pair<String, Boolean>? {
        if (repo.remoteTestOn.first()) {
            val hid = repo.remoteTestHouseholdId.first()
            if (hid.isNotBlank()) return hid to true
        }
        val hid = repo.remoteHouseholdId.first()
        return if (hid.isNotBlank()) hid to false else null
    }
}
