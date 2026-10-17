package com.app.nuri.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Night/Wi-Fi/charger gating for the scheduled check (brief SS10). */
class UpdateSchedulePolicyTest {

    @Test fun nightWindowBoundaries() {
        assertFalse(UpdateSchedulePolicy.isNightWindow(0))
        assertTrue(UpdateSchedulePolicy.isNightWindow(1))
        assertTrue(UpdateSchedulePolicy.isNightWindow(4))
        assertFalse(UpdateSchedulePolicy.isNightWindow(5))
        assertFalse(UpdateSchedulePolicy.isNightWindow(12))
        assertFalse(UpdateSchedulePolicy.isNightWindow(23))
    }

    @Test fun shouldRunNowRequiresAllThree() {
        assertTrue(UpdateSchedulePolicy.shouldRunNow(2, charging = true, unmeteredNetwork = true))
        assertFalse(UpdateSchedulePolicy.shouldRunNow(2, charging = false, unmeteredNetwork = true))
        assertFalse(UpdateSchedulePolicy.shouldRunNow(2, charging = true, unmeteredNetwork = false))
        assertFalse(UpdateSchedulePolicy.shouldRunNow(14, charging = true, unmeteredNetwork = true))
    }
}
