package com.app.nuri.setup

import com.app.nuri.config.NuriSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NU-5: the therapist consent-seen state must be READ and surfaced where the
 * brief intends — Therapist first-installs only, with a plain-language line.
 */
class TherapistConsentTest {

    @Test fun nonTherapistModesNeverSurface() {
        assertEquals(
            TherapistConsent.DisclosureState.NOT_THERAPIST_SETUP,
            TherapistConsent.disclosureState(null, consentSeen = true)
        )
        assertEquals(
            TherapistConsent.DisclosureState.NOT_THERAPIST_SETUP,
            TherapistConsent.disclosureState(NuriSettings.FirstInstallMode.INDEPENDENT, consentSeen = false)
        )
        assertEquals(
            TherapistConsent.DisclosureState.NOT_THERAPIST_SETUP,
            TherapistConsent.disclosureState(NuriSettings.FirstInstallMode.CAREGIVER, consentSeen = true)
        )
        assertFalse(TherapistConsent.shouldShowSharingCard(null))
        assertFalse(TherapistConsent.shouldShowSharingCard(NuriSettings.FirstInstallMode.CAREGIVER))
        assertFalse(TherapistConsent.shouldShowSharingCard(NuriSettings.FirstInstallMode.INDEPENDENT))
    }

    @Test fun therapistSeenAndNotSeen() {
        assertEquals(
            TherapistConsent.DisclosureState.SEEN,
            TherapistConsent.disclosureState(NuriSettings.FirstInstallMode.THERAPIST, consentSeen = true)
        )
        assertEquals(
            TherapistConsent.DisclosureState.NOT_YET_SEEN,
            TherapistConsent.disclosureState(NuriSettings.FirstInstallMode.THERAPIST, consentSeen = false)
        )
        assertTrue(TherapistConsent.shouldShowSharingCard(NuriSettings.FirstInstallMode.THERAPIST))
    }

    @Test fun statusTextIsPlainAndStateSpecific() {
        assertTrue(TherapistConsent.statusText(TherapistConsent.DisclosureState.SEEN).contains("read together"))
        assertTrue(TherapistConsent.statusText(TherapistConsent.DisclosureState.NOT_YET_SEEN).contains("not been marked"))
        assertEquals("", TherapistConsent.statusText(TherapistConsent.DisclosureState.NOT_THERAPIST_SETUP))
    }
}
