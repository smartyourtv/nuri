package com.app.nuri.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.app.nuri.config.FlavorHooks
import com.app.nuri.config.NuriSettings
import com.app.nuri.repository.SettingsRepository
import com.app.nuri.ui.theme.AppearanceTheme
import com.app.nuri.ui.theme.FontStep
import com.app.nuri.unstuck.CallActions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The senior home. v0.3.1 (Shawn on-device report 2026-10-18: "bare with
 * no help features and nowhere to type"): HOME IS HELP-FIRST. The first
 * things on this screen — biggest, above everything else — are the help
 * controls: the giant "I need help" button, "Show Nuri", and a VISIBLE
 * TEXT FIELD with an Ask button, because until the voice engine lands,
 * typing here IS how you talk to Nuri. Everything that was on this
 * screen before stays below, in the same order (interface freeze: add
 * and promote, never remove).
 *
 * Voice note, honestly: the speaking voice itself is still the gated
 * engine (sweep #1); this home is fully usable by touch today, and the
 * spoken-text seam routes the same phrases the voice will.
 */
@Composable
fun HomeScreen(
    repository: SettingsRepository,
    onOpenUnstuck: (startInCheckIn: Boolean) -> Unit,
    onOpenShowNuri: () -> Unit,
    onOpenGame: () -> Unit,
    onAskTyped: (String) -> Unit,
    gamesAllowedEffective: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val familyContacts by repository.familyContacts.collectAsState(initial = emptyList())
    val fontStep by repository.fontStep.collectAsState(initial = FontStep.EASY)
    val emotion by repository.emotionLevel.collectAsState(initial = NuriSettings.DEFAULT_EMOTION_LEVEL)
    val toggles by repository.personToggles.collectAsState(initial = com.app.nuri.tier.PersonToggles(emotionLevel = emotion))
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Hello" }
    val todayLine = remember { SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date()) }
    // Conversational suggestion (private edition's family layer; always
    // null in the public edition). Non-critical changes arrive this way —
    // offered, never switched silently.
    val suggestion = remember(fontStep, emotion, toggles) {
        FlavorHooks.pendingSuggestion(context, fontStep.name, emotion.name, toggles.gamesAllowed ?: true)
    }

    var typedText by remember { androidx.compose.runtime.mutableStateOf("") }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Nuri", style = MaterialTheme.typography.headlineLarge)
        Text("$greeting. It's $todayLine.", style = MaterialTheme.typography.bodyLarge)

        // === HELP-FIRST BLOCK (v0.3.1) — the first, biggest things here.
        Button(
            onClick = { onOpenUnstuck(false) },
            modifier = Modifier.fillMaxWidth().height(84.dp),
        ) {
            Text("I need help", style = MaterialTheme.typography.labelLarge)
        }
        Text("For anything confusing — the phone, the TV, a letter, a worry. Tell me, or show me a picture.")

        Button(
            onClick = onOpenShowNuri,
            modifier = Modifier.fillMaxWidth().height(72.dp),
        ) {
            Text("Show Nuri", style = MaterialTheme.typography.labelLarge)
        }
        Text("Take a picture of something and I'll read it or tell you what it is.")

        Text("Or type it here:", style = MaterialTheme.typography.titleLarge)
        androidx.compose.material3.OutlinedTextField(
            value = typedText,
            onValueChange = { typedText = it },
            label = { Text("Type what you need help with", style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Button(
            onClick = { if (typedText.isNotBlank()) { onAskTyped(typedText.trim()); typedText = "" } },
            modifier = Modifier.fillMaxWidth().height(72.dp),
        ) {
            Text("Ask Nuri", style = MaterialTheme.typography.labelLarge)
        }
        Text("Type it in your own words — spelling and exact words don't matter. Nuri will read it back to check it understood.")
        // === end help-first block; everything below is the v0.3.0 home, unchanged order.

        Button(onClick = { onOpenUnstuck(true) }, modifier = Modifier.fillMaxWidth()) {
            Text("Morning check-in", style = MaterialTheme.typography.labelLarge)
        }
        Text("Three quick questions, once a day. It helps your family know you're well.")

        // Emergency — two-step on purpose (explain, confirm, feedback):
        // the first tap arms it and says what will happen; the second calls.
        val armed = remember { androidx.compose.runtime.mutableStateOf(false) }
        Button(onClick = {
            if (!armed.value) {
                armed.value = true
                Toast.makeText(context, "Tap once more to call your emergency contact. Tap anywhere else to cancel.", Toast.LENGTH_LONG).show()
            } else {
                armed.value = false
                scope.launch {
                    val name = FlavorHooks.effectiveEmergencyName(context, repository.emergencyContactName.first())
                    val number = FlavorHooks.effectiveEmergencyNumber(context, repository.emergencyContactNumber.first())
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                    val plan = CallActions.emergencyPlan(name, number, granted)
                    if (plan == null) {
                        Toast.makeText(context, "No emergency contact is set up yet. In an emergency, call 911.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, plan.note, Toast.LENGTH_LONG).show()
                        val intent = if (plan.directCall) Intent(Intent.ACTION_CALL, Uri.parse("tel:${plan.number}"))
                        else Intent(Intent.ACTION_DIAL, Uri.parse("tel:${plan.number}"))
                        runCatching { context.startActivity(intent) }
                        val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                        repository.addSafetyAlert(com.app.nuri.unstuck.SafetyAlert(key, time, "Emergency call made", "The home-screen emergency button was used."))
                    }
                }
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text(if (armed.value) "Tap again to call now" else "Call for help", style = MaterialTheme.typography.labelLarge)
        }
        Text("Calls your emergency contact — the person set up in Settings. Two taps, so a pocket can't do it by accident.")

        if (familyContacts.isNotEmpty()) {
            Text("Call family", style = MaterialTheme.typography.titleLarge)
            familyContacts.forEach { contact ->
                Button(onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                    val plan = CallActions.familyPlan(contact, granted)
                    Toast.makeText(context, plan.note, Toast.LENGTH_SHORT).show()
                    val intent = if (plan.directCall) Intent(Intent.ACTION_CALL, Uri.parse("tel:${plan.number}"))
                    else Intent(Intent.ACTION_DIAL, Uri.parse("tel:${plan.number}"))
                    runCatching { context.startActivity(intent) }
                }, modifier = Modifier.fillMaxWidth()) { Text("Call ${contact.name}", style = MaterialTheme.typography.labelLarge) }
            }
        }

        if (gamesAllowedEffective) {
            Button(onClick = onOpenGame, modifier = Modifier.fillMaxWidth()) {
                Text("Puzzle game", style = MaterialTheme.typography.labelLarge)
            }
            Text("A gentle matching game. No timers, no scores that matter.")
        }

        suggestion?.let { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.line, style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = {
                        scope.launch {
                            when (s.kind) {
                                "FONT_STEP" -> runCatching { repository.setFontStep(FontStep.valueOf(s.value)) }
                                "EMOTION" -> runCatching { repository.setEmotionLevel(NuriSettings.EmotionLevel.valueOf(s.value)) }
                                "GAMES" -> runCatching { repository.setGamesAllowed(s.value.toBoolean()) }
                            }
                        }
                        Toast.makeText(context, "Done — changed. It can always be changed back in Settings.", Toast.LENGTH_LONG).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Yes, let's try it", style = MaterialTheme.typography.labelLarge) }
                    Button(onClick = { FlavorHooks.recordSuggestionDeclined(context, s) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Not now", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        if (hour in 17..22) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your evening", style = MaterialTheme.typography.titleLarge)
                    Text("The calm part of the day. Your chair, your show, a warm drink — nothing is due tonight, and nothing needs deciding.")
                    Text("If the evening feels restless, tap 'I need help' and tell me — evenings are a known wobbly time, and company helps.")
                }
            }
        }

        // Build identity (v0.3.1): always visible at the end of Home so any
        // screenshot or support conversation can name the exact build.
        Text("Nuri version ${com.app.nuri.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
    }
}
