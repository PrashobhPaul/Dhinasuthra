package com.dhinasuthra.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The migration framework (plan §2, §24, §26).
 *
 * The non-negotiable requirement: **installing a new version must never delete,
 * reset, overwrite or silently discard existing user data.** DhinaSuthra is a
 * longitudinal app — a user's history is the product, and it becomes more
 * valuable the older it gets. So schema evolution is treated as a first-class
 * feature rather than a startup detail.
 *
 * Every schema change is declared here as a [DsMigration]:
 *
 *  - `forward` only ever *adds*. No table is dropped, recreated or emptied.
 *  - `validate` runs after the change and must pass before the version is
 *    accepted; a failure aborts the transaction and leaves the old data intact.
 *  - migrations are idempotent (`IF NOT EXISTS` everywhere), so a retry after a
 *    crash mid-upgrade is safe.
 *
 * Adding a migration is the only thing a future schema change needs: append a
 * [DsMigration] to [MigrationRegistry.all] and bump [MigrationRegistry.CURRENT_VERSION].
 *
 * Migrations are written against [MigrationDb] rather than Android's
 * `SupportSQLiteDatabase` so that the upgrade suite can execute the real
 * migration against a real SQLite engine on the JVM — plan §25 asks for upgrade
 * tests from realistic old databases, and a test that can only run on a device
 * is a test that doesn't run.
 */

// ---------------------------------------------------------------------------
// A tiny SQL surface, so migrations are runnable anywhere SQLite is
// ---------------------------------------------------------------------------

interface MigrationRow {
    fun getLong(index: Int): Long
    fun getInt(index: Int): Int
    fun getString(index: Int): String?
}

interface MigrationDb {
    fun exec(sql: String)
    fun exec(sql: String, args: Array<Any?>)
    fun <T> query(sql: String, args: Array<Any?>, read: (MigrationRow) -> T): List<T>
}

fun <T> MigrationDb.query(sql: String, read: (MigrationRow) -> T): List<T> =
    query(sql, emptyArray(), read)

fun MigrationDb.tableExists(name: String): Boolean =
    query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf<Any?>(name)) {
        it.getString(0)
    }.isNotEmpty()

fun MigrationDb.rowCount(table: String): Long =
    if (!tableExists(table)) -1L
    else query("SELECT COUNT(*) FROM `$table`") { it.getLong(0) }.firstOrNull() ?: 0L

fun MigrationDb.columnsOf(table: String): List<String> =
    query("PRAGMA table_info(`$table`)") { it.getString(1) ?: "" }

/** Adapter for the real thing. */
fun SupportSQLiteDatabase.asMigrationDb(): MigrationDb = object : MigrationDb {

    private val target = this@asMigrationDb

    override fun exec(sql: String) = target.execSQL(sql)

    override fun exec(sql: String, args: Array<Any?>) = target.execSQL(sql, args)

    override fun <T> query(sql: String, args: Array<Any?>, read: (MigrationRow) -> T): List<T> {
        val cursor = if (args.isEmpty()) target.query(sql) else target.query(sql, args)
        return cursor.use { c ->
            val row = object : MigrationRow {
                override fun getLong(index: Int) = c.getLong(index)
                override fun getInt(index: Int) = c.getInt(index)
                override fun getString(index: Int): String? =
                    if (c.isNull(index)) null else c.getString(index)
            }
            buildList { while (c.moveToNext()) add(read(row)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Declarations
// ---------------------------------------------------------------------------

data class DsMigration(
    val fromVersion: Int,
    val toVersion: Int,
    val description: String,
    /** Additive DDL. Must be safe to run twice. */
    val forward: (MigrationDb) -> Unit,
    /**
     * Post-migration integrity check. Returning a failure aborts the enclosing
     * transaction, so the database is left exactly as it was.
     */
    val validate: (MigrationDb) -> ValidationResult = { ValidationResult.ok("no checks declared") }
)

data class ValidationResult(val passed: Boolean, val summary: String) {
    companion object {
        fun ok(summary: String) = ValidationResult(true, summary)
        fun failed(summary: String) = ValidationResult(false, summary)
    }
}

class MigrationFailedException(message: String) : IllegalStateException(message)

object MigrationRegistry {

    /**
     * The current schema version. Bump this in the same commit as the migration
     * that reaches it — [validateRegistry] fails the unit suite otherwise.
     */
    const val CURRENT_VERSION = 2

    /**
     * Every table that existed in v1. Each migration's validator asserts they
     * are all still standing afterwards, which is the cheapest possible proof
     * that an upgrade didn't cost anyone their history.
     */
    val V1_TABLES = listOf(
        "places", "raw_sensor_events", "context_events", "routine_events",
        "routine_patterns", "reminder_rules", "reminder_instances",
        "intentional_deviations", "daily_summaries", "app_state"
    )

    /**
     * Migration 002 — activity intelligence.
     *
     * Adds the evidence architecture (plan §3–§5): activity events with a
     * lifecycle and an inference-engine stamp, the evidence rows that justify
     * them, a generic device-signal table for sources beyond the phone's own
     * sensors, and the migration history itself.
     *
     * Nothing existing is touched. Every table in v1 is left exactly as it was.
     */
    private val MIGRATION_1_2 = DsMigration(
        fromVersion = 1,
        toVersion = 2,
        description = "Activity events, evidence, device signals, migration history",
        forward = { db ->
            db.exec(
                "CREATE TABLE IF NOT EXISTS `activity_events` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`epochDay` INTEGER NOT NULL, " +
                    "`activityType` TEXT NOT NULL, " +
                    "`startTime` INTEGER NOT NULL, " +
                    "`endTime` INTEGER, " +
                    "`status` TEXT NOT NULL, " +
                    "`confidence` REAL NOT NULL, " +
                    "`source` TEXT NOT NULL, " +
                    "`placeId` INTEGER, " +
                    "`inferenceEngineVersion` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "`supersedesId` INTEGER, " +
                    "`correctionReason` TEXT)"
            )
            db.exec(
                "CREATE INDEX IF NOT EXISTS `index_activity_events_epochDay` " +
                    "ON `activity_events` (`epochDay`)"
            )

            db.exec(
                "CREATE TABLE IF NOT EXISTS `activity_evidence` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`activityEventId` INTEGER NOT NULL, " +
                    "`signalType` TEXT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, " +
                    "`weight` REAL NOT NULL, " +
                    "`detail` TEXT NOT NULL)"
            )
            db.exec(
                "CREATE INDEX IF NOT EXISTS `index_activity_evidence_activityEventId` " +
                    "ON `activity_evidence` (`activityEventId`)"
            )

            db.exec(
                "CREATE TABLE IF NOT EXISTS `device_signals` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, " +
                    "`signalType` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL, " +
                    "`value` TEXT, " +
                    "`confidence` REAL NOT NULL, " +
                    "`metadata` TEXT)"
            )
            db.exec(
                "CREATE INDEX IF NOT EXISTS `index_device_signals_timestamp` " +
                    "ON `device_signals` (`timestamp`)"
            )

            db.exec(
                "CREATE TABLE IF NOT EXISTS `migration_history` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`fromVersion` INTEGER NOT NULL, " +
                    "`toVersion` INTEGER NOT NULL, " +
                    "`description` TEXT NOT NULL, " +
                    "`startedAt` INTEGER NOT NULL, " +
                    "`completedAt` INTEGER, " +
                    "`succeeded` INTEGER NOT NULL, " +
                    "`validationSummary` TEXT NOT NULL, " +
                    "`backupPath` TEXT)"
            )
        },
        validate = { db ->
            // Plan §26: prove the new structures exist *and* that nothing the user
            // already owned was disturbed on the way.
            val added = listOf("activity_events", "activity_evidence", "device_signals", "migration_history")
            val missing = added.filterNot { db.tableExists(it) }
            val lost = V1_TABLES.filterNot { db.tableExists(it) }
            when {
                missing.isNotEmpty() -> ValidationResult.failed("new tables missing: $missing")
                lost.isNotEmpty() -> ValidationResult.failed("pre-existing tables lost: $lost")
                else -> {
                    val kept = V1_TABLES.joinToString(", ") { "$it=${db.rowCount(it)}" }
                    ValidationResult.ok("added ${added.size} tables; preserved $kept")
                }
            }
        }
    )

    /** Every migration DhinaSuthra has ever shipped, oldest first. */
    val all: List<DsMigration> = listOf(MIGRATION_1_2)

    /**
     * Run one migration and record the attempt. Shared by the Room adapter and
     * by the upgrade tests, so what CI exercises is what ships.
     */
    fun runMigration(spec: DsMigration, db: MigrationDb, now: () -> Long = System::currentTimeMillis): ValidationResult {
        val startedAt = now()
        spec.forward(db)
        val result = spec.validate(db)
        // Written inside the caller's transaction, so a failed validation rolls
        // the DDL and this row back together.
        runCatching { db.recordMigration(spec, result, startedAt, now()) }
        if (!result.passed) {
            throw MigrationFailedException(
                "Migration ${spec.fromVersion}→${spec.toVersion} failed validation: ${result.summary}"
            )
        }
        return result
    }

    /** Room adapters. Each runs inside Room's transaction for that migration step. */
    fun roomMigrations(): Array<Migration> = all.map { spec ->
        object : Migration(spec.fromVersion, spec.toVersion) {
            override fun migrate(db: SupportSQLiteDatabase) {
                runMigration(spec, db.asMigrationDb())
            }
        }
    }.toTypedArray()

    /**
     * The registry must describe an unbroken path from 1 to [CURRENT_VERSION].
     * A gap here would mean some user, somewhere, cannot upgrade — asserted by
     * the unit suite so it can never ship.
     */
    fun validateRegistry(): ValidationResult {
        if (all.isEmpty()) {
            return if (CURRENT_VERSION == 1) ValidationResult.ok("initial schema")
            else ValidationResult.failed("no migrations declared for version $CURRENT_VERSION")
        }
        val sorted = all.sortedBy { it.fromVersion }
        var reached = 1
        for (m in sorted) {
            if (m.fromVersion != reached) {
                return ValidationResult.failed("no migration path from version $reached")
            }
            if (m.toVersion != m.fromVersion + 1) {
                return ValidationResult.failed("migration ${m.fromVersion}→${m.toVersion} must be a single step")
            }
            reached = m.toVersion
        }
        return if (reached != CURRENT_VERSION) {
            ValidationResult.failed("migrations reach version $reached but CURRENT_VERSION is $CURRENT_VERSION")
        } else {
            ValidationResult.ok("continuous path 1 → $CURRENT_VERSION")
        }
    }

    /** The migrations needed to get from [from] to [CURRENT_VERSION], in order. */
    fun pathFrom(from: Int): List<DsMigration> =
        all.sortedBy { it.fromVersion }.filter { it.fromVersion >= from }
}

private fun MigrationDb.recordMigration(
    spec: DsMigration,
    result: ValidationResult,
    startedAt: Long,
    completedAt: Long
) {
    if (!tableExists("migration_history")) return
    exec(
        "INSERT INTO `migration_history` " +
            "(`fromVersion`, `toVersion`, `description`, `startedAt`, `completedAt`, `succeeded`, `validationSummary`, `backupPath`) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        arrayOf<Any?>(
            spec.fromVersion, spec.toVersion, spec.description,
            startedAt, completedAt,
            if (result.passed) 1 else 0, result.summary, null
        )
    )
}
