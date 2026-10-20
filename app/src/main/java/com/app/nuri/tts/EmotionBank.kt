package com.app.nuri.tts

/**
 * EmotionBank — the converted performance banks, as data.
 *
 * Two banks ship as app assets, each a dir of WAV files +
 * manifest.csv: June's (assets/emotion-bank/june/ — her native
 * set, the 55-slot mirror of the male bank) and the male bank
 * (assets/emotion-bank/male/ — the laptop Scout bank, serving
 * Ilona's men's voices). Performances generated with the
 * Chatterbox conversion route — the ONLY laugh mechanism that
 * has passed the owner's ear anywhere in this project family
 * (files/nuri-voice-lessons-applied.md, V9). Acceptance is
 * still BY EAR: the voice self-test plays these so the owner
 * judges them in Ilona.
 *
 * Manifest format (one entry per line, header first):
 *   id,kind,file,durationMs,insertSafe
 * kind is an EmotionGate.SoundKind name; insertSafe marks the pure
 * sounds (no spoken words) that the laptop bank rules allow to be
 * inserted around speech (reaction LINES are whole-turn only — in
 * this build every cue plays as a standalone beat around speech,
 * never spliced mid-sentence).
 *
 * This file is pure Kotlin: parsing + catalog logic are unit-tested;
 * asset reading lives in the android layer (EmotionBankPlayer).
 */
object EmotionBank {

    data class BankEntry(
        val id: String,
        val kind: EmotionGate.SoundKind,
        val file: String,
        val durationMs: Int,
        val insertSafe: Boolean
    )

    const val ASSET_DIR = "emotion-bank/june"
    const val MANIFEST_ASSET = "$ASSET_DIR/manifest.csv"

    /** The male bank: the laptop Scout bank (55 slots), serving Ilona's men's voices. */
    const val ASSET_DIR_MALE = "emotion-bank/male"
    const val MANIFEST_ASSET_MALE = "$ASSET_DIR_MALE/manifest.csv"

    fun manifestAsset(assetDir: String): String = "$assetDir/manifest.csv"

    fun parseManifest(csv: String): List<BankEntry> =
        csv.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("id,") }
            .mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size < 5) return@mapNotNull null
                val kind = runCatching { EmotionGate.SoundKind.valueOf(parts[1].trim()) }.getOrNull()
                    ?: return@mapNotNull null
                if (kind == EmotionGate.SoundKind.NONE) return@mapNotNull null
                BankEntry(
                    id = parts[0].trim(),
                    kind = kind,
                    file = parts[2].trim(),
                    durationMs = parts[3].trim().toIntOrNull() ?: 0,
                    insertSafe = parts[4].trim().equals("true", ignoreCase = true)
                )
            }
            .toList()

    /** As the EmotionGate sees the bank. */
    fun gateCatalog(entries: List<BankEntry>): List<EmotionGate.Entry> =
        entries.map { EmotionGate.Entry(id = it.id, kind = it.kind) }
}
