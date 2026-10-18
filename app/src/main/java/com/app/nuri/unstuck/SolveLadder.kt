package com.app.nuri.unstuck

/**
 * The Solve Ladder (Shawn, 2026-10-17 — spec of record for every Unstuck
 * helper):
 *   1. AUTO — solve the problem automatically whenever possible (safe,
 *      reversible fixes; Nuri announces what it did; answering from Nuri's
 *      own records counts as AUTO).
 *   2. WALK-THROUGH — if the fix is easy, walk through it one step at a
 *      time, with an "I'm stuck" escape at every step.
 *   3. ASK — if it is more difficult, offer to ask a human. Nuri offers;
 *      the senior chooses. No silent escalation of merely-difficult
 *      problems.
 * Safety emergencies and fraud skip rung 3 for ESCALATE (standing alert
 * rules), and every helper ends by confirming the problem is resolved.
 */
enum class Rung { AUTO, WALK_THROUGH, ASK, ESCALATE }

/**
 * Rung-3 naming (Shawn, 2026-10-17): the PRIVATE build says "ask Shawn"
 * (he is the caregiver). The PUBLIC build uses the caregiver's name when
 * one was given at setup ("ask [name]"), falling back to "the caregiver"
 * only when no name was given. The public build must NEVER say Shawn.
 */
object AskNaming {
    /**
     * Who the ASK rung offers, as a noun phrase. [overrideName] comes from
     * the flavor (FlavorHooks.askNameOverride): the private edition
     * supplies the caregiver's own name from its PRIVATE source set; the
     * public flavor supplies null, so no private name can ever be
     * compiled into — or spoken by — the public build.
     */
    fun askTarget(overrideName: String?, caregiverName: String?): String = when {
        !overrideName.isNullOrBlank() -> overrideName.trim()
        !caregiverName.isNullOrBlank() -> caregiverName.trim()
        else -> "the caregiver"
    }

    /** The full offer sentence used at the ASK rung. */
    fun offerLine(overrideName: String?, caregiverName: String?): String =
        "Would you like me to ask ${askTarget(overrideName, caregiverName)} about this?"

    /** Short button label for the ASK rung. */
    fun buttonLabel(overrideName: String?, caregiverName: String?): String =
        "Ask ${askTarget(overrideName, caregiverName)}"
}

/**
 * Deceased-spouse response — a family-chosen SETUP setting (Shawn,
 * 2026-10-17). Nuri never improvises facts about the spouse. When the
 * family has written their own words ([familyScript]), Nuri uses them
 * verbatim. UNSET means: comfort + an ASK offer, and nothing factual.
 */
enum class SpouseResponseMode { UNSET, GENTLE_TRUTH, COMFORT_REDIRECT, ASK_CAREGIVER }

data class SpouseResponsePlan(
    /** What Nuri says. Never contains invented facts about the spouse. */
    val line: String,
    /** True when the ASK rung should be offered right after the line. */
    val offerAsk: Boolean,
)

object SpouseResponse {
    fun plan(
        mode: SpouseResponseMode,
        familyScript: String?,
        overrideName: String?,
        caregiverName: String?,
    ): SpouseResponsePlan {
        val script = familyScript?.trim().orEmpty()
        if (script.isNotEmpty()) return SpouseResponsePlan(script, offerAsk = mode == SpouseResponseMode.ASK_CAREGIVER)
        return when (mode) {
            SpouseResponseMode.GENTLE_TRUTH -> SpouseResponsePlan(
                "I know this is hard to hear, and I'm sorry. They passed away, and they aren't able to come. " +
                    "I'm right here with you, and we can sit together for a bit.",
                offerAsk = true,
            )
            SpouseResponseMode.COMFORT_REDIRECT -> SpouseResponsePlan(
                "You must miss them very much. Tell me about a happy time you had together — I'd love to hear it.",
                offerAsk = false,
            )
            SpouseResponseMode.ASK_CAREGIVER -> SpouseResponsePlan(
                "That's something ${AskNaming.askTarget(overrideName, caregiverName)} can help with best. " +
                    "Shall we ask them together?",
                offerAsk = true,
            )
            SpouseResponseMode.UNSET -> SpouseResponsePlan(
                "I'm here with you. Missing someone you love is hard, and you don't have to sit with it alone.",
                offerAsk = true,
            )
        }
    }

    /** Setup-screen explanation for each mode (plain language). */
    fun setupExplanation(mode: SpouseResponseMode): String = when (mode) {
        SpouseResponseMode.UNSET -> "Not chosen yet. Nuri will comfort, and offer to ask the caregiver. Nuri never makes up facts."
        SpouseResponseMode.GENTLE_TRUTH -> "Nuri gently says that they passed away, then stays with them."
        SpouseResponseMode.COMFORT_REDIRECT -> "Nuri turns to happy memories together instead."
        SpouseResponseMode.ASK_CAREGIVER -> "Nuri suggests asking the caregiver together."
    }
}
