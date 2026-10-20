package com.app.nuri.tts

import com.app.nuri.tts.android.VoiceModelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v0.4.1 hotfix regression coverage.
 *
 * (a) The v0.4.0 on-device failure: the Kokoro v1.1 pack downloaded
 * and archive-verified, yet isReady() rejected it because the
 * extracted-file pins were crossed from the v1.0 pack the eval had
 * measured. The REAL v1.1 sizes — from the genuine archive's tar
 * listing (archive SHA-256 a3f4c73d…):
 *   model.onnx 325,631,784 B · voices.bin 53,790,720 B
 * must report READY; the old wrong sizes must NOT.
 *
 * (b) The startup housekeeping sweep (Shawn, 2026-10-19: downloaded
 * files must not accumulate): staging dirs, *.partial files, and
 * READY packs' archives are planned for deletion; READY packs'
 * extracted files and not-yet-ready packs' archives are preserved.
 */
class VoiceModelManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sizedFile(parent: File, name: String, bytes: Long): File {
        parent.mkdirs()
        val file = File(parent, name)
        java.io.RandomAccessFile(file, "rw").use { it.setLength(bytes) }
        return file
    }

    /** An extracted Kokoro pack layout under [root], with tunable key sizes. */
    private fun kokoroLayout(root: File, modelBytes: Long, voicesBytes: Long): File {
        val dir = File(root, VoiceModelManager.TTS_DIR_NAME)
        sizedFile(dir, "model.onnx", modelBytes)
        sizedFile(dir, "voices.bin", voicesBytes)
        sizedFile(dir, "tokens.txt", 1_111L)
        File(dir, "espeak-ng-data").mkdirs()
        return dir
    }

    // (a) readiness pins -------------------------------------------------

    @Test fun realV11LayoutReportsReady() {
        val root = tmp.newFolder()
        kokoroLayout(root, modelBytes = 325_631_784L, voicesBytes = 53_790_720L)
        assertTrue(VoiceModelManager.isReadyIn(root, VoiceModelManager.Pack.TTS_KOKORO))
        assertEquals(
            "model.onnx",
            VoiceModelManager.kokoroModelFileIn(File(root, VoiceModelManager.TTS_DIR_NAME))?.name
        )
    }

    @Test fun oldWrongV040PinsDoNotReportReady() {
        // The exact wrong pair v0.4.0 demanded — a layout matching
        // BOTH old numbers must still be rejected: the genuine pack
        // never looks like this, and accepting it would be a lie.
        val root = tmp.newFolder()
        kokoroLayout(root, modelBytes = 325_631_433L, voicesBytes = 28_880_940L)
        assertFalse(VoiceModelManager.isReadyIn(root, VoiceModelManager.Pack.TTS_KOKORO))
    }

    @Test fun v10EvalSizesDoNotReportReady() {
        // The v1.0 pack the eval actually measured — also not the
        // pack the app downloads.
        val root = tmp.newFolder()
        kokoroLayout(root, modelBytes = 325_560_556L, voicesBytes = 28_200_960L)
        assertFalse(VoiceModelManager.isReadyIn(root, VoiceModelManager.Pack.TTS_KOKORO))
    }

    @Test fun realModelWithWrongVoicesDoesNotReportReady() {
        val root = tmp.newFolder()
        kokoroLayout(root, modelBytes = 325_631_784L, voicesBytes = 28_880_940L)
        assertFalse(VoiceModelManager.isReadyIn(root, VoiceModelManager.Pack.TTS_KOKORO))
    }

    @Test fun pinsMatchGenuineV11Listing() {
        assertEquals(325_631_784L, VoiceModelManager.Pins.KOKORO_MODEL_FP32_BYTES)
        assertEquals(53_790_720L, VoiceModelManager.Pins.KOKORO_VOICES_BYTES)
        assertEquals(364_816_464L, VoiceModelManager.Pins.KOKORO_ARCHIVE_BYTES)
        assertEquals(
            "a3f4c73d043860e3fd2e5b06f36795eb81de0fc8e8de6df703245edddd87dbad",
            VoiceModelManager.Pins.KOKORO_ARCHIVE_SHA256
        )
    }

    // (b) the sweep ------------------------------------------------------

    @Test fun sweepRemovesJunkAndPreservesReadyPacksAndUnreadyArchives() {
        val root = tmp.newFolder()
        // Kokoro READY (real v1.1 layout) + its leftover archive.
        kokoroLayout(root, modelBytes = 325_631_784L, voicesBytes = 53_790_720L)
        sizedFile(root, VoiceModelManager.KOKORO_ARCHIVE_NAME, 100L)
        // KWS NOT ready, but its archive is already downloaded —
        // it must survive the sweep (no pointless re-download).
        sizedFile(root, VoiceModelManager.KWS_ARCHIVE_NAME, 100L)
        // Junk: a staging dir, a partial download, an unknown file
        // and an unknown dir.
        File(root, "${VoiceModelManager.TTS_DIR_NAME}-staging").apply {
            mkdirs(); File(this, "half.bin").writeBytes(byteArrayOf(1))
        }
        sizedFile(root, "something.tar.bz2.partial", 10L)
        sizedFile(root, "mystery.bin", 10L)
        File(root, "leftover-dir").mkdirs()

        val plan = VoiceModelManager.sweepPlan(root).map { it.name }.toSet()
        assertEquals(
            setOf(
                VoiceModelManager.KOKORO_ARCHIVE_NAME, // READY pack's archive
                "${VoiceModelManager.TTS_DIR_NAME}-staging",
                "something.tar.bz2.partial",
                "mystery.bin",
                "leftover-dir"
            ),
            plan
        )
    }

    @Test fun sweepKeepsUnreadyPackArchiveAndPackDirs() {
        val root = tmp.newFolder()
        // Nothing ready: both archives + all three pack dirs stay.
        sizedFile(root, VoiceModelManager.KOKORO_ARCHIVE_NAME, 100L)
        sizedFile(root, VoiceModelManager.KWS_ARCHIVE_NAME, 100L)
        File(root, VoiceModelManager.TTS_DIR_NAME).mkdirs()
        File(root, VoiceModelManager.STT_DIR_NAME).mkdirs()
        File(root, VoiceModelManager.KWS_DIR_NAME).mkdirs()
        assertTrue(VoiceModelManager.sweepPlan(root).isEmpty())
    }

    @Test fun sweepOnMissingRootIsEmpty() {
        val missing = File(tmp.root, "does-not-exist")
        assertTrue(VoiceModelManager.sweepPlan(missing).isEmpty())
    }
}
