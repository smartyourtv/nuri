package com.app.nuri.remote

/**
 * Single-phone test mode (design §11) — the pure plan. The Android
 * layer executes these steps against two real Firebase identities;
 * the logic that must never drift is here and JVM-tested:
 *
 * - a test household is a NORMAL household marked test=true;
 * - both roles pair through the real [PairingManager] flow;
 * - exit deletes only what the published rules allow a delete on,
 *   marks the test household closed, and touches NOTHING else.
 */
object TestHouseholdPlan {

    /** Named FirebaseApp instance for the caregiver role in test mode (§11.1). */
    const val TEST_APP_NAME = "ilona-test-caregiver"

    /** Collections whose documents exit may delete, with the deleting role (rules §9). */
    val EXIT_DELETIONS: List<Pair<String, String>> = listOf(
        "events" to "CAREGIVER",
        "alerts" to "CAREGIVER",
        "pairing" to "CAREGIVER",
    )

    /**
     * Documents exit CANNOT delete under the published rules
     * (deletes are denied there by design): they stay behind as
     * inert ciphertext under a closed test household. Stated
     * plainly so the status record never overclaims a clean exit.
     */
    val EXIT_RESIDUE: List<String> = listOf(
        "households/{id} (marked closed by the caregiver identity)",
        "households/{id}/status/today (ciphertext; household closed)",
        "households/{id}/shadow/current (ciphertext; household closed)",
        "households/{id}/shadowAck/latest (ciphertext; household closed)",
    )

    /** A binding is a test binding only when it says so — real bindings are never test bindings. */
    fun isTestBinding(binding: HouseholdBinding?): Boolean = binding?.test == true

    /**
     * Exit safety: exiting test mode must leave a real binding
     * bit-for-bit untouched. Returns true when [real] survives
     * exiting [test] — always, by construction (separate ids, keys,
     * and state); the regression test pins it (§11.3).
     */
    fun realBindingSurvivesExit(real: HouseholdBinding?, test: HouseholdBinding?): Boolean {
        if (real == null) return true
        if (test == null) return true
        return real.householdId != test.householdId && !real.test
    }
}
