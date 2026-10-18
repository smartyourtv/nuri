package com.app.nuri.repair

import com.app.nuri.update.PendingUpdate
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Self-repair decisions (standing rule, 2026-10-17): the reconciler only
 * ever proposes conservative repairs, the corruption hand-off preserves
 * evidence, and every decision here is what the startup runner executes
 * and then verifies by re-reading.
 */
class SelfRepairTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun resetState() { SettingsRepairState.clearForTest() }

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun snapshot(
        pending: PendingUpdate? = null,
        writtenAt: Long = 0L,
        installed: Int = 1,
        jobScheduled: Boolean = true,
        downloads: List<String> = emptyList()
    ) = UpdateStateSnapshot(pending, writtenAt, installed, jobScheduled, downloads)

    @Test fun healthyStateNeedsNoRepair() {
        assertTrue(UpdateStateReconciler.decide(snapshot(), now).isEmpty())
    }

    @Test fun stalePendingMarkerIsCleared() {
        val actions = UpdateStateReconciler.decide(
            snapshot(pending = PendingUpdate(5, "0.5.0"), writtenAt = now - 8 * day, installed = 1),
            now
        )
        assertEquals(
            listOf(UpdateStateReconciler.Action.ClearStalePending(PendingUpdate(5, "0.5.0"))),
            actions
        )
    }

    @Test fun freshPendingMarkerIsLeftAlone() {
        val actions = UpdateStateReconciler.decide(
            snapshot(pending = PendingUpdate(5, "0.5.0"), writtenAt = now - 1 * day, installed = 1),
            now
        )
        assertTrue(actions.none { it is UpdateStateReconciler.Action.ClearStalePending })
    }

    @Test fun pendingThatInstalledIsTheExecutorsBusinessNotTheReconciler() {
        val actions = UpdateStateReconciler.decide(
            snapshot(pending = PendingUpdate(5, "0.5.0"), writtenAt = now - 30 * day, installed = 5),
            now
        )
        assertTrue(actions.none { it is UpdateStateReconciler.Action.ClearStalePending })
    }

    @Test fun pendingOfUnknownAgeIsNeverCleared() {
        // Records written before the timestamp existed read as 0 — without
        // a proven age, clearing would not be conservative.
        val actions = UpdateStateReconciler.decide(
            snapshot(pending = PendingUpdate(5, "0.5.0"), writtenAt = 0L, installed = 1),
            now
        )
        assertTrue(actions.none { it is UpdateStateReconciler.Action.ClearStalePending })
    }

    @Test fun orphanedDownloadsAreDeletedOnlyWhenNoInstallIsPending() {
        val withPending = UpdateStateReconciler.decide(
            snapshot(pending = PendingUpdate(5, "0.5.0"), writtenAt = now, downloads = listOf("nuri-update-5.apk")),
            now
        )
        assertTrue(withPending.none { it is UpdateStateReconciler.Action.DeleteStaleDownload })

        val withoutPending = UpdateStateReconciler.decide(
            snapshot(downloads = listOf("nuri-update-5.apk", "partial.bin")),
            now
        )
        assertEquals(
            listOf(
                UpdateStateReconciler.Action.DeleteStaleDownload("nuri-update-5.apk"),
                UpdateStateReconciler.Action.DeleteStaleDownload("partial.bin")
            ),
            withoutPending.filterIsInstance<UpdateStateReconciler.Action.DeleteStaleDownload>()
        )
    }

    @Test fun missingUpdateJobIsReported() {
        val actions = UpdateStateReconciler.decide(snapshot(jobScheduled = false), now)
        assertEquals(listOf(UpdateStateReconciler.Action.UpdateJobMissing), actions)
    }

    // --- corruption hand-off ---------------------------------------------

    @Test fun corruptionPreservesTheDamagedFileAndRecordsTheRootCause() {
        val filesDir = tmp.newFolder("files")
        val dsDir = File(filesDir, SettingsCorruption.DATASTORE_DIR).apply { mkdirs() }
        val damaged = File(dsDir, SettingsCorruption.SETTINGS_FILE)
        damaged.writeBytes(byteArrayOf(1, 2, 3, 4))

        val failure = RuntimeException("read failed", IOException("checksum mismatch"))
        val event = SettingsCorruption.onCorruption(filesDir, failure)

        assertEquals("java.io.IOException", event.exceptionClass)
        assertEquals("checksum mismatch", event.exceptionMessage)
        val preservedName = event.preservedCopyName
        assertTrue(preservedName != null)
        val preserved = File(File(filesDir, SettingsCorruption.PRESERVED_DIR), preservedName!!)
        assertTrue(preserved.exists())
        assertTrue(preserved.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
        // The damaged original is left in place (reversible — nothing destroyed).
        assertTrue(damaged.exists())
        // The runner consumes the event exactly once.
        assertEquals(event, SettingsRepairState.consume())
        assertNull(SettingsRepairState.consume())
    }

    @Test fun corruptionWithoutFilesStillRecordsTheEvent() {
        val event = SettingsCorruption.onCorruption(null, IOException("gone"))
        assertNull(event.preservedCopyName)
        assertEquals(event, SettingsRepairState.peek())
    }
}
