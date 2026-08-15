package com.dhinasuthra.app.activity

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * What was on the screen, from Android's usage-access API (plan §14, §16).
 *
 * This is how TV watching becomes visible to a phone. Android will not let one
 * app see another app's internals, but with usage access granted it will say
 * which app was in the foreground and for how long — and a Google TV remote
 * open for forty minutes on the sofa is a much better account of an evening
 * than silence.
 *
 * Usage access is a **special** permission: it is not granted by a dialog, it is
 * granted by the user in Android's own Settings screen, one app at a time. That
 * makes it the right place to draw the line — [isAvailable] returns false until
 * the user has gone and done it, and until then nothing here reads anything.
 *
 * What it deliberately does *not* do:
 *
 *  - It reads only the packages in [AppSignalMap]. Everything else the user ran
 *    is skipped at the point of reading, not filtered later, so browsing and
 *    banking apps never enter the app's data at all.
 *  - It records that an app was open, never what happened inside it. Usage
 *    access cannot see content, and this does not try.
 */
class UsageStatsSource(
    private val apps: AppSignalMap = AppSignalMap.DEFAULT,
    /** How far back to read on each poll. Android keeps only about a week. */
    private val lookBackMs: Long = 3L * 24 * 60 * 60 * 1000,
    /** Shorter than this in the foreground is a glance, not a session. */
    private val minSessionMin: Int = 2
) : DeviceSignalSource {

    override val id = "usage-stats"

    override fun isAvailable(context: Context): Boolean = hasUsageAccess(context)

    override suspend fun poll(context: Context): List<Signal> {
        if (!hasUsageAccess(context)) return emptyList()
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val now = System.currentTimeMillis()
        val events = runCatching { manager.queryEvents(now - lookBackMs, now) }.getOrNull()
            ?: return emptyList()

        val out = mutableListOf<Signal>()
        val openedAt = HashMap<String, Long>()
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val role = apps.roleOf(pkg) ?: continue      // not a package we care about

            when (event.eventType) {
                // MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND rather than the API 29
                // ACTIVITY_RESUMED / ACTIVITY_PAUSED names: same values, and this
                // app supports Android 8.
                UsageEvents.Event.MOVE_TO_FOREGROUND ->
                    openedAt.putIfAbsent(pkg, event.timeStamp)

                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val start = openedAt.remove(pkg) ?: continue
                    out += sessionSignals(role, pkg, start, event.timeStamp)
                }
            }
        }

        // Sessions still in the foreground are deliberately left out. Their
        // length changes every time we look, so emitting one would either be
        // recorded at its first observed length forever or pile up a new row per
        // poll. It gets picked up whole on the first poll after it ends.
        return out.sortedBy { it.timestamp }
    }

    /**
     * One foreground session becomes a start marker, a repeat marker and an end
     * marker. [TvDetector] groups those the same way it groups real remote
     * button presses, so a session read from usage stats and a session read from
     * a remote integration are interchangeable evidence.
     */
    private fun sessionSignals(
        role: AppSignalMap.Role,
        pkg: String,
        start: Long,
        end: Long
    ): List<Signal> {
        val minutes = ((end - start) / 60_000L).toInt()
        if (minutes < minSessionMin) return emptyList()
        val label = apps.labelOf(pkg)

        return when (role) {
            AppSignalMap.Role.TV_REMOTE -> listOf(
                Signal(start, SignalTypes.TV_REMOTE_INTERACTION, SignalSources.USAGE_STATS, pkg, metadata = label),
                Signal(end, SignalTypes.TV_NAVIGATION, SignalSources.USAGE_STATS, pkg, metadata = label)
            )
            AppSignalMap.Role.MEDIA -> listOf(
                Signal(
                    start, SignalTypes.MEDIA_APP_ACTIVE, SignalSources.USAGE_STATS,
                    value = minutes.toString(), metadata = label
                ),
                Signal(end, SignalTypes.APP_ACTIVE, SignalSources.USAGE_STATS, pkg, metadata = label)
            )
        }
    }

    companion object {

        /** True once the user has granted usage access to this app in Settings. */
        fun hasUsageAccess(context: Context): Boolean = runCatching {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            val mode = ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            // DEFAULT means "fall back to the manifest permission", which for a
            // special permission means checking it properly rather than assuming.
            if (mode == AppOpsManager.MODE_DEFAULT) {
                context.checkCallingOrSelfPermission(
                    "android.permission.PACKAGE_USAGE_STATS"
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                mode == AppOpsManager.MODE_ALLOWED
            }
        }.getOrDefault(false)
    }
}

/**
 * Which apps mean something, and what.
 *
 * A map rather than a chain of conditionals, so supporting another remote or
 * another streaming service is one line and no logic. Unlisted packages are
 * invisible to this app by construction.
 */
class AppSignalMap(private val entries: Map<String, Entry>) {

    enum class Role {
        /** Controls a television. Its use is evidence of watching one. */
        TV_REMOTE,

        /** Plays video. Its use is evidence of watching something. */
        MEDIA
    }

    data class Entry(val role: Role, val label: String)

    fun roleOf(pkg: String): Role? = entries[pkg]?.role

    fun labelOf(pkg: String): String? = entries[pkg]?.label

    val packages: Set<String> get() = entries.keys

    operator fun plus(pair: Pair<String, Entry>) = AppSignalMap(entries + pair)

    companion object {
        val DEFAULT = AppSignalMap(
            mapOf(
                // -- remotes: the phone driving a television --------------------
                "com.google.android.videos" to Entry(Role.TV_REMOTE, "Google TV"),
                "com.google.android.apps.googletv" to Entry(Role.TV_REMOTE, "Google TV"),
                "com.google.android.tv.remote" to Entry(Role.TV_REMOTE, "Android TV Remote"),
                "com.google.android.tv.remote.service" to Entry(Role.TV_REMOTE, "Android TV Remote"),
                "com.google.android.apps.chromecast.app" to Entry(Role.TV_REMOTE, "Google Home"),
                "com.samsung.smartviewad" to Entry(Role.TV_REMOTE, "Samsung SmartThings"),
                "com.samsung.android.oneconnect" to Entry(Role.TV_REMOTE, "SmartThings"),
                "com.lgeha.nuts" to Entry(Role.TV_REMOTE, "LG ThinQ"),
                "com.mi.android.globalminusscreen" to Entry(Role.TV_REMOTE, "Mi Remote"),
                "com.duokan.phone.remotecontroller" to Entry(Role.TV_REMOTE, "Mi Remote"),
                "com.roku.remote" to Entry(Role.TV_REMOTE, "Roku"),
                "com.amazon.storm.lightning.client.aosp" to Entry(Role.TV_REMOTE, "Fire TV"),

                // -- media: watching something, wherever it's playing -----------
                "com.netflix.mediaclient" to Entry(Role.MEDIA, "Netflix"),
                "com.google.android.youtube" to Entry(Role.MEDIA, "YouTube"),
                "com.amazon.avod.thirdpartyclient" to Entry(Role.MEDIA, "Prime Video"),
                "in.startv.hotstar" to Entry(Role.MEDIA, "Hotstar"),
                "com.jio.jioplay.tv" to Entry(Role.MEDIA, "JioTV"),
                "com.jiocinema" to Entry(Role.MEDIA, "JioCinema"),
                "com.sonyliv" to Entry(Role.MEDIA, "SonyLIV"),
                "com.disney.disneyplus" to Entry(Role.MEDIA, "Disney+")
            )
        )
    }
}
