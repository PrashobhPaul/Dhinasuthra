package com.dhinasuthra.app.sensing

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import com.dhinasuthra.app.core.DsLog
import com.dhinasuthra.app.core.Permissions
import com.dhinasuthra.app.core.model.LatLon
import com.dhinasuthra.app.sensing.activity.ActivityTransitionManager
import com.dhinasuthra.app.sensing.geofence.GeofenceManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await

/**
 * Sensor Capability Registry (architecture.md §11.3): runtime inventory of what
 * this device offers. The inference engine adapts automatically when a sensor is
 * absent. M1 actively fuses location/geofence/activity/screen/charging/boot/time;
 * the raw IMU families are registered here and picked up by the policy engine in M2
 * (flagged in README — execution-directive engineering rule).
 */
class SensorCapabilityRegistry(private val context: Context) {

    data class Capability(val name: String, val available: Boolean, val batteryCost: String)

    fun snapshot(): List<Capability> {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val pm = context.packageManager
        fun has(type: Int) = sm.getDefaultSensor(type) != null
        return listOf(
            Capability("GNSS / fused location", pm.hasSystemFeature(PackageManager.FEATURE_LOCATION), "adaptive"),
            Capability("Geofencing", pm.hasSystemFeature(PackageManager.FEATURE_LOCATION), "low"),
            Capability("Activity Recognition", true, "low"),
            Capability("Accelerometer", has(Sensor.TYPE_ACCELEROMETER), "medium"),
            Capability("Gyroscope", has(Sensor.TYPE_GYROSCOPE), "medium"),
            Capability("Magnetometer", has(Sensor.TYPE_MAGNETIC_FIELD), "low"),
            Capability("Rotation vector", has(Sensor.TYPE_ROTATION_VECTOR), "medium"),
            Capability("Step detector", has(Sensor.TYPE_STEP_DETECTOR), "low"),
            Capability("Step counter", has(Sensor.TYPE_STEP_COUNTER), "low"),
            Capability("Significant motion", has(Sensor.TYPE_SIGNIFICANT_MOTION), "very low"),
            Capability("Proximity", has(Sensor.TYPE_PROXIMITY), "low"),
            Capability("Ambient light", has(Sensor.TYPE_LIGHT), "low"),
            Capability("Barometer", has(Sensor.TYPE_PRESSURE), "low"),
            Capability("Screen state", true, "none"),
            Capability("Charging state", true, "none")
        )
    }
}

/**
 * Sensor Policy Engine (architecture.md §11.4): decides what should be active.
 * M1 policy: geofences on confirmed places + activity-transition updates + a
 * 15-minute fused sample. Objective is inference quality per unit of battery —
 * no continuous raw sampling, no wake locks, no foreground service (§15).
 */
class SensorPolicyEngine(
    private val context: Context,
    private val geofenceManager: GeofenceManager,
    private val activityManager: ActivityTransitionManager
) {
    suspend fun applyCurrentPolicy() {
        if (Permissions.hasBackgroundLocation(context)) geofenceManager.syncGeofences()
        if (Permissions.hasActivityRecognition(context)) activityManager.start()
    }

    suspend fun stopAll() {
        geofenceManager.removeAll()
        activityManager.stop()
    }
}

/** Thin fused-location access. Passive `lastLocation` only in M1 (battery-free). */
class LocationProvider(private val context: Context) {
    @SuppressLint("MissingPermission")
    suspend fun lastKnown(): Pair<LatLon, Float?>? {
        if (!Permissions.hasAnyLocation(context)) return null
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val loc = client.lastLocation.await() ?: return null
            Pair(LatLon(loc.latitude, loc.longitude), if (loc.hasAccuracy()) loc.accuracy else null)
        } catch (t: Throwable) {
            DsLog.w("lastKnown failed: ${t.javaClass.simpleName}")
            null
        }
    }

    /** One active fix when context is ambiguous (policy-gated; used sparingly). */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(): Pair<LatLon, Float?>? {
        if (!Permissions.hasAnyLocation(context)) return null
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val loc = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
                ?: return null
            Pair(LatLon(loc.latitude, loc.longitude), if (loc.hasAccuracy()) loc.accuracy else null)
        } catch (t: Throwable) {
            null
        }
    }
}
