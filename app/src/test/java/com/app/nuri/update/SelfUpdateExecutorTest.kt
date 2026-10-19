package com.app.nuri.update

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executor decision paths (brief SS10), driven entirely by fakes — no
 * Android, no network, no PackageInstaller. The happy-path hash is the real
 * SHA-256 of "abc" so the verification gate runs for real.
 */
class SelfUpdateExecutorTest {

    private class FakeFeed(var offer: UpdateOffer?) : UpdateFeedGateway {
        var calls = 0
        override fun fetchOffer(): UpdateOffer? { calls++; return offer }
    }

    private class FakeDownloader(var result: DownloadResult) : ApkDownloadGateway {
        var calls = 0
        override fun download(offer: UpdateOffer, destDir: File): DownloadResult { calls++; return result }
    }

    private class FakeInspector(
        var installed: Int = 9,
        var installedCert: String? = "CERT-A",
        var candidateCert: String? = "CERT-A"
    ) : ApkInspectionGateway {
        override fun installedVersionCode(): Int = installed
        override fun installedCertSha256Hex(): String? = installedCert
        override fun candidateCertSha256Hex(apk: File): String? = candidateCert
    }

    private class FakeInstaller(var result: InstallStart = InstallStart.Committed) : InstallGateway {
        var calls = 0
        override fun install(apk: File): InstallStart { calls++; return result }
    }

    private class FakeSelfTest(var ok: Boolean = true) : UpdateSelfTestGateway {
        var calls = 0
        override fun runSelfTest(): Boolean { calls++; return ok }
    }

    private class FakeAlerts : UpdateAlertGateway {
        val caregiver = mutableListOf<Pair<String, String>>()
        val notes = mutableListOf<String>()
        override fun caregiverAlert(title: String, detail: String) { caregiver += title to detail }
        override fun afterNote(text: String) { notes += text }
    }

    private class FakePendingStore : PendingUpdateStore {
        var value: PendingUpdate? = null
        override fun read(): PendingUpdate? = value
        override fun write(pending: PendingUpdate) { value = pending }
        override fun clear() { value = null }
    }

    private class Rig(
        policyOn: Boolean = true,
        feedOffer: UpdateOffer? = DEFAULT_OFFER,
        downloadResult: DownloadResult? = null, // null = a real Ok result over a real file
        val inspector: FakeInspector = FakeInspector(),
        val installer: FakeInstaller = FakeInstaller(),
        val selfTest: FakeSelfTest = FakeSelfTest(),
        downloadHash: String = GOOD_SHA
    ) {
        val dir: File = Files.createTempDirectory("nuri-exec-test").toFile()
        val downloadedFile: File = File(dir, "nuri-update-10.apk").apply { writeText("abc") }
        val feed = FakeFeed(feedOffer)
        val downloader = FakeDownloader(downloadResult ?: DownloadResult.Ok(downloadedFile, downloadHash))
        val alerts = FakeAlerts()
        val pending = FakePendingStore()
        val executor = SelfUpdateExecutor(
            autoUpdateEnabled = { policyOn },
            feed = feed, downloader = downloader, inspector = inspector,
            installer = installer, selfTest = selfTest, alerts = alerts,
            pendingStore = pending, downloadDir = dir
        )
    }

    companion object {
        // SHA-256("abc") — the well-known test vector, computed by the real gate code.
        val GOOD_SHA: String = ApkVerification.sha256Hex("abc".toByteArray(Charsets.UTF_8))
        val DEFAULT_OFFER = UpdateOffer(10, "1.10", "https://example.invalid/nuri.apk", GOOD_SHA, 3)
    }

    @Test fun policyOffSkipsEverything() {
        val r = Rig(policyOn = false)
        val result = r.executor.runScheduledCheck()
        assertEquals(UpdateRunOutcome.SKIPPED_POLICY_OFF, result.outcome)
        assertEquals(0, r.feed.calls)
        assertEquals(0, r.downloader.calls)
        assertEquals(0, r.installer.calls)
    }

    @Test fun feedUnavailableReportsCouldNotCheck() {
        val r = Rig(feedOffer = null)
        val result = r.executor.runScheduledCheck()
        assertEquals(UpdateRunOutcome.COULD_NOT_CHECK, result.outcome)
        assertEquals(0, r.downloader.calls)
    }

    @Test fun sameOrOlderOfferIsUpToDateWithoutDownloading() {
        val same = Rig(feedOffer = UpdateOffer(9, "1.9", "https://example.invalid/nuri.apk", GOOD_SHA, 3))
        assertEquals(UpdateRunOutcome.UP_TO_DATE, same.executor.runScheduledCheck().outcome)
        assertEquals(0, same.downloader.calls)
        val older = Rig(feedOffer = UpdateOffer(8, "1.8", "https://example.invalid/nuri.apk", GOOD_SHA, 3))
        assertEquals(UpdateRunOutcome.UP_TO_DATE, older.executor.runScheduledCheck().outcome)
        assertEquals(0, older.downloader.calls)
    }

    @Test fun downloadFailureStopsBeforeInstall() {
        val r = Rig(downloadResult = DownloadResult.Failed("The update could not be downloaded. Nothing was changed."))
        val result = r.executor.runScheduledCheck()
        assertEquals(UpdateRunOutcome.DOWNLOAD_FAILED, result.outcome)
        assertEquals(0, r.installer.calls)
        assertNull(r.pending.value)
    }

    @Test fun hashMismatchRefusedAndFileDeleted() {
        val r = Rig(downloadHash = "00")
        val result = r.executor.runScheduledCheck()
        assertEquals(UpdateRunOutcome.REFUSED, result.outcome)
        assertTrue(result.detail.contains("fingerprint"))
        assertEquals(0, r.installer.calls)
        assertFalse(r.downloadedFile.exists())
        assertNull(r.pending.value)
    }

    @Test fun certMismatchRefused() {
        val r = Rig(inspector = FakeInspector(candidateCert = "CERT-OTHER"))
        val result = r.executor.runScheduledCheck()
        assertEquals(UpdateRunOutcome.REFUSED, result.outcome)
        assertTrue(result.detail.contains("signed"))
        assertEquals(0, r.installer.calls)
    }

    @Test fun unreadableCandidateCertRefused() {
        val r = Rig(inspector = FakeInspector(candidateCert = null))
        val result = r.executor.runScheduledCheck()
        assertEquals(UpdateRunOutcome.REFUSED, result.outcome)
        assertEquals(0, r.installer.calls)
    }

    @Test fun happyPathStartsInstallAndRecordsPending() {
        val r = Rig()
        val result = r.executor.runScheduledCheck()
        assertEquals(UpdateRunOutcome.INSTALL_STARTED, result.outcome)
        assertEquals(1, r.installer.calls)
        assertEquals(PendingUpdate(10, "1.10"), r.pending.value)
        assertFalse(r.downloadedFile.exists()) // cache cleaned after commit
    }

    @Test fun installerFailureLeavesNoPending() {
        val r = Rig(installer = FakeInstaller(InstallStart.Failed("The update could not be started. Nothing was changed.")))
        val result = r.executor.runScheduledCheck()
        assertEquals(UpdateRunOutcome.INSTALL_FAILED, result.outcome)
        assertNull(r.pending.value)
    }

    @Test fun afterLaunchNoPendingIsNull() {
        val r = Rig()
        assertNull(r.executor.checkAfterLaunch())
        assertEquals(0, r.selfTest.calls)
    }

    @Test fun afterLaunchStillInstallingKeepsPendingAndSkipsSelfTest() {
        val r = Rig(inspector = FakeInspector(installed = 9))
        r.pending.value = PendingUpdate(10, "1.10")
        val result = r.executor.checkAfterLaunch()
        assertNotNull(result)
        assertEquals(PostUpdateOutcome.STILL_INSTALLING, result!!.outcome)
        assertEquals(0, r.selfTest.calls)
        assertEquals(PendingUpdate(10, "1.10"), r.pending.value)
    }

    @Test fun afterLaunchUpdatedAndSelfTestPassesLeavesAfterNote() {
        val r = Rig(inspector = FakeInspector(installed = 10), selfTest = FakeSelfTest(ok = true))
        r.pending.value = PendingUpdate(10, "1.10")
        val result = r.executor.checkAfterLaunch()
        assertEquals(PostUpdateOutcome.UPDATED_OK, result!!.outcome)
        assertEquals(listOf("Ilona was updated, nothing looks different."), r.alerts.notes)
        assertTrue(r.alerts.caregiver.isEmpty())
        assertNull(r.pending.value)
    }

    @Test fun afterLaunchUpdatedButSelfTestFailsAlertsCaregiverNoDowngrade() {
        val r = Rig(inspector = FakeInspector(installed = 10), selfTest = FakeSelfTest(ok = false))
        r.pending.value = PendingUpdate(10, "1.10")
        val result = r.executor.checkAfterLaunch()
        assertEquals(PostUpdateOutcome.UPDATED_SELF_TEST_FAILED, result!!.outcome)
        assertEquals(1, r.alerts.caregiver.size)
        assertTrue(r.alerts.caregiver[0].second.contains("newer version"))
        assertTrue(r.alerts.notes.isEmpty())
        assertNull(r.pending.value)
    }
}
