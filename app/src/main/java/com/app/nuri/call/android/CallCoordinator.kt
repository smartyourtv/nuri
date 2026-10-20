package com.app.nuri.call

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.telecom.TelecomManager
import com.app.nuri.call.android.WebRtcEngine
import com.app.nuri.config.FlavorHooks
import com.app.nuri.config.NuriSettings
import com.app.nuri.remote.CallEventPayload
import com.app.nuri.remote.CallKind
import com.app.nuri.remote.CallOutcome
import com.app.nuri.remote.RemoteDoc
import com.app.nuri.remote.android.RemoteSession
import com.app.nuri.repository.SettingsRepository
import com.app.nuri.reports.android.CrashReports
import com.app.nuri.speech.android.VoiceBus
import com.app.nuri.tts.android.IlonaSpeaker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The follow-through orchestrator (design §17) — the Android half.
 *
 * One hard rule, enforced by construction: media is created ONLY
 * in [openMedia], and [openMedia] is called ONLY with a
 * [GateDecision.OPEN_MEDIA] produced by [AnnouncementGate] — which
 * for check-in calls additionally requires his explicit answer.
 * There is no other call site that touches [WebRtcEngine] or the
 * telephony placer.
 */
object CallCoordinator {

    enum class Phase { IDLE, RINGING_CHECKIN, ANNOUNCING, WAITING_CAREGIVER, CONNECTING, IN_CALL, STAYING, ENDED }

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val headline: String = "",
        val detail: String = "",
        val kind: String = "",
        val media: CallMedia = CallMedia.VIDEO_AND_AUDIO,
        val videoOn: Boolean = true,
        val showAnswer: Boolean = false,
        val showDecline: Boolean = false,
        val showEnd: Boolean = false,
        val showEmergencyOffer: Boolean = false,
        val connectedAtMs: Long = 0L,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var seniorSession: RemoteSession? = null
    @Volatile private var caregiverSessionRef: RemoteSession? = null
    @Volatile private var activeJob: Job? = null
    @Volatile private var engine: WebRtcEngine? = null
    @Volatile private var telephonyCall: Boolean = false
    private val windowSignal = AtomicReference(WindowSignal.NONE)
    @Volatile private var currentSessionId: String? = null
    @Volatile private var currentKind: String = CallKind.CHECKIN
    @Volatile private var currentAlertTitle: String? = null
    @Volatile private var mediaStartedAtMs: Long = 0L
    @Volatile private var handledRequests = mutableSetOf<String>()

    fun attachSeniorSession(context: Context, session: RemoteSession) {
        seniorSession = session
    }

    fun attachCaregiverSession(context: Context, session: RemoteSession) {
        caregiverSessionRef = session
    }

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun timeText(): String = SimpleDateFormat("h:mm a", Locale.US).format(Date())

    private suspend fun caregiverName(context: Context): String {
        val repo = SettingsRepository(context)
        return CallNaming.caregiverDisplayName(FlavorHooks.askNameOverride(), repo.caregiverName.first())
    }

    private suspend fun callNumber(context: Context): String? {
        val repo = SettingsRepository(context)
        val direct = repo.caregiverCallNumber.first()
        if (direct.isNotBlank()) return direct
        val emergency = repo.emergencyContactNumber.first()
        val effective = FlavorHooks.effectiveEmergencyNumber(context, emergency)
        return effective.takeIf { it.isNotBlank() }
    }

    private suspend fun mediaPreference(context: Context): CallMedia {
        val repo = SettingsRepository(context)
        val personVoiceOnly = repo.personVoiceOnlyCalls.first()
        val householdVoiceOnly = seniorSession?.let { session ->
            val doc = runCatching { session.store.get(session.paths.household) }.getOrNull()
            RemoteDoc.str(doc ?: emptyMap(), "callMedia") == "VOICE"
        } ?: false
        return CallMediaPolicy.effective(householdVoiceOnly, personVoiceOnly)
    }

    // ── Senior side: triggers ────────────────────────────────────

    /** A check-in call request arrived on the household document (§17.2). */
    fun onCheckInRequest(context: Context, requestId: String) {
        val appContext = context.applicationContext
        synchronized(handledRequests) {
            if (requestId in handledRequests) return
            handledRequests += requestId
        }
        if (_uiState.value.phase != Phase.IDLE) return
        val session = seniorSession ?: return
        activeJob = scope.launch {
            runCatching { runCheckInCall(appContext, session, requestId) }
                .onFailure { CrashReports.recordProblem("Check-in call", "Failed: ${it.message}") }
        }
    }

    /** A safety alert record appeared; qualifying titles trigger follow-through (§17.3). */
    fun onSafetyAlert(context: Context, alertTitle: String) {
        if (!FollowThroughPolicy.qualifies(alertTitle)) return
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("call_handled", Context.MODE_PRIVATE)
        val key = "alert:${alertTitle.hashCode()}"
        val last = prefs.getLong(key, 0L)
        if (System.currentTimeMillis() - last < 10 * 60 * 1000L) return
        prefs.edit().putLong(key, System.currentTimeMillis()).apply()
        if (_uiState.value.phase != Phase.IDLE) return
        val session = seniorSession ?: return
        activeJob = scope.launch {
            runCatching { runAlertCall(appContext, session, alertTitle) }
                .onFailure { CrashReports.recordProblem("Alert follow-through", "Failed: ${it.message}") }
        }
    }

    /** A signal blob arrived on the counterpart channel (§17.1). */
    fun onRemoteSignal(context: Context, signal: CallSignal) {
        val sessionId = currentSessionId ?: return
        if (signal.sessionId != sessionId) return
        when (signal.state) {
            CallSignalState.DECLINED -> scope.launch { endSession(context.applicationContext, CallOutcome.DECLINED, byRemote = true) }
            CallSignalState.ENDED -> scope.launch { endSession(context.applicationContext, CallOutcome.ANSWERED, byRemote = true) }
            CallSignalState.NO_ANSWER -> scope.launch { endSession(context.applicationContext, CallOutcome.NO_ANSWER, byRemote = true) }
            CallSignalState.RINGING -> {
                // Caregiver side: a senior-initiated alert call is ringing us.
                if (caregiverSessionRef != null && signal.kind == CallKind.ALERT) {
                    _uiState.value = UiState(
                        phase = Phase.RINGING_CHECKIN,
                        headline = "He needs you",
                        detail = "An alert call is coming in. Tap Answer to talk — he can see and hear you.",
                        kind = CallKind.ALERT,
                        showAnswer = true,
                        showDecline = true,
                    )
                    pendingRemoteOffer = signal
                }
            }
            CallSignalState.CONNECTED -> {
                // An answer SDP for our outgoing session.
                val sdp = signal.sdp
                if (sdp != null) {
                    scope.launch { runCatching { engine?.acceptAnswer(sdp) } }
                }
            }
        }
    }

    @Volatile private var pendingRemoteOffer: CallSignal? = null

    // ── Senior side: the two flows ───────────────────────────────

    private suspend fun runCheckInCall(context: Context, session: RemoteSession, requestId: String) {
        com.app.nuri.call.android.CallAlerts.raise(context, "A call is coming in", "Ilona is announcing it — open Ilona to answer.")
        val name = caregiverName(context)
        currentKind = CallKind.CHECKIN
        currentSessionId = requestId
        windowSignal.set(WindowSignal.NONE)
        _uiState.value = UiState(
            phase = Phase.RINGING_CHECKIN,
            headline = "$name would like to talk",
            detail = "Tap Talk to answer — $name will see and hear you. Tap No and nothing will happen.",
            kind = CallKind.CHECKIN,
            showAnswer = true,
            showDecline = true,
        )
        val gate = newGate(context)
        val decision = gate.runCheckInGate(name)
        when (decision) {
            GateDecision.OPEN_MEDIA -> openMedia(context, session, CallKind.CHECKIN, null)
            GateDecision.CANCELLED_DECLINED -> {
                writeSeniorSignal(session, requestId, CallKind.CHECKIN, CallSignalState.DECLINED, null)
                postCallEvent(session, CallKind.CHECKIN, null, CallOutcome.DECLINED)
                finishWithMessage("You said no to the call.", "$name was told you didn't want to talk right now.")
            }
            GateDecision.CANCELLED_NO_ANSWER -> {
                writeSeniorSignal(session, requestId, CallKind.CHECKIN, CallSignalState.NO_ANSWER, null)
                postCallEvent(session, CallKind.CHECKIN, null, CallOutcome.NO_ANSWER)
                finishWithMessage("The call request ended.", "Nobody answered, so nothing was opened.")
            }
            GateDecision.CANCELLED_ANNOUNCE_FAILED -> {
                postCallEvent(session, CallKind.CHECKIN, null, CallOutcome.FAILED)
                finishWithMessage("The call couldn't start.", "Ilona couldn't make the announcement, so no call was placed.")
            }
        }
    }

    private suspend fun runAlertCall(context: Context, session: RemoteSession, alertTitle: String) {
        com.app.nuri.call.android.CallAlerts.raise(context, "Ilona is opening a call", "Ilona announced it first — open Ilona to see the call.")
        val name = caregiverName(context)
        currentKind = CallKind.ALERT
        currentAlertTitle = alertTitle
        val sessionId = UUID.randomUUID().toString()
        currentSessionId = sessionId
        windowSignal.set(WindowSignal.NONE)
        val deliberate = FollowThroughPolicy.isDeliberate(alertTitle)
        _uiState.value = UiState(
            phase = Phase.ANNOUNCING,
            headline = "Calling $name",
            detail = "Ilona is announcing the call first. Say no, or tap No, to stop it.",
            kind = CallKind.ALERT,
            showDecline = true,
        )
        val gate = newGate(context)
        val decision = gate.runAlertGate(name, deliberate)
        when (decision) {
            GateDecision.OPEN_MEDIA -> openMedia(context, session, CallKind.ALERT, alertTitle)
            GateDecision.CANCELLED_DECLINED -> {
                postCallEvent(session, CallKind.ALERT, alertTitle, CallOutcome.DECLINED)
                finishWithMessage("You stopped the call.", "The alert is still recorded for $name to see.")
            }
            else -> {
                postCallEvent(session, CallKind.ALERT, alertTitle, CallOutcome.FAILED)
                finishWithMessage("The call couldn't start.", "The alert is still recorded for $name to see.")
            }
        }
    }

    // ── The ONLY media entry point ──────────────────────────────

    private suspend fun openMedia(context: Context, session: RemoteSession, kind: String, alertTitle: String?) {
        val name = caregiverName(context)
        val media = mediaPreference(context)
        _uiState.value = _uiState.value.copy(
            phase = Phase.CONNECTING,
            headline = "Connecting to $name…",
            detail = "",
            media = media,
            videoOn = media == CallMedia.VIDEO_AND_AUDIO,
            showAnswer = false,
            showDecline = false,
            showEnd = true,
        )
        val sessionId = currentSessionId ?: return
        val eng = WebRtcEngine(context)
        engine = eng
        var connected = false
        eng.listener = object : WebRtcEngine.Listener {
            override fun onConnected() {
                connected = true
                mediaStartedAtMs = System.currentTimeMillis()
                _uiState.value = _uiState.value.copy(
                    phase = Phase.IN_CALL,
                    headline = "You're talking with $name",
                    detail = if (media == CallMedia.VIDEO_AND_AUDIO) {
                        "The camera is on — $name can see you. This stays on screen the whole call."
                    } else {
                        "Voice call — the camera is off."
                    },
                    connectedAtMs = mediaStartedAtMs,
                )
                scope.launch {
                    runCatching {
                        IlonaSpeaker.shared(context).speakBlocking(CallScripts.callConnected(name), com.app.nuri.tts.EmotionGate.Situation.SERIOUS)
                    }
                }
                startTimeBox(context)
            }

            override fun onConnectFailed() {
                scope.launch {
                    if (kind == CallKind.ALERT) {
                        telephonyFallback(context, session, name, alertTitle)
                    } else {
                        postCallEvent(session, kind, alertTitle, CallOutcome.FAILED)
                        finishWithMessage("The call couldn't connect.", "You can try again from the dashboard.")
                    }
                }
            }

            override fun onDisconnected() {
                scope.launch { endSession(context, CallOutcome.ANSWERED, byRemote = true) }
            }
        }

        if (kind == CallKind.CHECKIN) {
            // Caregiver offered when they tapped "Call him"; we answer.
            val doc = runCatching { session.store.get(session.paths.household) }.getOrNull()
            val offer = CallSignalCodec.forSession(doc, sessionId)
            val sdp = offer?.sdp
            if (sdp == null) {
                postCallEvent(session, kind, alertTitle, CallOutcome.FAILED)
                finishWithMessage("The call couldn't connect.", "The other phone's call didn't arrive properly.")
                return
            }
            val answer = withIo { eng.acceptOffer(sdp, media) }
            if (answer == null) {
                postCallEvent(session, kind, alertTitle, CallOutcome.FAILED)
                finishWithMessage("The call couldn't connect.", "")
                return
            }
            writeSeniorSignal(session, sessionId, kind, CallSignalState.CONNECTED, answer)
        } else {
            // Alert: we offer; the caregiver answers (or doesn't — reverse case).
            val offer = withIo { eng.createOffer(media) }
            if (offer == null) {
                telephonyFallback(context, session, name, alertTitle)
                return
            }
            writeSeniorSignal(
                session, sessionId, kind, CallSignalState.RINGING, offer,
            )
            // Reverse case clock: no answer within the ring window.
            scope.launch {
                delay(AnnouncementGate.CAREGIVER_RING_MS)
                if (!connected && _uiState.value.phase == Phase.CONNECTING && currentSessionId == sessionId) {
                    caregiverDidNotAnswer(context, session, name)
                }
            }
        }
    }

    private suspend fun <T> withIo(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    /** Alert fallback (§17.1): announced telephony voice when the app call can't connect. */
    private suspend fun telephonyFallback(context: Context, session: RemoteSession, name: String, alertTitle: String?) {
        val number = callNumber(context)
        if (number == null) {
            runCatching { IlonaSpeaker.shared(context).speakBlocking(CallScripts.noNumberLine(), com.app.nuri.tts.EmotionGate.Situation.SERIOUS) }
            postCallEvent(session, CallKind.ALERT, alertTitle, CallOutcome.NO_NUMBER)
            finishWithMessage("No phone number for the call.", "The alert is recorded, but Ilona has no number to call.")
            return
        }
        runCatching {
            IlonaSpeaker.shared(context).speakBlocking(CallScripts.fallbackTelephonyAnnouncement(name), com.app.nuri.tts.EmotionGate.Situation.SERIOUS)
        }
        engine?.close(); engine = null
        val placed = placeTelephonyCall(context, number)
        if (!placed) {
            postCallEvent(session, CallKind.ALERT, alertTitle, CallOutcome.FAILED)
            finishWithMessage("The call couldn't be made.", "The alert is still recorded for $name to see.")
            return
        }
        telephonyCall = true
        mediaStartedAtMs = System.currentTimeMillis()
        _uiState.value = _uiState.value.copy(
            phase = Phase.IN_CALL,
            headline = "You're talking with $name",
            detail = "Phone call — the camera is off for this call.",
            media = CallMedia.VOICE_ONLY,
            videoOn = false,
            connectedAtMs = mediaStartedAtMs,
        )
        startTimeBox(context)
    }

    private fun placeTelephonyCall(context: Context, number: String): Boolean = try {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        scope.launch {
            delay(2000)
            runCatching {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = true
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    /** Reverse case (§17.3): the caregiver didn't pick up. Ilona stays with him. */
    private suspend fun caregiverDidNotAnswer(context: Context, session: RemoteSession, name: String) {
        engine?.close(); engine = null
        val repo = SettingsRepository(context)
        val emergencyName = FlavorHooks.effectiveEmergencyName(context, repo.emergencyContactName.first())
            .ifBlank { "your emergency contact" }
        runCatching {
            IlonaSpeaker.shared(context).speakBlocking(
                CallScripts.caregiverNoAnswer(name, emergencyName),
                com.app.nuri.tts.EmotionGate.Situation.SERIOUS,
            )
        }
        _uiState.value = UiState(
            phase = Phase.STAYING,
            headline = "$name didn't answer",
            detail = "The alert stays on $name's phone until $name sees it. Ilona is right here with you.",
            kind = CallKind.ALERT,
            showEmergencyOffer = true,
            showEnd = true,
        )
    }

    // ── In-call controls (UI calls these) ────────────────────────

    fun uiAnswer() {
        windowSignal.set(WindowSignal.BUTTON_ANSWER)
        // Caregiver answering an incoming alert call:
        val offer = pendingRemoteOffer
        val cg = caregiverSessionRef
        if (offer != null && cg != null && _uiState.value.phase == Phase.RINGING_CHECKIN) {
            pendingRemoteOffer = null
            currentSessionId = offer.sessionId
            currentKind = CallKind.ALERT
            scope.launch {
                runCatching { answerAsCaregiver(cg, offer) }
            }
        }
    }

    fun uiDecline() {
        windowSignal.set(WindowSignal.BUTTON_DECLINE)
        val offer = pendingRemoteOffer
        val cg = caregiverSessionRef
        if (offer != null && cg != null) {
            pendingRemoteOffer = null
            scope.launch {
                runCatching {
                    cg.store.update(
                        cg.paths.household,
                        CallSignalCodec.encode(
                            CallSignal(offer.sessionId, offer.kind, offer.media, null, "CAREGIVER", CallSignalState.DECLINED, System.currentTimeMillis()),
                        ),
                    )
                }
                _uiState.value = UiState()
            }
        }
    }

    fun uiToggleVideo() {
        val on = !_uiState.value.videoOn
        engine?.setVideoEnabled(on)
        _uiState.value = _uiState.value.copy(
            videoOn = on,
            detail = if (on) "The camera is on again." else "The camera is off. The call continues with voice only.",
        )
    }

    fun uiEnd(context: Context) {
        scope.launch { endSession(context.applicationContext, CallOutcome.ANSWERED, byRemote = false) }
    }

    /** STAYING state's big button: the ordinary emergency call path, unchanged. */
    fun uiEmergencyOffer(context: Context) {
        scope.launch {
            val repo = SettingsRepository(context)
            val number = FlavorHooks.effectiveEmergencyNumber(context, repo.emergencyContactNumber.first())
            if (number.isNotBlank()) {
                placeTelephonyCall(context, number)
            }
            _uiState.value = UiState()
        }
    }

    fun uiDismissEnded() {
        if (_uiState.value.phase == Phase.ENDED || _uiState.value.phase == Phase.STAYING) {
            _uiState.value = UiState()
        }
    }

    fun attachViews(local: org.webrtc.SurfaceViewRenderer?, remote: org.webrtc.SurfaceViewRenderer?) {
        engine?.attachViews(local, remote)
    }

    // ── Caregiver side: outgoing check-in call ───────────────────

    /** "Call him": writes the request + our offer. The dashboard surface shows progress. */
    fun caregiverCallHim(context: Context) {
        val cg = caregiverSessionRef ?: return
        val appContext = context.applicationContext
        if (_uiState.value.phase != Phase.IDLE) return
        scope.launch {
            runCatching {
                val sessionId = UUID.randomUUID().toString()
                currentSessionId = sessionId
                currentKind = CallKind.CHECKIN
                val eng = WebRtcEngine(appContext)
                engine = eng
                val media = CallMedia.VIDEO_AND_AUDIO
                val offer = withIo { eng.createOffer(media) } ?: run {
                    _uiState.value = UiState(phase = Phase.ENDED, headline = "The call couldn't start.", detail = "Try again in a moment.")
                    return@runCatching
                }
                cg.store.update(
                    cg.paths.household,
                    mapOf(
                        "callRequestId" to sessionId,
                        "callRequestAt" to System.currentTimeMillis(),
                    ) + CallSignalCodec.encode(
                        CallSignal(sessionId, CallKind.CHECKIN, media.name, offer, "CAREGIVER", CallSignalState.RINGING, System.currentTimeMillis()),
                    ),
                )
                _uiState.value = UiState(
                    phase = Phase.WAITING_CAREGIVER,
                    headline = "Calling him…",
                    detail = "His phone is announcing the call. Nothing opens on his phone unless he answers.",
                    kind = CallKind.CHECKIN,
                    showEnd = true,
                )
                wireEngineForCaregiver(appContext)
            }.onFailure {
                CrashReports.recordProblem("Call him", "Failed: ${it.message}")
                _uiState.value = UiState(phase = Phase.ENDED, headline = "The call couldn't start.", detail = "Check this phone's connection and try again.")
            }
        }
    }

    private fun wireEngineForCaregiver(context: Context) {
        engine?.listener = object : WebRtcEngine.Listener {
            override fun onConnected() {
                mediaStartedAtMs = System.currentTimeMillis()
                _uiState.value = _uiState.value.copy(
                    phase = Phase.IN_CALL,
                    headline = "You're talking with him",
                    detail = "Video call. This panel stays here for the whole call.",
                    connectedAtMs = mediaStartedAtMs,
                )
                startTimeBox(context)
            }

            override fun onConnectFailed() {
                _uiState.value = UiState(phase = Phase.ENDED, headline = "The call couldn't connect.", detail = "His phone answered, but the call itself didn't come through.")
                engine?.close(); engine = null
            }

            override fun onDisconnected() {
                scope.launch { endSession(context, CallOutcome.ANSWERED, byRemote = true) }
            }
        }
    }

    private suspend fun answerAsCaregiver(session: RemoteSession, offer: CallSignal) {
        val context = session.app.applicationContext
        val eng = WebRtcEngine(context)
        engine = eng
        wireEngineForCaregiver(context)
        val media = runCatching { CallMedia.valueOf(offer.media) }.getOrDefault(CallMedia.VIDEO_AND_AUDIO)
        val answer = offer.sdp?.let { withIo { eng.acceptOffer(it, media) } }
        if (answer == null) {
            _uiState.value = UiState(phase = Phase.ENDED, headline = "The call couldn't connect.", detail = "")
            return
        }
        session.store.update(
            session.paths.household,
            CallSignalCodec.encode(
                CallSignal(offer.sessionId, offer.kind, offer.media, answer, "CAREGIVER", CallSignalState.CONNECTED, System.currentTimeMillis()),
            ),
        )
        _uiState.value = UiState(
            phase = Phase.CONNECTING,
            headline = "Connecting…",
            kind = CallKind.ALERT,
            showEnd = true,
            media = media,
            videoOn = media == CallMedia.VIDEO_AND_AUDIO,
        )
    }

    // ── Session end ──────────────────────────────────────────────

    private fun startTimeBox(context: Context) {
        scope.launch {
            delay(AnnouncementGate.CALL_TIME_BOX_MS)
            if (_uiState.value.phase == Phase.IN_CALL) {
                runCatching {
                    IlonaSpeaker.shared(context).speakBlocking(CallScripts.callTimedOut(), com.app.nuri.tts.EmotionGate.Situation.SERIOUS)
                }
                endSession(context, CallOutcome.TIMEOUT, byRemote = false)
            }
        }
    }

    private suspend fun endSession(context: Context, outcome: String, byRemote: Boolean) {
        val wasInCall = _uiState.value.phase == Phase.IN_CALL
        val durationSec = if (mediaStartedAtMs > 0) {
            ((System.currentTimeMillis() - mediaStartedAtMs) / 1000).toInt()
        } else {
            0
        }
        if (telephonyCall) {
            runCatching {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                @Suppress("MissingPermission")
                telecom.endCall()
            }
            telephonyCall = false
        }
        engine?.close(); engine = null
        val sessionId = currentSessionId
        val kind = currentKind
        val title = currentAlertTitle
        // Notify the counterpart through our own channel.
        if (seniorSession != null && sessionId != null && wasInCall) {
            runCatching {
                writeSeniorSignal(
                    seniorSession!!, sessionId, kind, CallSignalState.ENDED, null,
                    durationSec = durationSec,
                    endedBy = if (byRemote) CallOutcome.ENDED_BY_CAREGIVER else CallOutcome.ENDED_BY_SENIOR,
                )
            }
            runCatching {
                postCallEvent(
                    seniorSession!!, kind, title,
                    if (outcome == CallOutcome.TIMEOUT) CallOutcome.TIMEOUT else CallOutcome.ANSWERED,
                    durationSec = durationSec,
                    endedBy = when {
                        outcome == CallOutcome.TIMEOUT -> CallOutcome.ENDED_BY_TIMEOUT
                        byRemote -> CallOutcome.ENDED_BY_CAREGIVER
                        else -> CallOutcome.ENDED_BY_SENIOR
                    },
                )
            }
        }
        mediaStartedAtMs = 0
        currentSessionId = null
        currentAlertTitle = null
        _uiState.value = UiState(
            phase = Phase.ENDED,
            headline = "The call has ended.",
            detail = when {
                outcome == CallOutcome.TIMEOUT -> "It reached the ten-minute limit."
                byRemote && wasInCall -> "The other side ended the call."
                else -> ""
            },
        )
    }

    private fun finishWithMessage(headline: String, detail: String) {
        engine?.close(); engine = null
        currentSessionId = null
        _uiState.value = UiState(phase = Phase.ENDED, headline = headline, detail = detail)
    }

    // ── Gate + plumbing ──────────────────────────────────────────

    private fun newGate(context: Context): AnnouncementGate {
        val speaker = IlonaSpeaker.shared(context)
        val signals = object : AnnouncementGate.Signals {
            private val voiceJob = scope.launch {
                VoiceBus.utterances.collect { text ->
                    when {
                        CallScripts.isDecline(text) -> windowSignal.set(WindowSignal.DECLINE)
                        CallScripts.isAnswer(text) -> windowSignal.set(WindowSignal.ANSWER)
                    }
                }
            }

            override fun poll(): WindowSignal {
                val s = windowSignal.get()
                if (s != WindowSignal.NONE) voiceJob.cancel()
                return s
            }
        }
        return AnnouncementGate(
            speaker = AnnouncementGate.Speaker { line ->
                runCatching {
                    speaker.speakBlocking(line, com.app.nuri.tts.EmotionGate.Situation.SERIOUS)
                    true
                }.getOrDefault(false)
            },
            signals = signals,
            sleeper = AnnouncementGate.Sleeper { ms -> Thread.sleep(ms) },
        )
    }

    private suspend fun writeSeniorSignal(
        session: RemoteSession,
        sessionId: String,
        kind: String,
        state: String,
        sdp: String?,
        durationSec: Int = 0,
        endedBy: String? = null,
    ) {
        // Senior signals ride shadowAck/latest (§17.1). That document
        // is also the shadow ack — the call field rides alongside,
        // keyed separately, so neither clobbers the other.
        session.store.set(
            session.paths.shadowAck,
            CallSignalCodec.encode(
                CallSignal(sessionId, kind, CallMedia.VIDEO_AND_AUDIO.name, sdp, "SENIOR", state, System.currentTimeMillis(), durationSec, endedBy),
            ),
            merge = true,
        )
    }

    private suspend fun postCallEvent(
        session: RemoteSession,
        kind: String,
        alertTitle: String?,
        outcome: String,
        durationSec: Int = 0,
        endedBy: String? = null,
    ) {
        runCatching {
            session.observationRepository.postCallEvent(
                CallEventPayload(kind, alertTitle, outcome, durationSec, endedBy, todayKey(), timeText()),
            )
        }
    }
}
