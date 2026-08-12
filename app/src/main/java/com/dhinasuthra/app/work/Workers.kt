package com.dhinasuthra.app.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.TimeUtils
import java.time.Duration
import java.time.Instant

/**
 * Background cadence (architecture.md §9): WorkManager for everything that
 * tolerates timing flexibility. No foreground service, no wake locks (§15/§32).
 */
object Workers {
    private const val CONTEXT_SAMPLE = "context_sample"
    private const val ROLLUP = "daily_rollup"
    private const val REMINDER_PLAN = "reminder_plan"

    fun scheduleAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            CONTEXT_SAMPLE, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ContextSampleWorker>(Duration.ofMinutes(15)).build()
        )
        wm.enqueueUniquePeriodicWork(
            ROLLUP, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RollupWorker>(Duration.ofHours(6)).build()
        )
        wm.enqueueUniquePeriodicWork(
            REMINDER_PLAN, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderPlanWorker>(Duration.ofHours(12)).build()
        )
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(CONTEXT_SAMPLE)
        wm.cancelUniqueWork(ROLLUP)
        wm.cancelUniqueWork(REMINDER_PLAN)
    }
}

/**
 * 15-minute fused sample: passive last location + screen-interactive + charging.
 * Screen state is a sensor, not the product (product.md §2.4): no app names,
 * no usage — only interactive yes/no timestamps (§18).
 */
class ContextSampleWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = DhinaSuthraApp.get(applicationContext)
        if (!app.container.settings.trackingActive) return Result.success()

        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val interactive = pm.isInteractive
        val batteryIntent = applicationContext.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val fix = app.container.locationProvider.lastKnown()
        app.container.contextEngine.onSample(
            loc = fix?.first, accuracyM = fix?.second,
            interactive = interactive, charging = charging, at = Instant.now()
        )
        return Result.success()
    }
}

/** Rollup yesterday (finalize) + today-so-far; prune retention windows (§20). */
class RollupWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = DhinaSuthraApp.get(applicationContext)
        val today = TimeUtils.epochDay()
        app.container.analyticsEngine.dailyRollup(today - 1)
        app.container.analyticsEngine.dailyRollup(today)
        app.container.analyticsEngine.prune(today)
        return Result.success()
    }
}

class ReminderPlanWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        DhinaSuthraApp.get(applicationContext).container.reminderScheduler.planToday()
        return Result.success()
    }
}
