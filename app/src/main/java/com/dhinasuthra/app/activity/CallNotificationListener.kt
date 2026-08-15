package com.dhinasuthra.app.activity

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.DsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * WhatsApp and Teams calls (plan §31).
 *
 * These never reach the system call log — Android keeps that for the dialler —
 * so the only way a phone can know an internet call happened is the ongoing-call
 * notification the calling app posts while it is connected. That is a public,
 * user-granted interface, and it is the one used here.
 *
 * ## The line this draws
 *
 * A notification listener *can* see every notification on the device. This one
 * is written so that it doesn't:
 *
 *  - Anything from a package outside [CallApps.WATCHED] returns immediately. Not
 *    filtered downstream — returned from, before a single field is touched.
 *  - Within those packages, only notifications Android has categorised as calls
 *    are considered. Chat messages are ignored.
 *  - Nothing textual is ever read. Not the title, not the body, not the sender.
 *    The only things taken are: which app, whether a call clock is running, and
 *    the time. A WhatsApp call becomes "a WhatsApp call, 24 minutes" — the app
 *    literally cannot say who it was with, because it never looked.
 *  - It stays dormant until the user grants notification access in Android's own
 *    Settings, and stops the moment they revoke it.
 *
 * ## Telling a ringing phone from a conversation
 *
 * Both states post a call notification, so presence alone would count every
 * missed call as a chat. What separates them is the clock: calling apps attach a
 * running chronometer once — and only once — the call actually connects. So the
 * chronometer appearing is the answer, its notification going away is the end,
 * and a call notification that vanishes without one was never picked up. That
 * matches plan §31 exactly: duration runs from answer to end, and a missed call
 * creates nothing at all.
 */
class CallNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Notification keys whose call has connected, and when. */
    private val connected = HashMap<String, Long>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        // A reconnect means the service restarted; any call we were tracking is
        // long over and its end was missed. Starting clean is better than
        // inventing an end time we never observed.
        connected.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val carrier = CallApps.carrierFor(sbn.packageName) ?: return
        if (!isCallNotification(notification)) return

        val key = sbn.key ?: return
        if (connected.containsKey(key)) return          // already counted; this is an update
        if (!hasRunningClock(notification)) return      // still ringing, not yet answered

        val at = System.currentTimeMillis()
        connected[key] = at
        emit(Signal(at, SignalTypes.CALL_ANSWERED, SignalSources.NOTIFICATION, value = carrier))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val carrier = CallApps.carrierFor(sbn.packageName) ?: return
        if (!isCallNotification(notification)) return

        val key = sbn.key ?: return
        val at = System.currentTimeMillis()

        if (connected.remove(key) != null) {
            emit(Signal(at, SignalTypes.CALL_ENDED, SignalSources.NOTIFICATION, value = carrier))
        } else {
            // Rang, then stopped, without the clock ever starting.
            emit(Signal(at, SignalTypes.CALL_MISSED, SignalSources.NOTIFICATION, value = carrier))
        }
    }

    /** Android's own categorisation, plus the call-style template on newer versions. */
    private fun isCallNotification(notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_CALL) return true
        // Newer call notifications use CallStyle, whose template name Android
        // records under this extras key. Read as a plain string rather than via
        // the framework constant, which is not public on every version.
        val template = notification.extras?.getString(EXTRA_TEMPLATE_KEY)
        return template != null && template.endsWith("CallStyle")
    }

    /**
     * A running call timer. Calling apps add it when the call connects, so its
     * presence is the most reliable "they are actually talking" signal available
     * without reading any content.
     */
    private fun hasRunningClock(notification: Notification): Boolean {
        val extras = notification.extras ?: return false
        val shows = extras.getBoolean(Notification.EXTRA_SHOWS_CHRONOMETER, false)
        val countingDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
        // A countdown is a timer running out, not a conversation running on.
        return shows && !countingDown
    }

    private fun emit(signal: Signal) {
        val app = applicationContext as? DhinaSuthraApp ?: return
        scope.launch {
            runCatching { app.container.activityRepository.record(signal) }
                .onFailure { DsLog.w("could not record a call signal") }
        }
    }

    companion object {
        private const val EXTRA_TEMPLATE_KEY = "android.template"

        /** True once the user has granted notification access in Android Settings. */
        fun isEnabled(context: Context): Boolean = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        }.getOrDefault(false)
    }
}

/**
 * The complete list of packages the listener will look at, and what a call from
 * each one means.
 *
 * Kept out of the service class on purpose: this is the privacy boundary, and it
 * should be readable, reviewable and testable without dragging an Android
 * service in behind it. Anything not named here is never examined.
 *
 * Teams maps to a meeting rather than a call, per plan §31 — a Teams call at two
 * in the afternoon is work, and the timeline should say so.
 */
object CallApps {
    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    const val TEAMS = "com.microsoft.teams"
    const val TEAMS_WORK = "com.microsoft.teams.enterprise"

    val WATCHED: Map<String, String> = mapOf(
        WHATSAPP to CallDetector.CARRIER_WHATSAPP,
        WHATSAPP_BUSINESS to CallDetector.CARRIER_WHATSAPP,
        TEAMS to CallDetector.CARRIER_TEAMS,
        TEAMS_WORK to CallDetector.CARRIER_TEAMS
    )

    fun carrierFor(pkg: String?): String? = pkg?.let { WATCHED[it] }
}
