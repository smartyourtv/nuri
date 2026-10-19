package com.app.nuri.update

import java.io.File
import kotlinx.serialization.Serializable

/**
 * Self-update EXECUTOR (brief SS10) — the machinery that the policy layer in
 * SelfUpdate.kt was written to govern. This file is the pure orchestration:
 * every platform capability (feed, download, APK inspection, install,
 * self-test, alerting, pending-state) sits behind an interface, and the
 * Android implementations live in update/android/. JVM tests drive this
 * class with fakes, so every decision path is covered off-device.
 *
 * Non-negotiable order of operations (the executor may not reorder):
 *   policy gate -> fetch offer -> newer-only check -> download ->
 *   ApkVerification.refusalReason (SHA-256 + signing cert) -> install.
 * A refusal deletes the downloaded file and never opens an install session.
 * There is no silent downgrade anywhere in this flow: a bad update is fixed
 * forward with a newer version, never rolled back.
 */

/** The per-variant update feed document. Mapped to [UpdateOffer] for the gate. */
@Serializable
data class UpdateFeed(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long = 0
) {
    fun toOffer(): UpdateOffer = UpdateOffer(versionCode, versionName, apkUrl, sha256, sizeBytes)
}

interface UpdateFeedGateway {
    /** The current offer, or null when the feed could not be read (offline / not wired). */
    fun fetchOffer(): UpdateOffer?
}

sealed interface DownloadResult {
    data class Ok(val file: File, val sha256Hex: String) : DownloadResult
    data class Failed(val detail: String) : DownloadResult
}

interface ApkDownloadGateway {
    /** Downloads the offer's APK into [destDir], computing SHA-256 while streaming. */
    fun download(offer: UpdateOffer, destDir: File): DownloadResult
}

interface ApkInspectionGateway {
    fun installedVersionCode(): Int
    /** SHA-256 (hex) of the installed app's signing certificate, or null if unreadable. */
    fun installedCertSha256Hex(): String?
    /** SHA-256 (hex) of the candidate APK's signing certificate, or null if unreadable. */
    fun candidateCertSha256Hex(apk: File): String?
}

sealed interface InstallStart {
    /** The session was committed; the outcome arrives via the install-status receiver / next launch. */
    data object Committed : InstallStart
    data class Failed(val detail: String) : InstallStart
}

interface InstallGateway {
    fun install(apk: File): InstallStart
}

interface UpdateSelfTestGateway {
    fun runSelfTest(): Boolean
}

interface UpdateAlertGateway {
    fun caregiverAlert(title: String, detail: String)
    fun afterNote(text: String)
}

data class PendingUpdate(val versionCode: Int, val versionName: String)

interface PendingUpdateStore {
    fun read(): PendingUpdate?
    fun write(pending: PendingUpdate)
    fun clear()
}

enum class UpdateRunOutcome {
    SKIPPED_POLICY_OFF, COULD_NOT_CHECK, UP_TO_DATE, DOWNLOAD_FAILED, REFUSED, INSTALL_FAILED, INSTALL_STARTED
}

data class UpdateRunResult(val outcome: UpdateRunOutcome, val detail: String)

enum class PostUpdateOutcome { STILL_INSTALLING, UPDATED_OK, UPDATED_SELF_TEST_FAILED }

data class PostUpdateResult(val outcome: PostUpdateOutcome, val detail: String)

class SelfUpdateExecutor(
    private val autoUpdateEnabled: () -> Boolean,
    private val feed: UpdateFeedGateway,
    private val downloader: ApkDownloadGateway,
    private val inspector: ApkInspectionGateway,
    private val installer: InstallGateway,
    private val selfTest: UpdateSelfTestGateway,
    private val alerts: UpdateAlertGateway,
    private val pendingStore: PendingUpdateStore,
    private val downloadDir: File
) {
    companion object {
        /** The quiet after-note, verbatim from brief SS10. */
        const val AFTER_NOTE_TEXT = "Ilona was updated, nothing looks different."
    }

    /** One scheduled background check-and-install pass. Blocking; call off the main thread. */
    fun runScheduledCheck(): UpdateRunResult {
        if (!autoUpdateEnabled()) {
            return UpdateRunResult(
                UpdateRunOutcome.SKIPPED_POLICY_OFF,
                "Automatic updates are off for this Ilona, so nothing was checked."
            )
        }
        val offer = feed.fetchOffer() ?: return UpdateRunResult(
            UpdateRunOutcome.COULD_NOT_CHECK,
            "Could not check for updates right now. Nothing was changed."
        )
        val installed = inspector.installedVersionCode()
        if (!SelfUpdatePolicy.isNewer(installed, offer)) {
            return UpdateRunResult(UpdateRunOutcome.UP_TO_DATE, "Ilona is up to date.")
        }
        val downloaded = when (val r = downloader.download(offer, downloadDir)) {
            is DownloadResult.Failed -> return UpdateRunResult(UpdateRunOutcome.DOWNLOAD_FAILED, r.detail)
            is DownloadResult.Ok -> r
        }
        // The verification gate decides. Null certs become empty strings,
        // which can never match — an unreadable certificate is a refusal,
        // never a pass.
        val refusal = ApkVerification.refusalReason(
            installedVersionCode = installed,
            offer = offer,
            computedApkSha256Hex = downloaded.sha256Hex,
            installedCertSha256Hex = inspector.installedCertSha256Hex() ?: "",
            candidateCertSha256Hex = inspector.candidateCertSha256Hex(downloaded.file) ?: ""
        )
        if (refusal != null) {
            downloaded.file.delete()
            return UpdateRunResult(UpdateRunOutcome.REFUSED, refusal)
        }
        return when (val start = installer.install(downloaded.file)) {
            is InstallStart.Failed -> {
                downloaded.file.delete()
                UpdateRunResult(UpdateRunOutcome.INSTALL_FAILED, start.detail)
            }
            InstallStart.Committed -> {
                // The platform gateway has copied the bytes into the install
                // session by now, so the cache file can go.
                downloaded.file.delete()
                pendingStore.write(PendingUpdate(offer.versionCode, offer.versionName))
                UpdateRunResult(
                    UpdateRunOutcome.INSTALL_STARTED,
                    "Ilona ${offer.versionName} is being installed. Ilona will check itself when it starts again."
                )
            }
        }
    }

    /**
     * Post-update resolution, run when the app next starts after an install
     * was committed. Android kills and restarts the app around an install, so
     * the self-test honestly belongs here, not in the installing process.
     * Returns null when no update is pending.
     */
    fun checkAfterLaunch(): PostUpdateResult? {
        val pending = pendingStore.read() ?: return null
        val installed = inspector.installedVersionCode()
        if (installed < pending.versionCode) {
            return PostUpdateResult(
                PostUpdateOutcome.STILL_INSTALLING,
                "An update to Ilona ${pending.versionName} was started but has not finished installing yet."
            )
        }
        val passed = selfTest.runSelfTest()
        pendingStore.clear()
        return if (passed) {
            alerts.afterNote(AFTER_NOTE_TEXT)
            PostUpdateResult(
                PostUpdateOutcome.UPDATED_OK,
                "Ilona was updated to version ${pending.versionName} and passed its self-check."
            )
        } else {
            alerts.caregiverAlert(
                "Ilona update needs a look",
                "Ilona was updated to version ${pending.versionName}, but its self-check did not pass. " +
                    "Nothing was rolled back — a bad update is fixed with a newer version."
            )
            PostUpdateResult(
                PostUpdateOutcome.UPDATED_SELF_TEST_FAILED,
                "Ilona was updated to version ${pending.versionName}, but its self-check did not pass. The caregiver was told."
            )
        }
    }
}

/**
 * When the scheduled check may actually run (brief SS10: prefer night /
 * Wi-Fi / charger). JobScheduler enforces charging + unmetered network as
 * job constraints; this policy adds the night window and is the single
 * decision the job service consults — unit-tested here, not re-implemented
 * in the service.
 */
object UpdateSchedulePolicy {
    /** Night window: 01:00 (inclusive) to 05:00 (exclusive), local time. */
    fun isNightWindow(hourOfDay: Int): Boolean = hourOfDay in 1 until 5

    fun shouldRunNow(hourOfDay: Int, charging: Boolean, unmeteredNetwork: Boolean): Boolean =
        isNightWindow(hourOfDay) && charging && unmeteredNetwork
}
