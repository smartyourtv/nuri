package com.app.nuri.update

import com.app.nuri.config.NuriSettings
import java.security.MessageDigest

/**
 * Self-update mechanism (brief SS10, added 2026-10-16 per Shawn's decision) —
 * the no-Play-Store update path for installs Shawn controls, and an OPT-IN
 * option in the public flavor.
 *
 * This file is the POLICY + VERIFICATION core: pure Kotlin, unit-tested.
 * The download/install EXECUTION (background check scheduling, APK download,
 * PackageInstaller session) is a named remaining build item — it is not
 * invoked anywhere yet, and nothing here pretends an update can install
 * today. What IS decided here, and must never be bypassed by the future
 * executor:
 *  - An offer installs only if it is NEWER than the installed version
 *    (no silent downgrade exists — bad updates are fixed forward).
 *  - The downloaded APK's SHA-256 must match the feed's published hash.
 *  - The APK's signing certificate must match the installed app's
 *    certificate. Any mismatch = refuse, never install.
 */
data class UpdateOffer(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256Hex: String,
    val sizeBytes: Long
)

object SelfUpdatePolicy {
    /**
     * Is automatic updating in effect for this install?
     * - Private flavor: always ON (installs Shawn personally controls).
     * - Managed access mode (either flavor): ON (caregiver-controlled).
     * - Otherwise: the stored toggle decides; unset = OFF (the updater law's
     *   user-initiated default for Independent users).
     */
    fun autoUpdateEnabled(isPrivateFlavor: Boolean, accessMode: NuriSettings.AccessMode, toggle: Boolean?): Boolean =
        when {
            isPrivateFlavor -> true
            accessMode == NuriSettings.AccessMode.MANAGED -> true
            else -> toggle ?: false
        }

    /** Plain-language reason shown next to the Settings toggle. */
    fun autoUpdateReason(isPrivateFlavor: Boolean, accessMode: NuriSettings.AccessMode, toggle: Boolean?): String =
        when {
            isPrivateFlavor -> "On for this personal build — updates look after themselves."
            accessMode == NuriSettings.AccessMode.MANAGED -> "On — in this setup, your caregiver looks after updates."
            toggle == true -> "On — you chose automatic updates. You won't need to do anything when a new version is ready."
            else -> "Off — Nuri will tell you once when a new version is ready, and nothing installs until you tap Update."
        }

    /** The first-install offer copy (public flavor), verbatim from the brief. */
    const val FIRST_INSTALL_OFFER_TEXT =
        "Update Nuri automatically — you won't need to do anything when a new version is ready"

    /** Newer-only rule: equal or lower version codes are never an update. */
    fun isNewer(installedVersionCode: Int, offer: UpdateOffer): Boolean =
        offer.versionCode > installedVersionCode
}

object ApkVerification {
    /** SHA-256 of [bytes] as lowercase hex (same encoding the feed publishes). */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** Constant-shape comparison of a computed hash against the feed's hash (case-insensitive hex). */
    fun hashMatches(computedSha256Hex: String, feedSha256Hex: String): Boolean =
        computedSha256Hex.equals(feedSha256Hex.trim(), ignoreCase = true)

    /**
     * Signing-certificate match: the candidate APK's signer certificate
     * SHA-256 must equal the installed app's. Both values are certificate
     * digests (not secrets), compared exactly after normalization.
     */
    fun certificateMatches(installedCertSha256Hex: String, candidateCertSha256Hex: String): Boolean =
        installedCertSha256Hex.trim().equals(candidateCertSha256Hex.trim(), ignoreCase = true)

    /**
     * Full gate the future executor must pass before opening an install
     * session. Returns null when the offer may proceed, or the plain-language
     * refusal reason. Order matters: refuse cheap checks first.
     */
    fun refusalReason(
        installedVersionCode: Int,
        offer: UpdateOffer,
        computedApkSha256Hex: String,
        installedCertSha256Hex: String,
        candidateCertSha256Hex: String
    ): String? = when {
        !SelfUpdatePolicy.isNewer(installedVersionCode, offer) ->
            "That version is not newer than the one already here, so it was not installed."
        !hashMatches(computedApkSha256Hex, offer.sha256Hex) ->
            "The downloaded update did not match its safety fingerprint, so it was not installed."
        !certificateMatches(installedCertSha256Hex, candidateCertSha256Hex) ->
            "The downloaded update was not signed by the same maker as this Nuri, so it was not installed."
        else -> null
    }
}
