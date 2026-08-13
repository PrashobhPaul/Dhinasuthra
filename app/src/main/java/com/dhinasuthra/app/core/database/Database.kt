package com.dhinasuthra.app.core.database

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import com.dhinasuthra.app.core.model.ActivityKind
import com.dhinasuthra.app.core.model.ContextType
import com.dhinasuthra.app.core.model.DayType
import com.dhinasuthra.app.core.model.EventSource
import com.dhinasuthra.app.core.model.PlaceCategory
import com.dhinasuthra.app.core.model.ReminderResponse
import com.dhinasuthra.app.core.model.ReminderSeverity
import com.dhinasuthra.app.core.model.RoutineEventType
import com.dhinasuthra.app.core.model.SensorSignalType
import kotlinx.coroutines.flow.Flow

/**
 * Local-first storage (architecture.md §19). Raw signals are short-lived; semantic
 * events and learned patterns are long-lived (§73). schemaVersion via Room version.
 */

// ---------------------------------------------------------------------------
// Entities
// ---------------------------------------------------------------------------

@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: PlaceCategory,
    val lat: Double,
    val lon: Double,
    val radiusM: Float,
    val confidence: Float,
    val firstSeen: Long,
    val lastSeen: Long,
    val visitCount: Int,
    /** True once the user confirms the label; unconfirmed places are candidates. */
    val confirmed: Boolean,
    /** Distinct days on which this place was visited (visit rules, arch §25). */
    val visitDays: Int = 0
)

@Entity(tableName = "raw_sensor_events")
data class RawSensorEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: SensorSignalType,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Float? = null,
    val placeId: Long? = null,
    val activity: ActivityKind? = null,
    val interactive: Boolean? = null,
    val charging: Boolean? = null,
    val source: EventSource = EventSource.SENSOR
)

@Entity(tableName = "context_events")
data class ContextEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long?,          // null while the segment is open
    val contextType: ContextType,
    val placeId: Long? = null,
    val activity: ActivityKind? = null,
    val confidence: Float,
    val centroidLat: Double? = null,
    val centroidLon: Double? = null,
    val source: EventSource = EventSource.SENSOR
)

@Entity(tableName = "routine_events")
data class RoutineEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val eventType: RoutineEventType,
    val timestamp: Long,
    val durationMin: Int? = null,
    val placeId: Long? = null,
    val confidence: Float,
    val source: EventSource
)

@Entity(tableName = "routine_patterns", primaryKeys = ["eventType", "dayType"])
data class RoutinePatternEntity(
    val eventType: RoutineEventType,
    val dayType: DayType,
    /** Minutes-of-day, normalized for cross-midnight events (see RoutineStats). */
    val medianMin: Int,
    val p10: Int,
    val p25: Int,
    val p75: Int,
    val p90: Int,
    val typicalDurationMin: Int? = null,
    val observationCount: Int,
    val confidence: Float,
    val updatedAt: Long,
    /** User-defined routine template (§49C): a prior, never presented as observed. */
    val priorMin: Int? = null,
    val priorSource: String? = null
)

@Entity(tableName = "reminder_rules")
data class ReminderRuleEntity(
    @PrimaryKey val eventType: RoutineEventType,
    val enabled: Boolean,
    val cooldownMin: Int,
    val userConfigured: Boolean
)

@Entity(tableName = "reminder_instances")
data class ReminderInstanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventType: RoutineEventType,
    val epochDay: Long,
    val scheduledFor: Long,
    val firedAt: Long?,
    val severity: ReminderSeverity,
    val reason: String,
    val response: ReminderResponse
)

@Entity(tableName = "intentional_deviations")
data class IntentionalDeviationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val eventType: RoutineEventType?,   // null = whole day (holiday, travel day…)
    val reason: String,
    val createdAt: Long
)

@Entity(tableName = "daily_summaries")
data class DailySummaryEntity(
    @PrimaryKey val epochDay: Long,
    val homeMin: Int,
    val workMin: Int,
    val travelMin: Int,
    val sleepMin: Int,
    val socialMin: Int,
    val otherMin: Int,
    val unknownMin: Int,
    val routineMatch: Int?,      // 0..100, null while the day is too young to score
    val wakeMinOfDay: Int?,
    val sleepStartMinOfDay: Int?,
    val computedAt: Long
)

@Entity(tableName = "app_state")
data class AppStateEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "value") val value: String
)

// ---------------------------------------------------------------------------
// DAOs
// ---------------------------------------------------------------------------

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places ORDER BY visitCount DESC")
    fun observeAll(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places")
    suspend fun all(): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun byId(id: Long): PlaceEntity?

    @Query("SELECT * FROM places WHERE confirmed = 1")
    suspend fun confirmed(): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE category = :cat AND confirmed = 1 LIMIT 1")
    suspend fun confirmedByCategory(cat: PlaceCategory): PlaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(place: PlaceEntity): Long

    @Update
    suspend fun update(place: PlaceEntity)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RawSensorEventDao {
    @Insert
    suspend fun insert(e: RawSensorEventEntity): Long

    @Query("SELECT * FROM raw_sensor_events WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun between(from: Long, to: Long): List<RawSensorEventEntity>

    @Query("DELETE FROM raw_sensor_events WHERE timestamp < :before")
    suspend fun pruneBefore(before: Long)

    @Query("SELECT COUNT(*) FROM raw_sensor_events")
    suspend fun count(): Long
}

@Dao
interface ContextEventDao {
    @Insert
    suspend fun insert(e: ContextEventEntity): Long

    @Update
    suspend fun update(e: ContextEventEntity)

    @Query("SELECT * FROM context_events WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun openEvent(): ContextEventEntity?

    @Query("SELECT * FROM context_events WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    fun observeOpenEvent(): Flow<ContextEventEntity?>

    @Query("SELECT * FROM context_events WHERE startTime < :to AND (endTime IS NULL OR endTime > :from) ORDER BY startTime")
    suspend fun overlapping(from: Long, to: Long): List<ContextEventEntity>

    @Query("SELECT * FROM context_events WHERE startTime < :to AND (endTime IS NULL OR endTime > :from) ORDER BY startTime")
    fun observeOverlapping(from: Long, to: Long): Flow<List<ContextEventEntity>>

    @Query("DELETE FROM context_events WHERE startTime < :before AND endTime IS NOT NULL")
    suspend fun pruneBefore(before: Long)
}

@Dao
interface RoutineEventDao {
    @Insert
    suspend fun insert(e: RoutineEventEntity): Long

    @Query("SELECT * FROM routine_events WHERE epochDay = :day ORDER BY timestamp")
    suspend fun forDay(day: Long): List<RoutineEventEntity>

    @Query("SELECT * FROM routine_events WHERE epochDay = :day ORDER BY timestamp")
    fun observeForDay(day: Long): Flow<List<RoutineEventEntity>>

    @Query("SELECT * FROM routine_events WHERE epochDay BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun betweenDays(from: Long, to: Long): List<RoutineEventEntity>

    @Query("DELETE FROM routine_events WHERE epochDay = :day AND source != 'USER'")
    suspend fun clearInferredForDay(day: Long)

    @Query("DELETE FROM routine_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(DISTINCT epochDay) FROM routine_events")
    suspend fun observedDayCount(): Int
}

@Dao
interface RoutinePatternDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: RoutinePatternEntity)

    @Query("SELECT * FROM routine_patterns")
    suspend fun all(): List<RoutinePatternEntity>

    @Query("SELECT * FROM routine_patterns")
    fun observeAll(): Flow<List<RoutinePatternEntity>>

    @Query("SELECT * FROM routine_patterns WHERE eventType = :type AND dayType = :dayType")
    suspend fun byKey(type: RoutineEventType, dayType: DayType): RoutinePatternEntity?

    @Query("DELETE FROM routine_patterns")
    suspend fun clear()
}

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(r: ReminderRuleEntity)

    @Query("SELECT * FROM reminder_rules")
    suspend fun rules(): List<ReminderRuleEntity>

    @Query("SELECT * FROM reminder_rules")
    fun observeRules(): Flow<List<ReminderRuleEntity>>

    @Insert
    suspend fun insertInstance(i: ReminderInstanceEntity): Long

    @Update
    suspend fun updateInstance(i: ReminderInstanceEntity)

    @Query("SELECT * FROM reminder_instances WHERE id = :id")
    suspend fun instanceById(id: Long): ReminderInstanceEntity?

    @Query("SELECT * FROM reminder_instances WHERE epochDay = :day")
    suspend fun instancesForDay(day: Long): List<ReminderInstanceEntity>

    /** Recent history for one event type — drives cooldown and dismissal suppression. */
    @Query("SELECT * FROM reminder_instances WHERE eventType = :type ORDER BY scheduledFor DESC LIMIT :limit")
    suspend fun recentFor(type: RoutineEventType, limit: Int): List<ReminderInstanceEntity>

    @Query("SELECT * FROM reminder_instances ORDER BY scheduledFor DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ReminderInstanceEntity>>

    @Insert
    suspend fun insertDeviation(d: IntentionalDeviationEntity)

    @Query("SELECT * FROM intentional_deviations WHERE epochDay = :day")
    suspend fun deviationsForDay(day: Long): List<IntentionalDeviationEntity>
}

@Dao
interface DailySummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: DailySummaryEntity)

    @Query("SELECT * FROM daily_summaries WHERE epochDay = :day")
    suspend fun forDay(day: Long): DailySummaryEntity?

    @Query("SELECT * FROM daily_summaries WHERE epochDay = :day")
    fun observeForDay(day: Long): Flow<DailySummaryEntity?>

    @Query("SELECT * FROM daily_summaries WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay")
    suspend fun betweenDays(from: Long, to: Long): List<DailySummaryEntity>

    @Query("SELECT * FROM daily_summaries WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay")
    fun observeBetweenDays(from: Long, to: Long): Flow<List<DailySummaryEntity>>
}

@Dao
interface AppStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(s: AppStateEntity)

    @Query("SELECT `value` FROM app_state WHERE `key` = :key")
    suspend fun get(key: String): String?
}

// ---------------------------------------------------------------------------
// Converters + Database
// ---------------------------------------------------------------------------

class Converters {
    @TypeConverter fun placeCategoryToString(v: PlaceCategory?): String? = v?.name
    @TypeConverter fun stringToPlaceCategory(v: String?): PlaceCategory? = v?.let { PlaceCategory.valueOf(it) }
    @TypeConverter fun contextTypeToString(v: ContextType?): String? = v?.name
    @TypeConverter fun stringToContextType(v: String?): ContextType? = v?.let { ContextType.valueOf(it) }
    @TypeConverter fun activityToString(v: ActivityKind?): String? = v?.name
    @TypeConverter fun stringToActivity(v: String?): ActivityKind? = v?.let { ActivityKind.valueOf(it) }
    @TypeConverter fun routineTypeToString(v: RoutineEventType?): String? = v?.name
    @TypeConverter fun stringToRoutineType(v: String?): RoutineEventType? = v?.let { RoutineEventType.valueOf(it) }
    @TypeConverter fun dayTypeToString(v: DayType?): String? = v?.name
    @TypeConverter fun stringToDayType(v: String?): DayType? = v?.let { DayType.valueOf(it) }
    @TypeConverter fun signalToString(v: SensorSignalType?): String? = v?.name
    @TypeConverter fun stringToSignal(v: String?): SensorSignalType? = v?.let { SensorSignalType.valueOf(it) }
    @TypeConverter fun sourceToString(v: EventSource?): String? = v?.name
    @TypeConverter fun stringToSource(v: String?): EventSource? = v?.let { EventSource.valueOf(it) }
    @TypeConverter fun severityToString(v: ReminderSeverity?): String? = v?.name
    @TypeConverter fun stringToSeverity(v: String?): ReminderSeverity? = v?.let { ReminderSeverity.valueOf(it) }
    @TypeConverter fun responseToString(v: ReminderResponse?): String? = v?.name
    @TypeConverter fun stringToResponse(v: String?): ReminderResponse? = v?.let { ReminderResponse.valueOf(it) }
}

@Database(
    entities = [
        PlaceEntity::class, RawSensorEventEntity::class, ContextEventEntity::class,
        RoutineEventEntity::class, RoutinePatternEntity::class, ReminderRuleEntity::class,
        ReminderInstanceEntity::class, IntentionalDeviationEntity::class,
        DailySummaryEntity::class, AppStateEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class DhinaSuthraDatabase : RoomDatabase() {
    abstract fun placeDao(): PlaceDao
    abstract fun rawSensorEventDao(): RawSensorEventDao
    abstract fun contextEventDao(): ContextEventDao
    abstract fun routineEventDao(): RoutineEventDao
    abstract fun routinePatternDao(): RoutinePatternDao
    abstract fun reminderDao(): ReminderDao
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun appStateDao(): AppStateDao

    companion object {
        fun build(context: Context): DhinaSuthraDatabase =
            Room.databaseBuilder(context, DhinaSuthraDatabase::class.java, "dhinasuthra.db")
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
