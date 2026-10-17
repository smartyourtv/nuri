package com.app.nuri.google

/** Google integrations (brief SS9): the senior already has Gmail — zero new
 *  accounts, zero new passwords. All honest not-connected states in this
 *  build; no fake contacts, calendar events, or files are ever shown. */
enum class GoogleService { DRIVE, CONTACTS, CALENDAR }
data class GoogleServiceStatus(val service: GoogleService, val connected: Boolean, val detail: String)
interface GoogleIntegration {
    val service: GoogleService
    fun status(): GoogleServiceStatus
    fun setupChecklist(): List<String>
}
abstract class NotConnectedIntegration(override val service: GoogleService, private val purpose: String) : GoogleIntegration {
    override fun status(): GoogleServiceStatus = GoogleServiceStatus(service, connected = false,
        detail = "Not connected yet. $purpose TODO(GATED-GOOGLE): sign-in wiring.")
    override fun setupChecklist(): List<String> = listOf(
        "Use the senior's existing Google account — do not make a new one.",
        "The caregiver helps with sign-in, in person.",
        "Nuri never stores, speaks, or shows the password.",
        "If sign-in is ever lost, Nuri says: ask your caregiver — they can get you back in.")
}
class DriveIntegration : NotConnectedIntegration(GoogleService.DRIVE, "Used for the quiet daily backup copy.")
class ContactsIntegration : NotConnectedIntegration(GoogleService.CONTACTS, "Used so Nuri can show a name and photo when family call.")
class CalendarIntegration : NotConnectedIntegration(GoogleService.CALENDAR, "Used for the calendar the caregiver shares (visits, appointments).")
object GoogleDiagnostics {
    fun text(): String = listOf(DriveIntegration(), ContactsIntegration(), CalendarIntegration())
        .joinToString(" ") { "${it.service.name.lowercase().replaceFirstChar { c -> c.uppercase() }}: ${it.status().detail}" }
}
