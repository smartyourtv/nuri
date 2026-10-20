package com.app.nuri.tts.android

import android.content.Context
import com.app.nuri.reports.android.CrashReports
import com.app.nuri.tts.EmotionBank
import java.io.DataInputStream

/**
 * EmotionBankPlayer — loads a bank manifest from assets (June's
 * by default; the male bank when constructed with its dir) and
 * decodes an entry's WAV on demand. The bank files are 24 kHz mono
 * 16-bit PCM WAVs (generated that way — see the manifest's build
 * record in hidden_files/nuri-v040-status.md); anything else fails
 * honestly instead of playing garbage.
 *
 * If the bank is absent (its assets were never generated), the
 * catalog is empty and callers fall back to the shaped route — the
 * gap is visible in diagnostics, never papered over (V9).
 */
class EmotionBankPlayer(context: Context, private val assetDir: String = EmotionBank.ASSET_DIR) {

    private val appContext = context.applicationContext

    val entries: List<EmotionBank.BankEntry> by lazy {
        try {
            val csv = appContext.assets.open(EmotionBank.manifestAsset(assetDir))
                .bufferedReader().use { it.readText() }
            EmotionBank.parseManifest(csv)
        } catch (t: Throwable) {
            CrashReports.recordProblem(
                "EmotionBankPlayer",
                "Emotion bank ($assetDir) is not in this build (${t.message ?: "missing assets"}) — shaped voice route in use"
            )
            emptyList()
        }
    }

    val isAvailable: Boolean get() = entries.isNotEmpty()

    fun entry(id: String): EmotionBank.BankEntry? = entries.firstOrNull { it.id == id }

    /** Decode an entry to (samples, sampleRate); null if unreadable. */
    fun decode(entry: EmotionBank.BankEntry): Pair<FloatArray, Int>? {
        return try {
            val bytes = appContext.assets.open("$assetDir/${entry.file}").use { it.readBytes() }
            decodeWavPcm16(bytes)
        } catch (t: Throwable) {
            CrashReports.recordError("EmotionBankPlayer:decode:${entry.file}", t)
            null
        }
    }

    companion object {
        /** Minimal RIFF/WAVE reader: PCM, mono or stereo→mono, 16-bit. */
        fun decodeWavPcm16(bytes: ByteArray): Pair<FloatArray, Int>? {
            if (bytes.size < 44) return null
            if (!(bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                    bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte())) return null
            DataInputStream(bytes.inputStream()).use { input ->
                input.skipBytes(12) // "RIFF" + size + "WAVE"
                var sampleRate = 0
                var channels = 0
                var bitsPerSample = 0
                var audioFormat = 0
                var data: ByteArray? = null
                while (input.available() >= 8) {
                    val id = ByteArray(4)
                    input.readFully(id)
                    val chunkSize = Integer.reverseBytes(input.readInt())
                    val chunkId = String(id, Charsets.US_ASCII)
                    when (chunkId) {
                        "fmt " -> {
                            audioFormat = java.lang.Short.reverseBytes(input.readShort()).toInt()
                            channels = java.lang.Short.reverseBytes(input.readShort()).toInt()
                            sampleRate = Integer.reverseBytes(input.readInt())
                            input.skipBytes(6)
                            bitsPerSample = java.lang.Short.reverseBytes(input.readShort()).toInt()
                            if (chunkSize > 16) input.skipBytes(chunkSize - 16)
                        }
                        "data" -> {
                            data = ByteArray(chunkSize)
                            input.readFully(data)
                        }
                        else -> input.skipBytes(chunkSize + (chunkSize % 2))
                    }
                }
                val pcm = data ?: return null
                if (audioFormat != 1 || bitsPerSample != 16 || channels < 1 || sampleRate <= 0) return null
                val frames = pcm.size / (2 * channels)
                val out = FloatArray(frames)
                var idx = 0
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (ch in 0 until channels) {
                        val lo = pcm[idx].toInt() and 0xFF
                        val hi = pcm[idx + 1].toInt()
                        sum += ((hi shl 8) or lo).toShort().toFloat() / 32768f
                        idx += 2
                    }
                    out[frame] = sum / channels
                }
                return out to sampleRate
            }
        }
    }
}
