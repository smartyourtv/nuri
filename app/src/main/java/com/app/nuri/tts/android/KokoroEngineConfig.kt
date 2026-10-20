package com.app.nuri.tts.android

import java.io.File

/**
 * The exact sherpa Kokoro configuration Ilona ships (v0.5.1) —
 * factored pure (java.io.File only, no Android, no JNI types) so
 * JVM unit tests pin every value and the configuration cannot
 * drift silently again.
 *
 * The values mirror the v0.4.0 evaluation's render setup
 * (build-support/voice-eval/render_v10.py — the render the owner
 * confirmed by ear on 2026-10-20: "Yes that's her") against the
 * kokoro-multi-lang-v1_0 pack:
 *
 *  - LEXICON: the pack's three lexicon files, in the eval's order
 *    (US English, GB English, Chinese). v0.4.0–v0.5.0 passed NO
 *    lexicon; combined with the wrong (v1.1, Chinese-edition) pack
 *    that produced the accented stranger voice. The lexicon is
 *    part of the proven config, so it is pinned here.
 *  - LANG: the eval passed no lang argument — sherpa's default,
 *    the empty string. Shipping "" mirrors the confirmed render
 *    exactly; the previous "en" was never by-ear verified against
 *    this pack + lexicon combination.
 */
object KokoroEngineConfig {

    /** The sherpa default lang — the value the confirmed eval render ran with. */
    const val LANG: String = ""

    /** The pack's lexicon files, in the eval's order. */
    val LEXICON_FILE_NAMES: List<String> = listOf(
        "lexicon-us-en.txt",
        "lexicon-gb-en.txt",
        "lexicon-zh.txt"
    )

    /** The comma-joined absolute lexicon paths the sherpa config expects. */
    fun lexiconPathIn(dir: File): String =
        LEXICON_FILE_NAMES.joinToString(",") { File(dir, it).absolutePath }

    /** True when every lexicon file the engine config names is present in [dir]. */
    fun lexiconsPresentIn(dir: File): Boolean =
        LEXICON_FILE_NAMES.all { File(dir, it).isFile }
}
