package com.app.nuri.tts.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.app.nuri.reports.android.CrashReports
import com.app.nuri.speech.VoiceRuntime
import com.app.nuri.tts.BreathSoundGenerator
import com.app.nuri.tts.SpeakableText
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * KokoroVoiceEngine — Ilona's real voice (v0.4.0).
 *
 * June (Kokoro af_heart) by default; any picker voice by sid. The
 * production traits are the laptop's, ported (LL3): text is
 * normalized ONCE here (SpeakableText — callers cannot bypass it),
 * split into sentences, and synthesized with one sentence of
 * lookahead while the previous one plays, so speech doesn't stall
 * between sentences. Subtle generated breaths may sit in the longer
 * pauses (situation-fitted by the caller via the breath flag).
 *
 * Playback bookkeeping for V13: the engine always knows which
 * sentence it is on. stop(bargeIn = true) remembers the interrupted
 * sentence; resume() continues from exactly there. A cutoff is a
 * logged fact (the reports system hears about barges and resumes).
 *
 * sherpa's generate() is NOT concurrency-safe (the Scout mutex
 * lesson): every call is serialized on one worker thread.
 */
class KokoroVoiceEngine(context: Context, private val models: VoiceModelManager) {

    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ilona-kokoro").apply { isDaemon = true }
    }

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var initFailed = false
    private var track: AudioTrack? = null
    private var trackRate = 0

    @Volatile private var utteranceSeq = 0
    @Volatile private var playing = false
    @Volatile private var interruptedByBarge = false

    // V13 bookkeeping: the interrupted utterance, resumable.
    data class Interrupted(val sentences: List<String>, val fromIndex: Int, val sid: Int, val speed: Float)
    @Volatile var lastInterrupted: Interrupted? = null
        private set

    val isReady: Boolean get() = tts != null
    val isAvailable: Boolean get() = models.isReady(VoiceModelManager.Pack.TTS_KOKORO)

    /** Load the model if the pack is present. Idempotent; safe to call often. */
    fun ensureLoaded() {
        if (tts != null || initFailed) return
        worker.execute {
            if (tts != null || initFailed) return@execute
            try {
                val dir = models.ttsDir()
                val model = models.kokoroModelFile() ?: return@execute
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = model.absolutePath,
                            voices = File(dir, "voices.bin").absolutePath,
                            tokens = File(dir, "tokens.txt").absolutePath,
                            dataDir = File(dir, "espeak-ng-data").absolutePath,
                            // v0.5.1: the lexicon + lang of the
                            // by-ear-confirmed eval render, pinned
                            // in KokoroEngineConfig (unit-tested).
                            lexicon = KokoroEngineConfig.lexiconPathIn(dir),
                            lang = KokoroEngineConfig.LANG
                        ),
                        numThreads = 2
                    ),
                    maxNumSentences = 10
                )
                tts = OfflineTts(null, config)
                VoiceRuntime.setTtsEngine("Kokoro ready")
            } catch (t: Throwable) {
                initFailed = true
                VoiceRuntime.setTtsEngine("Kokoro problem: ${t.message ?: "load failed"}")
                CrashReports.recordError("KokoroVoiceEngine:init", t)
            }
        }
    }

    /**
     * Speak [rawText] in voice [sid] at [speed]. Everything is
     * normalized through SpeakableText here — the one unbypassable
     * seam (LL5). [withBreaths] allows generated breaths in the
     * pauses (never for urgent speech — the caller decides via the
     * EmotionGate's breath rules). Blocks the calling worker thread;
     * call from a background thread only.
     */
    fun speak(rawText: String, sid: Int, speed: Float, withBreaths: Boolean) {
        val engine = tts ?: return
        val text = SpeakableText.normalize(rawText)
        val sentences = SpeakableText.sentences(text)
        if (sentences.isEmpty()) return
        speakSentences(engine, sentences, startIndex = 0, sid = sid, speed = speed, withBreaths = withBreaths)
    }

    /**
     * Play raw PCM (emotion renders, bank WAVs decoded by the
     * caller). BLOCKING — call from the speaker's executor thread,
     * like [speak]; stop() interrupts it the same way.
     */
    fun playPcm(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        synchronized(speakLock) {
            val mySeq = ++utteranceSeq
            val t = ensureTrack(sampleRate) ?: return
            playing = true
            VoiceRuntime.setSpeaking(true)
            try {
                t.play()
                verifyRoute(t)
                writeAll(t, samples)
                drain(t, samples.size)
            } catch (e: Throwable) {
                CrashReports.recordError("KokoroVoiceEngine:playPcm", e)
            } finally {
                if (mySeq == utteranceSeq) {
                    playing = false
                    VoiceRuntime.setSpeaking(false)
                }
            }
        }
    }

    /** Continue the interrupted utterance from its interrupted sentence (V13). */
    fun resume() {
        val engine = tts ?: return
        val interrupted = lastInterrupted ?: return
        lastInterrupted = null
        CrashReports.recordRepair(
            "Voice playback",
            "resumed at sentence ${interrupted.fromIndex + 1} of ${interrupted.sentences.size} after a cleared interruption",
            com.app.nuri.reports.RepairStatus.REPAIRED
        )
        // Blocking like speak(): the caller (speaker executor or the
        // listen service's resume path) provides the thread.
        speakSentences(
            engine, interrupted.sentences, interrupted.fromIndex,
            interrupted.sid, interrupted.speed, withBreaths = false
        )
    }

    /**
     * Stop playback. [bargeIn] = the listener heard the person start
     * talking over Ilona: the position is remembered for resume().
     * A plain stop forgets the position.
     */
    fun stop(bargeIn: Boolean) {
        utteranceSeq++
        interruptedByBarge = bargeIn
        if (!bargeIn) lastInterrupted = null
        try {
            track?.pause()
            track?.flush()
        } catch (_: Throwable) {
        }
        playing = false
        VoiceRuntime.setSpeaking(false)
    }

    val isSpeaking: Boolean get() = playing

    // ------------------------------------------------------------------

    private val speakLock = Any()

    private fun speakSentences(
        engine: OfflineTts,
        sentences: List<String>,
        startIndex: Int,
        sid: Int,
        speed: Float,
        withBreaths: Boolean
    ) = synchronized(speakLock) {
        val mySeq = ++utteranceSeq
        playing = true
        VoiceRuntime.setSpeaking(true)
        var currentIndex = startIndex
        try {
            var pending = generate(engine, sentences[startIndex], sid, speed)
            var i = startIndex
            while (i < sentences.size) {
                if (mySeq != utteranceSeq) {
                    // Interrupted mid-utterance: only a BARGE-IN earns a
                    // resume position (V13); a plain stop forgets it.
                    if (interruptedByBarge) {
                        lastInterrupted = Interrupted(sentences, currentIndex, sid, speed)
                        interruptedByBarge = false
                    }
                    return
                }
                val audio = pending ?: break
                currentIndex = i
                val t = ensureTrack(audio.sampleRate) ?: break
                t.play()
                verifyRoute(t)
                writeAll(t, audio.samples)
                // One sentence of lookahead (LL3): synthesize the next
                // while this one drains.
                pending = if (i + 1 < sentences.size) generate(engine, sentences[i + 1], sid, speed) else null
                if (withBreaths && i + 1 < sentences.size && mySeq == utteranceSeq) {
                    maybeBreath(t, audio.sampleRate)
                }
                drain(t, audio.samples.size)
                i++
            }
            lastInterrupted = null
        } catch (t: Throwable) {
            CrashReports.recordError("KokoroVoiceEngine:speak", t)
            VoiceRuntime.noteFailure("speech: ${t.message ?: "synthesis failed"}")
        } finally {
            if (mySeq == utteranceSeq) {
                playing = false
                VoiceRuntime.setSpeaking(false)
            }
        }
    }

    private val generateLock = Any()

    /**
     * Synthesize a short carrier fragment in [sid]'s voice — the raw
     * material for the shaped emotion renderer (non-June voices).
     * Returns (samples, sampleRate) or null when not ready.
     */
    fun synthesizeFragment(text: String, sid: Int): Pair<FloatArray, Int>? {
        val engine = tts ?: return null
        val audio = synchronized(generateLock) { engine.generate(text, sid, 1.0f) }
        if (audio.samples.isEmpty()) return null
        return audio.samples to audio.sampleRate
    }

    private fun generate(engine: OfflineTts, text: String, sid: Int, speed: Float): GeneratedPcm? {
        if (text.isBlank()) return null
        val audio = synchronized(generateLock) { engine.generate(text, sid, speed) }
        if (audio.samples.isEmpty()) return null
        return GeneratedPcm(audio.samples, audio.sampleRate)
    }

    private data class GeneratedPcm(val samples: FloatArray, val sampleRate: Int)

    private fun maybeBreath(t: AudioTrack, sampleRate: Int) {
        // ~35% of inter-sentence pauses, gentle — the Scout rate. The
        // breath itself is generated fresh each time (no two alike).
        if (Random.nextFloat() > 0.35f) return
        val pcm = BreathSoundGenerator.generateBreath(sampleRate, durationMs = 220)
        if (pcm.isNotEmpty()) writeAll(t, FloatArray(pcm.size) { pcm[it] / 32768f })
    }

    private fun ensureTrack(sampleRate: Int): AudioTrack? {
        val existing = track
        if (existing != null && trackRate == sampleRate) return existing
        try { existing?.release() } catch (_: Throwable) {}
        track = null
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        if (minBuffer <= 0) return null
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = created
        trackRate = sampleRate
        return created
    }

    private fun writeAll(t: AudioTrack, samples: FloatArray) {
        var offset = 0
        while (offset < samples.size) {
            val written = t.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
    }

    /** Wait until the queued audio has (nearly) played out. */
    private fun drain(t: AudioTrack, approxFrames: Int) {
        // Playback position advances in real time; poll cheaply so a
        // stop() lands promptly instead of after the whole sentence.
        val deadline = System.currentTimeMillis() + (approxFrames * 1000L / trackRate.coerceAtLeast(1)) + 800
        while (System.currentTimeMillis() < deadline) {
            if (!playing) return
            val head = try { t.playbackHeadPosition } catch (_: Throwable) { return }
            if (head >= approxFrames - (trackRate / 20)) return
            try { Thread.sleep(30) } catch (_: InterruptedException) { return }
        }
    }

    /** V14: the route is read from the track AFTER playback starts. */
    private fun verifyRoute(t: AudioTrack) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val device = t.routedDevice ?: return
            val typeName = when (device.type) {
                android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "the phone speaker"
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "the wired headphones"
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                android.media.AudioDeviceInfo.TYPE_HEARING_AID -> "the Bluetooth device"
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "the USB headphones"
                else -> "another output"
            }
            VoiceRuntime.setRoutedDevice(typeName, verified = true)
        } catch (_: Throwable) {
            // Route unverifiable on this device — the card says so.
        }
    }
}
