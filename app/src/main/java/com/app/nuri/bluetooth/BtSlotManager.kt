package com.app.nuri.bluetooth

import com.app.nuri.config.NuriSettings

/** One remembered Bluetooth speaker / device slot (brief SS9). */
data class BtSlot(val address: String, val name: String, val lastSeenEpochMs: Long)

/**
 * 10-slot remembered-device logic — pure Kotlin, unit-testable.
 * Most-recently-used first; when full, the least-recently-used slot is
 * evicted. The caregiver manages this list (name + last seen, delete defunct).
 * Actual Bluetooth pairing/routing is hardware work: see capabilities below.
 */
class BtSlotManager(initial: List<BtSlot> = emptyList(), private val capacity: Int = NuriSettings.BLUETOOTH_SLOT_COUNT) {
    private val slots = initial.sortedByDescending { it.lastSeenEpochMs }.take(capacity).toMutableList()
    fun listMruFirst(): List<BtSlot> = slots.toList()
    /** Record a sighting/connection; returns the evicted slot if one was pushed out. */
    fun touch(address: String, name: String, nowEpochMs: Long): BtSlot? {
        val existing = slots.firstOrNull { it.address == address }
        if (existing != null) { slots.remove(existing); slots.add(0, existing.copy(name = name.ifBlank { existing.name }, lastSeenEpochMs = nowEpochMs)); return null }
        slots.add(0, BtSlot(address, name, nowEpochMs))
        return if (slots.size > capacity) slots.removeAt(slots.lastIndex) else null
    }
    fun remove(address: String): Boolean = slots.removeAll { it.address == address }
    fun encode(): String = slots.joinToString(";") { "${it.address}|${it.name.replace("|"," ").replace(";"," ")}|${it.lastSeenEpochMs}" }
    companion object {
        fun decode(encoded: String): BtSlotManager {
            if (encoded.isBlank()) return BtSlotManager()
            val parsed = encoded.split(";").mapNotNull { part ->
                val bits = part.split("|"); if (bits.size != 3) null else BtSlot(bits[0], bits[1], bits[2].toLongOrNull() ?: 0L)
            }
            return BtSlotManager(parsed)
        }
    }
}

/** Capability flags — honest: hardware paths are not implemented in this build. */
interface HearingAidStreaming { fun isSupported(): Boolean; fun statusText(): String }
class AshaNotImplemented : HearingAidStreaming {
    override fun isSupported(): Boolean = false
    override fun statusText(): String = "Hearing-aid streaming (ASHA): not built yet. It needs a hearing-aid device to test with."
}
interface BleTrackerSupport { fun isSupported(): Boolean; fun statusText(): String }
class BleTrackerNotImplemented : BleTrackerSupport {
    override fun isSupported(): Boolean = false
    override fun statusText(): String = "'Where are my keys' trackers (Bluetooth tags): not built yet. It needs a tracker tag to test with."
}
object BtDiagnostics {
    fun text(manager: BtSlotManager, asha: HearingAidStreaming = AshaNotImplemented(), trackers: BleTrackerSupport = BleTrackerNotImplemented()): String {
        val n = manager.listMruFirst().size
        return "Bluetooth speakers remembered: $n of ${NuriSettings.BLUETOOTH_SLOT_COUNT}. " +
            "Pairing and sound routing need the real speaker to test with — not built yet. " + asha.statusText() + " " + trackers.statusText()
    }
}
