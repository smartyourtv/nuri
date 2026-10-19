package com.app.nuri.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.app.nuri.config.FlavorHooks
import com.app.nuri.config.NuriSettings

/**
 * First-install modes (brief SS5). NOT wired into navigation yet — the
 * senior home layout is frozen once learned, so onboarding integration is a
 * deliberate step. UX rule throughout: every consequential choice explains
 * itself, asks for confirmation, and gives feedback. No stealth code here:
 * private-flavour behaviour is gated on the unresolved repo decision.
 */
object TherapistSharingText {
    const val WHAT_IS_SHARED: String =
        "Ilona shares only what was set up with the therapist: check-in summaries and safety alerts, " +
        "with the people named during setup. Ilona does not share conversations word for word, and does not share anything with advertisers."
    const val ASK_ANYTIME: String =
        "You can ask Ilona any time: 'What do you share?' Ilona will always answer with the list above."
    const val LEGAL_REP_NOTE: String =
        "If the person using Ilona cannot give consent themselves, their legal representative gives and documents the consent instead."
}

@Composable
fun FirstInstallScreen(
    modifier: Modifier = Modifier,
    therapistConsentSeen: Boolean = false,
    onTherapistConsentRead: () -> Unit = {},
    // v0.3.2 (legal checklist §5): the safety card is shown once, at
    // the very top of first install; the acknowledgment is stored.
    // It never blocks setup or any emergency feature — the choices
    // below stay usable whether or not it has been tapped.
    safetyCardSeen: Boolean = false,
    onSafetyCardSeen: () -> Unit = {},
    onModeChosen: (NuriSettings.FirstInstallMode, NuriSettings.AccessMode) -> Unit = { _, _ -> },
    // v0.3.0: the family setup captured in the CAREGIVER branch (caregiver
    // name, safe word, deceased-spouse response, emergency contact, and
    // the two paper/Google checklist steps). Defaults keep older callers.
    onFamilySetup: (caregiverName: String, safeWord: String, spouseModeName: String, emergencyName: String, emergencyNumber: String, sealedPaper: Boolean, recoveryChain: Boolean) -> Unit =
        { _, _, _, _, _, _, _ -> }
) {
    var mode by remember { mutableStateOf<NuriSettings.FirstInstallMode?>(null) }
    var pendingConfirm by remember { mutableStateOf<Pair<NuriSettings.FirstInstallMode, NuriSettings.AccessMode>?>(null) }
    var feedback by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Welcome to Ilona", style = MaterialTheme.typography.headlineMedium)
        if (!safetyCardSeen) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(SafetyCardText.TITLE, style = MaterialTheme.typography.titleLarge)
                    Text(SafetyCardText.BODY)
                    Button(onClick = onSafetyCardSeen, modifier = Modifier.fillMaxWidth()) { Text(SafetyCardText.BUTTON) }
                }
            }
        }
        Text("Who is setting up this phone? Choose the one that fits. You can read each one first — nothing starts until you confirm.")
        Button(onClick = { mode = NuriSettings.FirstInstallMode.INDEPENDENT }, modifier = Modifier.fillMaxWidth()) { Text("Just me — I am setting up my own Ilona") }
        Text("'Just me' is already the usual choice. No caregiver, no sharing, nothing leaves this phone.")
        Button(onClick = { mode = NuriSettings.FirstInstallMode.CAREGIVER }, modifier = Modifier.fillMaxWidth()) { Text("I am a family member or caregiver helping") }
        Text("You will choose whether you also see a gentle summary, and set up routines, contacts, and alerts.")
        Button(onClick = { mode = NuriSettings.FirstInstallMode.THERAPIST }, modifier = Modifier.fillMaxWidth()) { Text("I am a therapist setting this up for a client") }
        Text("The client sees a plain-language list of exactly what is shared, before anything is turned on.")

        when (mode) {
            NuriSettings.FirstInstallMode.INDEPENDENT -> {
                Text("Just me: full control stays with you. No dashboard, no sharing prompts.", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { pendingConfirm = NuriSettings.FirstInstallMode.INDEPENDENT to NuriSettings.AccessMode.INDEPENDENT }) { Text("Use 'Just me'") }
            }
            NuriSettings.FirstInstallMode.CAREGIVER -> {
                // v0.3.0: the family setup itself (was a placeholder note).
                var cgName by remember { mutableStateOf("") }
                var cgWord by remember { mutableStateOf("") }
                var cgSpouse by remember { mutableStateOf(com.app.nuri.unstuck.SpouseResponseMode.UNSET) }
                var cgEmName by remember { mutableStateOf("") }
                var cgEmNumber by remember { mutableStateOf("") }
                var cgSealed by remember { mutableStateOf(false) }
                var cgChain by remember { mutableStateOf(false) }
                Text("Set up the help basics now — they can all be changed later in Settings.", style = MaterialTheme.typography.titleMedium)
                androidx.compose.material3.OutlinedTextField(value = cgName, onValueChange = { cgName = it }, label = { Text("Your name (the caregiver)") }, modifier = Modifier.fillMaxWidth())
                Text("When help is hard, Ilona offers to ask for a person by name." + (FlavorHooks.askNameOverride()?.let { " (This edition says 'ask $it'.)" } ?: " (Your name, as written above.)"))
                androidx.compose.material3.OutlinedTextField(value = cgWord, onValueChange = { cgWord = it }, label = { Text("Family safe word") }, modifier = Modifier.fillMaxWidth())
                Text("A word only your family knows. On any 'emergency' call claiming to be family, Ilona asks whether the caller knew it — voice-copying scammers won't.")
                Text("If they ask for a husband or wife who has passed, how should Ilona answer? The family chooses; Ilona never improvises.")
                com.app.nuri.unstuck.SpouseResponseMode.values().forEach { m ->
                    Button(onClick = { cgSpouse = com.app.nuri.unstuck.SpouseResponse.toggle(cgSpouse, m) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (m == cgSpouse) "${m.name.replace('_', ' ')} — chosen" else m.name.replace('_', ' '))
                    }
                    Text(com.app.nuri.unstuck.SpouseResponse.setupExplanation(m))
                }
                androidx.compose.material3.OutlinedTextField(value = cgEmName, onValueChange = { cgEmName = it }, label = { Text("Emergency contact name") }, modifier = Modifier.fillMaxWidth())
                androidx.compose.material3.OutlinedTextField(value = cgEmNumber, onValueChange = { cgEmNumber = it }, label = { Text("Emergency contact number") }, modifier = Modifier.fillMaxWidth())
                androidx.compose.foundation.layout.Row {
                    androidx.compose.material3.Checkbox(checked = cgSealed, onCheckedChange = { cgSealed = it })
                    Text("Sealed paper copy of their Google password, kept by you. (Their password — held on paper by family. Ilona never sees it.)")
                }
                androidx.compose.foundation.layout.Row {
                    androidx.compose.material3.Checkbox(checked = cgChain, onCheckedChange = { cgChain = it })
                    Text("Google recovery chain done: you are their recovery contact, and Inactive Account Manager is named.")
                }
                Text("How much would you like to see?", style = MaterialTheme.typography.titleMedium)
                Button(onClick = {
                    onFamilySetup(cgName.trim(), cgWord.trim(), cgSpouse.name, cgEmName.trim(), cgEmNumber.trim(), cgSealed, cgChain)
                    pendingConfirm = NuriSettings.FirstInstallMode.CAREGIVER to NuriSettings.AccessMode.HYBRID
                }, modifier = Modifier.fillMaxWidth()) { Text("Shared — they keep full control, I see a summary too") }
                Button(onClick = {
                    onFamilySetup(cgName.trim(), cgWord.trim(), cgSpouse.name, cgEmName.trim(), cgEmNumber.trim(), cgSealed, cgChain)
                    pendingConfirm = NuriSettings.FirstInstallMode.CAREGIVER to NuriSettings.AccessMode.MANAGED
                }, modifier = Modifier.fillMaxWidth()) { Text("I look after the settings — they just talk to Ilona") }
                Text("Best when the person needs the simplest experience. You apply updates and changes for them.")
            }
            NuriSettings.FirstInstallMode.THERAPIST -> {
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What Ilona shares — please read this together first", style = MaterialTheme.typography.titleMedium)
                    Text(TherapistSharingText.WHAT_IS_SHARED)
                    Text(TherapistSharingText.ASK_ANYTIME)
                    Text(TherapistSharingText.LEGAL_REP_NOTE)
                    // NU-5: the stored consent-seen state is READ and shown
                    // here (it was write-only before), so a setup that was
                    // already read together says so instead of asking blind.
                    Text(
                        com.app.nuri.setup.TherapistConsent.statusText(
                            com.app.nuri.setup.TherapistConsent.disclosureState(
                                NuriSettings.FirstInstallMode.THERAPIST, therapistConsentSeen
                            )
                        ),
                        style = MaterialTheme.typography.titleMedium
                    )
                } }
                Button(onClick = {
                    // Pressing this IS the attestation — record it (NU-5 write
                    // side) before the setup confirmation.
                    onTherapistConsentRead()
                    pendingConfirm = NuriSettings.FirstInstallMode.THERAPIST to NuriSettings.AccessMode.HYBRID
                }) { Text("We have read this together — continue") }
                Text("Continuing means the sharing list above was shown first. Nothing is shared silently in this mode.")
            }
            null -> {}
        }
        if (feedback.isNotEmpty()) Text(feedback, style = MaterialTheme.typography.titleMedium)
    }
    pendingConfirm?.let { (m, a) ->
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            title = { Text("Is this right?") },
            text = { Text("Set up Ilona this way? You can change who helps later in Settings, with the same kind of confirmation.") },
            confirmButton = { TextButton(onClick = { onModeChosen(m, a); feedback = "Done — Ilona is set up. Welcome."; pendingConfirm = null }) { Text("Yes, set it up") } },
            dismissButton = { TextButton(onClick = { pendingConfirm = null }) { Text("Go back") } }
        )
    }
}
