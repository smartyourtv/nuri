package com.app.nuri.stealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STEALTH logic tests — PRIVATE flavor only. This file lives in
 * app/src/testPrivate, so it compiles and runs only for the private
 * flavor's unit tests; the public flavor has no stealth code to test.
 */
class ShadowLogicTest {
    @Test fun safetyCriticalShadowsWinSilently() {
        val shadow = ShadowValues(tierName = "GUIDED", emergencyNumber = "9165550100", emergencyName = "Shawn")
        assertEquals("GUIDED", ShadowLogic.effectiveTier("SUPPORTED", shadow))
        assertEquals("9165550100", ShadowLogic.effectiveEmergencyNumber("000", shadow))
        assertEquals("Shawn", ShadowLogic.effectiveEmergencyName("Nobody", shadow))
    }

    @Test fun noShadowMeansSeenValuesApply() {
        val shadow = ShadowValues()
        assertEquals("SUPPORTED", ShadowLogic.effectiveTier("SUPPORTED", shadow))
        assertEquals("123", ShadowLogic.effectiveEmergencyNumber("123", shadow))
        assertEquals("seen", ShadowLogic.effectiveMedScheduleEncoded("seen", shadow))
    }

    @Test fun medScheduleShadowReplacesWhenSet() {
        val shadow = ShadowValues(medScheduleEncoded = "shadow-schedule")
        assertEquals("shadow-schedule", ShadowLogic.effectiveMedScheduleEncoded("seen", shadow))
    }

    @Test fun nonCriticalChangesAreSuggestionsNotSwitches() {
        val shadow = ShadowValues(fontStepName = "EASIEST")
        val suggestion = ShadowLogic.pendingSuggestion(shadow, seenFontStepName = "EASY", seenEmotionLevelName = "WARM", seenGamesAllowed = true)
        assertEquals(ShadowLogic.KIND_FONT_STEP, suggestion?.kind)
        assertEquals("EASIEST", suggestion?.value)
        assertTrue(suggestion!!.line.contains("your choice"))
    }

    @Test fun declinedSuggestionIsNotReAskedUnlessValueChanges() {
        val shadow = ShadowValues(fontStepName = "EASIEST", declined = mapOf(ShadowLogic.KIND_FONT_STEP to "EASIEST"))
        assertNull(ShadowLogic.pendingSuggestion(shadow, "EASY", "WARM", true))
        val changed = shadow.copy(fontStepName = "EASIER")
        assertEquals("EASIER", ShadowLogic.pendingSuggestion(changed, "EASY", "WARM", true)?.value)
    }

    @Test fun noSuggestionWhenShadowMatchesSeen() {
        val shadow = ShadowValues(fontStepName = "EASY", emotionLevelName = "WARM", gamesAllowed = true)
        assertNull(ShadowLogic.pendingSuggestion(shadow, "EASY", "WARM", true))
    }

    // ── Part E, caregiver side (v0.3.2): tap-again-to-deselect ──────
    // Mirrors FlavorHooks.writeShadow/readShadow semantics exactly:
    // a null string REMOVES the stored key (SharedPreferences
    // putString(null) behavior as used by writeShadow), and readShadow
    // then reports null = no shadow = the senior's own value stands.
    private class FakeShadowStore {
        private val map = mutableMapOf<String, String>()
        fun write(values: ShadowValues) {
            fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
            put("tier", values.tierName); put("font", values.fontStepName); put("emotion", values.emotionLevelName)
            if (values.gamesAllowed == null) map.remove("games") else map["games"] = values.gamesAllowed.toString()
        }
        fun read(): ShadowValues = ShadowValues(
            tierName = map["tier"], fontStepName = map["font"], emotionLevelName = map["emotion"],
            gamesAllowed = map["games"]?.toBooleanStrictOrNull(),
        )
    }

    @Test fun caregiverDeselectClearsShadowAndPersists() {
        val store = FakeShadowStore()
        var shadow = store.read()
        // Caregiver sets a font-step shadow (toggle from no shadow).
        shadow = shadow.copy(fontStepName = ShadowLogic.toggleChoice(shadow.fontStepName, "EASIEST"))
        store.write(shadow)
        assertEquals("EASIEST", store.read().fontStepName)
        // Caregiver taps the SAME value again → deselect → persisted null.
        shadow = store.read()
        shadow = shadow.copy(fontStepName = ShadowLogic.toggleChoice(shadow.fontStepName, "EASIEST"))
        store.write(shadow)
        assertNull(store.read().fontStepName)
        // With no shadow, the senior's own value is effective again.
        assertEquals("SUPPORTED", ShadowLogic.effectiveTier("SUPPORTED", store.read()))
        assertNull(ShadowLogic.pendingSuggestion(store.read(), "EASY", "WARM", true))
    }

    @Test fun caregiverGamesToggleClearsToo() {
        val store = FakeShadowStore()
        var shadow = store.read().copy(gamesAllowed = ShadowLogic.toggleGames(store.read().gamesAllowed, true))
        store.write(shadow)
        assertEquals(true, store.read().gamesAllowed)
        shadow = store.read().copy(gamesAllowed = ShadowLogic.toggleGames(store.read().gamesAllowed, true))
        store.write(shadow)
        assertNull(store.read().gamesAllowed)
        // Choosing the OTHER value switches instead of clearing.
        assertEquals(false, ShadowLogic.toggleGames(true, false))
        assertEquals("GUIDED", ShadowLogic.toggleChoice("SUPPORTED", "GUIDED"))
    }
}
