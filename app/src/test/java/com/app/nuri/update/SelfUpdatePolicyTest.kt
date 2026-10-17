package com.app.nuri.update

import com.app.nuri.config.NuriSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfUpdatePolicyTest {

    @Test fun privateFlavorAlwaysAuto() {
        assertTrue(SelfUpdatePolicy.autoUpdateEnabled(true, NuriSettings.AccessMode.INDEPENDENT, null))
        assertTrue(SelfUpdatePolicy.autoUpdateEnabled(true, NuriSettings.AccessMode.INDEPENDENT, false))
    }

    @Test fun managedModeAuto() {
        assertTrue(SelfUpdatePolicy.autoUpdateEnabled(false, NuriSettings.AccessMode.MANAGED, null))
        assertTrue(SelfUpdatePolicy.autoUpdateEnabled(false, NuriSettings.AccessMode.MANAGED, false))
    }

    @Test fun publicIndependentDefaultsOffAndToggleDecides() {
        assertFalse(SelfUpdatePolicy.autoUpdateEnabled(false, NuriSettings.AccessMode.INDEPENDENT, null))
        assertFalse(SelfUpdatePolicy.autoUpdateEnabled(false, NuriSettings.AccessMode.HYBRID, null))
        assertTrue(SelfUpdatePolicy.autoUpdateEnabled(false, NuriSettings.AccessMode.INDEPENDENT, true))
        assertFalse(SelfUpdatePolicy.autoUpdateEnabled(false, NuriSettings.AccessMode.HYBRID, false))
    }

    @Test fun newerOnlyRule() {
        val offer = UpdateOffer(10, "1.10", "https://example.invalid/nuri.apk", "abc", 1000)
        assertTrue(SelfUpdatePolicy.isNewer(9, offer))
        assertFalse(SelfUpdatePolicy.isNewer(10, offer))
        assertFalse(SelfUpdatePolicy.isNewer(11, offer))
    }

    @Test fun sha256KnownVector() {
        // SHA-256("abc") — well-known test vector.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ApkVerification.sha256Hex("abc".toByteArray(Charsets.UTF_8))
        )
    }

    @Test fun refusalGate() {
        // Feed hash = full SHA-256 of "abc", uppercased (hash compare is case-insensitive).
        val offer = UpdateOffer(10, "1.10", "https://example.invalid/nuri.apk",
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", 3)
        val goodHash = ApkVerification.sha256Hex("abc".toByteArray(Charsets.UTF_8))
        // All good (feed hash case-insensitively matches "abc", certs match, newer) -> no refusal.
        assertNull(ApkVerification.refusalReason(9, offer, goodHash, "CERT1", "cert1"))
        // Downgrade refused first.
        assertTrue(ApkVerification.refusalReason(10, offer, goodHash, "CERT1", "cert1")!!.contains("not newer"))
        // Hash mismatch refused.
        assertTrue(ApkVerification.refusalReason(9, offer, "00", "CERT1", "cert1")!!.contains("fingerprint"))
        // Cert mismatch refused.
        assertTrue(ApkVerification.refusalReason(9, offer, goodHash, "CERT1", "OTHER")!!.contains("signed"))
    }
}
