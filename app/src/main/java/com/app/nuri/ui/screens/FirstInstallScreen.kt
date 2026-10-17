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
        "Nuri shares only what was set up with the therapist: check-in summaries and safety alerts, " +
        "with the people named during setup. Nuri does not share conversations word for word, and does not share anything with advertisers."
    const val ASK_ANYTIME: String =
        "You can ask Nuri any time: 'What do you share?' Nuri will always answer with the list above."
    const val LEGAL_REP_NOTE: String =
        "If the person using Nuri cannot give consent themselves, their legal representative gives and documents the consent instead."
}

@Composable
fun FirstInstallScreen(
    modifier: Modifier = Modifier,
    therapistConsentSeen: Boolean = false,
    onTherapistConsentRead: () -> Unit = {},
    onModeChosen: (NuriSettings.FirstInstallMode, NuriSettings.AccessMode) -> Unit = { _, _ -> }
) {
    var mode by remember { mutableStateOf<NuriSettings.FirstInstallMode?>(null) }
    var pendingConfirm by remember { mutableStateOf<Pair<NuriSettings.FirstInstallMode, NuriSettings.AccessMode>?>(null) }
    var feedback by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Welcome to Nuri", style = MaterialTheme.typography.headlineMedium)
        Text("Who is setting up this phone? Choose the one that fits. You can read each one first — nothing starts until you confirm.")
        Button(onClick = { mode = NuriSettings.FirstInstallMode.INDEPENDENT }, modifier = Modifier.fillMaxWidth()) { Text("Just me — I am setting up my own Nuri") }
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
                Text("How much would you like to see?", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { pendingConfirm = NuriSettings.FirstInstallMode.CAREGIVER to NuriSettings.AccessMode.HYBRID }, modifier = Modifier.fillMaxWidth()) { Text("Shared — they keep full control, I see a summary too") }
                Text("Routines, contacts, and alert settings come next. (Those setup screens are placeholders in this build.)")
                Button(onClick = { pendingConfirm = NuriSettings.FirstInstallMode.CAREGIVER to NuriSettings.AccessMode.MANAGED }, modifier = Modifier.fillMaxWidth()) { Text("I look after the settings — they just talk to Nuri") }
                Text("Best when the person needs the simplest experience. You apply updates and changes for them.")
            }
            NuriSettings.FirstInstallMode.THERAPIST -> {
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What Nuri shares — please read this together first", style = MaterialTheme.typography.titleMedium)
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
            text = { Text("Set up Nuri this way? You can change who helps later in Settings, with the same kind of confirmation.") },
            confirmButton = { TextButton(onClick = { onModeChosen(m, a); feedback = "Done — Nuri is set up. Welcome."; pendingConfirm = null }) { Text("Yes, set it up") } },
            dismissButton = { TextButton(onClick = { pendingConfirm = null }) { Text("Go back") } }
        )
    }
}
