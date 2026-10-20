package com.app.nuri.tts

import com.app.nuri.tts.android.KokoroEngineConfig
import com.app.nuri.tts.android.VoiceModelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Voice-pack readiness + housekeeping coverage.
 *
 * (a) v0.4.1 regression: extracted-file pins crossed between packs
 * made isReady() reject a perfectly downloaded pack. The pins must
 * match the pack the app actually downloads — no other.
 *
 * (b) v0.5.1 regression (the June hotfix): v0.4.0–v0.5.0 downloaded
 * the v1.1 pack, whose speaker table is the Chinese edition
 * (3 -> zf_001; af_heart/June absent entirely). The app now
 * downloads the v1.0 pack (3 -> af_heart). The REAL v1.0 sizes —
 * from the genuine archive (SHA-256 c5f7e2d2…):
 *   model.onnx 325,560,556 B · voices.bin 28,200,960 B
 * must report READY; the v1.1 sizes (325,631,784 / 53,790,720) —
 * the wrong pack already sitting on devices — must NOT, so an
 * upgraded phone rejects it and fetches the right one.
 *
 * (c) The startup housekeeping sweep (Shawn, 2026-10-19: downloaded
 * files must not accumulate): staging dirs, *.partial files, READY
 * packs' archives, and — new in v0.5.1 — the legacy v1.1 pack dir
 * and archive are planned for deletion; READY packs' extracted
 * files and not-yet-ready packs' archives are preserved.
 */
class VoiceModelManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sizedFile(parent: File, name: String, bytes: Long): File {
        parent.mkdirs()
        val file = File(parent, name)
        java.io.RandomAccessFile(file, "rw").use { it.setLength(bytes) }
        return file
    }

    /** An extracted Kokoro pack layout under [root]/[dirName], with tunable key sizes. */
    private fun kokoroLayout(
        root: File,
        modelBytes: Long,
        voicesBytes: Long,
        dirName: String = VoiceModelManager.TTS_DIR_NAME,
        withLexicons: Boolean = true
    ): File {
        val dir = File(root, dirName)
        sizedFile(dir, "model.onnx", modelBytes)
        sizedFile(dir, "voices.bin", voicesBytes)
        sizedFile(dir, "tokens.txt", 687L)
        File(dir, "espeak-ng-data").mkdirs()
        if (withLexicons) {
            for (name in KokoroEngineConfig.LEXICON_FILE_NAMES) sizedFile(dir, name, 100L)
        }
        return dir
    }

    // (a)+(b) readiness pins ----------------------------------------------

    @Test fun realV10LayoutReportsReady() {
        val root = tmp.newFolder()
        kokoroLayout(root, modelBytes = 325_560_556L, voicesBytes = 28_200_960L)
        assertTrue(VoiceModelManager.isReadyIn(root, VoiceModelManager.Pack.TTS_KOKORO))
        assertEquals(
            "model.onnx",
            VoiceModelManager.kokoroModelFileIn(File(root, VoiceModelManager.TTS_DIR_NAME))?.name
        )
    }

    @Test fun v11LayoutDoesNotReportReady() {
        // The wrong pack v0.4.0–v0.5.0 installed on devices: its
        // real sizes must be rejected under its old dir name AND
        // if its files ever sat under the current dir name.
        val legacyRoot = tmp.newFolder("legacy")
        kokoroLayout(
            legacyRoot, modelBytes = 325_631_784L, voicesBytes = 53_790_720L,
            dirName = "kokoro-multi-lang-v1_1"
        )
        assertFalse(VoiceModelManager.isReadyIn(legacyRoot, VoiceModelManager.Pack.TTS_KOKORO))

        val renamedRoot = tmp.newFolder("renamed")
        kokoroLayout(renamedRoot, modelBytes = 325_631_784L, voicesBytes = 53_790_720L)
        assertFalse(VoiceModelManager.isReadyIn(renamedRoot, VoiceModelManager.Pack.TTS_KOKORO))
    }

    @Test fun oldWrongV040PinsDoNotReportReady() {
        // The exact wrong pair v0.4.0 demanded — a layout matching
        // BOTH old numbers must still be rejected: the genuine pack
        // never looks like this, and accepting it would be a lie.
        val root = tmp.newFolder()
        kokoroLayout(root, modelBytes = 325_631_433L, voicesBytes = 28_880_940L)
        assertFalse(VoiceModelManager.isReadyIn(root, VoiceModelManager.Pack.TTS_KOKORO))
    }

    @Test fun realModelWithWrongVoicesDoesNotReportReady() {
        val root = tmp.newFolder()
        kokoroLayout(root, modelBytes = 325_560_556L, voicesBytes = 53_790_720L)
        assertFalse(VoiceModelManager.isReadyIn(root, VoiceModelManager.Pack.TTS_KOKORO))
    }

    @Test fun missingLexiconDoesNotReportReady() {
        // v0.5.1: the engine config names the pack's lexicon files;
        // a layout without them must not claim READY.
        val root = tmp.newFolder()
        kokoroLayout(root, modelBytes = 325_560_556L, voicesBytes = 28_200_960L, withLexicons = false)
        assertFalse(VoiceModelManager.isReadyIn(root, VoiceModelManager.Pack.TTS_KOKORO))
    }

    @Test fun pinsMatchGenuineV10Archive() {
        assertEquals(325_560_556L, VoiceModelManager.Pins.KOKORO_MODEL_FP32_BYTES)
        assertEquals(28_200_960L, VoiceModelManager.Pins.KOKORO_VOICES_BYTES)
        assertEquals(349_906_910L, VoiceModelManager.Pins.KOKORO_ARCHIVE_BYTES)
        assertEquals(
            "c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298",
            VoiceModelManager.Pins.KOKORO_ARCHIVE_SHA256
        )
        assertEquals(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
            VoiceModelManager.Pins.KOKORO_URL
        )
        assertEquals("kokoro-multi-lang-v1_0.tar.bz2", VoiceModelManager.KOKORO_ARCHIVE_NAME)
        assertEquals("kokoro-multi-lang-v1_0", VoiceModelManager.TTS_DIR_NAME)
    }

    // (c) the sweep ---------------------------------------------------------

    @Test fun sweepRemovesJunkAndPreservesReadyPacksAndUnreadyArchives() {
        val root = tmp.newFolder()
        // Kokoro READY (real v1.0 layout) + its leftover archive.
        kokoroLayout(root, modelBytes = 325_560_556L, voicesBytes = 28_200_960L)
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

    @Test fun sweepRemovesLegacyV11PackDirAndArchive() {
        // The v0.5.1 upgrade path: a phone coming from v0.5.0 holds
        // the extracted v1.1 pack under its old dir name and may
        // hold the v1.1 archive. Both are stale the moment the
        // names change — the sweep must plan exactly those two,
        // while the other packs' state is untouched.
        val root = tmp.newFolder()
        kokoroLayout(
            root, modelBytes = 325_631_784L, voicesBytes = 53_790_720L,
            dirName = "kokoro-multi-lang-v1_1"
        )
        sizedFile(root, "kokoro-multi-lang-v1_1.tar.bz2", 100L)
        // KWS unready archive stays; the current TTS archive (a
        // v1.0 download in progress) stays too.
        sizedFile(root, VoiceModelManager.KWS_ARCHIVE_NAME, 100L)
        sizedFile(root, VoiceModelManager.KOKORO_ARCHIVE_NAME, 100L)
        File(root, VoiceModelManager.STT_DIR_NAME).mkdirs()
        File(root, VoiceModelManager.KWS_DIR_NAME).mkdirs()

        val plan = VoiceModelManager.sweepPlan(root).map { it.name }.toSet()
        assertEquals(setOf("kokoro-multi-lang-v1_1", "kokoro-multi-lang-v1_1.tar.bz2"), plan)
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
