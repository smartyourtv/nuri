package com.app.nuri.unstuck.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.app.nuri.reports.android.CrashReports
import com.app.nuri.unstuck.DeviceStateGateway
import com.app.nuri.unstuck.DeviceStateSnapshot
import com.app.nuri.unstuck.FixAction
import com.app.nuri.unstuck.FixOutcome

/**
 * Real device-state reads + safe fixes (catalog "check first" / AUTO rung).
 *
 * Honesty rules (pre-build audit):
 *  - every field is read independently; one failure → null ("could not
 *    check"), never a guess;
 *  - airplane mode and Wi-Fi cannot be toggled by an app on modern
 *    Android — Nuri reads them and guides; it never claims otherwise;
 *  - DND is only changed when notification-policy access is granted;
 *  - Bluetooth device NAMES need BLUETOOTH_CONNECT on API 31+ — a
 *    SecurityException degrades to route type only (the Bluetooth-crash
 *    lesson: check first, catch always).
 */
class AndroidDeviceStateGateway(private val context: Context) : DeviceStateGateway {

    override fun read(): DeviceStateSnapshot {
        val audio = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val bm = context.getSystemService(BatteryManager::class.java)
        val am = context.getSystemService(AccessibilityManager::class.java)
        return DeviceStateSnapshot(
            mediaVolume = runCatching { audio?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull(),
            maxMediaVolume = runCatching { audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull(),
            ringVolume = runCatching { audio?.getStreamVolume(AudioManager.STREAM_RING) }.getOrNull(),
            maxRingVolume = runCatching { audio?.getStreamMaxVolume(AudioManager.STREAM_RING) }.getOrNull(),
            doNotDisturbOn = runCatching { nm?.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL }.getOrNull(),
            dndChangeAllowed = runCatching { nm?.isNotificationPolicyAccessGranted == true }.getOrDefault(false),
            airplaneModeOn = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 }.getOrNull(),
            wifiOn = wifiState(),
            hasConnectivity = hasConnectivity(),
            batteryPercent = runCatching { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrNull()?.takeIf { it in 0..100 },
            charging = runCatching { bm?.isCharging }.getOrNull(),
            storageFreePercent = storageFreePercent(),
            bluetoothOn = bluetoothOn(),
            audioRouteLabel = audioRouteLabel(audio),
            flashlightOn = null, // torch state is per-camera callback data; the reader reports it via UnstuckViewModel when available
            talkBackOn = talkBackOn(am),
            fontScale = runCatching { context.resources.configuration.fontScale }.getOrNull(),
            screenBright = null,
        )
    }

    private fun wifiState(): Boolean? = runCatching {
        val wm = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        @Suppress("DEPRECATION") wm?.isWifiEnabled
    }.getOrNull()

    private fun hasConnectivity(): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null
    }.getOrNull()

    private fun storageFreePercent(): Int? = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes; if (total <= 0) null else ((stat.availableBytes * 100) / total).toInt()
    }.getOrNull()

    private fun bluetoothOn(): Boolean? = runCatching {
        @Suppress("MissingPermission") // isEnabled needs no permission; adapter access itself is guarded
        android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled
    }.getOrNull()

    private fun audioRouteLabel(audio: AudioManager?): String? = runCatching {
        if (audio == null) return null
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        when {
            devices.any { it.type == android.media.AudioDeviceInfo.TYPE_HEARING_AID || it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET } -> "hearing device"
            devices.any { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO } -> "Bluetooth speaker or earbuds"
            devices.any { it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES } -> "headphones"
            devices.any { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } -> "phone speaker"
            else -> null
        }
    }.getOrNull()

    private fun talkBackOn(am: AccessibilityManager?): Boolean? = runCatching {
        if (am == null) return null
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
            .any { it.id.contains("talkback", ignoreCase = true) }
    }.getOrNull()

    override fun apply(action: FixAction): FixOutcome {
        val audio = context.getSystemService(AudioManager::class.java)
        return when (action) {
            FixAction.RAISE_MEDIA_VOLUME -> runCatching {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val target = maxOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC), (max * 0.6).toInt().coerceAtLeast(1))
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                FixOutcome(action, now == target, "Media volume is now $now of $max.")
            }.getOrElse { e -> CrashReports.recordError("AUTO fix volume", e); FixOutcome(action, false, "I couldn't change the volume myself.") }
            FixAction.RAISE_RING_VOLUME -> runCatching {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_RING)
                val target = maxOf(audio.getStreamVolume(AudioManager.STREAM_RING), (max * 0.7).toInt().coerceAtLeast(1))
                audio.setStreamVolume(AudioManager.STREAM_RING, target, 0)
                val now = audio.getStreamVolume(AudioManager.STREAM_RING)
                FixOutcome(action, now == target, "Ring volume is now $now of $max.")
            }.getOrElse { e -> CrashReports.recordError("AUTO fix ring", e); FixOutcome(action, false, "I couldn't change the ring volume myself.") }
            FixAction.TURN_OFF_DND -> runCatching {
                val nm = context.getSystemService(android.app.NotificationManager::class.java)
                if (nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                    val off = nm.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                    FixOutcome(action, off, if (off) "Do Not Disturb is off." else "Do Not Disturb would not switch off.")
                } else FixOutcome(action, false, "I don't have permission to switch Do Not Disturb off myself — I'll walk you through it.")
            }.getOrElse { e -> CrashReports.recordError("AUTO fix DND", e); FixOutcome(action, false, "I couldn't switch Do Not Disturb off myself.") }
            FixAction.TURN_OFF_FLASHLIGHT -> runCatching {
                val cm = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull { cid ->
                    cm.getCameraCharacteristics(cid).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (id == null) FixOutcome(action, false, "This phone's light can't be reached from here.")
                else { cm.setTorchMode(id, false); FixOutcome(action, true, "The flashlight is off.") }
            }.getOrElse { e -> CrashReports.recordError("AUTO fix flashlight", e); FixOutcome(action, false, "I couldn't turn the light off myself.") }
            FixAction.TURN_ON_BLUETOOTH -> FixOutcome(action, false, "Bluetooth can only be switched on by you — I'll walk you through it.")
        }
    }
}
