package com.dhinasuthra.app.database

import com.dhinasuthra.app.core.database.ActivityEventEntity
import com.dhinasuthra.app.core.database.ActivityEvidenceEntity
import com.dhinasuthra.app.core.database.DeviceSignalEntity
import com.dhinasuthra.app.core.database.MigrationHistoryEntity
import com.dhinasuthra.app.core.database.MigrationRegistry
import com.dhinasuthra.app.core.database.rowCount
import com.dhinasuthra.app.core.database.tableExists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upgrade suite (plan §25, §26).
 *
 * These tests exist to make one promise enforceable: **an app update cannot cost
 * the user their history.** They run the shipping migration code against a real
 * SQLite engine, starting from a realistic four-month-old v1 database.
 */
class MigrationTest {

    private fun migrateToLatest(db: JdbcMigrationDb) {
        MigrationRegistry.pathFrom(db.userVersion).forEach { spec ->
            MigrationRegistry.runMigration(spec, db)
            db.userVersion = spec.toVersion
        }
    }

    // -- the registry itself ------------------------------------------------

    @Test
    fun `registry describes an unbroken path to the current version`() {
        val result = MigrationRegistry.validateRegistry()
        assertTrue(result.summary, result.passed)
    }

    @Test
    fun `every migration is a single version step with a description`() {
        MigrationRegistry.all.forEach { m ->
            assertEquals(m.fromVersion + 1, m.toVersion)
            assertTrue("migration ${m.fromVersion} has no description", m.description.isNotBlank())
        }
    }

    // -- the upgrade --------------------------------------------------------

    @Test
    fun `upgrading a four-month-old database preserves every row`() {
        V1Fixture.build(days = 120).use { db ->
            val before = MigrationRegistry.V1_TABLES.associateWith { db.rowCount(it) }
            // Sanity: the fixture must actually contain data, or this proves nothing.
            assertTrue("fixture is empty", before.values.all { it > 0 })

            migrateToLatest(db)

            MigrationRegistry.V1_TABLES.forEach { table ->
                assertEquals("row count changed for $table", before[table], db.rowCount(table))
            }
            assertEquals(MigrationRegistry.CURRENT_VERSION, db.userVersion)
        }
    }

    @Test
    fun `user-corrected history survives the upgrade byte for byte`() {
        V1Fixture.build(days = 30).use { db ->
            val readCorrections = {
                db.query("SELECT epochDay, eventType, timestamp, durationMin FROM routine_events WHERE source = 'USER' ORDER BY timestamp", emptyArray()) {
                    listOf(it.getLong(0), it.getString(1), it.getLong(2), it.getInt(3)).joinToString("|")
                }
            }
            val readState = {
                db.query("SELECT `key`, `value` FROM app_state ORDER BY `key`", emptyArray()) {
                    "${it.getString(0)}=${it.getString(1)}"
                }
            }
            val corrections = readCorrections()
            val state = readState()
            assertTrue(corrections.isNotEmpty())
            assertTrue(state.any { it.startsWith("corrections.") })

            migrateToLatest(db)

            assertEquals(corrections, readCorrections())
            assertEquals(state, readState())
        }
    }

    @Test
    fun `learned patterns and places survive the upgrade`() {
        V1Fixture.build(days = 30).use { db ->
            val patterns = db.query(
                "SELECT eventType, dayType, medianMin, observationCount, priorMin FROM routine_patterns ORDER BY eventType",
                emptyArray()
            ) { "${it.getString(0)}/${it.getString(1)}/${it.getInt(2)}/${it.getInt(3)}/${it.getString(4)}" }
            val places = db.query("SELECT name, category, visitCount FROM places ORDER BY id", emptyArray()) {
                "${it.getString(0)}/${it.getString(1)}/${it.getInt(2)}"
            }

            migrateToLatest(db)

            assertEquals(
                patterns,
                db.query(
                    "SELECT eventType, dayType, medianMin, observationCount, priorMin FROM routine_patterns ORDER BY eventType",
                    emptyArray()
                ) { "${it.getString(0)}/${it.getString(1)}/${it.getInt(2)}/${it.getInt(3)}/${it.getString(4)}" }
            )
            assertEquals(
                places,
                db.query("SELECT name, category, visitCount FROM places ORDER BY id", emptyArray()) {
                    "${it.getString(0)}/${it.getString(1)}/${it.getInt(2)}"
                }
            )
        }
    }

    @Test
    fun `upgrade adds the activity intelligence tables and their indexes`() {
        V1Fixture.build(days = 10).use { db ->
            migrateToLatest(db)

            listOf("activity_events", "activity_evidence", "device_signals", "migration_history")
                .forEach { assertTrue("$it missing", db.tableExists(it)) }

            assertTrue(db.indexNames("activity_events").contains("index_activity_events_epochDay"))
            assertTrue(db.indexNames("activity_evidence").contains("index_activity_evidence_activityEventId"))
            assertTrue(db.indexNames("device_signals").contains("index_device_signals_timestamp"))
        }
    }

    @Test
    fun `migration records its own history with a passing validation`() {
        V1Fixture.build(days = 10).use { db ->
            migrateToLatest(db)
            val rows = db.query(
                "SELECT fromVersion, toVersion, succeeded, validationSummary FROM migration_history",
                emptyArray()
            ) { listOf(it.getInt(0), it.getInt(1), it.getInt(2), it.getString(3)) }

            assertEquals(1, rows.size)
            assertEquals(listOf(1, 2, 1), rows[0].take(3))
            val summary = rows[0][3] as String
            assertTrue(summary, summary.contains("preserved"))
        }
    }

    @Test
    fun `migrations are idempotent so an interrupted upgrade can be retried`() {
        V1Fixture.build(days = 10).use { db ->
            migrateToLatest(db)
            val counts = (MigrationRegistry.V1_TABLES + "activity_events").associateWith { db.rowCount(it) }

            // Simulate a process death after the DDL but before the version bump:
            // the same migration runs a second time on an already-migrated file.
            db.userVersion = 1
            migrateToLatest(db)

            counts.forEach { (table, n) -> assertEquals(table, n, db.rowCount(table)) }
            assertEquals(MigrationRegistry.CURRENT_VERSION, db.userVersion)
        }
    }

    @Test
    fun `a fresh install reaches the same schema as an upgraded one`() {
        val upgraded = V1Fixture.build(days = 5).also { migrateToLatest(it) }
        val fresh = JdbcMigrationDb.inMemory().also { db ->
            V1Fixture.DDL.forEach { db.exec(it) }
            migrateToLatest(db)
        }
        upgraded.use { u ->
            fresh.use { f ->
                assertEquals(u.tableNames() - "sqlite_sequence", f.tableNames() - "sqlite_sequence")
                (u.tableNames() - "sqlite_sequence").forEach { t ->
                    assertEquals("columns differ for $t", u.columnTypes(t), f.columnTypes(t))
                }
            }
        }
    }

    @Test
    fun `a failing validation is reported rather than silently accepted`() {
        // A migration whose validator refuses: the framework must throw, which is
        // what lets Room roll the transaction back and the guardian restore.
        val spec = com.dhinasuthra.app.core.database.DsMigration(
            fromVersion = 1, toVersion = 2, description = "deliberately broken",
            forward = { it.exec("CREATE TABLE IF NOT EXISTS `scratch` (`id` INTEGER)") },
            validate = { com.dhinasuthra.app.core.database.ValidationResult.failed("nope") }
        )
        V1Fixture.build(days = 2).use { db ->
            val thrown = runCatching { MigrationRegistry.runMigration(spec, db) }.exceptionOrNull()
            assertNotNull("a failed validation must throw", thrown)
            assertTrue(thrown is com.dhinasuthra.app.core.database.MigrationFailedException)
        }
    }

    // -- schema conformance -------------------------------------------------

    /**
     * The migration DDL and the Room entities are written by hand in two
     * different files. If they ever disagree, Room refuses to open the database
     * on the user's phone — a crash loop on an app whose whole promise is that
     * updates are safe. This asserts they agree, in CI, before that can happen.
     */
    @Test
    fun `migration DDL matches the Room entity declarations`() {
        V1Fixture.build(days = 2).use { db ->
            migrateToLatest(db)

            assertEntityMatches(db, "activity_events", ActivityEventEntity::class.java)
            assertEntityMatches(db, "activity_evidence", ActivityEvidenceEntity::class.java)
            assertEntityMatches(db, "device_signals", DeviceSignalEntity::class.java)
            assertEntityMatches(db, "migration_history", MigrationHistoryEntity::class.java)
        }
    }

    private fun assertEntityMatches(db: JdbcMigrationDb, table: String, entity: Class<*>) {
        val fields = entity.declaredFields.filterNot { it.isSynthetic }
        val declared = fields.map { it.name }.toSet()
        val actual = db.columnTypes(table).keys
        assertEquals("column names differ for $table", declared, actual)

        val types = db.columnTypes(table)
        val notNull = db.notNullColumns(table)
        fields.forEach { f ->
            val expectedAffinity = when (f.type) {
                java.lang.Long.TYPE, java.lang.Long::class.java,
                java.lang.Integer.TYPE, java.lang.Integer::class.java,
                java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> "INTEGER"
                java.lang.Float.TYPE, java.lang.Float::class.java,
                java.lang.Double.TYPE, java.lang.Double::class.java -> "REAL"
                else -> "TEXT"
            }
            assertEquals("$table.${f.name} affinity", expectedAffinity, types[f.name])

            // Kotlin compiles a non-null `Long` to a primitive and a nullable
            // `Long?` to the boxed type, so primitives prove NOT NULL is required.
            if (f.type.isPrimitive) {
                assertTrue("$table.${f.name} must be NOT NULL", notNull.contains(f.name))
            }
        }
        assertFalse("$table has no columns", actual.isEmpty())
    }
}
