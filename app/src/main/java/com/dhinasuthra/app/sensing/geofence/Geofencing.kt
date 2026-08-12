package com.dhinasuthra.app.sensing.geofence

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.DsLog
import com.dhinasuthra.app.core.Permissions
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Instant

/**
 * Layer 1 of the location architecture (architecture.md §11.6): geofences on
 * confirmed places replace constant GPS polling. Radius floors at 150 m,
 * responsiveness relaxed to 2 min to reduce wakeups (§32).
 */
class GeofenceManager(private val context: Context) {

    private val client = LocationServices.getGeofencingClient(context)

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 1001,
        Intent(context, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    @SuppressLint("MissingPermission")
    suspend fun syncGeofences() {
        if (!Permissions.hasBackgroundLocation(context)) return
        val app = DhinaSuthraApp.get(context)
        val places = app.container.db.placeDao().confirmed()
        try {
            client.removeGeofences(pendingIntent()).await()
            if (places.isEmpty()) return
            val fences = places.map { p ->
                Geofence.Builder()
                    .setRequestId("place_${p.id}")
                    .setCircularRegion(p.lat, p.lon, maxOf(150f, p.radiusM))
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setNotificationResponsiveness(120_000)
                    .build()
            }
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(fences)
                .build()
            client.addGeofences(request, pendingIntent()).await()
            DsLog.d("geofences synced: ${fences.size}")
        } catch (t: Throwable) {
            DsLog.w("geofence sync failed: ${t.javaClass.simpleName}")
        }
    }

    suspend fun removeAll() {
        try { client.removeGeofences(pendingIntent()).await() } catch (_: Throwable) {}
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) { DsLog.w("geofence error ${event.errorCode}"); return }
        val entering = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
        val ids = event.triggeringGeofences?.mapNotNull { it.requestId.removePrefix("place_").toLongOrNull() }
            ?: return
        val app = DhinaSuthraApp.get(context)
        if (!app.container.settings.trackingActive) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ids.forEach { app.container.contextEngine.onGeofence(it, entering, Instant.now()) }
                app.container.reminderScheduler.onContextChanged()
            } finally {
                pending.finish()
            }
        }
    }
}
