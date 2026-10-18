package com.app.nuri.speech

/**
 * Offline safety layer intents (brief SS3/SS9). LOCAL ONLY by design: these
 * handlers never touch the network, so the emergency trigger, medication
 * reminder, check-in and alarm work with Wi-Fi down. Actions are stubs that
 * record what WOULD happen in diagnostics — real dialling / scheduling needs
 * on-device wiring and permissions, and is not faked here.
 * Language is scaffolding, not treatment: no diagnosis, no clinical claims.
 */
enum class SafetyIntent { MEDICATION_REMINDER, MORNING_CHECK_IN, ALARM, EMERGENCY_TRIGGER, WHERE_AM_I, HELP_REQUEST, UNKNOWN }

object SafetyIntentParser {
    fun parse(spoken: String): SafetyIntent {
        val t = spoken.lowercase().trim()
        return when {
            // The universal help entry (Shawn, 2026-10-17) is checked FIRST:
            // "I need help with something" contains "I need help" but is a
            // request for the Unstuck two-path offer, NOT an emergency.
            com.app.nuri.unstuck.HelpEntry.isHelpEntry(t) -> SafetyIntent.HELP_REQUEST
            t in listOf("help", "emergency", "call for help") || t.contains("i need help") -> SafetyIntent.EMERGENCY_TRIGGER
            t.contains("where am i") -> SafetyIntent.WHERE_AM_I
            t.contains("medicine") || t.contains("medication") || t.contains("my pills") -> SafetyIntent.MEDICATION_REMINDER
            t.contains("check in") || t.contains("check-in") || t.contains("morning") -> SafetyIntent.MORNING_CHECK_IN
            t.contains("alarm") || t.contains("timer") || t.contains("remind me") -> SafetyIntent.ALARM
            else -> SafetyIntent.UNKNOWN
        }
    }
}

data class SafetyAction(val intent: SafetyIntent, val spokenReply: String, val stubAction: String, val caregiverAlert: Boolean)

object OfflineSafetyHandlers {
    fun handle(intent: SafetyIntent, emergencyContactName: String = "your caregiver"): SafetyAction = when (intent) {
        SafetyIntent.HELP_REQUEST -> SafetyAction(intent,
            com.app.nuri.unstuck.HelpEntry.OFFER_LINE,
            "Route to the Unstuck two-path offer (say it / take a picture); the Unstuck screen opens there.", caregiverAlert = false)
        SafetyIntent.EMERGENCY_TRIGGER -> SafetyAction(intent,
            "I hear you. I am contacting $emergencyContactName now. Stay where you are, I am here with you.",
            "STUB (offline, local): would call/alert $emergencyContactName. No call placed by this build.", caregiverAlert = true)
        SafetyIntent.WHERE_AM_I -> SafetyAction(intent,
            "You are at home. You are safe. I have let $emergencyContactName know you asked.",
            "STUB (offline, local): would ping caregiver. No message sent by this build.", caregiverAlert = true)
        SafetyIntent.MEDICATION_REMINDER -> SafetyAction(intent,
            "It is time for your medicine. Take your time. Tell me when you have taken it.",
            "STUB (offline, local): would show the scheduled medication list. No schedule stored yet.", caregiverAlert = false)
        SafetyIntent.MORNING_CHECK_IN -> SafetyAction(intent,
            "Good morning. How did you sleep? We will go one step at a time.",
            "STUB (offline, local): would start the morning check-in (sleep, medicine, mood, appetite).", caregiverAlert = false)
        SafetyIntent.ALARM -> SafetyAction(intent,
            "All right, I will remind you.", "STUB (offline, local): would set a local alarm/timer. None set by this build.", caregiverAlert = false)
        SafetyIntent.UNKNOWN -> SafetyAction(intent,
            "I'm a bit limited right now, but I'm here. You can say 'help' any time.",
            "No action.", caregiverAlert = false)
    }
    fun diagnosticsText(): String =
        "Safety layer works offline by design (local intent matching only, no network). " +
        "Since v0.3.0 the real actions live in the Unstuck layer: the emergency button places a real call to the preset contact, " +
        "medicine reminders run from the stored schedule, check-ins record, and 'Where am I?' reads the phone's location. " +
        "This offline parser routes the phrases; the stub replies here remain the no-UI fallback."
}
