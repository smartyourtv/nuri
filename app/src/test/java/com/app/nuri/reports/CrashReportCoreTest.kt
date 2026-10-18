package com.app.nuri.reports

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Crash-report core (standing rule, 2026-10-17): breadcrumbs, the file
 * format, and the app-private store. All pure JVM against temp dirs.
 */
class CrashReportCoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun report(
        epochMillis: Long = 1_700_000_000_000L,
        kind: ReportKind = ReportKind.CRASH,
        summary: String = CrashReport.crashSummary(),
        throwable: Throwable? = IllegalStateException("boom")
    ) = CrashReport(
        epochMillis = epochMillis,
        kind = kind,
        summaryLine = summary,
        flavor = "private",
        versionName = "0.1.0-scaffold",
        versionCode = 1,
        deviceModel = "SM-G973F",
        androidRelease = "12",
        sdkInt = 31,
        exceptionClass = throwable?.javaClass?.name,
        exceptionMessage = throwable?.message,
        stackTrace = throwable?.let { CrashReport.stackTraceOf(it) },
        breadcrumbs = listOf("+0 ms — crash reporting installed", "+40 ms — Nuri started")
    )

    // --- breadcrumbs ----------------------------------------------------

    @Test fun breadcrumbsAreRelativeToFirstMark() {
        var now = 1_000L
        val crumbs = StartupBreadcrumbs { now }
        crumbs.mark("first")
        now = 1_250L
        crumbs.mark("second")
        assertEquals(listOf("+0 ms — first", "+250 ms — second"), crumbs.snapshot())
    }

    @Test fun breadcrumbsAreBounded() {
        val crumbs = StartupBreadcrumbs { 5L }
        repeat(StartupBreadcrumbs.MAX_ENTRIES + 5) { crumbs.mark("step $it") }
        val snap = crumbs.snapshot()
        assertEquals(StartupBreadcrumbs.MAX_ENTRIES, snap.size)
        assertTrue(snap.first().endsWith("step 5")) // the oldest five were dropped
    }

    // --- formatter --------------------------------------------------------

    @Test fun fileNameMatchesRulePatternAndRoundTrips() {
        val epoch = 1_700_000_000_000L // whole second
        val name = ReportFormatter.fileNameFor(epoch)
        assertTrue(name.matches(Regex("""Nuri-crash-\d{8}-\d{6}\.txt""")))
        assertEquals(epoch, FileReportStore.epochFromName(name))
    }

    @Test fun formattedReportLeadsWithPlainLanguageAndCarriesEvidence() {
        val text = ReportFormatter.format(report())
        val lines = text.lines()
        assertEquals(CrashReport.crashSummary(), lines.first())
        assertTrue(text.contains("Kind: crash"))
        assertTrue(text.contains("Nuri 0.1.0-scaffold (version code 1), private edition"))
        assertTrue(text.contains("SM-G973F, Android 12 (API 31)"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
        assertTrue(text.contains("+40 ms — Nuri started"))
        assertTrue(text.contains("IllegalStateException")) // stack trace block
    }

    @Test fun repairSummariesSayWhatHappenedAndMarkTheOutcome() {
        assertTrue(
            CrashReport.repairSummary("Damaged settings file", RepairStatus.REPAIRED)
                .contains("fixed it")
        )
        assertTrue(
            CrashReport.repairSummary("Damaged settings file", RepairStatus.REPAIR_FAILED)
                .contains("could not fix it")
        )
    }

    @Test fun uniqueFileNameAvoidsOverwritingEvidence() {
        val base = "Nuri-crash-20261017-120000.txt"
        assertEquals(base, ReportFormatter.uniqueFileName(base) { false })
        assertEquals(
            "Nuri-crash-20261017-120000-2.txt",
            ReportFormatter.uniqueFileName(base) { it == base }
        )
        assertEquals(
            "Nuri-crash-20261017-120000-3.txt",
            ReportFormatter.uniqueFileName(base) { it == base || it.endsWith("-2.txt") }
        )
    }

    // --- store --------------------------------------------------------------

    @Test fun storeSavesListsNewestFirstReadsAndDeletes() {
        val store = FileReportStore(tmp.newFolder("reports"))
        val older = store.save(ReportFormatter.format(report(epochMillis = 1_700_000_000_000L)), 1_700_000_000_000L)
        val newer = store.save(ReportFormatter.format(report(epochMillis = 1_700_000_060_000L)), 1_700_000_060_000L)
        val listed = store.list()
        assertEquals(2, listed.size)
        assertEquals(newer.name, listed[0].file.name)
        assertEquals(CrashReport.crashSummary(), listed[0].summaryLine)
        assertTrue(store.read(older).contains("Kind: crash"))
        assertTrue(store.delete(older))
        assertEquals(1, store.list().size)
        assertFalse(older.exists())
    }

    @Test fun storeIgnoresForeignFiles() {
        val dir = tmp.newFolder("reports2")
        File(dir, "unrelated.txt").writeText("hello")
        File(dir, "Nuri-crash-notadate.txt").writeText("odd but ours")
        val store = FileReportStore(dir)
        // The odd-named file still lists (falls back to lastModified); the foreign one does not.
        assertEquals(1, store.list().size)
    }

    @Test fun storeRetentionKeepsNewestFifty() {
        val store = FileReportStore(tmp.newFolder("reports3"))
        repeat(FileReportStore.MAX_REPORTS + 5) { i ->
            store.save("report $i", 1_700_000_000_000L + i * 60_000L)
        }
        assertEquals(FileReportStore.MAX_REPORTS, store.list().size)
    }
}
