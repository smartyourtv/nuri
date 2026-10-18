package com.app.nuri.unstuck

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Part E (v0.3.2 — Shawn, 2026-10-18): tap again to deselect on the
 * spouse-response picker (both first install and Settings use
 * [SpouseResponse.toggle]). Deselecting returns to UNSET — the
 * family hasn't chosen, and Nuri uses the UNSET comfort plan and
 * never improvises.
 *
 * The persistence half mirrors SettingsRepository's exact
 * encode/decode path (enum stored by name; read back via valueOf
 * with UNSET as the default): select → save → deselect → the stored
 * value IS UNSET.
 */
class SpouseToggleTest {

    private class FakeSpouseStore {
        private var raw: String? = null
        fun save(mode: SpouseResponseMode) { raw = mode.name }
        fun read(): SpouseResponseMode =
            raw?.let { s -> runCatching { SpouseResponseMode.valueOf(s) }.getOrNull() } ?: SpouseResponseMode.UNSET
    }

    @Test fun tappingAnotherModeSelectsIt() {
        assertEquals(SpouseResponseMode.GENTLE_TRUTH, SpouseResponse.toggle(SpouseResponseMode.UNSET, SpouseResponseMode.GENTLE_TRUTH))
        assertEquals(SpouseResponseMode.ASK_CAREGIVER, SpouseResponse.toggle(SpouseResponseMode.GENTLE_TRUTH, SpouseResponseMode.ASK_CAREGIVER))
    }

    @Test fun tappingTheChosenModeClearsToUnset() {
        SpouseResponseMode.values().filter { it != SpouseResponseMode.UNSET }.forEach { mode ->
            assertEquals("tapping $mode again must clear it", SpouseResponseMode.UNSET, SpouseResponse.toggle(mode, mode))
        }
        // UNSET tapped from UNSET stays UNSET (no phantom selection).
        assertEquals(SpouseResponseMode.UNSET, SpouseResponse.toggle(SpouseResponseMode.UNSET, SpouseResponseMode.UNSET))
    }

    @Test fun selectSaveDeselectPersistsUnset() {
        val store = FakeSpouseStore()
        // The Settings picker writes every tap through the repository.
        store.save(SpouseResponse.toggle(store.read(), SpouseResponseMode.COMFORT_REDIRECT))
        assertEquals(SpouseResponseMode.COMFORT_REDIRECT, store.read())
        // Tap the chosen one again → deselect → stored value is UNSET.
        store.save(SpouseResponse.toggle(store.read(), SpouseResponseMode.COMFORT_REDIRECT))
        assertEquals(SpouseResponseMode.UNSET, store.read())
        // And behavior after deselect is the never-chosen behavior.
        val plan = SpouseResponse.plan(store.read(), familyScript = null, overrideName = null, caregiverName = null)
        assertEquals(
            SpouseResponse.plan(SpouseResponseMode.UNSET, null, null, null).line,
            plan.line,
        )
    }
}
