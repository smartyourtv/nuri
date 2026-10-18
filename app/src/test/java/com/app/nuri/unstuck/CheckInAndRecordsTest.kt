package com.app.nuri.unstuck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Check-ins, the morning brief, welfare logic, anchors, notes, calls (sweep B/E items). */
class CheckInAndRecordsTest {
    @Test fun briefNamesUnconfirmedMeds() {
        val brief = CheckIns.composeBrief(null, listOf("Metformin", "Vitamin D"), listOf("Metformin"), null)
        assertTrue(brief.contains("Vitamin D"))
        assertTrue(brief.contains("check-in"))
    }

    @Test fun briefCelebratesAllConfirmed() {
        val record = CheckInRecord("2026-10-17", "8:00 AM", "Well", "Good", "Yes")
        val brief = CheckIns.composeBrief(record, listOf("Metformin"), listOf("Metformin"), null)
        assertTrue(brief.contains("all confirmed"))
        assertTrue(brief.contains("already checked in"))
    }

    @Test fun checkInRoundTrip() {
        val list = listOf(CheckInRecord("2026-10-17", "8:00 AM", "Well", "Good", "Yes"))
        assertEquals(list, CheckIns.decode(CheckIns.encode(list)))
        assertEquals(emptyList<CheckInRecord>(), CheckIns.decode("junk|data"))
    }

    @Test fun welfarePromptAndAlertWindows() {
        assertTrue(WelfareCheck.shouldPrompt(hoursSinceLastInteraction = 7, isWakingHour = true, alreadyPromptedToday = false))
        assertFalse(WelfareCheck.shouldPrompt(7, isWakingHour = false, alreadyPromptedToday = false))
        assertFalse(WelfareCheck.shouldPrompt(7, isWakingHour = true, alreadyPromptedToday = true))
        assertTrue(WelfareCheck.shouldAlert(promptSentMinutesAgo = 31, responded = false))
        assertFalse(WelfareCheck.shouldAlert(31, responded = true))
    }

    @Test fun anchorsAndNotesRoundTrip() {
        val anchors = listOf(RoutineAnchor("After breakfast", "A short walk"))
        assertEquals(anchors, RoutineAnchors.decode(RoutineAnchors.encode(anchors)))
        val notes = listOf(NoteRecord("2026-10-17", "9:00 AM", "story", "The boat story"))
        assertEquals(notes, NoteRecords.decode(NoteRecords.encode(notes)))
    }

    @Test fun familyContactsRoundTrip() {
        val contacts = listOf(FamilyContact("Karen", "9165550100", "daughter"))
        assertEquals(contacts, FamilyContacts.decode(FamilyContacts.encode(contacts)))
    }

    @Test fun emergencyCallPlan() {
        val plan = CallActions.emergencyPlan("Shawn", "9165550100", callPermissionGranted = true)
        assertEquals("9165550100", plan?.number)
        assertTrue(plan!!.directCall)
        val dialerPlan = CallActions.emergencyPlan("Shawn", "9165550100", callPermissionGranted = false)
        assertFalse(dialerPlan!!.directCall)
        assertTrue(dialerPlan.note.contains("dialer"))
        assertNull(CallActions.emergencyPlan("", "", true))
    }
}
