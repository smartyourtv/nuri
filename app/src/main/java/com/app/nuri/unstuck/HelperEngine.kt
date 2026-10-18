package com.app.nuri.unstuck

/**
 * The solve-ladder session engine — pure Kotlin, fully testable.
 *
 * Given a helper + the check-first facts, [start] produces the opening
 * sequence (check findings, auto fixes, then steps or the ASK offer).
 * The UI advances with [onDone] / [onStuck]:
 *  - "I'm stuck" once → the step is rephrased once (a shorter variant);
 *  - "I'm stuck" twice (or the catalog marks it ASK) → the ASK rung.
 * Every session ends by confirming resolution, and the diary outcome
 * records where it landed.
 */
sealed class EngineOutput {
    /** A line spoken/shown to the senior. */
    data class Say(val text: String) : EngineOutput()
    /** An automatic safe fix, announced when it happens. */
    data class AutoFix(val action: FixAction, val detail: String) : EngineOutput()
    /** The ASK offer. */
    data class Ask(val question: String, val buttonLabel: String) : EngineOutput()
    /** Safety script: get a person, now. */
    data class Escalate(val line: String, val emergencyNumber: String = "911") : EngineOutput()
    /** The session is finished. */
    data class Done(val outcome: String) : EngineOutput()
}

/** Facts the engine can consult. Everything is optional — unknown ≠ broken. */
data class HelperFacts(
    val snapshot: DeviceStateSnapshot? = null,
    val todayMedsConfirmed: List<MedConfirmation> = emptyList(),
    val safeWordSet: Boolean = false,
    val spousePlan: SpouseResponsePlan? = null,
    val askOverrideName: String? = null,
    val caregiverName: String? = null,
    val medsDueToday: List<String> = emptyList(),
)

class HelperSession(val helper: HelperDef, val facts: HelperFacts) {
    private var stepIndex = 0
    private var stuckCount = 0
    private var finished = false
    val askedForPerson: Boolean get() = finished && stuckCount >= 2 || escalated
    var escalated: Boolean = false
        private set

    fun opening(): List<EngineOutput> {
        val out = mutableListOf<EngineOutput>()
        // Rung 1: check-first findings (spoken).
        checkFirstLine()?.let { out += EngineOutput.Say(it) }
        // AUTO fixes are proposed by the platform layer via [autoFixCandidates];
        // the engine just declares which ones are warranted.
        when (helper.mode) {
            HelperMode.AUTO_CHECK -> {
                if (stepIndex == 0 && helper.steps.isNotEmpty()) out += EngineOutput.Say(helper.steps[0])
            }
            HelperMode.GUIDED -> {
                helper.openingLine?.let { out += EngineOutput.Say(it) }
                if (helper.steps.isNotEmpty()) out += EngineOutput.Say(helper.steps[0])
            }
            HelperMode.ASK_ONLY -> {
                helper.openingLine?.let { out += EngineOutput.Say(it) }
                out += askOffer()
            }
            HelperMode.ESCALATE -> {
                escalated = true
                out += EngineOutput.Escalate(
                    helper.openingLine ?: "Let's get a person right now. I'll stay with you.",
                )
                out += askOffer()
            }
            HelperMode.DEFERRED -> {
                out += EngineOutput.Say(helper.deferredReason ?: "I can't do that part yet.")
                out += askOffer()
            }
        }
        return out
    }

    /** Fix actions the platform layer may apply (safe, reversible). */
    fun autoFixCandidates(): List<FixAction> {
        val s = facts.snapshot ?: return emptyList()
        if (helper.mode != HelperMode.AUTO_CHECK) return emptyList()
        return buildList {
            when (helper.checkKey) {
                CheckKey.SOUND -> {
                    if (s.mediaMuted || s.mediaLow) add(FixAction.RAISE_MEDIA_VOLUME)
                    if (s.ringMuted) add(FixAction.RAISE_RING_VOLUME)
                }
                CheckKey.DND -> if (s.doNotDisturbOn == true && s.dndChangeAllowed) add(FixAction.TURN_OFF_DND)
                CheckKey.FLASHLIGHT -> if (s.flashlightOn == true) add(FixAction.TURN_OFF_FLASHLIGHT)
                else -> {}
            }
        }
    }

    /** The check-first line spoken at the start, or null when nothing found. */
    fun checkFirstLine(): String? {
        val s = facts.snapshot ?: return when (helper.checkKey) {
            CheckKey.MED_LOG -> {
                // Answered from Nuri's own records — that's an AUTO move.
                if (helper.number == 9) MedRecords.didITakeAnswer(facts.todayMedsConfirmed) else null
            }
            CheckKey.SAFE_WORD -> if (helper.number == 21) SafeWord.emergencyCallLine(facts.safeWordSet) else null
            CheckKey.SPOUSE -> if (helper.number == 35) facts.spousePlan?.line else null
            else -> null
        }
        return when (helper.checkKey) {
            CheckKey.SOUND -> when {
                s.mediaMuted && s.ringMuted -> "I checked your sound first: the phone was silent and the ring was off too. I'll raise both, gently, and you tell me how it feels."
                s.mediaMuted -> "I checked your sound first: it was turned all the way down. I'll raise it gently and you tell me how it feels."
                s.mediaLow -> "I checked your sound first: it was set very low. I'll raise it a little."
                s.mediaVolume != null || s.ringVolume != null -> "I checked your sound first, and it's fine — so the cause is somewhere else."
                else -> null // unknown is not "fine" — never claim a check that didn't happen
            }
            CheckKey.DND -> when {
                s.doNotDisturbOn == true && s.dndChangeAllowed -> "I checked first: Do Not Disturb was on — that's why nothing buzzed. I've turned it off; you'll hear things again."
                s.doNotDisturbOn == true -> "I checked first: Do Not Disturb is on — that's why nothing buzzed. I can't switch it off for you, but I'll walk you through it."
                else -> null
            }
            CheckKey.CONNECTIVITY -> when {
                s.airplaneModeOn == true -> "I checked first: Airplane Mode was on — that's what stopped everything. That's usually a sleeve brushing the screen."
                s.wifiOn == false -> "I checked first: your Wi-Fi is off. I can't switch it back on myself, but I'll walk you through it."
                s.hasConnectivity == false -> "I checked first: there's no connection right now. Let's fix that first, then what you were doing."
                s.airplaneModeOn != null || s.wifiOn != null || s.hasConnectivity != null -> "I checked the connection first, and it's fine — so the cause is somewhere else."
                else -> null // unknown is not "fine" — never claim a check that didn't happen
            }
            CheckKey.BATTERY -> when {
                s.batteryPercent != null && s.batteryPercent <= 5 -> "I checked your battery first: it's at ${s.batteryPercent} percent — nearly empty. Plug it in now, and things will make more sense once it wakes up a little."
                s.batteryLow -> "I checked your battery first: it's at ${s.batteryPercent} percent and not charging. Let's plug it in before we do anything else."
                else -> null
            }
            CheckKey.STORAGE -> if (s.storageFull) "I checked your storage first: it's nearly full. That's why new things won't save. The fix is a tidy-up together — never deleting your photos." else null
            CheckKey.TALKBACK -> if (s.talkBackOn == true) "I can see the reading-aloud helper is on — that's the whole story. The fastest way out: hold both volume buttons together for three seconds." else null
            CheckKey.TEXT_SIZE -> if (s.fontScale != null && s.fontScale > 1.3f) "I checked the word size first: it drifted bigger than your usual. I'll walk you back to your usual." else null
            CheckKey.FLASHLIGHT -> if (s.flashlightOn == true) "I checked the flashlight first: it was on. I've turned it off for you." else null
            CheckKey.MED_LOG -> if (helper.number == 9) MedRecords.didITakeAnswer(facts.todayMedsConfirmed) else null
            CheckKey.SAFE_WORD -> if (helper.number == 21) SafeWord.emergencyCallLine(facts.safeWordSet) else null
            CheckKey.SPOUSE -> if (helper.number == 35) facts.spousePlan?.line else null
            CheckKey.ALARM -> "I checked your alarm first. Now, the classic question with zero mockery: does it say AM or PM?"
            CheckKey.LOCATION -> "I can read the phone's location. Stay where you are — we'll pin this down together."
            else -> null
        }
    }

    /** Senior tapped "Done / I understand" on the current step. */
    fun onDone(): List<EngineOutput> {
        if (finished) return emptyList()
        stuckCount = 0
        stepIndex++
        val remaining = helper.steps
        return if (stepIndex < remaining.size) {
            listOf(EngineOutput.Say(remaining[stepIndex]))
        } else {
            finished = true
            listOf(
                EngineOutput.Say("Is it working now? If yes, we're all done — and well done for sticking with it."),
                EngineOutput.Done("Resolved"),
            )
        }
    }

    /** Senior tapped "I'm stuck" on the current step. */
    fun onStuck(): List<EngineOutput> {
        if (finished) return emptyList()
        stuckCount++
        return when {
            stuckCount == 1 && helper.mode == HelperMode.GUIDED && stepIndex < helper.steps.size ->
                listOf(EngineOutput.Say("Let's try that one differently, in even smaller pieces: ${helper.steps[stepIndex]} " +
                    "Take your time — there's no rush, and asking again is always fine."))
            else -> {
                finished = true
                listOf(
                    EngineOutput.Say("No trouble at all — this is exactly the kind of thing a person can sort in a moment."),
                    askOffer(),
                    EngineOutput.Done("Asked ${AskNaming.askTarget(facts.askOverrideName, facts.caregiverName)}"),
                )
            }
        }
    }

    fun confirmUnresolved(): List<EngineOutput> {
        finished = true
        return listOf(askOffer(), EngineOutput.Done("Still stuck — asked ${AskNaming.askTarget(facts.askOverrideName, facts.caregiverName)}"))
    }

    private fun askOffer(): EngineOutput.Ask = EngineOutput.Ask(
        AskNaming.offerLine(facts.askOverrideName, facts.caregiverName),
        AskNaming.buttonLabel(facts.askOverrideName, facts.caregiverName),
    )
}

object HelperEngine {
    fun start(helper: HelperDef, facts: HelperFacts): HelperSession = HelperSession(helper, facts)

    /** Find a helper for free text: tolerant token match (never exact-phrase). */
    fun match(spokenText: String): HelperDef? = TolerantMatch.match(spokenText, HelperCatalogDataB.combined)?.helper

    /** Full tolerant outcome: best match + runners-up for mirror/choices. */
    fun matchAll(spokenText: String): List<TolerantMatch.Match> = TolerantMatch.matchAll(spokenText, HelperCatalogDataB.combined)

    val genericConfusionHelper: HelperDef = HelperDef(
        0, "MENU", "I'm confused — what is it?", HelperMode.GUIDED, triggers = listOf("i'm confused", "i need help", "i'm stuck", "i don't know"),
        steps = listOf("That's fine — tell me in your own words what's confusing you, and we'll take it from there.", "Or tap a topic below: Phone sound and calls, Messages, The TV, Letters and bills, Safety and scams, Show Nuri — take a photo of something."),
    )
}
