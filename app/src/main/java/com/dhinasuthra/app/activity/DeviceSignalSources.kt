package com.dhinasuthra.app.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.dhinasuthra.app.core.database.RawSensorEventEntity
import com.dhinasuthra.app.core.model.ActivityKind
import com.dhinasuthra.app.core.model.SensorSignalType

/**
 * Where signals come from (plan §14, §30 Phase 6).
 *
 * Plan §14 asks for "a generic ExternalDeviceSignal layer" rather than a phone-
 * only pipeline, so a source is just something that can hand over [Signal]s.
 * Adding a wearable, a car, or a speaker later means implementing
 * [DeviceSignalSource] and registering it — no schema change, no orchestrator
 * rewrite, because the storage and the detectors already speak in open strings.
 *
 * Two constraints from plan §29 are structural here, not policy:
 *
 *  - **Only permitted APIs.** Nothing in this file reads another app's private
 *    data. What is not exposed by a public Android API is simply not collected.
 *  - **Opt-in.** [isAvailable] gates every source, so a source whose permission
 *    the user hasn't granted contributes nothing and asks for nothing.
 */
interface DeviceSignalSource {
    val id: String

    /** False when the permission isn't held or the hardware isn't there. */
    fun isAvailable(context: Context): Boolean

    /** Start delivering into [sink]. Must be safe to call twice. */
    fun start(context: Context, sink: SignalSink) = Unit

    fun stop(context: Context) = Unit

    /**
     * Signals this source can hand over on demand, for sources that are read
     * rather than subscribed to — a log the system keeps whether or not the app
     * was running. Called before each recomputation. Must be idempotent: the
     * same call read twice must not become two calls.
     */
    suspend fun poll(context: Context): List<Signal> = emptyList()
}

/** Where a source hands its observations. Implemented by the repository. */
fun interface SignalSink {
    fun emit(signal: Signal)
}

/**
 * The phone's own screen and power transitions (plan §30 Phase 3).
 *
 * These have to be registered at runtime: Android has refused manifest-declared
 * `SCREEN_ON`/`SCREEN_OFF` receivers since API 8, and the app declines to hold a
 * foreground service to keep them alive. So live transitions are best-effort
 * while the process lives, and [SignalBridge] fills the gaps from the
 * fifteen-minute samples that keep being recorded regardless.
 */
class PhoneStateSource : DeviceSignalSource {

    override val id = "phone-state"

    private var receiver: BroadcastReceiver? = null

    override fun isAvailable(context: Context) = true

    override fun start(context: Context, sink: SignalSink) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val now = System.currentTimeMillis()
                val type = when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> SignalTypes.SCREEN_ON
                    Intent.ACTION_SCREEN_OFF -> SignalTypes.SCREEN_OFF
                    // Unlocking is a much stronger claim than the screen lighting
                    // up: it means a person, not a notification.
                    Intent.ACTION_USER_PRESENT -> SignalTypes.USER_PRESENT
                    Intent.ACTION_POWER_CONNECTED -> SignalTypes.CHARGER_CONNECTED
                    Intent.ACTION_POWER_DISCONNECTED -> SignalTypes.CHARGER_DISCONNECTED
                    else -> return
                }
                sink.emit(Signal(timestamp = now, type = type, source = SignalSources.PHONE))
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        // Every action above is a protected system broadcast, so NOT_EXPORTED is
        // both correct and required by API 34+.
        ContextCompat.registerReceiver(
            context.applicationContext, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiver = r
    }

    override fun stop(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
    }
}

/** The sources this release ships with, in registration order. */
class DeviceSignalSourceRegistry(private val sources: List<DeviceSignalSource>) {

    operator fun plus(source: DeviceSignalSource) = DeviceSignalSourceRegistry(sources + source)

    fun startAll(context: Context, sink: SignalSink) {
        sources.filter { it.isAvailable(context) }.forEach {
            runCatching { it.start(context, sink) }
        }
    }

    fun stopAll(context: Context) {
        sources.forEach { runCatching { it.stop(context) } }
    }

    /** Everything the readable sources can offer right now. */
    suspend fun pollAll(context: Context): List<Signal> =
        sources.filter { it.isAvailable(context) }
            .flatMap { runCatching { it.poll(context) }.getOrDefault(emptyList()) }

    companion object {
        fun default() = DeviceSignalSourceRegistry(listOf(PhoneStateSource(), CallLogSource()))
    }
}

/**
 * Turns the sensor history the app has *already collected* into signals the new
 * engine understands.
 *
 * This is what makes plan §22's reprocessing real rather than theoretical. An
 * existing user has months of `raw_sensor_events` — fifteen-minute screen and
 * charging samples, geofence crossings, activity transitions. None of it was
 * written with an awakening state machine in mind, and all of it can still feed
 * one. Without this bridge, "the new engine reprocesses your history" would only
 * mean "from the day you installed the update", which is exactly the kind of
 * quiet reset plan §2 exists to prevent.
 */
object SignalBridge {

    /**
     * Convert one legacy row. Returns nothing for rows that carry no usable
     * signal rather than inventing one.
     */
    fun from(event: RawSensorEventEntity, previous: RawSensorEventEntity?): List<Signal> {
        val at = event.timestamp
        return when (event.type) {
            SensorSignalType.SCREEN_SAMPLE -> buildList {
                // A sample is a level, not an edge. An edge is only claimed when
                // the previous sample disagreed — otherwise every fifteen minutes
                // would look like a fresh unlock.
                val was = previous?.interactive
                val isOn = event.interactive == true
                if (isOn && was != true) add(Signal(at, SignalTypes.SCREEN_ON, SignalSources.PHONE))
                if (isOn) add(Signal(at, SignalTypes.SCREEN_ACTIVE, SignalSources.PHONE))
                if (!isOn && was == true) add(Signal(at, SignalTypes.SCREEN_OFF, SignalSources.PHONE))
            }

            SensorSignalType.CHARGING_SAMPLE -> buildList {
                val was = previous?.charging
                val isCharging = event.charging == true
                if (isCharging && was == false) add(Signal(at, SignalTypes.CHARGER_CONNECTED, SignalSources.PHONE))
                if (!isCharging && was == true) add(Signal(at, SignalTypes.CHARGER_DISCONNECTED, SignalSources.PHONE))
            }

            SensorSignalType.ACTIVITY_ENTER -> when (event.activity) {
                ActivityKind.WALKING -> listOf(Signal(at, SignalTypes.WALKING, SignalSources.ACTIVITY_RECOGNITION))
                ActivityKind.RUNNING -> listOf(Signal(at, SignalTypes.RUNNING, SignalSources.ACTIVITY_RECOGNITION))
                ActivityKind.IN_VEHICLE -> listOf(Signal(at, SignalTypes.IN_VEHICLE, SignalSources.ACTIVITY_RECOGNITION))
                ActivityKind.ON_BICYCLE -> listOf(Signal(at, SignalTypes.MOTION, SignalSources.ACTIVITY_RECOGNITION))
                ActivityKind.STILL -> listOf(Signal(at, SignalTypes.STILL, SignalSources.ACTIVITY_RECOGNITION))
                else -> emptyList()
            }

            SensorSignalType.ACTIVITY_EXIT -> when (event.activity) {
                // Leaving STILL is movement, and it's often the only thing the
                // phone notices at the moment somebody gets out of bed.
                ActivityKind.STILL -> listOf(Signal(at, SignalTypes.MOTION, SignalSources.ACTIVITY_RECOGNITION))
                else -> emptyList()
            }

            SensorSignalType.GEOFENCE_ENTER ->
                listOf(Signal(at, SignalTypes.ARRIVED, SignalSources.LOCATION, value = event.placeId?.toString()))

            SensorSignalType.GEOFENCE_EXIT ->
                listOf(Signal(at, SignalTypes.DEPARTED, SignalSources.LOCATION, value = event.placeId?.toString()))

            SensorSignalType.LOCATION ->
                listOf(Signal(at, SignalTypes.LOCATION_SAMPLE, SignalSources.LOCATION))

            else -> emptyList()
        }
    }

    /** Convert a whole run of legacy rows, keeping them in time order. */
    fun fromAll(events: List<RawSensorEventEntity>): List<Signal> {
        val ordered = events.sortedBy { it.timestamp }
        // Level→edge conversion needs the previous sample *of the same kind*,
        // not merely the previous row.
        val lastOfType = mutableMapOf<SensorSignalType, RawSensorEventEntity>()
        return ordered.flatMap { e ->
            val signals = from(e, lastOfType[e.type])
            lastOfType[e.type] = e
            signals
        }
    }
}

/**
 * Phone calls, read from the log Android already keeps (plan §31).
 *
 * Calls are the clearest signal a phone has about a person's day — "I rang my
 * wife at eleven" is a fact, not an inference — and the app was silent about
 * them. This reads the system call log, which means calls that happened while
 * the app wasn't running are picked up too.
 *
 * Deliberate limits, so the app never claims more than it can see:
 *
 *  - **Opt-in.** Without `READ_CALL_LOG` granted, [isAvailable] is false and
 *    nothing here runs or asks.
 *  - **Connected calls only.** A call with zero duration was never answered, so
 *    it becomes a `CALL_MISSED` marker and no activity — plan §31 is explicit
 *    that a missed call must not produce a duration.
 *  - **Cellular only, honestly labelled.** WhatsApp and Teams calls do not
 *    appear in the system call log unless those apps register a connection
 *    service, and most don't. Rather than mislabel a cellular call as WhatsApp,
 *    everything from here says "phone".
 *  - **Nothing leaves the device.** The contact name is carried so the timeline
 *    can say who, and the app has no INTERNET permission to send it anywhere.
 */
class CallLogSource : DeviceSignalSource {

    override val id = "call-log"

    override fun isAvailable(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    override suspend fun poll(context: Context): List<Signal> {
        val since = System.currentTimeMillis() - LOOKBACK_MS
        val out = mutableListOf<Signal>()
        val projection = arrayOf(
            android.provider.CallLog.Calls.TYPE,
            android.provider.CallLog.Calls.DATE,
            android.provider.CallLog.Calls.DURATION,
            android.provider.CallLog.Calls.CACHED_NAME
        )
        context.contentResolver.query(
            android.provider.CallLog.Calls.CONTENT_URI,
            projection,
            "${android.provider.CallLog.Calls.DATE} >= ?",
            arrayOf(since.toString()),
            "${android.provider.CallLog.Calls.DATE} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val type = c.getInt(0)
                val startedAt = c.getLong(1)
                val seconds = c.getLong(2)
                val who = if (c.isNull(3)) null else c.getString(3)

                val connected = seconds > 0 && (
                    type == android.provider.CallLog.Calls.INCOMING_TYPE ||
                        type == android.provider.CallLog.Calls.OUTGOING_TYPE
                    )

                if (!connected) {
                    out.add(
                        Signal(
                            timestamp = startedAt,
                            type = SignalTypes.CALL_MISSED,
                            source = SignalSources.TELEPHONY,
                            value = CallDetector.CARRIER_CELLULAR,
                            metadata = who
                        )
                    )
                    continue
                }

                // The log records when the call started and how long it was
                // connected, so answer-to-end is exactly what gets stored.
                out.add(
                    Signal(
                        timestamp = startedAt,
                        type = SignalTypes.CALL_ANSWERED,
                        source = SignalSources.TELEPHONY,
                        value = CallDetector.CARRIER_CELLULAR,
                        metadata = who
                    )
                )
                out.add(
                    Signal(
                        timestamp = startedAt + seconds * 1000L,
                        type = SignalTypes.CALL_ENDED,
                        source = SignalSources.TELEPHONY,
                        value = CallDetector.CARRIER_CELLULAR,
                        metadata = who
                    )
                )
            }
        }
        return out
    }

    private companion object {
        /** Enough to backfill a fortnight the first time permission is granted. */
        const val LOOKBACK_MS = 14L * 24 * 60 * 60 * 1000
    }
}
