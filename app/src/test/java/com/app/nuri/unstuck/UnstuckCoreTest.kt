package com.app.nuri.unstuck

import com.app.nuri.config.NuriSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Solve-ladder engine, rung-3 naming, spouse plans, diary (spec A/B/C). */
class UnstuckCoreTest {

    // ── Rung-3 naming ────────────────────────────────────────────────
    @Test fun privateBuildAsksShawn() {
        assertEquals("Shawn", AskNaming.askTarget(overrideName = "Shawn", caregiverName = "Maria"))
        assertEquals("Ask Shawn", AskNaming.buttonLabel("Shawn", null))
    }

    @Test fun publicBuildUsesCaregiverName() {
        assertEquals("Maria", AskNaming.askTarget(overrideName = null, caregiverName = "Maria"))
        assertEquals("Would you like me to ask Maria about this?", AskNaming.offerLine(null, "Maria"))
    }

    @Test fun publicBuildFallsBackToTheCaregiver() {
        assertEquals("the caregiver", AskNaming.askTarget(overrideName = null, caregiverName = null))
        assertEquals("the caregiver", AskNaming.askTarget(overrideName = null, caregiverName = "  "))
    }

    @Test fun publicBuildNeverNamesShawn() {
        // Even if a stored name were blank/odd, the public offer must not contain "Shawn".
        assertFalse(AskNaming.offerLine(null, null).contains("Shawn"))
        assertFalse(AskNaming.offerLine(null, "").contains("Shawn"))
    }

    // ── Deceased-spouse setting ──────────────────────────────────────
    @Test fun spouseUnsetIsComfortPlusAskWithNoInventedFacts() {
        val plan = SpouseResponse.plan(SpouseResponseMode.UNSET, null, overrideName = null, caregiverName = "Maria")
        assertTrue(plan.offerAsk)
        assertFalse(plan.line.contains("passed away")) // unset must not assert facts either way
    }

    @Test fun spouseFamilyScriptUsedVerbatim() {
        val script = "He's resting now, love. Let's have some tea."
        val plan = SpouseResponse.plan(SpouseResponseMode.GENTLE_TRUTH, script, null, "Maria")
        assertEquals(script, plan.line)
    }

    @Test fun spouseAskCaregiverModeNamesTheAskTarget() {
        val plan = SpouseResponse.plan(SpouseResponseMode.ASK_CAREGIVER, null, overrideName = "Shawn", caregiverName = null)
        assertTrue(plan.line.contains("Shawn"))
    }

    // ── Engine flow ──────────────────────────────────────────────────
    private fun helper(number: Int) = HelperCatalogDataB.combined.first { it.number == number }

    @Test fun guidedSessionWalksStepsThenConfirms() {
        val session = HelperEngine.start(helper(53), HelperFacts())
        val opening = session.opening()
        assertTrue(opening.any { it is EngineOutput.Say })
        var outs = session.onDone()
        assertTrue(outs.any { it is EngineOutput.Say })
        outs = session.onDone()
        assertTrue(outs.any { it is EngineOutput.Say })
        outs = session.onDone()
        assertTrue(outs.any { it is EngineOutput.Done })
    }

    @Test fun stuckTwiceEscalatesToAskOffer() {
        val session = HelperEngine.start(helper(53), HelperFacts(askOverrideName = "Shawn"))
        session.opening()
        val first = session.onStuck()
        assertTrue(first.none { it is EngineOutput.Ask }) // first stuck = rephrase, patience rule
        val second = session.onStuck()
        assertTrue(second.any { it is EngineOutput.Ask })
        val ask = second.filterIsInstance<EngineOutput.Ask>().first()
        assertEquals("Ask Shawn", ask.buttonLabel)
    }

    @Test fun askOnlyHelperOpensWithAskOffer() {
        val session = HelperEngine.start(helper(22), HelperFacts(caregiverName = "Maria"))
        val outs = session.opening()
        assertTrue(outs.any { it is EngineOutput.Ask })
        assertEquals("Ask Maria", outs.filterIsInstance<EngineOutput.Ask>().first().buttonLabel)
    }

    @Test fun escalateHelperEscalates() {
        val session = HelperEngine.start(helper(32), HelperFacts())
        val outs = session.opening()
        assertTrue(outs.any { it is EngineOutput.Escalate })
    }

    @Test fun autoFixCandidatesOnlyForMutedSound() {
        val muted = DeviceStateSnapshot(mediaVolume = 0, maxMediaVolume = 15, ringVolume = 5, maxRingVolume = 7)
        val session = HelperEngine.start(helper(57), HelperFacts(snapshot = muted))
        assertTrue(session.autoFixCandidates().contains(FixAction.RAISE_MEDIA_VOLUME))
        val fine = DeviceStateSnapshot(mediaVolume = 10, maxMediaVolume = 15)
        val session2 = HelperEngine.start(helper(57), HelperFacts(snapshot = fine))
        assertTrue(session2.autoFixCandidates().isEmpty())
    }

    @Test fun unknownStateIsNeverTreatedAsBroken() {
        val session = HelperEngine.start(helper(74), HelperFacts(snapshot = DeviceStateSnapshot()))
        // No snapshot facts → no check line claiming a problem.
        assertTrue(session.checkFirstLine() == null)
    }

    // ── Diary ────────────────────────────────────────────────────────
    @Test fun diaryVisibilityFollowsAccessMode() {
        assertFalse(ConfusionDiary.caregiverVisible(NuriSettings.AccessMode.INDEPENDENT))
        assertTrue(ConfusionDiary.caregiverVisible(NuriSettings.AccessMode.HYBRID))
        assertTrue(ConfusionDiary.caregiverVisible(NuriSettings.AccessMode.MANAGED))
    }

    @Test fun diaryRoundTripAndGarbageSafety() {
        val entries = listOf(DiaryEntry("2026-10-17", "8:05 AM", "Asked for help: no sound", "Resolved"))
        assertEquals(entries, ConfusionDiary.decode(ConfusionDiary.encode(entries)))
        assertEquals(emptyList<DiaryEntry>(), ConfusionDiary.decode("garbage;;|"))
        assertEquals(emptyList<DiaryEntry>(), ConfusionDiary.decode(""))
    }

    // ── Catalog integrity ────────────────────────────────────────────
    @Test fun catalogHasAll98SituationsExactlyOnce() {
        val all = HelperCatalogDataB.combined
        assertEquals(98, all.size)
        assertEquals((1..98).toList(), all.map { it.number }.sorted())
        assertEquals(23, all.map { it.family }.distinct().size)
    }

    @Test fun everyGuidedHelperHasSteps() {
        HelperCatalogDataB.combined.filter { it.mode == HelperMode.GUIDED || it.mode == HelperMode.AUTO_CHECK }.forEach { def ->
            assertTrue("helper ${def.number} (${def.title}) needs steps", def.steps.isNotEmpty())
        }
    }
}
