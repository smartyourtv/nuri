package com.app.nuri.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.app.nuri.config.NuriSettings
import com.app.nuri.remote.FirebaseProjectConfig
import com.app.nuri.remote.PairingManager
import com.app.nuri.remote.android.FirebaseInit
import com.app.nuri.remote.android.PairingFlows
import com.app.nuri.remote.android.RemoteSessions
import com.app.nuri.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The senior phone's side of connecting (Amendment 1): ONE button
 * — "Connect this phone" — a scan, and done. The senior does
 * nothing else, ever: no codes to read aloud, no settings to
 * change, and success is a single quiet line. The same block is
 * used in Family & help setup and inside First Install.
 */
@Composable
fun SeniorConnectBlock(repository: SettingsRepository, onConnected: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val role by repository.remoteRole.collectAsState(initial = "NONE")
    val testOn by repository.remoteTestOn.collectAsState(initial = false)
    var working by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var showCodeEntry by remember { mutableStateOf(false) }
    var typedCode by remember { mutableStateOf("") }
    var typedHousehold by remember { mutableStateOf("") }

    fun claim(secret: String, householdId: String) {
        working = true
        scope.launch {
            val config = RemoteSessions.config(context, repository)
            if (config == null) {
                note = "Watching isn't ready on this phone yet — the one-time setup details are missing."
                working = false
                return@launch
            }
            val (result, _) = PairingFlows.seniorClaim(context, config, householdId, secret, test = testOn)
            working = false
            when (result) {
                is PairingManager.ClaimResult.Claimed -> {
                    if (testOn) {
                        repository.setRemoteTest(householdId)
                    } else {
                        repository.setRemoteBinding("SENIOR", householdId)
                        repository.setAccessMode(
                            if (result.mode == PairingManager.MODE_MANAGED) {
                                NuriSettings.AccessMode.MANAGED
                            } else {
                                NuriSettings.AccessMode.HYBRID
                            },
                        )
                    }
                    note = "Connected — you're all set. You don't need to do anything else."
                    onConnected()
                }
                PairingManager.ClaimResult.Expired ->
                    note = "That code ran out of time. Ask them to show you a fresh one, then tap the button again."
                PairingManager.ClaimResult.AlreadyClaimed ->
                    note = "That code was already used. Ask them to show you a fresh one."
                PairingManager.ClaimResult.WrongSecret ->
                    note = "That code didn't match. Check the numbers and try again."
                PairingManager.ClaimResult.NoOffer ->
                    note = "Couldn't connect — check the internet on both phones and try again."
            }
        }
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Watching", style = MaterialTheme.typography.titleLarge)
            if (role == "SENIOR" || testOn) {
                Text("This phone is connected. It shares check-ins, medicine confirmations and safety alerts with your caregiver — nothing else, ever.")
            } else {
                Text("If someone who helps you wants to see your check-ins and safety alerts on their phone, connect this phone once. You tap one button — Ilona does the rest.")
                Button(
                    enabled = !working,
                    onClick = {
                        scanCode(context) { scanned ->
                            val decoded = scanned?.let { PairingManager.decodeQrPayload(it) }
                            if (decoded != null) {
                                claim(decoded.second, decoded.first)
                            } else if (scanned != null) {
                                note = "That wasn't the connect code. Try again, or use the numbers instead."
                            }
                        }
                    },
                ) { Text("Connect this phone", style = MaterialTheme.typography.labelLarge) }
                OutlinedButton(onClick = { showCodeEntry = !showCodeEntry }) {
                    Text("Use the numbers instead")
                }
                if (showCodeEntry) {
                    Text("Type the 8 numbers they show you, and the screen code under them.")
                    OutlinedTextField(
                        value = typedCode,
                        onValueChange = { typedCode = it.filter { c -> c.isDigit() }.take(8) },
                        label = { Text("The 8 numbers") },
                    )
                    OutlinedTextField(
                        value = typedHousehold,
                        onValueChange = { typedHousehold = it },
                        label = { Text("The screen code (letters and numbers)") },
                    )
                    Button(
                        enabled = typedCode.length == 8 && typedHousehold.isNotBlank() && !working,
                        onClick = { claim(typedCode, PairingManager.normalizeId(typedHousehold)) },
                    ) { Text("Connect", style = MaterialTheme.typography.labelLarge) }
                }
                note?.let { Text(it) }
            }
        }
    }
}

/**
 * Public flavor's one-time project setup (design §10.2): paste the
 * free Firebase project's details once; validated on the spot.
 * Renders only when no bundled config exists (i.e. the public
 * flavor) — the private flavor never shows it.
 */
@Composable
fun WatchProjectSetupBlock(repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bundled = remember(context) { FirebaseInit.bundledConfig(context) }
    if (bundled != null) return // private flavor: config is built in
    val stored by repository.remoteConfigJson.collectAsState(initial = "")
    var pasted by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Watch from my phone — one-time setup", style = MaterialTheme.typography.titleLarge)
            if (stored.isNotBlank()) {
                Text("The project details are saved on this phone. Watching can be set up from the Family summary.")
                OutlinedButton(onClick = {
                    scope.launch { repository.setRemoteConfigJson("") }
                }) { Text("Remove the saved details") }
            } else {
                Text(
                    "Watching uses your own free Google Firebase project — your family's information goes to your project, nobody else's. " +
                        "Create the free project (the steps are in the guide), open its settings, copy the “firebaseConfig” block, and paste it here. " +
                        "This is a one-time thing, on the caregiver's phone and on theirs.",
                )
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = { Text("Paste the project details here") },
                    minLines = 4,
                )
                Button(onClick = {
                    val config = FirebaseProjectConfig.fromPastedConfig(pasted)
                    if (config == null) {
                        note = "That doesn't look right yet — it should include the project name, the app code, and the numbers. Copy the whole block and try again."
                    } else {
                        scope.launch {
                            repository.setRemoteConfigJson(FirebaseInit.encodeStoredConfig(config))
                            note = "That looks right — saved. Now open the Family summary and tap “Set up watching”."
                        }
                    }
                }) { Text("Save", style = MaterialTheme.typography.labelLarge) }
                note?.let { Text(it) }
            }
            Spacer(Modifier.height(2.dp))
            Text("These details say which project is yours. They are not a password — only phones you connect can see anything.")
        }
    }
}
