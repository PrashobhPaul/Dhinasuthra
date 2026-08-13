package com.dhinasuthra.app.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.R
import com.dhinasuthra.app.core.DsLog
import com.dhinasuthra.app.core.Permissions
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.database.IntentionalDeviationEntity
import com.dhinasuthra.app.core.database.ReminderInstanceEntity
import com.dhinasuthra.app.core.database.ReminderRuleEntity
import com.dhinasuthra.app.core.model.DayType
import com.dhinasuthra.app.core.model.NotificationPrivacy
import com.dhinasuthra.app.core.model.ReminderResponse
import com.dhinasuthra.app.core.model.ReminderSeverity
import com.dhinasuthra.app.core.model.RoutineEventType
import com.dhinasuthra.app.routine.RoutineStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Notification engine — local only (architecture.md §9/§30): works with Wi-Fi off,
 * data off, device offline. Uses inexact AlarmManager (no SCHEDULE_EXACT_ALARM —
 * graceful degradation per §9) and re-checks actual context before firing so a
 * completed routine never generates noise. A successful day may produce zero
 * notifications (§86).
 */
object NotificationChannels {
    const val REMINDERS = "routine_reminders"
    const val INSIGHTS = "insights"

    fun create(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                REMINDERS,
                context.getString(R.string.notif_channel_reminders),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.notif_channel_reminders_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                INSIGHTS,
                context.getString(R.string.notif_channel_insights),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = context.getString(R.string.notif_channel_insights_desc) }
        )
    }
}

/** Brand-voice copy (product.md §42/§62): assistant, never alarm. */
object ReminderCopy {
    fun title(type: RoutineEventType, severity: ReminderSeverity): String = when (type) {
        RoutineEventType.LEAVE_HOME -> "Time for your office journey?"
        RoutineEventType.LUNCH -> "Lunch is usually around now"
        RoutineEventType.LEAVE_OFFICE -> "You normally leave office around now"
        RoutineEventType.SLEEP -> "Winding down?"
        RoutineEventType.WAKE -> "Good morning"
        RoutineEventType.SHORT_BREAK -> "Short break?"
        else -> "Routine reminder"
    }.let { if (severity == ReminderSeverity.SIGNIFICANT) "$it ·" else it }

    fun body(type: RoutineEventType, severity: ReminderSeverity, medianMin: Int): String {
        val t = TimeUtils.formatMinuteOfDay(RoutineStats.denormalize(medianMin))
        val base = when (type) {
            RoutineEventType.LEAVE_HOME -> "You usually start your office journey around $t."
            RoutineEventType.LUNCH -> "You usually take lunch around $t."
            RoutineEventType.LEAVE_OFFICE -> "Your usual departure is around $t."
            RoutineEventType.SLEEP -> "You're running later than your usual sleep window (around $t)."
            RoutineEventType.WAKE -> "You're later than your usual start (around $t)."
            RoutineEventType.SHORT_BREAK -> "You usually take a brief break around this time."
            else -> "Around $t is your usual time."
        }
        return if (severity == ReminderSeverity.SIGNIFICANT)
            "$base Today looks different from your normal routine." else base
    }
}

class ReminderScheduler(private val context: Context) {

    private val remindable = listOf(
        RoutineEventType.LEAVE_HOME, RoutineEventType.LUNCH,
        RoutineEventType.LEAVE_OFFICE, RoutineEventType.SLEEP, RoutineEventType.WAKE
    )

    suspend fun ensureDefaultRules() {
        val dao = DhinaSuthraApp.get(context).container.db.reminderDao()
        val existing = dao.rules().map { it.eventType }.toSet()
        for (t in remindable) {
            if (t !in existing) {
                dao.upsertRule(ReminderRuleEntity(eventType = t, enabled = true, cooldownMin = 90, userConfigured = false))
            }
        }
    }

    /** Plan today's reminders from learned patterns (§29). Idempotent: cancels then reschedules. */
    suspend fun planToday() {
        val app = DhinaSuthraApp.get(context)
        if (!app.container.settings.trackingActive) { cancelAll(); return }
        ensureDefaultRules()
        val today = TimeUtils.epochDay()
        val dayType = TimeUtils.dayType(today)
        val patternDao = app.container.db.routinePatternDao()
        val reminderDao = app.container.db.reminderDao()
        val rules = reminderDao.rules().associateBy { it.eventType }
        val deviations = reminderDao.deviationsForDay(today)
        val wholeDayDeviation = deviations.any { it.eventType == null }
        val doneToday = app.container.db.routineEventDao().forDay(today).map { it.eventType }.toSet()
        val now = System.currentTimeMillis()

        cancelAll()
        if (wholeDayDeviation) return

        // RMD-07 — the learned sleep window is quiet hours; nothing is scheduled inside it.
        val sleepPattern = patternDao.byKey(RoutineEventType.SLEEP, dayType)
        val wakePattern = patternDao.byKey(RoutineEventType.WAKE, dayType)

        var scheduled = 0
        for (type in remindable) {
            if (scheduled >= DAILY_BUDGET) break        // RMD-10
            val rule = rules[type] ?: continue
            if (!rule.enabled) continue
            if (type in doneToday) continue             // RMD-02
            if (deviations.any { it.eventType == type }) continue
            val p = patternDao.byKey(type, dayType) ?: continue
            // RMD-01 — low confidence stays silent, unless the user explicitly stated
            // this time themselves, which is usable for prompting immediately.
            val observedEnough = p.confidence >= 0.55f && p.observationCount >= 5
            val userPriorBacked = p.priorMin != null && p.observationCount < 5
            if (!observedEnough && !userPriorBacked) continue

            val history = reminderDao.recentFor(type, DISMISSAL_HISTORY)
            // RMD-03 — respect the cooldown since this type last actually fired.
            val lastFired = history.mapNotNull { it.firedAt }.maxOrNull()
            if (lastFired != null && now - lastFired < rule.cooldownMin * 60_000L) continue
            // RMD-05 — repeated dismissal is an answer; stop asking.
            val recentFired = history.filter { it.firedAt != null }.take(DISMISSAL_STREAK)
            if (recentFired.size >= DISMISSAL_STREAK &&
                recentFired.all { it.response == ReminderResponse.DISMISSED }
            ) continue

            val q = com.dhinasuthra.app.core.model.Quantiles(p.medianMin, p.p10, p.p25, p.p75, p.p90)
            val gentleMin = p.p90 + RoutineStats.gentleToleranceMin(q)
            val significantMin = gentleMin + RoutineStats.significantLagMin()
            if (isQuietHour(gentleMin, type, sleepPattern, wakePattern)) continue

            scheduleAt(type, ReminderSeverity.GENTLE, TimeUtils.instantAt(today, gentleMin), now)
            if (!isQuietHour(significantMin, type, sleepPattern, wakePattern)) {
                scheduleAt(type, ReminderSeverity.SIGNIFICANT, TimeUtils.instantAt(today, significantMin), now)
            }
            scheduled++
        }
        DsLog.d("reminders planned for day $today ($scheduled of $DAILY_BUDGET budget)")
    }

    /**
     * RMD-07 — a prompt that would land inside the learned sleep window is suppressed.
     * Sleep and wake prompts are exempt: those are the two whose whole purpose is to
     * sit at the edges of that window.
     */
    private fun isQuietHour(
        minuteOfDay: Int,
        type: RoutineEventType,
        sleep: com.dhinasuthra.app.core.database.RoutinePatternEntity?,
        wake: com.dhinasuthra.app.core.database.RoutinePatternEntity?
    ): Boolean {
        if (type == RoutineEventType.SLEEP || type == RoutineEventType.WAKE) return false
        val sleepStart = sleep?.let { RoutineStats.denormalize(it.medianMin) } ?: return false
        val wakeTime = wake?.let { RoutineStats.denormalize(it.medianMin) } ?: DEFAULT_WAKE_MIN
        val minute = ((minuteOfDay % 1440) + 1440) % 1440
        return if (sleepStart <= wakeTime) minute in sleepStart..wakeTime
        else minute >= sleepStart || minute <= wakeTime          // window crosses midnight
    }

    private companion object {
        /** RMD-10 — the app spends at most this many prompts a day. */
        const val DAILY_BUDGET = 3
        const val DISMISSAL_HISTORY = 12
        const val DISMISSAL_STREAK = 3
        const val DEFAULT_WAKE_MIN = 6 * 60
    }

    private fun scheduleAt(type: RoutineEventType, severity: ReminderSeverity, at: Instant, now: Long) {
        if (at.toEpochMilli() <= now + 60_000) return   // window already passed — never restore stale (§31)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), firePendingIntent(type, severity))
    }

    private fun firePendingIntent(type: RoutineEventType, severity: ReminderSeverity): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra("eventType", type.name)
            .putExtra("severity", severity.name)
        val code = type.ordinal * 10 + severity.ordinal
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancelAll() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (type in remindable) for (sev in ReminderSeverity.entries) {
            am.cancel(firePendingIntent(type, sev))
        }
    }

    fun cancelFor(type: RoutineEventType) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (sev in ReminderSeverity.entries) am.cancel(firePendingIntent(type, sev))
    }

    /** Called on context transitions: if an expected event just happened, drop its reminders. */
    suspend fun onContextChanged() {
        val app = DhinaSuthraApp.get(context)
        val today = TimeUtils.epochDay()
        app.container.analyticsEngine.dailyRollup(today)
        val done = app.container.db.routineEventDao().forDay(today).map { it.eventType }.toSet()
        for (t in done) if (t in remindable) cancelFor(t)
    }

    fun snooze(type: RoutineEventType, severity: ReminderSeverity, minutes: Int = 15) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + minutes * 60_000L,
            firePendingIntent(type, severity)
        )
    }
}

/** Fires a reminder — after re-verifying it is still relevant (§29 deviation→threshold flow). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra("eventType")?.let { RoutineEventType.valueOf(it) } ?: return
        val severity = intent.getStringExtra("severity")?.let { ReminderSeverity.valueOf(it) } ?: return
        val app = DhinaSuthraApp.get(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!app.container.settings.trackingActive) return@launch
                if (!Permissions.hasNotifications(context)) return@launch
                val today = TimeUtils.epochDay()
                val db = app.container.db
                // Recheck actual state right now.
                app.container.analyticsEngine.dailyRollup(today)
                val done = db.routineEventDao().forDay(today).map { it.eventType }.toSet()
                if (type in done) return@launch
                val deviations = db.reminderDao().deviationsForDay(today)
                if (deviations.any { it.eventType == null || it.eventType == type }) return@launch
                // Cooldown: don't stack the same event's reminders within the rule window.
                val rule = db.reminderDao().rules().firstOrNull { it.eventType == type }
                val recent = db.reminderDao().instancesForDay(today)
                    .filter { it.eventType == type && it.firedAt != null }
                val cooldownMs = (rule?.cooldownMin ?: 90) * 60_000L
                if (recent.any { System.currentTimeMillis() - (it.firedAt ?: 0) < cooldownMs } &&
                    severity != ReminderSeverity.SIGNIFICANT
                ) return@launch

                val dayType = TimeUtils.dayType(today)
                val pattern = db.routinePatternDao().byKey(type, dayType) ?: return@launch
                val id = db.reminderDao().insertInstance(
                    ReminderInstanceEntity(
                        eventType = type, epochDay = today,
                        scheduledFor = System.currentTimeMillis(), firedAt = System.currentTimeMillis(),
                        severity = severity, reason = "p90+tolerance passed",
                        response = ReminderResponse.NONE
                    )
                )
                showNotification(context, id, type, severity, pattern.medianMin)
            } finally {
                pending.finish()
            }
        }
    }

    private fun showNotification(
        context: Context, instanceId: Long,
        type: RoutineEventType, severity: ReminderSeverity, medianMin: Int
    ) {
        val app = DhinaSuthraApp.get(context)
        val privacy = app.container.settings.notificationPrivacy
        val title: String
        val body: String
        if (privacy == NotificationPrivacy.MINIMAL) {
            title = context.getString(R.string.minimal_reminder); body = ""
        } else {
            title = ReminderCopy.title(type, severity)
            body = ReminderCopy.body(type, severity, medianMin)
        }

        fun action(actionName: String, label: String): NotificationCompat.Action {
            val i = Intent(context, ReminderActionReceiver::class.java)
                .putExtra("action", actionName)
                .putExtra("instanceId", instanceId)
                .putExtra("eventType", type.name)
                .putExtra("severity", severity.name)
            val pi = PendingIntent.getBroadcast(
                context, (instanceId * 10 + actionName.hashCode() % 7).toInt(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Action(0, label, pi)
        }

        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, com.dhinasuthra.app.ui.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_thread)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .addAction(action("SNOOZE", "Snooze 15m"))
            .addAction(action("INTENTIONAL", "It's intentional"))
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1000 + type.ordinal, notif)
    }
}

/** Snooze / intentional-deviation / dismiss actions become learning data (§18/§19). */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: return
        val instanceId = intent.getLongExtra("instanceId", -1)
        val type = intent.getStringExtra("eventType")?.let { RoutineEventType.valueOf(it) } ?: return
        val severity = intent.getStringExtra("severity")?.let { ReminderSeverity.valueOf(it) }
            ?: ReminderSeverity.GENTLE
        val app = DhinaSuthraApp.get(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(1000 + type.ordinal)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = app.container.db.reminderDao()
                val instance = dao.instanceById(instanceId)
                when (action) {
                    "SNOOZE" -> {
                        instance?.let { dao.updateInstance(it.copy(response = ReminderResponse.SNOOZED)) }
                        app.container.reminderScheduler.snooze(type, severity)
                    }
                    "INTENTIONAL" -> {
                        instance?.let { dao.updateInstance(it.copy(response = ReminderResponse.INTENTIONAL)) }
                        dao.insertDeviation(
                            IntentionalDeviationEntity(
                                epochDay = TimeUtils.epochDay(), eventType = type,
                                reason = "USER_INTENTIONAL", createdAt = System.currentTimeMillis()
                            )
                        )
                        app.container.reminderScheduler.cancelFor(type)
                    }
                    else -> instance?.let { dao.updateInstance(it.copy(response = ReminderResponse.DISMISSED)) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
