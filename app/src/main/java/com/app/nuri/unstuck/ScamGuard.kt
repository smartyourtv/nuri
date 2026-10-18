package com.app.nuri.unstuck

/**
 * Scam defense (catalog A5 / #21–24): the family safe word and message
 * pattern screening.
 *
 * The safe word is a family secret agreed at setup. On any "emergency"
 * claim from a loved one, Nuri's helper asks whether the caller said the
 * safe word. Scammers — including AI voice clones — don't know it. Nuri
 * NEVER treats a caller's claim as verified because it "sounds like"
 * someone: no voice is ever verified by sound.
 */
object SafeWord {
    fun matches(spoken: String?, stored: String?): Boolean {
        if (stored.isNullOrBlank() || spoken.isNullOrBlank()) return false
        return spoken.trim().equals(stored.trim(), ignoreCase = true)
    }

    /** The coaching line for a claimed family emergency. */
    fun emergencyCallLine(safeWordSet: Boolean): String = if (safeWordSet)
        "Before anything else: did they say the family safe word? A real emergency call from family will know it. " +
            "If they didn't say it — or they say 'there's no time' — hang up, and call the number you already have saved for them. " +
            "Real family won't mind you checking."
    else
        "Before anything else: call them back on the number you already have saved. Don't use a number they give you on this call. " +
            "Real family won't mind you checking first."
}

enum class ScamRisk { NONE, SUSPICIOUS, HIGH }

/**
 * Pattern screening for messages/texts (catalog #23). Screening FLAGS —
 * it never blocks, deletes, or replies. Money words + urgency + secrecy
 * together are the classic shape of a scam.
 */
object ScamScreen {
    private val moneyAsk = listOf("gift card", "wire", "venmo", "zelle", "cash app", "bitcoin", "prepaid card", "send money", "pay now")
    private val urgency = listOf("urgent", "right now", "immediately", "suspended", "arrest", "warrant", "final notice", "act now", "expires today")
    private val secrecy = listOf("don't tell", "do not tell", "keep this secret", "between us", "don't call")
    private val authority = listOf("irs", "social security", "medicare", "bank security", "fraud department", "microsoft support", "amazon security")

    fun screen(text: String): ScamRisk {
        val t = text.lowercase()
        val m = moneyAsk.any { it in t }; val u = urgency.any { it in t }
        val s = secrecy.any { it in t }; val a = authority.any { it in t }
        return when {
            m && (u || s) -> ScamRisk.HIGH
            (m && a) || (u && a) || s -> ScamRisk.SUSPICIOUS
            m || u -> ScamRisk.SUSPICIOUS
            else -> ScamRisk.NONE
        }
    }

    fun warningLine(risk: ScamRisk, askTarget: String): String? = when (risk) {
        ScamRisk.NONE -> null
        ScamRisk.SUSPICIOUS -> "This has some signs of a scam. Real companies don't rush you. Don't reply or tap anything yet — let's look together, or ask $askTarget."
        ScamRisk.HIGH -> "Stop — this looks like a common scam: it asks for money and rushes or scares you. Don't send anything and don't reply. Let's tell $askTarget right away."
    }
}
