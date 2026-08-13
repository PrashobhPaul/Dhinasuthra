package com.dhinasuthra.app

import android.app.Application
import android.content.Context
import com.dhinasuthra.app.analytics.AnalyticsEngine
import com.dhinasuthra.app.context.ContextEngine
import com.dhinasuthra.app.context.SleepEstimator
import com.dhinasuthra.app.core.Settings
import com.dhinasuthra.app.core.database.DhinaSuthraDatabase
import com.dhinasuthra.app.core.time.DeviceTimeProvider
import com.dhinasuthra.app.export.DataExporter
import com.dhinasuthra.app.intelligence.CorrectionStore
import com.dhinasuthra.app.intelligence.TimeIntelligenceRepository
import com.dhinasuthra.app.intelligence.TimetableStore
import com.dhinasuthra.app.places.PlaceLearner
import com.dhinasuthra.app.reminders.NotificationChannels
import com.dhinasuthra.app.reminders.ReminderScheduler
import com.dhinasuthra.app.routine.AdherenceEngine
import com.dhinasuthra.app.routine.GreetingEngine
import com.dhinasuthra.app.routine.RoutineLearningEngine
import com.dhinasuthra.app.routine.TimelineEditor
import com.dhinasuthra.app.sensing.LocationProvider
import com.dhinasuthra.app.sensing.activity.ActivityTransitionManager
import com.dhinasuthra.app.sensing.geofence.GeofenceManager
import com.dhinasuthra.app.sensing.SensorCapabilityRegistry
import com.dhinasuthra.app.sensing.SensorPolicyEngine
import com.dhinasuthra.app.work.Workers

/**
 * Manual dependency construction (architecture.md §49): no DI framework —
 * the project doesn't need one yet, and every dependency has to justify itself.
 */
class AppContainer(context: Context) {
    val settings = Settings(context)
    val db = DhinaSuthraDatabase.build(context)
    val timeProvider = DeviceTimeProvider()
    val greetingEngine = GreetingEngine(timeProvider)

    val locationProvider = LocationProvider(context)
    val geofenceManager = GeofenceManager(context)
    val activityTransitionManager = ActivityTransitionManager(context)
    val sensorCapabilityRegistry = SensorCapabilityRegistry(context)
    val sensorPolicyEngine = SensorPolicyEngine(context, geofenceManager, activityTransitionManager)

    val contextEngine = ContextEngine(db.placeDao(), db.contextEventDao(), db.rawSensorEventDao())
    val sleepEstimator = SleepEstimator(db.rawSensorEventDao())
    val placeLearner = PlaceLearner(db.placeDao(), db.contextEventDao())
    val routineLearningEngine = RoutineLearningEngine(
        db.contextEventDao(), db.routineEventDao(), db.routinePatternDao(), sleepEstimator
    )
    val adherenceEngine = AdherenceEngine()
    val analyticsEngine = AnalyticsEngine(
        db.contextEventDao(), db.routineEventDao(), db.routinePatternDao(),
        db.dailySummaryDao(), db.rawSensorEventDao(),
        placeLearner, routineLearningEngine, adherenceEngine
    )
    val reminderScheduler = ReminderScheduler(context)
    val dataExporter = DataExporter(context)

    // V2 intelligence layer: episodes, rules, patterns, routines (spec §34).
    val timetableStore = TimetableStore(db.appStateDao())
    val correctionStore = CorrectionStore(db.appStateDao())
    val timeIntelligence = TimeIntelligenceRepository(db, timetableStore, correctionStore)

    val timelineEditor = TimelineEditor(db.routineEventDao()) { day ->
        // §49A.5/49A.6: edits recalculate the affected day, patterns and — via
        // pattern relearn — downstream adherence. Reminders replan for today.
        analyticsEngine.writeSummary(day)
        routineLearningEngine.relearnPatterns(com.dhinasuthra.app.core.TimeUtils.epochDay())
        analyticsEngine.writeSummary(day)
        timeIntelligence.invalidate()
        if (day == com.dhinasuthra.app.core.TimeUtils.epochDay()) reminderScheduler.planToday()
    }
}

class DhinaSuthraApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationChannels.create(this)
        if (container.settings.onboarded && container.settings.trackingActive) {
            Workers.scheduleAll(this)
        }
    }

    companion object {
        fun get(context: Context): DhinaSuthraApp = context.applicationContext as DhinaSuthraApp
    }
}
