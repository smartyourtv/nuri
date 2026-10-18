package com.app.nuri.backup

import com.app.nuri.config.NuriSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sweep #27 (caregiver-mediated recovery) + #28 (snapshot rotation). */
class CaregiverRecoveryTest {
    @Test fun authorityDependsOnAccessMode() {
        assertEquals(RestoreAuthority.CAREGIVER, CaregiverMediatedRecovery.authorityFor(NuriSettings.AccessMode.MANAGED))
        assertEquals(RestoreAuthority.CAREGIVER, CaregiverMediatedRecovery.authorityFor(NuriSettings.AccessMode.HYBRID))
        assertEquals(RestoreAuthority.RECOVERY_CONTACT, CaregiverMediatedRecovery.authorityFor(NuriSettings.AccessMode.INDEPENDENT))
    }

    @Test fun restoreNeedsBothSignInAndApproval() {
        val mode = NuriSettings.AccessMode.HYBRID
        assertFalse(CaregiverMediatedRecovery.decide(mode, googleSignedIn = false, authorityApprovedFromOwnDevice = true).allowed)
        assertFalse(CaregiverMediatedRecovery.decide(mode, googleSignedIn = true, authorityApprovedFromOwnDevice = false).allowed)
        assertTrue(CaregiverMediatedRecovery.decide(mode, googleSignedIn = true, authorityApprovedFromOwnDevice = true).allowed)
    }

    @Test fun statusTextNeverMentionsAPhraseKey() {
        val text = CaregiverMediatedRecovery.statusText(NuriSettings.AccessMode.INDEPENDENT)
        assertTrue(text.contains("recovery contact"))
        assertTrue(text.contains("No paper phrase exists"))
    }

    @Test fun snapshotRotationKeepsNewestThree() {
        val snaps = (1..6).map { SnapshotInfo(it * 1000L, "s$it", 10) }
        val prune = SnapshotRotation.toPrune(snaps)
        assertEquals(3, prune.size)
        assertEquals(listOf(1000L, 2000L, 3000L), prune.map { it.epochMs }.sorted())
    }

    @Test fun weeklyDueLogic() {
        val now = 100L * 24 * 60 * 60 * 1000
        assertTrue(SnapshotRotation.weeklyDue(emptyList(), now))
        assertTrue(SnapshotRotation.weeklyDue(listOf(SnapshotInfo(now - 8L * 24 * 60 * 60 * 1000, "old", 1)), now))
        assertFalse(SnapshotRotation.weeklyDue(listOf(SnapshotInfo(now - 1L * 24 * 60 * 60 * 1000, "new", 1)), now))
    }
}
