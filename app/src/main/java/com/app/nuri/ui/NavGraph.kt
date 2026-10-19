package com.app.nuri.ui

/**
 * The navigation graph as DATA (v0.3.1) — the reachability contract.
 *
 * Shawn's on-device report on v0.3.0 ("bare, no help features, nowhere
 * to type") happened because features existed in code but weren't
 * VISIBLE from where a senior actually looks. This object is the single
 * declaration of every feature surface and the exact tap path that
 * reaches it from a cold start, and [com.app.nuri.ui.NavGraphTest]
 * asserts the contract in CI: every destination exists, every path is
 * ≤ [MAX_TAPS] taps, and Home carries the help-first entry points.
 *
 * MainActivity's wiring (3 tabs + sub-screen swaps) and the screens'
 * buttons must match this declaration; when a surface is added, it is
 * added HERE first, with its path.
 */
object NavGraph {

    const val MAX_TAPS = 3

    /** What Home itself must carry (the help-first contract, v0.3.1). */
    enum class HomeElement { HELP_BUTTON, SHOW_NURI_BUTTON, TEXT_INPUT, ASK_BUTTON, CHECKIN_BUTTON, EMERGENCY_BUTTON, GAME_BUTTON }

    val HOME_ELEMENTS: Set<HomeElement> = setOf(
        HomeElement.HELP_BUTTON,
        HomeElement.SHOW_NURI_BUTTON,
        HomeElement.TEXT_INPUT,
        HomeElement.ASK_BUTTON,
        HomeElement.CHECKIN_BUTTON,
        HomeElement.EMERGENCY_BUTTON,
        HomeElement.GAME_BUTTON,
    )

    /**
     * A feature surface and its tap path from cold start. Each path entry
     * is one tap (a tab tap, a button tap, or opening a sub-screen);
     * scrolling is not a tap.
     */
    data class Destination(val name: String, val path: List<String>)

    val DESTINATIONS: List<Destination> = listOf(
        Destination("Home", listOf()),
        Destination("Unstuck help (two-path offer)", listOf("Home: I need help")),
        Destination("Unstuck describe-and-match (typed)", listOf("Home: I need help", "Unstuck: Tell Ilona what's happening")),
        Destination("Unstuck from Home typing", listOf("Home: type + Ask Ilona")),
        Destination("Show Ilona (camera read/identify)", listOf("Home: Show Ilona")),
        Destination("Morning check-in", listOf("Home: Morning check-in")),
        Destination("Emergency call (armed)", listOf("Home: Call for help")),
        Destination("Family one-tap calls", listOf("Home: Call <family member>")),
        Destination("Puzzle game", listOf("Home: Puzzle game")),
        Destination("Health check (diagnostics + self-test)", listOf("Tab: Health check")),
        Destination("Settings", listOf("Tab: Settings")),
        Destination("Caregiver dashboard / family summary", listOf("Tab: Settings", "Settings: Family summary")),
        Destination("Help diary", listOf("Tab: Settings", "Settings: See the help diary")),
        Destination("Licenses & attributions", listOf("Tab: Settings", "Settings: Licenses & attributions")),
        Destination("Full third-party notices", listOf("Tab: Settings", "Settings: Licenses & attributions", "Licenses: Read the full notices")),
        Destination("About Ilona — safety information", listOf("Tab: Settings", "Settings: scroll to About Ilona")),
        Destination("Terms of use", listOf("Tab: Settings", "Settings: Terms of use")),
        Destination("Show Ilona closer-look key (caregiver)", listOf("Tab: Settings", "Settings: scroll to Family & help setup")),
        Destination("Crash & error reports", listOf("Tab: Settings", "Settings: See crash & error reports")),
        Destination("Family & help setup (caregiver name, safe word, spouse response, emergency contact, medicines, snapshots)", listOf("Tab: Settings", "Settings: scroll to Family & help setup")),
        Destination("First-install setup", listOf("First launch before setup is completed")),
    )

    fun unreachable(maxTaps: Int = MAX_TAPS): List<Destination> =
        DESTINATIONS.filter { it.path.size > maxTaps }
}
