package app.hopnet.services.findme

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A position fix. [time] is when it was measured (wall clock, ms). */
data class Fix(val lat: Double, val lon: Double, val accuracy: Float?, val time: Long)

/**
 * This phone's position for Find Me. GPS needs no internet. Reference-counted: the radios only
 * run while something needs them (an active alert, or the Find Me screen on display).
 * All LocationManager calls happen on the main thread.
 */
class LocationTracker(private val context: Context) {
    private val lm: LocationManager? = context.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val _fix = MutableStateFlow<Fix?>(null)
    /** Best recent fix, or null until the first one. Kept after tracking stops. */
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    private var users = 0
    private var running = false // main thread only
    private val listener = LocationListenerCompat { onLocation(it) }

    fun acquire() {
        synchronized(this) { users++ }
        main.post(::sync)
    }

    fun release() {
        synchronized(this) { users = (users - 1).coerceAtLeast(0) }
        main.post(::sync)
    }

    private fun sync() {
        val wanted = synchronized(this) { users > 0 }
        if (wanted && !running) start() else if (!wanted && running) stop()
    }

    @SuppressLint("MissingPermission") // checked by hasPermission() below
    private fun start() {
        val lm = lm ?: return
        if (!hasPermission(context)) return
        val wanted = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }
        val existing = runCatching { lm.allProviders }.getOrDefault(emptyList())
        val request = LocationRequestCompat.Builder(UPDATE_MS)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(1_000)
            .build()
        val executor = ContextCompat.getMainExecutor(context)
        for (provider in wanted.filter { it in existing }) {
            try {
                lm.getLastKnownLocation(provider)
                    ?.takeIf { System.currentTimeMillis() - it.time < LAST_KNOWN_MAX_AGE_MS }
                    ?.let(::onLocation)
                // A provider that is switched off now starts delivering once Location is turned on.
                LocationManagerCompat.requestLocationUpdates(lm, provider, request, executor, listener)
                running = true
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @SuppressLint("MissingPermission") // removing our own listener; a revoked permission is caught
    private fun stop() {
        try {
            lm?.let { LocationManagerCompat.removeUpdates(it, listener) }
        } catch (_: SecurityException) {
        }
        running = false
    }

    private fun onLocation(location: Location) {
        val next = Fix(location.latitude, location.longitude, if (location.hasAccuracy()) location.accuracy else null, location.time)
        if (next.lat.isFinite() && next.lon.isFinite() && isBetter(next, _fix.value)) _fix.value = next
    }

    companion object {
        private const val UPDATE_MS = 5_000L
        private const val LAST_KNOWN_MAX_AGE_MS = 15 * 60_000L
        private const val NEWER_MS = 30_000L

        /** Prefer newer fixes, unless a slightly newer one is much less accurate. */
        internal fun isBetter(next: Fix, current: Fix?): Boolean {
            if (current == null) return true
            val newer = next.time - current.time
            if (newer > NEWER_MS) return true
            if (newer < -NEWER_MS) return false
            val nextAcc = next.accuracy ?: Float.MAX_VALUE
            val currentAcc = current.accuracy ?: Float.MAX_VALUE
            return when {
                nextAcc < currentAcc -> true
                newer > 0 -> nextAcc - currentAcc <= 50f
                else -> false
            }
        }

        fun hasPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun isEnabled(context: Context): Boolean {
            val lm = context.getSystemService(LocationManager::class.java) ?: return false
            return LocationManagerCompat.isLocationEnabled(lm)
        }

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val out = FloatArray(1)
            Location.distanceBetween(lat1, lon1, lat2, lon2, out)
            return out[0]
        }

        /** Distance in metres and initial bearing in degrees clockwise from true north (0..360). */
        fun distanceAndBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Float, Float> {
            val out = FloatArray(2)
            Location.distanceBetween(lat1, lon1, lat2, lon2, out)
            return out[0] to (out[1] + 360f) % 360f
        }

        fun batteryPercent(context: Context): Int? {
            val bm = context.getSystemService(BatteryManager::class.java) ?: return null
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        }
    }
}
