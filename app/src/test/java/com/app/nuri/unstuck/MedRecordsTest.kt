package com.app.nuri.unstuck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Medication records: the log answers, the no-second-dose rule, list diffs, refills. */
class MedRecordsTest {
    private val today = listOf(MedConfirmation("2026-10-17", "8:05 AM", "Metformin"))

    @Test fun didITakeAnswersFromTheLog() {
        val answer = MedRecords.didITakeAnswer(today)
        assertTrue(answer.contains("8:05 AM"))
        assertTrue(answer.contains("Metformin"))
    }

    @Test fun noRecordNeverAdvisesASecondDose() {
        val answer = MedRecords.didITakeAnswer(emptyList())
        assertTrue(answer.contains("don't take another dose"))
        assertTrue(answer.contains("pharmacist"))
        assertFalse(answer.contains("go ahead and take", ignoreCase = true))
    }

    @Test fun missedDoseDetection() {
        val entry = MedScheduleEntry("Metformin", "Morning", dueHour = 8)
        assertTrue(MedRecords.isMissed(entry, emptyList(), nowHour = 10))
        assertFalse(MedRecords.isMissed(entry, today, nowHour = 10))
        assertFalse(MedRecords.isMissed(entry, emptyList(), nowHour = 7))
    }

    @Test fun refillLines() {
        assertTrue(MedRecords.refillLine(MedScheduleEntry("A", "Morning", daysLeft = 3))!!.contains("refill"))
        assertTrue(MedRecords.refillLine(MedScheduleEntry("A", "Morning", daysLeft = 0))!!.contains("today"))
        assertEquals(null, MedRecords.refillLine(MedScheduleEntry("A", "Morning", daysLeft = 20)))
        assertEquals(null, MedRecords.refillLine(MedScheduleEntry("A", "Morning")))
    }

    @Test fun scheduleRoundTrip() {
        val entries = listOf(MedScheduleEntry("Vitamin D", "Evening", 18, "Refill by Nov 2", 12))
        assertEquals(entries, MedRecords.decodeSchedule(MedRecords.encodeSchedule(entries)))
        assertEquals(emptyList<MedScheduleEntry>(), MedRecords.decodeSchedule("junk"))
    }

    @Test fun postHospitalDiffFlagsOnlyAndNeverDecides() {
        val diff = MedListDiff.compare(listOf("Metformin", "Lisinopril"), listOf("Metformin", "Furosemide"))
        assertEquals(listOf("Lisinopril"), diff.onlyOnOldList)
        assertEquals(listOf("Furosemide"), diff.onlyOnNewList)
        val spoken = MedListDiff.spokenSummary(diff)
        assertTrue(spoken.contains("never decide which list is right"))
        assertTrue(spoken.contains("pharmacist"))
    }

    @Test fun identicalListsProduceNoAlarm() {
        val diff = MedListDiff.compare(listOf("Aspirin"), listOf("aspirin"))
        assertTrue(diff.onlyOnOldList.isEmpty() && diff.onlyOnNewList.isEmpty())
    }
}
