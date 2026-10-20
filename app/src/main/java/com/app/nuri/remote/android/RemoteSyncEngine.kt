package com.app.nuri.remote.android

import android.content.Context
import com.app.nuri.BuildConfig
import com.app.nuri.call.CallCoordinator
import com.app.nuri.call.CallRequest
import com.app.nuri.config.FlavorHooks
import com.app.nuri.config.NuriSettings
import com.app.nuri.remote.FirebaseProjectConfig
import com.app.nuri.remote.RemoteDoc
import com.app.nuri.remote.RemoteShadowBridge
import com.app.nuri.remote.RemoteStatus
import com.app.nuri.remote.ShadowAckPayload
import com.app.nuri.remote.ShadowPayload
import com.app.nuri.remote.StatusPayload
import com.app.nuri.remote.MedEventPayload
import com.app.nuri.repository.SettingsRepository
import com.app.nuri.reports.android.CrashReports
import com.app.nuri.update.android.UpdateRuntimeStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Senior-side sync (design §7/§13.1): while a senior binding exists
 * and the mode shares (Hybrid/Managed), local record flows are
 * mirrored — encrypted — to the household, the status/today
 * document is kept fresh, incoming shadow documents are applied
 * through the flavor bridge, and call requests / alert triggers
 * reach the call coordinator.
 *
 * Independent mode never constructs this engine: the binding
 * cannot exist there (pairing sets a sharing mode), and the
 * engine re-checks the mode before every post as a second lock.
 *
 * Posted-record bookkeeping lives in a private SharedPreferences
 * set; Firestore's offline queue plus deterministic event ids
 * make every post idempotent, so a lost bookkeeping write can
 * only ever re-post, never duplicate (§13.1).
 */
class RemoteSyncEngine(
    private val context: Context,
    private val repository: SettingsRepository,
    private val session: RemoteSession,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val posted = appContext.getSharedPreferences("remote_posted", Context.MODE_PRIVATE)

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun timeText(): String = SimpleDateFormat("h:mm a", Locale.US).format(Date())

    private suspend fun sharingMode(): Boolean {
        val mode = repository.accessMode.first()
        return mode == NuriSettings.AccessMode.HYBRID || mode == NuriSettings.AccessMode.MANAGED
    }

    fun start() {
        scope.launch { runCatching { postRecordDeltas() } }
        scope.launch { runCatching { postStatusLoop() } }
        scope.launch { runCatching { watchHouseholdDoc() } }
        startShadowWatch()
        CallCoordinator.attachSeniorSession(appContext, session)
    }

    // ── record deltas ────────────────────────────────────────────

    private suspend fun postRecordDeltas() {
        val repo = session.observationRepository
        scope.launch {
            repository.checkIns.collectLatest { records ->
                if (!sharingMode()) return@collectLatest
                for (record in records) {
                    val id = "ci:${record.dayKey}:${record.timeText}"
                    if (!posted.getBoolean(id, false)) {
                        runCatching { repo.postCheckIn(record) }
                        posted.edit().putBoolean(id, true).apply()
                    }
                }
            }
        }
        scope.launch {
            repository.medConfirmations.collectLatest { records ->
                if (!sharingMode()) return@collectLatest
                for (record in records) {
                    val id = "med:${record.dayKey}:${record.timeText}:${record.entryName}"
                    if (!posted.getBoolean(id, false)) {
                        runCatching { repo.postMedConfirmation(record) }
                        posted.edit().putBoolean(id, true).apply()
                    }
                }
            }
        }
        scope.launch {
            repository.diary.collectLatest { records ->
                if (!sharingMode()) return@collectLatest
                for (entry in records) {
                    val id = "diary:${entry.dayKey}:${entry.timeText}:${entry.what.hashCode()}"
                    if (!posted.getBoolean(id, false)) {
                        runCatching { repo.postDiaryEntry(entry) }
                        posted.edit().putBoolean(id, true).apply()
                    }
                }
            }
        }
        scope.launch {
            repository.safetyAlerts.collectLatest { records ->
                if (!sharingMode()) return@collectLatest
                for (alert in records) {
                    val id = "alert:${alert.dayKey}:${alert.timeText}:${alert.title.hashCode()}"
                    if (!posted.getBoolean(id, false)) {
                        runCatching { repo.postSafetyAlert(alert) }
                        posted.edit().putBoolean(id, true).apply()
                        // Alert follow-through (design §17.3): the same
                        // record stream drives the call orchestrator.
                        CallCoordinator.onSafetyAlert(appContext, alert.title)
                    }
                }
            }
        }
    }

    // ── status/today ─────────────────────────────────────────────

    private suspend fun postStatusLoop() {
        val repo = session.observationRepository
        combine(
            repository.tier, repository.emotionLevel, repository.fontStep,
            repository.emergencyContactName, repository.emergencyContactNumber,
            repository.medSchedule, repository.personToggles,
            repository.checkIns, repository.medConfirmations, repository.diary,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            StatusInputs(
                tier = values[0] as NuriSettings.Tier,
                emotion = values[1] as NuriSettings.EmotionLevel,
                fontStep = (values[2] as Enum<*>).name,
                emergencyName = values[3] as String,
                emergencyNumber = values[4] as String,
                medSchedule = "",
                gamesAllowed = ((values[6] as com.app.nuri.tier.PersonToggles).gamesAllowed ?: true),
                checkIns = values[7] as List<com.app.nuri.unstuck.CheckInRecord>,
                meds = values[8] as List<com.app.nuri.unstuck.MedConfirmation>,
                diary = values[9] as List<com.app.nuri.unstuck.DiaryEntry>,
            )
        }.collectLatest { input ->
            if (!sharingMode()) return@collectLatest
            runCatching { repo.postStatus(buildStatus(input)) }
        }
    }

    private data class StatusInputs(
        val tier: NuriSettings.Tier,
        val emotion: NuriSettings.EmotionLevel,
        val fontStep: String,
        val emergencyName: String,
        val emergencyNumber: String,
        val medSchedule: String,
        val gamesAllowed: Boolean,
        val checkIns: List<com.app.nuri.unstuck.CheckInRecord>,
        val meds: List<com.app.nuri.unstuck.MedConfirmation>,
        val diary: List<com.app.nuri.unstuck.DiaryEntry>,
    )

    private fun buildStatus(input: StatusInputs): RemoteStatus {
        val today = todayKey()
        val seen = linkedMapOf(
            com.app.nuri.remote.RemoteSettingsKeys.TIER to input.tier.name,
            com.app.nuri.remote.RemoteSettingsKeys.EMOTION to input.emotion.name,
            com.app.nuri.remote.RemoteSettingsKeys.FONT to input.fontStep,
            com.app.nuri.remote.RemoteSettingsKeys.EMERGENCY_NAME to input.emergencyName,
            com.app.nuri.remote.RemoteSettingsKeys.EMERGENCY_NUMBER to input.emergencyNumber,
            com.app.nuri.remote.RemoteSettingsKeys.GAMES to input.gamesAllowed.toString(),
        )
        val effective = linkedMapOf(
            com.app.nuri.remote.RemoteSettingsKeys.TIER to
                FlavorHooks.effectiveTierName(appContext, input.tier.name),
            com.app.nuri.remote.RemoteSettingsKeys.EMOTION to input.emotion.name,
            com.app.nuri.remote.RemoteSettingsKeys.FONT to input.fontStep,
            com.app.nuri.remote.RemoteSettingsKeys.EMERGENCY_NAME to
                FlavorHooks.effectiveEmergencyName(appContext, input.emergencyName),
            com.app.nuri.remote.RemoteSettingsKeys.EMERGENCY_NUMBER to
                FlavorHooks.effectiveEmergencyNumber(appContext, input.emergencyNumber),
            com.app.nuri.remote.RemoteSettingsKeys.GAMES to input.gamesAllowed.toString(),
        )
        val payload = StatusPayload(
            checkIn = input.checkIns.lastOrNull()?.let {
                com.app.nuri.remote.CheckInPayload(it.dayKey, it.timeText, it.sleep, it.mood, it.appetite)
            },
            medsToday = input.meds.filter { it.dayKey == today }.map {
                MedEventPayload(it.dayKey, it.timeText, it.entryName, taken = true)
            },
            diaryLatest = input.diary.lastOrNull()?.let { "${it.what} — ${it.outcome}" },
            seenSettings = seen,
            effectiveSettings = effective,
            summaryLine = null,
        )
        return RemoteStatus(
            dayKey = today,
            updatedAtMs = null,
            lastSeenAtMs = null,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            lastUpdateCheckText = UpdateRuntimeStore(appContext).lastRunResult() ?: "",
            payload = payload,
        )
    }

    // ── household document: key receipt, call requests, call signals ──

    private suspend fun watchHouseholdDoc() {
        session.store.listenDocument(session.paths.household) { doc ->
            if (doc == null) return@listenDocument
            // Household key wrap may have landed.
            scope.launch { runCatching { PairingFlows.seniorTryReceiveKey(session) } }
            // Check-in call request (design §17.2).
            CallRequest.fromHouseholdDoc(doc)?.let { request ->
                if (!request.isStale(System.currentTimeMillis())) {
                    CallCoordinator.onCheckInRequest(appContext, request.id)
                }
            }
            // Caregiver's answer/end signals for senior-initiated calls.
            com.app.nuri.call.CallSignalCodec.decode(doc)?.let { signal ->
                CallCoordinator.onRemoteSignal(appContext, signal)
            }
            // Test alert request (design §11.2): the caregiver asks,
            // THIS phone posts a genuine test alert through the real
            // path — the only honest end-to-end proof there is.
            val testAt = RemoteDoc.long(doc, "testAlertAt") ?: 0L
            val seenTestAt = posted.getLong("test_alert_at", 0L)
            if (testAt > seenTestAt) {
                posted.edit().putLong("test_alert_at", testAt).apply()
                scope.launch {
                    if (!sharingMode()) return@launch
                    runCatching {
                        session.observationRepository.postSafetyAlert(
                            com.app.nuri.unstuck.SafetyAlert(
                                todayKey(), timeText(),
                                "Test alert",
                                "This is only a test — your caregiver asked Ilona to send a test alert. Nothing is wrong.",
                            ),
                        )
                    }
                }
            }
        }
        // The senior signals ride shadowAck; the caregiver watches
        // that side. Nothing senior-side to read back.
    }

    // ── shadow ───────────────────────────────────────────────────

    private fun startShadowWatch() {
        val repo = session.observationRepository
        repo.observeShadow { version, payload ->
            val handler = RemoteShadowBridge.handler ?: return@observeShadow
            if (version == null || payload == null) return@observeShadow
            scope.launch {
                if (!sharingMode()) return@launch
                runCatching {
                    handler.applyRemoteShadow(payload.values)
                    repo.postShadowAck(
                        ShadowAckPayload(
                            appliedVersion = version,
                            shadowValues = handler.currentShadowValues(),
                            effectiveSettings = latestEffectiveSettings(),
                        ),
                    )
                }.onFailure {
                    CrashReports.recordProblem("Remote shadow", "Could not be applied: ${it.message}")
                }
            }
        }
    }

    private suspend fun latestEffectiveSettings(): Map<String, String> {
        val tier = repository.tier.first()
        val emotion = repository.emotionLevel.first()
        val font = repository.fontStep.first()
        val emName = repository.emergencyContactName.first()
        val emNumber = repository.emergencyContactNumber.first()
        val games = repository.personToggles.first().gamesAllowed ?: true
        return linkedMapOf(
            com.app.nuri.remote.RemoteSettingsKeys.TIER to FlavorHooks.effectiveTierName(appContext, tier.name),
            com.app.nuri.remote.RemoteSettingsKeys.EMOTION to emotion.name,
            com.app.nuri.remote.RemoteSettingsKeys.FONT to font.name,
            com.app.nuri.remote.RemoteSettingsKeys.EMERGENCY_NAME to FlavorHooks.effectiveEmergencyName(appContext, emName),
            com.app.nuri.remote.RemoteSettingsKeys.EMERGENCY_NUMBER to FlavorHooks.effectiveEmergencyNumber(appContext, emNumber),
            com.app.nuri.remote.RemoteSettingsKeys.GAMES to games.toString(),
        )
    }
}
