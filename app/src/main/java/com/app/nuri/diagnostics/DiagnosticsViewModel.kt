package com.app.nuri.diagnostics

import androidx.lifecycle.ViewModel
import com.app.nuri.backup.BackupCrypto
import com.app.nuri.backup.DriveAppDataBackup
import com.app.nuri.backup.GmailSnapshotBackup
import com.app.nuri.backup.CaregiverMediatedRecovery
import com.app.nuri.bluetooth.BtDiagnostics
import com.app.nuri.bluetooth.BtSlotManager
import com.app.nuri.google.GoogleDiagnostics
import com.app.nuri.speech.AlwaysListenController
import com.app.nuri.speech.OfflineSafetyHandlers
import com.app.nuri.tts.NotBuiltTtsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One plain-language line on the diagnostics card. */
data class DiagnosticCheck(val name: String, val status: String, val detail: String)
data class DiagnosticsState(val checks: List<DiagnosticCheck> = emptyList(), val selfTestRan: Boolean = false)

/**
 * Diagnostics — present from day one. Phase D extension: honest checks for
 * every new pipeline. A check never claims OK for something not built or not
 * connected; the backup check runs a REAL encrypt/decrypt round-trip.
 */
class DiagnosticsViewModel : ViewModel() {
    private val alwaysListen = AlwaysListenController()
    private fun buildChecks(runBackupTest: Boolean): List<DiagnosticCheck> {
        val backupTest = if (runBackupTest) BackupCrypto.selfTest() else null
        return listOf(
            DiagnosticCheck("App", "OK", "Nuri is running (v0.3.0 — Unstuck helpers, Show Nuri, safety actions)."),
            DiagnosticCheck("Voice", "Not built yet", NotBuiltTtsEngine().statusText()),
            DiagnosticCheck("Always listen", "Not built yet", alwaysListen.diagnosticsText()),
            DiagnosticCheck("Safety check-ins", "Built — offline design", OfflineSafetyHandlers.diagnosticsText()),
            DiagnosticCheck("Encrypted backup (on this phone)", if (backupTest == null) "Not tested yet" else if (backupTest.ok) "OK" else "Needs attention",
                backupTest?.detail ?: "Tap the check button to test saving and restoring an encrypted copy."),
            DiagnosticCheck("Backup recovery", "Caregiver-mediated", CaregiverMediatedRecovery.statusText(com.app.nuri.config.NuriSettings.AccessMode.HYBRID)),
            DiagnosticCheck("Google Drive backup", "Not connected", DriveAppDataBackup().statusText()),
            DiagnosticCheck("Email snapshot backup", "Not connected", GmailSnapshotBackup().statusText()),
            DiagnosticCheck("Google account helpers", "Not connected", GoogleDiagnostics.text()),
            DiagnosticCheck("Bluetooth speakers", "Not built yet", BtDiagnostics.text(BtSlotManager())),
            DiagnosticCheck("Updates", "Not connected", "The full update machinery is built — nightly check, verified download, careful install, self-check afterwards — but no update address is set in this build yet, so it honestly reports that it cannot check. Updates never change how Nuri looks or sounds.")
        )
    }
    private val _state = MutableStateFlow(DiagnosticsState(checks = buildChecks(runBackupTest = false)))
    val state: StateFlow<DiagnosticsState> = _state.asStateFlow()
    fun runSelfTest() { _state.value = DiagnosticsState(checks = buildChecks(runBackupTest = true), selfTestRan = true) }
}
