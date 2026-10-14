package com.focussounds.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "activities",
    indices = [Index(value = ["builtInKey"], unique = true)],
)
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Stable key for built-in activities (cannot be renamed or deleted); null for custom ones. */
    val builtInKey: String?,
    val createdAtMs: Long,
) {
    val isBuiltIn: Boolean get() = builtInKey != null
}

/**
 * One row per focus session.
 *
 * [activityName] is a snapshot taken at start so history stays readable after the activity is
 * renamed or deleted; [activityId] is set to NULL when a custom activity is deleted.
 * [state] / [endReason] hold the stable keys of SessionRowState / EndReason.
 */
@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("activityId"), Index("startedAtMs"), Index("state")],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val activityId: Long?,
    val activityName: String,
    val soundId: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val plannedDurationMs: Long?,
    /** Time the sound was actually playing; paused time is excluded. */
    val activeDurationMs: Long,
    val state: String,
    val endReason: String?,
    /** Wall time of the last saved progress; the end time used when recovering after process death. */
    val lastCheckpointMs: Long,
)

data class HistoryRow(
    @Embedded val session: SessionEntity,
    /** The activity's current name, or null if it was deleted. */
    val currentActivityName: String?,
)

data class FactRow(
    val id: String,
    val activityId: Long?,
    val activityName: String,
    val currentActivityName: String?,
    val soundId: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val plannedDurationMs: Long?,
    val activeDurationMs: Long,
    val state: String,
    val endReason: String?,
)

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activities ORDER BY builtInKey IS NULL, id")
    fun observeAll(): Flow<List<ActivityEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringExisting(items: List<ActivityEntity>)

    @Insert
    suspend fun insert(item: ActivityEntity): Long

    @Query("SELECT COUNT(*) FROM activities WHERE name = :name COLLATE NOCASE AND id != :excludeId")
    suspend fun countWithName(name: String, excludeId: Long = -1): Int

    @Query("UPDATE activities SET name = :name WHERE id = :id AND builtInKey IS NULL")
    suspend fun renameCustom(id: Long, name: String): Int

    @Query("DELETE FROM activities WHERE id = :id AND builtInKey IS NULL")
    suspend fun deleteCustom(id: Long): Int

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun get(id: Long): ActivityEntity?
}

// The 'active' / 'finished' / 'interrupted' literals are SessionRowState / EndReason keys.
@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: SessionEntity)

    @Query(
        "UPDATE sessions SET activeDurationMs = :activeMs, lastCheckpointMs = :atMs " +
            "WHERE id = :id AND state = 'active'",
    )
    suspend fun checkpoint(id: String, activeMs: Long, atMs: Long): Int

    @Query(
        "UPDATE sessions SET state = 'finished', activeDurationMs = :activeMs, endedAtMs = :endedAtMs, " +
            "endReason = :reason, lastCheckpointMs = :endedAtMs WHERE id = :id AND state = 'active'",
    )
    suspend fun finish(id: String, activeMs: Long, endedAtMs: Long, reason: String): Int

    @Query(
        "UPDATE sessions SET state = 'finished', endReason = 'interrupted', endedAtMs = lastCheckpointMs " +
            "WHERE state = 'active'",
    )
    suspend fun finishOrphans(): Int

    @Query("UPDATE sessions SET activityId = NULL WHERE activityId = :activityId")
    suspend fun detachActivity(activityId: Long): Int

    @Query(
        "SELECT s.*, a.name AS currentActivityName FROM sessions s " +
            "LEFT JOIN activities a ON a.id = s.activityId ORDER BY s.startedAtMs DESC",
    )
    fun observeHistory(): Flow<List<HistoryRow>>

    /** Lightweight projection for analytics/history; includes the activity's current name. */
    @Query(
        "SELECT s.id, s.activityId, s.activityName, a.name AS currentActivityName, s.soundId, s.startedAtMs, " +
            "s.endedAtMs, s.plannedDurationMs, s.activeDurationMs, s.state, s.endReason FROM sessions s " +
            "LEFT JOIN activities a ON a.id = s.activityId ORDER BY s.startedAtMs",
    )
    fun observeFactRows(): Flow<List<FactRow>>

    /** Deletes finished sessions only; a session that is running right now is never touched. */
    @Query("DELETE FROM sessions WHERE state = 'finished'")
    suspend fun deleteFinished(): Int

    /** Deletes one finished session. A running ('active') row is never deleted, even if asked by id. */
    @Query("DELETE FROM sessions WHERE id = :id AND state = 'finished'")
    suspend fun deleteFinishedById(id: String): Int

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY startedAtMs")
    suspend fun all(): List<SessionEntity>
}

@Database(entities = [ActivityEntity::class, SessionEntity::class], version = 1, exportSchema = true)
abstract class FocusDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
    abstract fun sessionDao(): SessionDao

    companion object {
        fun open(context: Context, name: String = "focus.db"): FocusDatabase =
            Room.databaseBuilder(context, FocusDatabase::class.java, name).build()

        fun inMemory(context: Context): FocusDatabase =
            Room.inMemoryDatabaseBuilder(context, FocusDatabase::class.java).build()
    }
}
