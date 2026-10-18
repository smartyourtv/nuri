package com.app.nuri.unstuck

/**
 * A point-in-time read of the phone state Nuri is allowed to check
 * (catalog: "check first"). Every field is nullable — null means "could
 * not check", and the ladder NEVER treats unknown as broken.
 */
data class DeviceStateSnapshot(
    val mediaVolume: Int? = null,
    val maxMediaVolume: Int? = null,
    val ringVolume: Int? = null,
    val maxRingVolume: Int? = null,
    val doNotDisturbOn: Boolean? = null,
    /** True when Nuri is allowed to change Do Not Disturb itself. */
    val dndChangeAllowed: Boolean = false,
    val airplaneModeOn: Boolean? = null,
    val wifiOn: Boolean? = null,
    val hasConnectivity: Boolean? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val storageFreePercent: Int? = null,
    val bluetoothOn: Boolean? = null,
    /** Plain route label: "phone speaker", "headphones", "hearing device", "Bluetooth speaker"… */
    val audioRouteLabel: String? = null,
    val flashlightOn: Boolean? = null,
    val talkBackOn: Boolean? = null,
    val fontScale: Float? = null,
    val screenBright: Boolean? = null,
) {
    val mediaMuted: Boolean get() = mediaVolume != null && mediaVolume == 0
    val mediaLow: Boolean get() = mediaVolume != null && maxMediaVolume != null && maxMediaVolume > 0 && mediaVolume * 4 < maxMediaVolume
    val ringMuted: Boolean get() = ringVolume != null && ringVolume == 0
    val batteryLow: Boolean get() = batteryPercent != null && batteryPercent in 0..19 && charging != true
    val storageFull: Boolean get() = storageFreePercent != null && storageFreePercent in 0..4
}

/** Safe, reversible fixes Nuri may apply itself at the AUTO rung. */
enum class FixAction {
    RAISE_MEDIA_VOLUME,
    RAISE_RING_VOLUME,
    TURN_OFF_DND,
    TURN_OFF_FLASHLIGHT,
    TURN_ON_BLUETOOTH,
}

/** Result of applying a fix — success is only claimed after a re-check. */
data class FixOutcome(val action: FixAction, val applied: Boolean, val detail: String)

/** What the Android layer must provide: read state, apply safe fixes. */
interface DeviceStateGateway {
    fun read(): DeviceStateSnapshot
    fun apply(action: FixAction): FixOutcome
}
