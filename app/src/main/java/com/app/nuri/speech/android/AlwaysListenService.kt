package com.app.nuri.speech.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.app.nuri.config.NuriSettings
import com.app.nuri.reports.android.CrashReports
import com.app.nuri.repository.SettingsRepository
import com.app.nuri.speech.EnergyVad
import com.app.nuri.speech.KwsKeywords
import com.app.nuri.speech.ListenSessionMachine
import com.app.nuri.speech.RelativeEndpointer
import com.app.nuri.speech.VoiceRuntime
import com.app.nuri.tts.EmotionGate
import com.app.nuri.tts.android.IlonaSpeaker
import com.app.nuri.tts.android.VoiceModelManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * AlwaysListenService — "Hey Ilona" (v0.4.0).
 *
 * A microphone-type foreground service that exists ONLY while the
 * person (or caregiver) has listening turned on. Everything is
 * on-device by law (her offline safety rule): sherpa-onnx keyword
 * spotting for the wake phrase, Parakeet for the utterance, and the
 * transcription is handed to the UI over VoiceBus, where it takes
 * EXACTLY the typed-text path (LL4).
 *
 * The loop honors the hard lessons (files/nuri-voice-lessons
 * -applied.md): 800 ms wake hangover, pre-roll so the first word
 * survives, RELATIVE endpointing (never a fixed noisy-room cap),
 * short-answer protection, barge-in with mid-sentence resume,
 * session pattern (90 s follow-up, 10-minute hold, "that's all"),
 * mic-reality checks, and live state written to VoiceRuntime for
 * every window — the health card reads the service's own heartbeat,
 * never a flag (LL9).
 */
class AlwaysListenService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var running = false
    private var captureThread: Thread? = null

    private lateinit var models: VoiceModelManager
    private lateinit var speaker: IlonaSpeaker
    private lateinit var repo: SettingsRepository

    @Volatile private var longerListenWindow: Boolean = false

    // sherpa objects (capture-thread confined after init)
    private var spotter: KeywordSpotter? = null
    private var spotterStream: OnlineStream? = null
    private var wakePhraseUsed: String? = null
    private var recognizer: OfflineRecognizer? = null
    private var sttAnnouncedMissing = false
    private var kwsRetryAtMs = 0L
    private var sttRetryAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        models = VoiceModelManager(applicationContext)
        speaker = IlonaSpeaker.shared(applicationContext)
        repo = SettingsRepository(applicationContext)
        serviceScope.launch {
            repo.personToggles.collect { longerListenWindow = it.longerListenWindow ?: false }
        }

        createChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            CrashReports.recordError("AlwaysListenService:foreground", t)
            VoiceRuntime.serviceStopped("could not start as a foreground service")
            stopSelf()
            return
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            VoiceRuntime.serviceStopped("microphone permission not granted")
            CrashReports.recordProblem("AlwaysListenService", "started without microphone permission — stopping")
            stopSelf()
            return
        }

        VoiceRuntime.serviceStarted()
        running = true
        captureThread = Thread({ captureLoop() }, "ilona-listen").apply { isDaemon = true; start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        captureThread?.interrupt()
        captureThread = null
        runCatching { spotter?.release() }
        runCatching { recognizer?.release() }
        spotter = null
        recognizer = null
        VoiceRuntime.serviceStopped()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Capture loop
    // ------------------------------------------------------------------

    private fun captureLoop() {
        val sampleRate = 16_000
        val windowSamples = 1_280 // 80 ms — the proven Scout frame
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            fail("the microphone could not be opened on this phone")
            return
        }
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 4, windowSamples * 8)
            )
        } catch (t: Throwable) {
            CrashReports.recordError("AlwaysListenService:AudioRecord", t)
            fail("the microphone could not be opened")
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            fail("the microphone could not be opened")
            return
        }

        val vad = EnergyVad()
        val session = ListenSessionMachine()
        var endpointer = newEndpointer()
        val preRoll = java.util.ArrayDeque<ShortArray>()
        val utterance = ArrayList<Short>()
        var capturing = false
        var frames = 0L
        var silentWindows = 0
        var micTroubleReported = false
        var bargeCandidateSinceMs = 0L
        var resumeDeadlineMs = 0L
        var lastPackCheckMs = 0L

        try {
            recorder.startRecording()
            val buffer = ShortArray(windowSamples)
            while (running) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    Thread.sleep(20)
                    continue
                }
                val window = buffer.copyOf(read)
                val floats = FloatArray(read) { window[it] / 32768f }
                val rms = vad.rmsOf(floats)
                val gateOpen = vad.acceptWindow(floats)
                frames++

                // Mic reality (V5): a stuck/silent mic is a trouble
                // state on the card, never a quiet "all good".
                if (rms == 0f) silentWindows++ else silentWindows = 0
                val micLive = silentWindows < 40
                if (!micLive && !micTroubleReported) {
                    micTroubleReported = true
                    VoiceRuntime.noteFailure("the microphone is returning silence")
                    CrashReports.recordProblem("AlwaysListenService", "microphone frames are all silence — check the microphone")
                }
                if (micLive) micTroubleReported = false
                VoiceRuntime.heartbeat(frames, vad.noiseFloor, rms, micLive, vad.speechWindows)

                val now = System.currentTimeMillis()

                // Pack readiness is re-checked a few times a minute so
                // a finished download lights up without a restart.
                if (now - lastPackCheckMs > 5_000) {
                    lastPackCheckMs = now
                    if (spotter == null && now >= kwsRetryAtMs) initSpotter()
                    if (recognizer == null && now >= sttRetryAtMs) initRecognizer()
                }

                // Session timeout.
                if (session.tick(now)) {
                    capturing = false
                    utterance.clear()
                    VoiceRuntime.session(false)
                }

                // ---- Barge-in over Ilona's own speech ----
                if (speaker.isSpeaking && gateOpen && rms > maxOf(vad.noiseFloor * 4f, 0.05f)) {
                    if (bargeCandidateSinceMs == 0L) bargeCandidateSinceMs = now
                    if (now - bargeCandidateSinceMs >= 160 && resumeDeadlineMs == 0L) {
                        speaker.stopForBargeIn()
                        resumeDeadlineMs = now + 1_500
                        bargeCandidateSinceMs = 0L
                        // Listen for the interrupting words as an utterance.
                        if (!capturing) {
                            capturing = true
                            utterance.clear()
                            preRoll.forEach { w -> w.forEach { s -> utterance.add(s) } }
                            endpointer = newEndpointer()
                            endpointer.start(now - ListenSessionMachine.WAKE_HANGOVER_MS)
                        }
                    }
                } else if (!speaker.isSpeaking) {
                    bargeCandidateSinceMs = 0L
                }
                // A cleared false alarm resumes the sentence (V13).
                if (resumeDeadlineMs >= 1L && resumeDeadlineMs <= now && !capturing) {
                    resumeDeadlineMs = 0L
                    speaker.resumeIfInterrupted()
                }
                if (resumeDeadlineMs >= 1L && resumeDeadlineMs <= now && capturing && !endpointer.hasOnset) {
                    // Capture opened for the barge but nobody spoke:
                    // treat as false alarm too.
                    capturing = false
                    utterance.clear()
                    resumeDeadlineMs = 0L
                    speaker.resumeIfInterrupted()
                }

                // ---- Wake spotting (session closed) ----
                if (!session.isOpen() && spotter != null) {
                    feedSpotter(floats)
                    if (consumeWakeHit()) {
                        session.onWake(now)
                        VoiceRuntime.wakeHeard()
                        capturing = true
                        utterance.clear()
                        preRoll.forEach { w -> w.forEach { s -> utterance.add(s) } }
                        endpointer = newEndpointer()
                        endpointer.start(now)
                        resumeDeadlineMs = 0L
                        if (!speaker.isSpeaking) {
                            speaker.speakAsync("I'm listening.", EmotionGate.Situation.NORMAL)
                        }
                    }
                }

                // ---- Capture + endpointing (session open) ----
                if (session.isOpen() && capturing) {
                    window.forEach { s -> utterance.add(s) }
                    val closed = endpointer.onFrame(rms, now)
                    if (closed != null) {
                        capturing = false
                        if (closed.reason == RelativeEndpointer.CloseReason.SAFETY_MAX) {
                            CrashReports.recordProblem(
                                "AlwaysListenService",
                                "an utterance hit the 20-second safety limit (speech ${closed.speechMs} ms) — logged per the endpointing rule"
                            )
                        }
                        if (closed.speechMs > 0 && utterance.isNotEmpty()) {
                            if (resumeDeadlineMs > 0) resumeDeadlineMs = 0L // real speech — no resume
                            handleUtterance(utterance.toShortArray(), session)
                            if (!session.isOpen()) VoiceRuntime.session(false) else session.onActivity(System.currentTimeMillis())
                        } else if (resumeDeadlineMs > 0) {
                            // The "interruption" was only noise: resume
                            // what she was saying (V13).
                            resumeDeadlineMs = 0L
                            speaker.resumeIfInterrupted()
                        }
                        utterance.clear()
                        preRoll.clear()
                        // Re-arm for the next sentence in this session.
                        capturing = session.isOpen()
                        if (capturing) {
                            endpointer = newEndpointer()
                            endpointer.start(System.currentTimeMillis())
                        }
                    }
                } else if (!session.isOpen()) {
                    capturing = false
                }

                // Pre-roll: always keep the last ~1 second.
                preRoll.addLast(window)
                while (preRoll.size > 12) preRoll.removeFirst()
            }
        } catch (t: Throwable) {
            if (running) {
                CrashReports.recordError("AlwaysListenService:loop", t)
                fail("listening stopped: ${t.message ?: "microphone error"}")
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    private fun newEndpointer(): RelativeEndpointer = RelativeEndpointer(
        onsetRms = 0.02f,
        closeRatio = 0.25f,
        floorAfterOnset = 0.004f,
        pauseMs = if (longerListenWindow) 1_400L else 900L,
        minSpeechMs = 250L,
        hangoverMs = ListenSessionMachine.WAKE_HANGOVER_MS,
        safetyMaxMs = 20_000L
    )

    // ------------------------------------------------------------------
    // Utterances
    // ------------------------------------------------------------------

    private fun handleUtterance(samples: ShortArray, session: ListenSessionMachine) {
        val rec = recognizer
        if (rec == null) {
            VoiceRuntime.setSttEngine("not downloaded yet")
            if (!sttAnnouncedMissing) {
                sttAnnouncedMissing = true
                speaker.speakAsync(
                    "I heard you, but my understanding hasn't finished downloading yet. " +
                        "You can type to me for now — the words work just the same.",
                    EmotionGate.Situation.NORMAL
                )
            }
            return
        }
        val text = try {
            val floats = FloatArray(samples.size) { samples[it] / 32768f }
            val stream = rec.createStream()
            stream.acceptWaveform(floats, 16_000)
            rec.decode(stream)
            rec.getResult(stream).text.trim()
        } catch (t: Throwable) {
            CrashReports.recordError("AlwaysListenService:recognize", t)
            VoiceRuntime.noteFailure("understanding failed: ${t.message ?: "recognizer error"}")
            return
        }
        if (text.isBlank()) return
        VoiceRuntime.lastHeard(text)

        // Strip a leading wake phrase (the pre-roll contains it).
        val cleaned = text.replace(
            Regex("^\\s*(hey|okay|ok)?\\s*ilona\\b[\\s,.:;!]*", RegexOption.IGNORE_CASE), ""
        ).trim()
        val effective = cleaned.ifBlank { text }
        VoiceRuntime.transcribed(effective)

        // Session control phrases are consumed here, never sent on.
        if (session.onPhrase(effective, System.currentTimeMillis())) {
            if (session.isOpen()) {
                speaker.speakAsync("Of course. I'll stay right here.", EmotionGate.Situation.NORMAL)
            } else {
                VoiceRuntime.session(false)
                speaker.speakAsync("All right. I'm here whenever you need me — just say, hey Ilona.", EmotionGate.Situation.NORMAL)
            }
            return
        }
        if (cleaned.isBlank() && !effective.equals(text, ignoreCase = true)) {
            // The utterance was just the wake phrase itself: the
            // session simply stays open for the real request.
            session.onActivity(System.currentTimeMillis())
            return
        }
        session.onActivity(System.currentTimeMillis())
        VoiceBus.publish(effective)
    }

    // ------------------------------------------------------------------
    // sherpa init
    // ------------------------------------------------------------------

    private fun initSpotter() {
        if (!models.isReady(VoiceModelManager.Pack.WAKE_KWS)) {
            VoiceRuntime.setWakeEngine("waiting for the wake-word download")
            return
        }
        try {
            val dir = models.kwsDir()
            val encoder = findModelFile(dir, "encoder") ?: throw IllegalStateException("KWS encoder missing")
            val decoder = findModelFile(dir, "decoder") ?: throw IllegalStateException("KWS decoder missing")
            val joiner = findModelFile(dir, "joiner") ?: throw IllegalStateException("KWS joiner missing")
            val tokens = File(dir, "tokens.txt")

            // The wake phrase: the pack-verified BPE lines first
            // (KwsKeywords — canonical segmentation, checked against
            // this pack's vocabulary); the greedy composer only as a
            // last resort for a different future pack.
            val vocab = KwsKeywords.vocabulary(tokens.readText())
            var composedLine: String? = null
            var usedPhrase: String? = null
            for ((phrase, line) in KwsKeywords.VERIFIED) {
                if (KwsKeywords.lineFits(line, vocab)) {
                    composedLine = line
                    usedPhrase = phrase
                    break
                }
            }
            if (composedLine == null) {
                for (candidate in KwsKeywords.candidates()) {
                    val line = KwsKeywords.compose(candidate, vocab)
                    if (line != null) {
                        composedLine = line
                        usedPhrase = candidate
                        break
                    }
                }
            }
            if (composedLine == null || usedPhrase == null) {
                VoiceRuntime.setWakeEngine("the wake phrase can't be spelled by this wake model")
                CrashReports.recordProblem("AlwaysListenService", "KWS vocabulary cannot compose 'hey ilona' or 'ilona'")
                kwsRetryAtMs = Long.MAX_VALUE
                return
            }
            val runtimeDir = File(applicationContext.filesDir, "voice-models/kws-runtime").apply { mkdirs() }
            val keywordsFile = File(runtimeDir, "keywords.txt")
            keywordsFile.writeText(composedLine + "\n")

            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = 2
                ),
                keywordsFile = keywordsFile.absolutePath,
                keywordsScore = 1.0f,
                keywordsThreshold = 0.25f
            )
            spotter = KeywordSpotter(null, config)
            spotterStream = spotter?.createStream()
            wakePhraseUsed = usedPhrase
            VoiceRuntime.setWakeEngine("listening for '${usedPhrase.replaceFirstChar { it.titlecase() }}'")
        } catch (t: Throwable) {
            kwsRetryAtMs = System.currentTimeMillis() + 60_000
            VoiceRuntime.setWakeEngine("wake-word problem: ${t.message ?: "init failed"}")
            CrashReports.recordError("AlwaysListenService:kwsInit", t)
        }
    }

    private fun feedSpotter(floats: FloatArray) {
        val s = spotter ?: return
        val stream = spotterStream ?: return
        try {
            stream.acceptWaveform(floats, 16_000)
            while (s.isReady(stream)) s.decode(stream)
        } catch (t: Throwable) {
            VoiceRuntime.noteFailure("wake spotting hiccup: ${t.message ?: "error"}")
        }
    }

    private fun consumeWakeHit(): Boolean {
        val s = spotter ?: return false
        val stream = spotterStream ?: return false
        return try {
            val result = s.getResult(stream)
            if (result.keyword.isNotBlank()) {
                s.reset(stream)
                true
            } else false
        } catch (t: Throwable) {
            false
        }
    }

    private fun initRecognizer() {
        if (!models.isReady(VoiceModelManager.Pack.STT_PARAKEET)) {
            VoiceRuntime.setSttEngine("not downloaded yet")
            return
        }
        try {
            val dir = models.sttDir()
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(dir, "parakeet-encoder.onnx").absolutePath,
                        decoder = File(dir, "parakeet-decoder.onnx").absolutePath,
                        joiner = File(dir, "parakeet-joiner.onnx").absolutePath
                    ),
                    tokens = File(dir, "parakeet-tokens.txt").absolutePath,
                    modelType = "nemo_transducer",
                    numThreads = 2,
                    provider = "cpu"
                ),
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 128),
                decodingMethod = "greedy_search"
            )
            recognizer = OfflineRecognizer(null, config)
            VoiceRuntime.setSttEngine("Parakeet, on this phone")
        } catch (t: Throwable) {
            sttRetryAtMs = System.currentTimeMillis() + 60_000
            VoiceRuntime.setSttEngine("understanding problem: ${t.message ?: "init failed"}")
            CrashReports.recordError("AlwaysListenService:sttInit", t)
        }
    }

    /** The pack ships fp32 + int8 twins; always-on listening takes int8. */
    private fun findModelFile(dir: File, prefix: String): File? {
        val candidates = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
            ?: return null
        return candidates.firstOrNull { it.name.contains(".int8.") } ?: candidates.minByOrNull { it.name.length }
    }

    private fun fail(reason: String) {
        VoiceRuntime.noteFailure(reason)
        VoiceRuntime.serviceStopped(reason)
        CrashReports.recordProblem("AlwaysListenService", reason)
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ilona listening", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Ilona is listening for 'Hey Ilona'"
                }
            )
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Ilona is listening")
            .setContentText("Say “Hey Ilona” whenever you need her. Listening happens on this phone.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    companion object {
        private const val CHANNEL_ID = "ilona_listening"
        private const val NOTIFICATION_ID = 4101

        /** Start the listener, if it isn't already running. */
        fun start(context: Context) {
            val intent = Intent(context, AlwaysListenService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlwaysListenService::class.java))
        }
    }
}
