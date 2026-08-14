package com.dhinasuthra.app.database

/**
 * A realistic version-1 database — the thing an existing user actually has on
 * their phone before installing the next release.
 *
 * Plan §25 is explicit that testing only "fresh install → latest" is not enough,
 * because real users arrive carrying months of history. So the upgrade suite
 * starts here: the v1 DDL exactly as Room generated it, populated with data of
 * every kind the app collects — places, raw signals, context segments, routine
 * events, learned patterns, reminder history, user-declared deviations, daily
 * rollups and the key/value state that holds timetables and corrections.
 */
object V1Fixture {

    val DDL = listOf(
        "CREATE TABLE IF NOT EXISTS `places` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
            "`category` TEXT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, " +
            "`radiusM` REAL NOT NULL, `confidence` REAL NOT NULL, `firstSeen` INTEGER NOT NULL, " +
            "`lastSeen` INTEGER NOT NULL, `visitCount` INTEGER NOT NULL, `confirmed` INTEGER NOT NULL, " +
            "`visitDays` INTEGER NOT NULL)",

        "CREATE TABLE IF NOT EXISTS `raw_sensor_events` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, " +
            "`type` TEXT NOT NULL, `lat` REAL, `lon` REAL, `accuracyM` REAL, `placeId` INTEGER, " +
            "`activity` TEXT, `interactive` INTEGER, `charging` INTEGER, `source` TEXT NOT NULL)",

        "CREATE TABLE IF NOT EXISTS `context_events` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startTime` INTEGER NOT NULL, " +
            "`endTime` INTEGER, `contextType` TEXT NOT NULL, `placeId` INTEGER, `activity` TEXT, " +
            "`confidence` REAL NOT NULL, `centroidLat` REAL, `centroidLon` REAL, `source` TEXT NOT NULL)",

        "CREATE TABLE IF NOT EXISTS `routine_events` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `epochDay` INTEGER NOT NULL, " +
            "`eventType` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `durationMin` INTEGER, " +
            "`placeId` INTEGER, `confidence` REAL NOT NULL, `source` TEXT NOT NULL)",

        "CREATE TABLE IF NOT EXISTS `routine_patterns` (" +
            "`eventType` TEXT NOT NULL, `dayType` TEXT NOT NULL, `medianMin` INTEGER NOT NULL, " +
            "`p10` INTEGER NOT NULL, `p25` INTEGER NOT NULL, `p75` INTEGER NOT NULL, " +
            "`p90` INTEGER NOT NULL, `typicalDurationMin` INTEGER, `observationCount` INTEGER NOT NULL, " +
            "`confidence` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, `priorMin` INTEGER, " +
            "`priorSource` TEXT, PRIMARY KEY(`eventType`, `dayType`))",

        "CREATE TABLE IF NOT EXISTS `reminder_rules` (" +
            "`eventType` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `cooldownMin` INTEGER NOT NULL, " +
            "`userConfigured` INTEGER NOT NULL, PRIMARY KEY(`eventType`))",

        "CREATE TABLE IF NOT EXISTS `reminder_instances` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `eventType` TEXT NOT NULL, " +
            "`epochDay` INTEGER NOT NULL, `scheduledFor` INTEGER NOT NULL, `firedAt` INTEGER, " +
            "`severity` TEXT NOT NULL, `reason` TEXT NOT NULL, `response` TEXT NOT NULL)",

        "CREATE TABLE IF NOT EXISTS `intentional_deviations` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `epochDay` INTEGER NOT NULL, " +
            "`eventType` TEXT, `reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",

        "CREATE TABLE IF NOT EXISTS `daily_summaries` (" +
            "`epochDay` INTEGER NOT NULL, `homeMin` INTEGER NOT NULL, `workMin` INTEGER NOT NULL, " +
            "`travelMin` INTEGER NOT NULL, `sleepMin` INTEGER NOT NULL, `socialMin` INTEGER NOT NULL, " +
            "`otherMin` INTEGER NOT NULL, `unknownMin` INTEGER NOT NULL, `routineMatch` INTEGER, " +
            "`wakeMinOfDay` INTEGER, `sleepStartMinOfDay` INTEGER, `computedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`epochDay`))",

        "CREATE TABLE IF NOT EXISTS `app_state` (" +
            "`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))"
    )

    private const val DAY_MS = 86_400_000L

    /** Roughly four months of history, spread over 120 days. */
    fun populate(db: JdbcMigrationDb, days: Int = 120) {
        val base = 20_000L                    // epoch day; the exact value doesn't matter
        val baseMs = base * DAY_MS

        db.exec(
            "INSERT INTO places (name, category, lat, lon, radiusM, confidence, firstSeen, lastSeen, visitCount, confirmed, visitDays) " +
                "VALUES ('Home', 'HOME', 9.9312, 76.2673, 80.0, 0.97, $baseMs, ${baseMs + days * DAY_MS}, 240, 1, $days)"
        )
        db.exec(
            "INSERT INTO places (name, category, lat, lon, radiusM, confidence, firstSeen, lastSeen, visitCount, confirmed, visitDays) " +
                "VALUES ('Office', 'OFFICE', 9.9816, 76.2999, 120.0, 0.93, $baseMs, ${baseMs + days * DAY_MS}, 88, 1, 88)"
        )

        for (d in 0 until days) {
            val dayStart = baseMs + d * DAY_MS
            val epochDay = base + d

            db.exec(
                "INSERT INTO raw_sensor_events (timestamp, type, lat, lon, accuracyM, placeId, activity, interactive, charging, source) " +
                    "VALUES (${dayStart + 7 * 3_600_000}, 'SCREEN_SAMPLE', NULL, NULL, NULL, NULL, NULL, 1, 1, 'SENSOR')"
            )
            db.exec(
                "INSERT INTO raw_sensor_events (timestamp, type, lat, lon, accuracyM, placeId, activity, interactive, charging, source) " +
                    "VALUES (${dayStart + 9 * 3_600_000}, 'GEOFENCE_EXIT', 9.9312, 76.2673, 22.0, 1, 'WALKING', 0, 0, 'SENSOR')"
            )
            db.exec(
                "INSERT INTO context_events (startTime, endTime, contextType, placeId, activity, confidence, centroidLat, centroidLon, source) " +
                    "VALUES ($dayStart, ${dayStart + 8 * 3_600_000}, 'HOME', 1, 'STILL', 0.94, 9.9312, 76.2673, 'SENSOR')"
            )
            db.exec(
                "INSERT INTO routine_events (epochDay, eventType, timestamp, durationMin, placeId, confidence, source) " +
                    "VALUES ($epochDay, 'WAKE', ${dayStart + 8 * 3_600_000}, NULL, 1, 0.81, 'SENSOR')"
            )
            db.exec(
                "INSERT INTO daily_summaries (epochDay, homeMin, workMin, travelMin, sleepMin, socialMin, otherMin, unknownMin, routineMatch, wakeMinOfDay, sleepStartMinOfDay, computedAt) " +
                    "VALUES ($epochDay, 620, 480, 90, 430, 30, 60, 160, 78, 480, 1400, ${dayStart + DAY_MS})"
            )
        }

        // A hand-corrected event: exactly the kind of user truth an upgrade must not lose.
        db.exec(
            "INSERT INTO routine_events (epochDay, eventType, timestamp, durationMin, placeId, confidence, source) " +
                "VALUES ($base, 'LUNCH', ${baseMs + 13 * 3_600_000}, 42, 2, 1.0, 'USER')"
        )

        db.exec(
            "INSERT INTO routine_patterns (eventType, dayType, medianMin, p10, p25, p75, p90, typicalDurationMin, observationCount, confidence, updatedAt, priorMin, priorSource) " +
                "VALUES ('WAKE', 'WEEKDAY', 480, 455, 468, 494, 512, NULL, 64, 0.88, $baseMs, NULL, NULL)"
        )
        db.exec(
            "INSERT INTO routine_patterns (eventType, dayType, medianMin, p10, p25, p75, p90, typicalDurationMin, observationCount, confidence, updatedAt, priorMin, priorSource) " +
                "VALUES ('LUNCH', 'WEEKDAY', 787, 770, 779, 796, 810, 42, 51, 0.79, $baseMs, 780, 'USER_TEMPLATE')"
        )

        db.exec("INSERT INTO reminder_rules (eventType, enabled, cooldownMin, userConfigured) VALUES ('LUNCH', 1, 90, 1)")
        db.exec(
            "INSERT INTO reminder_instances (eventType, epochDay, scheduledFor, firedAt, severity, reason, response) " +
                "VALUES ('LUNCH', $base, ${baseMs + 13 * 3_600_000}, ${baseMs + 13 * 3_600_000}, 'GENTLE', 'later than usual', 'DISMISSED')"
        )
        db.exec(
            "INSERT INTO intentional_deviations (epochDay, eventType, reason, createdAt) " +
                "VALUES ($base, NULL, 'travelling', $baseMs)"
        )

        db.exec("INSERT INTO app_state (`key`, `value`) VALUES ('timetable.default', 'WAKE|480|20;WORK|540|30')")
        db.exec("INSERT INTO app_state (`key`, `value`) VALUES ('corrections.$base', 'LUNCH|765|810|USER')")
        db.exec("INSERT INTO app_state (`key`, `value`) VALUES ('onboarded', 'true')")
    }

    /** Build a populated v1 database, stamped with SQLite's own version marker. */
    fun build(days: Int = 120): JdbcMigrationDb = JdbcMigrationDb.inMemory().apply {
        DDL.forEach { exec(it) }
        populate(this, days)
        userVersion = 1
    }
}
