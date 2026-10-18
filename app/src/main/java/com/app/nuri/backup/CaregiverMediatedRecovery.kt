package com.app.nuri.backup

import com.app.nuri.config.NuriSettings

/**
 * Caregiver-mediated backup recovery (Shawn, 2026-10-17 — replaces the
 * REJECTED paper recovery phrase; the phrase-only key path is deleted).
 *
 * The design: the backup stays encrypted. Restoring it requires BOTH —
 *   1. the senior's own Google sign-in present on the new phone, and
 *   2. approval from the restore authority, given from THEIR OWN device:
 *      the caregiver (Managed / Hybrid) or the chosen recovery contact
 *      (Independent).
 * There is nothing for the senior to remember, write down, or lose.
 * A paper phrase is never a key and never an authority here.
 */
enum class RestoreAuthority { CAREGIVER, RECOVERY_CONTACT }

object CaregiverMediatedRecovery {
    fun authorityFor(accessMode: NuriSettings.AccessMode): RestoreAuthority = when (accessMode) {
        NuriSettings.AccessMode.MANAGED, NuriSettings.AccessMode.HYBRID -> RestoreAuthority.CAREGIVER
        NuriSettings.AccessMode.INDEPENDENT -> RestoreAuthority.RECOVERY_CONTACT
    }

    fun authorityLabel(authority: RestoreAuthority): String = when (authority) {
        RestoreAuthority.CAREGIVER -> "your caregiver"
        RestoreAuthority.RECOVERY_CONTACT -> "your chosen recovery contact"
    }

    data class Decision(val allowed: Boolean, val spokenReason: String)

    /** Both conditions are required — there is no single-factor path. */
    fun decide(
        accessMode: NuriSettings.AccessMode,
        googleSignedIn: Boolean,
        authorityApprovedFromOwnDevice: Boolean,
    ): Decision {
        val authority = authorityFor(accessMode)
        return when {
            !googleSignedIn -> Decision(
                false,
                "First, sign in with your Google account on this phone — that's how Nuri knows it's really you. Your caregiver can sit with you for that step; Nuri never sees or speaks the password.",
            )
            !authorityApprovedFromOwnDevice -> Decision(
                false,
                "Your backup is locked until ${authorityLabel(authority)} approves this restore from their own phone. That approval is what keeps your backup yours — nobody can restore it from this phone alone.",
            )
            else -> Decision(true, "Both checks passed: your Google sign-in, and approval from ${authorityLabel(authority)}. Your backup can be restored now.")
        }
    }

    /** Diagnostics / setup-line status text (replaces the old paper-phrase line). */
    fun statusText(accessMode: NuriSettings.AccessMode): String =
        "Caregiver-mediated: restores need the senior's Google sign-in plus approval from ${authorityLabel(authorityFor(accessMode))} on their own device. No paper phrase exists — it was retired by family decision (too risky)."
}
