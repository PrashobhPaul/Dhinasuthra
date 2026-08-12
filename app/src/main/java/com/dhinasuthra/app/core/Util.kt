package com.dhinasuthra.app.core

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.dhinasuthra.app.core.model.DayType
import com.dhinasuthra.app.core.model.NotificationPrivacy
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// ---------------------------------------------------------------------------
// Time (product.md §7.6: every inference considers local time context)
// ---------------------------------------------------------------------------

object TimeUtils {
    fun zone(): ZoneId = ZoneId.systemDefault()

    fun epochDay(instant: Instant = Instant.now()): Long =
        instant.atZone(zone()).toLocalDate().toEpochDay()

    fun localDate(epochDay: Long): LocalDate = LocalDate.ofEpochDay(epochDay)

    fun dayStart(epochDay: Long): Instant =
        localDate(epochDay).atStartOfDay(zone()).toInstant()

    fun dayEnd(epochDay: Long): Instant = dayStart(epochDay + 1)

    fun minuteOfDay(instant: Instant): Int {
        val ldt: LocalDateTime = instant.atZone(zone()).toLocalDateTime()
        return ldt.hour * 60 + ldt.minute
    }

    fun dayType(epochDay: Long): DayType {
        val dow = localDate(epochDay).dayOfWeek
        return if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) DayType.WEEKEND else DayType.WEEKDAY
    }

    /** Instant at [minuteOfDay] on [epochDay]; minutes ≥ 1440 spill into the next day. */
    fun instantAt(epochDay: Long, minuteOfDay: Int): Instant =
        dayStart(epochDay).plusSeconds(minuteOfDay * 60L)

    fun formatMinuteOfDay(min: Int): String {
        val m = ((min % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    fun formatDurationMin(min: Int): String {
        val h = min / 60; val m = min % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }
}

// ---------------------------------------------------------------------------
// Privacy-safe logging (architecture.md §35: never log location or personal detail)
// ---------------------------------------------------------------------------

object DsLog {
    private const val TAG = "DhinaSuthra"
    fun d(msg: String) = Log.d(TAG, msg)
    fun w(msg: String) = Log.w(TAG, msg)
    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)
}

// ---------------------------------------------------------------------------
// Permissions (architecture.md §14/§40)
// ---------------------------------------------------------------------------

object Permissions {
    fun hasFineLocation(c: Context) = granted(c, Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasCoarseLocation(c: Context) = granted(c, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun hasAnyLocation(c: Context) = hasFineLocation(c) || hasCoarseLocation(c)

    fun hasBackgroundLocation(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else hasAnyLocation(c)

    fun hasActivityRecognition(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            granted(c, Manifest.permission.ACTIVITY_RECOGNITION)
        else true

    fun hasNotifications(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33)
            granted(c, Manifest.permission.POST_NOTIFICATIONS)
        else true

    private fun granted(c: Context, p: String) =
        ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
}

// ---------------------------------------------------------------------------
// Settings (SharedPreferences; small, local, no cloud — §41)
// ---------------------------------------------------------------------------

class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("dhinasuthra_settings", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    /** Master tracking switch (architecture.md §70). */
    var trackingEnabled: Boolean
        get() = prefs.getBoolean("tracking_enabled", true)
        set(v) = prefs.edit().putBoolean("tracking_enabled", v).apply()

    /** Pause-until timestamp (epoch millis); 0 = not paused. */
    var pausedUntil: Long
        get() = prefs.getLong("paused_until", 0L)
        set(v) = prefs.edit().putLong("paused_until", v).apply()

    val trackingActive: Boolean
        get() = trackingEnabled && (pausedUntil == 0L || System.currentTimeMillis() > pausedUntil)

    var notificationPrivacy: NotificationPrivacy
        get() = NotificationPrivacy.valueOf(
            prefs.getString("notification_privacy", NotificationPrivacy.FULL.name)!!
        )
        set(v) = prefs.edit().putString("notification_privacy", v.name).apply()

    var userName: String
        get() = prefs.getString("user_name", "") ?: ""
        set(v) = prefs.edit().putString("user_name", v).apply()

    /** True while simulated data is loaded (debug builds only). */
    var simulatedDataLoaded: Boolean
        get() = prefs.getBoolean("simulated_loaded", false)
        set(v) = prefs.edit().putBoolean("simulated_loaded", v).apply()

    fun clearAll() = prefs.edit().clear().apply()
}
