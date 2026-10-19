package com.app.nuri.update

/** Updater (brief SS10 + standing updater law). Updates are user-initiated,
 *  interface-frozen (an update NEVER changes layout or voice — this checker
 *  only ever reads/writes version fields), with ONE subtle notification per
 *  version: dismiss = no repeat until the next version. In Managed mode the
 *  caregiver applies updates. */
data class VersionManifest(val versionCode: Int, val versionName: String)
interface VersionManifestFetcher { fun fetch(): VersionManifest? } // null = could not check (offline / not wired)
class NotWiredManifestFetcher : VersionManifestFetcher {
    override fun fetch(): VersionManifest? = null // TODO: point at the Nuri release manifest when distribution is decided.
}
data class UpdateDecision(val updateAvailable: Boolean, val shouldNotify: Boolean, val detail: String)
object UpdateChecker {
    fun decide(currentVersionCode: Int, manifest: VersionManifest?, lastNotifiedVersionCode: Int, dismissedVersionCode: Int): UpdateDecision {
        if (manifest == null) return UpdateDecision(false, false, "Could not check for updates right now. Nothing was changed.")
        if (manifest.versionCode <= currentVersionCode) return UpdateDecision(false, false, "Ilona is up to date.")
        val alreadyHandled = lastNotifiedVersionCode >= manifest.versionCode || dismissedVersionCode >= manifest.versionCode
        return UpdateDecision(true, shouldNotify = !alreadyHandled,
            detail = if (alreadyHandled) "A newer Ilona is ready when you are. You already saw this notice, so it will not keep asking."
                     else "A newer Ilona is ready. It looks and sounds exactly the same — updates never change how Ilona works or sounds.")
    }
    fun applyPolicyText(managedMode: Boolean): String =
        if (managedMode) "In this setup, your caregiver looks after updates for you." else "Tap Update only when you want it. Nothing installs by itself."
}
