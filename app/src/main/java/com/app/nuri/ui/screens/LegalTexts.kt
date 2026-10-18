package com.app.nuri.ui.screens

/**
 * Safety disclaimer texts (legal wiring checklist §5; source of truth:
 * files/nuri-safety-disclaimer.md, audit F-14 — use exactly, changes
 * need re-review against F-14). Key facts these rest on: Nuri never
 * calls 911 or any number by itself; fall detection is NOT in this
 * build and is never claimed; medication helpers never give dose
 * advice.
 */
object SafetyCardText {
    const val TITLE: String = "Please read this first — what Nuri is and is not"
    const val BODY: String =
        "Nuri is a helper app. It is not a medical device, and it is not a doctor, nurse, or pharmacist.\n\n" +
            "• In an emergency, call 911. Nuri does not contact emergency services for you. The \"Call for help\" button only dials when you tap it and confirm.\n\n" +
            "• Medicine reminders are memory aids. They repeat the schedule a person typed in. If the schedule was typed in wrong, the reminders will be wrong too. Nuri never tells you how much medicine to take. Questions about doses go to your pharmacist or doctor.\n\n" +
            "• Safety features are aids, not guarantees. Check-ins, safety notes, and alerts can miss things, and they only work on this phone. They do not watch you, and they cannot send help by themselves.\n\n" +
            "• If something feels wrong with your health, call your doctor or 911 — do not wait for Nuri."
    const val BUTTON: String = "I understand"
}

object SafetyInfoText {
    const val TITLE: String = "Safety information"
    const val BODY: String =
        "Nuri is not a medical device. It does not diagnose, treat, or monitor any medical condition, and it is not a substitute for professional care or for emergency services.\n\n" +
            "• Emergencies: call 911. Nuri never calls emergency services on its own; every call is started by you, after a confirmation.\n\n" +
            "• Medicine: reminders repeat the schedule a person entered and are memory aids only — not medical advice. Nuri never advises on doses. Ask a pharmacist or doctor.\n\n" +
            "• Safety aids: check-ins, missed-medicine notes, and other safety features are helpful aids. They can miss events, they depend on this phone being charged and nearby, and they work only on this device. A person who cares for you should not rely on Nuri alone.\n\n" +
            "Full privacy policy: https://smartyourtv.github.io/nuri/privacy-policy"
    const val PRIVACY_URL: String = "https://smartyourtv.github.io/nuri/privacy-policy"
}
