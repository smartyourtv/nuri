package com.app.nuri.unstuck

/** A family contact for one-tap calls (sweep #32): caregiver-entered at setup. */
data class FamilyContact(val name: String, val number: String, val relation: String = "")

object FamilyContacts {
    fun encode(list: List<FamilyContact>): String =
        list.joinToString(";") { c ->
            listOf(c.name, c.number, c.relation).joinToString("|") { it.replace("|", " ").replace(";", " ") }
        }

    fun decode(encoded: String): List<FamilyContact> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split(";").mapNotNull { part ->
            val b = part.split("|"); if (b.size != 3) null else FamilyContact(b[0], b[1], b[2])
        }
    }
}

/**
 * Phone-call actions (sweep #9/#32): the emergency trigger and one-tap
 * family calls place REAL calls via the platform dialer. Rules:
 *  - calling needs the CALL_PHONE runtime permission; without it the
 *    dialer opens with the number filled in (still one tap) — stated
 *    honestly in the returned plan;
 *  - 911 is never auto-dialed by Nuri: the senior always makes that call
 *    themselves (the accidental-SOS helper, catalog #87, exists because
 *    accidental emergency calls are a harm of their own).
 */
object CallActions {
    data class CallPlan(val number: String, val displayName: String, val directCall: Boolean, val note: String)

    fun emergencyPlan(contactName: String, contactNumber: String, callPermissionGranted: Boolean): CallPlan? {
        if (contactNumber.isBlank()) return null
        return CallPlan(
            number = contactNumber,
            displayName = contactName.ifBlank { "your emergency contact" },
            directCall = callPermissionGranted,
            note = if (callPermissionGranted) "Calling ${contactName.ifBlank { "your emergency contact" }} now. Stay where you are — I'm here."
            else "I've put the number in the dialer — tap the green call button to ring ${contactName.ifBlank { "your emergency contact" }}.",
        )
    }

    fun familyPlan(contact: FamilyContact, callPermissionGranted: Boolean): CallPlan = CallPlan(
        number = contact.number,
        displayName = contact.name,
        directCall = callPermissionGranted,
        note = if (callPermissionGranted) "Calling ${contact.name} now."
        else "I've put ${contact.name}'s number in the dialer — tap the green call button.",
    )
}
