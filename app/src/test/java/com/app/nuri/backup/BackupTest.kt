package com.app.nuri.backup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
class BackupTest {
    @Test fun cryptoRoundTrip() {
        val enc = BackupCrypto.encrypt("hello".toByteArray(), "a-long-enough-passphrase")
        assertEquals("hello", String(BackupCrypto.decrypt(enc, "a-long-enough-passphrase")))
    }
    @Test fun wrongPassphraseRejected() {
        val enc = BackupCrypto.encrypt("hello".toByteArray(), "a-long-enough-passphrase")
        try { BackupCrypto.decrypt(enc, "wrong-passphrase"); fail("should throw") } catch (e: Exception) { /* expected */ }
    }
    @Test fun selfTestPasses() { assertTrue(BackupCrypto.selfTest().ok) }
    // The paper recovery phrase test was REMOVED with the feature itself
    // (Shawn rejected the phrase 2026-10-17, "too risky"). Its replacement
    // is covered by CaregiverRecoveryTest.
}
