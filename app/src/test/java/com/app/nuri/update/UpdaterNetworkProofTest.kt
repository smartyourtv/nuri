package com.app.nuri.update

import com.app.nuri.update.android.HttpUpdateFeedGateway
import com.app.nuri.update.android.StreamingApkDownloader
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Order §B2 — the private flavor's auto-update path proven end to
 * end against a REAL local HTTPS server: the production
 * [HttpUpdateFeedGateway] and [StreamingApkDownloader] do real
 * TLS, real HTTP, real streaming SHA-256, and the production
 * [SelfUpdateExecutor] makes its decisions on what they return.
 * Only the platform edges that cannot exist on a JVM (APK
 * inspection via PackageManager, the install session, the
 * self-test) are fakes — and each fake records what it was asked.
 *
 * The server certificate is generated with the JDK's keytool in
 * setup; the JVM's default TLS factory is pointed at it for this
 * test class only.
 */
class UpdaterNetworkProofTest {

    companion object {
        private var serverSocket: SSLServerSocket? = null
        @Volatile private var serverRun = false
        private lateinit var baseUrl: String
        private lateinit var workDir: File

        /** The "APK" the server serves: deterministic bytes, not a real APK (the inspector is the platform edge). */
        val apkBytes: ByteArray = ByteArray(150_000) { (it * 31 % 251).toByte() }
        val apkSha: String = MessageDigest.getInstance("SHA-256").digest(apkBytes)
            .joinToString("") { "%02x".format(it) }
        @Volatile var apkHits = 0
        @Volatile var feedHits = 0
        @Volatile var feedBody: String = ""

        @JvmStatic
        @BeforeClass
        fun startServer() {
            workDir = Files.createTempDirectory("ilona-b2").toFile()
            val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
            val keystore = File(workDir, "server.p12")
            val gen = ProcessBuilder(
                keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-keystore", keystore.absolutePath, "-storetype", "PKCS12",
                "-storepass", "testpass", "-keypass", "testpass", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            ).redirectErrorStream(true).start()
            gen.inputStream.readBytes()
            check(gen.waitFor() == 0) { "keytool keygen failed" }
            val export = ProcessBuilder(
                keytool, "-exportcert", "-alias", "server", "-keystore", keystore.absolutePath,
                "-storetype", "PKCS12", "-storepass", "testpass", "-file", File(workDir, "server.crt").absolutePath,
            ).redirectErrorStream(true).start()
            export.inputStream.readBytes()
            check(export.waitFor() == 0) { "keytool export failed" }
            val trust = File(workDir, "trust.p12")
            val imp = ProcessBuilder(
                keytool, "-importcert", "-noprompt", "-alias", "server", "-file", File(workDir, "server.crt").absolutePath,
                "-keystore", trust.absolutePath, "-storetype", "PKCS12", "-storepass", "testpass",
            ).redirectErrorStream(true).start()
            imp.inputStream.readBytes()
            check(imp.waitFor() == 0) { "keytool import failed" }

            val ks = KeyStore.getInstance("PKCS12").apply {
                load(keystore.inputStream(), "testpass".toCharArray())
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(ks, "testpass".toCharArray()) }
            val ts = KeyStore.getInstance("PKCS12").apply {
                load(trust.inputStream(), "testpass".toCharArray())
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(ts) }
            val serverCtx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
            val clientCtx = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
            HttpsURLConnection.setDefaultSSLSocketFactory(clientCtx.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }

            // A minimal hand-rolled HTTPS responder: the gateways
            // under test speak plain HTTP/1.1 over TLS, so a server
            // socket + request-line routing is the whole job. (The
            // com.sun server classes aren't on the unit-test
            // classpath; javax.net.ssl is.)
            val socket = serverCtx.serverSocketFactory.createServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            serverSocket = socket
            serverRun = true
            baseUrl = "https://127.0.0.1:${socket.localPort}"
            Thread {
                while (serverRun) {
                    val conn = try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    try {
                        val reader = conn.inputStream.bufferedReader()
                        val requestLine = reader.readLine() ?: ""
                        // Consume headers.
                        while (true) {
                            val line = reader.readLine()
                            if (line.isNullOrEmpty()) break
                        }
                        val path = requestLine.split(" ").getOrNull(1) ?: "/"
                        val out = conn.outputStream
                        fun respond(code: Int, body: ByteArray, type: String = "application/octet-stream") {
                            val head = "HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                            out.write(head.toByteArray(Charsets.US_ASCII))
                            out.write(body)
                            out.flush()
                        }
                        when (path) {
                            "/feed.json" -> {
                                feedHits++
                                respond(200, feedBody.toByteArray(Charsets.UTF_8), "application/json")
                            }
                            "/app.apk" -> {
                                apkHits++
                                respond(200, apkBytes)
                            }
                            else -> respond(404, ByteArray(0))
                        }
                    } catch (_: Exception) {
                        // Client went away mid-request; keep serving.
                    } finally {
                        runCatching { conn.close() }
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            serverRun = false
            runCatching { serverSocket?.close() }
            workDir.deleteRecursively()
        }

        fun feedJson(versionCode: Int, sha: String, size: Long, apkPath: String = "/app.apk"): String =
            """{"versionCode":$versionCode,"versionName":"9.9.$versionCode","apkUrl":"$baseUrl$apkPath","sha256":"$sha","sizeBytes":$size}"""
    }

    // ── Fakes for the platform edges ─────────────────────────────

    private class FakeInspector(
        var installed: Int,
        val cert: String = "f0b30aa6-pinned",
        var candidateCert: String? = "f0b30aa6-pinned",
    ) : ApkInspectionGateway {
        override fun installedVersionCode(): Int = installed
        override fun installedCertSha256Hex(): String = cert
        override fun candidateCertSha256Hex(apk: File): String? = candidateCert
    }

    private class FakeInstaller(var result: InstallStart = InstallStart.Committed) : InstallGateway {
        var calls = 0
        override fun install(apk: File): InstallStart {
            calls++
            return result
        }
    }

    private class FakeSelfTest(var pass: Boolean = true) : UpdateSelfTestGateway {
        override fun runSelfTest(): Boolean = pass
    }

    private class FakeAlerts : UpdateAlertGateway {
        val alerts = mutableListOf<String>()
        val notes = mutableListOf<String>()
        override fun caregiverAlert(title: String, detail: String) { alerts += "$title|$detail" }
        override fun afterNote(text: String) { notes += text }
    }

    private class FakePending : PendingUpdateStore {
        var stored: PendingUpdate? = null
        override fun read(): PendingUpdate? = stored
        override fun write(pending: PendingUpdate) { stored = pending }
        override fun clear() { stored = null }
        fun withPending(p: PendingUpdate): FakePending = apply { stored = p }
    }

    private fun executor(
        inspector: FakeInspector = FakeInspector(installed = 9),
        installer: FakeInstaller = FakeInstaller(),
        selfTest: FakeSelfTest = FakeSelfTest(),
        alerts: FakeAlerts = FakeAlerts(),
        pending: FakePending = FakePending(),
    ): SelfUpdateExecutor = SelfUpdateExecutor(
        autoUpdateEnabled = { true },
        feed = HttpUpdateFeedGateway("$baseUrl/feed.json"),
        downloader = StreamingApkDownloader(),
        inspector = inspector,
        installer = installer,
        selfTest = selfTest,
        alerts = alerts,
        pendingStore = pending,
        downloadDir = File(workDir, "dl-${System.nanoTime()}"),
    )

    // ── Gateway-level proofs ─────────────────────────────────────

    @Test
    fun feedGatewayParsesARealFeedOverTls() {
        feedBody = feedJson(10, apkSha, apkBytes.size.toLong())
        val offer = HttpUpdateFeedGateway("$baseUrl/feed.json").fetchOffer()
        assertNotNull(offer)
        assertEquals(10, offer!!.versionCode)
        assertEquals(apkSha, offer.sha256Hex)
        assertEquals(apkBytes.size.toLong(), offer.sizeBytes)
    }

    @Test
    fun feedGatewayRefusesPlainHttp() {
        // Not wired / not secure → null (the executor reports COULD_NOT_CHECK), never a fetch.
        assertNull(HttpUpdateFeedGateway("http://127.0.0.1/feed.json").fetchOffer())
        assertNull(HttpUpdateFeedGateway("").fetchOffer())
    }

    @Test
    fun downloaderStreamsAndHashes() {
        val dir = File(workDir, "one")
        val offer = UpdateOffer(10, "9.9.10", "$baseUrl/app.apk", apkSha, apkBytes.size.toLong())
        val result = StreamingApkDownloader().download(offer, dir)
        assertTrue(result is DownloadResult.Ok)
        val ok = result as DownloadResult.Ok
        assertEquals(apkSha, ok.sha256Hex)
        assertTrue(apkBytes.contentEquals(ok.file.readBytes()))
    }

    @Test
    fun downloaderDeletesOnSizeMismatch() {
        val dir = File(workDir, "two")
        val offer = UpdateOffer(10, "9.9.10", "$baseUrl/app.apk", apkSha, apkBytes.size + 5L)
        val result = StreamingApkDownloader().download(offer, dir)
        assertTrue(result is DownloadResult.Failed)
        assertFalse(File(dir, "nuri-update-10.apk").exists())
    }

    @Test
    fun downloaderRefusesPlainHttpAnd404() {
        val dir = File(workDir, "three")
        val http = StreamingApkDownloader().download(UpdateOffer(10, "v", "http://127.0.0.1/app.apk", apkSha, 1), dir)
        assertTrue(http is DownloadResult.Failed)
        val missing = StreamingApkDownloader().download(UpdateOffer(10, "v", "$baseUrl/missing.apk", apkSha, 1), dir)
        assertTrue(missing is DownloadResult.Failed)
        assertFalse(File(dir, "nuri-update-10.apk").exists())
    }

    // ── Executor end-to-end (real network gateways) ──────────────

    @Test
    fun happyPathReachesInstallWithVerifiedBytes() {
        feedBody = feedJson(10, apkSha, apkBytes.size.toLong())
        val installer = FakeInstaller()
        val pending = FakePending()
        val result = executor(installer = installer, pending = pending).runScheduledCheck()
        assertEquals(UpdateRunOutcome.INSTALL_STARTED, result.outcome)
        assertEquals(1, installer.calls)
        assertEquals(10, pending.stored?.versionCode)
    }

    @Test
    fun tamperedHashIsRefusedAndNeverInstalls() {
        feedBody = feedJson(10, "0".repeat(64), apkBytes.size.toLong())
        val installer = FakeInstaller()
        val result = executor(installer = installer).runScheduledCheck()
        assertEquals(UpdateRunOutcome.REFUSED, result.outcome)
        assertEquals(0, installer.calls)
    }

    @Test
    fun wrongSigningCertIsRefusedAndNeverInstalls() {
        feedBody = feedJson(10, apkSha, apkBytes.size.toLong())
        val installer = FakeInstaller()
        val inspector = FakeInspector(installed = 9, candidateCert = "deadbeef-other-signer")
        val result = executor(inspector = inspector, installer = installer).runScheduledCheck()
        assertEquals(UpdateRunOutcome.REFUSED, result.outcome)
        assertEquals(0, installer.calls)
    }

    @Test
    fun downgradeOfferIsNeverEvenDownloaded() {
        feedBody = feedJson(8, apkSha, apkBytes.size.toLong())
        val hitsBefore = apkHits
        val installer = FakeInstaller()
        val result = executor(installer = installer).runScheduledCheck()
        assertEquals(UpdateRunOutcome.UP_TO_DATE, result.outcome)
        assertEquals(0, installer.calls)
        assertEquals(hitsBefore, apkHits) // no download request was made
    }

    @Test
    fun sameVersionIsUpToDate() {
        feedBody = feedJson(9, apkSha, apkBytes.size.toLong())
        val result = executor().runScheduledCheck()
        assertEquals(UpdateRunOutcome.UP_TO_DATE, result.outcome)
    }

    @Test
    fun unreadableFeedIsHonestCouldNotCheck() {
        feedBody = "this is not json"
        val result = executor().runScheduledCheck()
        assertEquals(UpdateRunOutcome.COULD_NOT_CHECK, result.outcome)
    }

    @Test
    fun postUpdateSelfTestPassAndFail() {
        val pending = FakePending().withPending(PendingUpdate(10, "9.9.10"))
        val okInspector = FakeInspector(installed = 10)
        val pass = executor(inspector = okInspector, pending = pending, selfTest = FakeSelfTest(true)).checkAfterLaunch()
        assertEquals(PostUpdateOutcome.UPDATED_OK, pass?.outcome)

        val pending2 = FakePending().withPending(PendingUpdate(10, "9.9.10"))
        val fail = executor(inspector = okInspector, pending = pending2, selfTest = FakeSelfTest(false)).checkAfterLaunch()
        assertEquals(PostUpdateOutcome.UPDATED_SELF_TEST_FAILED, fail?.outcome)

        // Still installing: the installed version hasn't moved yet.
        val pending3 = FakePending().withPending(PendingUpdate(10, "9.9.10"))
        val still = executor(inspector = FakeInspector(installed = 9), pending = pending3).checkAfterLaunch()
        assertEquals(PostUpdateOutcome.STILL_INSTALLING, still?.outcome)
    }

    @Test
    fun installStartFailureIsHonest() {
        feedBody = feedJson(10, apkSha, apkBytes.size.toLong())
        val installer = FakeInstaller(InstallStart.Failed("session could not be created"))
        val result = executor(installer = installer).runScheduledCheck()
        assertEquals(UpdateRunOutcome.INSTALL_FAILED, result.outcome)
    }

    // ── One-tap fallback mapping (extracted from the receiver) ───

    @Test
    fun installStatusMappingCoversTheFallback() {
        assertEquals(InstallAction.SHOW_SYSTEM_PROMPT, InstallStatusMapping.actionFor(InstallStatus.PENDING_USER_ACTION))
        assertEquals(InstallAction.MARK_INSTALLED, InstallStatusMapping.actionFor(InstallStatus.SUCCESS))
        assertEquals(InstallAction.CLEAR_AND_ALERT, InstallStatusMapping.actionFor(InstallStatus.FAILED))
    }
}
