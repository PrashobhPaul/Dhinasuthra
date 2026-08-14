package com.dhinasuthra.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for the activity-intelligence layer (plan §3–§5, §19, §23).
 *
 * Three tables, added by `MIGRATION_1_2` and never rewritten:
 *
 *  - [ActivityEventEntity] — the timeline. Carries a lifecycle status and the
 *    version of the engine that produced it, so a future release can tell its
 *    own guesses apart from the user's confirmed truth and from an older
 *    engine's guesses.
 *  - [ActivityEvidenceEntity] — *why*. Retained even after the user overrules
 *    the conclusion (plan §21: "Do not erase the evidence").
 *  - [DeviceSignalEntity] — immutable raw observations, phone or otherwise.
 *
 * Type columns are `String`, not enums with Room converters. A converter throws
 * on a value it doesn't recognise, which would turn "a newer build wrote a
 * signal type this build has never heard of" into a crash. Strings degrade.
 */

// ---------------------------------------------------------------------------
// Entities — column definitions must match MIGRATION_1_2's DDL exactly
// ---------------------------------------------------------------------------

@Entity(tableName = "activity_events", indices = [Index("epochDay")])
data class ActivityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    /** An [com.dhinasuthra.app.activity.ActivityCatalog] code. */
    val activityType: String,
    val startTime: Long,
    /** Null while the activity is still open. */
    val endTime: Long?,
    /** An [com.dhinasuthra.app.activity.ActivityStatus] code. */
    val status: String,
    val confidence: Float,
    val source: String,
    val placeId: Long?,
    val inferenceEngineVersion: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** The event this one replaced. The original row is kept, not deleted. */
    val supersedesId: Long?,
    val correctionReason: String?
)

@Entity(tableName = "activity_evidence", indices = [Index("activityEventId")])
data class ActivityEvidenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityEventId: Long,
    val signalType: String,
    val timestamp: Long,
    /** The contribution this piece of evidence made, 0..1. */
    val weight: Float,
    /** Plain language, shown to the user verbatim in the "why" view. */
    val detail: String
)

@Entity(tableName = "device_signals", indices = [Index("timestamp")])
data class DeviceSignalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val signalType: String,
    val source: String,
    val value: String?,
    val confidence: Float,
    val metadata: String?
)

@Entity(tableName = "migration_history")
data class MigrationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromVersion: Int,
    val toVersion: Int,
    val description: String,
    val startedAt: Long,
    val completedAt: Long?,
    val succeeded: Boolean,
    val validationSummary: String,
    val backupPath: String?
)

// ---------------------------------------------------------------------------
// DAOs
// ---------------------------------------------------------------------------

@Dao
interface ActivityEventDao {

    @Insert
    suspend fun insert(e: ActivityEventEntity): Long

    @Update
    suspend fun update(e: ActivityEventEntity)

    @Query("SELECT * FROM activity_events WHERE id = :id")
    suspend fun byId(id: Long): ActivityEventEntity?

    @Query("SELECT * FROM activity_events WHERE epochDay = :day ORDER BY startTime")
    suspend fun forDay(day: Long): List<ActivityEventEntity>

    @Query("SELECT * FROM activity_events WHERE epochDay = :day ORDER BY startTime")
    fun observeForDay(day: Long): Flow<List<ActivityEventEntity>>

    @Query("SELECT * FROM activity_events WHERE epochDay BETWEEN :from AND :to ORDER BY startTime")
    suspend fun betweenDays(from: Long, to: Long): List<ActivityEventEntity>

    /** Everything the user has vouched for — the training set for learning (plan §13). */
    @Query(
        "SELECT * FROM activity_events WHERE status IN ('CONFIRMED', 'CORRECTED') " +
            "AND activityType = :type ORDER BY startTime DESC LIMIT :limit"
    )
    suspend fun userTruthFor(type: String, limit: Int): List<ActivityEventEntity>

    /** Provisional events awaiting an answer — what the timeline offers to confirm. */
    @Query(
        "SELECT * FROM activity_events WHERE status = 'INFERRED' AND epochDay >= :since " +
            "ORDER BY startTime DESC LIMIT :limit"
    )
    fun observePending(since: Long, limit: Int): Flow<List<ActivityEventEntity>>

    /**
     * Only ever the engine's own provisional output for that day. Confirmed and
     * corrected events are excluded by the WHERE clause, which is what makes
     * reprocessing safe (plan §22).
     */
    @Query("DELETE FROM activity_events WHERE epochDay = :day AND status = 'INFERRED'")
    suspend fun clearProvisionalForDay(day: Long)

    @Query("SELECT COUNT(*) FROM activity_events")
    suspend fun count(): Long
}

@Dao
interface ActivityEvidenceDao {

    @Insert
    suspend fun insertAll(rows: List<ActivityEvidenceEntity>)

    @Query("SELECT * FROM activity_evidence WHERE activityEventId = :eventId ORDER BY timestamp")
    suspend fun forEvent(eventId: Long): List<ActivityEvidenceEntity>

    @Query("SELECT * FROM activity_evidence WHERE activityEventId = :eventId ORDER BY timestamp")
    fun observeForEvent(eventId: Long): Flow<List<ActivityEvidenceEntity>>

    @Query(
        "DELETE FROM activity_evidence WHERE activityEventId IN " +
            "(SELECT id FROM activity_events WHERE epochDay = :day AND status = 'INFERRED')"
    )
    suspend fun clearProvisionalForDay(day: Long)
}

@Dao
interface DeviceSignalDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(s: DeviceSignalEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<DeviceSignalEntity>)

    @Query("SELECT * FROM device_signals WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun between(from: Long, to: Long): List<DeviceSignalEntity>

    @Query(
        "SELECT * FROM device_signals WHERE signalType = :type AND timestamp BETWEEN :from AND :to " +
            "ORDER BY timestamp"
    )
    suspend fun ofType(type: String, from: Long, to: Long): List<DeviceSignalEntity>

    @Query("SELECT * FROM device_signals ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DeviceSignalEntity>

    /** Raw signals age out like the rest; semantic events do not. */
    @Query("DELETE FROM device_signals WHERE timestamp < :before")
    suspend fun pruneBefore(before: Long)

    @Query("SELECT COUNT(*) FROM device_signals")
    suspend fun count(): Long
}

@Dao
interface MigrationHistoryDao {

    @Query("SELECT * FROM migration_history ORDER BY startedAt DESC")
    suspend fun all(): List<MigrationHistoryEntity>

    @Query("SELECT * FROM migration_history ORDER BY startedAt DESC LIMIT 1")
    suspend fun latest(): MigrationHistoryEntity?

    @Insert
    suspend fun insert(e: MigrationHistoryEntity): Long
}
