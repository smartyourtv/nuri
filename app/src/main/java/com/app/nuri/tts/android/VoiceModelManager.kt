package com.app.nuri.tts.android

import android.content.Context
import com.app.nuri.reports.android.CrashReports
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * VoiceModelManager — Ilona's voice models download AFTER install
 * (the Lifewave v1.1.0 pattern): the APK stays ~53 MB; the ~810 MB
 * of models arrive once, over the family's Wi-Fi, each pinned by
 * size AND SHA-256 and verified before use. A bad download is
 * deleted and reported, never used (self-repair law).
 *
 * Packs (sizes + hashes pinned from the verified artifacts — see
 * hidden_files/nuri-v040-model-eval.md):
 *  - TTS_KOKORO:   kokoro-int8-multi-lang-v1_0.tar.bz2 (sherpa-onnx
 *                  tts-models release) — the phone-proven pack.
 *  - STT_PARAKEET: Parakeet TDT 0.6B v2 int8, file by file from the
 *                  HuggingFace repo Scout ships, + silero VAD.
 *  - WAKE_KWS:     sherpa-onnx-kws-zipformer-gigaspeech-3.3M
 *                  (asr-models release) — the "Hey Ilona" spotter.
 *
 * Downloads run on a private executor so they survive leaving the
 * Settings screen; if the process dies mid-download the partial
 * file fails its size/hash check next launch and is re-fetched.
 * Nothing here ever runs on the main thread.
 */
class VoiceModelManager(context: Context) {

    enum class Pack { TTS_KOKORO, STT_PARAKEET, WAKE_KWS }

    enum class State { ABSENT, DOWNLOADING, VERIFYING, EXTRACTING, READY, FAILED }

    data class PackStatus(
        val state: State,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val message: String? = null
    ) {
        val percent: Int
            get() = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).toInt().coerceIn(0, 100) else 0
    }

    private val appContext = context.applicationContext
    private val rootDir = File(appContext.filesDir, "voice-models")
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ilona-voice-models").apply { isDaemon = true }
    }

    private val _statuses = MutableStateFlow<Map<Pack, PackStatus>>(emptyMap())
    val statuses: StateFlow<Map<Pack, PackStatus>> = _statuses

    init {
        refresh()
    }

    fun status(pack: Pack): PackStatus =
        _statuses.value[pack] ?: PackStatus(if (isReady(pack)) State.READY else State.ABSENT)

    private fun setStatus(pack: Pack, status: PackStatus) {
        _statuses.value = _statuses.value + (pack to status)
    }

    // ------------------------------------------------------------------
    // Locations
    // ------------------------------------------------------------------

    fun ttsDir(): File = File(rootDir, "kokoro-multi-lang-v1_1")
    fun sttDir(): File = File(rootDir, "parakeet")
    fun kwsDir(): File = File(rootDir, "kws-gigaspeech")

    /** The Kokoro model file the engine should load, or null if the pack isn't complete. */
    fun kokoroModelFile(): File? {
        val dir = ttsDir()
        val fp32 = File(dir, "model.onnx")
        val int8 = File(dir, "model.int8.onnx")
        return when {
            // Laptop-parity default: the full-precision model (the
            // model evaluation found it faster than int8 on CPU in
            // every benchmark run, as well as fuller — see
            // hidden_files/nuri-v040-model-eval.md).
            fp32.exists() && fp32.length() == Pins.KOKORO_MODEL_FP32_BYTES -> fp32
            int8.exists() && int8.length() == Pins.KOKORO_MODEL_INT8_BYTES -> int8
            else -> null
        }
    }

    fun isReady(pack: Pack): Boolean = when (pack) {
        Pack.TTS_KOKORO -> {
            val dir = ttsDir()
            kokoroModelFile() != null &&
                File(dir, "voices.bin").length() == Pins.KOKORO_VOICES_BYTES &&
                File(dir, "tokens.txt").exists() &&
                File(dir, "espeak-ng-data").isDirectory
        }
        Pack.STT_PARAKEET -> Pins.PARAKEET_DOWNLOADS.all { spec ->
            File(sttDir(), spec.localName).let { it.exists() && it.length() == spec.bytes }
        }
        Pack.WAKE_KWS -> Pins.KWS_FILES.all { (name, bytes) ->
            File(kwsDir(), name).let { it.exists() && it.length() == bytes }
        }
    }

    fun refresh() {
        for (pack in Pack.values()) {
            val current = _statuses.value[pack]?.state
            if (current == State.DOWNLOADING || current == State.VERIFYING || current == State.EXTRACTING) continue
            setStatus(pack, PackStatus(if (isReady(pack)) State.READY else State.ABSENT))
        }
    }

    /** Total bytes a full download of all missing packs will fetch. */
    fun missingBytes(): Long = Pack.values().filter { !isReady(it) }.sumOf { Pins.totalBytes(it) }

    // ------------------------------------------------------------------
    // Download orchestration
    // ------------------------------------------------------------------

    /** Download (or resume-check) every missing pack, in order. Idempotent. */
    fun downloadMissing() {
        for (pack in Pack.values()) {
            if (isReady(pack)) {
                setStatus(pack, PackStatus(State.READY))
                continue
            }
            val state = _statuses.value[pack]?.state
            if (state == State.DOWNLOADING || state == State.VERIFYING || state == State.EXTRACTING) continue
            executor.execute { downloadPack(pack) }
        }
    }

    private fun downloadPack(pack: Pack) {
        try {
            when (pack) {
                Pack.TTS_KOKORO -> downloadArchivePack(
                    pack,
                    url = Pins.KOKORO_URL,
                    archiveName = "kokoro-int8-multi-lang-v1_0.tar.bz2",
                    expectedBytes = Pins.KOKORO_ARCHIVE_BYTES,
                    expectedSha256 = Pins.KOKORO_ARCHIVE_SHA256,
                    targetDir = ttsDir()
                )
                Pack.WAKE_KWS -> downloadArchivePack(
                    pack,
                    url = Pins.KWS_URL,
                    archiveName = "kws.tar.bz2",
                    expectedBytes = Pins.KWS_ARCHIVE_BYTES,
                    expectedSha256 = Pins.KWS_ARCHIVE_SHA256,
                    targetDir = kwsDir()
                )
                Pack.STT_PARAKEET -> {
                    sttDir().mkdirs()
                    var done = 0L
                    val total = Pins.totalBytes(pack)
                    for (spec in Pins.PARAKEET_DOWNLOADS) {
                        val dest = File(sttDir(), spec.localName)
                        if (dest.exists() && dest.length() == spec.bytes && sha256(dest) == spec.sha256) {
                            done += spec.bytes
                            setStatus(pack, PackStatus(State.DOWNLOADING, done, total))
                            continue
                        }
                        downloadFile(spec.url, dest, spec.bytes, spec.sha256) { soFar ->
                            setStatus(pack, PackStatus(State.DOWNLOADING, done + soFar, total))
                        }
                        done += spec.bytes
                    }
                }
            }
            if (isReady(pack)) {
                setStatus(pack, PackStatus(State.READY))
                CrashReports.recordRepair("Voice models", "${pack.name} downloaded and verified", com.app.nuri.reports.RepairStatus.REPAIRED)
            } else {
                throw IllegalStateException("pack incomplete after download")
            }
        } catch (t: Throwable) {
            setStatus(pack, PackStatus(State.FAILED, message = t.message ?: t.javaClass.simpleName))
            CrashReports.recordError("VoiceModelManager:${pack.name}", t)
        }
    }

    private fun downloadArchivePack(
        pack: Pack,
        url: String,
        archiveName: String,
        expectedBytes: Long,
        expectedSha256: String,
        targetDir: File
    ) {
        rootDir.mkdirs()
        val archive = File(rootDir, archiveName)
        if (!(archive.exists() && archive.length() == expectedBytes && sha256(archive) == expectedSha256)) {
            downloadFile(url, archive, expectedBytes, expectedSha256) { soFar ->
                setStatus(pack, PackStatus(State.DOWNLOADING, soFar, expectedBytes))
            }
        }
        setStatus(pack, PackStatus(State.VERIFYING, expectedBytes, expectedBytes))
        // Extract to a staging dir; only a complete extraction replaces
        // the live dir, so a crash mid-extract never leaves a half pack.
        val staging = File(rootDir, "${targetDir.name}-staging")
        staging.deleteRecursively()
        staging.mkdirs()
        setStatus(pack, PackStatus(State.EXTRACTING, expectedBytes, expectedBytes))
        extractTarBz2(archive, staging)
        // Archives contain a top-level folder; if so, descend into it.
        val contentRoot = staging.listFiles()?.singleOrNull { it.isDirectory } ?: staging
        targetDir.deleteRecursively()
        targetDir.parentFile?.mkdirs()
        check(contentRoot.renameTo(targetDir) || copyRecursively(contentRoot, targetDir)) {
            "could not install extracted pack"
        }
        staging.deleteRecursively()
        archive.delete() // the extracted pack is the verified copy now
    }

    private fun copyRecursively(from: File, to: File): Boolean {
        if (from.isDirectory) {
            if (!to.mkdirs() && !to.isDirectory) return false
            return from.listFiles()?.all { copyRecursively(it, File(to, it.name)) } ?: true
        }
        from.copyTo(to, overwrite = true)
        return to.length() == from.length()
    }

    /**
     * Download [url] to [dest] with a size + SHA-256 contract. Writes
     * to a .partial sibling first; a failed verification deletes the
     * partial (never a corrupt payload in place — self-repair law).
     */
    private fun downloadFile(
        url: String,
        dest: File,
        expectedBytes: Long,
        expectedSha256: String?,
        onProgress: (Long) -> Unit
    ) {
        dest.parentFile?.mkdirs()
        val partial = File(dest.parentFile, dest.name + ".partial")
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "Ilona/${com.app.nuri.BuildConfig.VERSION_NAME}")
            }
            val code = connection.responseCode
            check(code in 200..299) { "download failed (HTTP $code)" }
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        if (total - lastReport >= 1_000_000) {
                            lastReport = total
                            onProgress(total)
                        }
                    }
                    output.flush()
                }
            }
            onProgress(partial.length())
            check(partial.length() == expectedBytes) {
                "size mismatch: got ${partial.length()}, expected $expectedBytes"
            }
            if (expectedSha256 != null) {
                val actual = sha256(partial)
                check(actual == expectedSha256) { "hash mismatch for ${dest.name}" }
            }
            check(partial.renameTo(dest) || run { partial.copyTo(dest, overwrite = true); partial.delete(); true }) {
                "could not finalize ${dest.name}"
            }
        } catch (t: Throwable) {
            partial.delete()
            throw t
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractTarBz2(archive: File, destDir: File) {
        val canonicalDest = destDir.canonicalPath
        FileInputStream(archive).use { fis ->
            BZip2CompressorInputStream(BufferedInputStream(fis)).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        // Path-escape guard (the Scout extractor's rule):
                        // a hostile archive can never write outside destDir.
                        check(outFile.canonicalPath.startsWith(canonicalDest)) {
                            "archive entry escapes target: ${entry.name}"
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                val buffer = ByteArray(256 * 1024)
                                while (true) {
                                    val read = tar.read(buffer)
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                }
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------
    // Pins — verified artifact identities (hidden_files/nuri-v040-model-eval.md)
    // ------------------------------------------------------------------

    object Pins {
        // Kokoro TTS — multi-lang v1.1 pack (eval verdict: the
        // FULL-PRECISION model is the laptop-parity choice — it
        // benchmarked faster than int8 on CPU in every run, as well
        // as being the laptop's full-quality config; the pack
        // bundles both precisions and the engine prefers the fp32
        // model.onnx. af_heart = sid 3 in this pack (verified
        // against the pack's voices list). All pins verified from
        // the downloaded artifact itself (2026-10-19).
        const val KOKORO_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_1.tar.bz2"
        const val KOKORO_ARCHIVE_BYTES = 364_816_464L
        const val KOKORO_ARCHIVE_SHA256 = "a3f4c73d043860e3fd2e5b06f36795eb81de0fc8e8de6df703245edddd87dbad"
        const val KOKORO_MODEL_FP32_BYTES = 325_631_433L
        const val KOKORO_MODEL_INT8_BYTES = 114_203_756L
        const val KOKORO_VOICES_BYTES = 28_880_940L

        data class FileSpec(val url: String, val localName: String, val bytes: Long, val sha256: String)

        // Parakeet STT — per-file pins from the HF repo tree (sizes
        // verified against the live API in the eval; sha256 = the
        // repos' LFS oids). The VAD file comes from csukuangfj/vad —
        // the same source Scout ships.
        val PARAKEET_DOWNLOADS: List<FileSpec> = listOf(
            FileSpec(
                "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main/encoder.int8.onnx",
                "parakeet-encoder.onnx", 652_184_296L, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"
            ),
            FileSpec(
                "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main/decoder.int8.onnx",
                "parakeet-decoder.onnx", 7_257_753L, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"
            ),
            FileSpec(
                "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main/joiner.int8.onnx",
                "parakeet-joiner.onnx", 1_739_080L, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"
            ),
            FileSpec(
                "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main/tokens.txt",
                "parakeet-tokens.txt", 9_384L, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"
            ),
            FileSpec(
                "https://huggingface.co/csukuangfj/vad/resolve/main/silero_vad.onnx",
                "vad.onnx", 1_807_522L, "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28"
            )
        )

        // Wake-word KWS — gigaspeech 3.3M zipformer, from the
        // kws-models release (the asr-models tag 404s for this
        // asset — verified 2026-10-19). Archive hash verified from
        // the artifact itself. The pack ships fp32 + int8 variants;
        // Ilona runs the int8 trio (always-on CPU cost).
        const val KWS_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
        const val KWS_ARCHIVE_BYTES = 17_626_723L
        const val KWS_ARCHIVE_SHA256 = "f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a"
        val KWS_FILES: Map<String, Long> = mapOf(
            "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" to 4_807_159L,
            "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" to 277_985L,
            "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" to 163_380L,
            "tokens.txt" to 5_006L
        )

        fun totalBytes(pack: Pack): Long = when (pack) {
            Pack.TTS_KOKORO -> KOKORO_ARCHIVE_BYTES
            Pack.STT_PARAKEET -> PARAKEET_DOWNLOADS.sumOf { it.bytes }
            Pack.WAKE_KWS -> KWS_ARCHIVE_BYTES
        }
    }
}
