package com.app.nuri.update

/**
 * The install-session status → action mapping (brief SS10), as a
 * pure function so the one-tap fallback branch is provable in JVM
 * tests (order §B2). The Android receiver translates
 * PackageInstaller's integer status into [InstallStatus] and acts
 * on the returned [InstallAction] — behavior unchanged from when
 * the mapping lived inline in the receiver.
 */
enum class InstallStatus { PENDING_USER_ACTION, SUCCESS, FAILED }

enum class InstallAction {
    /** The device did not honor silent install: surface the system prompt (the one-tap fallback). */
    SHOW_SYSTEM_PROMPT,

    /** The install landed: record it; the next launch runs the self-test + after-note. */
    MARK_INSTALLED,

    /** Failed/abandoned: clear the pending marker and alert the caregiver; the current version keeps running. */
    CLEAR_AND_ALERT,
}

object InstallStatusMapping {
    fun actionFor(status: InstallStatus): InstallAction = when (status) {
        InstallStatus.PENDING_USER_ACTION -> InstallAction.SHOW_SYSTEM_PROMPT
        InstallStatus.SUCCESS -> InstallAction.MARK_INSTALLED
        InstallStatus.FAILED -> InstallAction.CLEAR_AND_ALERT
    }
}
