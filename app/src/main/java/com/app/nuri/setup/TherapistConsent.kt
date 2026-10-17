package com.app.nuri.setup

import com.app.nuri.config.NuriSettings

/**
 * Therapist-mode consent visibility (brief SS5; bug-hunt NU-5 — the stored
 * consent-seen state was write-only, so nothing ever surfaced it).
 *
 * Brief intent: in Therapist first-installs, sharing is DISCLOSED, never
 * hidden. The client sees exactly what is shared before anything is turned
 * on, and can ask "What do you share?" any time afterwards. This object is
 * the single decision both surfaces consult:
 *  - the first-install therapist flow shows the state inside the consent
 *    card and records it when the list is read together;
 *  - Settings shows the "What Nuri shares" card (sharing list + this state)
 *    for Therapist installs only, where it can be read and marked seen.
 *
 * Pure and unit-tested; the screens render [statusText] verbatim so the
 * wording lives in exactly one place.
 */
object TherapistConsent {

    enum class DisclosureState { NOT_THERAPIST_SETUP, SEEN, NOT_YET_SEEN }

    fun disclosureState(
        firstInstallMode: NuriSettings.FirstInstallMode?,
        consentSeen: Boolean
    ): DisclosureState = when {
        firstInstallMode != NuriSettings.FirstInstallMode.THERAPIST -> DisclosureState.NOT_THERAPIST_SETUP
        consentSeen -> DisclosureState.SEEN
        else -> DisclosureState.NOT_YET_SEEN
    }

    /** The Settings sharing card exists only for Therapist first-installs. */
    fun shouldShowSharingCard(firstInstallMode: NuriSettings.FirstInstallMode?): Boolean =
        firstInstallMode == NuriSettings.FirstInstallMode.THERAPIST

    /** Plain-language status line, sized by the screens per the readability law (SS3). */
    fun statusText(state: DisclosureState): String = when (state) {
        DisclosureState.SEEN ->
            "This list was read together during setup on this phone, and marked as read."
        DisclosureState.NOT_YET_SEEN ->
            "This list has not been marked as read together yet. Please read it together now."
        DisclosureState.NOT_THERAPIST_SETUP -> ""
    }
}
