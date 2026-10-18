package com.app.nuri.unstuck.android

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import java.util.Locale

/**
 * "Where am I?" (sweep #12): the phone's own location services answer —
 * last known fix, or a fresh coarse fix when permission allows. The
 * reassurance text speaks what is actually known (a street/area when the
 * geocoder answers; otherwise honest generality) — never an invented
 * address. Guided tier adds a caregiver alert record (the "ping" this
 * build can truthfully make; remote delivery is sweep #13's gap).
 */
object WhereAmI {
    data class PlaceAnswer(val line: String, val locality: String?, val accurate: Boolean)

    @SuppressLint("MissingPermission") // caller checks ACCESS_COARSE/FINE first; SecurityException caught regardless
    fun answer(context: Context): PlaceAnswer {
        val lm = context.getSystemService(LocationManager::class.java)
        val location: Location? = runCatching {
            val providers = listOf(LocationManager.GPS_PROVIDER, NetworkProviderCompat.NETWORK, LocationManager.PASSIVE_PROVIDER)
            providers.firstNotNullOfOrNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        }.getOrNull()
        if (location == null) {
            return PlaceAnswer(
                "I couldn't get a location reading just now. If you're at home, you're in the safest place to wait. " +
                    "Look for a street sign or a shop name and tell me what it says — that pins the place down.",
                null, accurate = false,
            )
        }
        val locality = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()?.let { a ->
                    listOfNotNull(a.thoroughfare, a.locality).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
                }
        }.getOrNull()
        return if (locality != null) {
            PlaceAnswer("You're near $locality. Stay where you are if you're unsure — we can call your caregiver and have them come to you.", locality, accurate = true)
        } else {
            PlaceAnswer("I have a rough location, but no street name for it yet. Stay where you are, and read me a street sign or a shop name if you can see one.", null, accurate = false)
        }
    }

    private object NetworkProviderCompat { const val NETWORK: String = LocationManager.NETWORK_PROVIDER }
}
