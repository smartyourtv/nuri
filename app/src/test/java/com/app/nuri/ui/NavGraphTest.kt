package com.app.nuri.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reachability contract (v0.3.1): every feature surface declared in
 * [NavGraph] must be reachable in ≤3 taps from a cold start, Home must
 * carry the help-first elements, and the declaration must cover the
 * full feature set — a surface that exists in code but not here (or
 * here but with a longer path) fails the build.
 */
class NavGraphTest {

    @Test
    fun everyDestinationReachableWithinThreeTaps() {
        val tooFar = NavGraph.unreachable()
        assertTrue("Destinations beyond ${NavGraph.MAX_TAPS} taps: $tooFar", tooFar.isEmpty())
    }

    @Test
    fun homeCarriesTheHelpFirstElements() {
        assertTrue(NavGraph.HomeElement.HELP_BUTTON in NavGraph.HOME_ELEMENTS)
        assertTrue(NavGraph.HomeElement.SHOW_NURI_BUTTON in NavGraph.HOME_ELEMENTS)
        assertTrue(NavGraph.HomeElement.TEXT_INPUT in NavGraph.HOME_ELEMENTS)
        assertTrue(NavGraph.HomeElement.ASK_BUTTON in NavGraph.HOME_ELEMENTS)
    }

    @Test
    fun allFeatureSurfacesAreDeclared() {
        val names = NavGraph.DESTINATIONS.map { it.name }
        listOf(
            "Unstuck help (two-path offer)",
            "Show Ilona (camera read/identify)",
            "Morning check-in",
            "Emergency call (armed)",
            "Puzzle game",
            "Health check (diagnostics + self-test)",
            "Caregiver dashboard / family summary",
            "Help diary",
            "Licenses & attributions",
            "Crash & error reports",
            "First-install setup",
        ).forEach { expected ->
            assertTrue("Missing destination: $expected", expected in names)
        }
        // No duplicates, no empty names.
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.none { it.isBlank() })
    }

    @Test
    fun typedHelpHasTwoPaths() {
        // Typing on Home AND typing inside Unstuck both reach the matcher.
        val typed = NavGraph.DESTINATIONS.filter { "typed" in it.name || "typing" in it.name }
        assertEquals(2, typed.size)
    }
}
