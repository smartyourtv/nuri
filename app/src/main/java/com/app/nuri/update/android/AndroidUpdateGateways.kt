package com.app.nuri.update.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.app.nuri.update.ApkDownloadGateway
import com.app.nuri.update.ApkInspectionGateway
import com.app.nuri.update.ApkVerification
import com.app.nuri.update.DownloadResult
import com.app.nuri.update.InstallGateway
import com.app.nuri.update.InstallStart
import com.app.nuri.update.UpdateFeed
import com.app.nuri.update.UpdateFeedGateway
import com.app.nuri.update.UpdateOffer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/**
 * Android implementations of the executor's gateways (brief SS10). All
 * network access is HTTPS-only: a blank or non-HTTPS address means "not
 * wired" and honestly reports that it could not check — the same stance as
 * the pre-existing NotWiredManifestFetcher. No fake successes anywhere.
 */
class HttpUpdateFeedGateway(private val feedUrl: String) : UpdateFeedGateway {
    override fun fetchOffer(): UpdateOffer? {
        if (!feedUrl.startsWith("https://")) return null // blank = not wired yet
        return try {
            val conn = (URL(feedUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            Json { ignoreUnknownKeys = true }
                .decodeFromString(UpdateFeed.serializer(), text)
                .toOffer()
        } catch (_: Exception) {
            null // offline / bad feed — the executor reports "could not check"
        }
    }
}

class StreamingApkDownloader : ApkDownloadGateway {
    override fun download(offer: UpdateOffer, destDir: File): DownloadResult {
        if (!offer.apkUrl.startsWith("https://")) {
            return DownloadResult.Failed("The update address was not a secure one, so nothing was downloaded.")
        }
        destDir.mkdirs()
        val dest = File(destDir, "nuri-update-${offer.versionCode}.apk")
        return try {
            val conn = (URL(offer.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                requestMethod = "GET"
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                dest.delete()
                return DownloadResult.Failed("The update could not be downloaded. Nothing was changed.")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        total += n
                    }
                }
            }
            if (offer.sizeBytes > 0 && total != offer.sizeBytes) {
                dest.delete()
                return DownloadResult.Failed("The update download was incomplete, so it was not installed.")
            }
            DownloadResult.Ok(dest, digest.digest().joinToString("") { "%02x".format(it) })
        } catch (_: Exception) {
            dest.delete()
            DownloadResult.Failed("The update could not be downloaded. Nothing was changed.")
        }
    }
}

class AndroidApkInspector(context: Context) : ApkInspectionGateway {
    private val app = context.applicationContext
    private val pm: PackageManager get() = app.packageManager

    override fun installedVersionCode(): Int =
        try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(app.packageName, 0).longVersionCode.toInt()
        } catch (_: Exception) {
            0
        }

    override fun installedCertSha256Hex(): String? =
        try {
            certSha256(pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
        } catch (_: Exception) {
            null
        }

    override fun candidateCertSha256Hex(apk: File): String? =
        try {
            val info = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            info?.let { certSha256(it) }
        } catch (_: Exception) {
            null
        }

    /** Signer certificate SHA-256 (hex), computed the same way for installed and candidate APKs. */
    private fun certSha256(info: PackageInfo): String? {
        val signing = info.signingInfo ?: return null
        val certs = if (signing.hasMultipleSigners()) {
            signing.apkContentsSigners
        } else {
            signing.signingCertificateHistory ?: signing.apkContentsSigners
        }
        val cert = certs?.firstOrNull() ?: return null
        return ApkVerification.sha256Hex(cert.toByteArray())
    }
}

/**
 * PackageInstaller gateway. The session asks for no user action on Android
 * 12+ (API 31 `USER_ACTION_NOT_REQUIRED`); devices that don't honor it send
 * STATUS_PENDING_USER_ACTION to [UpdateInstallReceiver], which surfaces the
 * one-tap prompt. The final outcome always arrives via that receiver and the
 * executor's next-launch check — this gateway only reports that the session
 * committed.
 */
class PackageInstallerGateway(context: Context) : InstallGateway {
    private val app = context.applicationContext

    override fun install(apk: File): InstallStart {
        return try {
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            params.setInstallReason(PackageManager.INSTALL_REASON_USER)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("nuri-update", 0, apk.length()).use { out ->
                    apk.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                val statusIntent = Intent(app, UpdateInstallReceiver::class.java)
                    .setAction(ACTION_UPDATE_STATUS)
                val pending = android.app.PendingIntent.getBroadcast(
                    app, sessionId, statusIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
            }
            InstallStart.Committed
        } catch (_: Exception) {
            InstallStart.Failed("The update could not be started. Nothing was changed.")
        }
    }

    companion object {
        const val ACTION_UPDATE_STATUS = "com.app.nuri.UPDATE_STATUS"
    }
}
