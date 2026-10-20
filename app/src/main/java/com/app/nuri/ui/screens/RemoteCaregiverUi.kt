package com.app.nuri.ui.screens

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.app.nuri.config.NuriSettings
import com.app.nuri.remote.FirebaseProjectConfig
import com.app.nuri.remote.PairingManager
import com.app.nuri.remote.RemoteDoc
import com.app.nuri.remote.android.FirebaseInit
import com.app.nuri.remote.android.PairingFlows
import com.app.nuri.remote.android.QrBitmap
import com.app.nuri.remote.android.RemoteSession
import com.app.nuri.remote.android.RemoteSessions
import com.app.nuri.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The caregiver side of watching (design §13.2 + order Amendment 1):
 * one obvious door ("Set up watching"), plain words only, and the
 * "His phone" view + troubleshooting panel once connected. Nothing
 * in this file renders on a senior-facing surface — it lives inside
 * the caregiver dashboard.
 */

internal class RemoteCaregiverState {
    var config: FirebaseProjectConfig? by mutableStateOf(null)
    var configLoaded by mutableStateOf(false)
    var role by mutableStateOf("NONE")
    var testOn by mutableStateOf(false)
    var session: RemoteSession? by mutableStateOf(null)
    var sessionError by mutableStateOf<String?>(null)
}

@Composable
internal fun rememberRemoteCaregiverState(repository: SettingsRepository): RemoteCaregiverState {
    val state = remember { RemoteCaregiverState() }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        state.config = RemoteSessions.config(context, repository)
        state.configLoaded = true
        state.role = repository.remoteRole.first()
        state.testOn = repository.remoteTestOn.first()
        state.session = RemoteSessions.caregiverSession(context, repository)
    }
    return state
}

/** Entry point rendered by the caregiver dashboard (design §13.2). */
@Composable
fun RemoteCaregiverSection(repository: SettingsRepository) {
    val accessMode by repository.accessMode.collectAsState(initial = NuriSettings.AccessMode.INDEPENDENT)
    val state = rememberRemoteCaregiverState(repository)
    if (accessMode == NuriSettings.AccessMode.INDEPENDENT && !state.testOn) return
    if (!state.configLoaded) return

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.role == "SENIOR" && !state.testOn) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Watching is connected", style = MaterialTheme.typography.titleMedium)
                    Text("This phone shares its check-ins, medicine confirmations and safety alerts with your caregiver, the way you chose together. Nothing else leaves this phone.")
                }
            }
            return@Column
        }
        if (state.session == null) {
            SetUpWatchingDoor(repository, state)
        } else {
            HisPhonePanel(repository, state.session!!)
            TroubleshootingPanel(repository, state.session!!)
        }
    }
}

/**
 * The one door (Amendment 1): "Set up watching". Tap → a code
 * appears with ONE instruction; the other phone connects itself;
 * this phone then says "You're connected" and offers the test
 * alert. Happy path taps: this button (1) + his phone's single
 * connect button (1).
 */
@Composable
private fun SetUpWatchingDoor(repository: SettingsRepository, state: RemoteCaregiverState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var offer by remember { mutableStateOf<PairingManager.Offer?>(null) }
    var pendingSession by remember { mutableStateOf<RemoteSession?>(null) }
    var connected by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    // While an offer is showing, watch for his phone connecting.
    DisposableEffect(offer?.householdId) {
        val session = pendingSession
        val currentOffer = offer
        if (session == null || currentOffer == null) return@DisposableEffect onDispose { }
        val registration = session.store.listenDocument(session.paths.pairing) { doc ->
            val claimedBy = RemoteDoc.str(doc ?: emptyMap(), "claimedBy")
            if (claimedBy != null) {
                scope.launch {
                    runCatching { PairingFlows.caregiverDeliverKey(session) }
                    if (state.testOn) {
                        repository.setRemoteTest(currentOffer.householdId)
                    } else {
                        repository.setRemoteBinding("CAREGIVER", currentOffer.householdId)
                    }
                    connected = true
                }
            }
        }
        onDispose { registration.close() }
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Watching", style = MaterialTheme.typography.titleLarge)
            when {
                connected -> {
                    Text("You're connected", style = MaterialTheme.typography.titleMedium)
                    Text("His phone will now share check-ins, medicine confirmations and safety alerts with this phone.")
                    TestAlertButton(repository, pendingSession)
                    Text("You can close this and open Ilona again any time — watching keeps working in the background.")
                }
                offer != null -> {
                    Text("Point his phone at this code.", style = MaterialTheme.typography.titleMedium)
                    val bitmap = remember(offer!!.qrPayload) { QrBitmap.render(offer!!.qrPayload) }
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Connect code",
                            modifier = Modifier.fillMaxWidth().height(320.dp),
                        )
                    }
                    Text("Or type this code on his phone: ${offer!!.code}", style = MaterialTheme.typography.titleLarge)
                    Text("If typing, he'll also need this screen code: ${PairingManager.displayId(offer!!.householdId)}")
                    Text("The code works for ten minutes. On his phone, tap “Connect this phone” — Ilona will do the rest.")
                    OutlinedButton(onClick = { offer = null; pendingSession = null }) { Text("Cancel") }
                }
                else -> {
                    Text("See his check-ins, medicine confirmations and safety alerts on this phone, and get a note here when something needs you.")
                    if (state.config == null) {
                        Text("One thing first: watching needs the free project details pasted in once. They're in “Family & help setup” below — paste them there, then come back here.")
                    }
                    Button(
                        enabled = state.config != null && !working,
                        onClick = {
                            working = true
                            scope.launch {
                                val config = state.config ?: return@launch
                                val mode = repository.accessMode.first()
                                val created = PairingFlows.caregiverCreateHousehold(
                                    context, config,
                                    mode = if (mode == NuriSettings.AccessMode.MANAGED) {
                                        PairingManager.MODE_MANAGED
                                    } else {
                                        PairingManager.MODE_HYBRID
                                    },
                                    test = state.testOn,
                                )
                                if (created == null) {
                                    note = "Couldn't start — check this phone's internet and try again."
                                    working = false
                                    return@launch
                                }
                                val (hid, session) = created
                                pendingSession = session
                                offer = PairingFlows.caregiverCreateOffer(
                                    session,
                                    mode = if (mode == NuriSettings.AccessMode.MANAGED) {
                                        PairingManager.MODE_MANAGED
                                    } else {
                                        PairingManager.MODE_HYBRID
                                    },
                                    test = state.testOn,
                                )
                                working = false
                            }
                        },
                    ) { Text("Set up watching", style = MaterialTheme.typography.labelLarge) }
                    note?.let { Text(it) }
                    state.sessionError?.let { Text(it) }
                }
            }
        }
    }
}

/** Asks HIS phone to send a real test alert through the whole path (design §11.2's mechanism, two-phone form). */
@Composable
internal fun TestAlertButton(repository: SettingsRepository, session: RemoteSession?) {
    val scope = rememberCoroutineScope()
    var sentAt by remember { mutableStateOf(0L) }
    Button(
        enabled = session != null,
        onClick = {
            val s = session ?: return@Button
            scope.launch {
                runCatching {
                    s.store.update(s.paths.household, mapOf("testAlertAt" to System.currentTimeMillis()))
                    sentAt = System.currentTimeMillis()
                }
            }
        },
    ) { Text("Send a test alert", style = MaterialTheme.typography.labelLarge) }
    if (sentAt > 0) {
        Text("Sent. A test alert should arrive here within a few seconds — it will say clearly that it is only a test.")
    }
}

/**
 * The "His phone" view (design §13.2): freshness first (§7's
 * honesty lines), then the same summary cards the dashboard uses,
 * fed from the decrypted remote records — plus alert acknowledge
 * and the "Call him" door (Amendment 2).
 */
@Composable
private fun HisPhonePanel(repository: SettingsRepository, session: RemoteSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<com.app.nuri.remote.RemoteStatus?>(null) }
    var events by remember { mutableStateOf<List<com.app.nuri.remote.RemoteEvent>>(emptyList()) }
    var alerts by remember { mutableStateOf<List<com.app.nuri.remote.RemoteAlert>>(emptyList()) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val remote = remember(session) { session.caregiverRepository }
    DisposableEffect(session) {
        val c1 = remote.observeStatus { status = it; nowMs = System.currentTimeMillis() }
        val c2 = remote.observeEvents { events = it }
        val c3 = remote.observeAlerts { alerts = it }
        onDispose { c1.close(); c2.close(); c3.close() }
    }
    val freshness = com.app.nuri.remote.RemoteFreshness.lines(status, nowMs)
    val today = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) }
    val accessMode by repository.accessMode.collectAsState(initial = NuriSettings.AccessMode.HYBRID)
    val tier by repository.tier.collectAsState(initial = com.app.nuri.config.NuriSettings.DEFAULT_TIER)
    val data = remember(status, events, alerts, accessMode) {
        com.app.nuri.remote.RemoteDashboardRepository(status, events, alerts, today)
            .dataFor(tier, if (accessMode == NuriSettings.AccessMode.INDEPENDENT) NuriSettings.AccessMode.HYBRID else accessMode)
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("His phone", style = MaterialTheme.typography.titleLarge)
            Text(freshness.lastUpdatedLine, style = MaterialTheme.typography.titleMedium)
            Text(freshness.lastSeenLine)
            freshness.noWordLine?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
                com.app.nuri.remote.RemoteFreshness.NO_WORD_STEPS.forEach { step -> Text("• $step") }
            }
            data.dailySummary?.let { Text(it) }
            data.weeklySummary?.let { Text(it) }
            if (alerts.isNotEmpty()) {
                Text("Alerts from his phone", style = MaterialTheme.typography.titleMedium)
                alerts.takeLast(5).reversed().forEach { alert ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(alert.payload.title, style = MaterialTheme.typography.titleMedium)
                        Text(alert.payload.detail)
                        alert.payload.locationText?.let { Text("Where: $it") }
                        Text("${alert.payload.dayKey} · ${alert.payload.timeText}")
                        if (alert.ackByCaregiver) {
                            Text("You've seen this.")
                        } else {
                            Button(onClick = {
                                scope.launch { runCatching { remote.acknowledgeAlert(alert.id) } }
                            }) { Text("Got it", style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            CallHimButton(session)
        }
    }
}

/** "Call him" (design §17.2) + the caregiver's in-call surface state. */
@Composable
private fun CallHimButton(session: RemoteSession) {
    val context = LocalContext.current
    val callState by com.app.nuri.call.CallCoordinator.uiState.collectAsState()
    val phase = callState.phase
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (phase) {
            com.app.nuri.call.CallCoordinator.Phase.IDLE, com.app.nuri.call.CallCoordinator.Phase.ENDED -> {
                Button(onClick = { com.app.nuri.call.CallCoordinator.caregiverCallHim(context) }) {
                    Text("Call him", style = MaterialTheme.typography.labelLarge)
                }
                if (phase == com.app.nuri.call.CallCoordinator.Phase.ENDED) {
                    Text(callState.headline)
                    if (callState.detail.isNotBlank()) Text(callState.detail)
                }
                Text("Ilona will announce the call on his phone first, and name you. Nothing opens on his phone unless he answers.")
            }
            com.app.nuri.call.CallCoordinator.Phase.RINGING_CHECKIN -> {
                Text(callState.headline, style = MaterialTheme.typography.titleMedium)
                Text(callState.detail)
                Button(onClick = { com.app.nuri.call.CallCoordinator.uiAnswer() }) {
                    Text("Answer", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(onClick = { com.app.nuri.call.CallCoordinator.uiDecline() }) { Text("Not now") }
            }
            com.app.nuri.call.CallCoordinator.Phase.WAITING_CAREGIVER -> {
                Text(callState.headline, style = MaterialTheme.typography.titleMedium)
                Text(callState.detail)
                OutlinedButton(onClick = { com.app.nuri.call.CallCoordinator.uiEnd(context) }) { Text("Stop calling") }
            }
            com.app.nuri.call.CallCoordinator.Phase.CONNECTING, com.app.nuri.call.CallCoordinator.Phase.IN_CALL -> {
                Text(callState.headline, style = MaterialTheme.typography.titleMedium)
                Text(callState.detail)
                CaregiverVideoViews()
                if (callState.phase == com.app.nuri.call.CallCoordinator.Phase.IN_CALL) {
                    OutlinedButton(onClick = { com.app.nuri.call.CallCoordinator.uiToggleVideo() }) {
                        Text(if (callState.videoOn) "Turn my camera off" else "Turn my camera on")
                    }
                }
                Button(onClick = { com.app.nuri.call.CallCoordinator.uiEnd(context) }) {
                    Text("End call", style = MaterialTheme.typography.labelLarge)
                }
            }
            else -> {
                Text(callState.headline, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Video surfaces for the caregiver side of a call (design §17.5). */
@Composable
private fun CaregiverVideoViews() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("You can see each other here while you talk.")
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                val remote = org.webrtc.SurfaceViewRenderer(ctx)
                val local = org.webrtc.SurfaceViewRenderer(ctx)
                val container = android.widget.FrameLayout(ctx)
                container.addView(
                    remote,
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT, 640,
                    ),
                )
                val localParams = android.widget.FrameLayout.LayoutParams(240, 320).apply {
                    gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
                }
                container.addView(local, localParams)
                com.app.nuri.call.CallCoordinator.attachViews(local, remote)
                container
            },
            modifier = Modifier.fillMaxWidth().height(360.dp),
        )
    }
}

/** Launches the system code scanner; the OS handles the camera and returns the text. */
internal fun scanCode(context: Context, onResult: (String?) -> Unit) {
    val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
        .enableAutoZoom()
        .build()
    val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options)
    scanner.startScan()
        .addOnSuccessListener { barcode -> onResult(barcode.rawValue) }
        .addOnCanceledListener { onResult(null) }
        .addOnFailureListener { onResult(null) }
}

/**
 * The troubleshooting panel (order §B1) — caregiver surface only.
 * Every line here is a fact about the link, in plain words; the
 * only actions are restart, test, and the replace-a-phone flows.
 */
@Composable
private fun TroubleshootingPanel(repository: SettingsRepository, session: RemoteSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lastSync by repository.remoteLastSyncAt.collectAsState(initial = 0L)
    val threshold by repository.remoteNoAnswerThreshold.collectAsState(initial = 2)
    val checkinHour by repository.remoteCheckinHour.collectAsState(initial = 10)
    var status by remember { mutableStateOf<com.app.nuri.remote.RemoteStatus?>(null) }
    var watchRunning by remember { mutableStateOf(com.app.nuri.remote.android.WatchState.running) }
    val remote = remember(session) { session.caregiverRepository }
    DisposableEffect(session) {
        val c = remote.observeStatus { status = it }
        onDispose { c.close() }
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Troubleshooting", style = MaterialTheme.typography.titleLarge)

            Text("Is the link working?", style = MaterialTheme.typography.titleMedium)
            Text(
                if (lastSync > 0) {
                    "Last heard from his phone: ${com.app.nuri.remote.RemoteFreshness.timeText(lastSync)}"
                } else {
                    "Nothing has arrived from his phone yet."
                },
            )
            Text(com.app.nuri.remote.HisPhoneUpdate.line(status))
            com.app.nuri.remote.HisPhoneUpdate.detail(status)?.let { Text(it) }

            Text("Watching on this phone", style = MaterialTheme.typography.titleMedium)
            Text(
                if (watchRunning) {
                    "Ilona is watching for new alerts on this phone."
                } else {
                    "Ilona is NOT watching right now — alerts will still be here when you open Ilona, but you won't get a note the moment they happen."
                },
            )
            Button(onClick = {
                com.app.nuri.remote.android.CaregiverWatchService.restart(context)
                watchRunning = true
            }) { Text(if (watchRunning) "Restart watching" else "Start watching", style = MaterialTheme.typography.labelLarge) }

            Text("Send a test alert", style = MaterialTheme.typography.titleMedium)
            Text("Asks his phone to send a test alert through the whole path, so you can see it arrive.")
            TestAlertButton(repository, session)

            Text("Calls", style = MaterialTheme.typography.titleMedium)
            Text("If he doesn't answer a check-in call, Ilona tells you here. After a few unanswered calls close together, Ilona raises a welfare note with the human next steps — it never calls emergency services on its own.")
            Text("Unanswered calls before the welfare note: $threshold")
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { repository.setRemoteNoAnswerThreshold(threshold - 1) } }) { Text("Fewer") }
                OutlinedButton(onClick = { scope.launch { repository.setRemoteNoAnswerThreshold(threshold + 1) } }) { Text("More") }
            }
            Text("“No morning check-in” is worked out at $checkinHour o'clock in the morning.")
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { repository.setRemoteCheckinHour((checkinHour - 1).coerceIn(6, 12)) } }) { Text("Earlier") }
                OutlinedButton(onClick = { scope.launch { repository.setRemoteCheckinHour((checkinHour + 1).coerceIn(6, 12)) } }) { Text("Later") }
            }
            CallMediaSetting(session)

            Text("Replace a phone", style = MaterialTheme.typography.titleMedium)
            ReplaceHisPhone(session)
            AddMyNewPhone(repository, session)
            ThisIsMyNewPhone(repository)

            Text("Connection test code", style = MaterialTheme.typography.titleMedium)
            val fcmToken by repository.remoteFcmToken.collectAsState(initial = "")
            Text(
                if (fcmToken.isBlank()) {
                    "No test code yet — it appears after Ilona has run once with an internet connection."
                } else {
                    "If you ever need to prove messages can reach this phone, this is the code the test message is sent to:"
                },
            )
            if (fcmToken.isNotBlank()) Text(fcmToken)
        }
    }
}

/** Per-household call media default (design §17.5): video+voice, or voice only. His own choice always wins over this. */
@Composable
private fun CallMediaSetting(session: RemoteSession) {
    val scope = rememberCoroutineScope()
    var voiceOnly by remember { mutableStateOf(false) }
    DisposableEffect(session) {
        val c = session.store.listenDocument(session.paths.household) { doc ->
            voiceOnly = RemoteDoc.str(doc ?: emptyMap(), "callMedia") == "VOICE"
        }
        onDispose { c.close() }
    }
    Text(if (voiceOnly) "Calls open with voice only." else "Calls open with video and voice.")
    OutlinedButton(onClick = {
        val next = !voiceOnly
        voiceOnly = next
        scope.launch {
            runCatching {
                session.store.update(session.paths.household, mapOf("callMedia" to if (next) "VOICE" else "VIDEO"))
            }
        }
    }) { Text(if (voiceOnly) "Use video and voice" else "Use voice only") }
    Text("His own choice on his phone always comes first — this only sets the starting point.")
}

/** Replace HIS phone (§4.3/Amendment 1): same connect shape — a code, his new phone taps once. */
@Composable
private fun ReplaceHisPhone(session: RemoteSession) {
    val scope = rememberCoroutineScope()
    var offer by remember { mutableStateOf<PairingManager.Offer?>(null) }
    var confirm by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Replace his phone", style = MaterialTheme.typography.titleMedium)
        when {
            offer != null -> {
                Text("Point his new phone at this code.", style = MaterialTheme.typography.titleMedium)
                val bitmap = remember(offer!!.qrPayload) { QrBitmap.render(offer!!.qrPayload) }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(), contentDescription = "Connect code",
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                    )
                }
                Text("Or type this code on his new phone: ${offer!!.code}", style = MaterialTheme.typography.titleLarge)
                Text("If typing, he'll also need this screen code: ${PairingManager.displayId(offer!!.householdId)}")
                Text("His old phone stops sharing as soon as the new one connects.")
            }
            confirm -> {
                Text("His old phone will stop sharing the moment the new one connects. His check-ins and settings on the old phone stay on that phone.")
                Button(onClick = {
                    scope.launch {
                        runCatching {
                            val pm = PairingManager(session.store)
                            pm.beginSeniorReplacement(session.paths.householdId)
                            val doc = session.store.get(session.paths.household)
                            val mode = RemoteDoc.str(doc ?: emptyMap(), "mode") ?: PairingManager.MODE_HYBRID
                            val test = RemoteDoc.bool(doc ?: emptyMap(), "test") ?: false
                            offer = pm.createOffer(session.paths.householdId, session.uid, mode, test)
                        }
                        confirm = false
                    }
                }) { Text("Yes, replace his phone", style = MaterialTheme.typography.labelLarge) }
                OutlinedButton(onClick = { confirm = false }) { Text("Not now") }
            }
            else -> {
                Text("He got a new phone? This shows a code; on the new phone he taps “Connect this phone” once.")
                OutlinedButton(onClick = { confirm = true }) { Text("Replace his phone") }
            }
        }
    }
}

/** This phone is the OLD caregiver phone: take in the new phone's code, hand over watching (§4.3). */
@Composable
private fun AddMyNewPhone(repository: SettingsRepository, session: RemoteSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showHandoffCode by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Add my new phone", style = MaterialTheme.typography.titleMedium)
        if (showHandoffCode) {
            Text("Now point your new phone at this code.", style = MaterialTheme.typography.titleMedium)
            val payload = "ilona-household:v1:${session.paths.householdId}"
            val bitmap = remember(payload) { QrBitmap.render(payload) }
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(), contentDescription = "Handoff code",
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                )
            }
            Text("Your new phone will say “You're connected” when it's done.")
        } else {
            Text("On your new phone, open “This is my new phone” — it shows a code. Point this phone at it.")
            OutlinedButton(onClick = {
                scanCode(context) { scanned ->
                    val parts = scanned?.split(":") ?: return@scanCode
                    // ilona-newcaregiver:v1:{uid}:{algo}:{pubkeyB64}
                    if (parts.size < 5 || parts[0] != "ilona-newcaregiver") {
                        note = "That code didn't look right. Try again."
                        return@scanCode
                    }
                    val newUid = parts[2]
                    val algo = parts[3]
                    val pub = parts.subList(4, parts.size).joinToString(":")
                    scope.launch {
                        runCatching {
                            val doc = session.store.get(session.paths.household) ?: return@runCatching
                            @Suppress("UNCHECKED_CAST")
                            val current = (doc["caregiverUids"] as? List<String>) ?: listOf(session.uid)
                            session.store.update(
                                session.paths.household,
                                mapOf("caregiverUids" to (current + newUid).distinct()),
                            )
                            val hk = session.keyStore.householdKey()
                            if (hk != null) {
                                val recipient = com.app.nuri.remote.HouseholdCrypto.DeviceKeyRecord(algo, pub, "")
                                val blobJson = com.app.nuri.remote.HouseholdCrypto.wrapHouseholdKey(hk, recipient)
                                @Suppress("UNCHECKED_CAST")
                                val wraps = (doc["keyWraps"] as? Map<String, Any?>) ?: emptyMap()
                                session.store.update(
                                    session.paths.household,
                                    mapOf("keyWraps" to wraps + (newUid to blobJson)),
                                )
                            }
                            showHandoffCode = true
                        }.onFailure { note = "Something went wrong — check the internet and try again." }
                    }
                }
            }) { Text("Point this phone at my new phone's code") }
        }
        note?.let { Text(it) }
    }
}

/** This phone is the NEW caregiver phone (§4.3): show my code, then take in the old phone's code. */
@Composable
private fun ThisIsMyNewPhone(repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var myUid by remember { mutableStateOf<String?>(null) }
    var myCodePayload by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        scope.launch {
            val config = RemoteSessions.config(context, repository) ?: return@launch
            val probe = FirebaseInit.session(context, config, null, "pending", "CAREGIVER", test = false)
                ?: return@launch
            val keyStore = com.app.nuri.remote.android.HouseholdKeyStore(context, "pending-caregiver")
            val deviceKey = keyStore.deviceKey()
            myUid = probe.uid
            myCodePayload = "ilona-newcaregiver:v1:${probe.uid}:${deviceKey.algorithm}:${deviceKey.publicKeyB64}"
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("This is my new phone", style = MaterialTheme.typography.titleMedium)
        when {
            done -> Text("You're connected. This phone can watch now, just like your old one.")
            myCodePayload != null -> {
                Text("On your OLD phone, tap “Add my new phone” and point it at this code.", style = MaterialTheme.typography.titleMedium)
                val bitmap = remember(myCodePayload) { QrBitmap.render(myCodePayload!!) }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(), contentDescription = "My code",
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                    )
                }
                Text("Then, when your old phone shows its own code, tap here and point this phone at it.")
                Button(onClick = {
                    scanCode(context) { scanned ->
                        val parts = scanned?.split(":") ?: return@scanCode
                        if (parts.size != 3 || parts[0] != "ilona-household") {
                            note = "That code didn't look right. Try again."
                            return@scanCode
                        }
                        val hid = parts[2]
                        scope.launch {
                            runCatching {
                                val pending = com.app.nuri.remote.android.HouseholdKeyStore(context, "pending-caregiver")
                                val record = pending.deviceKey()
                                val realStore = com.app.nuri.remote.android.HouseholdKeyStore(
                                    context,
                                    com.app.nuri.remote.android.HouseholdKeyStore.tagFor(hid, "CAREGIVER", false),
                                )
                                realStore.adoptDeviceKey(record)
                                repository.setRemoteBinding("CAREGIVER", hid)
                                // Pick up the wrapped key the old phone left.
                                val config = RemoteSessions.config(context, repository)
                                val session = config?.let {
                                    FirebaseInit.session(context, it, null, hid, "CAREGIVER", test = false)
                                }
                                if (session != null) {
                                    PairingFlows.seniorTryReceiveKey(session)
                                }
                                done = true
                            }.onFailure { note = "Something went wrong — check the internet and try again." }
                        }
                    }
                }) { Text("My old phone is showing its code", style = MaterialTheme.typography.labelLarge) }
            }
            else -> Text("Getting this phone ready…")
        }
        note?.let { Text(it) }
    }
}
