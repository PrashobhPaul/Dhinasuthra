package com.dhinasuthra.app.sensing.activity

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.DsLog
import com.dhinasuthra.app.core.Permissions
import com.dhinasuthra.app.core.model.ActivityKind
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Instant

/**
 * Layer 2 (architecture.md §11.6): activity transitions are the primary
 * movement-state mechanism — battery-cheap, semantically reliable, preferred
 * over raw accelerometer streaming (§16.4).
 */
class ActivityTransitionManager(private val context: Context) {

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 1002,
        Intent(context, ActivityTransitionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    private val watched = listOf(
        DetectedActivity.STILL, DetectedActivity.WALKING, DetectedActivity.RUNNING,
        DetectedActivity.ON_BICYCLE, DetectedActivity.IN_VEHICLE
    )

    @SuppressLint("MissingPermission")
    suspend fun start() {
        if (!Permissions.hasActivityRecognition(context)) return
        val transitions = watched.flatMap { type ->
            listOf(
                ActivityTransition.Builder().setActivityType(type)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
                ActivityTransition.Builder().setActivityType(type)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build()
            )
        }
        try {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent())
                .await()
            DsLog.d("activity transitions registered")
        } catch (t: Throwable) {
            DsLog.w("activity registration failed: ${t.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun stop() {
        try {
            ActivityRecognition.getClient(context)
                .removeActivityTransitionUpdates(pendingIntent()).await()
        } catch (_: Throwable) {}
    }

    companion object {
        fun mapKind(type: Int): ActivityKind = when (type) {
            DetectedActivity.STILL -> ActivityKind.STILL
            DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> ActivityKind.WALKING
            DetectedActivity.RUNNING -> ActivityKind.RUNNING
            DetectedActivity.ON_BICYCLE -> ActivityKind.ON_BICYCLE
            DetectedActivity.IN_VEHICLE -> ActivityKind.IN_VEHICLE
            else -> ActivityKind.UNKNOWN
        }
    }
}

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val app = DhinaSuthraApp.get(context)
        if (!app.container.settings.trackingActive) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (e in result.transitionEvents) {
                    val kind = ActivityTransitionManager.mapKind(e.activityType)
                    val enter = e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
                    app.container.contextEngine.onActivity(kind, enter, Instant.now())
                }
                app.container.reminderScheduler.onContextChanged()
            } finally {
                pending.finish()
            }
        }
    }
}
