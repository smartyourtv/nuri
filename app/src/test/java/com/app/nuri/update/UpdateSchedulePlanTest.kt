package com.app.nuri.update

import com.app.nuri.update.android.UpdateSchedulePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scheduler fallback ladder (v0.3.1): steps must relax one
 * requirement at a time, from the full brief-§10 job down to the
 * session-only minimal job, and failure signatures must be stable and
 * distinct (they drive report dedupe).
 */
class UpdateSchedulePlanTest {

    @Test
    fun ladderStartsAtTheFullJob() {
        val first = UpdateSchedulePlan.STEPS.first()
        assertTrue(first.persisted && first.requiresCharging && first.unmeteredOnly)
    }

    @Test
    fun ladderEndsAtTheMinimalJob() {
        val last = UpdateSchedulePlan.STEPS.last()
        assertTrue(!last.persisted && !last.requiresCharging && !last.unmeteredOnly)
    }

    @Test
    fun eachStepRelaxesExactlyOneRequirement() {
        val steps = UpdateSchedulePlan.STEPS
        for (i in 1 until steps.size) {
            val prev = steps[i - 1]
            val cur = steps[i]
            val diffs = listOf(
                prev.persisted != cur.persisted,
                prev.requiresCharging != cur.requiresCharging,
                prev.unmeteredOnly != cur.unmeteredOnly,
            ).count { it }
            assertEquals("Step $i must relax exactly one requirement vs step ${i - 1}", 1, diffs)
        }
    }

    @Test
    fun failureSignaturesAreStableAndDistinct() {
        val a1 = UpdateSchedulePlan.failureSignature("full", "IllegalStateException")
        val a2 = UpdateSchedulePlan.failureSignature("full", "IllegalStateException")
        val b = UpdateSchedulePlan.failureSignature("full", null)
        val c = UpdateSchedulePlan.failureSignature("minimal", "IllegalStateException")
        assertEquals(a1, a2)
        assertTrue(a1 != b && a1 != c && b != c)
    }
}
