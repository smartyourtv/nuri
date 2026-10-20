package com.app.nuri.tts.android

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.app.nuri.config.NuriSettings
import com.app.nuri.reports.android.CrashReports
import com.app.nuri.repository.SettingsRepository
import com.app.nuri.speech.VoiceRuntime
import com.app.nuri.tts.BreathSoundGenerator
import com.app.nuri.tts.EmotionGate
import com.app.nuri.tts.EmotionProsody
import com.app.nuri.tts.EmotionSoundRenderer
import com.app.nuri.tts.SpeakableText
import com.app.nuri.tts.VoiceProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * IlonaSpeaker — the one speech seam for the whole app (v0.4.0).
 *
 * Every voice Ilona has goes through here: Show Ilona read-aloud,
 * check-ins, the listener's own short replies, the self-test.
 * Rules enforced HERE so no caller can bypass them:
 *  - Text passes SpeakableText (in the Kokoro engine, and here for
 *    the platform fallback) — "nine one one", year words, doses.
 *  - The SELECTED voice speaks (VoiceProfiles over the persisted
 *    single_voice_id; June by default; updates never reset it).
 *  - Emotion sounds pass the EmotionGate (urgent/serious = none,
 *    always, in code).
 *  - FALLBACK HONESTY: when Kokoro isn't ready, the phone's
 *    built-in voice speaks instead, and [lastSpokeWith] + the
 *    diagnostics card SAY SO. June is never claimed when the
 *    device voice spoke.
 *
 * June's emotion sounds are her bank performances, and the men's
 * voices use the male bank — emotion sounds follow the selected
 * voice. Voices without a bank use the shaped renderer (phone
 * Scout lineage) with its honest ceiling. All speech is
 * serialized on one executor.
 */
class IlonaSpeaker private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val repo = SettingsRepository(appContext)
    val models = VoiceModelManager(appContext)
    val engine = KokoroVoiceEngine(appContext, models)
    private val bankPlayers = java.util.concurrent.ConcurrentHashMap<String, EmotionBankPlayer>()

    /** The bank player for an asset dir, created on first use. */
    private fun bankPlayer(assetDir: String): EmotionBankPlayer =
        bankPlayers.getOrPut(assetDir) { EmotionBankPlayer(appContext, assetDir) }

    /** June's bank — the default voice's; the health card reports it. */
    private val bank: EmotionBankPlayer get() = bankPlayer(com.app.nuri.tts.EmotionBank.ASSET_DIR)

    private val speechExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ilona-speaker").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The speech-relevant slice of the settings store, kept current. */
    data class SpeechSettings(
        val emotionLevel: NuriSettings.EmotionLevel = NuriSettings.DEFAULT_EMOTION_LEVEL,
        val tier: NuriSettings.Tier = NuriSettings.DEFAULT_TIER,
        val slowerSpeech: Boolean = false
    )

    @Volatile private var settings: SpeechSettings = SpeechSettings()

    enum class SpokeWith { NONE, KOKORO, PLATFORM }

    private val _lastSpokeWith = MutableStateFlow(SpokeWith.NONE)
    val lastSpokeWith: StateFlow<SpokeWith> = _lastSpokeWith

    private val recentBankIds = java.util.ArrayDeque<String>()

    // Platform fallback (the v0.3.2 interim voice).
    @Volatile private var platformTts: TextToSpeech? = null
    @Volatile private var platformReady = false
    @Volatile private var platformSpeaking = false

    init {
        scope.launch { repo.emotionLevel.collect { v -> settings = settings.copy(emotionLevel = v) } }
        scope.launch { repo.tier.collect { v -> settings = settings.copy(tier = v) } }
        scope.launch {
            repo.personToggles.collect { v ->
                settings = settings.copy(slowerSpeech = v.slowerSpeech ?: false)
            }
        }
        scope.launch {
            // Warm the engine as soon as the pack exists; if a
            // download completes later, the next speak retries.
            engine.ensureLoaded()
        }
    }

    /** The voice that would speak right now (persisted choice; June default). */
    fun currentVoice(): VoiceProfiles.Voice = VoiceProfiles.resolve(storedVoiceId())

    /** Read the persisted voice id fresh — a pick takes effect on the very next line. */
    private fun storedVoiceId(): String =
        runCatching { runBlocking { repo.singleVoiceId.first() } }.getOrNull() ?: VoiceProfiles.LEGACY_UNSET

    /** Play [voice]'s sample line in that voice (the picker's tap-to-hear). */
    fun speakSample(voice: VoiceProfiles.Voice) {
        speechExecutor.execute {
            engine.ensureLoaded()
            if (engine.isReady) {
                engine.speak(voice.sampleLine, voice.sid, VoiceProfiles.speedFor(voice, 1f, settings.slowerSpeech), withBreaths = false)
                _lastSpokeWith.value = SpokeWith.KOKORO
            } else {
                speakPlatform(SpeakableText.normalize(voice.sampleLine), 1f, settings.slowerSpeech)
                _lastSpokeWith.value = SpokeWith.PLATFORM
            }
        }
    }

    fun bankAvailable(): Boolean = bank.isAvailable
    fun bankEntryCount(): Int = bank.entries.size

    /**
     * Speak [text]. [situation] tags the moment for the emotion
     * gate — callers pass URGENT/SERIOUS for alerts (no sounds, ever)
     * and FUNNY only when the reply is genuinely playful. Async:
     * returns immediately; speech queues on the speaker executor.
     */
    fun speakAsync(text: String, situation: EmotionGate.Situation = EmotionGate.Situation.NORMAL) {
        speechExecutor.execute { speakBlocking(text, situation) }
    }

    /** Speak and wait until done (self-test, sequenced prompts). */
    fun speakBlocking(text: String, situation: EmotionGate.Situation = EmotionGate.Situation.NORMAL) {
        if (text.isBlank()) return
        val snapshot = settings
        val voice = VoiceProfiles.resolve(storedVoiceId())
        val prosody = EmotionProsody.hintsFor(snapshot.emotionLevel, snapshot.tier)
        val effectiveLevel = if (snapshot.tier == NuriSettings.Tier.GUIDED)
            com.app.nuri.tier.TierDefaults.clampEmotionForGuided(snapshot.emotionLevel)
        else snapshot.emotionLevel
        engine.ensureLoaded()
        if (engine.isReady) {
            maybeEmotionCue(voice, situation, effectiveLevel)
            val speed = VoiceProfiles.speedFor(voice, prosody.rateMultiplier, snapshot.slowerSpeech)
            val breaths = situation != EmotionGate.Situation.URGENT && situation != EmotionGate.Situation.SERIOUS
            engine.speak(text, voice.sid, speed, withBreaths = breaths)
            _lastSpokeWith.value = SpokeWith.KOKORO
        } else {
            speakPlatform(SpeakableText.normalize(text), prosody.rateMultiplier, snapshot.slowerSpeech)
            _lastSpokeWith.value = SpokeWith.PLATFORM
        }
    }

    /** Stop all speech now (a plain stop — no resume position kept). */
    fun stop() {
        engine.stop(bargeIn = false)
        runCatching { platformTts?.stop() }
        platformSpeaking = false
    }

    /** Barge-in stop from the listener: keeps a resume position (V13). */
    fun stopForBargeIn() {
        engine.stop(bargeIn = true)
        runCatching { platformTts?.stop() }
        platformSpeaking = false
    }

    /** Resume after a cleared false alarm, from the exact sentence (V13). */
    fun resumeIfInterrupted() {
        if (engine.lastInterrupted != null) {
            speechExecutor.execute { engine.resume() }
        }
    }

    val isSpeaking: Boolean get() = engine.isSpeaking || platformSpeaking

    // ------------------------------------------------------------------
    // Emotion cues
    // ------------------------------------------------------------------

    private fun maybeEmotionCue(
        voice: VoiceProfiles.Voice,
        situation: EmotionGate.Situation,
        level: NuriSettings.EmotionLevel
    ) {
        try {
            val voiceBank = voice.bankDir?.let { bankPlayer(it) }
            if (voiceBank != null && voiceBank.isAvailable) {
                val picked = EmotionGate.decide(
                    situation, level, com.app.nuri.tts.EmotionBank.gateCatalog(voiceBank.entries),
                    recentBankIds.toList(), Random
                ) ?: return
                val entry = voiceBank.entry(picked.id) ?: return
                val decoded = voiceBank.decode(entry) ?: return
                engine.playPcm(decoded.first, decoded.second)
                recentBankIds.addLast(entry.id)
                while (recentBankIds.size > 2) recentBankIds.removeFirst()
                return
            }
            // Voices without a bank (or a bank missing from this
            // build): shaped synthesis in the selected voice — the
            // Scout lineage route, honest ceiling noted in the
            // lessons file.
            val picked = EmotionGate.decide(
                situation, level,
                SHAPED_CATALOG, recentShapedKinds.toList(), Random
            ) ?: return
            playShapedCue(voice, EmotionGate.SoundKind.valueOf(picked.id))
        } catch (t: Throwable) {
            CrashReports.recordError("IlonaSpeaker:emotionCue", t)
        }
    }

    private val recentShapedKinds = java.util.ArrayDeque<String>()

    private fun playShapedCue(voice: VoiceProfiles.Voice, kind: EmotionGate.SoundKind) {
        val rendered: Pair<FloatArray, Int> = when (kind) {
            EmotionGate.SoundKind.BREATH_LIGHT, EmotionGate.SoundKind.BREATH_SOFT -> {
                // The generator returns 16-bit PCM; the player takes floats.
                val pcm = BreathSoundGenerator.generateBreath(24_000, durationMs = 240)
                FloatArray(pcm.size) { pcm[it] / 32768f } to 24_000
            }
            EmotionGate.SoundKind.CHUCKLE ->
                shapedFromVoice(voice) { frag, sr -> EmotionSoundRenderer.renderLaugh(frag, sr, EmotionSoundRenderer.LaughKind.CHUCKLE, Random) }
            // The renderer has two gears (chuckle / full laugh): the
            // gate's LAUGH and FULL_LAUGH both take the full gear.
            EmotionGate.SoundKind.LAUGH, EmotionGate.SoundKind.FULL_LAUGH ->
                shapedFromVoice(voice) { frag, sr -> EmotionSoundRenderer.renderLaugh(frag, sr, EmotionSoundRenderer.LaughKind.FULL_LAUGH, Random) }
            EmotionGate.SoundKind.SIGH ->
                shapedFromVoice(voice) { frag, sr -> EmotionSoundRenderer.renderSigh(frag, sr, Random) }
            // No shaped surprise exists in the renderer; the gate's
            // own fallback already maps surprise to a chuckle, so
            // this branch is unreachable in practice — a chuckle is
            // the honest stand-in, never a fake gasp.
            EmotionGate.SoundKind.SURPRISE ->
                shapedFromVoice(voice) { frag, sr -> EmotionSoundRenderer.renderLaugh(frag, sr, EmotionSoundRenderer.LaughKind.CHUCKLE, Random) }
            EmotionGate.SoundKind.NONE -> return
        }
        if (rendered.first.isEmpty()) return
        engine.playPcm(rendered.first, rendered.second)
        recentShapedKinds.addLast(kind.name)
        while (recentShapedKinds.size > 2) recentShapedKinds.removeFirst()
    }

    /** Synthesize a short neutral carrier in the voice, then shape it. */
    private fun shapedFromVoice(
        voice: VoiceProfiles.Voice,
        shape: (FloatArray, Int) -> FloatArray
    ): Pair<FloatArray, Int> {
        // The engine owns synthesis; a carrier syllable rendered by
        // the voice itself is the raw material (never a word — the
        // renderer keeps only the voice's timbre and rhythm).
        val carrier = engine.synthesizeFragment("uh", voice.sid)
        if (carrier == null || carrier.first.isEmpty()) return FloatArray(0) to 24_000
        return shape(carrier.first, carrier.second) to carrier.second
    }

    // ------------------------------------------------------------------
    // Voice self-test (V19): judged BY EAR — this only plays samples.
    // ------------------------------------------------------------------

    /**
     * The one-tap self-test: a normal line, then each emotion sound,
     * a chuckle, a full laugh, and a breath — in the SELECTED voice,
     * at the CURRENT emotion level (a level that forbids a sound
     * says so out loud instead of faking it). Runs on the speaker
     * executor; call from a background scope.
     */
    fun runSelfTest() {
        val snapshot = settings
        val voice = VoiceProfiles.resolve(storedVoiceId())
        val level = if (snapshot.tier == NuriSettings.Tier.GUIDED)
            com.app.nuri.tier.TierDefaults.clampEmotionForGuided(snapshot.emotionLevel)
        else snapshot.emotionLevel
        engine.ensureLoaded()
        if (!engine.isReady) {
            speakBlocking(
                "This is the phone's built-in voice. Ilona's own voice hasn't downloaded yet, " +
                    "so this test uses the phone's voice. Her real voice will sound warmer and more natural."
            )
            return
        }
        speakBlocking("Hello. This is ${voice.displayName.substringBefore(" —")}, the voice you chose for Ilona. Here is how I sound when I talk with you.")
        playSelfTestCue(voice, EmotionGate.SoundKind.CHUCKLE, level, "a small chuckle")
        playSelfTestCue(voice, EmotionGate.SoundKind.LAUGH, level, "a laugh")
        playSelfTestCue(voice, EmotionGate.SoundKind.FULL_LAUGH, level, "a big, full laugh")
        playSelfTestCue(voice, EmotionGate.SoundKind.SIGH, level, "a soft sigh")
        playSelfTestCue(voice, EmotionGate.SoundKind.SURPRISE, level, "a surprised sound")
        playSelfTestCue(voice, EmotionGate.SoundKind.BREATH_SOFT, level, "a quiet breath")
        speakBlocking("That is the end of the voice test. If any sound didn't seem right, tell the family and they can pick a different voice or feeling level in Settings.")
    }

    private fun playSelfTestCue(
        voice: VoiceProfiles.Voice,
        kind: EmotionGate.SoundKind,
        level: NuriSettings.EmotionLevel,
        label: String
    ) {
        // Level permission mirrors the gate's tables exactly.
        val allowed = when (kind) {
            EmotionGate.SoundKind.CHUCKLE -> level != NuriSettings.EmotionLevel.CALM
            EmotionGate.SoundKind.LAUGH -> level == NuriSettings.EmotionLevel.EXPRESSIVE ||
                level == NuriSettings.EmotionLevel.FULL_HEARTED
            EmotionGate.SoundKind.FULL_LAUGH -> level == NuriSettings.EmotionLevel.FULL_HEARTED ||
                level == NuriSettings.EmotionLevel.EXPRESSIVE
            EmotionGate.SoundKind.SIGH, EmotionGate.SoundKind.BREATH_SOFT,
            EmotionGate.SoundKind.BREATH_LIGHT -> true
            EmotionGate.SoundKind.SURPRISE -> level != NuriSettings.EmotionLevel.CALM &&
                level != NuriSettings.EmotionLevel.GENTLE
            EmotionGate.SoundKind.NONE -> false
        }
        if (!allowed) {
            speakBlocking("At the feeling level you chose, I don't use $label out loud.")
            return
        }
        speakBlocking("Here is $label.")
        if (voice.sid == VoiceProfiles.JUNE.sid && bank.isAvailable) {
            val candidates = bank.entries.filter { it.kind == kind }
            val entry = candidates.firstOrNull { it.id !in recentBankIds } ?: candidates.firstOrNull()
            if (entry != null) {
                bank.decode(entry)?.let { engine.playPcm(it.first, it.second) }
                recentBankIds.addLast(entry.id)
                while (recentBankIds.size > 2) recentBankIds.removeFirst()
                return
            }
        }
        playShapedCue(voice, kind)
    }

    // ------------------------------------------------------------------
    // Platform fallback
    // ------------------------------------------------------------------

    private fun speakPlatform(spokenText: String, rateMultiplier: Float, slowerSpeech: Boolean) {
        val tts = ensurePlatformTts() ?: run {
            VoiceRuntime.noteFailure("no voice available: Kokoro not ready and the phone voice failed to start")
            CrashReports.recordProblem("IlonaSpeaker", "platform TTS unavailable when Kokoro was not ready")
            return
        }
        VoiceRuntime.setTtsEngine("Phone's built-in voice (Ilona's voice not downloaded yet)")
        if (!platformReady) {
            // The v0.3.2 hold: the first utterance waits for the
            // engine instead of being dropped.
            pendingPlatformText = spokenText to (rateMultiplier to slowerSpeech)
            return
        }
        platformSpeaking = true
        VoiceRuntime.setSpeaking(true)
        val rate = (BASE_PLATFORM_RATE * rateMultiplier * if (slowerSpeech) 0.92f else 1f)
        tts.setSpeechRate(rate)
        val result = tts.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        if (result == TextToSpeech.ERROR) {
            platformSpeaking = false
            VoiceRuntime.setSpeaking(false)
            VoiceRuntime.noteFailure("the phone's voice refused to speak")
        }
    }

    @Volatile private var pendingPlatformText: Pair<String, Pair<Float, Boolean>>? = null

    private fun ensurePlatformTts(): TextToSpeech? {
        platformTts?.let { return it }
        var created: TextToSpeech? = null
        created = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { created?.setLanguage(Locale.US) }
                created?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        platformSpeaking = false
                        VoiceRuntime.setSpeaking(false)
                    }
                    @Deprecated("deprecated in API 21")
                    override fun onError(utteranceId: String?) {
                        platformSpeaking = false
                        VoiceRuntime.setSpeaking(false)
                    }
                })
                platformReady = true
                pendingPlatformText?.let { (text, rates) ->
                    pendingPlatformText = null
                    speakPlatform(text, rates.first, rates.second)
                }
            } else {
                CrashReports.recordProblem("IlonaSpeaker", "phone voice failed to start (status $status)")
            }
        }
        platformTts = created
        return created
    }

    companion object {
        private const val BASE_PLATFORM_RATE = 0.85f

        private val SHAPED_CATALOG: List<EmotionGate.Entry> =
            EmotionGate.SoundKind.values()
                .filter { it != EmotionGate.SoundKind.NONE && it != EmotionGate.SoundKind.SURPRISE }
                .map { EmotionGate.Entry(id = it.name, kind = it) }

        @Volatile private var instance: IlonaSpeaker? = null

        /** The app-wide seam. One instance per process. */
        fun shared(context: Context): IlonaSpeaker =
            instance ?: synchronized(this) {
                instance ?: IlonaSpeaker(context.applicationContext).also { instance = it }
            }
    }
}
