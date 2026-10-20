package com.app.nuri.tts

import com.app.nuri.tts.android.KokoroEngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v0.5.1: the sherpa Kokoro configuration is pinned so it cannot
 * drift silently (the v0.4.0 failure mode). The values mirror the
 * v0.4.0 evaluation's render setup against the kokoro-multi-lang-
 * v1_0 pack — the render the owner confirmed by ear on 2026-10-20
 * ("Yes that's her"): lexicon wired from the pack's three lexicon
 * files, lang left at sherpa's default.
 */
class KokoroEngineConfigTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun langIsTheSherpaDefaultTheEvalRanWith() {
        assertEquals("", KokoroEngineConfig.LANG)
    }

    @Test fun lexiconPathJoinsThePacksThreeFilesInEvalOrder() {
        val dir = File("voice-models/kokoro-multi-lang-v1_0")
        val expected = listOf("lexicon-us-en.txt", "lexicon-gb-en.txt", "lexicon-zh.txt")
            .joinToString(",") { File(dir, it).absolutePath }
        assertEquals(expected, KokoroEngineConfig.lexiconPathIn(dir))
        assertEquals(
            listOf("lexicon-us-en.txt", "lexicon-gb-en.txt", "lexicon-zh.txt"),
            KokoroEngineConfig.LEXICON_FILE_NAMES
        )
    }

    @Test fun lexiconPresenceRequiresAllThreeFiles() {
        val dir = tmp.newFolder()
        assertFalse(KokoroEngineConfig.lexiconsPresentIn(dir))
        for (name in KokoroEngineConfig.LEXICON_FILE_NAMES) {
            File(dir, name).writeBytes(byteArrayOf(1))
        }
        assertTrue(KokoroEngineConfig.lexiconsPresentIn(dir))
        File(dir, "lexicon-gb-en.txt").delete()
        assertFalse(KokoroEngineConfig.lexiconsPresentIn(dir))
    }
}
