package com.dhinasuthra.app.sensing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.DsLog
import com.dhinasuthra.app.core.database.RawSensorEventEntity
import com.dhinasuthra.app.core.model.SensorSignalType
import com.dhinasuthra.app.work.Workers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reboot recovery (architecture.md §31): reload rules → recalculate → reschedule.
 * Stale reminders are never blindly restored — ReminderScheduler replans from
 * patterns + today's actual state.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_TIMEZONE_CHANGED) return
        val app = DhinaSuthraApp.get(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.container.db.rawSensorEventDao().insert(
                    RawSensorEventEntity(
                        timestamp = System.currentTimeMillis(),
                        type = if (action == Intent.ACTION_BOOT_COMPLETED)
                            SensorSignalType.BOOT else SensorSignalType.TIMEZONE_CHANGE
                    )
                )
                if (app.container.settings.trackingActive) {
                    app.container.sensorPolicyEngine.applyCurrentPolicy()
                    Workers.scheduleAll(context)
                    app.container.reminderScheduler.planToday()
                }
                DsLog.d("boot recovery complete")
            } finally {
                pending.finish()
            }
        }
    }
}
